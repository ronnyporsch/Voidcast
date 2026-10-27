package de.ronnyporsch.voidcast.ui.screen.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ronnyporsch.voidcast.data.offline.PlaylistDownloadStatus
import de.ronnyporsch.voidcast.data.offline.PlaylistDownloads
import de.ronnyporsch.voidcast.data.youtube.DownloadStatus
import de.ronnyporsch.voidcast.data.youtube.YouTubeService
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.domain.model.formatFileSize
import de.ronnyporsch.voidcast.domain.model.youTubeArtistId
import de.ronnyporsch.voidcast.ui.LocalCoverArtUrlProvider
import de.ronnyporsch.voidcast.ui.components.CoverArtImage
import de.ronnyporsch.voidcast.ui.components.PlaylistDownloadButton
import de.ronnyporsch.voidcast.ui.components.RememberScrollPosition
import de.ronnyporsch.voidcast.ui.components.SongRow
import de.ronnyporsch.voidcast.ui.components.TooltipIconButton
import de.ronnyporsch.voidcast.ui.components.VerticalScrollbar
import de.ronnyporsch.voidcast.ui.components.YouTubeDownloadButton
import de.ronnyporsch.voidcast.ui.components.YouTubeDownloadMessage
import de.ronnyporsch.voidcast.ui.navigation.DownloadsTab
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SpotifyLightGray
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The YouTube Music songs downloaded into the server's library in this session, with their progress, and (Android)
 * the playlists, albums and songs downloaded for offline playback.
 */
@Composable
fun DownloadsScreen(
    youTube: YouTubeService,
    /** Null where nothing can be downloaded for offline playback. */
    deviceDownloads: PlaylistDownloads?,
    tab: DownloadsTab,
    onTabSelected: (DownloadsTab) -> Unit,
    onPlay: (songs: List<Song>, startIndex: Int) -> Unit,
    /** [artistId] is only known for YouTube Music artists. */
    onArtistClick: (artistId: String?, artistName: String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    currentSongId: String? = null,
) {
    val requestedSongs by youTube.requestedSongs.collectAsState()
    val capabilities by youTube.capabilities.collectAsState(youTube.currentCapabilities)
    val tabs = listOfNotNull(
        DownloadsTab.YouTube.takeIf { capabilities.canDownload || requestedSongs.isNotEmpty() },
        DownloadsTab.Device.takeIf { deviceDownloads != null },
    ).ifEmpty { listOf(DownloadsTab.YouTube) }
    val shownTab = tab.takeIf { it in tabs } ?: tabs.first()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (tabs.size > 1) {
            TabRow(
                selectedTabIndex = tabs.indexOf(shownTab),
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                tabs.forEach {
                    Tab(
                        selected = it == shownTab,
                        onClick = { onTabSelected(it) },
                        text = { Text(it.title, fontWeight = if (it == shownTab) FontWeight.Bold else FontWeight.Normal) },
                    )
                }
            }
        }
        when (shownTab) {
            DownloadsTab.YouTube -> YouTubeDownloadsList(youTube, onPlay, onArtistClick, onAlbumClick, currentSongId)
            DownloadsTab.Device -> deviceDownloads?.let {
                DeviceDownloadsList(it, onPlay, onArtistClick, onAlbumClick, onPlaylistClick, currentSongId)
            }
        }
    }
}

