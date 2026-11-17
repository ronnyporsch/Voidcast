package de.ronnyporsch.voidcast.ui.screen.albums

import de.ronnyporsch.voidcast.domain.model.isYouTubeId
import de.ronnyporsch.voidcast.ui.components.YouTubeIcon
import androidx.compose.runtime.saveable.rememberSaveable
import de.ronnyporsch.voidcast.ui.components.matchesFilter
import de.ronnyporsch.voidcast.ui.components.filtered
import de.ronnyporsch.voidcast.ui.components.SongFilterField
import de.ronnyporsch.voidcast.ui.components.FavoriteButton
import de.ronnyporsch.voidcast.domain.model.FavoriteKind
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import de.ronnyporsch.voidcast.ui.components.TooltipIconButton
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ronnyporsch.voidcast.domain.model.Album
import de.ronnyporsch.voidcast.domain.model.formatTotals
import de.ronnyporsch.voidcast.ui.LocalCoverArtUrlProvider
import de.ronnyporsch.voidcast.ui.components.ArtistImage
import de.ronnyporsch.voidcast.ui.components.CoverArtImage
import de.ronnyporsch.voidcast.ui.components.CollapsingHeaderState
import de.ronnyporsch.voidcast.ui.components.CollapsingTopBar
import de.ronnyporsch.voidcast.ui.components.collapsingHeader
import de.ronnyporsch.voidcast.ui.components.collapsingHeaderImage
import de.ronnyporsch.voidcast.ui.components.collapsingHeaderTitle
import de.ronnyporsch.voidcast.ui.components.rememberCollapsingHeaderState
import de.ronnyporsch.voidcast.ui.components.ExpandableText
import de.ronnyporsch.voidcast.ui.components.SelectableAlbum
import de.ronnyporsch.voidcast.ui.components.SimilarArtists
import de.ronnyporsch.voidcast.ui.components.SongRow
import de.ronnyporsch.voidcast.ui.components.DownloadedIndicator
import de.ronnyporsch.voidcast.ui.components.LocalDownloadedCollections
import de.ronnyporsch.voidcast.ui.components.LocalSongDownloads
import de.ronnyporsch.voidcast.ui.components.PlaylistDownloadButton
import de.ronnyporsch.voidcast.ui.components.rememberAlbumSelection
import de.ronnyporsch.voidcast.ui.components.RememberScrollPosition
import de.ronnyporsch.voidcast.ui.components.VerticalScrollbar
import de.ronnyporsch.voidcast.ui.components.libraryFormat
import de.ronnyporsch.voidcast.ui.components.YouTubeDownloadButton
import de.ronnyporsch.voidcast.ui.components.YouTubeDownloadMessage
import de.ronnyporsch.voidcast.ui.components.contextMenuSongs
import de.ronnyporsch.voidcast.ui.components.AlbumTouchSelectionBar
import de.ronnyporsch.voidcast.ui.components.TouchSelectionBar
import de.ronnyporsch.voidcast.ui.components.rememberAlbumTouchSelection
import de.ronnyporsch.voidcast.ui.components.rememberTouchSelection
import de.ronnyporsch.voidcast.ui.components.touchSelection
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SpotifyLightGray

