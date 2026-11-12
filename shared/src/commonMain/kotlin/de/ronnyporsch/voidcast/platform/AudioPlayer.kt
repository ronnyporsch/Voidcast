package de.ronnyporsch.voidcast.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

data class AudioPlayerState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isBuffering: Boolean = false,
    /** Why the last [AudioPlayer.play] failed, cleared by the next one. */
    val error: String? = null,
    /** Width / height of the picture of the video being played; null while there is none. */
    val videoAspectRatio: Float? = null,
)

interface AudioPlayer {
    val state: StateFlow<AudioPlayerState>
    fun play(url: String)

    /**
     * Loads [url] to be started by [resume], e.g. the next song. Players that can't load without playing just play
     * it; then the caller pauses it.
     */
    fun prepare(url: String) = play(url)
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun stop()
    fun setVolume(volume: Float)

    /**
     * Plays what's loaded or loaded next [gainDb] decibels quieter (it is never amplified, see
     * [de.ronnyporsch.voidcast.domain.model.normalizationGain]), on top of [setVolume]; 0 plays it as it is.
     */
    fun setGain(gainDb: Float)

    /**
     * Plays everything [boostDb] decibels louder than it is, beyond the full volume of the device, on top of
     * [setVolume] and [setGain]; at most [de.ronnyporsch.voidcast.Platform.maxVolumeBoostDb], 0 plays it as it is.
     */
    fun setBoost(boostDb: Int) {}
    fun release()

    /** Whether [playVideo] shows the picture (see [VideoSurface]); if not, videos are played as audio only. */
    val supportsVideo: Boolean get() = false

    /** Plays a video with picture and sound, starting at [startPositionMs]. */
    fun playVideo(url: String, startPositionMs: Long = 0) {
        play(url)
        if (startPositionMs > 0) seekTo(startPositionMs)
    }
}

expect fun createAudioPlayer(): AudioPlayer

/** Shows the picture of the video [player] plays, if any. */
@Composable
fun VideoSurface(player: AudioPlayer, modifier: Modifier = Modifier) {
    val target = if (player is CrossfadeAudioPlayer) player.activePlayer.collectAsState().value else player
    PlatformVideoSurface(target, modifier)
}

@Composable
internal expect fun PlatformVideoSurface(player: AudioPlayer, modifier: Modifier)
