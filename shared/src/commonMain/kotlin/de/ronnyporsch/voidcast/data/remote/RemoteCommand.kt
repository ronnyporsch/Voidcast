package de.ronnyporsch.voidcast.data.remote

import de.ronnyporsch.voidcast.domain.model.PlaybackState
import de.ronnyporsch.voidcast.domain.model.PlaybackStatus
import de.ronnyporsch.voidcast.domain.model.RepeatMode
import de.ronnyporsch.voidcast.domain.model.Song
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.TimeSource

/** What one device tells the player of another to do; the playback part of the player's events. */
@Serializable
sealed interface RemoteCommand {
    /** Plays [songs] from [startIndex] at [startPositionMs]; [shuffle] like in the player's PlaySongs event. */
    @Serializable @SerialName("PlaySongs")
    data class PlaySongs(val songs: List<Song>, val startIndex: Int, val shuffle: Boolean? = null, val startPositionMs: Long = 0) : RemoteCommand

    /** Continues [playback] (queue, position, modes) there, e.g. when moving playback from one device to another. */
    @Serializable @SerialName("Load")
    data class Load(val playback: PlaybackState, val paused: Boolean) : RemoteCommand

    @Serializable @SerialName("Play") data object Play : RemoteCommand
    @Serializable @SerialName("Pause") data object Pause : RemoteCommand
    @Serializable @SerialName("PlayPause") data object PlayPause : RemoteCommand
    @Serializable @SerialName("Stop") data object Stop : RemoteCommand
    @Serializable @SerialName("Next") data object Next : RemoteCommand
    @Serializable @SerialName("Previous") data object Previous : RemoteCommand
    @Serializable @SerialName("SeekTo") data class SeekTo(val positionMs: Long) : RemoteCommand
    /** Seeks forward (or backward if negative) from the current position. */
    @Serializable @SerialName("SeekBy") data class SeekBy(val deltaMs: Long) : RemoteCommand
    @Serializable @SerialName("SetShuffle") data class SetShuffle(val shuffle: Boolean) : RemoteCommand
    @Serializable @SerialName("SetRepeat") data class SetRepeat(val repeatMode: RepeatMode) : RemoteCommand
    @Serializable @SerialName("AddToQueue") data class AddToQueue(val songs: List<Song>) : RemoteCommand
    @Serializable @SerialName("PlayNext") data class PlayNext(val songs: List<Song>) : RemoteCommand
    @Serializable @SerialName("MoveInQueue") data class MoveInQueue(val from: Int, val to: Int) : RemoteCommand
    @Serializable @SerialName("RemoveFromQueue") data class RemoveFromQueue(val index: Int) : RemoteCommand
    @Serializable @SerialName("PlayQueueIndex") data class PlayQueueIndex(val index: Int) : RemoteCommand
    @Serializable @SerialName("PlayNow") data class PlayNow(val song: Song) : RemoteCommand
}

/** Another client of the user, whose playback can be controlled from here. */
data class RemoteDevice(
    val sessionId: String,
    val name: String,
    /** The app, e.g. "Jellyfin Web". */
    val client: String,
    /**
     * Another Voidcast instance: it reports its whole player state (queue included) and takes every command. Other
     * clients only report their current song and can't play YouTube Music songs.
     */
    val isVoidcast: Boolean,
    /** As last reported; see [playbackNow]. */
    val playback: PlaybackState,
    /** When [playback] was reported. */
    val reportedAt: TimeSource.Monotonic.ValueTimeMark,
) {
    /** [playback] with the position it has reached since it was reported. */
    fun playbackNow(): PlaybackState {
        if (playback.status != PlaybackStatus.Playing) return playback
        val position = playback.positionMs + reportedAt.elapsedNow().inWholeMilliseconds
        return playback.copy(positionMs = if (playback.durationMs > 0) position.coerceAtMost(playback.durationMs) else position)
    }
}
