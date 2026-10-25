package de.ronnyporsch.voidcast.ui.screen.library

import de.ronnyporsch.voidcast.ui.components.FavoriteButton
import de.ronnyporsch.voidcast.domain.model.FavoriteKind
import de.ronnyporsch.voidcast.domain.model.audioFormat
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
import androidx.compose.material.icons.filled.Favorite
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    currentSongId: String? = null,
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
    onCreatePlaylist: () -> Unit,
    onOpen: (LibrarySelection) -> Unit,
) {
    // On mobile there is no room for a detail pane: the sidebar fills the screen and items open their own screens
    if (remember { getPlatform().isMobile }) {
        LibrarySidebar(
            state = state,
            viewModel = viewModel,
            onCreatePlaylist = onCreatePlaylist,
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
        onOpen = onOpen,
        modifier = modifier,
    )
}

@Composable
private fun LibrarySidebar(
    state: LibraryState,
    viewModel: LibraryViewModel,
    onCreatePlaylist: () -> Unit,
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
    Surface(
        modifier = modifier,
        color = containerColor,
        shape = RoundedCornerShape(0.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            TabRow(
                selectedTabIndex = state.selectedSidebarTab.ordinal,
                containerColor = containerColor,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                LibrarySidebarTab.entries.forEach { tab ->
                    Tab(
                        selected = state.selectedSidebarTab == tab,
                        onClick = { viewModel.onEvent(LibraryEvent.SidebarTabSelected(tab)) },
                        text = if (tab == LibrarySidebarTab.Favorites) null else {
                            {
                                Text(
                                    tab.name,
                                    fontWeight = if (state.selectedSidebarTab == tab) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        },
                        // Four labels don't fit the sidebar
                        icon = if (tab == LibrarySidebarTab.Favorites) {
                            {
                                Icon(
                                    Icons.Default.Favorite,
                                    contentDescription = "Favorites",
                                    tint = if (state.selectedSidebarTab == tab) AccentColor else SpotifyLightGray,
                                )
                            }
                        } else null,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            when (state.selectedSidebarTab) {
                LibrarySidebarTab.Artists -> ArtistSidebar(
                    artists = state.artists,
                    selectedArtistId = state.selectedArtistId,
                    onArtistClick = {
                        open(LibrarySelection.Artist(it), LibraryEvent.OpenArtistScreen(it))
                    },
                    onArtistDoubleClick = if (openItemScreens) null else {
                        { openAndPlay(LibrarySelection.Artist(it), LibraryEvent.ArtistDoubleClicked(it)) }
                    },
                    state = viewModel.artistSidebarState,
                    modifier = Modifier.fillMaxSize(),
                )
                LibrarySidebarTab.Favorites -> FavoritesSidebar(
                    state = state,
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
                    listState = viewModel.favoritesSidebarState,
                    modifier = Modifier.fillMaxSize(),
                )
                LibrarySidebarTab.Playlists -> PlaylistSidebar(
                    playlists = state.playlists,
                    selectedPlaylistId = state.selectedPlaylistId,
                    onPlaylistClick = {
                        open(LibrarySelection.Playlist(it.id), LibraryEvent.OpenPlaylistScreen(it))
                    },
                    onPlaylistDoubleClick = if (openItemScreens) null else {
                        { openAndPlay(LibrarySelection.Playlist(it.id), LibraryEvent.PlaylistDoubleClicked(it)) }
                    },
                    onCreatePlaylist = onCreatePlaylist,
                    state = viewModel.playlistSidebarState,
                    modifier = Modifier.fillMaxSize(),
                    downloads = if (state.canDownloadPlaylists) state.playlistDownloads else null,
                    onDownload = { viewModel.onEvent(LibraryEvent.DownloadPlaylist(it)) },
                    onRemoveDownload = { viewModel.onEvent(LibraryEvent.RemovePlaylistDownload(it)) },
                )
            }
        }
    }
}

@Composable
private fun LibraryDetailPane(
    state: LibraryState,
    viewModel: LibraryViewModel,
    currentSongId: String?,
    modifier: Modifier = Modifier,
) {
    // One of each per visit of the library, so going back or forward to a visit shows the pane as it was scrolled
    val artistDetailState = rememberLazyGridState()
    val albumDetailState = rememberLazyListState()
    val playlistDetailState = rememberLazyListState()
    val favoriteSongsState = rememberLazyListState()
    when (state.activeDetailView) {
        LibraryDetailView.Artists -> ArtistDetailPane(
            artist = state.selectedArtist,
            albums = state.selectedArtistAlbums,
            appearsOnAlbums = state.selectedArtistAppearsOnAlbums,
            biography = state.selectedArtistBiography,
            largeImageUrl = state.selectedArtistLargeImageUrl,
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
            isSelected = { it in state.selectedSongIds },
            isPlaying = { it == currentSongId },
            onArtistClick = { name -> viewModel.onEvent(LibraryEvent.SongArtistClicked(name)) },
            onAlbumClick = { albumId -> viewModel.onEvent(LibraryEvent.SongAlbumClicked(albumId)) },
            onPlayAll = { viewModel.onEvent(LibraryEvent.PlayAllPlaylistSongs) },
            onShuffle = { viewModel.onEvent(LibraryEvent.ShuffleAllPlaylistSongs) },
            state = playlistDetailState,
            modifier = modifier,
            onSongCtrlClick = { viewModel.onEvent(LibraryEvent.SongSelectionToggled(it)) },
            onSongShiftClick = { _, index -> viewModel.onEvent(LibraryEvent.SongRangeSelected(index)) },
            contextMenuSongs = { songs, song -> songs.contextMenuSongs(song, state.selectedSongIds) },
        )
    }
}

@Composable
private fun ArtistSidebar(
    artists: List<Artist>,
    selectedArtistId: String?,
    onArtistClick: (Artist) -> Unit,
    onArtistDoubleClick: ((Artist) -> Unit)?,
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Row(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = state,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(artists, key = { index, artist -> "${artist.id.ifEmpty { "name:${artist.name}" }}::$index" }) { _, artist ->
                            ArtistListItem(
                                artist = artist,
                                isSelected = artist.id == selectedArtistId,
                                onClick = { onArtistClick(artist) },
                                onDoubleClick = onArtistDoubleClick?.let { { it(artist) } },
                            )
                        }
                    }

                    AlphabetScroller(
                        alphabet = artists.mapNotNull { it.name.firstOrNull()?.uppercaseChar() }.distinct().sorted(),
                        onCharClicked = { char ->
                            val index = artists.indexOfFirst { it.name.firstOrNull()?.uppercaseChar() == char }
                            if (index != -1) {
                                scope.launch { state.animateScrollToItem(index) }
                            }
                        }
                    )
                }

                VerticalScrollbar(
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    state = state
                )
            }
        }
    }

    @Composable
private fun ArtistDetailPane(
    artist: Artist?,
    albums: List<Album>,
    appearsOnAlbums: List<Album>,
    biography: String?,
    largeImageUrl: String?,
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
            // In the order they are shown, for selecting ranges of them
            val shownAlbums = albums + appearsOnAlbums
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = state,
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(bottom = 100.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            val currentIndex = songs.indexOfFirst { isSelected(it.id) }
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (currentIndex < songs.size - 1) {
                                        onSongClick(songs[currentIndex + 1], currentIndex + 1)
                                        true
                                    } else false
                                }
                                Key.DirectionUp -> {
                                    if (currentIndex > 0) {
                                        onSongClick(songs[currentIndex - 1], currentIndex - 1)
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
                            headerState = headerState,
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

                        itemsIndexed(
                            songs,
                            key = { index, song -> "${song.id}::$index" },
                            span = { _, _ -> GridItemSpan(maxLineSpan) },
                        ) { index, song ->
                            SongRow(
                                song = song,
                                index = index,
                                onSongClick = { onSongClick(song, index) },
                                onSongDoubleClick = { onSongDoubleClick(song, index) },
                                onSongCtrlClick = { onSongCtrlClick(song) },
                                onSongShiftClick = { onSongShiftClick(song, index) },
                                contextMenuSongs = { contextMenuSongs(songs, song) },
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
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = state,
                    contentPadding = PaddingValues(bottom = 100.dp),
                    modifier = Modifier.onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            val currentIndex = album.songs.indexOfFirst { isSelected(it.id) }
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (currentIndex < album.songs.size - 1) {
                                        onSongClick(album.songs[currentIndex + 1], currentIndex + 1)
                                        true
                                    } else false
                                }
                                Key.DirectionUp -> {
                                    if (currentIndex > 0) {
                                        onSongClick(album.songs[currentIndex - 1], currentIndex - 1)
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

                    itemsIndexed(album.songs, key = { index, song -> "${song.id.ifEmpty { "song" }}::${song.title}::${song.artist}::$index" }) { index, song ->
                        SongRow(
                            song = song,
                            index = index,
                            onArtistClick = onArtistClick,
                            onSongClick = { onSongClick(song, index) },
                            onSongDoubleClick = { onSongDoubleClick(song, index) },
                            onSongCtrlClick = { onSongCtrlClick(song) },
                            onSongShiftClick = { onSongShiftClick(song, index) },
                            contextMenuSongs = { contextMenuSongs(album.songs, song) },
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
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = artistName,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.collapsingHeaderTitle(headerState),
        )

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

@Composable
private fun AlbumCard(album: Album) {
    val coverArt = LocalCoverArtUrlProvider.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        CoverArtImage(
            url = coverArt.getUrl(album.coverArtId),
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            placeholderFontSize = 40.sp,
        )

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = album.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FavoriteButton(FavoriteKind.Album, album.id, Modifier.size(28.dp), iconSize = 18.dp)
        }

        Text(
            text = album.year?.toString() ?: "",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}


@Composable
private fun PlaylistSidebar(
    playlists: List<Playlist>,
    selectedPlaylistId: String?,
    onPlaylistClick: (Playlist) -> Unit,
    onPlaylistDoubleClick: ((Playlist) -> Unit)?,
    onCreatePlaylist: () -> Unit,
    state: LazyListState,
    modifier: Modifier = Modifier,
    /** Null where playlists can't be downloaded. */
    downloads: Map<String, PlaylistDownloadStatus>? = null,
    onDownload: (Playlist) -> Unit = {},
    onRemoveDownload: (Playlist) -> Unit = {},
) {
    val coverArt = LocalCoverArtUrlProvider.current
    val scope = rememberCoroutineScope()
    // Songs dragged onto a playlist get added to it
    val playlistEditor = LocalPlaylistEditor.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Playlists",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = onCreatePlaylist) {
                Text("+ New", color = AccentColor, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Row(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = state,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(playlists, key = { it.id }) { playlist ->
                            val isSelected = playlist.id == selectedPlaylistId
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
                                            else -> Color.Transparent
                                        }
                                    )
                                    .combinedClickable(
                                        onClick = { onPlaylistClick(playlist) },
                                        onDoubleClick = onPlaylistDoubleClick?.let { { it(playlist) } },
                                    )
                                    .padding(10.dp),
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
                                        text = playlist.name,
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

                    AlphabetScroller(
                        alphabet = playlists.mapNotNull { it.name.firstOrNull()?.uppercaseChar() }.distinct().sorted(),
                        onCharClicked = { char ->
                            val index = playlists.indexOfFirst { it.name.firstOrNull()?.uppercaseChar() == char }
                            if (index != -1) {
                                scope.launch { state.animateScrollToItem(index) }
                            }
                        }
                    )
                }

                VerticalScrollbar(
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    state = state
                )
            }
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
    onSongShiftClick: (Song, Int) -> Unit = { _, _ -> },
    contextMenuSongs: (List<Song>, Song) -> List<Song> = { _, song -> listOf(song) },
    contextMenuIndices: (List<Song>, Int) -> List<Int> = { _, index -> listOf(index) },
) {
    val coverArt = LocalCoverArtUrlProvider.current
    RememberScrollPosition(state, "playlist")

    Surface(
        modifier = modifier,
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
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = state,
                        contentPadding = PaddingValues(bottom = 100.dp),
                        modifier = Modifier.onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                val currentIndex = playlist.songs.indexOfFirst { isSelected(it.id) }
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        if (currentIndex < playlist.songs.size - 1) {
                                            onSongClick(playlist.songs[currentIndex + 1], currentIndex + 1)
                                            true
                                        } else false
                                    }
                                    Key.DirectionUp -> {
                                        if (currentIndex > 0) {
                                            onSongClick(playlist.songs[currentIndex - 1], currentIndex - 1)
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

                                Text(
                                    text = "${playlist.songCount} songs",
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

                        itemsIndexed(playlist.songs, key = { index, song -> "${song.id.ifEmpty { "song" }}::${song.title}::${song.artist}::$index" }) { index, song ->
                            SongRow(
                                song = song,
                                index = index,
                                onSongClick = { onSongClick(song, index) },
                                onSongDoubleClick = { onSongDoubleClick(song, index) },
                                onRemoveClick = { onRemoveClick(listOf(index)) },
                                onSongCtrlClick = { onSongCtrlClick(song) },
                                onSongShiftClick = { onSongShiftClick(song, index) },
                                contextMenuSongs = { contextMenuSongs(playlist.songs, song) },
                                onRemoveFromPlaylist = { onRemoveClick(contextMenuIndices(playlist.songs, index)) },
                                isSelected = isSelected(song.id),
                                isPlaying = isPlaying(song.id),
                                onArtistClick = onArtistClick,
                                onAlbumClick = onAlbumClick,
                            )
                        }
                    }

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
                text = "${artist.albumCount} album${if (artist.albumCount != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    }
}

/** Favorite songs (as one entry that opens them), artists and albums. */
@Composable
private fun FavoritesSidebar(
    state: LibraryState,
    onSongsClick: () -> Unit,
    onArtistClick: (Artist) -> Unit,
    onArtistDoubleClick: ((Artist) -> Unit)?,
    onAlbumClick: (Album) -> Unit,
    onAlbumDoubleClick: ((Album) -> Unit)?,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
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

            if (state.favoriteArtists.isNotEmpty()) {
                item(key = "artists-header") { FavoritesSectionTitle("Artists") }
                items(state.favoriteArtists, key = { "artist:${it.id}" }) { artist ->
                    ArtistListItem(
                        artist = artist,
                        isSelected = state.activeDetailView == LibraryDetailView.Artists && artist.id == state.selectedArtistId,
                        onClick = { onArtistClick(artist) },
                        onDoubleClick = onArtistDoubleClick?.let { { it(artist) } },
                    )
                }
            }

            if (state.favoriteAlbums.isNotEmpty()) {
                item(key = "albums-header") { FavoritesSectionTitle("Albums") }
                items(state.favoriteAlbums, key = { "album:${it.id}" }) { album ->
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
