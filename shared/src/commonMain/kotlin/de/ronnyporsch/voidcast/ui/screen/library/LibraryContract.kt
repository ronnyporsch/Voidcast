package de.ronnyporsch.voidcast.ui.screen.library

import de.ronnyporsch.voidcast.data.offline.PlaylistDownloadStatus
import de.ronnyporsch.voidcast.domain.model.Album
import de.ronnyporsch.voidcast.domain.model.Artist
import de.ronnyporsch.voidcast.domain.model.Genre
import de.ronnyporsch.voidcast.domain.model.LibraryCategory
import de.ronnyporsch.voidcast.domain.model.LibraryOrder
import de.ronnyporsch.voidcast.domain.model.LibrarySort
import de.ronnyporsch.voidcast.domain.model.LibraryViewSettings
import de.ronnyporsch.voidcast.domain.model.Playlist
import de.ronnyporsch.voidcast.domain.model.Song

enum class LibraryDetailView { Artists, Albums, Playlists, FavoriteSongs, Genres }

data class LibraryState(
    /** What the sidebar lists and how it orders it. */
    val view: LibraryViewSettings = LibraryViewSettings(),
    val activeDetailView: LibraryDetailView = LibraryDetailView.Playlists,
    val artists: List<Artist> = emptyList(),
    val selectedArtistId: String? = null,
    val selectedArtist: Artist? = null,
    val selectedArtistAlbums: List<Album> = emptyList(),
    val selectedArtistAppearsOnAlbums: List<Album> = emptyList(),
    val selectedArtistBiography: String? = null,
    val selectedArtistLargeImageUrl: String? = null,
    val selectedArtistSongs: List<Song> = emptyList(),
    val selectedArtistSongsLoading: Boolean = false,
    val selectedAlbumId: String? = null,
    val selectedAlbum: Album? = null,
    val playlists: List<Playlist> = emptyList(),
    val selectedPlaylistId: String? = null,
    val selectedPlaylist: Playlist? = null,
    val selectedPlaylistLoading: Boolean = false,
    /** All albums, songs and genres of the library; loaded when their category is first shown. */
    val albums: List<Album> = emptyList(),
    val songs: List<Song> = emptyList(),
    val genres: List<Genre> = emptyList(),
    /** Categories of the sidebar being loaded. */
    val loadingCategories: Set<LibraryCategory> = emptySet(),
    val selectedGenre: Genre? = null,
    val selectedGenreSongs: List<Song> = emptyList(),
    val selectedGenreSongsLoading: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedSongIds: Set<String> = emptySet(),
    /** The server isn't reachable; only the playlists and songs stored on the device are shown. */
    val isOffline: Boolean = false,
    /** Whether playlists can be downloaded on this platform. */
    val canDownloadPlaylists: Boolean = false,
    /** Downloaded playlists, by id. */
    val playlistDownloads: Map<String, PlaylistDownloadStatus> = emptyMap(),
    val favoriteArtists: List<Artist> = emptyList(),
    val favoriteAlbums: List<Album> = emptyList(),
    val favoriteSongs: List<Song> = emptyList(),
)

val LibraryState.category: LibraryCategory get() = view.category

val LibraryState.order: LibraryOrder get() = view.orderOf(view.category)

/** The orders offered for the sidebar's category; leaves out dates the server doesn't tell. */
val LibraryState.availableSorts: List<LibrarySort>
    get() = category.sorts.filter { sort ->
        sort != LibrarySort.DateAdded || when (category) {
            LibraryCategory.Playlists -> playlists.any { it.dateAdded != null }
            LibraryCategory.Artists -> artists.any { it.dateAdded != null }
            LibraryCategory.Albums -> albums.any { it.dateAdded != null }
            LibraryCategory.Songs -> songs.any { it.dateAdded != null }
            LibraryCategory.Favorites -> favoriteAlbums.any { it.dateAdded != null } || favoriteArtists.any { it.dateAdded != null }
            LibraryCategory.Genres -> false
        }
    }

/** The songs the detail pane shows. */
val LibraryState.detailSongs: List<Song>
    get() = when (activeDetailView) {
        LibraryDetailView.Albums -> selectedAlbum?.songs.orEmpty()
        LibraryDetailView.Playlists -> selectedPlaylist?.songs.orEmpty()
        LibraryDetailView.Artists -> selectedArtistSongs
        LibraryDetailView.FavoriteSongs -> favoriteSongs
        LibraryDetailView.Genres -> selectedGenreSongs
    }

