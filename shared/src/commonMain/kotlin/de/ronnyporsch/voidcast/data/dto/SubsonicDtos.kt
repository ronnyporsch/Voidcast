package de.ronnyporsch.voidcast.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubsonicResponse(
    @SerialName("subsonic-response") val subsonicResponse: SubsonicResponseBody,
)

@Serializable
data class SubsonicResponseBody(
    val status: String = "",
    val version: String = "",
    val error: SubsonicError? = null,
    val artists: ArtistsDto? = null,
    val artist: ArtistWithAlbumsDto? = null,
    val album: AlbumWithSongsDto? = null,
    val playlists: PlaylistsDto? = null,
    val playlist: PlaylistWithSongsDto? = null,
    val searchResult3: SearchResult3Dto? = null,
    val artistInfo2: ArtistInfo2Dto? = null,
    val lyricsList: LyricsListDto? = null,
    val lyrics: LyricsDto? = null,
    val starred2: Starred2Dto? = null,
    val albumList2: AlbumList2Dto? = null,
    val genres: GenresDto? = null,
    val songsByGenre: SongsByGenreDto? = null,
)

@Serializable
data class SubsonicError(
    val code: Int = 0,
    val message: String = "",
)

@Serializable
data class ArtistsDto(
    val index: List<ArtistIndexDto> = emptyList(),
)

@Serializable
data class ArtistIndexDto(
    val name: String = "",
    val artist: List<ArtistDto> = emptyList(),
)

@Serializable
data class ArtistDto(
    val id: String = "",
    val name: String = "",
    val albumCount: Int = 0,
    val coverArt: String? = null,
)

@Serializable
data class ArtistWithAlbumsDto(
    val id: String = "",
    val name: String = "",
    val albumCount: Int = 0,
    val coverArt: String? = null,
    val album: List<AlbumDto> = emptyList(),
)

@Serializable
data class AlbumDto(
    val id: String = "",
    val name: String = "",
    val artist: String = "",
    val artistId: String = "",
    /** OpenSubsonic: every artist credited in the tags, in tag order. */
    val artists: List<ArtistDto> = emptyList(),
    val coverArt: String? = null,
    val songCount: Int = 0,
    val year: Int? = null,
    val duration: Int = 0,
    /** When it got added to the library, ISO 8601. */
    val created: String? = null,
)

@Serializable
data class AlbumWithSongsDto(
    val id: String = "",
    val name: String = "",
    val artist: String = "",
    val artistId: String = "",
    /** OpenSubsonic: every artist credited in the tags, in tag order. */
    val artists: List<ArtistDto> = emptyList(),
    val coverArt: String? = null,
    val songCount: Int = 0,
    val year: Int? = null,
    val duration: Int = 0,
    /** When it got added to the library, ISO 8601. */
    val created: String? = null,
    val song: List<SongDto> = emptyList(),
)

@Serializable
data class SongDto(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val artistId: String = "",
    /** OpenSubsonic: every artist credited in the tags, in tag order. */
    val artists: List<ArtistDto> = emptyList(),
    val album: String = "",
    val albumId: String = "",
    val coverArt: String? = null,
    val duration: Int = 0,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val bitRate: Int? = null,
    /** File extension, e.g. "flac". */
    val suffix: String? = null,
    /** When it got added to the library, ISO 8601. */
    val created: String? = null,
)

@Serializable
data class PlaylistsDto(
    val playlist: List<PlaylistDto> = emptyList(),
)

@Serializable
data class PlaylistDto(
    val id: String = "",
    val name: String = "",
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    /** ISO 8601. */
    val created: String? = null,
)

@Serializable
data class PlaylistWithSongsDto(
    val id: String = "",
    val name: String = "",
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    /** ISO 8601. */
    val created: String? = null,
    val entry: List<SongDto> = emptyList(),
)

@Serializable
data class ArtistInfo2Dto(
    val biography: String? = null,
    val musicBrainzId: String? = null,
    val smallImageUrl: String? = null,
    val mediumImageUrl: String? = null,
    val largeImageUrl: String? = null,
)

@Serializable
data class SearchResult3Dto(
    val artist: List<ArtistDto> = emptyList(),
    val album: List<AlbumDto> = emptyList(),
    val song: List<SongDto> = emptyList(),
)

/** Favorites of getStarred2. */
@Serializable
data class Starred2Dto(
    val artist: List<ArtistDto> = emptyList(),
    val album: List<AlbumDto> = emptyList(),
    val song: List<SongDto> = emptyList(),
)

@Serializable
data class AlbumList2Dto(
    val album: List<AlbumDto> = emptyList(),
)

@Serializable
data class SongsByGenreDto(
    val song: List<SongDto> = emptyList(),
)

@Serializable
data class GenresDto(
    val genre: List<GenreDto> = emptyList(),
)

@Serializable
data class GenreDto(
    /** The genre's name. */
    val value: String = "",
    val songCount: Int = 0,
    val albumCount: Int = 0,
)

/** OpenSubsonic getLyricsBySongId. */
@Serializable
data class LyricsListDto(
    val structuredLyrics: List<StructuredLyricsDto> = emptyList(),
)

@Serializable
data class StructuredLyricsDto(
    val lang: String = "",
    val synced: Boolean = false,
    /** In milliseconds, added to every line's start. */
    val offset: Long = 0,
    val line: List<LyricLineDto> = emptyList(),
)

@Serializable
data class LyricLineDto(
    /** In milliseconds, only set for synced lyrics. */
    val start: Long? = null,
    val value: String = "",
)

/** Plain lyrics of the classic getLyrics endpoint. */
@Serializable
data class LyricsDto(
    val artist: String = "",
    val title: String = "",
    val value: String = "",
)
