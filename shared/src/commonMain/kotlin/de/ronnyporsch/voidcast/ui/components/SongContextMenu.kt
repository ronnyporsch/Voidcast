package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownloadOff
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.data.jellyfin.PlaylistAdditionDto
import de.ronnyporsch.voidcast.data.offline.PlaylistDownloads
import de.ronnyporsch.voidcast.data.repository.MusicRepository
import de.ronnyporsch.voidcast.data.youtube.YouTubeService
import de.ronnyporsch.voidcast.domain.model.FavoriteKind
import de.ronnyporsch.voidcast.domain.model.Playlist
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.domain.model.isYouTube
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Adds songs to playlists from the song context menu (and drag and drop), on any screen. Reports the outcome to
 * [onMessage]. YouTube Music songs get downloaded into the library by the server, see [YouTubeService.addToPlaylist];
 * [onPlaylistChanged] tells about the playlists it changed.
 */
class PlaylistEditor(
    private val repository: MusicRepository,
    private val scope: CoroutineScope,
    private val youTube: YouTubeService? = null,
    private val onPlaylistChanged: suspend (playlistId: String) -> Unit = {},
    private val onMessage: suspend (String) -> Unit,
) {
    /** Whether YouTube Music songs can be added to playlists. */
    val canAddYouTube: Boolean get() = youTube?.currentCapabilities?.addToPlaylists == true

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    fun refreshPlaylists() {
        scope.launch { repository.getPlaylists().onSuccess { _playlists.value = it } }
    }

    /** Adds [songs] that aren't in the playlist yet; a playlist never contains a song twice. */
    fun addToPlaylist(playlist: Playlist, songs: List<Song>) {
        val (youTubeSongs, songs) = songs.distinctBy { it.id }.partition { it.isYouTube }
        if (youTubeSongs.isNotEmpty()) addYouTube(playlist.id, playlist.name, youTubeSongs)
        if (songs.isEmpty()) return
        scope.launch {
            // If the playlist can't be loaded, add anyway rather than not at all
            val existingIds = repository.getPlaylist(playlist.id).getOrNull()?.songs.orEmpty().map { it.id }.toSet()
            val (duplicates, new) = songs.partition { it.id in existingIds }
            if (new.isEmpty()) {
                val what = duplicates.singleOrNull()?.let { "\"${it.title}\" is" } ?: "These songs are"
                onMessage("$what already in \"${playlist.name}\"")
                return@launch
            }
            repository.addSongsToPlaylist(playlist.id, new.map { it.id })
                .onSuccess {
                    val skipped = if (duplicates.isEmpty()) "" else " (${duplicates.describe()} already in it)"
                    onMessage("Added ${new.describe()} to \"${playlist.name}\"$skipped")
                }
                .onFailure { onMessage("Couldn't add to \"${playlist.name}\": ${it.message}") }
        }
    }

    fun createPlaylist(name: String, songs: List<Song>) {
        scope.launch {
            val (youTubeSongs, songs) = songs.distinctBy { it.id }.partition { it.isYouTube }
            repository.createPlaylist(name, songs.map { it.id })
                .onSuccess { playlist ->
                    if (youTubeSongs.isNotEmpty()) addYouTube(playlist.id, name, youTubeSongs)
                    onMessage(if (songs.isEmpty()) "Created \"$name\"" else "Created \"$name\" with ${songs.describe()}")
                }
                .onFailure { onMessage("Couldn't create \"$name\": ${it.message}") }
        }
    }

    /** YouTube Music [songs] aren't in the library; the server downloads them and adds them once it has them. */
    private fun addYouTube(playlistId: String, playlistName: String, songs: List<Song>) {
        val youTube = youTube?.takeIf { canAddYouTube } ?: return
        scope.launch {
            try {
                val addition = youTube.addToPlaylist(
                    playlistId,
                    songs,
                    onStarted = { addition ->
                        // Don't hold up the polling while the message shows
                        if (addition.jobs.isNotEmpty()) {
                            val what = addition.jobs.singleOrNull()?.let { "\"${it.title}\"" } ?: "${addition.jobs.size} songs"
                            scope.launch { onMessage("Downloading $what to add to \"$playlistName\"") }
                        }
                    },
                    onProgress = { onPlaylistChanged(playlistId) },
                )
                onPlaylistChanged(playlistId)
                onMessage(addition.describe(songs, playlistName))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage("Couldn't add to \"$playlistName\": ${e.message}")
            }
        }
    }

    private fun PlaylistAdditionDto.describe(songs: List<Song>, playlistName: String): String {
        val single = songs.singleOrNull()
        return when {
            failures.isNotEmpty() && single != null -> "Couldn't add \"${single.title}\" to \"$playlistName\": ${failures.single().substringAfter(": ")}"
            failures.isNotEmpty() -> "Added $added of $total songs to \"$playlistName\", ${failures.size} failed: ${failures.first()}"
            added == 0 -> "${single?.let { "\"${it.title}\" is" } ?: "These songs are"} already in \"$playlistName\""
            alreadyInPlaylist > 0 -> "Added $added songs to \"$playlistName\" ($alreadyInPlaylist already in it)"
            else -> "Added ${songs.describe()} to \"$playlistName\""
        }
    }

    private fun List<Song>.describe() = singleOrNull()?.let { "\"${it.title}\"" } ?: "$size songs"
}

