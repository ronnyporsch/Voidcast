package de.ronnyporsch.voidcast.ui.screen.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.data.repository.MusicRepository
import de.ronnyporsch.voidcast.domain.model.Genre
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.ui.components.*
import de.ronnyporsch.voidcast.ui.theme.AccentColor

/** Mobile: the songs of a genre on their own screen; tapping a song plays the list from there. */
@Composable
fun GenreSongsScreen(
    genre: Genre,
    repository: MusicRepository,
    onBack: () -> Unit,
    onPlay: (songs: List<Song>, startIndex: Int, shuffle: Boolean?) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    currentSongId: String? = null,
) {
    // Null while loading
    val loadedSongs by produceState<List<Song>?>(null, genre) {
        value = repository.getGenreSongs(genre).getOrDefault(emptyList())
    }
    val songs = loadedSongs.orEmpty()
    val listState = rememberLazyListState()
    val headerState = rememberCollapsingHeaderState(listState)
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    val touchSelection = rememberTouchSelection(
        items = songs,
        selectedIds = selectedIds,
        onChange = { selectedIds = it },
        id = { it.id },
        key = ::genreSongKey,
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
            title = genre.name,
            image = { modifier -> GenreImage(modifier) },
        )
        GenreSongsList(
            genre = genre,
            songs = songs,
            isLoading = loadedSongs == null,
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

private fun genreSongKey(index: Int, song: Song): Any = "${song.id}::$index"

/** Stands in for a cover of a genre. */
@Composable
fun GenreImage(modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier.background(Brush.linearGradient(listOf(Color(0xFF3949AB), Color(0xFF0B1F4F)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Category,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(maxWidth * 0.45f),
        )
    }
}

/** Header, play buttons and rows of the songs of a genre. */
@Composable
fun GenreSongsList(
    genre: Genre,
    songs: List<Song>,
    isLoading: Boolean,
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
    RememberScrollPosition(state, "genre")
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
                    GenreImage(Modifier.size(200.dp).collapsingHeaderImage(headerState))

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = genre.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.collapsingHeaderTitle(headerState),
                    )

                    Text(
                        text = genre.countsText(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentColor)
                    }
                }
            } else if (songs.isNotEmpty()) {
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

                itemsIndexed(songs, key = ::genreSongKey) { index, song ->
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
                    )
                }
            }
        }
        VerticalScrollbar(Modifier.align(Alignment.CenterEnd).fillMaxHeight(), state)
    }
}

@Composable
internal fun GenreListItem(genre: Genre, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) AccentColor.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GenreImage(Modifier.size(44.dp))

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = genre.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = genre.countsText(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Leaves out counts the server doesn't tell. */
private fun Genre.countsText(): String = listOfNotNull(
    albumCount.takeIf { it > 0 }?.let { "$it album${if (it != 1) "s" else ""}" },
    songCount.takeIf { it > 0 }?.let { "$it song${if (it != 1) "s" else ""}" },
).joinToString(" • ")
