package de.ronnyporsch.voidcast.ui.screen.library

import de.ronnyporsch.voidcast.domain.model.isYouTubeId
import de.ronnyporsch.voidcast.ui.components.YouTubeIcon
import de.ronnyporsch.voidcast.ui.components.FavoriteButton
import de.ronnyporsch.voidcast.domain.model.FavoriteKind
import de.ronnyporsch.voidcast.domain.model.audioFormat
import de.ronnyporsch.voidcast.domain.model.formatTotals
import de.ronnyporsch.voidcast.domain.model.formatTotalDuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ronnyporsch.voidcast.data.offline.PlaylistDownloadStatus
import de.ronnyporsch.voidcast.domain.model.Album
import de.ronnyporsch.voidcast.domain.model.Artist
import de.ronnyporsch.voidcast.domain.model.Playlist
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.getPlatform
import de.ronnyporsch.voidcast.ui.LocalCoverArtUrlProvider
import de.ronnyporsch.voidcast.ui.components.*
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SpotifyLightGray
import de.ronnyporsch.voidcast.ui.screen.favorites.FAVORITE_SONGS_TITLE
import de.ronnyporsch.voidcast.ui.screen.favorites.FavoriteSongsList
import de.ronnyporsch.voidcast.ui.screen.favorites.FavoritesImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import de.ronnyporsch.voidcast.domain.model.LibraryOrder
import de.ronnyporsch.voidcast.domain.model.LibraryCategory
import de.ronnyporsch.voidcast.domain.model.LibrarySort
import de.ronnyporsch.voidcast.util.sortedBy
import de.ronnyporsch.voidcast.util.withSongCounts
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    currentSongId: String? = null,
    /** The playlist the playing songs are of, marked in the sidebar. */
    playingPlaylistId: String? = null,
    /** Desktop: opens an item of the sidebar in the detail pane, as a new entry of the back stack. */
    onOpen: (LibrarySelection) -> Unit = { viewModel.onEvent(LibraryEvent.Show(it)) },
) {
    val state by viewModel.state.collectAsState()
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    // On mobile the sidebar fills the whole screen, edge to edge
    val isMobile = remember { getPlatform().isMobile }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(viewModel, onDismiss = { showCreatePlaylistDialog = false })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(if (isMobile) 0.dp else 16.dp),
    ) {
        if (state.isOffline) {
            Text(
                text = "Can't reach the server. Showing what is stored on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = SpotifyLightGray,
                modifier = if (isMobile) Modifier.padding(16.dp) else Modifier.padding(bottom = 16.dp),
            )
        }

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentColor)
            }
        } else if (state.error != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { viewModel.onEvent(LibraryEvent.Refresh) }) {
                        Text("Retry", color = AccentColor)
                    }
                }
            }
        } else {
            LibrarySplitView(
                state = state,
                viewModel = viewModel,
                currentSongId = currentSongId,
                playingPlaylistId = playingPlaylistId,
                onCreatePlaylist = { showCreatePlaylistDialog = true },
                onOpen = onOpen,
            )
        }
    }
}

