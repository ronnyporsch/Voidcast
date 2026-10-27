package de.ronnyporsch.voidcast.ui.screen.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.data.youtube.DownloadStatus
import de.ronnyporsch.voidcast.data.youtube.YouTubeService
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.domain.model.youTubeArtistId
import de.ronnyporsch.voidcast.ui.components.RememberScrollPosition
import de.ronnyporsch.voidcast.ui.components.SongRow
import de.ronnyporsch.voidcast.ui.components.TooltipIconButton
import de.ronnyporsch.voidcast.ui.components.VerticalScrollbar
import de.ronnyporsch.voidcast.ui.components.YouTubeDownloadButton
import de.ronnyporsch.voidcast.ui.components.YouTubeDownloadMessage
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SpotifyLightGray

/** The YouTube Music songs downloaded into the server's library in this session, with their progress. */
@Composable
fun DownloadsScreen(
    youTube: YouTubeService,
    onPlay: (songs: List<Song>, startIndex: Int) -> Unit,
    /** [artistId] is only known for YouTube Music artists. */
    onArtistClick: (artistId: String?, artistName: String) -> Unit,
    onAlbumClick: (String) -> Unit,
    currentSongId: String? = null,
) {
    val songs by youTube.requestedSongs.collectAsState()
    val downloads by youTube.downloads.collectAsState()
    val listState = rememberLazyListState()
    RememberScrollPosition(listState)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 100.dp)) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
                    Text(
                        text = "YouTube Music downloads",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (songs.isEmpty()) "Songs downloaded into the library in this session show up here"
                        else downloadSummary(songs.map { downloads[it.id] }),
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyLightGray,
                    )
                }
            }
            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                Column {
                    SongRow(
                        song = song,
                        onSongClick = { onPlay(songs, index) },
                        onArtistClick = { name -> onArtistClick(song.youTubeArtistId(name), name) },
                        onAlbumClick = onAlbumClick,
                        showAlbum = true,
                        isPlaying = song.id == currentSongId,
                        trailingContent = {
                            // Retries failed downloads
                            YouTubeDownloadButton(song, downloads[song.id]) { youTube.download(it) }
                        },
                    )
                    YouTubeDownloadMessage(downloads[song.id])
                }
            }
        }
        VerticalScrollbar(Modifier.align(Alignment.CenterEnd).fillMaxHeight(), listState)
    }
}

/** Opens the [DownloadsScreen], green while it is shown or something is downloading; only once something got downloaded. */
@Composable
fun DownloadsButton(youTube: YouTubeService, isOpen: Boolean, onClick: () -> Unit) {
    val songs by youTube.requestedSongs.collectAsState()
    val downloads by youTube.downloads.collectAsState()
    if (songs.isEmpty()) return
    val running = songs.any { downloads[it.id] == DownloadStatus.InProgress }
    TooltipIconButton(
        onClick = onClick,
        icon = if (running) Icons.Default.Downloading else Icons.Default.Download,
        contentDescription = "Downloads",
        tint = if (isOpen || running) AccentColor else SpotifyLightGray,
    )
}

/** E.g. "2 downloading • 5 done • 1 failed". */
private fun downloadSummary(statuses: List<DownloadStatus?>): String = listOfNotNull(
    statuses.count { it == DownloadStatus.InProgress }.takeIf { it > 0 }?.let { "$it downloading" },
    statuses.count { it is DownloadStatus.Done || it == DownloadStatus.InLibrary }.takeIf { it > 0 }?.let { "$it done" },
    statuses.count { it is DownloadStatus.Failed }.takeIf { it > 0 }?.let { "$it failed" },
).joinToString(" • ")