@Composable
fun ArtistAlbumsScreen(
    viewModel: ArtistAlbumsViewModel,
    onBack: () -> Unit,
    currentSongId: String? = null,
) {
    val state by viewModel.state.collectAsState()
    val gridState = rememberLazyGridState()
    RememberScrollPosition(gridState)
    val albumSelection = rememberAlbumSelection()
    // Limits the albums and songs shown
    var query by rememberSaveable { mutableStateOf("") }
    // Library albums all go under "Albums"
    val (albums, singles) = state.albums.filter { matchesFilter(query, it.name) }.partition { it.isFullAlbum }
    val appearsOnAlbums = state.appearsOnAlbums.filter { matchesFilter(query, it.name, it.artist) }
    // In the order they are shown, for selecting ranges of them
    val shownAlbums = albums + singles + appearsOnAlbums
    // The songs matching the search, with their positions among all of them, which the events refer to
    val visible = remember(state.songs, query) { state.songs.filtered(query) }
    val visibleSongs = remember(visible) { visible.map { it.value } }
    // Mobile: albums and songs are selected by touch, one kind at a time
    val albumTouchSelection = rememberAlbumTouchSelection(shownAlbums, albumSelection) {
        if (it.isNotEmpty() && state.selectedSongIds.isNotEmpty()) viewModel.onEvent(ArtistAlbumsEvent.SongsSelected(emptySet()))
    }
    val songKey = { index: Int, song: Song -> "${song.id}::$index" }
    val songTouchSelection = rememberTouchSelection(
        items = visibleSongs,
        selectedIds = state.selectedSongIds,
        onChange = {
            viewModel.onEvent(ArtistAlbumsEvent.SongsSelected(it))
            if (it.isNotEmpty()) albumSelection.clear()
        },
        id = { it.id },
        key = { index, song -> songKey(visible[index].index, song) },
    )
    AlbumTouchSelectionBar(albumTouchSelection)
    TouchSelectionBar(songTouchSelection, songs = { songTouchSelection.selectedItems })
    val headerState = rememberCollapsingHeaderState(gridState)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        CollapsingTopBar(
            onBack = onBack,
            state = headerState,
            title = state.artistName,
            image = { modifier ->
                ArtistImage(url = state.largeImageUrl, artistName = state.artistName, modifier = modifier)
            },
        )

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentColor)
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(bottom = 100.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .touchSelection(albumTouchSelection, gridState)
                        .touchSelection(songTouchSelection, gridState)
                        .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            val currentIndex = visibleSongs.indexOfFirst { it.id in state.selectedSongIds }
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (currentIndex < visible.size - 1) {
                                        val (index, song) = visible[currentIndex + 1]
                                        viewModel.onEvent(ArtistAlbumsEvent.SongClicked(song, index))
                                        true
                                    } else false
                                }

                                Key.DirectionUp -> {
                                    if (currentIndex > 0) {
                                        val (index, song) = visible[currentIndex - 1]
                                        viewModel.onEvent(ArtistAlbumsEvent.SongClicked(song, index))
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
                            artistId = state.artistId,
                            artistName = state.artistName,
                            imageUrl = state.largeImageUrl,
                            biography = state.biography,
                            // YouTube Music only lists an artist's top songs
                            songTotals = if (state.isYouTube) null else state.songs.formatTotals(),
                            headerState = headerState,
                            songs = state.songs,
                            coverArtId = state.coverArtId,
                            youTubeDownload = if (state.isYouTube && state.canDownload && state.albums.isNotEmpty()) {
                                { YouTubeArtistDownloadButton(state) { viewModel.onEvent(ArtistAlbumsEvent.DownloadArtist(it)) } }
                            } else null,
                        )
                    }

                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SongFilterField(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "Search in albums and songs",
                        )
                    }

                    for ((title, releases) in listOf("Albums" to albums, "Singles & EPs" to singles)) {
                        if (releases.isEmpty()) continue
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }

                        items(releases, key = { it.id }) { album ->
                            SelectableAlbum(
                                album = album,
                                albums = shownAlbums,
                                selection = albumSelection,
                                onClick = { viewModel.onEvent(ArtistAlbumsEvent.AlbumClicked(album)) },
                                touchSelection = albumTouchSelection,
                            ) {
                                AlbumCard(album, downloaded = album.id in state.downloadedAlbumIds)
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
                            SelectableAlbum(
                                album = album,
                                albums = shownAlbums,
                                selection = albumSelection,
                                onClick = { viewModel.onEvent(ArtistAlbumsEvent.AlbumClicked(album)) },
                                touchSelection = albumTouchSelection,
                            ) {
                                AlbumCard(album, downloaded = album.id in state.downloadedAlbumIds)
                            }
                        }
                    }

                    if (query.isBlank() && state.similarArtists.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SimilarArtists(state.similarArtists, onArtistClick = { viewModel.onEvent(ArtistAlbumsEvent.SimilarArtistClicked(it)) })
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

                    if (state.isSongsLoading) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(color = AccentColor, modifier = Modifier.size(24.dp))
                            }
                        }
                    } else if (state.songs.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Button(
                                    onClick = { viewModel.onEvent(ArtistAlbumsEvent.PlayAllSongs) },
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                                    shape = RoundedCornerShape(0.dp),
                                ) {
                                    Text("\u25B6  Play All", fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = { viewModel.onEvent(ArtistAlbumsEvent.ShuffleAllSongs) },
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
                            key = { (index, song) -> songKey(index, song) },
                            span = { GridItemSpan(maxLineSpan) },
                        ) { (index, song) ->
                            Column {
                                SongRow(
                                    song = song,
                                    index = index,
                                    onSongClick = { songTouchSelection.click(song) { viewModel.onEvent(ArtistAlbumsEvent.SongClicked(song, index)) } },
                                    longPressSelects = songTouchSelection.isEnabled,
                                    onSongDoubleClick = { viewModel.onEvent(ArtistAlbumsEvent.SongDoubleClicked(song, index)) },
                                    onSongCtrlClick = { viewModel.onEvent(ArtistAlbumsEvent.SongSelectionToggled(song)) },
                                    onSongShiftClick = { viewModel.onEvent(ArtistAlbumsEvent.SongRangeSelected(index)) },
                                    contextMenuSongs = { visibleSongs.contextMenuSongs(song, state.selectedSongIds) },
                                    onArtistClick = { /* Already in artist view */ },
                                    isSelected = song.id in state.selectedSongIds,
                                    isPlaying = currentSongId == song.id,
                                    showAlbum = true,
                                    showArtist = false,
                                    audioFormat = song.libraryFormat(state.libraryFormats),
                                    showFavorite = true,
                                    trailingContent = if (state.isYouTube && state.canDownload) {
                                        {
                                            YouTubeDownloadButton(song, state.downloads[song.id]) { edited ->
                                                viewModel.onEvent(ArtistAlbumsEvent.DownloadSong(edited))
                                            }
                                        }
                                    } else null,
                                )
                                YouTubeDownloadMessage(state.downloads[song.id])
                            }
                        }
                    }
                }

                VerticalScrollbar(Modifier.align(Alignment.CenterEnd).fillMaxHeight(), gridState)
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
    /** The artist's songs as far as loaded, for downloading them. */
    songs: List<Song>,
    coverArtId: String?,
    /** Downloads a YouTube Music artist into the library. */
    youTubeDownload: (@Composable () -> Unit)? = null,
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

        if (artistId?.isYouTubeId == true) YouTubeIcon(Modifier.padding(top = 8.dp), size = 24.dp)

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Keeps all songs of the artist on the device for offline playback
            val offlineDownloads = LocalSongDownloads.current
            if (offlineDownloads != null && artistId != null && !artistId.isYouTubeId) {
                val artistDownloads by offlineDownloads.artistStatus.collectAsState()
                PlaylistDownloadButton(
                    playlistName = artistName,
                    status = artistDownloads[artistId],
                    onDownload = { offlineDownloads.downloadArtist(artistId, artistName, songs, coverArtId) },
                    onRemove = { offlineDownloads.removeArtist(artistId) },
                    kind = "artist",
                )
            }

            youTubeDownload?.invoke()

            FavoriteButton(FavoriteKind.Artist, artistId)
        }

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