@Composable
private fun LibrarySplitView(
    state: LibraryState,
    viewModel: LibraryViewModel,
    currentSongId: String?,
    playingPlaylistId: String?,
    onCreatePlaylist: () -> Unit,
    onOpen: (LibrarySelection) -> Unit,
) {
    // On mobile there is no room for a detail pane: the sidebar fills the screen and items open their own screens
    if (remember { getPlatform().isMobile }) {
        LibrarySidebar(
            state = state,
            viewModel = viewModel,
            onCreatePlaylist = onCreatePlaylist,
            playingPlaylistId = playingPlaylistId,
            openItemScreens = true,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    Row(Modifier.fillMaxSize()) {
        LibrarySidebar(
            state = state,
            viewModel = viewModel,
            onCreatePlaylist = onCreatePlaylist,
            playingPlaylistId = playingPlaylistId,
            onOpen = onOpen,
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight(),
        )

        Spacer(Modifier.width(16.dp))

        LibraryDetailPane(
            state = state,
            viewModel = viewModel,
            currentSongId = currentSongId,
            onOpen = onOpen,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun CreatePlaylistDialog(viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    var newPlaylistName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Playlist") },
        text = {
            OutlinedTextField(
                value = newPlaylistName,
                onValueChange = { newPlaylistName = it },
                label = { Text("Playlist Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (newPlaylistName.isNotBlank()) {
                        viewModel.onEvent(LibraryEvent.CreatePlaylist(newPlaylistName))
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentColor)
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Desktop: the library sidebar next to screens other than the library, so it is always visible.
 * Opening an item from it goes to the library, which shows the item in its detail pane.
 */
@Composable
fun DesktopLibrarySidebar(
    viewModel: LibraryViewModel,
    onOpen: (LibrarySelection) -> Unit,
    playingPlaylistId: String? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(viewModel, onDismiss = { showCreatePlaylistDialog = false })
    }
    LibrarySidebar(
        state = state,
        viewModel = viewModel,
        onCreatePlaylist = { showCreatePlaylistDialog = true },
        playingPlaylistId = playingPlaylistId,
        onOpen = onOpen,
        modifier = modifier,
    )
}

@Composable
private fun LibrarySidebar(
    state: LibraryState,
    viewModel: LibraryViewModel,
    onCreatePlaylist: () -> Unit,
    playingPlaylistId: String? = null,
    openItemScreens: Boolean = false,
    /** Opens an item in the detail pane (not with [openItemScreens]). */
    onOpen: (LibrarySelection) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Shown alone (mobile), the sidebar is the whole screen and shouldn't stand out from it
    val containerColor = if (openItemScreens) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceVariant
    fun open(selection: LibrarySelection, screenEvent: LibraryEvent) {
        if (openItemScreens) viewModel.onEvent(screenEvent) else onOpen(selection)
    }
    fun openAndPlay(selection: LibrarySelection, playEvent: LibraryEvent) {
        viewModel.onEvent(playEvent)
        onOpen(selection)
    }
    val order = state.order
    val listState = viewModel.sidebarStates.getValue(state.category)
    val isCategoryLoading = state.category in state.loadingCategories
    val scope = rememberCoroutineScope()
    val onFilterChange: (String) -> Unit = { filter ->
        viewModel.sidebarFilters[state.category] = filter
        // The matches start at the top
        scope.launch { listState.scrollToItem(0) }
    }
    Surface(
        modifier = modifier,
        color = containerColor,
        shape = RoundedCornerShape(0.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            LibrarySidebarHeader(
                category = state.category,
                order = order,
                sorts = state.availableSorts,
                onCategorySelected = { viewModel.onEvent(LibraryEvent.CategorySelected(it)) },
                onSortSelected = { viewModel.onEvent(LibraryEvent.SortSelected(it)) },
                onCreatePlaylist = onCreatePlaylist.takeIf { state.category == LibraryCategory.Playlists },
                onShuffleLibrary = { viewModel.onEvent(LibraryEvent.ShuffleLibrary) },
            )
            val filter = viewModel.sidebarFilters[state.category].orEmpty()
            SidebarFilter(filter, "Filter ${state.category.title.lowercase()}", onFilterChange)

            when (state.category) {
                LibraryCategory.Artists -> {
                    // Sorted apart from the filter, so typing in it only filters
                    val sortedArtists = remember(state.artists, state.albums, order) {
                        val counted = if (order.sort == LibrarySort.SongCount) state.artists.withSongCounts(state.albums) else state.artists
                        counted.sortedBy(order)
                    }
                    val artists = remember(sortedArtists, filter) { sortedArtists.matching(filter) { matches(it.name) } }
                    val artistKeys = remember(artists) { artists.uniqueKeys { it.id.ifEmpty { "name:${it.name}" } } }
                    SidebarList(
                        items = artists,
                        key = { index, _ -> artistKeys[index] },
                        state = listState,
                        emptyText = if (filter.isBlank()) "No artists" else "No matching artists",
                    ) { _, artist ->
                        ArtistListItem(
                            artist = artist,
                            isSelected = artist.id == state.selectedArtistId,
                            onClick = { open(LibrarySelection.Artist(artist), LibraryEvent.OpenArtistScreen(artist)) },
                            onDoubleClick = if (openItemScreens) null else {
                                { openAndPlay(LibrarySelection.Artist(artist), LibraryEvent.ArtistDoubleClicked(artist)) }
                            },
                        )
                    }
                }
                LibraryCategory.Albums -> {
                    val sortedAlbums = remember(state.albums, order) { state.albums.sortedBy(order) }
                    val albums = remember(sortedAlbums, filter) { sortedAlbums.matching(filter) { matches(it.name, it.artist) } }
                    val albumKeys = remember(albums) { albums.uniqueKeys { it.id } }
                    SidebarList(
                        items = albums,
                        key = { index, _ -> albumKeys[index] },
                        state = listState,
                        isLoading = isCategoryLoading,
                        emptyText = if (filter.isBlank()) "No albums" else "No matching albums",
                    ) { _, album ->
                        AlbumListItem(
                            album = album,
                            isSelected = state.activeDetailView == LibraryDetailView.Albums && album.id == state.selectedAlbumId,
                            onClick = { open(LibrarySelection.Album(album), LibraryEvent.OpenAlbumScreen(album)) },
                            onDoubleClick = if (openItemScreens) null else {
                                { openAndPlay(LibrarySelection.Album(album), LibraryEvent.AlbumDoubleClicked(album)) }
                            },
                        )
                    }
                }
                LibraryCategory.Songs -> {
                    val sortedSongs = remember(state.songs, order) { state.songs.sortedBy(order) }
                    val songs = remember(sortedSongs, filter) { sortedSongs.matchingSongs(filter) }
                    SongSidebar(
                        songs = songs,
                        state = listState,
                        isLoading = isCategoryLoading,
                        onPlay = { index -> viewModel.onEvent(LibraryEvent.PlaySongs(songs, index)) },
                        onArtistClick = { viewModel.onEvent(LibraryEvent.SongArtistClicked(it)) },
                        onAlbumClick = { viewModel.onEvent(LibraryEvent.SongAlbumClicked(it)) },
                    )
                }
                LibraryCategory.Genres -> {
                    val sortedGenres = remember(state.genres) { state.genres.sortedBy { it.name.lowercase() } }
                    val genres = remember(sortedGenres, filter) { sortedGenres.matching(filter) { matches(it.name) } }
                    SidebarList(
                        items = genres,
                        key = { _, genre -> genre.id },
                        state = listState,
                        isLoading = isCategoryLoading,
                        emptyText = if (filter.isBlank()) "No genres" else "No matching genres",
                    ) { _, genre ->
                        GenreListItem(
                            genre = genre,
                            isSelected = state.activeDetailView == LibraryDetailView.Genres && genre.id == state.selectedGenre?.id,
                            onClick = { open(LibrarySelection.Genre(genre), LibraryEvent.OpenGenreScreen(genre)) },
                        )
                    }
                }
                LibraryCategory.Favorites -> FavoritesSidebar(
                    state = state,
                    order = order,
                    filter = filter,
                    onSongsClick = {
                        open(LibrarySelection.FavoriteSongs, LibraryEvent.OpenFavoriteSongsScreen)
                    },
                    onArtistClick = {
                        open(LibrarySelection.Artist(it), LibraryEvent.OpenArtistScreen(it))
                    },
                    onArtistDoubleClick = if (openItemScreens) null else {
                        { openAndPlay(LibrarySelection.Artist(it), LibraryEvent.ArtistDoubleClicked(it)) }
                    },
                    onAlbumClick = {
                        open(LibrarySelection.Album(it), LibraryEvent.OpenAlbumScreen(it))
                    },
                    onAlbumDoubleClick = if (openItemScreens) null else {
                        { openAndPlay(LibrarySelection.Album(it), LibraryEvent.AlbumDoubleClicked(it)) }
                    },
                    listState = listState,
                    modifier = Modifier.fillMaxSize(),
                )
                LibraryCategory.Playlists -> {
                    val expandedFolders = state.view.expandedPlaylistFolders
                    val sortedPlaylists = remember(state.playlists, order) { state.playlists.sortedBy(order) }
                    val entries = remember(sortedPlaylists, expandedFolders, filter) {
                        val playlists = sortedPlaylists.matching(filter) { matches(it.name) }
                        // Matches in folders show without opening them
                        playlists.toSidebarEntries(if (filter.isBlank()) expandedFolders else playlists.mapNotNullTo(mutableSetOf()) { it.folderAndName()?.first })
                    }
                    PlaylistSidebar(
                        entries = entries,
                        onFolderClick = { viewModel.onEvent(LibraryEvent.PlaylistFolderToggled(it.name)) },
                        selectedPlaylistId = state.selectedPlaylistId,
                        playingPlaylistId = playingPlaylistId,
                        onPlaylistClick = {
                            open(LibrarySelection.Playlist(it.id), LibraryEvent.OpenPlaylistScreen(it))
                        },
                        onPlaylistDoubleClick = if (openItemScreens) null else {
                            { openAndPlay(LibrarySelection.Playlist(it.id), LibraryEvent.PlaylistDoubleClicked(it)) }
                        },
                        state = listState,
                        downloads = if (state.canDownloadPlaylists) state.playlistDownloads else null,
                        onDownload = { viewModel.onEvent(LibraryEvent.DownloadPlaylist(it)) },
                        onRemoveDownload = { viewModel.onEvent(LibraryEvent.RemovePlaylistDownload(it)) },
                    )
                }
            }
        }
    }
}

/** Filters the list of the sidebar below it. */
@Composable
private fun SidebarFilter(filter: String, placeholder: String, onFilterChange: (String) -> Unit) {
    SongFilterField(
        query = filter,
        onQueryChange = onFilterChange,
        placeholder = placeholder,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp),
    )
}

/**
 * Picks what the sidebar lists and how it orders it, and shuffles the whole library; playlists get a button to create
 * one.
 */
@Composable
private fun LibrarySidebarHeader(
    category: LibraryCategory,
    order: LibraryOrder,
    sorts: List<LibrarySort>,
    onCategorySelected: (LibraryCategory) -> Unit,
    onSortSelected: (LibrarySort) -> Unit,
    onCreatePlaylist: (() -> Unit)?,
    onShuffleLibrary: () -> Unit,
) {
    var categoryMenuOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .clickable { categoryMenuOpen = true }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = category.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose what to show")
            }
            DropdownMenu(expanded = categoryMenuOpen, onDismissRequest = { categoryMenuOpen = false }) {
                LibraryCategory.entries.forEach { entry ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                entry.title,
                                fontWeight = if (entry == category) FontWeight.Bold else FontWeight.Normal,
                                color = if (entry == category) AccentColor else Color.Unspecified,
                            )
                        },
                        onClick = {
                            categoryMenuOpen = false
                            onCategorySelected(entry)
                        },
                    )
                }
            }
        }

        if (onCreatePlaylist != null) {
            TextButton(onClick = onCreatePlaylist) {
                Text("+ New", color = AccentColor, fontWeight = FontWeight.Bold)
            }
        }

        TooltipIconButton(
            onClick = onShuffleLibrary,
            icon = Icons.Default.Shuffle,
            contentDescription = "Shuffle all songs of the library",
            tint = SpotifyLightGray,
        )

        if (sorts.size > 1) {
            Box {
                TooltipIconButton(
                    onClick = { sortMenuOpen = true },
                    icon = Icons.AutoMirrored.Filled.Sort,
                    contentDescription = "Sort: ${order.description}",
                    tint = if (order == LibraryOrder()) SpotifyLightGray else AccentColor,
                )
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    sorts.forEach { entry ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    entry.title,
                                    fontWeight = if (entry == order.sort) FontWeight.Bold else FontWeight.Normal,
                                    color = if (entry == order.sort) AccentColor else Color.Unspecified,
                                )
                            },
                            // The current sort shows its direction; choosing it again reverses it
                            trailingIcon = if (entry == order.sort) {
                                {
                                    Icon(
                                        if (order.isAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                        contentDescription = order.description,
                                        tint = AccentColor,
                                    )
                                }
                            } else null,
                            onClick = {
                                sortMenuOpen = false
                                onSortSelected(entry)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** The scrolling list of a sidebar category. Shows [emptyText] if there are no [items]. */
/**
 * [key] of each item, made unique by numbering the repeats of one. Unlike the items' positions, these keys stay with
 * them when the list gets filtered or sorted, so the rows still shown are kept instead of all being built anew.
 */
private fun <T> List<T>.uniqueKeys(key: (T) -> String): List<String> {
    val repeats = HashMap<String, Int>()
    return map { item ->
        val base = key(item)
        val repeat = repeats[base] ?: 0
        repeats[base] = repeat + 1
        if (repeat == 0) base else "$base\n$repeat"
    }
}

@Composable
private fun <T> SidebarList(
    items: List<T>,
    key: (index: Int, item: T) -> Any,
    state: LazyListState,
    emptyText: String,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    /** Added to the lazy list's modifier. */
    listModifier: Modifier = Modifier,
    itemContent: @Composable (index: Int, item: T) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize().padding(12.dp)) {
        when {
            isLoading && items.isEmpty() -> CircularProgressIndicator(color = AccentColor, modifier = Modifier.align(Alignment.Center))
            items.isEmpty() -> Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
            )
            else -> {
                LazyColumn(
                    state = state,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().then(listModifier),
                ) {
                    itemsIndexed(items, key = key) { index, item -> itemContent(index, item) }
                }

                VerticalScrollbar(
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    state = state
                )
            }
        }
    }
}

/**
 * All songs of the library. Mobile: tapping a song plays the list from there. Desktop and web: a click selects it
 * (with Ctrl and Shift several), a double click plays the list from there.
 */
@Composable
private fun SongSidebar(
    songs: List<Song>,
    state: LazyListState,
    isLoading: Boolean,
    onPlay: (index: Int) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
) {
    val isMobile = remember { getPlatform().isMobile }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    val keys = remember(songs) { songs.uniqueKeys { it.id } }
    val songKey = { index: Int, _: Song -> keys[index] }
    val touchSelection = rememberTouchSelection(
        items = songs,
        selectedIds = selectedIds,
        onChange = { selectedIds = it },
        id = { it.id },
        key = songKey,
    )
    TouchSelectionBar(touchSelection, songs = { touchSelection.selectedItems })
    SidebarList(
        items = songs,
        key = songKey,
        listModifier = Modifier.touchSelection(touchSelection, state),
        state = state,
        isLoading = isLoading,
        emptyText = "No songs",
    ) { index, song ->
        SongRow(
            song = song,
            onSongClick = { touchSelection.click(song) { if (isMobile) onPlay(index) else selectedIds = setOf(song.id) } },
            longPressSelects = touchSelection.isEnabled,
            onSongDoubleClick = {
                selectedIds = setOf(song.id)
                onPlay(index)
            },
            onSongCtrlClick = { selectedIds = selectedIds.toggled(song.id) },
            onSongShiftClick = { selectedIds = songs.selectedThrough(index, selectedIds) },
            contextMenuSongs = { songs.contextMenuSongs(song, selectedIds) },
            isSelected = song.id in selectedIds,
            onArtistClick = onArtistClick,
            onAlbumClick = onAlbumClick,
            contentPadding = PaddingValues(10.dp),
        )
    }
}

@Composable
private fun LibraryDetailPane(
    state: LibraryState,
    viewModel: LibraryViewModel,
    currentSongId: String?,
    onOpen: (LibrarySelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One of each per visit of the library, so going back or forward to a visit shows the pane as it was scrolled
    val artistDetailState = rememberLazyGridState()
    val albumDetailState = rememberLazyListState()
    val playlistDetailState = rememberLazyListState()
    val favoriteSongsState = rememberLazyListState()
    val genreDetailState = rememberLazyListState()
    val playlistOverviewState = rememberLazyGridState()
    when (state.activeDetailView) {
        LibraryDetailView.PlaylistOverview -> PlaylistOverviewPane(
            playlists = state.playlists,
            order = state.view.orderOf(LibraryCategory.Playlists),
            downloads = if (state.canDownloadPlaylists) state.playlistDownloads else emptyMap(),
            onPlaylistClick = { onOpen(LibrarySelection.Playlist(it.id)) },
            onPlaylistDoubleClick = {
                viewModel.onEvent(LibraryEvent.PlaylistDoubleClicked(it))
                onOpen(LibrarySelection.Playlist(it.id))
            },
            state = playlistOverviewState,
            modifier = modifier,
        )
        LibraryDetailView.Artists -> ArtistDetailPane(
            artist = state.selectedArtist,
            albums = state.selectedArtistAlbums,
            appearsOnAlbums = state.selectedArtistAppearsOnAlbums,
            biography = state.selectedArtistBiography,
            largeImageUrl = state.selectedArtistLargeImageUrl,
            similarArtists = state.selectedArtistSimilarArtists,
            onSimilarArtistClick = { viewModel.onEvent(LibraryEvent.OpenArtistScreen(it)) },
            songs = state.selectedArtistSongs,
            isSongsLoading = state.selectedArtistSongsLoading,
            onAlbumClick = { viewModel.onEvent(LibraryEvent.OpenAlbumScreen(it)) },
            onSongAlbumClick = { albumId -> viewModel.onEvent(LibraryEvent.SongAlbumClicked(albumId)) },
            onSongClick = { song, index -> viewModel.onEvent(LibraryEvent.ArtistSongClicked(song, index)) },
            onSongDoubleClick = { song, index -> viewModel.onEvent(LibraryEvent.ArtistSongDoubleClicked(song, index)) },
            isSelected = { it in state.selectedSongIds },
            isPlaying = { it == currentSongId },
            onPlayAll = { viewModel.onEvent(LibraryEvent.PlayAllArtistSongs) },
            onShuffle = { viewModel.onEvent(LibraryEvent.ShuffleAllArtistSongs) },
            state = artistDetailState,
            modifier = modifier,
            onSongCtrlClick = { viewModel.onEvent(LibraryEvent.SongSelectionToggled(it)) },
            onSongShiftClick = { _, index -> viewModel.onEvent(LibraryEvent.SongRangeSelected(index)) },
            contextMenuSongs = { songs, song -> songs.contextMenuSongs(song, state.selectedSongIds) },
        )
        LibraryDetailView.Albums -> AlbumDetailPane(
            album = state.selectedAlbum,
            onSongClick = { song, index -> viewModel.onEvent(LibraryEvent.SongClicked(song, index)) },
            onSongDoubleClick = { song, index -> viewModel.onEvent(LibraryEvent.SongDoubleClicked(song, index)) },
            isSelected = { it in state.selectedSongIds },
            isPlaying = { it == currentSongId },
            onArtistClick = { name -> viewModel.onEvent(LibraryEvent.SongArtistClicked(name)) },
            onPlayAll = { viewModel.onEvent(LibraryEvent.PlayAllAlbumSongs) },
            onShuffle = { viewModel.onEvent(LibraryEvent.ShuffleAllAlbumSongs) },
            state = albumDetailState,
            modifier = modifier,
            onSongCtrlClick = { viewModel.onEvent(LibraryEvent.SongSelectionToggled(it)) },
            onSongShiftClick = { _, index -> viewModel.onEvent(LibraryEvent.SongRangeSelected(index)) },
            contextMenuSongs = { songs, song -> songs.contextMenuSongs(song, state.selectedSongIds) },
        )
        LibraryDetailView.FavoriteSongs -> Surface(
            modifier = modifier,
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(0.dp),
        ) {
            val headerState = rememberCollapsingHeaderState(favoriteSongsState)
            Box(Modifier.fillMaxSize()) {
                FavoriteSongsList(
                    songs = state.favoriteSongs,
                    state = favoriteSongsState,
                    headerState = headerState,
                    onSongClick = { song, index -> viewModel.onEvent(LibraryEvent.SongClicked(song, index)) },
                    onSongDoubleClick = { song, index -> viewModel.onEvent(LibraryEvent.SongDoubleClicked(song, index)) },
                    isSelected = { it in state.selectedSongIds },
                    isPlaying = { it == currentSongId },
                    onArtistClick = { name -> viewModel.onEvent(LibraryEvent.SongArtistClicked(name)) },
                    onAlbumClick = { albumId -> viewModel.onEvent(LibraryEvent.SongAlbumClicked(albumId)) },
                    onPlayAll = { viewModel.onEvent(LibraryEvent.PlayAllFavoriteSongs) },
                    onShuffle = { viewModel.onEvent(LibraryEvent.ShuffleAllFavoriteSongs) },
                    onSongCtrlClick = { viewModel.onEvent(LibraryEvent.SongSelectionToggled(it)) },
                    onSongShiftClick = { _, index -> viewModel.onEvent(LibraryEvent.SongRangeSelected(index)) },
                    contextMenuSongs = { songs, song -> songs.contextMenuSongs(song, state.selectedSongIds) },
                )
                CollapsedHeaderOverlay(
                    state = headerState,
                    title = FAVORITE_SONGS_TITLE,
                    image = { modifier -> FavoritesImage(modifier) },
                )
            }
        }
        LibraryDetailView.Genres -> Surface(
            modifier = modifier,
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(0.dp),
        ) {
            val genre = state.selectedGenre
            if (genre != null) {
                val headerState = rememberCollapsingHeaderState(genreDetailState)
                Box(Modifier.fillMaxSize()) {
                    GenreSongsList(
                        genre = genre,
                        songs = state.selectedGenreSongs,
                        albums = state.selectedGenreAlbums,
                        artists = state.selectedGenreArtists,
                        onGenreArtistClick = { viewModel.onEvent(LibraryEvent.OpenArtistScreen(it)) },
                        isLoading = state.selectedGenreSongsLoading,
                        state = genreDetailState,
                        headerState = headerState,
                        onSongClick = { song, index -> viewModel.onEvent(LibraryEvent.SongClicked(song, index)) },
                        onSongDoubleClick = { song, index -> viewModel.onEvent(LibraryEvent.SongDoubleClicked(song, index)) },
                        isSelected = { it in state.selectedSongIds },
                        isPlaying = { it == currentSongId },
                        onArtistClick = { name -> viewModel.onEvent(LibraryEvent.SongArtistClicked(name)) },
                        onAlbumClick = { albumId -> viewModel.onEvent(LibraryEvent.SongAlbumClicked(albumId)) },
                        onPlayAll = { viewModel.onEvent(LibraryEvent.PlayAllGenreSongs) },
                        onShuffle = { viewModel.onEvent(LibraryEvent.ShuffleAllGenreSongs) },
                        onSongCtrlClick = { viewModel.onEvent(LibraryEvent.SongSelectionToggled(it)) },
                        onSongShiftClick = { _, index -> viewModel.onEvent(LibraryEvent.SongRangeSelected(index)) },
                        contextMenuSongs = { songs, song -> songs.contextMenuSongs(song, state.selectedSongIds) },
                    )
                    CollapsedHeaderOverlay(
                        state = headerState,
                        title = genre.name,
                        image = { modifier -> GenreImage(modifier) },
                    )
                }
            }
        }
        LibraryDetailView.Playlists -> PlaylistDetailPane(
            playlist = state.selectedPlaylist,
            isLoading = state.selectedPlaylistLoading,
            isOffline = state.isOffline,
            onSongClick = { song, index -> viewModel.onEvent(LibraryEvent.SongClicked(song, index)) },
            onSongDoubleClick = { song, index -> viewModel.onEvent(LibraryEvent.SongDoubleClicked(song, index)) },
            onRemoveClick = { indices ->
                state.selectedPlaylist?.let {
                    viewModel.onEvent(LibraryEvent.RemoveSongsFromPlaylist(it, indices))
                }
            },
            contextMenuIndices = { songs, index -> songs.contextMenuIndices(index, state.selectedSongIds) },
            onMoveSongs = { indices, before ->
                state.selectedPlaylist?.let { viewModel.onEvent(LibraryEvent.MoveSongsInPlaylist(it, indices, before)) }
            },
            isSelected = { it in state.selectedSongIds },
            isPlaying = { it == currentSongId },
            onArtistClick = { name -> viewModel.onEvent(LibraryEvent.SongArtistClicked(name)) },
            onAlbumClick = { albumId -> viewModel.onEvent(LibraryEvent.SongAlbumClicked(albumId)) },
            onPlayAll = { viewModel.onEvent(LibraryEvent.PlayAllPlaylistSongs) },
            onShuffle = { viewModel.onEvent(LibraryEvent.ShuffleAllPlaylistSongs) },
            state = playlistDetailState,
            modifier = modifier,
            onSongCtrlClick = { viewModel.onEvent(LibraryEvent.SongSelectionToggled(it)) },
            onSongShiftClick = { songs, index -> viewModel.onEvent(LibraryEvent.SongsSelected(songs.selectedThrough(index, state.selectedSongIds))) },
            contextMenuSongs = { songs, song -> songs.contextMenuSongs(song, state.selectedSongIds) },
        )
    }
}

@Composable
private fun ArtistDetailPane(
    artist: Artist?,
    albums: List<Album>,
    appearsOnAlbums: List<Album>,
    biography: String?,
    largeImageUrl: String?,
    similarArtists: List<Artist>,
    onSimilarArtistClick: (Artist) -> Unit,
    songs: List<Song>,
    isSongsLoading: Boolean,
    onAlbumClick: (Album) -> Unit,
    /** Album link of a song whose album isn't shown in the overview. */
    onSongAlbumClick: (albumId: String) -> Unit,
    onSongClick: (Song, Int) -> Unit,
    onSongDoubleClick: (Song, Int) -> Unit,
    isSelected: (String) -> Boolean,
    isPlaying: (String) -> Boolean,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    state: LazyGridState,
    onSongCtrlClick: (Song) -> Unit = {},
    onSongShiftClick: (Song, Int) -> Unit = { _, _ -> },
    contextMenuSongs: (List<Song>, Song) -> List<Song> = { _, song -> listOf(song) },
    modifier: Modifier = Modifier,
) {
    RememberScrollPosition(state, "artist")
    // Limits the albums and songs shown
    var query by remember(artist?.id, artist?.name) { mutableStateOf("") }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(0.dp),
    ) {
        if (artist == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Select an artist to see its library details",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val headerState = rememberCollapsingHeaderState(state)
            val albumSelection = rememberAlbumSelection()
            val albums = albums.matching(query) { matches(it.name) }
            val appearsOnAlbums = appearsOnAlbums.matching(query) { matches(it.name, it.artist) }
            // In the order they are shown, for selecting ranges of them
            val shownAlbums = albums + appearsOnAlbums
            // The songs matching the search, with their positions among all of them, which the callbacks get
            val visible = remember(songs, query) { songs.filtered(query) }
            val visibleSongs = remember(visible) { visible.map { it.value } }
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = state,
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(bottom = 100.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            val currentIndex = visibleSongs.indexOfFirst { isSelected(it.id) }
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (currentIndex < visible.size - 1) {
                                        val (index, song) = visible[currentIndex + 1]
                                        onSongClick(song, index)
                                        true
                                    } else false
                                }
                                Key.DirectionUp -> {
                                    if (currentIndex > 0) {
                                        val (index, song) = visible[currentIndex - 1]
                                        onSongClick(song, index)
                                        true
                                    } else false
                                }
                                else -> false
                            }
                        } else false
                    }
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ArtistHeader(
                            artistId = artist.id,
                            artistName = artist.name,
                            imageUrl = largeImageUrl,
                            biography = biography,
                            songTotals = songs.formatTotals(),
                            headerState = headerState,
                        )
                    }

                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SongFilterField(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "Search in albums and songs",
                        )
                    }

                    if (albums.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = "Albums",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }

                        items(albums, key = { it.id }) { album ->
                            SelectableAlbum(album, shownAlbums, albumSelection, onClick = { onAlbumClick(album) }) {
                                AlbumCard(album)
                            }
                        }
                    }

                    if (appearsOnAlbums.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = "Appears on",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                            )
                        }

                        items(appearsOnAlbums, key = { it.id }) { album ->
                            SelectableAlbum(album, shownAlbums, albumSelection, onClick = { onAlbumClick(album) }) {
                                AlbumCard(album)
                            }
                        }
                    }

                    if (query.isBlank() && similarArtists.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SimilarArtists(similarArtists, onArtistClick = onSimilarArtistClick)
                        }
                    }

                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = "Songs",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }

                    if (isSongsLoading) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(color = AccentColor, modifier = Modifier.size(24.dp))
                            }
                        }
                    } else if (songs.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Button(
                                    onClick = onPlayAll,
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                                    shape = RoundedCornerShape(0.dp),
                                ) {
                                    Text("\u25B6  Play All", fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = onShuffle,
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    shape = RoundedCornerShape(0.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                                ) {
                                    Text("\u21C6  Shuffle", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (visible.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = "No songs match \"${query.trim()}\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }

                        items(
                            visible,
                            key = { (index, song) -> "${song.id}::$index" },
                            span = { GridItemSpan(maxLineSpan) },
                        ) { (index, song) ->
                            SongRow(
                                song = song,
                                index = index,
                                onSongClick = { onSongClick(song, index) },
                                onSongDoubleClick = { onSongDoubleClick(song, index) },
                                onSongCtrlClick = { onSongCtrlClick(song) },
                                onSongShiftClick = { onSongShiftClick(song, index) },
                                contextMenuSongs = { contextMenuSongs(visibleSongs, song) },
                                isSelected = isSelected(song.id),
                                isPlaying = isPlaying(song.id),
                                onArtistClick = { /* Already in artist view */ },
                                onAlbumClick = { albumId ->
                                    val album = albums.find { it.id == albumId } ?: appearsOnAlbums.find { it.id == albumId }
                                    if (album != null) onAlbumClick(album) else onSongAlbumClick(albumId)
                                },
                                showAlbum = true,
                                showArtist = false,
                                audioFormat = song.audioFormat,
                                showFavorite = true,
                            )
                        }
                    }
                }

                CollapsedHeaderOverlay(
                    state = headerState,
                    title = artist.name,
                    image = { modifier -> ArtistImage(url = largeImageUrl, artistName = artist.name, modifier = modifier) },
                )

                VerticalScrollbar(Modifier.align(Alignment.CenterEnd).fillMaxHeight(), state)
            }
        }
    }
}

    @Composable
