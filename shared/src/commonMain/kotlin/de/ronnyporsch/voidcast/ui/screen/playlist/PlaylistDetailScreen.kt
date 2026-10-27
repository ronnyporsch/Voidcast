package de.ronnyporsch.voidcast.ui.screen.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import de.ronnyporsch.voidcast.ui.components.SongFilterField
import de.ronnyporsch.voidcast.ui.components.filtered
import de.ronnyporsch.voidcast.ui.components.rememberSongFilterFocus
import de.ronnyporsch.voidcast.ui.components.selectedThrough
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
    var query by rememberSaveable { mutableStateOf("") }

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
            // The songs matching the search, with their positions in the playlist, which the events refer to
            val visible = remember(playlist.songs, query) { playlist.songs.filtered(query) }
            val visibleSongs = remember(visible) { visible.map { it.value } }
            val songKey = { index: Int, song: Song -> "${song.id}::$index" }
            // The field is the third item, after the header and the play buttons
            val filterFocus = rememberSongFilterFocus(listState, itemIndex = 2)
            val songSelection = rememberTouchSelection(
                items = visibleSongs,
                selectedIds = state.selectedSongIds,
                onChange = { viewModel.onEvent(PlaylistDetailEvent.SongsSelected(it)) },
                id = { it.id },
                key = { index, song -> songKey(visible[index].index, song) },
            )
            TouchSelectionBar(
                selection = songSelection,
                songs = { songSelection.selectedItems },
                onRemoveFromPlaylist = {
                    viewModel.onEvent(PlaylistDetailEvent.RemoveSongs(visible.filter { it.value.id in state.selectedSongIds }.map { it.index }))
                },
            )

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
                                        viewModel.onEvent(PlaylistDetailEvent.SongClicked(song, index))
                                        true
                                    } else false
                                }
                                Key.DirectionUp -> {
                                    if (currentIndex > 0) {
                                        val (index, song) = visible[currentIndex - 1]
                                        viewModel.onEvent(PlaylistDetailEvent.SongClicked(song, index))
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

                    itemsIndexed(visible, key = { _, (index, song) -> songKey(index, song) }) { visibleIndex, (index, song) ->
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
                            // Only through the songs shown, as hidden ones would be acted on unnoticed
                            onSongShiftClick = {
                                viewModel.onEvent(PlaylistDetailEvent.SongsSelected(visibleSongs.selectedThrough(visibleIndex, state.selectedSongIds)))
                            },
                            contextMenuSongs = { visibleSongs.contextMenuSongs(song, state.selectedSongIds) },
                            onRemoveFromPlaylist = {
                                val indices = visibleSongs.contextMenuIndices(visibleIndex, state.selectedSongIds).map { visible[it].index }
                                viewModel.onEvent(PlaylistDetailEvent.RemoveSongs(indices))
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

