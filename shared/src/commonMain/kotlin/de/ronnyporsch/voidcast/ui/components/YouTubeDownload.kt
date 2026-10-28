package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.data.youtube.DownloadStatus
import de.ronnyporsch.voidcast.data.youtube.YouTubeDownloadRequest
import de.ronnyporsch.voidcast.domain.model.Artist
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SpotifyLightGray

/**
 * Downloads a YouTube Music song into the server's library and shows the progress. Asks for the tags and the part to
 * download first, prefilled with YouTube Music's, see [YouTubeDownloadDialog], and passes them to [onDownload].
 */
@Composable
fun YouTubeDownloadButton(song: Song, status: DownloadStatus?, onDownload: (YouTubeDownloadRequest) -> Unit) {
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

/**
 * Lets the user correct the tags of [song] (title, artists, album, genre) and cut off an intro or outro before it gets
 * downloaded. [progress] tells e.g. which of several songs this is; [onKeepRest] offers to download the remaining ones
 * of them with YouTube Music's tags.
 */
@Composable
fun YouTubeDownloadDialog(
    song: Song,
    onDownload: (YouTubeDownloadRequest) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Download to library",
    confirmLabel: String = "Download",
    progress: String? = null,
    onKeepRest: (() -> Unit)? = null,
) {
    var songTitle by remember(song) { mutableStateOf(song.title) }
    var artist by remember(song) { mutableStateOf(song.artist) }
    // One per line, as names may contain commas ("Tyler, The Creator")
    var artists by remember(song) { mutableStateOf(song.artists.joinToString("\n") { it.name }) }
    var album by remember(song) { mutableStateOf(song.album) }
    var genre by remember(song) { mutableStateOf(song.genre.orEmpty()) }
    var start by remember(song) { mutableStateOf(formatCutTime(0)) }
    var end by remember(song) { mutableStateOf(if (song.duration > 0) formatCutTime(song.duration) else "") }
    val startSeconds = parseCutTime(start)
    // Empty: up to the end
    val endSeconds = if (end.isBlank()) null else parseCutTime(end)
    val startValid = startSeconds != null && (song.duration <= 0 || startSeconds < song.duration)
    val endValid = end.isBlank() || (endSeconds != null && endSeconds > (startSeconds ?: 0) &&
        (song.duration <= 0 || endSeconds <= song.duration))
    val valid = songTitle.isNotBlank() && startValid && endValid
    val download = {
        if (valid) {
            val names = artists.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val albumName = album.trim()
            onDownload(
                YouTubeDownloadRequest(
                    song = song.copy(
                        title = songTitle.trim(),
                        artist = artist.trim(),
                        // Keep the artist pages of unchanged artists
                        artists = names.map { name -> song.artists.firstOrNull { it.name == name } ?: Artist(id = "", name = name) },
                        album = albumName,
                        // Without an album the server must not tag it with the one from YouTube Music
                        albumId = if (albumName.isEmpty()) "" else song.albumId,
                        genre = genre.trim().ifEmpty { null },
                    ),
                    startTime = startSeconds?.takeIf { it > 0 },
                    endTime = endSeconds?.takeIf { song.duration <= 0 || it < song.duration },
                )
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(title)
                if (progress != null) {
                    Text(progress, style = MaterialTheme.typography.bodyMedium, color = SpotifyLightGray)
                }
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = songTitle,
                    onValueChange = { songTitle = it },
                    label = { Text("Title") },
                    singleLine = true,
                    isError = songTitle.isBlank(),
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
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("Album") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = genre,
                    onValueChange = { genre = it },
                    label = { Text("Genre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = start,
                        onValueChange = { start = it },
                        label = { Text("Start") },
                        supportingText = { Text("Cuts off an intro") },
                        singleLine = true,
                        isError = !startValid,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = end,
                        onValueChange = { end = it },
                        label = { Text("End") },
                        supportingText = { Text("Cuts off an outro") },
                        singleLine = true,
                        isError = !endValid,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = download, enabled = valid, shape = RoundedCornerShape(0.dp)) {
                Text(confirmLabel, color = if (valid) AccentColor else Color.Unspecified)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onKeepRest != null) {
                    TextButton(onClick = onKeepRest) { Text("Keep the rest as is") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * Asks for the tags of the YouTube Music songs [editor] is about to add to a playlist, see
 * [PlaylistEditor.pendingYouTube]: one song after another, then adds them.
 */
@Composable
fun PendingYouTubeAdditionDialog(editor: PlaylistEditor) {
    val pending = editor.pendingYouTube.collectAsState().value.firstOrNull() ?: return
    val songs = remember(pending) { pending.songs.filter { it.id in pending.songIdsToTag } }
    var index by remember(pending) { mutableStateOf(0) }
    val requests = remember(pending) { mutableListOf<YouTubeDownloadRequest>() }
    val isLast = index >= songs.lastIndex
    YouTubeDownloadDialog(
        song = songs[index],
        title = "Add to \"${pending.playlistName}\"",
        confirmLabel = if (isLast) "Add" else "Next",
        progress = if (songs.size > 1) "Song ${index + 1} of ${songs.size}" else null,
        onDownload = { request ->
            requests += request
            if (isLast) editor.finishYouTube(pending, requests.toList()) else index++
        },
        onKeepRest = if (isLast) null else ({ editor.finishYouTube(pending, requests.toList()) }),
        onDismiss = { editor.finishYouTube(pending, null) },
    )
}

/** Seconds as "m:ss". */
private fun formatCutTime(seconds: Int) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/** "m:ss", "h:mm:ss" or plain seconds; null if it's none of them. */
private fun parseCutTime(text: String): Int? {
    val parts = text.trim().split(':')
    if (parts.size > 3 || parts.any { it.isEmpty() || it.any { c -> !c.isDigit() } }) return null
    return parts.fold(0) { total, part -> total * 60 + (part.toIntOrNull() ?: return null) }
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
