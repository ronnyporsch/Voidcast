package de.ronnyporsch.voidcast.platform

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

class CrossfadeAudioPlayer(
    private val playerA: AudioPlayer,
    private val playerB: AudioPlayer,
) : AudioPlayer {

    private val _state = MutableStateFlow(AudioPlayerState())
    override val state: StateFlow<AudioPlayerState> = _state.asStateFlow()

    // Main: platform players (ExoPlayer) must only be accessed from the thread that created them
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateCollectionJob: Job? = null
    private var crossfadeJob: Job? = null
    private var prepareJob: Job? = null

    private var currentPlayer: AudioPlayer = playerA
        set(value) {
            field = value
            _activePlayer.value = value
        }
    private val _activePlayer = MutableStateFlow(playerA)
    /** The player the current song plays on, e.g. to show its video. */
    val activePlayer: StateFlow<AudioPlayer> = _activePlayer.asStateFlow()
    private var nextPlayer: AudioPlayer = playerB
    private var nextPreparedUrl: String? = null
    /** Given to [prepareNext] during a crossfade, while the next player is the incoming one; prepared after it. */
    private var preparedAfterCrossfadeUrl: String? = null
    @Volatile
    private var isCrossfading = false
    @Volatile
    private var isCrossfadePaused = false

    init {
        collectCurrentPlayerState()
    }

    private fun collectCurrentPlayerState() {
        stateCollectionJob?.cancel()
        stateCollectionJob = scope.launch {
            currentPlayer.state.collect { playerState ->
                if (!isCrossfading) {
                    _state.value = playerState
                }
            }
        }
    }

    fun prepareNext(url: String) {
        if (isCrossfading) {
            preparedAfterCrossfadeUrl = url
            return
        }
        if (nextPreparedUrl == url) return
        nextPreparedUrl = url
        val player = nextPlayer
        // Start loading on the next player (it will buffer/download)
        player.play(url)
        // Immediately pause it so it's ready but not audible
        player.setVolume(0f)
        prepareJob?.cancel()
        prepareJob = scope.launch {
            // Wait a bit for the player to start buffering, then pause
            delay(100)
            player.pause()
        }
    }

    /**
     * Plays [url] from the start on the player [prepareNext] loaded it on, so it doesn't have to be loaded again.
     * Returns false if it isn't prepared (or failed to load); then nothing changed.
     */
    fun playPrepared(url: String): Boolean {
        if (isCrossfading || nextPreparedUrl != url || nextPlayer.state.value.error != null) return false
        prepareJob?.cancel()
        val inPlayer = nextPlayer
        val outPlayer = currentPlayer

        outPlayer.stop()
        outPlayer.setVolume(1f)
        // It played for a moment before being paused
        inPlayer.seekTo(0)
        inPlayer.setVolume(1f)
        inPlayer.resume()

        currentPlayer = inPlayer
        nextPlayer = outPlayer
        nextPreparedUrl = null

        collectCurrentPlayerState()
        return true
    }

    fun startCrossfade(durationMs: Long) {
        if (isCrossfading) return
        isCrossfading = true
        isCrossfadePaused = false
        crossfadeJob?.cancel()
        // The incoming player must not be paused by a still pending prepareNext
        prepareJob?.cancel()

        val outPlayer = currentPlayer
        val inPlayer = nextPlayer

        // Resume the next player (was paused after prepareNext)
        inPlayer.setVolume(0f)
        inPlayer.resume()

        crossfadeJob = scope.launch {
            val steps = (durationMs / 50).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                if (!isActive) break
                // Hold the fade while paused, so it continues where it was on resume
                while (isCrossfadePaused) {
                    _state.value = inPlayer.state.value.copy(isPlaying = false, isBuffering = false)
                    delay(50)
                }
                val progress = i.toFloat() / steps
                outPlayer.setVolume(1f - progress)
                inPlayer.setVolume(progress)

                // Report state from the incoming player during crossfade
                val inState = inPlayer.state.value
                _state.value = inState.copy(isPlaying = true)

                delay(50)
            }
            finishCrossfade()
        }
    }

    /** Swaps the players, the incoming one is the current one from now on. */
    private fun finishCrossfade() {
        crossfadeJob?.cancel()
        val outPlayer = currentPlayer
        val inPlayer = nextPlayer
        outPlayer.stop()
        outPlayer.setVolume(1f)
        inPlayer.setVolume(1f)

        currentPlayer = inPlayer
        nextPlayer = outPlayer
        nextPreparedUrl = null
        isCrossfading = false
        isCrossfadePaused = false

        collectCurrentPlayerState()
        preparedAfterCrossfadeUrl?.let {
            preparedAfterCrossfadeUrl = null
            prepareNext(it)
        }
    }

    fun playNextGapless() {
        prepareJob?.cancel()
        val inPlayer = nextPlayer
        val outPlayer = currentPlayer

        if (nextPreparedUrl != null) {
            // Next track is pre-loaded and paused — just resume it
            inPlayer.setVolume(1f)
            inPlayer.resume()
        }

        outPlayer.stop()
        outPlayer.setVolume(1f)

        currentPlayer = inPlayer
        nextPlayer = outPlayer
        nextPreparedUrl = null
        isCrossfading = false

        collectCurrentPlayerState()
    }

    override fun play(url: String) {
        crossfadeJob?.cancel()
        isCrossfading = false
        nextPlayer.stop()
        nextPlayer.setVolume(1f)
        nextPreparedUrl = null
        preparedAfterCrossfadeUrl = null
        currentPlayer.setVolume(1f)
        currentPlayer.play(url)
        collectCurrentPlayerState()
    }

    override val supportsVideo: Boolean get() = playerA.supportsVideo && playerB.supportsVideo

    /** Plays the video without crossfade or gapless transitions. */
    override fun playVideo(url: String, startPositionMs: Long) {
        crossfadeJob?.cancel()
        isCrossfading = false
        nextPlayer.stop()
        nextPlayer.setVolume(1f)
        nextPreparedUrl = null
        preparedAfterCrossfadeUrl = null
        currentPlayer.setVolume(1f)
        currentPlayer.playVideo(url, startPositionMs)
        collectCurrentPlayerState()
    }

    override fun pause() {
        currentPlayer.pause()
        if (isCrossfading) {
            isCrossfadePaused = true
            nextPlayer.pause()
            _state.value = nextPlayer.state.value.copy(isPlaying = false, isBuffering = false)
        }
    }

    override fun resume() {
        currentPlayer.resume()
        if (isCrossfading) {
            isCrossfadePaused = false
            nextPlayer.resume()
        }
    }

    override fun seekTo(positionMs: Long) {
        // The incoming song is the one shown already, the rest of the outgoing one isn't wanted anymore
        if (isCrossfading) finishCrossfade()
        currentPlayer.seekTo(positionMs)
    }

    override fun stop() {
        crossfadeJob?.cancel()
        isCrossfading = false
        currentPlayer.stop()
        nextPlayer.stop()
        currentPlayer.setVolume(1f)
        nextPlayer.setVolume(1f)
        nextPreparedUrl = null
        preparedAfterCrossfadeUrl = null
        _state.value = AudioPlayerState()
    }

    override fun setVolume(volume: Float) {
        currentPlayer.setVolume(volume)
    }

    override fun release() {
        crossfadeJob?.cancel()
        stateCollectionJob?.cancel()
        scope.cancel()
        playerA.release()
        playerB.release()
    }
}
