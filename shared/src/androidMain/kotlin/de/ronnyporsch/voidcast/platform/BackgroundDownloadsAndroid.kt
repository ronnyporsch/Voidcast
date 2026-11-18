package de.ronnyporsch.voidcast.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.ronnyporsch.voidcast.AppSession
import de.ronnyporsch.voidcast.AutoLoginStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val WORK_NAME = "downloads"
// Changing the constraints needs new tags, or work enqueued with the old ones would be kept
private const val TAG_WIFI_ONLY = "network:wifi"
private const val TAG_ANY_NETWORK = "network:any"

private val enqueueScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

actual fun keepDownloadsRunning(wifiOnly: Boolean) {
    val workManager = WorkManager.getInstance(AppContextHolder.context)
    val tag = if (wifiOnly) TAG_WIFI_ONLY else TAG_ANY_NETWORK
    enqueueScope.launch {
        // Already there with the right network: keeps it, or a running worker would be stopped for nothing
        val current = workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).first().filterNot { it.state.isFinished }
        if (current.any { tag in it.tags }) return@launch
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints(wifiOnly))
            .addTag(tag)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}

private fun constraints(wifiOnly: Boolean): Constraints {
    if (!wifiOnly) return Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        // Close enough before Android 9, which can't require wifi or ethernet themselves
        return Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
    }
    // What isOnWifi counts as wifi, so the worker runs exactly when the downloads may. A VPN is fine: its network
    // carries the transport of the one underneath, and the app's default network is the VPN while one is up
    val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
        .build()
    return Constraints.Builder().setRequiredNetworkRequest(request, NetworkType.UNMETERED).build()
}

/**
 * Runs the downloads in a foreground service (with a notification), so the system doesn't freeze or kill the app
 * while they are pending. If it does anyway, WorkManager starts the app again later and this resumes them; the
 * network constraint holds it back until wifi is there if the user only downloads over wifi.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = coroutineScope {
        AppContextHolder.init(applicationContext)
        val app = withContext(Dispatchers.Main) { AppSession.get() }
        if (app.settingsHolder.settings.value.offlineMode) return@coroutineScope Result.success()

        val foreground = try {
            setForeground(foregroundInfo(remaining = 0, total = 0))
            true
        } catch (_: IllegalStateException) {
            // Android 12+ doesn't let apps in the background start foreground services; then this runs as a regular
            // worker, which the system gives about 10 minutes before it stops it and WorkManager tries again later
            false
        }

        // After the system killed the app the new session connects first, which already starts the syncs
        val status = withTimeoutOrNull(CONNECT_TIMEOUT) {
            app.autoLoginStatus.first {
                it == AutoLoginStatus.Connected || it == AutoLoginStatus.None || it is AutoLoginStatus.Rejected
            }
        } ?: return@coroutineScope Result.retry()
        if (status != AutoLoginStatus.Connected) return@coroutineScope Result.success()

        val downloads = app.connection.downloads
        val progress = if (foreground) launch {
            // Of this run; grows when songs get added meanwhile, so the bar doesn't jump back
            var total = 0
            var lastRemaining = 0
            downloads.pendingSongCount.collect { remaining ->
                if (remaining > lastRemaining) total += remaining - lastRemaining
                lastRemaining = remaining
                try {
                    setForeground(foregroundInfo(remaining, total))
                } catch (_: IllegalStateException) {
                }
            }
        } else null
        downloads.syncAllAndAwait()
        progress?.cancel()
        Result.success()
    }

    /** Without [total] songs known yet, the progress bar shows that something is going on. */
    private fun foregroundInfo(remaining: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown while songs are being downloaded" }
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading songs")
            .apply { if (remaining > 0) setContentText(if (remaining == 1) "1 song left" else "$remaining songs left") }
            .setProgress(total, total - remaining, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 0x0D0A
        const val CONNECT_TIMEOUT = 5 * 60 * 1000L
    }
}
