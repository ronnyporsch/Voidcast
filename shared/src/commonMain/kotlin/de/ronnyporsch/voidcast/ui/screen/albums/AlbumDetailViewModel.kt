package de.ronnyporsch.voidcast.ui.screen.albums

import de.ronnyporsch.voidcast.data.youtube.YouTubeDownloadRequest
import androidx.lifecycle.viewModelScope
import de.ronnyporsch.voidcast.data.repository.MusicRepository
import de.ronnyporsch.voidcast.data.youtube.YouTubeService
import de.ronnyporsch.voidcast.domain.model.isYouTubeId
import de.ronnyporsch.voidcast.getPlatform
import de.ronnyporsch.voidcast.mvi.MviStore
import de.ronnyporsch.voidcast.ui.components.selectedThrough
import de.ronnyporsch.voidcast.ui.components.toggled
import kotlinx.coroutines.launch

class AlbumDetailViewModel(
    private val albumId: String,
    private val repository: MusicRepository,
    private val youTube: YouTubeService,
) : MviStore<AlbumDetailState, AlbumDetailEvent, AlbumDetailEffect>(AlbumDetailState(isYouTube = albumId.isYouTubeId)) {

    init {
        loadAlbum()
        if (albumId.isYouTubeId) {
            viewModelScope.launch {
                youTube.capabilities.collect { setState { copy(canDownload = it.canDownload) } }
            }
            viewModelScope.launch {
                youTube.downloads.collect { setState { copy(downloads = it) } }
            }
            viewModelScope.launch {
                youTube.knownFormats.collect { setState { copy(libraryFormats = it) } }
            }
        }
    }

    override fun onEvent(event: AlbumDetailEvent) {
        when (event) {
            is AlbumDetailEvent.SongsSelected -> setState { copy(selectedSongIds = event.ids) }
            is AlbumDetailEvent.SongSelectionToggled ->
                setState { copy(selectedSongIds = selectedSongIds.toggled(event.song.id)) }
            is AlbumDetailEvent.SongRangeSelected -> setState {
                copy(selectedSongIds = album?.songs.orEmpty().selectedThrough(event.index, selectedSongIds))
            }
            is AlbumDetailEvent.SongClicked -> {
                if (getPlatform().isMobile) {
                    val songs = currentState.album?.songs ?: return
                    emitEffect(AlbumDetailEffect.PlaySongs(songs, event.index))
                } else {
                    setState { copy(selectedSongIds = setOf(event.song.id)) }
                }
            }
            is AlbumDetailEvent.SongDoubleClicked -> {
                setState { copy(selectedSongIds = setOf(event.song.id)) }
                val songs = currentState.album?.songs ?: return
                emitEffect(AlbumDetailEffect.PlaySongs(songs, event.index))
            }
            is AlbumDetailEvent.PlayAll -> {
                val songs = currentState.album?.songs ?: return
                emitEffect(AlbumDetailEffect.PlaySongs(songs, 0))
            }
            is AlbumDetailEvent.ShuffleAll -> {
                val songs = currentState.album?.songs ?: return
                emitEffect(AlbumDetailEffect.PlaySongs(songs, 0, shuffle = true))
            }
            is AlbumDetailEvent.ArtistClicked -> {
                // Library artists are looked up by name; YouTube Music ones need their id
                val album = currentState.album
                val artistId = (album?.artists.orEmpty() + album?.songs.orEmpty().flatMap { it.artists })
                    .firstOrNull { it.name == event.artistName && it.id.isYouTubeId }?.id
                emitEffect(AlbumDetailEffect.NavigateToArtist(event.artistName, artistId))
            }
            is AlbumDetailEvent.DownloadSong -> youTube.download(event.request)
            is AlbumDetailEvent.DownloadAll -> currentState.album?.songs?.let { songs -> youTube.download(songs.map(::YouTubeDownloadRequest)) }
        }
    }

    private fun loadAlbum() {
        setState { copy(isLoading = true) }
        launch {
            (if (albumId.isYouTubeId) runCatching { youTube.getAlbum(albumId) } else repository.getAlbum(albumId))
                .onSuccess { setState { copy(album = it, isLoading = false) } }
                .onFailure { setState { copy(error = it.message, isLoading = false) } }
        }
    }
}
