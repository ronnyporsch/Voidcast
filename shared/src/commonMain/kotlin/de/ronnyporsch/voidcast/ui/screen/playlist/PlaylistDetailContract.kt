package de.ronnyporsch.voidcast.ui.screen.playlist

import de.ronnyporsch.voidcast.domain.model.Playlist
import de.ronnyporsch.voidcast.domain.model.Song

data class PlaylistDetailState(
    val playlist: Playlist? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedSongIds: Set<String> = emptySet(),
)

sealed class PlaylistDetailEvent {
    data class SongClicked(val song: Song, val index: Int) : PlaylistDetailEvent()
    data class SongDoubleClicked(val song: Song, val index: Int) : PlaylistDetailEvent()
    /** Replaces the selection: songs selected by touch on mobile (see TouchSelection) or by a Shift-click. */
    data class SongsSelected(val ids: Set<String>) : PlaylistDetailEvent()
    /** Ctrl-click: adds the song to or removes it from the selection. */
    data class SongSelectionToggled(val song: Song) : PlaylistDetailEvent()
    /** Removes the entries at [indices] (positions in the playlist) from it. */
    data class RemoveSongs(val indices: List<Int>) : PlaylistDetailEvent()
    data object PlayAll : PlaylistDetailEvent()
    data object ShuffleAll : PlaylistDetailEvent()
    data class ArtistClicked(val artistName: String) : PlaylistDetailEvent()
    data class AlbumClicked(val albumId: String) : PlaylistDetailEvent()
}

sealed class PlaylistDetailEffect {
    data class PlaySongs(val songs: List<Song>, val startIndex: Int, val shuffle: Boolean? = null) : PlaylistDetailEffect()
    data class NavigateToArtist(val artistName: String) : PlaylistDetailEffect()
    data class NavigateToAlbum(val albumId: String) : PlaylistDetailEffect()
}
