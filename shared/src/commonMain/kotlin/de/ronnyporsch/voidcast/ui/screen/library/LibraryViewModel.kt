package de.ronnyporsch.voidcast.ui.screen.library

import androidx.compose.foundation.lazy.LazyListState
import de.ronnyporsch.voidcast.data.offline.PlaylistDownloads
import de.ronnyporsch.voidcast.data.repository.MusicRepository
import de.ronnyporsch.voidcast.domain.model.Genre
import de.ronnyporsch.voidcast.domain.model.LibraryCategory
import de.ronnyporsch.voidcast.domain.model.LibraryOrder
import de.ronnyporsch.voidcast.domain.model.LibraryViewSettings
import de.ronnyporsch.voidcast.getPlatform
import de.ronnyporsch.voidcast.mvi.MviStore
import de.ronnyporsch.voidcast.ui.components.selectedThrough
import de.ronnyporsch.voidcast.ui.components.toggled
import de.ronnyporsch.voidcast.util.newestAlbumsFirst
import de.ronnyporsch.voidcast.util.newestFirst
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import de.ronnyporsch.voidcast.data.repository.Favorites
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow

class LibraryViewModel(
    private val repository: MusicRepository,
    /** Show the loading indicator until the first (auto-)login has connected the repository. */
    awaitingConnection: Boolean = false,
    /** Ids of playlists changed elsewhere, e.g. through the song context menu. */
    playlistChanges: Flow<String> = emptyFlow(),
    private val downloads: PlaylistDownloads? = null,
    favorites: Favorites? = null,
    /** What the sidebar showed last time. */
    initialView: LibraryViewSettings = LibraryViewSettings(),
    /** Called when the sidebar's category or order changed, to keep them. */
    private val onViewChanged: (LibraryViewSettings) -> Unit = {},
) : MviStore<LibraryState, LibraryEvent, LibraryEffect>(
    LibraryState(view = initialView, isLoading = awaitingConnection, canDownloadPlaylists = downloads?.isSupported == true)
) {

    // The sidebar is shown next to other screens too and keeps its scroll position across them
    val sidebarStates: Map<LibraryCategory, LazyListState> = LibraryCategory.entries.associateWith { LazyListState() }

    init {
        if (repository.isConfigured) {
            loadAll()
        }
        launch {
            playlistChanges.collect { playlistId ->
                repository.getPlaylists().onSuccess { setState { copy(playlists = it) } }
                if (currentState.selectedPlaylistId == playlistId) loadPlaylist(playlistId, force = true)
            }
        }
        if (downloads != null) {
            launch { downloads.status.collect { setState { copy(playlistDownloads = it) } } }
            // Offline the artists are those of the downloaded songs, e.g. fewer after removing a download
            launch {
                downloads.downloadedSongIds.collect {
                    if (currentState.isOffline) repository.getArtists().onSuccess { setState { copy(artists = it) } }
                }
            }
        }
        if (favorites != null) {
            // Unmarked favorites disappear right away, before the server confirmed
            launch {
                combine(favorites.items, favorites.ids) { items, ids -> items to ids }.collect { (items, ids) ->
                    setState {
                        copy(
                            favoriteArtists = items.artists.filter { it.id in ids },
                            favoriteAlbums = items.albums.filter { it.id in ids },
                            favoriteSongs = items.songs.filter { it.id in ids },
                        )
                    }
                }
            }
        }
    }

    override fun onEvent(event: LibraryEvent) {
        when (event) {
            is LibraryEvent.SongSelectionToggled ->
                setState { copy(selectedSongIds = selectedSongIds.toggled(event.song.id)) }
            is LibraryEvent.SongsSelected -> setState { copy(selectedSongIds = event.ids) }
            is LibraryEvent.SongRangeSelected ->
                setState { copy(selectedSongIds = detailSongs.selectedThrough(event.index, selectedSongIds)) }
            is LibraryEvent.CategorySelected -> {
                setView(currentState.view.copy(category = event.category))
                loadTabData(event.category)
            }
            is LibraryEvent.SortSelected -> {
                val view = currentState.view
                val current = view.orderOf(view.category)
                val order = if (current.sort == event.sort) current.copy(reversed = !current.reversed) else LibraryOrder(event.sort)
                setView(view.copy(orders = view.orders + (view.category to order)))
                // Show the start of the reordered list
                launch { sidebarStates.getValue(view.category).scrollToItem(0) }
            }
            is LibraryEvent.Refresh -> loadAll()
            is LibraryEvent.ConnectionFailed -> if (currentState.isLoading) loadOffline()
            is LibraryEvent.DownloadPlaylist -> downloads?.download(event.playlist)
            is LibraryEvent.RemovePlaylistDownload -> downloads?.remove(event.playlist.id)
            is LibraryEvent.PlaylistFolderToggled -> {
                val view = currentState.view
                val expanded = view.expandedPlaylistFolders
                setView(view.copy(expandedPlaylistFolders = if (event.folder in expanded) expanded - event.folder else expanded + event.folder))
            }
            is LibraryEvent.Show -> if (currentState.selection?.key != event.selection.key) {
                when (val selection = event.selection) {
                    is LibrarySelection.Artist -> onEvent(LibraryEvent.ArtistClicked(selection.artist))
                    is LibrarySelection.Album -> onEvent(LibraryEvent.AlbumClicked(selection.album))
                    is LibrarySelection.Playlist -> selectPlaylist(selection.playlistId)
                    LibrarySelection.FavoriteSongs -> onEvent(LibraryEvent.FavoriteSongsClicked)
                    is LibrarySelection.Genre -> onEvent(LibraryEvent.GenreClicked(selection.genre))
                }
            }
            is LibraryEvent.ArtistClicked -> {
                setState {
                    copy(
                        activeDetailView = LibraryDetailView.Artists,
                        selectedArtist = event.artist,
                        selectedArtistId = event.artist.id,
                        selectedArtistAlbums = emptyList(),
                        selectedArtistAppearsOnAlbums = emptyList(),
                        selectedArtistBiography = null,
                        selectedArtistLargeImageUrl = null,
                        selectedArtistSongs = emptyList(),
                        selectedArtistSongsLoading = false,
                        selectedAlbumId = null,
                        selectedAlbum = null,
                        selectedPlaylistId = null,
                        selectedPlaylist = null,
                        selectedPlaylistLoading = false,
                    )
                }
                if (event.artist.id.isNotEmpty()) {
                    loadArtistAlbums(event.artist.id)
                    loadArtistInfo(event.artist.id)
                } else {
                    resolveArtistByName(event.artist.name)
                }
            }
            is LibraryEvent.ArtistDoubleClicked -> {
                onEvent(LibraryEvent.ArtistClicked(event.artist))
                launch {
                    val albumIds = if (event.artist.id.isEmpty()) {
                        emptyList()
                    } else {
                        repository.getArtistAlbums(event.artist.id).getOrDefault(emptyList()).map { it.id }
                    }
                    repository.getSongsForArtist(event.artist.name, albumIds).onSuccess { songs ->
                        if (songs.isNotEmpty()) {
                            emitEffect(LibraryEffect.PlaySongs(songs, 0, shuffle = true))
                        }
                    }
                }
            }
            is LibraryEvent.PlayAllArtistSongs -> {
                val songs = currentState.selectedArtistSongs
                if (songs.isNotEmpty()) emitEffect(LibraryEffect.PlaySongs(songs, 0))
            }
            is LibraryEvent.ShuffleAllArtistSongs -> {
                val songs = currentState.selectedArtistSongs
                if (songs.isNotEmpty()) emitEffect(LibraryEffect.PlaySongs(songs, 0, shuffle = true))
            }
            is LibraryEvent.ArtistSongClicked -> {
                if (getPlatform().isMobile) {
                    emitEffect(LibraryEffect.PlaySongs(currentState.selectedArtistSongs, event.index))
                } else {
                    setState { copy(selectedSongIds = setOf(event.song.id)) }
                }
            }
            is LibraryEvent.ArtistSongDoubleClicked -> {
                setState { copy(selectedSongIds = setOf(event.song.id)) }
                emitEffect(LibraryEffect.PlaySongs(currentState.selectedArtistSongs, event.index))
            }
            is LibraryEvent.AlbumClicked -> {
                setState {
                    copy(
                        activeDetailView = LibraryDetailView.Albums,
                        selectedAlbum = event.album,
                        selectedAlbumId = event.album.id,
                        selectedArtistId = null,
                        selectedArtist = null,
                        selectedArtistAlbums = emptyList(),
                        selectedArtistAppearsOnAlbums = emptyList(),
                        selectedArtistBiography = null,
                        selectedArtistLargeImageUrl = null,
                        selectedArtistSongs = emptyList(),
                        selectedArtistSongsLoading = false,
                        selectedPlaylistId = null,
                        selectedPlaylist = null,
                        selectedPlaylistLoading = false,
                    )
                }
                if (event.album.id.isNotEmpty()) {
                    loadAlbum(event.album.id)
                }
            }
            is LibraryEvent.AlbumDoubleClicked -> {
                onEvent(LibraryEvent.AlbumClicked(event.album))
                launch {
                    repository.getAlbum(event.album.id).onSuccess { fullAlbum ->
                        if (fullAlbum.songs.isNotEmpty()) {
                            emitEffect(LibraryEffect.PlaySongs(fullAlbum.songs, 0, shuffle = true))
                        }
                    }
                }
            }
            is LibraryEvent.PlayAllAlbumSongs -> {
                val songs = currentState.selectedAlbum?.songs.orEmpty()
                if (songs.isNotEmpty()) emitEffect(LibraryEffect.PlaySongs(songs, 0))
            }
            is LibraryEvent.ShuffleAllAlbumSongs -> {
                val songs = currentState.selectedAlbum?.songs.orEmpty()
                if (songs.isNotEmpty()) emitEffect(LibraryEffect.PlaySongs(songs, 0, shuffle = true))
            }
            is LibraryEvent.PlaylistClicked -> selectPlaylist(event.playlist.id)
            is LibraryEvent.PlaylistDoubleClicked -> {
                onEvent(LibraryEvent.PlaylistClicked(event.playlist))
                launch {
                    repository.getPlaylist(event.playlist.id).onSuccess { fullPlaylist ->
                        if (fullPlaylist.songs.isNotEmpty()) {
                            emitEffect(LibraryEffect.PlaySongs(fullPlaylist.songs, 0, shuffle = true))
                        }
                    }
                }
            }
            // The playlists get reloaded through playlistChanges
            is LibraryEvent.CreatePlaylist -> launch { repository.createPlaylist(event.name) }
            is LibraryEvent.RemoveSongsFromPlaylist -> launch {
                repository.removeSongsFromPlaylist(event.playlist.id, event.indices)
                    .onSuccess { setState { copy(selectedSongIds = emptySet()) } }
            }
            is LibraryEvent.PlayAllPlaylistSongs -> {
                val songs = currentState.selectedPlaylist?.songs.orEmpty()
                if (songs.isNotEmpty()) emitEffect(LibraryEffect.PlaySongs(songs, 0))
            }
            is LibraryEvent.ShuffleAllPlaylistSongs -> {
                val songs = currentState.selectedPlaylist?.songs.orEmpty()
                if (songs.isNotEmpty()) emitEffect(LibraryEffect.PlaySongs(songs, 0, shuffle = true))
            }
            is LibraryEvent.SongClicked -> {
                val songs = currentState.detailSongs
                if (songs.isNotEmpty()) {
                    if (getPlatform().isMobile) {
                        emitEffect(LibraryEffect.PlaySongs(songs, event.index))
                    } else {
                        setState { copy(selectedSongIds = setOf(event.song.id)) }
                    }
                }
            }
            is LibraryEvent.SongDoubleClicked -> {
                setState { copy(selectedSongIds = setOf(event.song.id)) }
                val songs = currentState.detailSongs
                if (songs.isNotEmpty()) {
                    emitEffect(LibraryEffect.PlaySongs(songs, event.index))
                }
            }
            is LibraryEvent.OpenArtistScreen ->
                emitEffect(LibraryEffect.NavigateToArtist(event.artist.id.ifEmpty { null }, event.artist.name))
            is LibraryEvent.OpenAlbumScreen ->
                emitEffect(LibraryEffect.NavigateToAlbum(event.album.id))
            is LibraryEvent.OpenPlaylistScreen ->
                emitEffect(LibraryEffect.NavigateToPlaylist(event.playlist.id))
            is LibraryEvent.OpenGenreScreen ->
                emitEffect(LibraryEffect.NavigateToGenre(event.genre))
            is LibraryEvent.GenreClicked -> selectGenre(event.genre)
            is LibraryEvent.PlayAllGenreSongs -> {
                val songs = currentState.selectedGenreSongs
                if (songs.isNotEmpty()) emitEffect(LibraryEffect.PlaySongs(songs, 0))
            }
            is LibraryEvent.ShuffleAllGenreSongs -> {
                val songs = currentState.selectedGenreSongs
                if (songs.isNotEmpty()) emitEffect(LibraryEffect.PlaySongs(songs, 0, shuffle = true))
            }
            is LibraryEvent.PlaySongs ->
                if (event.songs.isNotEmpty()) emitEffect(LibraryEffect.PlaySongs(event.songs, event.startIndex))
            is LibraryEvent.SongArtistClicked -> {
                emitEffect(LibraryEffect.NavigateToArtist(null, event.artistName))
            }
            is LibraryEvent.SongAlbumClicked -> {
                emitEffect(LibraryEffect.NavigateToAlbum(event.albumId))
            }
            is LibraryEvent.FavoriteSongsClicked -> setState {
                copy(
                    activeDetailView = LibraryDetailView.FavoriteSongs,
                    selectedArtistId = null,
                    selectedArtist = null,
                    selectedAlbumId = null,
                    selectedAlbum = null,
                    selectedPlaylistId = null,
                    selectedPlaylist = null,
                    selectedPlaylistLoading = false,
                )
            }
            is LibraryEvent.OpenFavoriteSongsScreen -> emitEffect(LibraryEffect.NavigateToFavoriteSongs)
            is LibraryEvent.PlayAllFavoriteSongs -> {
                val songs = currentState.favoriteSongs
                if (songs.isNotEmpty()) emitEffect(LibraryEffect.PlaySongs(songs, 0))
            }
            is LibraryEvent.ShuffleAllFavoriteSongs -> {
                val songs = currentState.favoriteSongs
                if (songs.isNotEmpty()) emitEffect(LibraryEffect.PlaySongs(songs, 0, shuffle = true))
            }
        }
    }

    private fun setView(view: LibraryViewSettings) {
        setState { copy(view = view) }
        onViewChanged(view)
    }

    private fun selectGenre(genre: Genre) {
        setState {
            copy(
                activeDetailView = LibraryDetailView.Genres,
                selectedGenre = genre,
                selectedGenreSongs = emptyList(),
                selectedGenreSongsLoading = true,
                selectedArtistId = null,
                selectedArtist = null,
                selectedAlbumId = null,
                selectedAlbum = null,
                selectedPlaylistId = null,
                selectedPlaylist = null,
                selectedPlaylistLoading = false,
            )
        }
        launch {
            val songs = repository.getGenreSongs(genre).getOrDefault(emptyList())
            if (currentState.selectedGenre?.id == genre.id) {
                setState { copy(selectedGenreSongs = songs, selectedGenreSongsLoading = false) }
            }
        }
    }

    private fun selectPlaylist(playlistId: String) {
        setState {
            copy(
                activeDetailView = LibraryDetailView.Playlists,
                selectedPlaylistId = playlistId,
                selectedPlaylist = null,
                selectedPlaylistLoading = true,
                selectedArtistId = null,
                selectedArtist = null,
                selectedAlbumId = null,
                selectedAlbum = null,
                selectedArtistAlbums = emptyList(),
                selectedArtistAppearsOnAlbums = emptyList(),
                selectedArtistBiography = null,
                selectedArtistLargeImageUrl = null,
                selectedArtistSongs = emptyList(),
                selectedArtistSongsLoading = false,
            )
        }
        loadPlaylist(playlistId)
    }

    /** Shows the playlists and downloaded songs stored on the device, as long as the server isn't reachable. */
    private fun loadOffline() {
        launch {
            val playlists = repository.getPlaylists().getOrDefault(emptyList())
            val artists = repository.getArtists().getOrDefault(emptyList())
            setState {
                copy(
                    isLoading = false,
                    isOffline = true,
                    playlists = playlists,
                    artists = artists,
                    // The downloaded songs tell them now
                    albums = emptyList(),
                    songs = emptyList(),
                    genres = emptyList(),
                    activeDetailView = LibraryDetailView.Playlists,
                )
            }
            ensureDefaultSelection()
            loadTabData(currentState.category)
        }
    }

    private fun loadAll() {
        // Albums, songs and genres get loaded again when shown, they may be of a different server now
        setState { copy(isLoading = true, isOffline = false, error = null, albums = emptyList(), songs = emptyList(), genres = emptyList()) }
        launch {
            val artistsResult = repository.getArtists()
            val playlistsResult = repository.getPlaylists()

            val artists = artistsResult.getOrDefault(emptyList())
            val playlists = playlistsResult.getOrDefault(emptyList())

            setState {
                copy(
                    isLoading = false,
                    artists = artists,
                    playlists = playlists,
                    error = artistsResult.exceptionOrNull()?.message,
                )
            }

            ensureDefaultSelection()
            loadTabData(currentState.category)
        }
    }

    private fun loadTabData(category: LibraryCategory) {
        when (category) {
            LibraryCategory.Artists -> {
                if (currentState.artists.isEmpty()) {
                    launch {
                        repository.getArtists().onSuccess {
                            setState { copy(artists = it) }
                            ensureDefaultSelection()
                        }
                    }
                } else {
                    ensureDefaultSelection()
                }
            }
            // Favorites are loaded on connecting and kept up to date by Favorites
            LibraryCategory.Favorites -> {}
            LibraryCategory.Albums -> if (currentState.albums.isEmpty()) {
                loadCategory(category, { getAlbums() }) { copy(albums = it) }
            }
            LibraryCategory.Songs -> if (currentState.songs.isEmpty()) {
                loadCategory(category, { getAllSongs() }) { copy(songs = it) }
            }
            LibraryCategory.Genres -> if (currentState.genres.isEmpty()) {
                loadCategory(category, { getGenres() }) { copy(genres = it) }
            }
            LibraryCategory.Playlists -> {
                if (currentState.playlists.isEmpty()) {
                    launch {
                        repository.getPlaylists().onSuccess {
                            setState { copy(playlists = it) }
                        }
                    }
                }
            }
        }
    }

    /** Loads the items of a sidebar [category] with [load] and puts them into the state with [update]. */
    private fun <T> loadCategory(
        category: LibraryCategory,
        load: suspend MusicRepository.() -> Result<T>,
        update: LibraryState.(T) -> LibraryState,
    ) {
        if (category in currentState.loadingCategories) return
        setState { copy(loadingCategories = loadingCategories + category) }
        launch {
            val result = repository.load()
            setState {
                val loaded = result.getOrNull()
                (if (loaded != null) update(loaded) else this).copy(loadingCategories = loadingCategories - category)
            }
        }
    }

    private fun ensureDefaultSelection() {
        when (currentState.activeDetailView) {
            LibraryDetailView.Artists -> {
                if (currentState.selectedArtist == null && currentState.artists.isNotEmpty()) {
                    val artist = currentState.artists.first()
                    setState {
                        copy(
                            selectedArtist = artist,
                            selectedArtistId = artist.id,
                            selectedArtistAlbums = emptyList(),
                            selectedArtistAppearsOnAlbums = emptyList(),
                            selectedArtistBiography = null,
                            selectedArtistLargeImageUrl = null,
                            selectedArtistSongs = emptyList(),
                            selectedAlbumId = null,
                            selectedAlbum = null,
                            selectedPlaylistId = null,
                            selectedPlaylist = null,
                            selectedPlaylistLoading = false,
                        )
                    }
                    if (artist.id.isNotEmpty()) {
                        loadArtistAlbums(artist.id)
                        loadArtistInfo(artist.id)
                    } else {
                        resolveArtistByName(artist.name)
                    }
                } else if (currentState.selectedArtist != null && currentState.selectedArtistAlbums.isEmpty() && currentState.selectedArtistId?.isNotEmpty() == true) {
                    loadArtistAlbums(currentState.selectedArtistId!!)
                    if (currentState.selectedArtistBiography == null) {
                        loadArtistInfo(currentState.selectedArtistId!!)
                    }
                }
            }
            LibraryDetailView.Albums -> {
                if (currentState.selectedAlbumId?.isNotEmpty() == true && currentState.selectedAlbum == null) {
                    loadAlbum(currentState.selectedAlbumId!!)
                }
            }
            LibraryDetailView.Playlists -> {
                if (currentState.selectedPlaylistId == null && currentState.playlists.isNotEmpty()) {
                    val playlist = currentState.playlists.first()
                    setState { copy(selectedPlaylistId = playlist.id, selectedPlaylist = null, selectedPlaylistLoading = true) }
                    loadPlaylist(playlist.id)
                }
            }
            LibraryDetailView.FavoriteSongs, LibraryDetailView.Genres -> {}
        }
    }

    /**
     * Artists opened by name (e.g. from a song's artist credit) carry no server id. Look one up
     * by name so their albums and biography still load; artists missing from the index fall
     * back to a song search.
     */
    private fun resolveArtistByName(artistName: String) {
        if (artistName.isBlank()) return

        launch {
            val artist = repository.findArtistByName(artistName).getOrNull()
            if (currentState.selectedArtist?.name != artistName) return@launch
            if (artist != null) {
                setState { copy(selectedArtistId = artist.id) }
                loadArtistAlbums(artist.id)
                loadArtistInfo(artist.id)
            } else {
                loadAppearsOnAlbums(artistName, emptyList())
                loadArtistSongs(artistName, emptyList())
            }
        }
    }

    private fun loadArtistAlbums(artistId: String) {
        if (artistId.isBlank()) return

        launch {
            repository.getArtistAlbums(artistId)
                .onSuccess { albums ->
                    val dedupedAlbums = albums.distinctBy { it.id.ifEmpty { "${it.name}_${it.artist}" } }.newestFirst()
                    setState { copy(selectedArtistAlbums = dedupedAlbums) }
                    loadAppearsOnAlbums(
                        currentState.selectedArtist?.name.orEmpty(),
                        dedupedAlbums.map { it.id },
                    )
                    loadArtistSongs(
                        currentState.selectedArtist?.name.orEmpty(),
                        dedupedAlbums.map { it.id },
                    )
                }
        }
    }

    private fun loadAppearsOnAlbums(artistName: String, albumIds: List<String>) {
        if (artistName.isBlank()) return

        launch {
            repository.getAppearsOnAlbums(artistName, albumIds)
                .onSuccess { albums ->
                    setState { copy(selectedArtistAppearsOnAlbums = albums.newestFirst()) }
                }
        }
    }

    private fun loadArtistInfo(artistId: String) {
        if (artistId.isBlank()) return
        launch {
            repository.getArtistInfo(artistId)
                .onSuccess { info ->
                    setState {
                        copy(
                            selectedArtistBiography = info.biography,
                            selectedArtistLargeImageUrl = info.largeImageUrl,
                        )
                    }
                }
        }
    }

    private fun loadArtistSongs(artistName: String, albumIds: List<String>) {
        setState { copy(selectedArtistSongsLoading = true) }
        launch {
            repository.getSongsForArtist(artistName, albumIds)
                .onSuccess { songs -> setState { copy(selectedArtistSongs = songs.newestAlbumsFirst(), selectedArtistSongsLoading = false) } }
                .onFailure { setState { copy(selectedArtistSongsLoading = false) } }
        }
    }

    private fun loadAlbum(albumId: String) {
        if (albumId.isBlank()) {
            return
        }

        launch {
            repository.getAlbum(albumId)
                .onSuccess { album ->
                    setState { copy(selectedAlbum = album) }
                }
        }
    }

    private fun loadPlaylist(playlistId: String, force: Boolean = false) {
        if (!force && currentState.selectedPlaylistId == playlistId && currentState.selectedPlaylist != null && !currentState.selectedPlaylistLoading) {
            return
        }

        setState {
            copy(
                selectedPlaylistId = playlistId,
                selectedPlaylist = null,
                selectedPlaylistLoading = true,
                error = null,
            )
        }

        launch {
            repository.getPlaylist(playlistId)
                .onSuccess { playlist ->
                    setState { copy(selectedPlaylist = playlist, selectedPlaylistLoading = false) }
                }
                .onFailure { error ->
                    // Offline only playlists that were opened or downloaded before are available; the pane says so
                    setState {
                        copy(
                            selectedPlaylistLoading = false,
                            error = if (isOffline) null else error.message ?: "Unable to load playlist",
                        )
                    }
                }
        }
    }
}
