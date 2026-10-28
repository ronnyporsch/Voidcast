package de.ronnyporsch.voidcast.ui.screen.player

import de.ronnyporsch.voidcast.data.remote.RemoteDevice
import de.ronnyporsch.voidcast.domain.model.AudioStreamInfo
import de.ronnyporsch.voidcast.domain.model.DEFAULT_VIDEO_QUALITY
import de.ronnyporsch.voidcast.domain.model.Lyrics
import de.ronnyporsch.voidcast.domain.model.PlaybackState
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.domain.model.VideoStreamInfo

data class PlayerState(
    val playback: PlaybackState = PlaybackState(),
    val showLyrics: Boolean = false,
    /** Lyrics of the current song. */
    val lyrics: LyricsState = LyricsState.Loading,
    /** Codec and bitrate of the current song, once known. */
    val audioFormat: AudioStreamInfo? = null,
    /** Most recent first, including the current song. */
    val recentlyPlayed: List<Song> = emptyList(),
    /** Whether the current song plays as a video with picture, see [de.ronnyporsch.voidcast.platform.VideoSurface]. */
    val isVideo: Boolean = false,
    /** Width / height of the video's picture, once known. */
    val videoAspectRatio: Float? = null,
    /** Highest resolution (height) videos get streamed in. */
    val videoQuality: Int = DEFAULT_VIDEO_QUALITY,
    /** Video stream of the current video, once known; its audio stream is in [audioFormat]. */
    val videoFormat: VideoStreamInfo? = null,
    /** Heights the current video is available in, highest first; empty until known. */
    val videoHeights: List<Int> = emptyList(),
    /** Whether the server lets clients control each other's playback (Jellyfin). */
    val remoteControlAvailable: Boolean = false,
    /** Other devices of the user whose playback can be controlled. */
    val devices: List<RemoteDevice> = emptyList(),
    /** The device whose playback [playback] is and the events control; null for this device. */
    val remoteDevice: RemoteDevice? = null,
) {
    /** Whether the songs of the queue can be moved, removed and jumped to. */
    val canEditQueue: Boolean get() = remoteDevice?.isVoidcast != false
}

sealed interface LyricsState {
    data object Loading : LyricsState
    data class Loaded(val lyrics: Lyrics) : LyricsState
    data object NotFound : LyricsState
    data class Failed(val message: String) : LyricsState
}

sealed class PlayerEvent {
    /**
     * Plays [songs] from [startIndex]. [shuffle] true shuffles all of them (ignoring [startIndex]), null keeps the
     * shuffle mode and, if it's on, plays the song at [startIndex] first.
     */
    data class PlaySongs(val songs: List<Song>, val startIndex: Int, val shuffle: Boolean? = null) : PlayerEvent()
    data object PlayPause : PlayerEvent()
    data object Play : PlayerEvent()
    data object Pause : PlayerEvent()
    data object Next : PlayerEvent()
    data object Previous : PlayerEvent()
    data object ToggleShuffle : PlayerEvent()
    data object ToggleRepeat : PlayerEvent()
    data object ToggleCrossfade : PlayerEvent()
    data object ToggleLyrics : PlayerEvent()
    data object ShowLyrics : PlayerEvent()
    /** Plays [songs] after the current song and the ones queued before. */
    data class AddToQueue(val songs: List<Song>) : PlayerEvent()
    /** Moves the song at queue index [from] to [to]. */
    data class MoveInQueue(val from: Int, val to: Int) : PlayerEvent()
    data class RemoveFromQueue(val index: Int) : PlayerEvent()
    /** Jumps to the song at this queue index. */
    data class PlayQueueIndex(val index: Int) : PlayerEvent()
    /** Plays [song] now without replacing the queue, e.g. from the recently played songs. */
    data class PlayNow(val song: Song) : PlayerEvent()
    data class SeekTo(val positionMs: Long) : PlayerEvent()
    /** Seeks [deltaMs] forward (or back if negative), within the song. */
    data class SeekBy(val deltaMs: Long) : PlayerEvent()
    /** Streams videos in the highest resolution up to this height from now on, the current one too. */
    data class SetVideoQuality(val height: Int) : PlayerEvent()
    /**
     * Plays on the device with this session id from now on ([PlayerState.devices]), or on this device if null.
     * What's playing moves along.
     */
    data class SelectDevice(val sessionId: String?) : PlayerEvent()
}

sealed class PlayerEffect {
    data class PlaybackError(val message: String) : PlayerEffect()
    data class Message(val message: String) : PlayerEffect()
}
