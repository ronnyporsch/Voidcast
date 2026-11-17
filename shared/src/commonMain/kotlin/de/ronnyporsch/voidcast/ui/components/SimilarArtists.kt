package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.domain.model.Artist
import de.ronnyporsch.voidcast.domain.model.isYouTubeId
import de.ronnyporsch.voidcast.ui.LocalCoverArtUrlProvider
import de.ronnyporsch.voidcast.ui.theme.AccentColor

/**
 * The "Similar artists" section of an artist overview: as many as fit in one row, sized like the album cards above,
 * and the rest after "Show all". Shows nothing without [artists].
 */
@Composable
fun SimilarArtists(artists: List<Artist>, onArtistClick: (Artist) -> Unit, modifier: Modifier = Modifier) {
    if (artists.isEmpty()) return
    var expanded by rememberSaveable(artists) { mutableStateOf(false) }
    val spacing = 12.dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = ((maxWidth + spacing) / (160.dp + spacing)).toInt().coerceAtLeast(1)
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Similar artists",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (artists.size > columns) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Show less" else "Show all", color = AccentColor)
                    }
                }
            }
            val shown = if (expanded) artists else artists.take(columns)
            for (row in shown.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    for (artist in row) {
                        SimilarArtistCard(artist, onClick = { onArtistClick(artist) }, modifier = Modifier.weight(1f))
                    }
                    // Keeps the cards of a partial last row as wide as the others
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SimilarArtistCard(artist: Artist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val coverArt = LocalCoverArtUrlProvider.current
    Column(
        modifier = modifier.clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtistImage(
            url = coverArt.getUrl(artist.coverArtId),
            artistName = artist.name,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = artist.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (artist.id.isYouTubeId) YouTubeIcon(Modifier.padding(start = 4.dp), size = 16.dp)
        }
    }
}
