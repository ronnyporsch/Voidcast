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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Releases from this and last year of the artists the server's scans look at, which aren't in the library yet. */
@Composable
fun NewReleasesScreen(
    viewModel: NewReleasesViewModel,
    /** Whether the user may start a scan, which the server only lets administrators do. */
    canScan: Boolean,
    onAlbumClick: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val gridState = rememberLazyGridState()
    RememberScrollPosition(gridState)
    val albumSelection = rememberAlbumSelection()
    val releases = state.scan?.releases.orEmpty()
    val albumTouchSelection = rememberAlbumTouchSelection(releases, albumSelection)
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
            item(span = { GridItemSpan(maxLineSpan) }) { Header(state, canScan, onScan = { viewModel.onEvent(NewReleasesEvent.Scan) }) }

            for ((year, releasesOfYear) in releases.groupBy { it.year }) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = year.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                items(releasesOfYear, key = { it.id }) { album ->
                    SelectableAlbum(
                        album = album,
                        albums = releases,
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

        if (state.isLoading) {
            CircularProgressIndicator(color = AccentColor, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun Header(state: NewReleasesState, canScan: Boolean, onScan: () -> Unit) {
    val scan = state.scan
    val songsText = scan?.let { "${it.minSongs} ${if (it.minSongs == 1) "song" else "songs"}" }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("New releases", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (scan != null) {
            val artists = scan.artistCount
            Text(
                text = if (scan.favoritesOnly) "From your favorite ${if (artists == 1) "artist" else "$artists artists"}"
                else "From the $artists ${if (artists == 1) "artist" else "artists"} with at least $songsText in the library",
                style = MaterialTheme.typography.bodyMedium,
                color = SpotifyLightGray,
            )
            Text("Last scan: ${daysAgo(scan.scannedAt)}", style = MaterialTheme.typography.bodySmall, color = SpotifyLightGray)
        }
        if (canScan) {
            OutlinedButton(onClick = onScan, enabled = !state.isScanning, shape = RoundedCornerShape(0.dp)) {
                Text(if (state.isScanning) "Scanning…" else "Scan now", fontWeight = FontWeight.Bold)
            }
        }
        if (state.isScanning) {
            val total = state.artistsToCheck
            if (total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { state.checkedArtists.toFloat() / total },
                    color = AccentColor,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Checked ${state.checkedArtists} of $total artists", style = MaterialTheme.typography.bodySmall, color = SpotifyLightGray)
            } else {
                LinearProgressIndicator(color = AccentColor, modifier = Modifier.fillMaxWidth())
            }
        }
        if (scan != null && scan.failedArtists > 0 && !state.isScanning) {
            Text("Couldn't check ${scan.failedArtists} of ${scan.artistCount} artists", style = MaterialTheme.typography.bodySmall, color = SpotifyLightGray)
        }
        state.error?.let { Text("Couldn't look for new releases: $it", style = MaterialTheme.typography.bodyMedium) }
        if (!state.isScanning && !state.isLoading && state.error == null) {
            when {
                scan == null -> "The server scans once a week and hasn't finished a scan yet"
                scan.releases.isNotEmpty() -> null
                scan.artistCount != 0 -> "Nothing new right now"
                scan.favoritesOnly -> "You have no favorite artists yet"
                else -> "No artist has at least $songsText in the library yet"
            }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@OptIn(ExperimentalTime::class)
private fun daysAgo(epochMillis: Long): String =
    when (val days = (Clock.System.now().toEpochMilliseconds() - epochMillis) / (24 * 60 * 60 * 1000L)) {
        0L -> "today"
        1L -> "yesterday"
        else -> "$days days ago"
    }