@Composable
private fun YouTubeDownloadsList(
    youTube: YouTubeService,
    onPlay: (songs: List<Song>, startIndex: Int) -> Unit,
    onArtistClick: (artistId: String?, artistName: String) -> Unit,
    onAlbumClick: (String) -> Unit,
    currentSongId: String?,
) {
    val songs by youTube.requestedSongs.collectAsState()
    val downloads by youTube.downloads.collectAsState()
    val listState = rememberLazyListState()
    RememberScrollPosition(listState, "youTubeDownloads")

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 100.dp)) {
            item(key = "header") {
                Header(
                    title = "YouTube Music downloads",
                    subtitle = if (songs.isEmpty()) "Songs downloaded into the library in this session show up here"
                    else downloadSummary(songs.map { downloads[it.id] }),
                )
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

@Composable
private fun DeviceDownloadsList(
    downloads: PlaylistDownloads,
    onPlay: (songs: List<Song>, startIndex: Int) -> Unit,
    onArtistClick: (artistId: String?, artistName: String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    currentSongId: String?,
) {
    val content by downloads.content.collectAsState()
    val playlistStatus by downloads.status.collectAsState()
    val albumStatus by downloads.albumStatus.collectAsState()
    val listState = rememberLazyListState()
    RememberScrollPosition(listState, "deviceDownloads")
    val songs = content.songs

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 100.dp)) {
            item(key = "header") {
                val size = songs.sumOf { it.size ?: 0L }
                Header(
                    title = "On this device",
                    subtitle = if (songs.isEmpty() && content.playlists.isEmpty() && content.albums.isEmpty()) {
                        "Download playlists, albums or songs to listen to them offline"
                    } else {
                        listOfNotNull(
                            "${songs.size} song${if (songs.size != 1) "s" else ""}",
                            size.takeIf { it > 0 }?.formatFileSize(),
                        ).joinToString(" • ")
                    },
                )
            }

            if (content.playlists.isNotEmpty()) {
                item(key = "playlistsHeader") { SectionHeader("Playlists") }
                items(content.playlists, key = { "playlist:${it.id}" }) { playlist ->
                    DownloadedCollectionRow(
                        name = playlist.name,
                        coverArtId = playlist.coverArtId,
                        status = playlistStatus[playlist.id],
                        kind = "playlist",
                        onClick = { onPlaylistClick(playlist.id) },
                        onDownload = { downloads.download(playlist) },
                        onRemove = { downloads.remove(playlist.id) },
                    )
                }
            }

            if (content.albums.isNotEmpty()) {
                item(key = "albumsHeader") { SectionHeader("Albums") }
                items(content.albums, key = { "album:${it.id}" }) { album ->
                    DownloadedCollectionRow(
                        name = album.name,
                        coverArtId = album.coverArtId,
                        status = albumStatus[album.id],
                        kind = "album",
                        detail = album.artist,
                        onClick = { onAlbumClick(album.id) },
                        onDownload = { downloads.downloadAlbum(album) },
                        onRemove = { downloads.removeAlbum(album.id) },
                    )
                }
            }

            if (songs.isNotEmpty()) {
                item(key = "songsHeader") { SectionHeader("Songs") }
                itemsIndexed(songs, key = { _, song -> "song:${song.id}" }) { index, song ->
                    SongRow(
                        song = song,
                        onSongClick = { onPlay(songs, index) },
                        onArtistClick = { name -> onArtistClick(null, name) },
                        onAlbumClick = onAlbumClick,
                        showAlbum = true,
                        isPlaying = song.id == currentSongId,
                    )
                }
            }
        }
        VerticalScrollbar(Modifier.align(Alignment.CenterEnd).fillMaxHeight(), listState)
    }
}

/** A downloaded playlist or album, with how far its songs are downloaded. */
@Composable
private fun DownloadedCollectionRow(
    name: String,
    coverArtId: String?,
    status: PlaylistDownloadStatus?,
    kind: String,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    detail: String = "",
) {
    val coverArt = LocalCoverArtUrlProvider.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArtImage(
            url = coverArt.getUrl(coverArtId),
            modifier = Modifier.size(44.dp),
            cornerRadius = 0.dp,
            placeholderFontSize = 18.sp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = listOfNotNull(
                    detail.ifBlank { null },
                    status?.let { "${it.downloadedSongs} of ${it.totalSongs} songs" },
                ).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PlaylistDownloadButton(
            playlistName = name,
            status = status,
            onDownload = onDownload,
            onRemove = onRemove,
            kind = kind,
        )
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = SpotifyLightGray)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/**
 * Opens the [DownloadsScreen], green while it is shown or something is downloading. Shown where songs can be
 * downloaded for offline playback, otherwise once something got downloaded from YouTube Music.
 */
@Composable
fun DownloadsButton(youTube: YouTubeService, deviceDownloads: PlaylistDownloads?, isOpen: Boolean, onClick: () -> Unit) {
    val songs by youTube.requestedSongs.collectAsState()
    val downloads by youTube.downloads.collectAsState()
    val transferring by (deviceDownloads?.downloadingSongIds ?: remember { MutableStateFlow(emptySet<String>()) })
        .collectAsState()
    if (songs.isEmpty() && deviceDownloads == null) return
    val running = transferring.isNotEmpty() || songs.any { downloads[it.id] == DownloadStatus.InProgress }
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
