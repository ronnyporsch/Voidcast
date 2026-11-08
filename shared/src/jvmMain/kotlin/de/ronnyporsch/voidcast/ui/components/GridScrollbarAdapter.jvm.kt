package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** See [LazyGridScrollMetrics]. */
@Composable
internal fun rememberGridScrollbarAdapter(state: LazyGridState): ScrollbarAdapter = remember(state) {
    val metrics = LazyGridScrollMetrics(state)
    object : ScrollbarAdapter {
        override val scrollOffset get() = metrics.scrollOffset
        override val contentSize get() = metrics.contentSize
        override val viewportSize get() = metrics.viewportSize
        override suspend fun scrollTo(scrollOffset: Double) = metrics.scrollTo(scrollOffset)
    }
}