val LocalPlaylistEditor = staticCompositionLocalOf<PlaylistEditor?> { null }

/** Adds songs to the player's queue, from the song context menu. */
val LocalAddToQueue = staticCompositionLocalOf<((List<Song>) -> Unit)?> { null }

/** Looks up codec and bitrate of YouTube Music songs' streams, from the song context menu. */
val LocalStreamFormatLookup = staticCompositionLocalOf<((List<Song>) -> Unit)?> { null }

/** Downloads single songs from the song context menu; null where songs can't be downloaded. */
val LocalSongDownloads = staticCompositionLocalOf<PlaylistDownloads?> { null }

fun Set<String>.toggled(songId: String): Set<String> = if (songId in this) this - songId else this + songId

/**
 * Shift-click on the item at [index]: adds the items from it to the nearest selected one to [selectedIds]. Without a
 * selection only the clicked item gets selected.
 */
fun <T> List<T>.selectedThrough(index: Int, selectedIds: Set<String>, id: (T) -> String): Set<String> {
    val nearest = indices.filter { id(this[it]) in selectedIds }.minByOrNull { abs(it - index) }
        ?: return setOf(id(this[index]))
    return selectedIds + subList(min(nearest, index), max(nearest, index) + 1).map(id)
}

/** See [selectedThrough]. */
fun List<Song>.selectedThrough(index: Int, selectedSongIds: Set<String>): Set<String> =
    selectedThrough(index, selectedSongIds) { it.id }

/** The songs a context menu on [song] acts on: the selection if [song] is part of it, otherwise only [song]. */
fun List<Song>.contextMenuSongs(song: Song, selectedSongIds: Set<String>): List<Song> =
    if (song.id in selectedSongIds) filter { it.id in selectedSongIds }.distinctBy { it.id } else listOf(song)

/** Playlist positions a context menu on the song at [index] acts on: those of the selection if the song is part of it, otherwise only [index]. */
fun List<Song>.contextMenuIndices(index: Int, selectedSongIds: Set<String>): List<Int> =
    if (this[index].id in selectedSongIds) indices.filter { this[it].id in selectedSongIds } else listOf(index)

