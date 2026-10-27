package de.ronnyporsch.voidcast.ui.screen.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ronnyporsch.voidcast.domain.model.Album
import de.ronnyporsch.voidcast.domain.model.Artist
import de.ronnyporsch.voidcast.domain.model.Playlist
import de.ronnyporsch.voidcast.domain.model.youTubeArtistId
import de.ronnyporsch.voidcast.ui.LocalCoverArtUrlProvider
import de.ronnyporsch.voidcast.ui.components.ArtistImage
import de.ronnyporsch.voidcast.ui.components.ClickableArtistText
import de.ronnyporsch.voidcast.ui.components.CoverArtImage
import de.ronnyporsch.voidcast.ui.components.PluginMissingWarning
import de.ronnyporsch.voidcast.ui.components.SelectableAlbum
import de.ronnyporsch.voidcast.ui.components.SongRow
import de.ronnyporsch.voidcast.ui.components.rememberAlbumSelection
import de.ronnyporsch.voidcast.ui.components.RememberScrollPosition
import de.ronnyporsch.voidcast.ui.components.VerticalScrollbar
import de.ronnyporsch.voidcast.ui.components.contextMenuSongs
import de.ronnyporsch.voidcast.ui.components.AlbumTouchSelectionBar
import de.ronnyporsch.voidcast.ui.components.TouchSelectionBar
import de.ronnyporsch.voidcast.ui.components.rememberAlbumTouchSelection
import de.ronnyporsch.voidcast.ui.components.rememberTouchSelection
import de.ronnyporsch.voidcast.ui.components.touchSelection
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.ui.components.YouTubeDownloadButton
import de.ronnyporsch.voidcast.ui.components.YouTubeDownloadMessage
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SpotifyLightGray

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    currentSongId: String? = null,
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    RememberScrollPosition(listState)
    val albumSelection = rememberAlbumSelection()
    // Mobile: albums and songs are selected by touch, one kind at a time
    val albumTouchSelection = rememberAlbumTouchSelection(state.albums, albumSelection, key = { _, album -> "album-${album.id}" }) {
        if (it.isNotEmpty() && state.selectedSongIds.isNotEmpty()) viewModel.onEvent(SearchEvent.SongsSelected(emptySet()))
    }
    val songKey = { index: Int, song: Song -> "song-${song.id}::$index" }
    val songTouchSelection = rememberTouchSelection(
        items = state.songs,
        selectedIds = state.selectedSongIds,
        onChange = {
            viewModel.onEvent(SearchEvent.SongsSelected(it))
            if (it.isNotEmpty()) albumSelection.clear()
        },
        id = { it.id },
        key = songKey,
    )
    AlbumTouchSelectionBar(albumTouchSelection)
    TouchSelectionBar(songTouchSelection, songs = { songTouchSelection.selectedItems })
    val focusManager = LocalFocusManager.current
    // Clicking a result unfocuses the search bar, which hides the soft keyboard on mobile
    val onResultClick: (SearchEvent) -> Unit = { event ->
        focusManager.clearFocus()
        viewModel.onEvent(event)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentColor)
            }
        } else if (!state.hasSearched) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (state.youTubeAvailable) "Search your music library and YouTube Music" else "Search your music library", style = MaterialTheme.typography.bodyMedium, color = SpotifyLightGray)
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 100.dp),
                    modifier = Modifier
                        .touchSelection(albumTouchSelection, listState)
                        .touchSelection(songTouchSelection, listState)
                        .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            // Library songs and YouTube Music songs form one navigable list
                            val allSongs = state.songs + state.youTubeSongs
                            val currentIndex = allSongs.indexOfFirst { it.id in state.selectedSongIds }
                            val targetIndex = when (event.key) {
                                Key.DirectionDown -> currentIndex + 1
                                Key.DirectionUp -> currentIndex - 1
                                else -> return@onKeyEvent false
                            }
                            if (currentIndex >= 0 && targetIndex in allSongs.indices) {
                                val song = allSongs[targetIndex]
                                viewModel.onEvent(
                                    if (targetIndex < state.songs.size) {
                                        SearchEvent.SongClicked(song, targetIndex)
                                    } else {
                                        SearchEvent.YouTubeSongClicked(song, targetIndex - state.songs.size)
                                    }
                                )
                                true
                            } else false
                        } else false
                    }
                ) {
                    if (state.youTubePluginMissing) {
                        item(key = "plugin-missing") {
                            PluginMissingWarning(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }

                    if (state.artists.isNotEmpty()) {
                        item {
                            Text("Artists", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                        items(state.artists, key = { "artist-${it.id}" }) { artist ->
                            SearchArtistRow(artist) {
                                onResultClick(SearchEvent.ArtistClicked(artist))
                            }
                        }
                    }

                    if (state.playlists.isNotEmpty()) {
                        item {
                            Text("Playlists", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                        items(state.playlists, key = { "playlist-${it.id}" }) { playlist ->
                            SearchPlaylistRow(playlist) {
                                onResultClick(SearchEvent.PlaylistClicked(playlist))
                            }
                        }
                    }

                    if (state.albums.isNotEmpty()) {
                        item {
                            Text("Albums", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                        items(state.albums, key = { "album-${it.id}" }) { album ->
                            SelectableAlbum(
                                album = album,
                                albums = state.albums,
                                selection = albumSelection,
                                onClick = { onResultClick(SearchEvent.AlbumClicked(album)) },
                                touchSelection = albumTouchSelection,
                            ) {
                                SearchAlbumRow(
                                    album = album,
                                    onArtistClick = { name -> onResultClick(SearchEvent.ArtistNameClicked(name)) },
                                )
                            }
                        }
                    }

                    if (state.songs.isNotEmpty()) {
                        item {
                            Text("Songs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                        itemsIndexed(state.songs, key = songKey) { index, song ->
                            SongRow(
                                song = song,
                                onArtistClick = { name -> onResultClick(SearchEvent.ArtistNameClicked(name)) },
                                onSongClick = { songTouchSelection.click(song) { onResultClick(SearchEvent.SongClicked(song, index)) } },
                                longPressSelects = songTouchSelection.isEnabled,
                                onSongDoubleClick = { onResultClick(SearchEvent.SongDoubleClicked(song, index)) },
                                onSongCtrlClick = { viewModel.onEvent(SearchEvent.SongSelectionToggled(song)) },
                                onSongShiftClick = { viewModel.onEvent(SearchEvent.SongRangeSelected(index)) },
                                contextMenuSongs = { state.songs.contextMenuSongs(song, state.selectedSongIds) },
                                isSelected = song.id in state.selectedSongIds,
                                isPlaying = currentSongId == song.id,
                                showAlbum = true
                            )
                        }
                    }

                    if (state.youTubeAvailable) {
                        item {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("YouTube Music", style = MaterialTheme.typography.titleLarge)
                                if (state.isYouTubeLoading) {
                                    Spacer(Modifier.width(12.dp))
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = AccentColor,
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        }
                        state.youTubeError?.let { error ->
                            item {
                                Text(
                                    error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        }
                        if (!state.isYouTubeLoading && state.youTubeError == null && state.youTubeArtists.isEmpty() && state.youTubeSongs.isEmpty()) {
                            item {
                                Text(
                                    "No results on YouTube Music",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SpotifyLightGray,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        }
                        if (state.youTubeArtists.isNotEmpty()) {
                            item { SearchSubheader("Artists") }
                        }
                        items(state.youTubeArtists, key = { "yt-artist-${it.id}" }) { artist ->
                            SearchArtistRow(artist, subtitle = "Artist \u2022 YouTube Music") {
                                onResultClick(SearchEvent.ArtistClicked(artist))
                            }
                        }
                        if (state.youTubeSongs.isNotEmpty()) {
                            item { SearchSubheader(if (state.youTubeSongs.any { it.isVideo }) "Songs & videos" else "Songs") }
                        }
                        itemsIndexed(state.youTubeSongs, key = { index, song -> "yt-${song.id}::$index" }) { index, song ->
                            val download = state.downloads[song.id]
                            Column {
                                SongRow(
                                    song = song,
                                    onArtistClick = { name ->
                                        onResultClick(SearchEvent.ArtistNameClicked(name, song.youTubeArtistId(name)))
                                    },
                                    onSongClick = { onResultClick(SearchEvent.YouTubeSongClicked(song, index)) },
                                    onSongDoubleClick = { onResultClick(SearchEvent.YouTubeSongDoubleClicked(song, index)) },
                                    isSelected = song.id in state.selectedSongIds,
                                    isPlaying = currentSongId == song.id,
                                    showAlbum = true,
                                    audioFormat = state.youTubeFormats[song.id],
                                    typeLabel = if (song.isVideo) "Video" else "Song",
                                    trailingContent = if (state.youTubeDownloadAvailable) {
                                        { YouTubeDownloadButton(song, download) { viewModel.onEvent(SearchEvent.DownloadYouTubeSong(it)) } }
                                    } else null,
                                )
                                YouTubeDownloadMessage(download)
                            }
                        }
                    }

                    if (state.artists.isEmpty() && state.playlists.isEmpty() && state.albums.isEmpty() && state.songs.isEmpty() && !state.youTubeAvailable) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text("No results found", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                VerticalScrollbar(Modifier.align(Alignment.CenterEnd).fillMaxHeight(), listState)
            }
        }
    }
}

@Composable
private fun SearchSubheader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SearchArtistRow(artist: Artist, subtitle: String = "Artist", onClick: () -> Unit) {
    val coverArt = LocalCoverArtUrlProvider.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtistImage(
            url = coverArt.getUrl(artist.coverArtId),
            artistName = artist.name,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(artist.name, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SearchPlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    val coverArt = LocalCoverArtUrlProvider.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArtImage(
            url = coverArt.getUrl(playlist.coverArtId),
            modifier = Modifier.size(48.dp),
            cornerRadius = 0.dp,
            placeholderFontSize = 18.sp,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Playlist \u2022 ${playlist.songCount} songs", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SearchAlbumRow(album: Album, onArtistClick: (String) -> Unit) {
    val coverArt = LocalCoverArtUrlProvider.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArtImage(
            url = coverArt.getUrl(album.coverArtId),
            modifier = Modifier.size(48.dp),
            cornerRadius = 0.dp,
            placeholderFontSize = 18.sp,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(album.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ClickableArtistText(
                artists = album.artists,
                fallback = album.artist,
                onArtistClick = onArtistClick,
                prefix = "Album \u2022 ",
            )
        }
    }
}

