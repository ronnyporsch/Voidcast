package de.ronnyporsch.voidcast.ui.screen.playlist

import de.ronnyporsch.voidcast.data.repository.MusicRepository
import de.ronnyporsch.voidcast.data.repository.movedTo
import de.ronnyporsch.voidcast.getPlatform
import de.ronnyporsch.voidcast.mvi.MviStore
import de.ronnyporsch.voidcast.ui.components.toggled
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class PlaylistDetailViewModel(
    private val playlistId: String,
    private val repository: MusicRepository,
    /** Ids of playlists that got changed, e.g. by songs dropped onto this one. */
    playlistChanges: Flow<String> = emptyFlow(),
) : MviStore<PlaylistDetailState, PlaylistDetailEvent, PlaylistDetailEffect>(PlaylistDetailState()) {

    init {
        loadPlaylist()
        launch {
            playlistChanges.collect { if (it == playlistId) loadPlaylist(showLoading = false) }
        }
    }

    override fun onEvent(event: PlaylistDetailEvent) {
        when (event) {
            is PlaylistDetailEvent.SongsSelected -> setState { copy(selectedSongIds = event.ids) }
            is PlaylistDetailEvent.SongSelectionToggled ->
                setState { copy(selectedSongIds = selectedSongIds.toggled(event.song.id)) }
            is PlaylistDetailEvent.SongClicked -> {
                if (getPlatform().isMobile) {
                    val songs = currentState.playlist?.songs ?: return
                    emitEffect(PlaylistDetailEffect.PlaySongs(songs, event.index))
                } else {
                    setState { copy(selectedSongIds = setOf(event.song.id)) }
                }
            }
            is PlaylistDetailEvent.SongDoubleClicked -> {
                setState { copy(selectedSongIds = setOf(event.song.id)) }
                val songs = currentState.playlist?.songs ?: return
                emitEffect(PlaylistDetailEffect.PlaySongs(songs, event.index))
            }
            is PlaylistDetailEvent.RemoveSongs -> launch {
                repository.removeSongsFromPlaylist(playlistId, event.indices)
                    .onSuccess {
                        setState { copy(selectedSongIds = emptySet()) }
                        // Reload in the background to keep the list and its scroll position
                        loadPlaylist(showLoading = false)
                    }
            }
            is PlaylistDetailEvent.MoveSongs -> moveSongs(event.indices, event.before)
            is PlaylistDetailEvent.PlayAll -> {
                val songs = currentState.playlist?.songs ?: return
                emitEffect(PlaylistDetailEffect.PlaySongs(songs, 0))
            }
            is PlaylistDetailEvent.ShuffleAll -> {
                val songs = currentState.playlist?.songs ?: return
                emitEffect(PlaylistDetailEffect.PlaySongs(songs, 0, shuffle = true))
            }
            is PlaylistDetailEvent.ArtistClicked -> {
                emitEffect(PlaylistDetailEffect.NavigateToArtist(event.artistName))
            }
            is PlaylistDetailEvent.AlbumClicked -> {
                emitEffect(PlaylistDetailEffect.NavigateToAlbum(event.albumId))
            }
        }
    }

    private fun moveSongs(indices: List<Int>, before: Int) {
        val playlist = currentState.playlist ?: return
        // Counted in the playlist without the moved songs
        val to = before - indices.distinct().count { it < before }
        val moved = playlist.songs.movedTo(indices, to)
        if (moved == playlist.songs) return
        // Shown right away, the server takes a moment
        setState { copy(playlist = playlist.copy(songs = moved)) }
        launch {
            repository.moveSongsInPlaylist(playlistId, indices, to)
                .onFailure { setState { copy(error = it.message) } }
            loadPlaylist(showLoading = false)
        }
    }

    private fun loadPlaylist(showLoading: Boolean = true) {
        if (showLoading) setState { copy(isLoading = true) }
        launch {
            repository.getPlaylist(playlistId)
                .onSuccess { setState { copy(playlist = it, isLoading = false) } }
                .onFailure { setState { copy(error = it.message, isLoading = false) } }
        }
    }
}
