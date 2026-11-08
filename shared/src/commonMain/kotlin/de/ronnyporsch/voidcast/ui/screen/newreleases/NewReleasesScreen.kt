package de.ronnyporsch.voidcast.ui.screen.newreleases

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.ui.components.AlbumTouchSelectionBar
import de.ronnyporsch.voidcast.ui.components.RememberScrollPosition
import de.ronnyporsch.voidcast.ui.components.SelectableAlbum
import de.ronnyporsch.voidcast.ui.components.VerticalScrollbar
import de.ronnyporsch.voidcast.ui.components.rememberAlbumSelection
import de.ronnyporsch.voidcast.ui.components.rememberAlbumTouchSelection
import de.ronnyporsch.voidcast.ui.components.touchSelection
import de.ronnyporsch.voidcast.ui.screen.library.AlbumCard
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SpotifyLightGray

/** Releases from this and last year of the artists the library has many songs of, which aren't in the library yet. */
@Composable
fun NewReleasesScreen(
    viewModel: NewReleasesViewModel,
    onAlbumClick: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val gridState = rememberLazyGridState()
    RememberScrollPosition(gridState)
    val albumSelection = rememberAlbumSelection()
    val albumTouchSelection = rememberAlbumTouchSelection(state.releases, albumSelection)
    AlbumTouchSelectionBar(albumTouchSelection)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().touchSelection(albumTouchSelection, gridState),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { Header(state) }

            for ((year, releases) in state.releases.groupBy { it.year }) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = year.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                items(releases, key = { it.id }) { album ->
                    SelectableAlbum(
                        album = album,
                        albums = state.releases,
                        selection = albumSelection,
                        onClick = { onAlbumClick(album.id) },
                        touchSelection = albumTouchSelection,
                    ) {
                        AlbumCard(album, subtitle = listOfNotNull(album.artist, album.releaseType).joinToString(" • "))
                    }
                }
            }
        }

        VerticalScrollbar(Modifier.align(Alignment.CenterEnd).fillMaxHeight(), gridState)

        if (state.artistCount == null && state.error == null) {
            CircularProgressIndicator(color = AccentColor, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun Header(state: NewReleasesState) {
    val minSongs = state.criteria.minSongs
    val songsText = "$minSongs ${if (minSongs == 1) "song" else "songs"}"
    val favoritesOnly = state.criteria.favoriteArtists != null
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("New releases", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        val artists = state.artistCount
        if (artists != null) {
            Text(
                text = if (favoritesOnly) "From your favorite ${if (artists == 1) "artist" else "$artists artists"}"
                else "From the $artists ${if (artists == 1) "artist" else "artists"} with at least $songsText in your library",
                style = MaterialTheme.typography.bodyMedium,
                color = SpotifyLightGray,
            )
        }
        if (state.isScanning && !artists.isNullOrZero()) {
            LinearProgressIndicator(
                progress = { state.checkedArtists.toFloat() / artists!! },
                color = AccentColor,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Checked ${state.checkedArtists} of $artists artists", style = MaterialTheme.typography.bodySmall, color = SpotifyLightGray)
        }
        if (state.failedArtists > 0 && !state.isScanning) {
            Text("Couldn't check ${state.failedArtists} of $artists artists", style = MaterialTheme.typography.bodySmall, color = SpotifyLightGray)
        }
        state.error?.let { Text("Couldn't look for new releases: $it", style = MaterialTheme.typography.bodyMedium) }
        if (!state.isScanning && state.error == null && state.scannedAt != null && state.releases.isEmpty()) {
            Text(
                text = when {
                    artists != 0 -> "Nothing new right now"
                    favoritesOnly -> "You have no favorite artists yet"
                    else -> "No artist has at least $songsText in your library yet"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun Int?.isNullOrZero() = this == null || this == 0
