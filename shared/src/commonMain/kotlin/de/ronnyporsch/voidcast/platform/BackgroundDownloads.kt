package de.ronnyporsch.voidcast.platform

/**
 * Call when downloads are pending: keeps them going while the app is in the background, and resumes them if the
 * system killed the app, as far as the platform needs that. With [wifiOnly] that only happens over wifi.
 */
expect fun keepDownloadsRunning(wifiOnly: Boolean)
