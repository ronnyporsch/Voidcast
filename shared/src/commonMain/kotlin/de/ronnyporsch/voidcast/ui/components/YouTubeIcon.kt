package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val YouTubeRed = Color(0xFFFF0033)

/** Rounded rectangle with a play triangle cut out; Material has no YouTube icon. */
private val YouTubeVector: ImageVector by lazy {
    ImageVector.Builder("YouTube", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(5f, 5f)
            horizontalLineTo(19f)
            arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 23f, 9f)
            verticalLineTo(15f)
            arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 19f, 19f)
            horizontalLineTo(5f)
            arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 1f, 15f)
            verticalLineTo(9f)
            arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 5f, 5f)
            close()
            moveTo(10f, 8.8f)
            verticalLineTo(15.2f)
            lineTo(15.8f, 12f)
            close()
        }
    }.build()
}

/** Marks songs, albums and artists that are streamed from YouTube Music rather than being part of the library. */
@Composable
fun YouTubeIcon(modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Icon(YouTubeVector, contentDescription = "YouTube Music", tint = YouTubeRed, modifier = modifier.size(size))
}
