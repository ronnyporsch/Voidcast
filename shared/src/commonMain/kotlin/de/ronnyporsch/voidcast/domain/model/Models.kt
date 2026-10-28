package de.ronnyporsch.voidcast.domain.model

import kotlinx.serialization.Serializable

data class ServerConfig(
    val serverUrl: String,
    val username: String,
    val token: String,
    val salt: String,
)

@Serializable
data class Artist(
    val id: String,
    val name: String,
    val albumCount: Int = 0,
    val coverArtId: String? = null,
    /** When it got added to the library, as an ISO 8601 timestamp; null if the server doesn't tell. */
    val dateAdded: String? = null,
)

data class ArtistInfo(
    val biography: String? = null,
    val largeImageUrl: String? = null,
)

data class Album(
    val id: String,
    val name: String,
    val artist: String = "",
    val artistId: String = "",
    val artists: List<Artist> = emptyList(),
    val coverArtId: String? = null,
    val songCount: Int = 0,
    val year: Int? = null,
    val duration: Int = 0,
    val songs: List<Song> = emptyList(),
    /** E.g. "Album", "Single" or "EP"; only known for YouTube Music albums. */
    val releaseType: String? = null,
    /** When it got added to the library, as an ISO 8601 timestamp; null if the server doesn't tell. */
    val dateAdded: String? = null,
)

@Serializable
data class Song(
    val id: String,
    val title: String,
    val artist: String = "",
    val artistId: String = "",
    val artists: List<Artist> = emptyList(),
    val album: String = "",
    val albumId: String = "",
    val coverArtId: String? = null,
    val duration: Int = 0,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    /** In kbps. */
    val bitRate: Int? = null,
    /** Display name such as "FLAC" or "Opus", see [codecDisplayName]. */
    val codec: String? = null,
    /** File size in bytes; null if the server doesn't tell. */
    val size: Long? = null,
    /** A (music) video from YouTube or the library, which can be played with picture. */
    val isVideo: Boolean = false,
    /** When it got added to the library, as an ISO 8601 timestamp; null if the server doesn't tell. */
    val dateAdded: String? = null,
)

/** What kind of item a favorite is; servers mark them through different endpoints. */
enum class FavoriteKind { Song, Album, Artist }

/** [codec] is a display name such as "Opus" or "AAC", see [codecDisplayName]; [bitRate] is in kbps. */
data class AudioStreamInfo(val codec: String?, val bitRate: Int?)

/** Video stream of a video; [codec] is a display name such as "H.264", [bitRate] is in kbps. */
data class VideoStreamInfo(val codec: String?, val bitRate: Int?, val width: Int?, val height: Int?, val frameRate: Int?)

/** The streams a video plays with. */
data class VideoFormat(val video: VideoStreamInfo, val audio: AudioStreamInfo)

/** Codec and bitrate the song carries itself (library songs, from some song lists), or null. */
val Song.audioFormat: AudioStreamInfo?
    get() = AudioStreamInfo(codec, bitRate).takeIf { codec != null || bitRate != null }

/** Display name of a codec or file type as servers report it, e.g. "flac" -> "FLAC", "m4a" -> "AAC". */
fun codecDisplayName(codec: String): String = when (val lower = codec.lowercase()) {
    "opus" -> "Opus"
    "vorbis", "ogg", "oga" -> "Vorbis"
    "aac", "m4a", "mp4a" -> "AAC"
    else -> if (lower.startsWith("mp4a")) "AAC" else lower.uppercase()
}

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArtId: String? = null,
    val songs: List<Song> = emptyList(),
    /** When it got created, as an ISO 8601 timestamp; null if the server doesn't tell. */
    val dateAdded: String? = null,
)

/** A library of the server with music videos, see [de.ronnyporsch.voidcast.data.repository.MusicRepository.getMusicVideos]. */
data class VideoLibrary(
    val id: String,
    val name: String,
)

/** A genre of the library; on Subsonic servers its [id] is its name. */
data class Genre(
    val id: String,
    val name: String,
    val albumCount: Int = 0,
    val songCount: Int = 0,
)

enum class PlaybackStatus {
    Idle, Playing, Paused, Buffering
}

@Serializable
data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val currentSong: Song? = null,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    /** How many songs after the current one were added with "Add to queue"; more get added after them. */
    val queuedCount: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.Off,
    val crossfadeEnabled: Boolean = false,
)

enum class RepeatMode {
    Off, All, One
}

/** E.g. "1 h 23 min" or "45 min"; for totals such as a playlist's length. */
fun Int.formatTotalDuration(): String {
    val hours = this / 3600
    val minutes = (this % 3600 + 30) / 60
    return if (hours > 0) "$hours h $minutes min" else "$minutes min"
}

/** E.g. "512 KB", "4.2 MB" or "1.3 GB". */
fun Long.formatFileSize(): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    if (unit < 2) return "${value.toLong()} ${units[unit]}"
    val tenths = (value * 10 + 0.5).toLong()
    return "${tenths / 10}.${tenths % 10} ${units[unit]}"
}

fun Int.formatDuration(): String {
    val minutes = this / 60
    val seconds = this % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

fun Long.formatDurationMs(): String {
    val totalSeconds = (this / 1000).toInt()
    return totalSeconds.formatDuration()
}

/**
 * Songs, albums and artists from YouTube Music carry this prefix in their ids; they are not part of the library.
 * Their album and artist ids (e.g. [Song.albumId]) carry it, too.
 */
const val YOUTUBE_SONG_ID_PREFIX = "yt:"

val String.isYouTubeId: Boolean
    get() = startsWith(YOUTUBE_SONG_ID_PREFIX)

/** The YouTube Music id (video, album browse or channel id) of an id with [YOUTUBE_SONG_ID_PREFIX], else null. */
val String.youTubeId: String?
    get() = if (isYouTubeId) removePrefix(YOUTUBE_SONG_ID_PREFIX) else null

val Song.isYouTube: Boolean
    get() = id.isYouTubeId

val Song.youTubeVideoId: String?
    get() = id.youTubeId

/** Id of the YouTube Music artist page of the song's artist [name], if it has one. */
fun Song.youTubeArtistId(name: String): String? =
    if (isYouTube) artists.firstOrNull { it.name == name && it.id.isYouTubeId }?.id else null

/** A line of [Lyrics]; [startMs] is null for unsynced lyrics. */
data class LyricLine(
    val text: String,
    val startMs: Long? = null,
)

data class Lyrics(
    val lines: List<LyricLine>,
) {
    /** Synced lyrics have a start time for every line, so the current line can be highlighted. */
    val isSynced: Boolean get() = lines.isNotEmpty() && lines.all { it.startMs != null }
}
