package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.data.offline.PlaylistDownloadStatus
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SpotifyLightGray

/**
 * Shows whether a playlist (or album, see [kind]) is downloaded ([status] null: it isn't). Clicking it starts the download, or asks whether
 * to remove the downloaded files.
 */
@Composable
fun PlaylistDownloadButton(
    playlistName: String,
    status: PlaylistDownloadStatus?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    /** What is downloaded, for the texts. */
    kind: String = "playlist",
) {
    var showDialog by remember { mutableStateOf(false) }

    when {
        status == null -> TooltipIconButton(
            onClick = onDownload,
            icon = Icons.Default.Download,
            contentDescription = "Download for offline playback",
            tint = SpotifyLightGray,
            modifier = modifier,
        )
        status.isRunning -> IconButton(onClick = { showDialog = true }, modifier = modifier) {
            Box(contentAlignment = Alignment.Center) {
                val progress = if (status.totalSongs > 0) status.downloadedSongs.toFloat() / status.totalSongs else 0f
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(22.dp),
                    color = AccentColor,
                    strokeWidth = 2.dp,
                )
            }
        }
        status.isComplete -> TooltipIconButton(
            onClick = { showDialog = true },
            icon = Icons.Default.DownloadDone,
            contentDescription = "Downloaded",
            tint = AccentColor,
            modifier = modifier,
        )
        // Some songs failed, e.g. because the connection dropped
        else -> TooltipIconButton(
            onClick = { showDialog = true },
            icon = Icons.Default.Download,
            contentDescription = "${status.downloadedSongs} of ${status.totalSongs} songs downloaded",
            tint = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
    }

    if (showDialog && status != null) {
        val incomplete = !status.isRunning && !status.isComplete
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(if (status.isRunning) "Cancel download?" else "Remove download?") },
            text = {
                Text(
                    buildString {
                        if (status.isRunning || incomplete) {
                            append("${status.downloadedSongs} of ${status.totalSongs} songs of \"$playlistName\" are downloaded. ")
                        }
                        append("The downloaded songs of \"$playlistName\" will be deleted from this device. ")
                        append("The $kind stays on the server.")
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDialog = false
                        onRemove()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                if (incomplete) {
                    TextButton(onClick = {
                        showDialog = false
                        onDownload()
                    }) {
                        Text("Retry", color = AccentColor)
                    }
                }
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            },
        )
    }
}
