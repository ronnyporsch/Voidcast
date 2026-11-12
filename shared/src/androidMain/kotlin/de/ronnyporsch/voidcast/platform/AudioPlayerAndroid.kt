package de.ronnyporsch.voidcast.platform

import de.ronnyporsch.voidcast.domain.model.gainToAmplitude
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

class AudioPlayerAndroid : AudioPlayer {

    private val _state = MutableStateFlow(AudioPlayerState())
    override val state: StateFlow<AudioPlayerState> = _state.asStateFlow()

    private val lazyPlayer = lazy {
        val context = AppContextHolder.context
        // Streams over OkHttp, so a self-signed server certificate can be accepted. The server only answers once it has
        // downloaded a YouTube video, which may take longer than OkHttp's default timeout of 10 s
        val httpClient = sharedOkHttpClient.newBuilder().readTimeout(2, TimeUnit.MINUTES).build()
        val dataSourceFactory = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(httpClient))
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build().also { exo ->
                applyBoost(exo.audioSessionId)
                exo.addListener(object : Player.Listener {
                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        applyBoost(audioSessionId)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        updateState()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updateState()
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        val ratio = if (videoSize.width > 0 && videoSize.height > 0) {
                            videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                        } else null
                        _state.value = _state.value.copy(videoAspectRatio = ratio)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        positionJob?.cancel()
                        // The cause carries the useful detail, e.g. "Cleartext HTTP traffic to ... not permitted"
                        val detail = generateSequence<Throwable>(error) { it.cause }.last().message
                        _state.value = _state.value.copy(
                            isPlaying = false,
                            isBuffering = false,
                            error = detail ?: error.errorCodeName,
                        )
                    }
                })
            }
    }

    private val player: ExoPlayer by lazyPlayer

    private var positionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private fun updateState() {
        _state.value = _state.value.copy(
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition,
            durationMs = player.duration.coerceAtLeast(0),
            // Buffering while paused counts as paused, the player isn't trying to play
            isBuffering = player.playbackState == Player.STATE_BUFFERING && player.playWhenReady,
        )
    }

    private fun startPositionTracking() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                updateState()
                delay(500)
            }
        }
    }

    /** Shows its video on a surface, see [PlatformVideoSurface]. */
    internal val exoPlayer: ExoPlayer get() = player

    override fun play(url: String) {
        _state.value = _state.value.copy(error = null, videoAspectRatio = null)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
        startPositionTracking()
    }

    override val supportsVideo: Boolean = true

    override fun playVideo(url: String, startPositionMs: Long) {
        _state.value = _state.value.copy(error = null, videoAspectRatio = null)
        player.setMediaItem(MediaItem.fromUri(url), startPositionMs)
        player.prepare()
        player.play()
        startPositionTracking()
    }

    override fun pause() {
        player.pause()
        positionJob?.cancel()
        updateState()
    }

    override fun resume() {
        player.play()
        startPositionTracking()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        updateState()
    }

    private var volume = 1f
    private var gain = 1f

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        player.volume = this.volume * gain
    }

    override fun setGain(gainDb: Float) {
        gain = gainToAmplitude(gainDb)
        player.volume = volume * gain
    }

    private var boostDb = 0
    /** Amplifies what the audio session [boostSessionId] plays; null until there is something to amplify. */
    private var booster: LoudnessEnhancer? = null
    private var boostSessionId = C.AUDIO_SESSION_ID_UNSET

    // The player's volume ends at 1, which plays the song as it is
    override fun setBoost(boostDb: Int) {
        this.boostDb = boostDb.coerceIn(0, MAX_BOOST_DB)
        // A player not created yet gets it once it is
        if (lazyPlayer.isInitialized()) applyBoost(player.audioSessionId)
    }

    private fun applyBoost(sessionId: Int) {
        if (sessionId != boostSessionId) {
            booster?.release()
            booster = null
            boostSessionId = sessionId
        }
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || (boostDb == 0 && booster == null)) return
        try {
            val effect = booster ?: LoudnessEnhancer(sessionId).also { booster = it }
            effect.setTargetGain(boostDb * 100) // in millibel
            effect.enabled = boostDb > 0
        } catch (_: RuntimeException) {
            // The device has no such effect (or no more of them); plays without boost
            booster?.release()
            booster = null
        }
    }

    override fun stop() {
        player.stop()
        positionJob?.cancel()
        updateState()
    }

    override fun release() {
        positionJob?.cancel()
        scope.cancel()
        booster?.release()
        booster = null
        player.release()
    }
}

/** See [de.ronnyporsch.voidcast.Platform.maxVolumeBoostDb]. */
internal const val MAX_BOOST_DB = 18

actual fun createAudioPlayer(): AudioPlayer = AudioPlayerAndroid()
