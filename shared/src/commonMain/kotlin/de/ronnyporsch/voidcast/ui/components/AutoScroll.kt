package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * Middle-click autoscroll, as in browsers: clicking the mouse wheel over a list sets an anchor, and the list then
 * scrolls the faster the farther the pointer is above or below it, until the next click. Holding the wheel and
 * releasing it after moving ends it on release instead. Lists take part through [autoScrollTarget].
 */
class AutoScroll {
    internal class Target(val state: ScrollableState) {
        var coordinates: LayoutCoordinates? = null
    }

    internal val targets = mutableListOf<Target>()

    /** Where the current autoscroll started, in the host's coordinates; null if there is none. */
    internal var anchor by mutableStateOf<Offset?>(null)

    internal var hostCoordinates: LayoutCoordinates? = null

    /** The innermost target at [position] (in the host's coordinates). */
    internal fun targetAt(position: Offset): Target? {
        val host = hostCoordinates?.takeIf { it.isAttached } ?: return null
        val inWindow = position + host.positionInWindow()
        return targets
            .mapNotNull { target -> target.coordinates?.takeIf { it.isAttached }?.boundsInWindow()?.let { target to it } }
            .filter { (_, bounds) -> bounds.contains(inWindow) }
            .minByOrNull { (_, bounds) -> bounds.width * bounds.height }
            ?.first
    }
}

val LocalAutoScroll = staticCompositionLocalOf<AutoScroll?> { null }

/**
 * Makes [state] scroll by middle-click autoscroll, see [AutoScroll], within the bounds of this element's parent: put
 * on a list's scrollbar, which shares the list's parent.
 */
fun Modifier.autoScrollTarget(state: ScrollableState): Modifier = composed {
    val autoScroll = LocalAutoScroll.current ?: return@composed this
    val target = remember(state) { AutoScroll.Target(state) }
    DisposableEffect(autoScroll, target) {
        autoScroll.targets += target
        onDispose { autoScroll.targets -= target }
    }
    onGloballyPositioned { target.coordinates = it.parentLayoutCoordinates ?: it }
}

/** Handles middle-click autoscroll of the [autoScrollTarget]s within this element, and draws its anchor. */
fun Modifier.autoScrollHost(autoScroll: AutoScroll, indicatorColor: Color): Modifier = this
    .onGloballyPositioned { autoScroll.hostCoordinates = it }
    .pointerInput(autoScroll) { handleAutoScroll(autoScroll) }
    .drawWithContent {
        drawContent()
        val anchor = autoScroll.anchor ?: return@drawWithContent
        // An arrow up and one down, outlined to stand out on any content
        val halfWidth = 7.dp.toPx()
        val height = 8.dp.toPx()
        val gap = 3.dp.toPx()
        val arrows = Path().apply {
            moveTo(anchor.x, anchor.y - gap - height)
            lineTo(anchor.x + halfWidth, anchor.y - gap)
            lineTo(anchor.x - halfWidth, anchor.y - gap)
            close()
            moveTo(anchor.x, anchor.y + gap + height)
            lineTo(anchor.x + halfWidth, anchor.y + gap)
            lineTo(anchor.x - halfWidth, anchor.y + gap)
            close()
        }
        drawPath(arrows, Color.Black.copy(alpha = 0.6f), style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
        drawPath(arrows, indicatorColor)
    }

private suspend fun PointerInputScope.handleAutoScroll(autoScroll: AutoScroll) = coroutineScope {
    val deadZone = 12.dp.toPx()
    // Pixels per second per pixel the pointer is beyond the dead zone
    val speedFactor = 8f
    awaitPointerEventScope {
        var wasTertiaryPressed = false
        while (true) {
            // The initial pass sees clicks before the lists' items do, so the clicks of autoscroll don't click them
            val press = awaitPointerEvent(PointerEventPass.Initial)
            val middlePressed = press.type == PointerEventType.Press && press.buttons.isTertiaryPressed && !wasTertiaryPressed
            wasTertiaryPressed = press.buttons.isTertiaryPressed
            if (!middlePressed) continue
            val start = press.changes.first().position
            val target = autoScroll.targetAt(start) ?: continue
            press.changes.forEach { it.consume() }

            var pointer = start
            autoScroll.anchor = start
            val scrolling = launch {
                target.state.scroll {
                    var last = withFrameNanos { it }
                    while (true) {
                        val now = withFrameNanos { it }
                        val offset = pointer.y - start.y
                        val beyond = (abs(offset) - deadZone).coerceAtLeast(0f)
                        scrollBy(sign(offset) * beyond * speedFactor * (now - last) / 1_000_000_000f)
                        last = now
                    }
                }
            }

            // Holding the wheel and moving the pointer, the release ends autoscroll; a click keeps it on
            var held = true
            var moved = false
            while (scrolling.isActive) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!scrolling.isActive) break
                event.changes.firstOrNull()?.let { pointer = it.position }
                if ((pointer - start).getDistance() > deadZone) moved = true
                when (event.type) {
                    // The wheel scrolls as usual, which ends autoscroll
                    PointerEventType.Scroll -> break
                    PointerEventType.Release -> if (held) {
                        held = false
                        event.changes.forEach { it.consume() }
                        if (moved) break
                    }
                    PointerEventType.Press -> {
                        // Swallows the click that ends autoscroll
                        event.changes.forEach { it.consume() }
                        do {
                            val next = awaitPointerEvent(PointerEventPass.Initial)
                            next.changes.forEach { it.consume() }
                        } while (next.changes.any { it.pressed })
                        break
                    }
                    else -> {}
                }
            }
            wasTertiaryPressed = false
            scrolling.cancel()
            autoScroll.anchor = null
        }
    }
}
