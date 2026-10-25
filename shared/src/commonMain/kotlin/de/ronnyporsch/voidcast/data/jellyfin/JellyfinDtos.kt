package de.ronnyporsch.voidcast.data.jellyfin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthenticateByNameRequest(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String,
)

@Serializable
data class AuthenticationResultDto(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("User") val user: UserDto,
)

@Serializable
data class UserDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
)

@Serializable
data class ItemsResultDto(
    @SerialName("Items") val items: List<BaseItemDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)

@Serializable
data class NameIdPairDto(
    @SerialName("Name") val name: String = "",
    @SerialName("Id") val id: String = "",
)

/** The subset of Jellyfin's BaseItemDto used for artists, albums, songs and playlists. */
@Serializable
data class BaseItemDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "",
    @SerialName("MediaType") val mediaType: String? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumPrimaryImageTag") val albumPrimaryImageTag: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("AlbumArtists") val albumArtists: List<NameIdPairDto> = emptyList(),
    @SerialName("ArtistItems") val artistItems: List<NameIdPairDto> = emptyList(),
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
    /** In units of 100 ns. */
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("AlbumCount") val albumCount: Int? = null,
    @SerialName("SongCount") val songCount: Int? = null,
    /** When it got added to the library, ISO 8601; only with the DateCreated field. */
    @SerialName("DateCreated") val dateCreated: String? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    /** Only with the MediaStreams field. */
    @SerialName("MediaStreams") val mediaStreams: List<MediaStreamDto> = emptyList(),
    /** Only with the MediaSources field. */
    @SerialName("MediaSources") val mediaSources: List<MediaSourceDto> = emptyList(),
    /** Identifies the entry within a playlist (the same song can be in it more than once). */
    @SerialName("PlaylistItemId") val playlistItemId: String? = null,
)

@Serializable
data class MediaSourceDto(
    /** In bytes. */
    @SerialName("Size") val size: Long? = null,
)

@Serializable
data class MediaStreamDto(
    @SerialName("Type") val type: String = "",
    @SerialName("Codec") val codec: String? = null,
    /** In bit/s. */
    @SerialName("BitRate") val bitRate: Int? = null,
)

@Serializable
data class CreatePlaylistDto(
    @SerialName("Name") val name: String,
    @SerialName("Ids") val ids: List<String>,
    @SerialName("UserId") val userId: String,
    @SerialName("MediaType") val mediaType: String = "Audio",
)

@Serializable
data class PlaylistCreationResultDto(
    @SerialName("Id") val id: String,
)

@Serializable
data class LyricDto(
    @SerialName("Lyrics") val lyrics: List<LyricLineDto> = emptyList(),
)

@Serializable
data class LyricLineDto(
    @SerialName("Text") val text: String = "",
    /** In units of 100 ns, null for unsynced lyrics. */
    @SerialName("Start") val start: Long? = null,
)

/** A lyrics search result of a lyrics provider plugin such as LrcLib. */
@Serializable
data class RemoteLyricInfoDto(
    @SerialName("Id") val id: String,
    @SerialName("ProviderName") val providerName: String = "",
    @SerialName("Lyrics") val lyrics: LyricDto = LyricDto(),
)

// Voidcast Jellyfin plugin (see jellyfin-plugin/)

@Serializable
data class PluginInfoDto(
    @SerialName("Version") val version: String = "",
    @SerialName("YtDlpAvailable") val ytDlpAvailable: Boolean = false,
    @SerialName("YtDlpVersion") val ytDlpVersion: String? = null,
    @SerialName("CanDownload") val canDownload: Boolean = false,
    @SerialName("DownloadDirectory") val downloadDirectory: String? = null,
    @SerialName("Warning") val warning: String? = null,
    /** Whether search includes videos and videos can be streamed with picture (plugin 1.8.0.0 and later). */
    @SerialName("VideoAvailable") val videoAvailable: Boolean = false,
    /** Whether YouTube Music tracks can be added to playlists, see [PlaylistAdditionDto] (plugin 1.9.0.0 and later). */
    @SerialName("PlaylistAdditionAvailable") val playlistAdditionAvailable: Boolean = false,
)

@Serializable
data class YouTubeArtistDto(
    /** YouTube Music channel id, empty for artists without an artist page. */
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
)