private fun AlbumDetailPane(
    album: Album?,
    onSongClick: (Song, Int) -> Unit,
    onSongDoubleClick: (Song, Int) -> Unit,
    isSelected: (String) -> Boolean,
    isPlaying: (String) -> Boolean,
    onArtistClick: (String) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    state: LazyListState,
    modifier: Modifier = Modifier,
    onSongCtrlClick: (Song) -> Unit = {},
    onSongShiftClick: (Song, Int) -> Unit = { _, _ -> },
    contextMenuSongs: (List<Song>, Song) -> List<Song> = { _, song -> listOf(song) },
) {
    val coverArt = LocalCoverArtUrlProvider.current
    RememberScrollPosition(state, "album")
    var query by remember(album?.id) { mutableStateOf("") }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(0.dp),
    ) {
        if (album == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Select an album to see its songs",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val headerState = rememberCollapsingHeaderState(state)
            // The songs matching the search, with their positions in the album, which the callbacks get
            val visible = remember(album.songs, query) { album.songs.filtered(query) }
            val visibleSongs = remember(visible) { visible.map { it.value } }
            // The field is the third item, after the header and the play buttons
            val filterFocus = rememberSongFilterFocus(state, itemIndex = 2)
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = state,
                    contentPadding = PaddingValues(bottom = 100.dp),
                    modifier = Modifier.onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            val currentIndex = visibleSongs.indexOfFirst { isSelected(it.id) }
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (currentIndex < visible.size - 1) {
                                        val (index, song) = visible[currentIndex + 1]
                                        onSongClick(song, index)
                                        true
                                    } else false
                                }
                                Key.DirectionUp -> {
                                    if (currentIndex > 0) {
                                        val (index, song) = visible[currentIndex - 1]
                                        onSongClick(song, index)
                                        true
                                    } else false
                                }
                                else -> false
                            }
                        } else false
                    }
                ) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .collapsingHeader(headerState)
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CoverArtImage(
                                url = coverArt.getUrl(album.coverArtId),
                                modifier = Modifier.size(200.dp).collapsingHeaderImage(headerState),
                                placeholderFontSize = 60.sp,
                                enlargedUrl = coverArt.getUrl(album.coverArtId, ENLARGED_IMAGE_SIZE),
                            )

                            Spacer(Modifier.height(16.dp))

                            Text(
                                text = album.name,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.collapsingHeaderTitle(headerState),
                            )

                            ClickableArtistText(
                                artists = album.artists,
                                fallback = album.artist.ifBlank { "Unknown artist" },
                                onArtistClick = onArtistClick,
                                style = MaterialTheme.typography.bodyMedium,
                            )

                            val albumInfo = buildString {
                                append("Album")
                                if (album.year != null) {
                                    append(" \u2022 ")
                                    append(album.year)
                                }
                                if (album.songCount > 0) {
                                    append(" \u2022 ")
                                    append("${album.songCount} song${if (album.songCount != 1) "s" else ""}")
                                }
                            }
                            Text(
                                text = albumInfo,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = onPlayAll,
                                modifier = Modifier.weight(1f).height(44.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                                shape = RoundedCornerShape(0.dp),
                            ) {
                                Text("\u25B6  Play", fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = onShuffle,
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(0.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                            ) {
                                Text("\u21C6  Shuffle", fontWeight = FontWeight.Bold)
                            }

                            FavoriteButton(FavoriteKind.Album, album.id)
                        }
                    }

                    item {
                        SongFilterField(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "Search in album",
                            focus = filterFocus,
                        )
                    }

                    if (visible.isEmpty() && query.isNotBlank()) {
                        item {
                            Text(
                                text = "No songs match \"${query.trim()}\"",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }

                    items(visible, key = { (index, song) -> "${song.id.ifEmpty { "song" }}::${song.title}::${song.artist}::$index" }) { (index, song) ->
                        SongRow(
                            song = song,
                            index = index,
                            onArtistClick = onArtistClick,
                            onSongClick = { onSongClick(song, index) },
                            onSongDoubleClick = { onSongDoubleClick(song, index) },
                            onSongCtrlClick = { onSongCtrlClick(song) },
                            onSongShiftClick = { onSongShiftClick(song, index) },
                            contextMenuSongs = { contextMenuSongs(visibleSongs, song) },
                            isSelected = isSelected(song.id),
                            isPlaying = isPlaying(song.id),
                            audioFormat = song.audioFormat,
                            showFavorite = true,
                        )
                    }
                }

                CollapsedHeaderOverlay(
                    state = headerState,
                    title = album.name,
                    image = { modifier -> CoverArtImage(url = coverArt.getUrl(album.coverArtId), modifier = modifier) },
                )

                VerticalScrollbar(Modifier.align(Alignment.CenterEnd).fillMaxHeight(), state)
            }
        }
    }
}

