package de.ronnyporsch.voidcast

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.platform.GlobalShortcuts
import de.ronnyporsch.voidcast.platform.SingleInstance
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.skia.Image

fun main() {
    if (!SingleInstance.acquire()) return
    app()
}

private fun app() = application {
    val currentSong by AppSession.get().currentSong.collectAsState(null)
    val icon = remember {
        val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")!!.use { it.readBytes() }
        BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
    }
    val windowState = rememberWindowState(placement = WindowPlacement.Maximized, width = 1024.dp, height = 768.dp)
    Window(
        onCloseRequest = {
            AppSession.get().release()
            exitApplication()
        },
        title = currentSong?.let { if (it.artist.isBlank()) it.title else "${it.artist} - ${it.title}" } ?: "Voidcast",
        icon = icon,
        state = windowState,
        onPreviewKeyEvent = GlobalShortcuts::onKeyEvent,
        onKeyEvent = GlobalShortcuts::onUnhandledKeyEvent,
    ) {
        // Another start of the app brings this window to the front instead
        LaunchedEffect(Unit) {
            SingleInstance.activationRequests.collect {
                windowState.isMinimized = false
                // Window managers tend to ignore toFront() alone; briefly staying on top gets the window raised
                window.isAlwaysOnTop = true
                window.toFront()
                window.requestFocus()
                window.isAlwaysOnTop = false
            }
        }
        App()
    }
}
