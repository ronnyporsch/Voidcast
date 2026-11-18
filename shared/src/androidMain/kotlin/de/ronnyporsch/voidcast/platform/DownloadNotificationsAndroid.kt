package de.ronnyporsch.voidcast.platform

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Whether the app asked for the notification permission before, see [canShowPermissionPopup]. */
object NotificationPermission {
    private const val PREFS_NAME = "permissions"
    private const val KEY_ASKED = "askedForNotifications"

    /** The permission exists from Android 13; before, notifications are allowed unless turned off in the settings. */
    val isRuntimePermission: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun wasAsked(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putBoolean(KEY_ASKED, true) }
    }

    fun isGranted(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Whether requesting the permission shows the popup: never asked, or declined once. After declining twice the
     * system doesn't show it anymore, then only the settings help.
     */
    fun canShowPermissionPopup(activity: Activity): Boolean = isRuntimePermission &&
        (!wasAsked(activity) || activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
}

@Composable
actual fun rememberDownloadNotifications(): DownloadNotifications? {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return null
    var enabled by remember { mutableStateOf(NotificationPermission.isGranted(context)) }
    // Also after coming back from the system settings
    LifecycleResumeEffect(Unit) {
        enabled = NotificationPermission.isGranted(context)
        onPauseOrDispose {}
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        enabled = granted
    }
    return remember(activity, enabled) {
        object : DownloadNotifications {
            override val enabled = enabled

            override fun change() {
                // Decided before asking: someone who just declined shouldn't be sent to the settings right away
                if (!enabled && NotificationPermission.canShowPermissionPopup(activity)) {
                    NotificationPermission.markAsked(activity)
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    activity.openNotificationSettings()
                }
            }
        }
    }
}

private fun Activity.openNotificationSettings() {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    }
    startActivity(intent)
}
