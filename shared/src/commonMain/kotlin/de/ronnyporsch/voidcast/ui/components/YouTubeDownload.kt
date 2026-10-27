package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.data.youtube.DownloadStatus
import de.ronnyporsch.voidcast.domain.model.Artist
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SpotifyLightGray

/**
 * Downloads a YouTube Music song into the server's library and shows the progress. Asks for the title and artists
 * to tag it with first, prefilled with YouTube Music's, and passes [song] with them to [onDownload].
 */
@Composable
fun YouTubeDownloadButton(song: Song, status: DownloadStatus?, onDownload: (Song) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    when (status) {
        DownloadStatus.InProgress -> Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentColor, strokeWidth = 2.dp)
        }
        is DownloadStatus.Done, DownloadStatus.InLibrary -> TooltipIconButton(
            onClick = {},
            icon = Icons.Default.CheckCircle,
            contentDescription = if (status is DownloadStatus.Done) "Added to library" else "Already in library",
            tint = AccentColor,
        )
        is DownloadStatus.Failed -> TooltipIconButton(
            onClick = { showDialog = true },
            icon = Icons.Default.ErrorOutline,
            contentDescription = "Download failed, click to retry",
            tint = MaterialTheme.colorScheme.error,
        )
        null -> TooltipIconButton(
            onClick = { showDialog = true },
            icon = Icons.Default.Download,
            contentDescription = "Download to library",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showDialog) {
        YouTubeDownloadDialog(
            song = song,
            onDownload = {
                showDialog = false
                onDownload(it)
            },
            onDismiss = { showDialog = false },
        )
    }
}

/** Lets the user correct the tags of [song] before it gets downloaded. */
@Composable
private fun YouTubeDownloadDialog(song: Song, onDownload: (Song) -> Unit, onDismiss: () -> Unit) {
    var title by remember(song) { mutableStateOf(song.title) }
    var artist by remember(song) { mutableStateOf(song.artist) }
    // One per line, as names may contain commas ("Tyler, The Creator")
    var artists by remember(song) { mutableStateOf(song.artists.joinToString("\n") { it.name }) }
    val download = {
        if (title.isNotBlank()) {
            val names = artists.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            onDownload(
                song.copy(
                    title = title.trim(),
                    artist = artist.trim(),
                    // Keep the artist pages of unchanged artists
                    artists = names.map { name -> song.artists.firstOrNull { it.name == name } ?: Artist(id = "", name = name) },
                )
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download to library") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    isError = title.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist") },
                    supportingText = { Text("The credit as displayed, e.g. \"Daft Punk feat. Pharrell Williams\"") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = artists,
                    onValueChange = { artists = it },
                    label = { Text("Artists") },
                    supportingText = { Text("Each artist on its own line") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = download,
                enabled = title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
            ) {
                Text("Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Where a finished download went or why it failed; shown below the song's row. */
@Composable
fun YouTubeDownloadMessage(status: DownloadStatus?) {
    val message = when (status) {
        is DownloadStatus.Done -> status.message
        is DownloadStatus.Failed -> status.message
        else -> return
    }
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = if (status is DownloadStatus.Failed) MaterialTheme.colorScheme.error else SpotifyLightGray,
        modifier = Modifier.padding(start = 68.dp, end = 16.dp, bottom = 8.dp),
    )
}
