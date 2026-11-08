package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.ui.theme.ScrollbarColor
import de.ronnyporsch.voidcast.ui.theme.ScrollbarHoverColor

private fun scrollbarStyle() = defaultScrollbarStyle().copy(
    thickness = 12.dp,
    unhoverColor = ScrollbarColor,
    hoverColor = ScrollbarHoverColor,
)

@Composable
actual fun VerticalScrollbar(
    modifier: Modifier,
    state: LazyListState
) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier,
        style = scrollbarStyle()
    )
}

@Composable
actual fun VerticalScrollbar(
    modifier: Modifier,
    state: LazyGridState
) {
    VerticalScrollbar(
        adapter = rememberGridScrollbarAdapter(state),
        modifier = modifier,
        style = scrollbarStyle()
    )
}

@Composable
actual fun VerticalScrollbar(
    modifier: Modifier,
    state: ScrollState
) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier,
        style = scrollbarStyle()
    )
}
