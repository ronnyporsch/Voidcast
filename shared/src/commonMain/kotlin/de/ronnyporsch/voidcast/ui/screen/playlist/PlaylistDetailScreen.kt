package de.ronnyporsch.voidcast.ui.screen.playlist

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
import de.ronnyporsch.voidcast.getPlatform
import de.ronnyporsch.voidcast.ui.LocalCoverArtUrlProvider
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
import de.ronnyporsch.voidcast.ui.components.contextMenuIndices
import de.ronnyporsch.voidcast.ui.components.TouchSelectionBar
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.domain.model.formatFileSize
import de.ronnyporsch.voidcast.domain.model.formatTotalDuration
import de.ronnyporsch.voidcast.ui.components.rememberTouchSelection
import de.ronnyporsch.voidcast.ui.components.touchSelection
import de.ronnyporsch.voidcast.ui.theme.AccentColor

@Composable
fun PlaylistDetailScreen(
    viewModel: PlaylistDetailViewModel,
    onBack: () -> Unit,
    currentSongId: String? = null,
) {
    val state by viewModel.state.collectAsState()
    val isMobile = remember { getPlatform().isMobile }
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
            title = state.playlist?.name.orEmpty(),
            image = { modifier -> CoverArtImage(url = coverArt.getUrl(state.playlist?.coverArtId), modifier = modifier) },
        )

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentColor)
            }
        } else {
            val playlist = state.playlist ?: return
            val songKey = { index: Int, song: Song -> "${song.id}::$index" }
            val songSelection = rememberTouchSelection(
                items = playlist.songs,
                selectedIds = state.selectedSongIds,
                onChange = { viewModel.onEvent(PlaylistDetailEvent.SongsSelected(it)) },
                id = { it.id },
                key = songKey,
            )
            TouchSelectionBar(
                selection = songSelection,
                songs = { songSelection.selectedItems },
                onRemoveFromPlaylist = {
                    viewModel.onEvent(PlaylistDetailEvent.RemoveSongs(playlist.songs.indices.filter { playlist.songs[it].id in state.selectedSongIds }))
                },
            )

            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 100.dp),
                    modifier = Modifier.touchSelection(songSelection, listState).onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            val playlist = state.playlist ?: return@onKeyEvent false
                            val currentIndex = playlist.songs.indexOfFirst { it.id in state.selectedSongIds }
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (currentIndex < playlist.songs.size - 1) {
                                        viewModel.onEvent(PlaylistDetailEvent.SongClicked(playlist.songs[currentIndex + 1], currentIndex + 1))
                                        true
                                    } else false
                                }
                                Key.DirectionUp -> {
                                    if (currentIndex > 0) {
                                        viewModel.onEvent(PlaylistDetailEvent.SongClicked(playlist.songs[currentIndex - 1], currentIndex - 1))
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
                            val totalSize = playlist.songs.sumOf { it.size ?: 0L }
                            Text(
                                text = listOfNotNull(
                                    "${playlist.songCount} songs",
                                    totalDuration.takeIf { it > 0 }?.formatTotalDuration(),
                                    totalSize.takeIf { it > 0 }?.formatFileSize(),
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
                                onClick = { viewModel.onEvent(PlaylistDetailEvent.PlayAll) },
                                modifier = Modifier.weight(1f).height(44.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                                shape = RoundedCornerShape(0.dp),
                            ) {
                                Text("\u25B6  Play", fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { viewModel.onEvent(PlaylistDetailEvent.ShuffleAll) },
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(0.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                            ) {
                                Text("\u21C6  Shuffle", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    itemsIndexed(playlist.songs, key = songKey) { index, song ->
                        SongRow(
                            song = song,
                            index = index,
                            // Taps on mobile play the song; artist links would be too easy to hit by accident
                            onArtistClick = if (isMobile) null else { name -> viewModel.onEvent(PlaylistDetailEvent.ArtistClicked(name)) },
                            onAlbumClick = { albumId -> viewModel.onEvent(PlaylistDetailEvent.AlbumClicked(albumId)) },
                            onSongClick = { songSelection.click(song) { viewModel.onEvent(PlaylistDetailEvent.SongClicked(song, index)) } },
                            longPressSelects = songSelection.isEnabled,
                            onSongDoubleClick = { viewModel.onEvent(PlaylistDetailEvent.SongDoubleClicked(song, index)) },
                            onSongCtrlClick = { viewModel.onEvent(PlaylistDetailEvent.SongSelectionToggled(song)) },
                            onSongShiftClick = { viewModel.onEvent(PlaylistDetailEvent.SongRangeSelected(index)) },
                            contextMenuSongs = { playlist.songs.contextMenuSongs(song, state.selectedSongIds) },
                            onRemoveFromPlaylist = {
                                viewModel.onEvent(PlaylistDetailEvent.RemoveSongs(playlist.songs.contextMenuIndices(index, state.selectedSongIds)))
                            },
                            isSelected = song.id in state.selectedSongIds,
                            isPlaying = currentSongId == song.id,
                        )
                    }
                }

                VerticalScrollbar(Modifier.align(Alignment.CenterEnd).fillMaxHeight(), listState)
            }
        }
    }
}

