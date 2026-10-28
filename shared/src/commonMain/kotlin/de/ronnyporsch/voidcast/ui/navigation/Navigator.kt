package de.ronnyporsch.voidcast.ui.navigation

import de.ronnyporsch.voidcast.domain.model.Genre
import de.ronnyporsch.voidcast.ui.components.ScrollPositions
import de.ronnyporsch.voidcast.ui.screen.library.LibrarySelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class Screen {
    data object Login : Screen()
    /** [selection] is what the detail pane shows (desktop), null until it is known. */
    data class Library(val selection: LibrarySelection? = null) : Screen()
    data class ArtistAlbums(val artistId: String?, val artistName: String) : Screen()
    data class AlbumSongs(val albumId: String) : Screen()
    data class PlaylistSongs(val playlistId: String) : Screen()
    data object FavoriteSongs : Screen()
    data class GenreSongs(val genre: Genre) : Screen()
    data object Player : Screen()
    data class Queue(val tab: QueueTab) : Screen()
    data object Search : Screen()
    data class Downloads(val tab: DownloadsTab) : Screen()
    /** The music videos of a Jellyfin server; [libraryId] is the library whose tab is shown, null for the first. */
    data class MusicVideos(val libraryId: String? = null) : Screen()
    data object Settings : Screen()
}

enum class QueueTab(val title: String) {
    Queue("Queue"),
    RecentlyPlayed("Recently played"),
}

enum class DownloadsTab(val title: String) {
    /** The songs downloaded into the server's library in this session. */
    YouTube("YouTube Music"),
    /** Android: what is downloaded for offline playback. */
    Device("On this device"),
}

/**
 * A visit of [screen] in the history. [scrollPositions] keeps how far its lists were scrolled, so going back or
 * forward to it shows them as they were; [id] tells visits of equal screens apart.
 */
class BackStackEntry(
    val screen: Screen,
    val id: Long,
    val scrollPositions: ScrollPositions = ScrollPositions(),
)

class Navigator(initialScreen: Screen = Screen.Login) {
    private var nextId = 0L
    private val backStack = mutableListOf(entry(initialScreen))
    private val forwardStack = mutableListOf<BackStackEntry>()

    private val _currentEntry = MutableStateFlow(backStack.last())
    val currentEntry: StateFlow<BackStackEntry> = _currentEntry.asStateFlow()

    private val _currentScreen = MutableStateFlow(initialScreen)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    fun push(screen: Screen) {
        if (_currentScreen.value == screen) return
        forwardStack.clear()
        backStack.add(entry(screen))
        show(backStack.last())
    }

    fun pop(): Boolean {
        if (backStack.size <= 1) return false
        val current = backStack.removeLast()
        forwardStack.add(current)
        show(backStack.last())
        return true
    }

    fun forward(): Boolean {
        if (forwardStack.isEmpty()) return false
        val entry = forwardStack.removeLast()
        backStack.add(entry)
        show(entry)
        return true
    }

    /** Replaces the current screen without a way back to it, e.g. switching tabs. It keeps its scroll positions. */
    fun replace(screen: Screen) {
        val current = backStack.last()
        backStack[backStack.lastIndex] = BackStackEntry(screen, current.id, current.scrollPositions)
        show(backStack.last())
    }

    fun replaceAll(screen: Screen) {
        backStack.clear()
        forwardStack.clear()
        backStack.add(entry(screen))
        show(backStack.last())
    }

    fun canGoBack(): Boolean = backStack.size > 1
    fun canGoForward(): Boolean = forwardStack.isNotEmpty()

    private fun entry(screen: Screen) = BackStackEntry(screen, nextId++)

    private fun show(entry: BackStackEntry) {
        _currentEntry.value = entry
        _currentScreen.value = entry.screen
    }
}