/**
 * Downloads all releases of a YouTube Music artist into the server's library, after asking whether that is meant;
 * [onDownload] gets whether to download the singles and EPs too.
 */
@Composable
private fun YouTubeArtistDownloadButton(state: ArtistAlbumsState, onDownload: (includeSingles: Boolean) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val missing = state.albums.filter { it.id !in state.downloadedAlbumIds }
    when {
        state.albums.any { it.id in state.pendingAlbumIds } -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = AccentColor, strokeWidth = 2.dp)
        }
        missing.isEmpty() -> TooltipIconButton(
            onClick = {},
            icon = Icons.Default.CheckCircle,
            contentDescription = "In library",
            tint = AccentColor,
        )
        else -> TooltipIconButton(
            onClick = { showDialog = true },
            icon = Icons.Default.Download,
            contentDescription = "Download artist to library",
            tint = SpotifyLightGray,
        )
    }

    if (showDialog) {
        val (albums, singles) = missing.partition { it.isFullAlbum }
        var includeSingles by remember { mutableStateOf(true) }
        val count = albums.size + if (includeSingles) singles.size else 0
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Download artist to library") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        buildString {
                            append("All songs of ")
                            append(
                                listOfNotNull(
                                    albums.size.takeIf { it > 0 }?.let { "$it album${if (it != 1) "s" else ""}" },
                                    singles.size.takeIf { it > 0 && includeSingles }?.let { "$it singles & EPs" },
                                ).joinToString(" and ").ifEmpty { "no releases" }
                            )
                            append(" of ${state.artistName} get downloaded into the server's library, with the tags from YouTube Music.")
                            if (missing.size < state.albums.size) append(" Releases that are in the library already are left out.")
                        }
                    )
                    if (albums.isNotEmpty() && singles.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = includeSingles, onCheckedChange = { includeSingles = it })
                            Text("Include singles & EPs")
                        }
                    }
                }
            },
            confirmButton = {
                OutlinedButton(
                    onClick = {
                        showDialog = false
                        onDownload(includeSingles)
                    },
                    enabled = count > 0,
                    shape = RoundedCornerShape(0.dp),
                ) {
                    Text("Download", color = AccentColor)
                }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AlbumCard(album: Album, downloaded: Boolean) {
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
            if (downloaded) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "In library",
                    tint = AccentColor,
                    modifier = Modifier
                        .padding(6.dp)
                        .size(26.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape),
                )
            } else if (album in LocalDownloadedCollections.current) {
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
            text = listOfNotNull(album.releaseType, album.year).joinToString(" \u2022 "),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

