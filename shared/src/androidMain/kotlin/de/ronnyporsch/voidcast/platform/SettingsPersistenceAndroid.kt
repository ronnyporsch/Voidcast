package de.ronnyporsch.voidcast.platform

import android.content.Context
import de.ronnyporsch.voidcast.domain.model.AppSettings
import de.ronnyporsch.voidcast.domain.model.PlaybackSettings
import de.ronnyporsch.voidcast.domain.model.ServerType

private val prefs by lazy {
    AppContextHolder.context.getSharedPreferences("voidcast_settings", Context.MODE_PRIVATE)
}

actual fun saveSettings(settings: AppSettings) {
    prefs.edit()
        .putString("serverType", settings.serverType.name)
        .putString("serverUrl", settings.serverUrl)
        .putString("username", settings.username)
        .putString("password", settings.password)
        .putBoolean("allowUntrustedCertificate", settings.allowUntrustedCertificate)
        .putBoolean("gaplessEnabled", settings.playback.gaplessEnabled)
        .putBoolean("crossfadeEnabled", settings.playback.crossfadeEnabled)
        .putInt("crossfadeDurationSeconds", settings.playback.crossfadeDurationSeconds)
        .putString("jellyfinDeviceId", settings.jellyfinDeviceId)
        .putString("libraryView", settings.libraryView.encode())
        .apply()
}

actual fun loadSettings(): AppSettings? {
    val p = prefs
    val serverUrl = p.getString("serverUrl", "") ?: ""
    if (serverUrl.isBlank()) return null
    return AppSettings(
        serverType = ServerType.entries.firstOrNull { it.name == p.getString("serverType", "") } ?: ServerType.Subsonic,
        serverUrl = serverUrl,
        username = p.getString("username", "") ?: "",
        password = p.getString("password", "") ?: "",
        allowUntrustedCertificate = p.getBoolean("allowUntrustedCertificate", false),
        playback = PlaybackSettings(
            gaplessEnabled = p.getBoolean("gaplessEnabled", false),
            crossfadeEnabled = p.getBoolean("crossfadeEnabled", false),
            crossfadeDurationSeconds = p.getInt("crossfadeDurationSeconds", 5),
        ),
        jellyfinDeviceId = p.getString("jellyfinDeviceId", "") ?: "",
        libraryView = decodeLibraryView(p.getString("libraryView", null)),
    )
}
