package de.ronnyporsch.voidcast.platform

import de.ronnyporsch.voidcast.domain.model.AppSettings
import de.ronnyporsch.voidcast.domain.model.PlaybackSettings
import de.ronnyporsch.voidcast.domain.model.RepeatMode
import de.ronnyporsch.voidcast.domain.model.ServerType
import java.util.prefs.Preferences

private val prefs = Preferences.userNodeForPackage(AppSettings::class.java)

actual fun saveSettings(settings: AppSettings) {
    prefs.put("serverType", settings.serverType.name)
    prefs.put("serverUrl", settings.serverUrl)
    prefs.put("username", settings.username)
    prefs.put("password", settings.password)
    prefs.putBoolean("allowUntrustedCertificate", settings.allowUntrustedCertificate)
    prefs.putBoolean("gaplessEnabled", settings.playback.gaplessEnabled)
    prefs.putBoolean("crossfadeEnabled", settings.playback.crossfadeEnabled)
    prefs.putInt("crossfadeDurationSeconds", settings.playback.crossfadeDurationSeconds)
    prefs.putBoolean("shuffle", settings.playback.shuffle)
    prefs.put("repeatMode", settings.playback.repeatMode.name)
    prefs.put("jellyfinDeviceId", settings.jellyfinDeviceId)
    prefs.put("libraryView", settings.libraryView.encode())
    prefs.flush()
}

actual fun loadSettings(): AppSettings? {
    val serverUrl = prefs.get("serverUrl", "") ?: ""
    if (serverUrl.isBlank()) return null
    return AppSettings(
        serverType = ServerType.entries.firstOrNull { it.name == prefs.get("serverType", "") } ?: ServerType.Subsonic,
        serverUrl = serverUrl,
        username = prefs.get("username", "") ?: "",
        password = prefs.get("password", "") ?: "",
        allowUntrustedCertificate = prefs.getBoolean("allowUntrustedCertificate", false),
        playback = PlaybackSettings(
            gaplessEnabled = prefs.getBoolean("gaplessEnabled", false),
            crossfadeEnabled = prefs.getBoolean("crossfadeEnabled", false),
            crossfadeDurationSeconds = prefs.getInt("crossfadeDurationSeconds", 5),
            shuffle = prefs.getBoolean("shuffle", false),
            repeatMode = RepeatMode.entries.firstOrNull { it.name == prefs.get("repeatMode", "") } ?: RepeatMode.Off,
        ),
        jellyfinDeviceId = prefs.get("jellyfinDeviceId", "") ?: "",
        libraryView = decodeLibraryView(prefs.get("libraryView", null)),
    )
}
