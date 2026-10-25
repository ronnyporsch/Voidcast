package de.ronnyporsch.voidcast.ui.screen.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.ronnyporsch.voidcast.data.repository.MusicRepository
import de.ronnyporsch.voidcast.domain.model.Album
import de.ronnyporsch.voidcast.domain.model.Genre
import de.ronnyporsch.voidcast.ui.components.*
import de.ronnyporsch.voidcast.ui.theme.AccentColor

/** Mobile: the albums of a genre on their own screen. */
@Composable
fun GenreAlbumsScreen(
    genre: Genre,
    repository: MusicRepository,
    onBack: () -> Unit,
    onAlbumClick: (Album) -> Unit,
) {
    // Null while loading
    val albums by produceState<List<Album>?>(null, genre) {
        value = repository.getGenreAlbums(genre).getOrDefault(emptyList())
    }
    val gridState = rememberLazyGridState()
    val headerState = rememberCollapsingHeaderState(gridState)
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
        GenreAlbumsGrid(
            genre = genre,
            albums = albums.orEmpty(),
            isLoading = albums == null,
            onAlbumClick = onAlbumClick,
            state = gridState,
            headerState = headerState,
        )
    }
}

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

/** Header and album grid of a genre. */
@Composable
fun GenreAlbumsGrid(
    genre: Genre,
    albums: List<Album>,
    isLoading: Boolean,
    onAlbumClick: (Album) -> Unit,
    state: LazyGridState,
    headerState: CollapsingHeaderState,
    modifier: Modifier = Modifier,
) {
    RememberScrollPosition(state, "genre")
    val albumSelection = rememberAlbumSelection()
    val touchSelection = rememberAlbumTouchSelection(albums, albumSelection)
    AlbumTouchSelectionBar(touchSelection)
    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = state,
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.touchSelection(touchSelection, state),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
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
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentColor)
                    }
                }
            } else {
                items(albums, key = { it.id }) { album ->
                    SelectableAlbum(album, albums, albumSelection, onClick = { onAlbumClick(album) }, touchSelection = touchSelection) {
                        AlbumCard(album, showArtist = true)
                    }
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
