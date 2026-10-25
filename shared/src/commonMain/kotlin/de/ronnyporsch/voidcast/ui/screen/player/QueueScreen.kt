package de.ronnyporsch.voidcast.ui.screen.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.getPlatform
import de.ronnyporsch.voidcast.ui.components.SongRow
import de.ronnyporsch.voidcast.ui.components.TooltipIconButton
import de.ronnyporsch.voidcast.ui.components.VerticalScrollbar
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SpotifyLightGray
import de.ronnyporsch.voidcast.ui.navigation.QueueTab
import kotlinx.coroutines.isActive

/** The current song with the ones coming after it, which can be reordered by dragging, and the recently played songs. */
@Composable
fun QueueScreen(
    viewModel: PlayerViewModel,
    tab: QueueTab,
    onTabSelected: (QueueTab) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            QueueTab.entries.forEach {
                Tab(
                    selected = it == tab,
                    onClick = { onTabSelected(it) },
                    text = { Text(it.title, fontWeight = if (it == tab) FontWeight.Bold else FontWeight.Normal) },
                )
            }
        }
        when (tab) {
            QueueTab.Queue -> QueueList(state, viewModel::onEvent, onArtistClick, onAlbumClick)
            QueueTab.RecentlyPlayed -> RecentlyPlayedList(state, viewModel::onEvent, onArtistClick, onAlbumClick)
        }
    }
}

/** Buttons that open the queue and the recently played songs, green while [openTab] is shown. */
@Composable
fun QueueButtons(
    openTab: QueueTab?,
    onClick: (QueueTab) -> Unit,
    buttonModifier: Modifier = Modifier,
) {
    TooltipIconButton(
        onClick = { onClick(QueueTab.Queue) },
        icon = Icons.AutoMirrored.Filled.QueueMusic,
        contentDescription = "Queue",
        tint = if (openTab == QueueTab.Queue) AccentColor else SpotifyLightGray,
        modifier = buttonModifier,
    )
    TooltipIconButton(
        onClick = { onClick(QueueTab.RecentlyPlayed) },
        icon = Icons.Default.History,
        contentDescription = "Recently played",
        tint = if (openTab == QueueTab.RecentlyPlayed) AccentColor else SpotifyLightGray,
        modifier = buttonModifier,
    )
}

/** A song of the queue, with a key that stays the same while it's moved, also if the song is in the queue twice. */
private data class QueueEntry(val key: String, val song: Song)

private fun List<Song>.toEntries(): List<QueueEntry> {
    val occurrences = mutableMapOf<String, Int>()
    return map { song ->
        val n = occurrences[song.id] ?: 0
        occurrences[song.id] = n + 1
        QueueEntry("$QUEUE_KEY_PREFIX${song.id}#$n", song)
    }
}

private const val QUEUE_KEY_PREFIX = "queue:"

@Composable
private fun QueueList(
    state: PlayerState,
    onEvent: (PlayerEvent) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
) {
    val playback = state.playback
    val current = playback.currentIndex
    val currentSong = playback.currentSong
    val isMobile = remember { getPlatform().isMobile }
    val listState = rememberLazyListState()
    val reorder = remember(listState) { QueueReorderState(listState) }
    // Index of the first upcoming song in the queue
    val firstUpcoming = current + 1
    reorder.onMove = { from, to -> onEvent(PlayerEvent.MoveInQueue(firstUpcoming + from, firstUpcoming + to)) }
    // Moved locally while dragging, the queue only changes on drop
    val upcoming = remember(playback.queue, current) {
        if (currentSong == null) emptyList() else playback.queue.drop(firstUpcoming).toEntries()
    }
    // Read here rather than in the list content, so the list updates whenever the queue changes
    val entries = reorder.entries(upcoming)
    // A drag stops if the queue changes meanwhile, e.g. because the next song started
    LaunchedEffect(upcoming) { reorder.cancel() }
    // Rows that are being animated out still call this, so look the songs up by key instead of their row index
    val latestEntries by rememberUpdatedState(entries)
    val latestFirstUpcoming by rememberUpdatedState(firstUpcoming)
    fun queueIndexOf(key: String): Int? =
        latestEntries.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.let { latestFirstUpcoming + it }
    var selectedKey by remember { mutableStateOf<String?>(null) }

    // Scrolls while a song is dragged to the top or bottom edge
    LaunchedEffect(reorder.draggedKey) {
        if (reorder.draggedKey == null) return@LaunchedEffect
        while (isActive) {
            val delta = reorder.autoScrollDelta()
            if (delta != 0f) reorder.onScrolled(listState.scrollBy(delta))
            withFrameNanos {}
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 100.dp), modifier = Modifier.fillMaxSize()) {
            if (currentSong == null) {
                item(key = "empty") { EmptyText("Nothing is playing") }
                return@LazyColumn
            }
            item(key = "nowPlayingHeader") { SectionHeader("Now playing") }
            item(key = "nowPlaying") {
                SongRow(
                    song = currentSong,
                    onSongClick = {},
                    onArtistClick = onArtistClick,
                    onAlbumClick = onAlbumClick,
                    showAlbum = true,
                    isPlaying = true,
                )
            }
            item(key = "upcomingHeader") { SectionHeader("Next up") }
            if (entries.isEmpty()) {
                item(key = "upcomingEmpty") {
                    val device = state.remoteDevice
                    EmptyText(
                        if (device != null && !device.isVoidcast) "${device.name} doesn't share what comes next."
                        else "Nothing comes next. Add songs with \"Add to queue\" in their menu."
                    )
                }
            }
            items(entries, key = { it.key }) { entry ->
                val isDragged = reorder.draggedKey == entry.key
                val play: () -> Unit = {
                    if (state.canEditQueue) queueIndexOf(entry.key)?.let { onEvent(PlayerEvent.PlayQueueIndex(it)) }
                }
                SongRow(
                    song = entry.song,
                    onSongClick = { if (isMobile) play() else selectedKey = entry.key },
                    onSongDoubleClick = play,
                    onArtistClick = onArtistClick,
                    onAlbumClick = onAlbumClick,
                    showAlbum = true,
                    isSelected = selectedKey == entry.key || isDragged,
                    onRemoveClick = if (!state.canEditQueue) null else {
                        { queueIndexOf(entry.key)?.let { onEvent(PlayerEvent.RemoveFromQueue(it)) } }
                    },
                    removeDescription = "Remove from queue",
                    trailingContent = (@Composable {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = "Drag to reorder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(if (isMobile) 32.dp else 24.dp)
                                .pointerInput(entry.key) {
                                    detectDragGestures(
                                        onDragStart = { reorder.start(entry.key, latestEntries) },
                                        onDragEnd = { reorder.end() },
                                        onDragCancel = { reorder.end() },
                                    ) { change, amount ->
                                        change.consume()
                                        reorder.drag(amount.y)
                                    }
                                },
                        )
                    }).takeIf { state.canEditQueue },
                    modifier = if (isDragged) {
                        Modifier
                            .zIndex(1f)
                            .graphicsLayer { translationY = reorder.offset }
                            .shadow(6.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    } else {
                        Modifier.animateItem()
                    },
                )
            }
        }

        VerticalScrollbar(Modifier.align(Alignment.CenterEnd).fillMaxHeight(), listState)
    }
}

