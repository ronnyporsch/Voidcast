package de.ronnyporsch.voidcast.ui.screen.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.ui.theme.AccentColor

/** Plain progress bar for a slider: the Material track has a stop indicator dot and a thumb gap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlainSliderTrack(
    sliderState: SliderState,
    inactiveColor: Color,
    height: Dp = 4.dp,
) {
    val range = sliderState.valueRange
    val fraction = if (range.endInclusive > range.start) {
        ((sliderState.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    } else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(inactiveColor),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(AccentColor),
        )
    }
}
