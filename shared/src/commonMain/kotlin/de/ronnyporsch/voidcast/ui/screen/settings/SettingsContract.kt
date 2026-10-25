package de.ronnyporsch.voidcast.ui.screen.settings

import de.ronnyporsch.voidcast.domain.model.ServerType

data class SettingsState(
    val serverType: ServerType = ServerType.Subsonic,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val allowUntrustedCertificate: Boolean = false,
    val gaplessEnabled: Boolean = false,
    val crossfadeEnabled: Boolean = false,
    val crossfadeDurationSeconds: Int = 5,
    /** Describes the YouTube Music support of the connected Jellyfin server's Voidcast plugin, if any. */
    val serverYouTubeInfo: String? = null,
    /** The connected Jellyfin server has no Voidcast plugin, so YouTube Music is unavailable. */
    val pluginMissing: Boolean = false,
    val isReconnecting: Boolean = false,
    val reconnectSuccess: Boolean = false,
    val isLoggingOut: Boolean = false,
    val error: String? = null,
)

sealed class SettingsEvent {
    data class ServerTypeChanged(val type: ServerType) : SettingsEvent()
    data class ServerUrlChanged(val url: String) : SettingsEvent()
    data class UsernameChanged(val username: String) : SettingsEvent()
    data class PasswordChanged(val password: String) : SettingsEvent()
    data class AllowUntrustedCertificateToggled(val allow: Boolean) : SettingsEvent()
    data class GaplessToggled(val enabled: Boolean) : SettingsEvent()
    data class CrossfadeToggled(val enabled: Boolean) : SettingsEvent()
    data class CrossfadeDurationChanged(val seconds: Int) : SettingsEvent()
    data object Reconnect : SettingsEvent()
    data object Logout : SettingsEvent()
}

sealed class SettingsEffect {
    data class ShowMessage(val message: String) : SettingsEffect()
    data object LoggedOut : SettingsEffect()
}
