package de.ronnyporsch.voidcast.platform

import androidx.compose.runtime.Composable

/** The notification showing the progress of downloads, which the user has to allow on Android. */
interface DownloadNotifications {
    /** Whether the notification shows. */
    val enabled: Boolean

    /** Asks the user to allow the notification, or opens the system settings where they can turn it on or off. */
    fun change()
}

/** Null on platforms without such a notification. */
@Composable
expect fun rememberDownloadNotifications(): DownloadNotifications?
