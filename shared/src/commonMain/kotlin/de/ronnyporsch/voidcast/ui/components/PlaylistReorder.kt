package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import kotlinx.coroutines.isActive

/** Separates the playlist position at the end of the keys of the song rows, see [playlistEntryKey]. */
private const val ENTRY_KEY_SEPARATOR = "::"

/**
 * Key of the row of the playlist entry at [index]. Song rows of lists that use [rememberPlaylistReorder] need keys
 * that end like this, so the drop position can be told from them.
 */
fun playlistEntryKey(prefix: String, index: Int): String = "$prefix$ENTRY_KEY_SEPARATOR$index"

/** Songs dragged out of the playlist [playlistId], with their positions in it. */
data class PlaylistEntriesDrag(val playlistId: String, val indices: List<Int>)

/** Lets songs of the playlist be dragged to another position in it (desktop and web, see [SongDragAndDrop]). */
class PlaylistReorder internal constructor(internal val target: SongDropTarget, private val listState: LazyListState) {
    internal var coordinates: LayoutCoordinates? = null

    /** Position in the playlist the songs would be dropped before at the pointer; the size of it is after the end. */
    internal fun insertionIndex(): Int? = insertionPoint()?.first

    /** [insertionIndex] and where to show it, in pixels from the top of the list. */
    internal fun insertionPoint(): Pair<Int, Int>? {
        val coordinates = coordinates?.takeIf { it.isAttached } ?: return null
        val y = coordinates.windowToLocal(target.position).y
        val rows = listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            (info.key as? String)?.substringAfterLast(ENTRY_KEY_SEPARATOR)?.toIntOrNull()?.let { it to info }
        }
        val (_, last) = rows.lastOrNull() ?: return null
        val before = rows.firstOrNull { (_, info) -> y < info.offset + info.size / 2 }
        return if (before != null) before.first to before.second.offset
        else rows.last().first + 1 to last.offset + last.size
    }

    /** Distance to scroll by while the pointer is near the top or bottom edge. */
    internal fun autoScrollDelta(edge: Float): Float {
        val coordinates = coordinates?.takeIf { it.isAttached } ?: return 0f
        val y = coordinates.windowToLocal(target.position).y
        val height = coordinates.size.height
        return when {
            y < edge -> (y - edge) / 4f
            y > height - edge -> (y - (height - edge)) / 4f
            else -> 0f
        }.coerceIn(-edge / 2, edge / 2)
    }
}

private class ReorderHolder {
    var value: PlaylistReorder? = null
}

/** Null where songs can't be dragged; [onMove] gets the positions of the dropped songs and the one they go before. */
@Composable
fun rememberPlaylistReorder(
    playlistId: String,
    listState: LazyListState,
    enabled: Boolean,
    onMove: (indices: List<Int>, before: Int) -> Unit,
): PlaylistReorder? {
    val dragAndDrop = LocalSongDragAndDrop.current
    // Set below, as it needs the target
    val reorder = remember { ReorderHolder() }
    val target = rememberSongDropTarget(
        accepts = { enabled && (dragAndDrop?.origin as? PlaylistEntriesDrag)?.playlistId == playlistId },
        icon = Icons.Default.SwapVert,
        onDrop = {
            val drag = dragAndDrop?.origin as? PlaylistEntriesDrag
            val before = reorder.value?.insertionIndex()
            if (drag != null && before != null) onMove(drag.indices, before)
        },
    ) ?: return null
    val current = remember(target, listState) { PlaylistReorder(target, listState) }
    reorder.value = current

    val edge = with(LocalDensity.current) { 48.dp.toPx() }
    LaunchedEffect(current, target.isHovered) {
        if (!target.isHovered) return@LaunchedEffect
        while (isActive) {
            val delta = current.autoScrollDelta(edge)
            if (delta != 0f) listState.scrollBy(delta)
            withFrameNanos {}
        }
    }
    return current
}

/** Marks the song list [reorder] is for. */
fun Modifier.playlistReorderTarget(reorder: PlaylistReorder?): Modifier =
    if (reorder == null) this
    else songDropTarget(reorder.target).onGloballyPositioned { reorder.coordinates = it }

/** A line where the dragged songs would go; place it over the song list, the same size. */
@Composable
fun PlaylistReorderIndicator(reorder: PlaylistReorder, modifier: Modifier) {
    Box(
        modifier.drawBehind {
            if (!reorder.target.isHovered) return@drawBehind
            val (_, y) = reorder.insertionPoint() ?: return@drawBehind
            val thickness = 2.dp.toPx()
            drawRect(AccentColor, topLeft = Offset(0f, y - thickness / 2), size = Size(size.width, thickness))
        }
    )
}
