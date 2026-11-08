package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.defaultScrollbarStyle
import de.ronnyporsch.voidcast.ui.theme.ScrollbarColor
import de.ronnyporsch.voidcast.ui.theme.ScrollbarHoverColor

internal fun webScrollbarStyle() = defaultScrollbarStyle().copy(
    unhoverColor = ScrollbarColor,
    hoverColor = ScrollbarHoverColor,
)
