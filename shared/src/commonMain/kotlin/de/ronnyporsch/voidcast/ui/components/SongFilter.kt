package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.platform.GlobalShortcuts
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SearchPlaceholderGray

private val WHITESPACE = Regex("\\s+")

/**
 * A filter query split into its words once, to match many items against it: an item matches if every word is part
 * of one of its fields, ignoring case. A blank query matches everything.
 */
class TextFilter(query: String) {
    private val words = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }

    val isBlank: Boolean get() = words.isEmpty()

    fun matches(vararg fields: String): Boolean = words.all { word -> fields.any { it.contains(word, ignoreCase = true) } }

    /** Matches the song's title, artist and album. */
    fun matches(song: Song): Boolean = matches(song.title, song.artist, song.album)
}

/** The items matching [query] by [matches], see [TextFilter]; the list itself for a blank query. */
inline fun <T> List<T>.matching(query: String, matches: TextFilter.(T) -> Boolean): List<T> {
    val textFilter = TextFilter(query)
    return if (textFilter.isBlank) this else filter { textFilter.matches(it) }
}

/** Whether every word of [query] is part of one of [fields], ignoring case; a blank query matches everything. */
fun matchesFilter(query: String, vararg fields: String): Boolean = TextFilter(query).matches(*fields)

/** Whether [query] is part of the song's title, artist or album, ignoring case; a blank query matches every song. */
fun Song.matches(query: String): Boolean = TextFilter(query).matches(this)

/** The songs matching [query] together with their positions in the whole list, see [matches]. */
fun List<Song>.filtered(query: String): List<IndexedValue<Song>> {
    val filter = TextFilter(query)
    return if (filter.isBlank) withIndex().toList() else withIndex().filter { filter.matches(it.value) }
}

/** The songs matching [query], see [matches]. */
fun List<Song>.matchingSongs(query: String): List<Song> = matching(query) { matches(it) }

/** Focuses a [SongFilterField] on Ctrl+F, see [rememberSongFilterFocus]. */
class SongFilterFocus {
    val requester = FocusRequester()
    /** Set until the field got focused, which may need it to be scrolled into view first. */
    var requested by mutableStateOf(false)
}

/** Ctrl+F (see [GlobalShortcuts]) scrolls [listState] to the [SongFilterField] at [itemIndex] and focuses it. */
@Composable
fun rememberSongFilterFocus(listState: LazyListState, itemIndex: Int): SongFilterFocus {
    val focus = remember { SongFilterFocus() }
    LaunchedEffect(listState, itemIndex) {
        GlobalShortcuts.focusFilter.collect {
            focus.requested = true
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == itemIndex }) listState.scrollToItem(itemIndex)
        }
    }
    return focus
}

/** The "x" at the end of search and filter fields; keeps the normal pointer rather than the text field's I-beam. */
@Composable
fun ClearSearchButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.pointerHoverIcon(PointerIcon.Default)) {
        Icon(Icons.Default.Close, contentDescription = "Clear search")
    }
}

/** Text field to search the songs of a list, e.g. a playlist, or another list such as the artists. */
@Composable
fun SongFilterField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focus: SongFilterFocus? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
) {
    // Also once the field got composed after scrolling it into view
    LaunchedEffect(focus?.requested) {
        if (focus?.requested == true) {
            focus.requester.requestFocus()
            focus.requested = false
        }
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder, color = SearchPlaceholderGray) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (query.isEmpty()) null else {
            { ClearSearchButton(onClick = { onQueryChange("") }) }
        },
        singleLine = true,
        shape = RoundedCornerShape(0.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AccentColor,
            cursorColor = AccentColor,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding)
            .then(if (focus != null) Modifier.focusRequester(focus.requester) else Modifier),
    )
}
