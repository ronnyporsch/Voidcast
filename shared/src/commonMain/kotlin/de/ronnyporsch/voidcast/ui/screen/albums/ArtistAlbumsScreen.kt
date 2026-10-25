package de.ronnyporsch.voidcast.ui.screen.albums

import de.ronnyporsch.voidcast.ui.components.FavoriteButton
import de.ronnyporsch.voidcast.domain.model.FavoriteKind
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ronnyporsch.voidcast.domain.model.Album
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
import de.ronnyporsch.voidcast.ui.components.SongRow
import de.ronnyporsch.voidcast.ui.components.rememberAlbumSelection
import de.ronnyporsch.voidcast.ui.components.RememberScrollPosition
import de.ronnyporsch.voidcast.ui.components.VerticalScrollbar
import de.ronnyporsch.voidcast.ui.components.libraryFormat
import de.ronnyporsch.voidcast.ui.components.YouTubeDownloadButton
import de.ronnyporsch.voidcast.ui.components.YouTubeDownloadMessage
import de.ronnyporsch.voidcast.ui.components.contextMenuSongs
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
    // Only YouTube Music knows release types; library albums all go under "Albums"
    val (albums, singles) = state.albums.partition { it.releaseType == null || it.releaseType == "Album" }
    // In the order they are shown, for selecting ranges of them
    val shownAlbums = albums + singles + state.appearsOnAlbums
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
                    modifier = Modifier.onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            val currentIndex = state.songs.indexOfFirst { it.id in state.selectedSongIds }
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (currentIndex < state.songs.size - 1) {
                                        viewModel.onEvent(ArtistAlbumsEvent.SongClicked(state.songs[currentIndex + 1], currentIndex + 1))
                                        true
                                    } else false
                                }

                                Key.DirectionUp -> {
                                    if (currentIndex > 0) {
                                        viewModel.onEvent(ArtistAlbumsEvent.SongClicked(state.songs[currentIndex - 1], currentIndex - 1))
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
                            headerState = headerState,
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
                            ) {
                                AlbumCard(album, downloaded = album.id in state.downloadedAlbumIds)
                            }
                        }
                    }

                    if (state.appearsOnAlbums.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = "Appears on",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                            )
                        }

                        items(state.appearsOnAlbums, key = { it.id }) { album ->
                            SelectableAlbum(
                                album = album,
                                albums = shownAlbums,
                                selection = albumSelection,
                                onClick = { viewModel.onEvent(ArtistAlbumsEvent.AlbumClicked(album)) },
                            ) {
                                AlbumCard(album, downloaded = album.id in state.downloadedAlbumIds)
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

                        itemsIndexed(
                            state.songs,
                            key = { index, song -> "${song.id}::$index" },
                            span = { _, _ -> GridItemSpan(maxLineSpan) },
                        ) { index, song ->
                            Column {
                                SongRow(
                                    song = song,
                                    index = index,
                                    onSongClick = { viewModel.onEvent(ArtistAlbumsEvent.SongClicked(song, index)) },
                                    onSongDoubleClick = { viewModel.onEvent(ArtistAlbumsEvent.SongDoubleClicked(song, index)) },
                                    onSongCtrlClick = { viewModel.onEvent(ArtistAlbumsEvent.SongSelectionToggled(song)) },
                                    onSongShiftClick = { viewModel.onEvent(ArtistAlbumsEvent.SongRangeSelected(index)) },
                                    contextMenuSongs = { state.songs.contextMenuSongs(song, state.selectedSongIds) },
                                    onArtistClick = { /* Already in artist view */ },
                                    isSelected = song.id in state.selectedSongIds,
                                    isPlaying = currentSongId == song.id,
                                    showAlbum = true,
                                    showArtist = false,
                                    audioFormat = song.libraryFormat(state.libraryFormats),
                                    showFavorite = true,
                                    trailingContent = if (state.isYouTube && state.canDownload) {
                                        {
                                            YouTubeDownloadButton(state.downloads[song.id]) {
                                                viewModel.onEvent(ArtistAlbumsEvent.DownloadSong(song))
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
            FavoriteButton(FavoriteKind.Album, album.id, Modifier.size(28.dp), iconSize = 18.dp)
        }

        Text(
            text = listOfNotNull(album.releaseType, album.year).joinToString(" \u2022 "),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

