package de.ronnyporsch.voidcast.ui.screen.albums

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
import de.ronnyporsch.voidcast.ui.LocalCoverArtUrlProvider
import de.ronnyporsch.voidcast.ui.components.ClickableArtistText
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
import de.ronnyporsch.voidcast.ui.components.YouTubeDownloadMessage
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

            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 100.dp),
                    modifier = Modifier.onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            val album = state.album ?: return@onKeyEvent false
                            val currentIndex = album.songs.indexOfFirst { it.id in state.selectedSongIds }
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (currentIndex < album.songs.size - 1) {
                                        viewModel.onEvent(AlbumDetailEvent.SongClicked(album.songs[currentIndex + 1], currentIndex + 1))
                                        true
                                    } else false
                                }

                                Key.DirectionUp -> {
                                    if (currentIndex > 0) {
                                        viewModel.onEvent(AlbumDetailEvent.SongClicked(album.songs[currentIndex - 1], currentIndex - 1))
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

                            FavoriteButton(FavoriteKind.Album, album.id)
                        }
                    }

                    itemsIndexed(album.songs, key = { index, song -> "${song.id}::$index" }) { index, song ->
                        Column {
                            SongRow(
                                song = song,
                                index = index,
                                onArtistClick = { name -> viewModel.onEvent(AlbumDetailEvent.ArtistClicked(name)) },
                                onSongClick = { viewModel.onEvent(AlbumDetailEvent.SongClicked(song, index)) },
                                onSongDoubleClick = { viewModel.onEvent(AlbumDetailEvent.SongDoubleClicked(song, index)) },
                                onSongCtrlClick = { viewModel.onEvent(AlbumDetailEvent.SongSelectionToggled(song)) },
                                onSongShiftClick = { viewModel.onEvent(AlbumDetailEvent.SongRangeSelected(index)) },
                                contextMenuSongs = { album.songs.contextMenuSongs(song, state.selectedSongIds) },
                                isSelected = song.id in state.selectedSongIds,
                                isPlaying = currentSongId == song.id,
                                audioFormat = song.libraryFormat(state.libraryFormats),
                                showFavorite = true,
                                trailingContent = if (state.isYouTube && state.canDownload) {
                                    {
                                        YouTubeDownloadButton(state.downloads[song.id]) {
                                            viewModel.onEvent(AlbumDetailEvent.DownloadSong(song))
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