/** A YouTube Music search result of the plugin; sent back as is to download the track. */
@Serializable
data class YouTubeSongDto(
    @SerialName("VideoId") val videoId: String,
    @SerialName("Title") val title: String = "",
    @SerialName("Artist") val artist: String = "",
    @SerialName("Artists") val artists: List<YouTubeArtistDto> = emptyList(),
    @SerialName("Album") val album: String = "",
    @SerialName("AlbumId") val albumId: String = "",
    @SerialName("ThumbnailUrl") val thumbnailUrl: String? = null,
    /** In seconds. */
    @SerialName("Duration") val duration: Int = 0,
    /** A (music) video rather than a song with a still cover. */
    @SerialName("IsVideo") val isVideo: Boolean = false,
)

@Serializable
data class YouTubeArtistSummaryDto(
    /** Channel id of the artist page. */
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("ThumbnailUrl") val thumbnailUrl: String? = null,
)

@Serializable
data class YouTubeAlbumSummaryDto(
    @SerialName("Id") val id: String,
    @SerialName("Title") val title: String = "",
    /** As displayed by YouTube Music, e.g. "Album", "Single" or "EP". */
    @SerialName("ReleaseType") val releaseType: String? = null,
    @SerialName("Year") val year: Int? = null,
    @SerialName("ThumbnailUrl") val thumbnailUrl: String? = null,
)

@Serializable
data class YouTubeArtistPageDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("ThumbnailUrl") val thumbnailUrl: String? = null,
    @SerialName("Description") val description: String? = null,
    @SerialName("Albums") val albums: List<YouTubeAlbumSummaryDto> = emptyList(),
    /** The artist's most popular songs. */
    @SerialName("Songs") val songs: List<YouTubeSongDto> = emptyList(),
)

@Serializable
data class YouTubeAlbumDto(
    @SerialName("Id") val id: String,
    @SerialName("Title") val title: String = "",
    @SerialName("Artist") val artist: String = "",
    @SerialName("Artists") val artists: List<YouTubeArtistDto> = emptyList(),
    @SerialName("ReleaseType") val releaseType: String? = null,
    @SerialName("Year") val year: Int? = null,
    @SerialName("ThumbnailUrl") val thumbnailUrl: String? = null,
    @SerialName("Songs") val songs: List<YouTubeSongDto> = emptyList(),
)

@Serializable
data class DownloadJobDto(
    @SerialName("Id") val id: String,
    @SerialName("VideoId") val videoId: String = "",
    @SerialName("Title") val title: String = "",
    /** Queued, Downloading, Importing, Completed or Failed. */
    @SerialName("State") val state: String = "",
    @SerialName("Message") val message: String? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("ItemId") val itemId: String? = null,
) {
    val isFinished: Boolean get() = state == "Completed" || state == "Failed"
}

/** YouTube Music tracks the plugin adds to a playlist, downloading those that aren't in the library first. */
@Serializable
data class PlaylistAdditionDto(
    @SerialName("Id") val id: String,
    @SerialName("PlaylistName") val playlistName: String = "",
    @SerialName("Total") val total: Int = 0,
    @SerialName("Added") val added: Int = 0,
    @SerialName("AlreadyInPlaylist") val alreadyInPlaylist: Int = 0,
    /** Why tracks couldn't be added, one entry per track. */
    @SerialName("Failures") val failures: List<String> = emptyList(),
    @SerialName("Finished") val finished: Boolean = false,
    /** Downloads of the tracks that weren't in the library. */
    @SerialName("Jobs") val jobs: List<DownloadJobDto> = emptyList(),
)

/** A downloaded YouTube Music track; [codec] is a display name, [bitRate] in kbps. */
@Serializable
data class DownloadedTrackDto(
    @SerialName("VideoId") val videoId: String,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("BitRate") val bitRate: Int? = null,
)

@Serializable
data class VideoStreamInfoDto(
    @SerialName("VideoCodec") val videoCodec: String? = null,
    @SerialName("VideoBitRate") val videoBitRate: Int? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("FrameRate") val frameRate: Int? = null,
    @SerialName("AudioCodec") val audioCodec: String? = null,
    @SerialName("AudioBitRate") val audioBitRate: Int? = null,
)

@Serializable
data class AudioStreamInfoDto(
    @SerialName("Codec") val codec: String? = null,
    @SerialName("BitRate") val bitRate: Int? = null,
)
