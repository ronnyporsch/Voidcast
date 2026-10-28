package de.ronnyporsch.voidcast.ui.screen.albums

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.saveable.rememberSaveable
import de.ronnyporsch.voidcast.ui.components.rememberSongFilterFocus
import de.ronnyporsch.voidcast.ui.components.filtered
import de.ronnyporsch.voidcast.ui.components.SongFilterField
import de.ronnyporsch.voidcast.ui.components.FavoriteButton
import de.ronnyporsch.voidcast.domain.model.FavoriteKind
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.domain.model.formatFileSize
import de.ronnyporsch.voidcast.domain.model.formatTotalDuration
import de.ronnyporsch.voidcast.ui.LocalCoverArtUrlProvider
import de.ronnyporsch.voidcast.ui.components.ClickableArtistText
import de.ronnyporsch.voidcast.ui.components.ENLARGED_IMAGE_SIZE
import de.ronnyporsch.voidcast.ui.components.libraryFormat
import de.ronnyporsch.voidcast.ui.components.CoverArtImage
import de.ronnyporsch.voidcast.ui.components.CollapsingTopBar
import de.ronnyporsch.voidcast.ui.components.collapsingHeader
import de.ronnyporsch.voidcast.ui.components.collapsingHeaderImage
import de.ronnyporsch.voidcast.ui.components.collapsingHeaderTitle
import de.ronnyporsch.voidcast.ui.components.rememberCollapsingHeaderState
import de.ronnyporsch.voidcast.ui.components.SongRow
import de.ronnyporsch.voidcast.ui.components.RememberScrollPosition
import de.ronnyporsch.voidcast.ui.components.VerticalScrollbar
import de.ronnyporsch.voidcast.ui.components.contextMenuSongs
import de.ronnyporsch.voidcast.ui.components.YouTubeDownloadButton
import de.ronnyporsch.voidcast.ui.components.LocalSongDownloads
import de.ronnyporsch.voidcast.ui.components.PlaylistDownloadButton
import de.ronnyporsch.voidcast.ui.components.YouTubeDownloadMessage
import de.ronnyporsch.voidcast.ui.components.TouchSelectionBar
import de.ronnyporsch.voidcast.ui.components.rememberTouchSelection
import de.ronnyporsch.voidcast.ui.components.touchSelection
import de.ronnyporsch.voidcast.data.youtube.DownloadStatus
import de.ronnyporsch.voidcast.ui.theme.AccentColor