/** Context menu of one or more songs, opened at [offset] relative to the bottom left of the element it belongs to. */
@Composable
fun SongContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    songs: List<Song>,
    offset: DpOffset,
    /** Shows "Remove from playlist", for songs listed in a playlist. */
    onRemoveFromPlaylist: (() -> Unit)? = null,
    /** What the songs are, e.g. the albums they are from; by default the number of songs if there are several. */
    title: String? = null,
    /** Offers to mark the songs as favorites. */
    showFavorites: Boolean = true,
) {
    val editor = LocalPlaylistEditor.current
    val addToQueue = LocalAddToQueue.current
    val songDownloads = LocalSongDownloads.current
    if (editor == null && addToQueue == null && onRemoveFromPlaylist == null && songDownloads == null) return
    // YouTube Music results aren't in the library; the server downloads them first where it can
    val canAddToPlaylist = editor != null && (editor.canAddYouTube || songs.none { it.isYouTube })
    var showPlaylists by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    // The menu forgets the songs when it closes, but the dialog outlives it
    var dialogSongs by remember { mutableStateOf(emptyList<Song>()) }
    val playlists by (editor?.playlists ?: remember { MutableStateFlow(emptyList<Playlist>()) }).collectAsState()
    val favorites = LocalFavorites.current
    val streamFormatLookup = LocalStreamFormatLookup.current
    val favoriteIds by (favorites?.ids ?: remember { MutableStateFlow(emptySet<String>()) }).collectAsState()
    val downloadedSongIds = LocalDownloadedSongIds.current
    val pendingDownloads = LocalPendingSongDownloads.current
    val singleDownloadIds by (songDownloads?.singleSongIds ?: remember { MutableStateFlow(emptySet<String>()) }).collectAsState()

    LaunchedEffect(expanded) {
        if (!expanded) showPlaylists = false
    }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, offset = offset) {
        val header = title ?: if (songs.size > 1) "${songs.size} songs selected" else null
        if (header != null) {
            Text(
                header,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 280.dp),
            )
            HorizontalDivider()
        }
        if (!showPlaylists) {
            if (addToQueue != null) {
                DropdownMenuItem(
                    text = { Text("Add to queue") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                    onClick = {
                        addToQueue(songs)
                        onDismiss()
                    },
                )
            }
            if (favorites != null && showFavorites) {
                val favoriteSongs = songs.filter { favorites.canBeFavorite(it.id) }
                if (favoriteSongs.isNotEmpty()) {
                    // Adds all unless all of them are favorites already
                    val add = favoriteSongs.any { it.id !in favoriteIds }
                    DropdownMenuItem(
                        text = { Text(if (add) "Add to favorites" else "Remove from favorites") },
                        leadingIcon = {
                            Icon(if (add) Icons.Default.FavoriteBorder else Icons.Default.Favorite, contentDescription = null)
                        },
                        onClick = {
                            favoriteSongs.forEach { favorites.set(FavoriteKind.Song, it.id, add) }
                            onDismiss()
                        },
                    )
                }
            }
            if (canAddToPlaylist) {
                DropdownMenuItem(
                    text = { Text("Add to playlist") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                    onClick = {
                        editor?.refreshPlaylists()
                        showPlaylists = true
                    },
                )
            }
            if (songDownloads != null) {
                // YouTube Music results aren't in the library; they get downloaded to the server instead
                val downloadable = songs.filterNot { it.isYouTube }
                val missing = downloadable.filter {
                    it.id !in downloadedSongIds && it.id !in singleDownloadIds && it.id !in pendingDownloads
                }
                // Songs downloaded with a playlist stay as long as the playlist is downloaded
                val removable = downloadable.filter { it.id in singleDownloadIds }
                if (missing.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Download") },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                        onClick = {
                            songDownloads.downloadSongs(missing)
                            onDismiss()
                        },
                    )
                } else if (removable.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Remove download") },
                        leadingIcon = { Icon(Icons.Default.FileDownloadOff, contentDescription = null) },
                        onClick = {
                            songDownloads.removeSongs(removable.map { it.id })
                            onDismiss()
                        },
                    )
                }
            }
            if (streamFormatLookup != null && songs.any { it.isYouTube }) {
                DropdownMenuItem(
                    text = { Text("Show audio format") },
                    leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                    onClick = {
                        streamFormatLookup(songs.filter { it.isYouTube })
                        onDismiss()
                    },
                )
            }
            if (onRemoveFromPlaylist != null) {
                DropdownMenuItem(
                    text = { Text("Remove from playlist") },
                    leadingIcon = { Icon(Icons.Default.PlaylistRemove, contentDescription = null) },
                    onClick = {
                        onRemoveFromPlaylist()
                        onDismiss()
                    },
                )
            }
        } else if (editor != null) {
            DropdownMenuItem(
                text = { Text("Back") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
                onClick = { showPlaylists = false },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("New playlist…", color = AccentColor) },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = AccentColor) },
                onClick = {
                    dialogSongs = songs
                    showCreateDialog = true
                    onDismiss()
                },
            )
            playlists.forEach { playlist ->
                DropdownMenuItem(
                    text = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        editor.addToPlaylist(playlist, songs)
                        onDismiss()
                    },
                )
            }
        }
    }

    if (showCreateDialog && editor != null) {
        var name by remember { mutableStateOf("") }
        val create = {
            if (name.isNotBlank()) {
                editor.createPlaylist(name.trim(), dialogSongs)
                showCreateDialog = false
            }
        }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create New Playlist") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Playlist Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = create, colors = ButtonDefaults.buttonColors(containerColor = AccentColor)) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            },
        )
    }
}
