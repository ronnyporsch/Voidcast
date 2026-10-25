package de.ronnyporsch.voidcast.ui.screen.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.ui.components.*
import de.ronnyporsch.voidcast.ui.theme.AccentColor

/** Mobile: the favorite songs on their own screen; tapping a song plays the list from there. */
@Composable
fun FavoriteSongsScreen(
    songs: List<Song>,
    onBack: () -> Unit,
    onPlay: (songs: List<Song>, startIndex: Int, shuffle: Boolean?) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    currentSongId: String? = null,
) {
    val listState = rememberLazyListState()
    val headerState = rememberCollapsingHeaderState(listState)
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    val touchSelection = rememberTouchSelection(
        items = songs,
        selectedIds = selectedIds,
        onChange = { selectedIds = it },
        id = { it.id },
        key = ::favoriteSongKey,
    )
    TouchSelectionBar(touchSelection, songs = { touchSelection.selectedItems })
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        CollapsingTopBar(
            onBack = onBack,
            state = headerState,
            title = FAVORITE_SONGS_TITLE,
            image = { modifier -> FavoritesImage(modifier) },
        )
        FavoriteSongsList(
            songs = songs,
            state = listState,
            headerState = headerState,
            onSongClick = { _, index -> onPlay(songs, index, null) },
            onSongDoubleClick = { _, index -> onPlay(songs, index, null) },
            isSelected = { it in selectedIds },
            isPlaying = { it == currentSongId },
            onArtistClick = onArtistClick,
            onAlbumClick = onAlbumClick,
            onPlayAll = { if (songs.isNotEmpty()) onPlay(songs, 0, null) },
            onShuffle = { if (songs.isNotEmpty()) onPlay(songs, 0, true) },
            touchSelection = touchSelection,
        )
    }
}

const val FAVORITE_SONGS_TITLE = "Favorite songs"

private fun favoriteSongKey(index: Int, song: Song): Any = "${song.id}::$index"

/** Stands in for a cover: a heart on a green gradient. */
@Composable
fun FavoritesImage(modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier.background(Brush.linearGradient(listOf(AccentColor, Color(0xFF4F0B4F)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Favorite,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(maxWidth * 0.45f),
        )
    }
}

/** Header, play buttons and rows of the favorite songs; each row has a heart to unmark the song. */
@Composable
fun FavoriteSongsList(
    songs: List<Song>,
    state: LazyListState,
    headerState: CollapsingHeaderState,
    onSongClick: (Song, Int) -> Unit,
    onSongDoubleClick: (Song, Int) -> Unit,
    isSelected: (String) -> Boolean,
    isPlaying: (String) -> Boolean,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    onSongCtrlClick: (Song) -> Unit = {},
    contextMenuSongs: (List<Song>, Song) -> List<Song> = { _, song -> listOf(song) },
    onSongShiftClick: (Song, Int) -> Unit = { _, _ -> },
    /** Mobile: selects the songs by touch. */
    touchSelection: TouchSelection<Song>? = null,
) {
    RememberScrollPosition(state, "favoriteSongs")
    Box(modifier) {
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(bottom = 100.dp),
            modifier = Modifier
                .fillMaxSize()
                .then(if (touchSelection != null) Modifier.touchSelection(touchSelection, state) else Modifier)
                .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val currentIndex = songs.indexOfFirst { isSelected(it.id) }
                when (event.key) {
                    Key.DirectionDown -> if (currentIndex < songs.size - 1) {
                        onSongClick(songs[currentIndex + 1], currentIndex + 1)
                        true
                    } else false
                    Key.DirectionUp -> if (currentIndex > 0) {
                        onSongClick(songs[currentIndex - 1], currentIndex - 1)
                        true
                    } else false
                    else -> false
                }
            },
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .collapsingHeader(headerState)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FavoritesImage(Modifier.size(200.dp).collapsingHeaderImage(headerState))

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = FAVORITE_SONGS_TITLE,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.collapsingHeaderTitle(headerState),
                    )

                    Text(
                        text = "${songs.size} song${if (songs.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (songs.isEmpty()) {
                item {
                    Text(
                        text = "Mark songs with the heart to find them here",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            } else {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = onPlayAll,
                            modifier = Modifier.weight(1f).height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                            shape = RoundedCornerShape(0.dp),
                        ) {
                            Text("▶  Play", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = onShuffle,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(0.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                        ) {
                            Text("⇆  Shuffle", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                itemsIndexed(songs, key = ::favoriteSongKey) { index, song ->
                    SongRow(
                        song = song,
                        index = index,
                        onSongClick = {
                            if (touchSelection != null) touchSelection.click(song) { onSongClick(song, index) } else onSongClick(song, index)
                        },
                        longPressSelects = touchSelection?.isEnabled == true,
                        onSongDoubleClick = { onSongDoubleClick(song, index) },
                        onSongCtrlClick = { onSongCtrlClick(song) },
                        onSongShiftClick = { onSongShiftClick(song, index) },
                        contextMenuSongs = { contextMenuSongs(songs, song) },
                        isSelected = isSelected(song.id),
                        isPlaying = isPlaying(song.id),
                        onArtistClick = onArtistClick,
                        onAlbumClick = onAlbumClick,
                        showAlbum = true,
                        showFavorite = true,
                    )
                }
            }
        }
        VerticalScrollbar(Modifier.align(Alignment.CenterEnd).fillMaxHeight(), state)
    }
}
