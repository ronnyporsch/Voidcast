package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first

/** [index] and [offset] of the first visible item, or for plain scrollable content the scroll value as [index]. */
data class ScrollPosition(val index: Int, val offset: Int = 0)

/** How far the lists of a back stack entry were scrolled, by name; see [RememberScrollPosition]. */
class ScrollPositions {
    private val positions = mutableMapOf<String, ScrollPosition>()

    operator fun get(name: String): ScrollPosition? = positions[name]

    operator fun set(name: String, position: ScrollPosition) {
        positions[name] = position
    }
}

/** The scroll positions of the shown back stack entry; null where there is no navigation. */
val LocalScrollPositions = staticCompositionLocalOf<ScrollPositions?> { null }

/**
 * Keeps the scroll position of [state] when the screen is left and restores it when navigating back or forward to
 * it. [name] tells several lists of one screen apart.
 */
@Composable
fun RememberScrollPosition(state: LazyListState, name: String = "list") = RememberScrollPosition(
    state = state,
    name = name,
    current = { ScrollPosition(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) },
    isScrolling = { state.isScrollInProgress },
    canRestore = { state.layoutInfo.totalItemsCount > it.index },
    restore = { state.scrollToItem(it.index, it.offset) },
)

/** See [RememberScrollPosition]. */
@Composable
fun RememberScrollPosition(state: LazyGridState, name: String = "grid") = RememberScrollPosition(
    state = state,
    name = name,
    current = { ScrollPosition(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) },
    isScrolling = { state.isScrollInProgress },
    canRestore = { state.layoutInfo.totalItemsCount > it.index },
    restore = { state.scrollToItem(it.index, it.offset) },
)

/** See [RememberScrollPosition]. */
@Composable
fun RememberScrollPosition(state: ScrollState, name: String = "scroll") = RememberScrollPosition(
    state = state,
    name = name,
    current = { ScrollPosition(state.value) },
    isScrolling = { state.isScrollInProgress },
    // The maximum is only known once the content got laid out
    canRestore = { state.viewportSize > 0 && state.maxValue >= it.index },
    restore = { state.scrollTo(it.index) },
)

@Composable
private fun RememberScrollPosition(
    state: Any,
    name: String,
    current: () -> ScrollPosition,
    isScrolling: () -> Boolean,
    canRestore: (ScrollPosition) -> Boolean,
    restore: suspend (ScrollPosition) -> Unit,
) {
    val positions = LocalScrollPositions.current ?: return
    // Until it got restored, leaving again keeps the saved position rather than the one shown meanwhile
    val pending = remember(positions, state, name) { mutableStateOf(positions[name]) }
    LaunchedEffect(positions, state, name) {
        val saved = pending.value ?: return@LaunchedEffect
        // The content may still be loading; scrolling meanwhile gives up on restoring
        snapshotFlow { isScrolling() || canRestore(saved) }.first { it }
        if (!isScrolling()) restore(saved)
        pending.value = null
    }
    DisposableEffect(positions, state, name) {
        onDispose { positions[name] = pending.value ?: current() }
    }
}