@Composable
private fun RecentlyPlayedList(
    state: PlayerState,
    onEvent: (PlayerEvent) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
) {
    val isMobile = remember { getPlatform().isMobile }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 100.dp), modifier = Modifier.fillMaxSize()) {
            if (state.recentlyPlayed.isEmpty()) {
                item { EmptyText("Songs you play show up here") }
            }
            items(state.recentlyPlayed, key = { it.id }) { song ->
                val play = { onEvent(PlayerEvent.PlayNow(song)) }
                SongRow(
                    song = song,
                    onSongClick = { if (isMobile) play() else selectedId = song.id },
                    onSongDoubleClick = play,
                    onArtistClick = onArtistClick,
                    onAlbumClick = onAlbumClick,
                    showAlbum = true,
                    isSelected = selectedId == song.id,
                    isPlaying = song.id == state.playback.currentSong?.id,
                )
            }
        }

        VerticalScrollbar(Modifier.align(Alignment.CenterEnd).fillMaxHeight(), listState)
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

@Composable
private fun EmptyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

/** Moves the dragged entry past the ones it gets dragged over; the queue gets changed once it's dropped. */
private class QueueReorderState(private val listState: LazyListState) {
    /** The entries while dragging, with the dragged one moved. */
    private var moved by mutableStateOf<List<QueueEntry>?>(null)

    /** What to show for the [upcoming] songs of the queue. */
    fun entries(upcoming: List<QueueEntry>): List<QueueEntry> = moved ?: upcoming
    var draggedKey by mutableStateOf<String?>(null)
        private set
    /** How far the dragged entry is drawn from where it's laid out. */
    var offset by mutableFloatStateOf(0f)
        private set
    var onMove: (from: Int, to: Int) -> Unit = { _, _ -> }
    private var startIndex = -1

    fun start(key: String, entries: List<QueueEntry>) {
        moved = entries
        draggedKey = key
        offset = 0f
        startIndex = entries.indexOfFirst { it.key == key }
    }

    fun drag(delta: Float) {
        if (draggedKey == null) return
        offset += delta
        moveIfNeeded()
    }

    /** The list scrolled by [delta] under the dragged entry, which stays under the pointer. */
    fun onScrolled(delta: Float) {
        offset += delta
        moveIfNeeded()
    }

    fun end() {
        val key = draggedKey ?: return
        val to = moved?.indexOfFirst { it.key == key } ?: -1
        cancel()
        if (startIndex >= 0 && to >= 0 && startIndex != to) onMove(startIndex, to)
    }

    fun cancel() {
        moved = null
        draggedKey = null
        offset = 0f
    }

    fun autoScrollDelta(): Float {
        val info = listState.layoutInfo
        val dragged = info.visibleItemsInfo.firstOrNull { it.key == draggedKey } ?: return 0f
        val top = dragged.offset + offset
        val bottom = top + dragged.size
        val edge = dragged.size.toFloat()
        return when {
            bottom > info.viewportEndOffset - edge -> (bottom - (info.viewportEndOffset - edge)) / 4f
            top < info.viewportStartOffset + edge -> (top - (info.viewportStartOffset + edge)) / 4f
            else -> 0f
        }.coerceIn(-edge / 2, edge / 2)
    }

    private fun moveIfNeeded() {
        val key = draggedKey ?: return
        val visible = listState.layoutInfo.visibleItemsInfo
        val dragged = visible.firstOrNull { it.key == key } ?: return
        val center = dragged.offset + offset + dragged.size / 2f
        val target = visible.firstOrNull {
            it.key != key && (it.key as? String)?.startsWith(QUEUE_KEY_PREFIX) == true &&
                center >= it.offset && center < it.offset + it.size
        } ?: return
        val entries = moved ?: return
        val from = entries.indexOfFirst { it.key == key }
        val to = entries.indexOfFirst { it.key == target.key }
        if (from < 0 || to < 0) return
        moved = entries.toMutableList().apply { add(to, removeAt(from)) }
        // It's laid out at the target's place from now on
        offset -= target.offset - dragged.offset
    }
}
