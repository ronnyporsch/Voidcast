package de.ronnyporsch.voidcast.ui.screen.search

import androidx.lifecycle.viewModelScope
import de.ronnyporsch.voidcast.data.repository.MusicRepository
import de.ronnyporsch.voidcast.data.youtube.YouTubeLink
import de.ronnyporsch.voidcast.data.youtube.YouTubeService
import de.ronnyporsch.voidcast.domain.model.Artist
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.domain.model.YOUTUBE_SONG_ID_PREFIX
import de.ronnyporsch.voidcast.getPlatform
import de.ronnyporsch.voidcast.mvi.MviStore
import de.ronnyporsch.voidcast.ui.components.selectedThrough
import de.ronnyporsch.voidcast.ui.components.toggled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchViewModel(
    private val repository: MusicRepository,
    private val youTube: YouTubeService,
) : MviStore<SearchState, SearchEvent, SearchEffect>(SearchState()) {

    private var librarySearchJob: Job? = null
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
            is SearchEvent.SongsSelected -> setState { copy(selectedSongIds = event.ids) }
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
            is SearchEvent.DownloadYouTubeSong -> youTube.download(event.request)
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
        val link = YouTubeLink.parse(query)
        if (link != null && currentState.youTubeAvailable) {
            openYouTubeLink(link)
            return
        }
        setState { copy(isLoading = true) }
        librarySearchJob?.cancel()
        librarySearchJob = viewModelScope.launch {
            // The search APIs don't cover playlists, so match them by name
            val matchingPlaylists = async {
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

    private fun searchYouTube(query: String) = loadYouTube {
        // Debounce: the query changes on every keystroke
        delay(400)
        // Artists are optional: plugins before artist search answer with 404
        val artists = async { runCatching { youTube.searchArtists(query) }.onFailure { if (it is CancellationException) throw it } }
        val songs = youTube.searchSongs(query)
        artists.await().getOrDefault(emptyList()) to songs
    }

    /** Shows just what [link] points to; the library has nothing to match a link. */
    private fun openYouTubeLink(link: YouTubeLink) {
        librarySearchJob?.cancel()
        setState {
            copy(artists = emptyList(), playlists = emptyList(), albums = emptyList(), songs = emptyList(), isLoading = false, hasSearched = true)
        }
        loadYouTube {
            when (link) {
                is YouTubeLink.Video -> {
                    if (!youTube.currentCapabilities.songLookup) error("Update the Voidcast plugin on the server to open YouTube links")
                    emptyList<Artist>() to listOf(youTube.getSong(link.videoId))
                }
                is YouTubeLink.Channel -> listOf(youTube.getArtist(YOUTUBE_SONG_ID_PREFIX + link.channelId).artist) to emptyList()
            }
        }
    }

    /** Replaces the YouTube Music results with those [load] finds, cancelling a previous search. */
    private fun loadYouTube(load: suspend CoroutineScope.() -> Pair<List<Artist>, List<Song>>) {
        youTubeSearchJob?.cancel()
        setState { copy(isYouTubeLoading = true) }
        youTubeSearchJob = viewModelScope.launch {
            try {
                val (artists, songs) = load()
                setState {
                    copy(
                        youTubeArtists = artists,
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