sealed class LibraryEvent {
    data class CategorySelected(val category: LibraryCategory) : LibraryEvent()
    /** Orders the sidebar's current category by [sort]; choosing its current sort again reverses it. */
    data class SortSelected(val sort: LibrarySort) : LibraryEvent()
    data object Refresh : LibraryEvent()
    /** Connecting to the server failed; show what is stored on the device meanwhile. */
    data object ConnectionFailed : LibraryEvent()
    data class DownloadPlaylist(val playlist: Playlist) : LibraryEvent()
    data class RemovePlaylistDownload(val playlist: Playlist) : LibraryEvent()
    /** Open the item on its own screen instead of the detail pane (mobile, albums of the artist pane). */
    data class OpenArtistScreen(val artist: Artist) : LibraryEvent()
    data class OpenAlbumScreen(val album: Album) : LibraryEvent()
    data class OpenPlaylistScreen(val playlist: Playlist) : LibraryEvent()
    data class OpenGenreScreen(val genre: Genre) : LibraryEvent()
    /** Shows the item in the detail pane, unless it is shown already. */
    data class Show(val selection: LibrarySelection) : LibraryEvent()
    data class ArtistClicked(val artist: Artist) : LibraryEvent()
    data class ArtistDoubleClicked(val artist: Artist) : LibraryEvent()
    data object PlayAllArtistSongs : LibraryEvent()
    data object ShuffleAllArtistSongs : LibraryEvent()
    data class ArtistSongClicked(val song: Song, val index: Int) : LibraryEvent()
    data class ArtistSongDoubleClicked(val song: Song, val index: Int) : LibraryEvent()
    data class AlbumClicked(val album: Album) : LibraryEvent()
    data class AlbumDoubleClicked(val album: Album) : LibraryEvent()
    data object PlayAllAlbumSongs : LibraryEvent()
    data object ShuffleAllAlbumSongs : LibraryEvent()
    data class PlaylistClicked(val playlist: Playlist) : LibraryEvent()
    data class PlaylistDoubleClicked(val playlist: Playlist) : LibraryEvent()
    data class CreatePlaylist(val name: String) : LibraryEvent()
    data class RemoveSongsFromPlaylist(val playlist: Playlist, val indices: List<Int>) : LibraryEvent()
    data object PlayAllPlaylistSongs : LibraryEvent()
    data object ShuffleAllPlaylistSongs : LibraryEvent()
    data class SongClicked(val song: Song, val index: Int) : LibraryEvent()
    data class SongDoubleClicked(val song: Song, val index: Int) : LibraryEvent()
    /** Ctrl-click: adds the song to or removes it from the selection. */
    data class SongSelectionToggled(val song: Song) : LibraryEvent()
    /** Shift-click: adds the songs from the one at [index] to the nearest selected one to the selection. */
    data class SongRangeSelected(val index: Int) : LibraryEvent()
    data class SongArtistClicked(val artistName: String) : LibraryEvent()
    /** Shows the favorite songs in the detail pane. */
    data object FavoriteSongsClicked : LibraryEvent()
    data object OpenFavoriteSongsScreen : LibraryEvent()
    data object PlayAllFavoriteSongs : LibraryEvent()
    data object ShuffleAllFavoriteSongs : LibraryEvent()
    data class SongAlbumClicked(val albumId: String) : LibraryEvent()
    data class GenreClicked(val genre: Genre) : LibraryEvent()
    data object PlayAllGenreSongs : LibraryEvent()
    data object ShuffleAllGenreSongs : LibraryEvent()
    /** Plays songs of the sidebar, e.g. all songs of the library from the one at [startIndex] on. */
    data class PlaySongs(val songs: List<Song>, val startIndex: Int) : LibraryEvent()
}

sealed class LibraryEffect {
    data class NavigateToArtist(val artistId: String?, val artistName: String) : LibraryEffect()
    data class NavigateToAlbum(val albumId: String) : LibraryEffect()
    data class NavigateToPlaylist(val playlistId: String) : LibraryEffect()
    data object NavigateToFavoriteSongs : LibraryEffect()
    data class NavigateToGenre(val genre: Genre) : LibraryEffect()
    data class PlaySongs(val songs: List<Song>, val startIndex: Int, val shuffle: Boolean? = null) : LibraryEffect()
}
