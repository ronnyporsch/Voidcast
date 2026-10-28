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
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.platform.GlobalShortcuts
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SearchPlaceholderGray

/** Whether every word of [query] is part of one of [fields], ignoring case; a blank query matches everything. */
fun matchesFilter(query: String, vararg fields: String): Boolean {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return words.all { word -> fields.any { it.contains(word, ignoreCase = true) } }
}

/** Whether [query] is part of the song's title, artist or album, ignoring case; a blank query matches every song. */
fun Song.matches(query: String): Boolean = matchesFilter(query, title, artist, album)

/** The songs matching [query] together with their positions in the whole list, see [matches]. */
fun List<Song>.filtered(query: String): List<IndexedValue<Song>> =
    withIndex().filter { it.value.matches(query) }

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
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                }
            }
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
