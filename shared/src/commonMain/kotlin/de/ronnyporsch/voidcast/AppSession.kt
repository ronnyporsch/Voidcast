package de.ronnyporsch.voidcast

import de.ronnyporsch.voidcast.data.remote.RemoteControl
import de.ronnyporsch.voidcast.data.repository.InvalidCredentialsException
import de.ronnyporsch.voidcast.data.repository.MusicServerConnection
import de.ronnyporsch.voidcast.data.repository.PlayHistory
import de.ronnyporsch.voidcast.domain.model.SettingsHolder
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.platform.CrossfadeAudioPlayer
import de.ronnyporsch.voidcast.platform.createAudioPlayer
import de.ronnyporsch.voidcast.platform.createSystemMediaControls
import de.ronnyporsch.voidcast.ui.screen.player.PlayerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What lives as long as the process instead of the UI: the server connection and the player. On Android this keeps
 * playback going without the activity, and lets the media browser (Google Maps, Android Auto) play music without
 * the app being open.
 */
class AppSession private constructor() {
    val settingsHolder = SettingsHolder()
    val connection = MusicServerConnection(settingsHolder)
    private val audioPlayer = CrossfadeAudioPlayer(createAudioPlayer(), createAudioPlayer())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Playing on other devices of the user and letting them control this one. */
    private val remoteControl = RemoteControl(connection, scope)
    val playerViewModel = PlayerViewModel(
        audioPlayer,
        connection.repository,
        settingsHolder,
        PlayHistory { settingsHolder.settings.value.serverKey },
        remoteControl,
    )
    /** For the desktop window title, without the window needing the ViewModel classes. */
    val currentSong: Flow<Song?> = playerViewModel.state.map { it.playback.currentSong }.distinctUntilChanged()

    /** Whether there was a saved login when the session started; then it connects in the background. */
    private val autoLogin = settingsHolder.settings.value.hasSavedConnection

    private val _autoLoginStatus = MutableStateFlow(if (autoLogin) AutoLoginStatus.Connecting else AutoLoginStatus.None)
    val autoLoginStatus: StateFlow<AutoLoginStatus> = _autoLoginStatus.asStateFlow()

    /** Cuts the wait for the next auto-login attempt short. */
    private val retryRequests = Channel<Unit>(Channel.CONFLATED)

    // Media notification on Android, media keys via MPRIS on Linux
    private val systemMediaControls = createSystemMediaControls(
        playback = playerViewModel.state.map { it.playback },
        onEvent = playerViewModel::onEvent,
        // Throws while not connected, e.g. when playing downloaded songs offline
        coverArtUrl = { song -> song.coverArtId?.let { runCatching { connection.repository.getCoverArtUrl(it, 512) }.getOrNull() } },
    )

    init {
        if (autoLogin) scope.launch { runAutoLogin() }
        // Also after logging in by hand, e.g. once the saved login got rejected
        scope.launch {
            connection.connections.collect { if (it > 0) _autoLoginStatus.value = AutoLoginStatus.Connected }
        }
    }

    // Only a rejected login asks for the credentials again; if the server isn't reachable (yet), e.g. because the
    // network is still coming up, keep retrying
    private suspend fun runAutoLogin() {
        val saved = settingsHolder.settings.value
        var retryDelayMs = 2_000L
        var attempt = 0
        while (true) {
            // An attempt is about to start anyway
            retryRequests.tryReceive()
            try {
                connection.connect(
                    saved.serverType,
                    saved.serverUrl,
                    saved.username,
                    saved.password,
                    saved.allowUntrustedCertificate,
                    save = false,
                )
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidCredentialsException) {
                _autoLoginStatus.value = AutoLoginStatus.Rejected(e.message ?: "")
                return
            } catch (e: Exception) {
                _autoLoginStatus.value = AutoLoginStatus.Retrying(
                    "Can't reach ${saved.serverUrl}: ${e.message ?: e::class.simpleName}",
                    ++attempt,
                )
                withTimeoutOrNull(retryDelayMs) { retryRequests.receive() }
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    /** While the server isn't reachable ([AutoLoginStatus.Retrying]), tries again right away. */
    fun retryConnection() {
        retryRequests.trySend(Unit)
    }

    /** Stops playback for good, e.g. when the desktop app's window gets closed. */
    fun release() {
        scope.cancel()
        systemMediaControls?.release()
        audioPlayer.release()
        if (instance === this) instance = null
    }

    companion object {
        private var instance: AppSession? = null

        /** The session, created on first use. Call on the main thread. */
        fun get(): AppSession = instance ?: AppSession().also { instance = it }

        /** Releases the session if there is one, see [AppSession.release]. */
        fun release() {
            instance?.release()
        }
    }
}

sealed interface AutoLoginStatus {
    /** There was no saved login. */
    data object None : AutoLoginStatus
    data object Connecting : AutoLoginStatus
    data object Connected : AutoLoginStatus

    /** The server couldn't be reached, it is tried again later. [attempt] makes every failure a distinct state. */
    data class Retrying(val message: String, val attempt: Int) : AutoLoginStatus

    /** The server rejected the saved login. */
    data class Rejected(val message: String) : AutoLoginStatus
}
