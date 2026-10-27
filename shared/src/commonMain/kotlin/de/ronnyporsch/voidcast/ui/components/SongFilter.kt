package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.ui.theme.AccentColor

/** Whether [query] is part of the song's title, artist or album, ignoring case; a blank query matches every song. */
fun Song.matches(query: String): Boolean {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return words.all { word ->
        title.contains(word, ignoreCase = true) ||
            artist.contains(word, ignoreCase = true) ||
            album.contains(word, ignoreCase = true)
    }
}

/** The songs matching [query] together with their positions in the whole list, see [matches]. */
fun List<Song>.filtered(query: String): List<IndexedValue<Song>> =
    withIndex().filter { it.value.matches(query) }

/** Text field to search the songs of a list, e.g. a playlist. */
@Composable
fun SongFilterField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder) },
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
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
