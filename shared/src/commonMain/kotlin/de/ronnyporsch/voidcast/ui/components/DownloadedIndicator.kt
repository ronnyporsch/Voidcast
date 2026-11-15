package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.data.offline.DownloadedCollections
import de.ronnyporsch.voidcast.ui.theme.AccentColor

/** The artists and albums downloaded completely on this device; lists mark them with a [DownloadedIndicator]. */
val LocalDownloadedCollections = compositionLocalOf { DownloadedCollections() }

/** Marks an artist or album whose songs are all downloaded on this device; [onCover] where it lies on a cover. */
@Composable
fun DownloadedIndicator(modifier: Modifier = Modifier, size: Dp = 18.dp, onCover: Boolean = false) {
    Icon(
        imageVector = Icons.Default.DownloadDone,
        contentDescription = "Downloaded",
        tint = AccentColor,
        modifier = if (onCover) {
            modifier.size(size).background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(3.dp)
        } else {
            modifier.size(size)
        },
    )
}
