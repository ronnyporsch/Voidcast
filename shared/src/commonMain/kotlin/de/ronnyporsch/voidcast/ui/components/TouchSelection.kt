package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.getPlatform
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Selecting items of a list by touch (mobile), like in photo gallery apps: a long press selects an item, moving the
 * finger on without lifting it selects the items up to the one under it (or unselects them, if the pressed item was
 * selected already). While items are selected, taps select or unselect items instead of opening them, and
 * [TouchSelectionBar] replaces the bottom navigation with actions for them; back ends the selection.
 *
 * Desktop and web select with Ctrl- and Shift-clicks instead; there this does nothing. Create it with
 * [rememberTouchSelection] and add [touchSelection] to the lazy list or grid showing the items.
 */
@Stable
class TouchSelection<T> internal constructor() {
    val isEnabled: Boolean = getPlatform().isMobile

    // Updated on every composition of rememberTouchSelection, which recomposes when they change; only read when
    // handling input
    internal var items: List<T> = emptyList()
    internal var id: (T) -> String = { it.toString() }
    internal var keys: Map<Any, Int> = emptyMap()
    internal var onChange: (Set<String>) -> Unit = {}

    // State, as composables that only get this object (e.g. TouchSelectionBar) are skipped otherwise when it changes
    var selectedIds: Set<String> by mutableStateOf(emptySet())
        internal set

    /** Items are selected: taps select or unselect them rather than open them. */
    val isActive: Boolean get() = isEnabled && selectedIds.isNotEmpty()

    /** The selected items, in the order of the list; an item listed more than once only once. */
    val selectedItems: List<T> get() = items.filter { id(it) in selectedIds }.distinctBy(id)

    /** A tap on [item]: selects or unselects it while the selection [isActive], otherwise [open]s it. */
    fun click(item: T, open: () -> Unit) {
        if (isActive) onChange(selectedIds.toggled(id(item))) else open()
    }

    fun clear() = onChange(emptySet())

    fun selectAll() = onChange(items.mapTo(mutableSetOf()) { id(it) })

    private var dragBase = emptySet<String>()
    private var dragStart = -1
    private var dragEnd = -1
    private var dragSelects = true

    internal fun indexOfKey(key: Any): Int? = keys[key]

    internal fun startDrag(index: Int) {
        if (index !in items.indices) return
        dragBase = selectedIds
        dragStart = index
        dragEnd = -1
        dragSelects = id(items[index]) !in selectedIds
        dragTo(index)
    }

    internal fun dragTo(index: Int) {
        if (dragStart !in items.indices || index !in items.indices || index == dragEnd) return
        dragEnd = index
        val range = items.subList(min(dragStart, index), max(dragStart, index) + 1).map(id)
        onChange(if (dragSelects) dragBase + range else dragBase - range)
    }
}

/**
 * See [TouchSelection]. [key] has to give the keys of the lazy list's items, so the item under the finger can be
 * found; [selectedIds] and [onChange] keep the selection, e.g. in a view model.
 */
@Composable
fun <T> rememberTouchSelection(
    items: List<T>,
    selectedIds: Set<String>,
    onChange: (Set<String>) -> Unit,
    id: (T) -> String,
    key: (index: Int, item: T) -> Any,
): TouchSelection<T> {
    val selection = remember { TouchSelection<T>() }
    selection.items = items
    selection.id = id
    selection.keys = remember(items) { items.withIndex().associate { (index, item) -> key(index, item) to index } }
    selection.onChange = onChange
    selection.selectedIds = selectedIds
    return selection
}

/** Lets [selection] select the items of this list by long pressing and moving the finger. */
@Composable
fun Modifier.touchSelection(selection: TouchSelection<*>, state: LazyListState): Modifier =
    touchSelection(selection, state) { position ->
        val y = position.y.toInt()
        state.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.key
    }

/** Lets [selection] select the items of this grid by long pressing and moving the finger. */
@Composable
fun Modifier.touchSelection(selection: TouchSelection<*>, state: LazyGridState): Modifier =
    touchSelection(selection, state) { position ->
        val point = position.round()
        state.layoutInfo.visibleItemsInfo.firstOrNull { IntRect(it.offset, it.size).contains(point) }?.key
    }

@Composable
private fun Modifier.touchSelection(
    selection: TouchSelection<*>,
    scrollState: ScrollableState,
    keyAt: (Offset) -> Any?,
): Modifier {
    if (!selection.isEnabled) return this
    val haptics = LocalHapticFeedback.current
    return pointerInput(selection, scrollState) {
        val edge = 64.dp.toPx()
        val maxScrollPerFrame = 24.dp.toPx()
        fun indexAt(position: Offset) = keyAt(position)?.let(selection::indexOfKey)
        coroutineScope {
            awaitEachGesture {
                // Before the list and its items, so a long press can take the gesture away from them
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val startIndex = indexAt(down.position) ?: return@awaitEachGesture
                val endedBeforeLongPress = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                        // Lifted, or moved (scrolling, swiping a row) before the long press
                        if (change == null || !change.pressed || change.isConsumed ||
                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                        ) break
                    }
                    true
                }
                if (endedBeforeLongPress != null) return@awaitEachGesture

                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                selection.startDrag(startIndex)
                var position = down.position
                // Near the top or bottom edge the list scrolls, selecting on
                val autoScroll = launch {
                    while (isActive) {
                        val speed = when {
                            position.y < edge -> -(edge - position.y) / edge * maxScrollPerFrame
                            position.y > size.height - edge -> (position.y - size.height + edge) / edge * maxScrollPerFrame
                            else -> 0f
                        }
                        if (speed != 0f) {
                            scrollState.scrollBy(speed)
                            indexAt(position)?.let(selection::dragTo)
                        }
                        delay(16)
                    }
                }
                try {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        // Neither scroll the list nor tap the item
                        change.consume()
                        if (!change.pressed) break
                        position = change.position
                        indexAt(position)?.let(selection::dragTo)
                    }
                } finally {
                    autoScroll.cancel()
                }
            }
        }
    }
}

