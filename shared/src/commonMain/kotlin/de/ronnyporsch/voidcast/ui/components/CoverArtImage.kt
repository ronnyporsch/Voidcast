package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SpotifySurface

/** Size of cover art to request for [EnlargedImageDialog]. */
const val ENLARGED_IMAGE_SIZE = 1200

/** [enlargedUrl]: clicking the image shows it in this (larger) version over the whole window, see [EnlargedImageDialog]. */
@Composable
fun CoverArtImage(
    url: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 0.dp,
    placeholderText: String = "\u266B",
    placeholderFontSize: TextUnit = 20.sp,
    enlargedUrl: String? = null,
) {
    if (url != null) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(cornerRadius))
                .background(SpotifySurface)
                .enlargeOnClick(enlargedUrl),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(cornerRadius))
                .background(SpotifySurface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = placeholderText,
                fontSize = placeholderFontSize,
                color = AccentColor,
            )
        }
    }
}

/** [enlargedUrl]: see [CoverArtImage]. */
@Composable
fun ArtistImage(
    url: String?,
    artistName: String,
    modifier: Modifier = Modifier,
    enlargedUrl: String? = null,
) {
    if (url != null) {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(SpotifySurface)
                .enlargeOnClick(enlargedUrl),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url,
                contentDescription = artistName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    } else {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(SpotifySurface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = artistName.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = AccentColor,
            )
        }
    }
}

/** Clicking shows the image at [url] enlarged; null does nothing. */
@Composable
private fun Modifier.enlargeOnClick(url: String?): Modifier {
    if (url == null) return this
    var enlarged by remember { mutableStateOf(false) }
    if (enlarged) EnlargedImageDialog(url, onDismiss = { enlarged = false })
    return pointerHoverIcon(PointerIcon.Hand).clickable { enlarged = true }
}

/** The image at [url] as large as it fits into the window; a click anywhere closes it. */
@Composable
fun EnlargedImageDialog(url: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = null, indication = null, onClick = onDismiss)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.sizeIn(maxWidth = 1000.dp, maxHeight = 1000.dp).fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
