package de.ronnyporsch.voidcast.platform

import kotlinx.browser.document
import org.w3c.dom.events.KeyboardEvent

private var installed = false

actual fun installGlobalShortcutListener() {
    if (installed) return
    installed = true
    // Capture phase, so the browser's own Ctrl+K (focus web search) and Ctrl+F (find) are suppressed before anything
    // else sees them
    document.addEventListener("keydown", { event ->
        event as KeyboardEvent
        // Space pauses or resumes, unless it's typed into a text field; held down it doesn't toggle on and on
        if (event.key == " " && !event.ctrlKey && !event.metaKey && !event.altKey && !isTextInputFocused()) {
            if (!event.repeat) GlobalShortcuts.requestPlayPause()
            event.preventDefault()
            event.stopPropagation()
            return@addEventListener
        }
        // Right/Left seek, unless a text field moves its cursor with them
        if ((event.key == "ArrowRight" || event.key == "ArrowLeft") && !event.ctrlKey && !event.metaKey &&
            !event.shiftKey && !event.altKey && !isTextInputFocused()
        ) {
            GlobalShortcuts.requestSeek(forward = event.key == "ArrowRight")
            event.preventDefault()
            event.stopPropagation()
            return@addEventListener
        }
        // F enters or leaves fullscreen for a video, unless it's typed into a text field
        if (event.key.lowercase() == "f" && !event.ctrlKey && !event.metaKey && !event.altKey && !isTextInputFocused()) {
            if (event.repeat || GlobalShortcuts.requestToggleVideoFullscreen()) {
                event.preventDefault()
                event.stopPropagation()
            }
            return@addEventListener
        }
        if (!event.ctrlKey && !event.metaKey) return@addEventListener
        val handled = when (event.key.lowercase()) {
            "k" -> {
                GlobalShortcuts.requestSearchFocus()
                true
            }
            "f" -> GlobalShortcuts.requestFilterFocus()
            // Text fields move the cursor by words with them; a focused one has its input element focused
            "arrowright", "arrowleft" -> !event.shiftKey && !event.altKey && !isTextInputFocused() &&
                GlobalShortcuts.requestSkip(next = event.key == "ArrowRight")
            else -> false
        }
        if (handled) {
            event.preventDefault()
            event.stopPropagation()
        }
    }, true)
}

private fun isTextInputFocused(): Boolean =
    document.activeElement?.tagName?.uppercase().let { it == "INPUT" || it == "TEXTAREA" }
