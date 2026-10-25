package de.ronnyporsch.voidcast.ui.screen.albums

import de.ronnyporsch.voidcast.domain.model.AudioStreamInfo
import de.ronnyporsch.voidcast.data.youtube.DownloadStatus
import de.ronnyporsch.voidcast.domain.model.Album
import de.ronnyporsch.voidcast.domain.model.Song

data class ArtistAlbumsState(
    /** Null while the artist is only known by name (and if it isn't in the library). */
    val artistId: String? = null,
    val artistName: String = "",
    val coverArtId: String? = null,
    val biography: String? = null,
    val largeImageUrl: String? = null,
    val albums: List<Album> = emptyList(),
    val appearsOnAlbums: List<Album> = emptyList(),
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = false,
    val isSongsLoading: Boolean = false,
    val error: String? = null,
    val selectedSongIds: Set<String> = emptySet(),
    /** Whether this is a YouTube Music artist, whose songs can be downloaded into the library if [canDownload]. */
    val isYouTube: Boolean = false,
    val canDownload: Boolean = false,
    /** YouTube Music downloads by song id. */
    val downloads: Map<String, DownloadStatus> = emptyMap(),
    /** Codec and bitrate of downloaded YouTube Music songs, by song id. */
    val libraryFormats: Map<String, AudioStreamInfo> = emptyMap(),
    /** YouTube Music albums whose songs are all in the library. */
    val downloadedAlbumIds: Set<String> = emptySet(),
)

sealed class ArtistAlbumsEvent {
    data class AlbumClicked(val album: Album) : ArtistAlbumsEvent()
    data class SongClicked(val song: Song, val index: Int) : ArtistAlbumsEvent()
    data class SongDoubleClicked(val song: Song, val index: Int) : ArtistAlbumsEvent()
    /** Ctrl-click: adds the song to or removes it from the selection. */
    /** Mobile: the songs selected by touch, see TouchSelection. */
    data class SongsSelected(val ids: Set<String>) : ArtistAlbumsEvent()
    data class SongSelectionToggled(val song: Song) : ArtistAlbumsEvent()
    /** Shift-click: adds the songs from the one at [index] to the nearest selected one to the selection. */
    data class SongRangeSelected(val index: Int) : ArtistAlbumsEvent()
    data object PlayAllSongs : ArtistAlbumsEvent()
    data object ShuffleAllSongs : ArtistAlbumsEvent()
    /** Downloads a YouTube Music song into the library. */
    data class DownloadSong(val song: Song) : ArtistAlbumsEvent()
}

sealed class ArtistAlbumsEffect {
    data class NavigateToAlbum(val albumId: String) : ArtistAlbumsEffect()
    data class PlaySongs(val songs: List<Song>, val startIndex: Int, val shuffle: Boolean? = null) : ArtistAlbumsEffect()
}
