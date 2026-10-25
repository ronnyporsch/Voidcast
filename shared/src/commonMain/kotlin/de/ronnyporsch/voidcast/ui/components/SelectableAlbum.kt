package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.domain.model.Album
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.getPlatform
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import kotlinx.coroutines.launch

/** Loads the songs of albums for the album context menu, in their order; reports failures itself. */
val LocalAlbumSongs = staticCompositionLocalOf<(suspend (List<Album>) -> List<Song>)?> { null }

/** The albums of a list selected with Ctrl- and Shift-clicks (desktop and web), see [SelectableAlbum]. */
@Stable
class AlbumSelection {
    var selectedIds by mutableStateOf(emptySet<String>())
        private set

    fun toggle(album: Album) {
        selectedIds = selectedIds.toggled(album.id)
    }

    /** Shift-click on the album at [index] of [albums], see [selectedThrough]. */
    fun selectThrough(albums: List<Album>, index: Int) {
        if (index in albums.indices) selectedIds = albums.selectedThrough(index, selectedIds) { it.id }
    }

    fun clear() {
        selectedIds = emptySet()
    }

    /** The albums a context menu on [album] acts on: the selection if [album] is part of it, otherwise only [album]. */
    fun menuAlbums(albums: List<Album>, album: Album): List<Album> =
        if (album.id in selectedIds) albums.filter { it.id in selectedIds }.distinctBy { it.id } else listOf(album)
}

@Composable
fun rememberAlbumSelection(): AlbumSelection = remember { AlbumSelection() }

/**
 * Makes [content], an album in a list of [albums] (in the order they are shown), selectable with Ctrl- and
 * Shift-clicks; a plain click clears the selection and calls [onClick]. Right-click (long press on mobile) opens the
 * song context menu with the songs of the selected albums, or only of this one if it isn't selected.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectableAlbum(
    album: Album,
    albums: List<Album>,
    selection: AlbumSelection,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val isMobile = remember { getPlatform().isMobile }
    val density = LocalDensity.current
    val loadSongs = LocalAlbumSongs.current
    val scope = rememberCoroutineScope()
    // Set while the menu is open; its songs are null while they are loading
    var menuAlbums by remember { mutableStateOf<List<Album>?>(null) }
    var menuSongs by remember { mutableStateOf<List<Song>?>(null) }
    var pressPosition by remember { mutableStateOf(Offset.Zero) }
    var ctrlPressed by remember { mutableStateOf(false) }
    var shiftPressed by remember { mutableStateOf(false) }
    var height by remember { mutableStateOf(0) }
    val currentAlbums by rememberUpdatedState(albums)

    val openMenu: () -> Unit = {
        if (loadSongs != null && menuAlbums == null) {
            val targets = selection.menuAlbums(currentAlbums, album)
            menuAlbums = targets
            scope.launch {
                val songs = loadSongs(targets)
                if (songs.isEmpty()) menuAlbums = null else menuSongs = songs
            }
        }
    }
    // The pointer handler below outlives recompositions
    val currentOpenMenu by rememberUpdatedState(openMenu)
    val closeMenu = {
        menuAlbums = null
        menuSongs = null
    }
    val clickModifier = if (isMobile) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = openMenu)
    } else {
        Modifier.clickable {
            when {
                shiftPressed -> selection.selectThrough(currentAlbums, currentAlbums.indexOfFirst { it.id == album.id })
                ctrlPressed -> selection.toggle(album)
                else -> {
                    selection.clear()
                    onClick()
                }
            }
        }
    }

    Box(modifier) {
        Box(
            Modifier
                .background(
                    if (album.id in selection.selectedIds || menuAlbums != null) MaterialTheme.colorScheme.surfaceVariant
                    else Color.Transparent
                )
                .onSizeChanged { height = it.height }
                .pointerInput(loadSongs) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type != PointerEventType.Press) continue
                            pressPosition = event.changes.first().position
                            ctrlPressed = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                            shiftPressed = event.keyboardModifiers.isShiftPressed
                            if (!isMobile && event.buttons.isSecondaryPressed && loadSongs != null) {
                                event.changes.forEach { it.consume() }
                                currentOpenMenu()
                            }
                        }
                    }
                }
                .then(clickModifier),
        ) {
            content()
        }

        if (menuAlbums != null && menuSongs == null) {
            CircularProgressIndicator(
                color = AccentColor,
                strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center).size(32.dp),
            )
        }

        // The menu is placed below its anchor, so move it up to where the album got clicked
        SongContextMenu(
            expanded = menuSongs != null,
            onDismiss = closeMenu,
            songs = menuSongs.orEmpty(),
            offset = with(density) { DpOffset(pressPosition.x.toDp(), (pressPosition.y - height).toDp()) },
            title = menuAlbums?.let { albums ->
                val songCount = menuSongs?.size ?: 0
                val songs = "$songCount song${if (songCount != 1) "s" else ""}"
                albums.singleOrNull()?.let { "${it.name} • $songs" } ?: "${albums.size} albums selected • $songs"
            },
            showFavorites = false,
        )
    }
}