@Composable
private fun ArtistHeader(
    artistId: String?,
    artistName: String,
    imageUrl: String?,
    biography: String?,
    songTotals: String?,
    headerState: CollapsingHeaderState,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .collapsingHeader(headerState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtistImage(
            url = imageUrl,
            artistName = artistName,
            modifier = Modifier.size(180.dp).collapsingHeaderImage(headerState),
            // The artist image comes in one size
            enlargedUrl = imageUrl,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = artistName,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.collapsingHeaderTitle(headerState),
        )

        if (songTotals != null) {
            Text(text = songTotals, style = MaterialTheme.typography.bodySmall)
        }

        FavoriteButton(FavoriteKind.Artist, artistId)

        if (!biography.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            ExpandableText(
                text = biography,
                style = MaterialTheme.typography.bodyMedium,
                color = SpotifyLightGray,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

/** [showArtist] where the albums aren't all of one artist. */
@Composable
internal fun AlbumCard(album: Album, showArtist: Boolean = false, subtitle: String? = null) {
    val coverArt = LocalCoverArtUrlProvider.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            CoverArtImage(
                url = coverArt.getUrl(album.coverArtId),
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                placeholderFontSize = 40.sp,
            )
            if (album in LocalDownloadedCollections.current) {
                DownloadedIndicator(Modifier.padding(6.dp), size = 26.dp, onCover = true)
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = album.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (album.id.isYouTubeId) YouTubeIcon(Modifier.padding(start = 4.dp), size = 16.dp)
            FavoriteButton(FavoriteKind.Album, album.id, Modifier.size(28.dp), iconSize = 18.dp)
        }

        Text(
            text = subtitle ?: if (showArtist) listOfNotNull(album.artist.ifBlank { null }, album.year?.toString()).joinToString(" \u2022 ")
            else album.year?.toString() ?: "",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


/**
 * All playlists as a grid of covers, in the order of the playlists sidebar: first those without a folder, then each
 * folder's (see [PlaylistSidebarEntry]) under its name.
 */
@Composable
private fun PlaylistOverviewPane(
    playlists: List<Playlist>,
    order: LibraryOrder,
    downloads: Map<String, PlaylistDownloadStatus>,
    onPlaylistClick: (Playlist) -> Unit,
    onPlaylistDoubleClick: (Playlist) -> Unit,
    state: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val sorted = remember(playlists, order) { playlists.sortedBy(order) }
    val unfoldered = remember(sorted) { sorted.filter { it.folderAndName() == null } }
    val folders = remember(sorted) {
        sorted.mapNotNull { playlist -> playlist.folderAndName()?.let { (folder, name) -> folder to (playlist to name) } }
            .groupBy({ it.first }, { it.second })
    }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(0.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                state = state,
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        Text(
                            text = "Playlists",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${playlists.size} playlist${if (playlists.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (playlists.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        Text(
                            text = "No playlists",
                            color = SpotifyLightGray,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                items(unfoldered, key = { it.id }) { playlist ->
                    PlaylistCard(playlist, playlist.name, downloads[playlist.id], onPlaylistClick, onPlaylistDoubleClick)
                }
                folders.forEach { (folder, entries) ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "folder:$folder") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp),
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = SpotifyLightGray, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = folder,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    items(entries, key = { "$folder/${it.first.id}" }) { (playlist, name) ->
                        PlaylistCard(playlist, name, downloads[playlist.id], onPlaylistClick, onPlaylistDoubleClick)
                    }
                }
            }
            VerticalScrollbar(Modifier.align(Alignment.CenterEnd).fillMaxHeight(), state)
        }
    }
}

/** A playlist of the [PlaylistOverviewPane]; songs dropped onto it get added to it. */
@Composable
private fun PlaylistCard(
    playlist: Playlist,
    name: String,
    download: PlaylistDownloadStatus?,
    onClick: (Playlist) -> Unit,
    onDoubleClick: (Playlist) -> Unit,
) {
    val coverArt = LocalCoverArtUrlProvider.current
    val playlistEditor = LocalPlaylistEditor.current
    val dropTarget = rememberSongDropTarget { songs -> playlistEditor?.addToPlaylist(playlist, songs) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .songDropTarget(dropTarget)
            .background(
                when {
                    dropTarget?.isHovered == true -> AccentColor.copy(alpha = 0.32f)
                    dropTarget?.isActive == true -> AccentColor.copy(alpha = 0.08f)
                    else -> Color.Transparent
                }
            )
            .combinedClickable(onClick = { onClick(playlist) }, onDoubleClick = { onDoubleClick(playlist) })
            .padding(8.dp),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            CoverArtImage(
                url = coverArt.getUrl(playlist.coverArtId),
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                cornerRadius = 0.dp,
                placeholderFontSize = 40.sp,
            )
            if (download?.isComplete == true) {
                DownloadedIndicator(Modifier.padding(6.dp), size = 26.dp, onCover = true)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${playlist.songCount} song${if (playlist.songCount != 1) "s" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlaylistSidebar(
    entries: List<PlaylistSidebarEntry>,
    onFolderClick: (PlaylistSidebarEntry.Folder) -> Unit,
    selectedPlaylistId: String?,
    /** Marked slightly; a collapsed folder with it too. */
    playingPlaylistId: String?,
    onPlaylistClick: (Playlist) -> Unit,
    onPlaylistDoubleClick: ((Playlist) -> Unit)?,
    state: LazyListState,
    /** Null where playlists can't be downloaded. */
    downloads: Map<String, PlaylistDownloadStatus>? = null,
    onDownload: (Playlist) -> Unit = {},
    onRemoveDownload: (Playlist) -> Unit = {},
) {
    val coverArt = LocalCoverArtUrlProvider.current
    // Songs dragged onto a playlist get added to it
    val playlistEditor = LocalPlaylistEditor.current

    SidebarList(
        items = entries,
        key = { _, entry ->
            when (entry) {
                is PlaylistSidebarEntry.Folder -> "folder:${entry.name}"
                is PlaylistSidebarEntry.Item -> entry.playlist.id
            }
        },
        state = state,
        emptyText = "No playlists",
    ) { _, entry ->
        if (entry is PlaylistSidebarEntry.Folder) {
            val isPlaying = !entry.isExpanded && entry.playlists.any { it.id == playingPlaylistId }
            PlaylistFolderRow(entry, isPlaying = isPlaying, onClick = { onFolderClick(entry) })
            return@SidebarList
        }
        val (playlist, name, folder) = entry as PlaylistSidebarEntry.Item
        val isSelected = playlist.id == selectedPlaylistId
        val isPlaying = playlist.id == playingPlaylistId
        val dropTarget = rememberSongDropTarget { songs -> playlistEditor?.addToPlaylist(playlist, songs) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .songDropTarget(dropTarget)
                .clip(RoundedCornerShape(0.dp))
                .background(
                    when {
                        dropTarget?.isHovered == true -> AccentColor.copy(alpha = 0.32f)
                        isSelected -> AccentColor.copy(alpha = 0.16f)
                        dropTarget?.isActive == true -> AccentColor.copy(alpha = 0.08f)
                        isPlaying -> PLAYING_PLAYLIST_HIGHLIGHT
                        else -> Color.Transparent
                    }
                )
                .combinedClickable(
                    onClick = { onPlaylistClick(playlist) },
                    onDoubleClick = onPlaylistDoubleClick?.let { { it(playlist) } },
                )
                .padding(start = if (folder != null) 30.dp else 10.dp, top = 10.dp, end = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArtImage(
                url = coverArt.getUrl(playlist.coverArtId),
                modifier = Modifier.size(44.dp),
                cornerRadius = 0.dp,
                placeholderFontSize = 18.sp,
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${playlist.songCount} song${if (playlist.songCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (downloads != null) {
                PlaylistDownloadButton(
                    playlistName = playlist.name,
                    status = downloads[playlist.id],
                    onDownload = { onDownload(playlist) },
                    onRemove = { onRemoveDownload(playlist) },
                )
            }
        }
    }
}

/** The background of the playlist being played in the sidebar, fainter than a selected item's. */
private val PLAYING_PLAYLIST_HIGHLIGHT = AccentColor.copy(alpha = 0.07f)

/** A folder of the playlists sidebar; a click expands or collapses it. */
@Composable
private fun PlaylistFolderRow(folder: PlaylistSidebarEntry.Folder, isPlaying: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isPlaying) PLAYING_PLAYLIST_HIGHLIGHT else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(
                if (folder.isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                contentDescription = null,
                tint = SpotifyLightGray,
                modifier = Modifier.size(32.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${folder.playlists.size} playlist${if (folder.playlists.size != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Icon(
            if (folder.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (folder.isExpanded) "Collapse" else "Expand",
            tint = SpotifyLightGray,
        )
    }
}

@Composable
private fun PlaylistDetailPane(
    playlist: Playlist?,
    isLoading: Boolean,
    isOffline: Boolean = false,
    onSongClick: (Song, Int) -> Unit,
    onSongDoubleClick: (Song, Int) -> Unit,
    onRemoveClick: (indices: List<Int>) -> Unit,
    isSelected: (String) -> Boolean,
    isPlaying: (String) -> Boolean,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    state: LazyListState,
    modifier: Modifier = Modifier,
    onSongCtrlClick: (Song) -> Unit = {},
    /** Gets the songs shown, which the search may limit, and the position of the clicked one among them. */
    onSongShiftClick: (List<Song>, Int) -> Unit = { _, _ -> },
    contextMenuSongs: (List<Song>, Song) -> List<Song> = { _, song -> listOf(song) },
    contextMenuIndices: (List<Song>, Int) -> List<Int> = { _, index -> listOf(index) },
    /** Songs dragged to another position: their positions in the playlist and the one they go before. */
    onMoveSongs: (indices: List<Int>, before: Int) -> Unit = { _, _ -> },
) {
    val coverArt = LocalCoverArtUrlProvider.current
    RememberScrollPosition(state, "playlist")
    var query by remember(playlist?.id) { mutableStateOf("") }

    Surface(
        modifier = modifier.playlistDropTarget(playlist),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(0.dp),
    ) {
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentColor)
                }
            }

            playlist == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (isOffline) "This playlist isn't stored on this device" else "Select a playlist to see its songs",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                val headerState = rememberCollapsingHeaderState(state)
                // The songs matching the search, with their positions in the playlist, which the callbacks get
                val visible = remember(playlist.songs, query) { playlist.songs.filtered(query) }
                val visibleSongs = remember(visible) { visible.map { it.value } }
                // The field is the third item, after the header and the play buttons
                val filterFocus = rememberSongFilterFocus(state, itemIndex = 2)
                // Songs dragged within the list move there; not while searching, as the hidden ones would be in the way
                val reorder = rememberPlaylistReorder(playlist.id, state, enabled = query.isBlank(), onMove = onMoveSongs)
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = state,
                        contentPadding = PaddingValues(bottom = 100.dp),
                        modifier = Modifier.playlistReorderTarget(reorder).onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                val currentIndex = visibleSongs.indexOfFirst { isSelected(it.id) }
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        if (currentIndex < visible.size - 1) {
                                            val (index, song) = visible[currentIndex + 1]
                                            onSongClick(song, index)
                                            true
                                        } else false
                                    }
                                    Key.DirectionUp -> {
                                        if (currentIndex > 0) {
                                            val (index, song) = visible[currentIndex - 1]
                                            onSongClick(song, index)
                                            true
                                        } else false
                                    }
                                    else -> false
                                }
                            } else false
                        }
                    ) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .collapsingHeader(headerState)
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CoverArtImage(
                                    url = coverArt.getUrl(playlist.coverArtId),
                                    modifier = Modifier.size(200.dp).collapsingHeaderImage(headerState),
                                    placeholderFontSize = 60.sp,
                                )

                                Spacer(Modifier.height(16.dp))

                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.collapsingHeaderTitle(headerState),
                                )

                                Text(
                                    text = "Playlist",
                                    style = MaterialTheme.typography.bodyMedium,
                                )

                                val totalDuration = playlist.songs.sumOf { it.duration }
                                Text(
                                    text = listOfNotNull(
                                        "${playlist.songCount} songs",
                                        totalDuration.takeIf { it > 0 }?.formatTotalDuration(),
                                    ).joinToString(" \u2022 "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Button(
                                    onClick = onPlayAll,
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                                    shape = RoundedCornerShape(0.dp),
                                ) {
                                    Text("\u25B6  Play", fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = onShuffle,
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    shape = RoundedCornerShape(0.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                                ) {
                                    Text("\u21C6  Shuffle", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        item {
                            SongFilterField(
                                query = query,
                                onQueryChange = { query = it },
                                placeholder = "Search in playlist",
                                focus = filterFocus,
                            )
                        }

                        if (visible.isEmpty() && playlist.songs.isNotEmpty()) {
                            item {
                                Text(
                                    text = "No songs match \"${query.trim()}\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                                )
                            }
                        }

                        // Selections and context menus only act on the songs shown, so hidden ones aren't affected unnoticed
                        itemsIndexed(visible, key = { _, (index, song) -> playlistEntryKey("${song.id.ifEmpty { "song" }}::${song.title}::${song.artist}", index) }) { visibleIndex, (index, song) ->
                            SongRow(
                                song = song,
                                index = index,
                                onSongClick = { onSongClick(song, index) },
                                onSongDoubleClick = { onSongDoubleClick(song, index) },
                                onSongCtrlClick = { onSongCtrlClick(song) },
                                onSongShiftClick = { onSongShiftClick(visibleSongs, visibleIndex) },
                                contextMenuSongs = { contextMenuSongs(visibleSongs, song) },
                                onRemoveFromPlaylist = { onRemoveClick(contextMenuIndices(visibleSongs, visibleIndex).map { visible[it].index }) },
                                isSelected = isSelected(song.id),
                                isPlaying = isPlaying(song.id),
                                onArtistClick = onArtistClick,
                                onAlbumClick = onAlbumClick,
                                dragOrigin = {
                                    PlaylistEntriesDrag(playlist.id, contextMenuIndices(visibleSongs, visibleIndex).map { visible[it].index })
                                },
                            )
                        }
                    }

                    reorder?.let { PlaylistReorderIndicator(it, Modifier.matchParentSize()) }

                    CollapsedHeaderOverlay(
                        state = headerState,
                        title = playlist.name,
                        image = { modifier -> CoverArtImage(url = coverArt.getUrl(playlist.coverArtId), modifier = modifier) },
                    )

                    VerticalScrollbar(Modifier.align(Alignment.CenterEnd).fillMaxHeight(), state)
                }
            }
        }
    }
}

@Composable
private fun ArtistListItem(artist: Artist, isSelected: Boolean, onClick: () -> Unit, onDoubleClick: (() -> Unit)?) {
    val coverArt = LocalCoverArtUrlProvider.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(0.dp))
            .background(if (isSelected) AccentColor.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(
                onClick = onClick,
                onDoubleClick = onDoubleClick,
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtistImage(
            url = coverArt.getUrl(artist.coverArtId),
            artistName = artist.name,
            modifier = Modifier.size(44.dp),
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append("${artist.albumCount} album${if (artist.albumCount != 1) "s" else ""}")
                    artist.songCount?.let { append(" • $it song${if (it != 1) "s" else ""}") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (artist in LocalDownloadedCollections.current) DownloadedIndicator(Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun AlbumListItem(album: Album, isSelected: Boolean, onClick: () -> Unit, onDoubleClick: (() -> Unit)?) {
    val coverArt = LocalCoverArtUrlProvider.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(0.dp))
            .background(if (isSelected) AccentColor.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(
                onClick = onClick,
                onDoubleClick = onDoubleClick,
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArtImage(
            url = coverArt.getUrl(album.coverArtId),
            modifier = Modifier.size(44.dp),
            cornerRadius = 0.dp,
            placeholderFontSize = 18.sp,
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = buildString {
                append(album.artist.ifBlank { "Unknown artist" })
                if (album.songCount > 0) {
                    append(" \u2022 ")
                    append("${album.songCount} song${if (album.songCount != 1) "s" else ""}")
                }
            }
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (album in LocalDownloadedCollections.current) DownloadedIndicator(Modifier.padding(start = 8.dp))
    }
}

/** Favorite songs (as one entry that opens them), artists and albums. */
@Composable
private fun FavoritesSidebar(
    state: LibraryState,
    order: LibraryOrder,
    /** Limits the artists and albums, see [TextFilter]. */
    filter: String,
    onSongsClick: () -> Unit,
    onArtistClick: (Artist) -> Unit,
    onArtistDoubleClick: ((Artist) -> Unit)?,
    onAlbumClick: (Album) -> Unit,
    onAlbumDoubleClick: ((Album) -> Unit)?,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val sortedArtists = remember(state.favoriteArtists, order) { state.favoriteArtists.sortedBy(order) }
    val artists = remember(sortedArtists, filter) { sortedArtists.matching(filter) { matches(it.name) } }
    val sortedAlbums = remember(state.favoriteAlbums, order) { state.favoriteAlbums.sortedBy(order) }
    val albums = remember(sortedAlbums, filter) { sortedAlbums.matching(filter) { matches(it.name, it.artist) } }
    Box(modifier = modifier.padding(12.dp)) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "songs") {
                val songCount = state.favoriteSongs.size
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (state.activeDetailView == LibraryDetailView.FavoriteSongs) AccentColor.copy(alpha = 0.16f)
                            else Color.Transparent
                        )
                        .clickable(onClick = onSongsClick)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FavoritesImage(Modifier.size(44.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = FAVORITE_SONGS_TITLE,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "$songCount song${if (songCount != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (artists.isNotEmpty()) {
                item(key = "artists-header") { FavoritesSectionTitle("Artists") }
                items(artists, key = { "artist:${it.id}" }) { artist ->
                    ArtistListItem(
                        artist = artist,
                        isSelected = state.activeDetailView == LibraryDetailView.Artists && artist.id == state.selectedArtistId,
                        onClick = { onArtistClick(artist) },
                        onDoubleClick = onArtistDoubleClick?.let { { it(artist) } },
                    )
                }
            }

            if (albums.isNotEmpty()) {
                item(key = "albums-header") { FavoritesSectionTitle("Albums") }
                items(albums, key = { "album:${it.id}" }) { album ->
                    AlbumListItem(
                        album = album,
                        isSelected = state.activeDetailView == LibraryDetailView.Albums && album.id == state.selectedAlbumId,
                        onClick = { onAlbumClick(album) },
                        onDoubleClick = onAlbumDoubleClick?.let { { it(album) } },
                    )
                }
            }
        }

        VerticalScrollbar(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            state = listState,
        )
    }
}

@Composable
private fun FavoritesSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 10.dp, top = 8.dp),
    )
}