/** What [SelectionActionBar] shows and acts on. */
class SelectionBarContent(
    val count: Int,
    /** The songs of the selection, e.g. those of selected albums. */
    val songs: suspend () -> List<Song>,
    val onClear: () -> Unit,
    val onSelectAll: () -> Unit,
    val onRemoveFromPlaylist: (() -> Unit)?,
    val showFavorites: Boolean,
)

/** Where screens put their [TouchSelectionBar]; the app shows it in place of the bottom navigation. */
@Stable
class SelectionBarHost {
    private val contents = mutableStateMapOf<Any, SelectionBarContent>()

    val current: SelectionBarContent? get() = contents.values.firstOrNull()

    internal fun set(owner: Any, content: SelectionBarContent?) {
        if (content == null) contents.remove(owner) else contents[owner] = content
    }
}

val LocalSelectionBarHost = staticCompositionLocalOf<SelectionBarHost?> { null }

/** Shows the action bar for [selection] while items are selected; [songs] are the selected songs. */
@Composable
fun TouchSelectionBar(
    selection: TouchSelection<*>,
    songs: suspend () -> List<Song>,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    showFavorites: Boolean = true,
) {
    val host = LocalSelectionBarHost.current
    if (host == null || !selection.isEnabled) return
    val owner = remember { Any() }
    val content = if (selection.isActive) {
        SelectionBarContent(
            count = selection.selectedIds.size,
            songs = songs,
            onClear = selection::clear,
            onSelectAll = selection::selectAll,
            onRemoveFromPlaylist = onRemoveFromPlaylist,
            showFavorites = showFavorites,
        )
    } else null
    SideEffect { host.set(owner, content) }
    DisposableEffect(host, owner) {
        onDispose { host.set(owner, null) }
    }
}

/** The actions for the selected items, in place of the bottom navigation; the song context menu has the rest. */
@Composable
fun SelectionActionBar(content: SelectionBarContent) {
    val addToQueue = LocalAddToQueue.current
    val editor = LocalPlaylistEditor.current
    val scope = rememberCoroutineScope()
    val currentContent by rememberUpdatedState(content)
    var isLoading by remember { mutableStateOf(false) }
    // The songs the open menu acts on
    var playlistMenuSongs by remember { mutableStateOf<List<Song>?>(null) }
    var moreMenuSongs by remember { mutableStateOf<List<Song>?>(null) }
    fun withSongs(action: (List<Song>) -> Unit) {
        if (isLoading) return
        isLoading = true
        scope.launch {
            val songs = currentContent.songs()
            isLoading = false
            if (songs.isNotEmpty()) action(songs)
        }
    }

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        TooltipIconButton(onClick = content.onClear, icon = Icons.Default.Close, contentDescription = "End selection")
        Text(
            text = "${content.count} selected",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).padding(start = 4.dp).align(Alignment.CenterVertically),
        )
        if (isLoading) {
            CircularProgressIndicator(
                color = AccentColor,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp).align(Alignment.CenterVertically),
            )
            Spacer(Modifier.width(8.dp))
        }
        TooltipIconButton(onClick = content.onSelectAll, icon = Icons.Default.SelectAll, contentDescription = "Select all")
        if (addToQueue != null) {
            TooltipIconButton(
                onClick = {
                    withSongs {
                        addToQueue(it)
                        currentContent.onClear()
                    }
                },
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = "Add to queue",
            )
        }
        if (editor != null) {
            Box {
                TooltipIconButton(
                    onClick = { withSongs { playlistMenuSongs = it } },
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = "Add to playlist",
                )
                SongContextMenu(
                    expanded = playlistMenuSongs != null,
                    onDismiss = { playlistMenuSongs = null },
                    songs = playlistMenuSongs.orEmpty(),
                    offset = DpOffset.Zero,
                    showPlaylistsFirst = true,
                    onActionDone = { currentContent.onClear() },
                )
            }
        }
        Box {
            TooltipIconButton(
                onClick = { withSongs { moreMenuSongs = it } },
                icon = Icons.Default.MoreVert,
                contentDescription = "More",
            )
            SongContextMenu(
                expanded = moreMenuSongs != null,
                onDismiss = { moreMenuSongs = null },
                songs = moreMenuSongs.orEmpty(),
                offset = DpOffset.Zero,
                onRemoveFromPlaylist = content.onRemoveFromPlaylist,
                showFavorites = content.showFavorites,
                onActionDone = { currentContent.onClear() },
            )
        }
    }
}
