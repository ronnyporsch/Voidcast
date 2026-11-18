package de.ronnyporsch.voidcast

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.ronnyporsch.voidcast.platform.AppContextHolder
import de.ronnyporsch.voidcast.platform.NotificationPermission
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Downloads work without it, the notification showing their progress just stays hidden
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        AppContextHolder.init(this)
        askForNotificationsOnFirstDownload()

        setContent {
            App()
        }
    }

    /** Asks once, when the user first downloads something, so the request comes with an obvious reason. */
    private fun askForNotificationsOnFirstDownload() {
        if (!NotificationPermission.isRuntimePermission || NotificationPermission.wasAsked(this)) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppSession.get().connection.downloads.requested.collect {
                    // Also asked in the meantime from the settings
                    if (NotificationPermission.wasAsked(this@MainActivity)) return@collect
                    NotificationPermission.markAsked(this@MainActivity)
                    if (!NotificationPermission.isGranted(this@MainActivity)) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
    }
}
