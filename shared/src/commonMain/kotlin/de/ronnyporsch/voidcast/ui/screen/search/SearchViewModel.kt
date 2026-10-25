package de.ronnyporsch.voidcast.ui.screen.search

import androidx.lifecycle.viewModelScope
import de.ronnyporsch.voidcast.data.repository.MusicRepository
import de.ronnyporsch.voidcast.data.youtube.YouTubeService
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.getPlatform
import de.ronnyporsch.voidcast.mvi.MviStore
import de.ronnyporsch.voidcast.ui.components.selectedThrough
import de.ronnyporsch.voidcast.ui.components.toggled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchViewModel(
    private val repository: MusicRepository,
    private val youTube: YouTubeService,
) : MviStore<SearchState, SearchEvent, SearchEffect>(SearchState()) {

    private var youTubeSearchJob: Job? = null

    init {
        // Server-side YouTube support depends on the connected server
        viewModelScope.launch {
            youTube.capabilities.collect { capabilities ->
                setState {
                    copy(
                        youTubeAvailable = capabilities.available,
                        youTubeDownloadAvailable = capabilities.canDownload,
                    )
                }
            }
        }
        viewModelScope.launch {
            youTube.pluginMissing.collect { setState { copy(youTubePluginMissing = it) } }
        }
        viewModelScope.launch {
            youTube.downloads.collect { setState { copy(downloads = it) } }
        }
        viewModelScope.launch {
            youTube.knownFormats.collect { setState { copy(youTubeFormats = it) } }
        }
    }

    override fun onEvent(event: SearchEvent) {
        when (event) {
            is SearchEvent.SongSelectionToggled ->
                setState { copy(selectedSongIds = selectedSongIds.toggled(event.song.id)) }
            is SearchEvent.SongRangeSelected ->
                setState { copy(selectedSongIds = songs.selectedThrough(event.index, selectedSongIds)) }
            is SearchEvent.QueryChanged -> {
                setState { copy(query = event.query) }
                if (event.query.length >= 2) {
                    performSearch(event.query)
                }
            }
            is SearchEvent.Search -> performSearch(currentState.query)
            is SearchEvent.ArtistClicked ->
                emitEffect(SearchEffect.NavigateToArtist(event.artist.id, event.artist.name))
            is SearchEvent.ArtistNameClicked ->
                emitEffect(SearchEffect.NavigateToArtist(event.artistId, event.artistName))
            is SearchEvent.AlbumClicked ->
                emitEffect(SearchEffect.NavigateToAlbum(event.album.id))
            is SearchEvent.PlaylistClicked ->
                emitEffect(SearchEffect.NavigateToPlaylist(event.playlist.id))
            is SearchEvent.SongClicked -> selectOrPlay(currentState.songs, event.song, event.index)
            is SearchEvent.SongDoubleClicked -> play(currentState.songs, event.song, event.index)
            is SearchEvent.YouTubeSongClicked -> selectOrPlay(currentState.youTubeSongs, event.song, event.index)
            is SearchEvent.YouTubeSongDoubleClicked -> play(currentState.youTubeSongs, event.song, event.index)
            is SearchEvent.DownloadYouTubeSong -> youTube.download(event.song)
        }
    }

    private fun selectOrPlay(songs: List<Song>, song: Song, index: Int) {
        if (getPlatform().isMobile) {
            emitEffect(SearchEffect.PlaySongs(songs, index))
        } else {
            setState { copy(selectedSongIds = setOf(song.id)) }
        }
    }

    private fun play(songs: List<Song>, song: Song, index: Int) {
        setState { copy(selectedSongIds = setOf(song.id)) }
        emitEffect(SearchEffect.PlaySongs(songs, index))
    }

    private fun performSearch(query: String) {
        if (query.isBlank()) return
        setState { copy(isLoading = true) }
        launch {
            // The search APIs don't cover playlists, so match them by name
            val matchingPlaylists = viewModelScope.async {
                repository.getPlaylists().getOrDefault(emptyList()).filter { it.name.contains(query.trim(), ignoreCase = true) }
            }
            val results = repository.search(query)
            val playlists = matchingPlaylists.await()
            results
                .onSuccess {
                    setState {
                        copy(
                            artists = it.artists,
                            playlists = playlists,
                            albums = it.albums,
                            songs = it.songs,
                            isLoading = false,
                            hasSearched = true,
                        )
                    }
                }
                .onFailure {
                    setState { copy(playlists = playlists, isLoading = false, hasSearched = true) }
                }
        }
        if (currentState.youTubeAvailable) searchYouTube(query)
    }

    private fun searchYouTube(query: String) {
        youTubeSearchJob?.cancel()
        setState { copy(isYouTubeLoading = true) }
        youTubeSearchJob = viewModelScope.launch {
            // Debounce: the query changes on every keystroke
            delay(400)
            try {
                // Artists are optional: plugins before artist search answer with 404
                val artists = async { runCatching { youTube.searchArtists(query) }.onFailure { if (it is CancellationException) throw it } }
                val songs = youTube.searchSongs(query)
                val foundArtists = artists.await().getOrDefault(emptyList())
                setState {
                    copy(
                        youTubeArtists = foundArtists,
                        youTubeSongs = songs,
                        isYouTubeLoading = false,
                        youTubeError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState {
                    copy(
                        youTubeArtists = emptyList(),
                        youTubeSongs = emptyList(),
                        isYouTubeLoading = false,
                        youTubeError = e.message ?: "YouTube Music search failed",
                    )
                }
            }
        }
    }
}
