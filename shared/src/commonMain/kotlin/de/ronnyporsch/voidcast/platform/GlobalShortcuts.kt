package de.ronnyporsch.voidcast.platform

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-wide keyboard shortcuts that work no matter which element has focus (or whether any has).
 * Compose only routes key events to the focused node, so platforms feed them in from the window:
 * desktop via `Window(onPreviewKeyEvent, onKeyEvent)`, web via a document listener ([installGlobalShortcutListener]).
 */
object GlobalShortcuts {
    private val _focusSearch = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val focusSearch: SharedFlow<Unit> = _focusSearch.asSharedFlow()

    private val _focusFilter = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Focuses the filter of the songs shown, e.g. those of a playlist; only collected while there is one. */
    val focusFilter: SharedFlow<Unit> = _focusFilter.asSharedFlow()

    private val _skip = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    /** Skips to the next song (true) or the previous one (false). */
    val skip: SharedFlow<Boolean> = _skip.asSharedFlow()

    /** Ctrl+K focuses the search, Ctrl+F the filter of the songs shown (Cmd on macOS). */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (!event.isCtrlPressed && !event.isMetaPressed) return false
        return when (event.key) {
            Key.K -> {
                requestSearchFocus()
                true
            }
            Key.F -> requestFilterFocus()
            else -> false
        }
    }

    /**
     * Ctrl+Right/Left skips to the next/previous song (Cmd on macOS). Only for key events nothing else handled, as
     * text fields move the cursor by words with them.
     */
    fun onUnhandledKeyEvent(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (!event.isCtrlPressed && !event.isMetaPressed || event.isShiftPressed || event.isAltPressed) return false
        return when (event.key) {
            Key.DirectionRight -> requestSkip(next = true)
            Key.DirectionLeft -> requestSkip(next = false)
            else -> false
        }
    }

    fun requestSearchFocus() {
        _focusSearch.tryEmit(Unit)
    }

    /** Returns false if no filter is shown, so the shortcut can do what it does otherwise, e.g. the browser's search. */
    fun requestFilterFocus(): Boolean {
        if (_focusFilter.subscriptionCount.value == 0) return false
        _focusFilter.tryEmit(Unit)
        return true
    }

    fun requestSkip(next: Boolean): Boolean {
        _skip.tryEmit(next)
        return true
    }
}

/** Hooks [GlobalShortcuts] into platforms whose window isn't reachable from the app's entry point. */
expect fun installGlobalShortcutListener()