@Composable
fun AlbumDetailScreen(
    viewModel: AlbumDetailViewModel,
    onBack: () -> Unit,
    currentSongId: String? = null,
) {
    val state by viewModel.state.collectAsState()
    val coverArt = LocalCoverArtUrlProvider.current
    val listState = rememberLazyListState()
    RememberScrollPosition(listState)
    val headerState = rememberCollapsingHeaderState(listState)
    var query by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        CollapsingTopBar(
            onBack = onBack,
            state = headerState,
            title = state.album?.name.orEmpty(),
            image = { modifier -> CoverArtImage(url = coverArt.getUrl(state.album?.coverArtId), modifier = modifier) },
        )

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentColor)
            }
        } else {
            val album = state.album ?: return
            // The songs matching the search, with their positions in the album, which the events refer to
            val visible = remember(album.songs, query) { album.songs.filtered(query) }
            val visibleSongs = remember(visible) { visible.map { it.value } }
            val songKey = { index: Int, song: Song -> "${song.id}::$index" }
            // The field is the third item, after the header and the play buttons
            val filterFocus = rememberSongFilterFocus(listState, itemIndex = 2)
            val songSelection = rememberTouchSelection(
                items = visibleSongs,
                selectedIds = state.selectedSongIds,
                onChange = { viewModel.onEvent(AlbumDetailEvent.SongsSelected(it)) },
                id = { it.id },
                key = { index, song -> songKey(visible[index].index, song) },
            )
            TouchSelectionBar(songSelection, songs = { songSelection.selectedItems })

            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 100.dp),
                    modifier = Modifier.touchSelection(songSelection, listState).onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            val currentIndex = visibleSongs.indexOfFirst { it.id in state.selectedSongIds }
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (currentIndex < visible.size - 1) {
                                        val (index, song) = visible[currentIndex + 1]
                                        viewModel.onEvent(AlbumDetailEvent.SongClicked(song, index))
                                        true
                                    } else false
                                }

                                Key.DirectionUp -> {
                                    if (currentIndex > 0) {
                                        val (index, song) = visible[currentIndex - 1]
                                        viewModel.onEvent(AlbumDetailEvent.SongClicked(song, index))
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
                                fallback = album.artist,
                                onArtistClick = { name -> viewModel.onEvent(AlbumDetailEvent.ArtistClicked(name)) },
                                style = MaterialTheme.typography.bodyMedium,
                            )

                            val albumInfo = buildString {
                                append(album.releaseType ?: "Album")
                                if (album.year != null) {
                                    append(" \u2022 ")
                                    append(album.year)
                                }
                                if (album.songCount > 0) {
                                    append(" \u2022 ")
                                    append("${album.songCount} song${if (album.songCount != 1) "s" else ""}")
                                }
                                val totalDuration = album.songs.sumOf { it.duration }.takeIf { it > 0 } ?: album.duration
                                if (totalDuration > 0) {
                                    append(" \u2022 ")
                                    append(totalDuration.formatTotalDuration())
                                }
                                val totalSize = album.songs.sumOf { it.size ?: 0L }
                                if (totalSize > 0) {
                                    append(" \u2022 ")
                                    append(totalSize.formatFileSize())
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
                                onClick = { viewModel.onEvent(AlbumDetailEvent.PlayAll) },
                                modifier = Modifier.weight(1f).height(44.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                                shape = RoundedCornerShape(0.dp),
                            ) {
                                Text("\u25B6  Play", fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { viewModel.onEvent(AlbumDetailEvent.ShuffleAll) },
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(0.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                            ) {
                                Text("\u21C6  Shuffle", fontWeight = FontWeight.Bold)
                            }

                            if (state.isYouTube && state.canDownload) {
                                AlbumDownloadButton(
                                    songs = album.songs,
                                    downloads = state.downloads,
                                    onClick = { viewModel.onEvent(AlbumDetailEvent.DownloadAll) },
                                    modifier = Modifier.weight(1f).height(44.dp),
                                )
                            }

                            // Android: keeps the album on the device for offline playback
                            val offlineDownloads = LocalSongDownloads.current
                            if (offlineDownloads != null && !state.isYouTube) {
                                val albumDownloads by offlineDownloads.albumStatus.collectAsState()
                                PlaylistDownloadButton(
                                    playlistName = album.name,
                                    status = albumDownloads[album.id],
                                    onDownload = { offlineDownloads.downloadAlbum(album) },
                                    onRemove = { offlineDownloads.removeAlbum(album.id) },
                                    kind = "album",
                                )
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

                    items(visible, key = { (index, song) -> songKey(index, song) }) { (index, song) ->
                        Column {
                            SongRow(
                                song = song,
                                index = index,
                                onArtistClick = { name -> viewModel.onEvent(AlbumDetailEvent.ArtistClicked(name)) },
                                onSongClick = { songSelection.click(song) { viewModel.onEvent(AlbumDetailEvent.SongClicked(song, index)) } },
                                longPressSelects = songSelection.isEnabled,
                                onSongDoubleClick = { viewModel.onEvent(AlbumDetailEvent.SongDoubleClicked(song, index)) },
                                onSongCtrlClick = { viewModel.onEvent(AlbumDetailEvent.SongSelectionToggled(song)) },
                                onSongShiftClick = { viewModel.onEvent(AlbumDetailEvent.SongRangeSelected(index)) },
                                contextMenuSongs = { visibleSongs.contextMenuSongs(song, state.selectedSongIds) },
                                isSelected = song.id in state.selectedSongIds,
                                isPlaying = currentSongId == song.id,
                                audioFormat = song.libraryFormat(state.libraryFormats),
                                showFavorite = true,
                                trailingContent = if (state.isYouTube && state.canDownload) {
                                    {
                                        YouTubeDownloadButton(song, state.downloads[song.id]) { edited ->
                                            viewModel.onEvent(AlbumDetailEvent.DownloadSong(edited))
                                        }
                                    }
                                } else null,
                            )
                            YouTubeDownloadMessage(state.downloads[song.id])
                        }
                    }
                }

                VerticalScrollbar(Modifier.align(Alignment.CenterEnd).fillMaxHeight(), listState)
            }
        }
    }
}


/** Downloads all songs of a YouTube Music album into the library and shows the overall progress. */
@Composable
private fun AlbumDownloadButton(
    songs: List<Song>,
    downloads: Map<String, DownloadStatus>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statuses = songs.map { downloads[it.id] }
    val done = statuses.count { it is DownloadStatus.Done || it == DownloadStatus.InLibrary }
    val running = statuses.any { it == DownloadStatus.InProgress }
    val failed = statuses.any { it is DownloadStatus.Failed }
    val label = when {
        running -> "$done/${songs.size}"
        songs.isNotEmpty() && done == songs.size -> "\u2713  Downloaded"
        failed -> "Retry download"
        else -> "\u2193  Download"
    }
    OutlinedButton(
        onClick = onClick,
        // Only songs that aren't downloaded or downloading yet get requested, so a retry is safe
        enabled = !running && done < songs.size,
        modifier = modifier,
        shape = RoundedCornerShape(0.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
    ) {
        if (running) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AccentColor, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
