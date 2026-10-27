package de.ronnyporsch.voidcast

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.platform.GlobalShortcuts
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.skia.Image

fun main() = application {
    val currentSong by AppSession.get().currentSong.collectAsState(null)
    val icon = remember {
        val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")!!.use { it.readBytes() }
        BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
    }
    Window(
        onCloseRequest = {
            AppSession.get().release()
            exitApplication()
        },
        title = currentSong?.let { if (it.artist.isBlank()) it.title else "${it.artist} - ${it.title}" } ?: "Voidcast",
        icon = icon,
        state = rememberWindowState(placement = WindowPlacement.Maximized, width = 1024.dp, height = 768.dp),
        onPreviewKeyEvent = GlobalShortcuts::onKeyEvent,
        onKeyEvent = GlobalShortcuts::onUnhandledKeyEvent,
    ) {
        App()
    }
}
