package de.ronnyporsch.voidcast.domain.model

import de.ronnyporsch.voidcast.platform.loadSettings
import de.ronnyporsch.voidcast.platform.saveSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class PlaybackSettings(
    val gaplessEnabled: Boolean = false,
    val crossfadeEnabled: Boolean = false,
    val crossfadeDurationSeconds: Int = 5,
    /** Highest video resolution (height in pixels) to stream, e.g. 1080 for 1080p. */
    val videoQuality: Int = DEFAULT_VIDEO_QUALITY,
    /** The player's shuffle and repeat mode, kept across app restarts. */
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.Off,
)

const val DEFAULT_VIDEO_QUALITY = 1080

/** Offered while the heights a video is available in aren't known yet. */
val STANDARD_VIDEO_QUALITIES = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)

enum class ServerType(val displayName: String) {
    Jellyfin("Jellyfin"),
    Subsonic("Navidrome / Subsonic"),
}

@Serializable
data class AppSettings(
    val serverType: ServerType = ServerType.Subsonic,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    /** Accept the server's TLS certificate even if it isn't trusted, e.g. because it's self-signed. */
    val allowUntrustedCertificate: Boolean = false,
    val playback: PlaybackSettings = PlaybackSettings(),
    /** Identifies this installation to Jellyfin, so logging in again replaces the previous session. */
    val jellyfinDeviceId: String = "",
    val libraryView: LibraryViewSettings = LibraryViewSettings(),
    /** Don't use the network at all, as if the device was in airplane mode. */
    val offlineMode: Boolean = false,
    /** Download songs for offline playback only while on wifi, not over mobile data. */
    val downloadOnlyOnWifi: Boolean = false,
) {
    val hasSavedConnection: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    /** Identifies the saved server for the data stored on the device. */
    val serverKey: String
        get() = "$serverType|$serverUrl|$username"
}

class SettingsHolder {
    private val _settings = MutableStateFlow(loadSettings() ?: AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: AppSettings.() -> AppSettings) {
        _settings.value = _settings.value.transform()
        saveSettings(_settings.value)
    }
}
