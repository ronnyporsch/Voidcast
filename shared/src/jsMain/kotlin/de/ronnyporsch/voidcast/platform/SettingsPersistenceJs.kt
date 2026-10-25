package de.ronnyporsch.voidcast.platform

import de.ronnyporsch.voidcast.domain.model.AppSettings
import de.ronnyporsch.voidcast.domain.model.PlaybackSettings
import de.ronnyporsch.voidcast.domain.model.ServerType
import kotlinx.browser.window

actual fun saveSettings(settings: AppSettings) {
    val storage = window.localStorage
    storage.setItem("serverType", settings.serverType.name)
    storage.setItem("serverUrl", settings.serverUrl)
    storage.setItem("username", settings.username)
    storage.setItem("password", settings.password)
    storage.setItem("allowUntrustedCertificate", settings.allowUntrustedCertificate.toString())
    storage.setItem("gaplessEnabled", settings.playback.gaplessEnabled.toString())
    storage.setItem("crossfadeEnabled", settings.playback.crossfadeEnabled.toString())
    storage.setItem("crossfadeDurationSeconds", settings.playback.crossfadeDurationSeconds.toString())
    storage.setItem("jellyfinDeviceId", settings.jellyfinDeviceId)
    storage.setItem("libraryView", settings.libraryView.encode())
}

actual fun loadSettings(): AppSettings? {
    val storage = window.localStorage
    val serverUrl = storage.getItem("serverUrl") ?: return null
    if (serverUrl.isBlank()) return null
    return AppSettings(
        serverType = ServerType.entries.firstOrNull { it.name == storage.getItem("serverType") } ?: ServerType.Subsonic,
        serverUrl = serverUrl,
        username = storage.getItem("username") ?: "",
        password = storage.getItem("password") ?: "",
        allowUntrustedCertificate = storage.getItem("allowUntrustedCertificate")?.toBooleanStrictOrNull() ?: false,
        playback = PlaybackSettings(
            gaplessEnabled = storage.getItem("gaplessEnabled")?.toBooleanStrictOrNull() ?: false,
            crossfadeEnabled = storage.getItem("crossfadeEnabled")?.toBooleanStrictOrNull() ?: false,
            crossfadeDurationSeconds = storage.getItem("crossfadeDurationSeconds")?.toIntOrNull() ?: 5,
        ),
        jellyfinDeviceId = storage.getItem("jellyfinDeviceId") ?: "",
        libraryView = decodeLibraryView(storage.getItem("libraryView")),
    )
}
