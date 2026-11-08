package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import kotlin.math.abs

/** What a scrollbar needs to know about what it scrolls, in pixels. */
interface ScrollMetrics {
    val viewportSize: Double
    val scrollOffset: Double
    val contentSize: Double
    suspend fun scrollTo(target: Double)
}

/** Lazy content can't tell its height, so it's estimated from the height the visible items take up on average; exact at either end. */
abstract class LazyScrollMetrics(private val scrollable: ScrollableState) : ScrollMetrics {
    protected abstract val itemCount: Int
    protected abstract val contentPadding: Int
    protected abstract val firstVisibleIndex: Int
    protected abstract val firstVisibleOffset: Int
    /** Top and bottom edge of each visible item. */
    protected abstract fun visibleItems(): List<Pair<Int, Int>>
    protected abstract suspend fun scrollToItem(index: Int, offset: Int)

    override val scrollOffset: Double
        get() = itemHeight() * firstVisibleIndex + firstVisibleOffset

    override val contentSize: Double
        get() = itemHeight() * itemCount + contentPadding

    override suspend fun scrollTo(target: Double) {
        val distance = target - scrollOffset
        // Nearby in pixels, as the estimate could be off; farther away without composing everything in between
        if (abs(distance) <= viewportSize) {
            scrollable.scrollBy(distance.toFloat())
            return
        }
        val height = itemHeight()
        if (height <= 0.0) return
        val offset = target.coerceIn(0.0, (contentSize - viewportSize).coerceAtLeast(0.0))
        val index = (offset / height).toInt().coerceIn(0, itemCount - 1)
        scrollToItem(index, (offset - index * height).toInt().coerceAtLeast(0))
    }

    private fun itemHeight(): Double {
        val items = visibleItems()
        if (items.isEmpty()) return 0.0
        return (items.maxOf { it.second } - items.minOf { it.first }).toDouble() / items.size
    }
}

class LazyListScrollMetrics(private val state: LazyListState) : LazyScrollMetrics(state) {
    override val viewportSize get() = state.layoutInfo.viewportSize.height.toDouble()
    override val itemCount get() = state.layoutInfo.totalItemsCount
    override val contentPadding get() = state.layoutInfo.beforeContentPadding + state.layoutInfo.afterContentPadding
    override val firstVisibleIndex get() = state.firstVisibleItemIndex
    override val firstVisibleOffset get() = state.firstVisibleItemScrollOffset
    override fun visibleItems() = state.layoutInfo.visibleItemsInfo.map { it.offset to it.offset + it.size }
    override suspend fun scrollToItem(index: Int, offset: Int) = state.scrollToItem(index, offset)
}

/**
 * Compose's own scrollbar adapter takes every item of a grid for one cell. Grids with full-width rows, such as the
 * songs of an artist, have many more lines than that, so it found the content to fit and hid the scrollbar.
 */
class LazyGridScrollMetrics(private val state: LazyGridState) : LazyScrollMetrics(state) {
    override val viewportSize get() = state.layoutInfo.viewportSize.height.toDouble()
    override val itemCount get() = state.layoutInfo.totalItemsCount
    override val contentPadding get() = state.layoutInfo.beforeContentPadding + state.layoutInfo.afterContentPadding
    override val firstVisibleIndex get() = state.firstVisibleItemIndex
    override val firstVisibleOffset get() = state.firstVisibleItemScrollOffset

    // Items on their way out have no row
    override fun visibleItems() = state.layoutInfo.visibleItemsInfo
        .filter { it.row != LazyGridItemInfo.UnknownRow }
        .map { it.offset.y to it.offset.y + it.size.height }

    override suspend fun scrollToItem(index: Int, offset: Int) = state.scrollToItem(index, offset)
}
