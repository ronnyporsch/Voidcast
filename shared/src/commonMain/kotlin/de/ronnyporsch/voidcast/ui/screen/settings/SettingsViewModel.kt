package de.ronnyporsch.voidcast.ui.screen.settings

import de.ronnyporsch.voidcast.domain.model.PlaybackSettings
import androidx.lifecycle.viewModelScope
import de.ronnyporsch.voidcast.data.jellyfin.NewReleasesSettingsDto
import de.ronnyporsch.voidcast.data.jellyfin.PluginInfoDto
import de.ronnyporsch.voidcast.data.repository.MusicServerConnection
import de.ronnyporsch.voidcast.domain.model.SettingsHolder
import de.ronnyporsch.voidcast.mvi.MviStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val connection: MusicServerConnection,
    private val settingsHolder: SettingsHolder,
) : MviStore<SettingsState, SettingsEvent, SettingsEffect>(SettingsState()) {

    private var savingNewReleasesSettings: Job? = null

    init {
        val current = settingsHolder.settings.value
        setState {
            copy(
                serverType = current.serverType,
                serverUrl = current.serverUrl,
                username = current.username,
                allowUntrustedCertificate = current.allowUntrustedCertificate,
                certificateFingerprint = current.certificateFingerprint.takeIf { current.allowUntrustedCertificate },
                gaplessEnabled = current.playback.gaplessEnabled,
                crossfadeEnabled = current.playback.crossfadeEnabled,
                crossfadeDurationSeconds = current.playback.crossfadeDurationSeconds,
                offlineMode = current.offlineMode,
                downloadOnlyOnWifi = current.downloadOnlyOnWifi,
            )
        }
        viewModelScope.launch {
            connection.plugin.collect { plugin ->
                setState {
                    copy(
                        serverYouTubeInfo = plugin?.let(::describe),
                        newReleasesMinSongs = plugin?.newReleasesMinSongs ?: newReleasesMinSongs,
                        newReleasesFavoritesOnly = plugin?.newReleasesFavoritesOnly ?: newReleasesFavoritesOnly,
                        canConfigureNewReleases = plugin?.canConfigureNewReleases == true,
                    )
                }
            }
        }
        viewModelScope.launch {
            connection.pluginMissing.collect { setState { copy(pluginMissing = it) } }
        }
        // Pick up changes an admin made to the plugin's settings
        launch { connection.refreshPlugin() }
    }

    private fun describe(plugin: PluginInfoDto): String = when {
        !plugin.ytDlpAvailable ->
            "The Voidcast plugin on your Jellyfin server can't find yt-dlp. It needs yt-dlp installed on the server."
        !plugin.canDownload ->
            "Your Jellyfin user may not download YouTube Music tracks. An administrator can allow it in the Voidcast plugin settings."
        else -> buildString {
            append("Downloads are handled by the Voidcast plugin on your Jellyfin server")
            plugin.downloadDirectory?.let { append(" and saved to $it") }
            append(". Browser cookies and the download folder are configured in the Jellyfin dashboard.")
            plugin.warning?.let { append("\n\n$it") }
        }
    }

    override fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.ServerTypeChanged -> setState { copy(serverType = event.type, error = null) }
            is SettingsEvent.ServerUrlChanged -> setState { copy(serverUrl = event.url, error = null) }
            is SettingsEvent.UsernameChanged -> setState { copy(username = event.username, error = null) }
            is SettingsEvent.PasswordChanged -> setState { copy(password = event.password, error = null) }
            is SettingsEvent.AllowUntrustedCertificateToggled -> {
                // Turning it on (again) trusts the certificate the server shows at the next connection
                connection.forgetCertificate()
                setState { copy(allowUntrustedCertificate = event.allow, certificateFingerprint = null, error = null) }
            }
            is SettingsEvent.GaplessToggled -> {
                setState { copy(gaplessEnabled = event.enabled) }
                updatePlaybackSettings { copy(gaplessEnabled = event.enabled) }
            }
            is SettingsEvent.CrossfadeToggled -> {
                setState { copy(crossfadeEnabled = event.enabled) }
                updatePlaybackSettings { copy(crossfadeEnabled = event.enabled) }
            }
            is SettingsEvent.CrossfadeDurationChanged -> {
                setState { copy(crossfadeDurationSeconds = event.seconds) }
                updatePlaybackSettings { copy(crossfadeDurationSeconds = event.seconds) }
            }
            is SettingsEvent.OfflineModeToggled -> {
                setState { copy(offlineMode = event.enabled) }
                settingsHolder.update { copy(offlineMode = event.enabled) }
            }
            is SettingsEvent.DownloadOnlyOnWifiToggled -> {
                setState { copy(downloadOnlyOnWifi = event.enabled) }
                settingsHolder.update { copy(downloadOnlyOnWifi = event.enabled) }
            }
            is SettingsEvent.NewReleasesMinSongsChanged -> {
                setState { copy(newReleasesMinSongs = event.minSongs) }
                saveNewReleasesSettings()
            }
            is SettingsEvent.NewReleasesFavoritesOnlyToggled -> {
                setState { copy(newReleasesFavoritesOnly = event.enabled) }
                saveNewReleasesSettings()
            }
            is SettingsEvent.Reconnect -> reconnect()
            is SettingsEvent.Logout -> logout()
        }
    }

    private fun logout() {
        if (currentState.isLoggingOut) return
        setState { copy(isLoggingOut = true) }
        launch {
            connection.logout()
            emitEffect(SettingsEffect.LoggedOut)
        }
    }

    private fun saveNewReleasesSettings() {
        savingNewReleasesSettings?.cancel()
        savingNewReleasesSettings = viewModelScope.launch {
            // The number gets typed digit by digit
            delay(500)
            try {
                val state = currentState
                connection.jellyfinApi.setNewReleasesSettings(NewReleasesSettingsDto(state.newReleasesMinSongs, state.newReleasesFavoritesOnly))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emitEffect(SettingsEffect.ShowMessage("Couldn't save the new releases settings: ${e.message}"))
            }
        }
    }

    // Only the changed setting: the others may have changed elsewhere since, e.g. crossfade in the player
    private fun updatePlaybackSettings(transform: PlaybackSettings.() -> PlaybackSettings) {
        settingsHolder.update { copy(playback = playback.transform()) }
    }

    private fun reconnect() {
        val state = currentState
        val saved = settingsHolder.settings.value
        // The password isn't kept: without it, only the saved login can be connected again
        val sameLogin = state.serverType == saved.serverType && state.username == saved.username &&
            state.serverUrl.trim().trimEnd('/') == saved.serverUrl && saved.hasSavedToken
        if (state.serverUrl.isBlank() || state.username.isBlank()) {
            setState { copy(error = "Server URL and username are required") }
            return
        }
        if (state.password.isBlank() && !sameLogin) {
            setState { copy(error = "Enter the password to connect to another server or as another user") }
            return
        }

        setState { copy(isReconnecting = true, error = null, reconnectSuccess = false) }

        launch {
            try {
                if (state.password.isBlank()) {
                    settingsHolder.update { copy(allowUntrustedCertificate = state.allowUntrustedCertificate) }
                    connection.resume()
                } else {
                    connection.connect(
                        state.serverType,
                        state.serverUrl,
                        state.username,
                        state.password,
                        state.allowUntrustedCertificate,
                    )
                    setState { copy(password = "") }
                }
                val connected = settingsHolder.settings.value
                setState {
                    copy(
                        isReconnecting = false,
                        reconnectSuccess = true,
                        certificateFingerprint = connected.certificateFingerprint.takeIf { connected.allowUntrustedCertificate },
                    )
                }
                emitEffect(SettingsEffect.ShowMessage("Connected successfully"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { copy(isReconnecting = false, error = e.message ?: "Connection error") }
            }
        }
    }
}
