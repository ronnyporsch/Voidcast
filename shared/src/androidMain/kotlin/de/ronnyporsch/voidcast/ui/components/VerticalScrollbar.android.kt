package de.ronnyporsch.voidcast.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.ui.theme.ScrollbarHoverColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
actual fun VerticalScrollbar(
    modifier: Modifier,
    state: LazyListState
) {
    TouchScrollbar(modifier, remember(state) { LazyListScrollMetrics(state) }, state.isScrollInProgress)
}

@Composable
actual fun VerticalScrollbar(
    modifier: Modifier,
    state: LazyGridState
) {
    TouchScrollbar(modifier, remember(state) { LazyGridScrollMetrics(state) }, state.isScrollInProgress)
}

@Composable
actual fun VerticalScrollbar(
    modifier: Modifier,
    state: ScrollState
) {
    val metrics = remember(state) {
        object : ScrollMetrics {
            override val viewportSize get() = state.viewportSize.toDouble()
            override val scrollOffset get() = state.value.toDouble()
            override val contentSize get() = (state.maxValue + state.viewportSize).toDouble()
            override suspend fun scrollTo(target: Double) { state.scrollTo(target.toInt()) }
        }
    }
    TouchScrollbar(modifier, metrics, state.isScrollInProgress)
}

/** How long the thumb stays after scrolling stopped, to have time to grab it. */
private const val THUMB_LINGER_MS = 1500L

/**
 * A thumb at the edge that shows while scrolling and can be dragged to jump through long lists. Hidden otherwise, so
 * it doesn't take taps from what's at the end of the rows.
 */
@Composable
private fun TouchScrollbar(modifier: Modifier, metrics: ScrollMetrics, isScrolling: Boolean) {
    var isDragging by remember { mutableStateOf(false) }
    var isShown by remember { mutableStateOf(false) }
    LaunchedEffect(isScrolling, isDragging) {
        if (isScrolling || isDragging) {
            isShown = true
        } else {
            delay(THUMB_LINGER_MS)
            isShown = false
        }
    }
    val alpha by animateFloatAsState(if (isShown) 1f else 0f)
    if (alpha == 0f) return

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val minThumbHeight = with(density) { 48.dp.toPx() }
    BoxWithConstraints(modifier.width(24.dp).fillMaxHeight().graphicsLayer { this.alpha = alpha }) {
        val trackHeight = constraints.maxHeight.toFloat()
        fun thumbHeight(): Float {
            val share = (metrics.viewportSize / metrics.contentSize).toFloat()
            return (trackHeight * share).coerceIn(minThumbHeight.coerceAtMost(trackHeight), trackHeight)
        }
        fun maxScroll() = metrics.contentSize - metrics.viewportSize
        fun thumbTop(): Float = (trackHeight - thumbHeight()) * (metrics.scrollOffset / maxScroll()).toFloat().coerceIn(0f, 1f)

        if (maxScroll() > 0 && trackHeight > 0) {
            Box(
                Modifier
                    .offset { IntOffset(0, thumbTop().toInt()) }
                    .width(24.dp)
                    .height(with(density) { thumbHeight().toDp() })
                    .pointerInput(metrics, trackHeight) {
                        var top = 0f
                        detectVerticalDragGestures(
                            onDragStart = {
                                isDragging = true
                                top = thumbTop()
                            },
                            onDragEnd = { isDragging = false },
                            onDragCancel = { isDragging = false },
                        ) { change, dragAmount ->
                            change.consume()
                            val range = trackHeight - thumbHeight()
                            top = (top + dragAmount).coerceIn(0f, range)
                            val target = if (range > 0) top / range * maxScroll() else 0.0
                            // A newer one cancels this one, which is fine as only the latest position matters
                            scope.launch { metrics.scrollTo(target) }
                        }
                    },
            ) {
                Box(Modifier.align(Alignment.CenterEnd).width(4.dp).fillMaxHeight().background(ScrollbarHoverColor))
            }
        }
    }
}
