package de.ronnyporsch.voidcast.ui.screen.albums

import androidx.lifecycle.viewModelScope
import de.ronnyporsch.voidcast.data.repository.MusicRepository
import de.ronnyporsch.voidcast.data.youtube.YouTubeService
import de.ronnyporsch.voidcast.domain.model.isYouTubeId
import de.ronnyporsch.voidcast.getPlatform
import de.ronnyporsch.voidcast.mvi.MviStore
import de.ronnyporsch.voidcast.ui.components.selectedThrough
import de.ronnyporsch.voidcast.ui.components.toggled
import de.ronnyporsch.voidcast.util.newestAlbumsFirst
import de.ronnyporsch.voidcast.util.newestFirst
import kotlinx.coroutines.launch

class ArtistAlbumsViewModel(
    private val artistId: String?,
    private val artistName: String,
    private val repository: MusicRepository,
    private val youTube: YouTubeService,
) : MviStore<ArtistAlbumsState, ArtistAlbumsEvent, ArtistAlbumsEffect>(
    ArtistAlbumsState(artistId = artistId, artistName = artistName, isYouTube = artistId?.isYouTubeId == true)
) {

    private var resolvedArtistId: String? = artistId

    init {
        if (artistId != null && artistId.isYouTubeId) {
            loadYouTubeArtist(artistId)
        } else if (artistId != null) {
            loadAlbums(artistId)
            loadArtistInfo(artistId)
        } else {
            resolveArtistByName()
        }
    }

    override fun onEvent(event: ArtistAlbumsEvent) {
        when (event) {
            is ArtistAlbumsEvent.SongSelectionToggled ->
                setState { copy(selectedSongIds = selectedSongIds.toggled(event.song.id)) }
            is ArtistAlbumsEvent.SongRangeSelected ->
                setState { copy(selectedSongIds = songs.selectedThrough(event.index, selectedSongIds)) }
            is ArtistAlbumsEvent.AlbumClicked ->
                emitEffect(ArtistAlbumsEffect.NavigateToAlbum(event.album.id))
            is ArtistAlbumsEvent.SongClicked -> {
                if (getPlatform().isMobile) {
                    emitEffect(ArtistAlbumsEffect.PlaySongs(state.value.songs, event.index))
                } else {
                    setState { copy(selectedSongIds = setOf(event.song.id)) }
                }
            }
            is ArtistAlbumsEvent.SongDoubleClicked -> {
                setState { copy(selectedSongIds = setOf(event.song.id)) }
                emitEffect(ArtistAlbumsEffect.PlaySongs(state.value.songs, event.index))
            }
            is ArtistAlbumsEvent.PlayAllSongs -> {
                val songs = state.value.songs
                if (songs.isNotEmpty()) emitEffect(ArtistAlbumsEffect.PlaySongs(songs, 0))
            }
            is ArtistAlbumsEvent.ShuffleAllSongs -> {
                val songs = state.value.songs
                if (songs.isNotEmpty()) emitEffect(ArtistAlbumsEffect.PlaySongs(songs, 0, shuffle = true))
            }
            is ArtistAlbumsEvent.DownloadSong -> youTube.download(event.song)
        }
    }

    /** YouTube Music artists come with their releases and top songs; they have no library albums to search. */
    private fun loadYouTubeArtist(id: String) {
        setState { copy(isLoading = true, isSongsLoading = true) }
        viewModelScope.launch {
            youTube.capabilities.collect { setState { copy(canDownload = it.canDownload) } }
        }
        viewModelScope.launch {
            youTube.downloads.collect { setState { copy(downloads = it) } }
        }
        viewModelScope.launch {
            youTube.knownFormats.collect { setState { copy(libraryFormats = it) } }
        }
        launch {
            runCatching { youTube.getArtist(id) }
                .onSuccess { page ->
                    setState {
                        copy(
                            artistName = page.artist.name,
                            coverArtId = page.artist.coverArtId,
                            biography = page.info.biography,
                            largeImageUrl = page.info.largeImageUrl,
                            albums = page.albums.newestFirst(),
                            songs = page.songs,
                            isLoading = false,
                            isSongsLoading = false,
                        )
                    }
                    // The server looks up the albums' track lists, which takes a moment the first time
                    runCatching { youTube.downloadedAlbumIds(page.albums) }
                        .onSuccess { setState { copy(downloadedAlbumIds = it) } }
                }
                .onFailure { setState { copy(error = it.message, isLoading = false, isSongsLoading = false) } }
        }
    }

    private fun resolveArtistByName() {
        setState { copy(isLoading = true) }
        launch {
            repository.findArtistByName(artistName)
                .onSuccess { artist ->
                    if (artist != null) {
                        resolvedArtistId = artist.id
                        setState { copy(artistId = artist.id) }
                        loadAlbums(artist.id)
                        loadArtistInfo(artist.id)
                    } else {
                        // Artist not in index (e.g. featured/collaborative artist) — search songs
                        setState { copy(isLoading = false) }
                        loadAppearsOnAlbums(artistName, emptyList())
                        loadSongs(emptyList())
                    }
                }
                .onFailure {
                    setState { copy(isLoading = false) }
                    loadAppearsOnAlbums(artistName, emptyList())
                    loadSongs(emptyList())
                }
        }
    }

    private fun loadAlbums(id: String) {
        setState { copy(isLoading = true) }
        launch {
            repository.getArtistAlbums(id)
                .onSuccess { albums ->
                    val dedupedAlbums = albums.distinctBy { it.id.ifEmpty { "${it.name}_${it.artist}" } }.newestFirst()
                    setState { copy(albums = dedupedAlbums, isLoading = false) }
                    loadAppearsOnAlbums(artistName, dedupedAlbums.map { it.id })
                    loadSongs(dedupedAlbums.map { it.id })
                }
                .onFailure { setState { copy(error = it.message, isLoading = false) } }
        }
    }

    private fun loadAppearsOnAlbums(artistName: String, albumIds: List<String>) {
        launch {
            repository.getAppearsOnAlbums(artistName, albumIds)
                .onSuccess { setState { copy(appearsOnAlbums = it.newestFirst()) } }
        }
    }

    private fun loadSongs(albumIds: List<String>) {
        setState { copy(isSongsLoading = true) }
        launch {
            repository.getSongsForArtist(artistName, albumIds)
                .onSuccess { songs -> setState { copy(songs = songs.newestAlbumsFirst(), isSongsLoading = false) } }
                .onFailure { setState { copy(isSongsLoading = false) } }
        }
    }

    private fun loadArtistInfo(id: String) {
        launch {
            repository.getArtistInfo(id)
                .onSuccess { info ->
                    setState {
                        copy(
                            biography = info.biography,
                            largeImageUrl = info.largeImageUrl,
                        )
                    }
                }
        }
    }
}

