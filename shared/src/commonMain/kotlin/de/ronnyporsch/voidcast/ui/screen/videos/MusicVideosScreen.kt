package de.ronnyporsch.voidcast.ui.screen.videos

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ronnyporsch.voidcast.data.repository.MusicRepository
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.domain.model.VideoLibrary
import de.ronnyporsch.voidcast.domain.model.formatDuration
import de.ronnyporsch.voidcast.getPlatform
import de.ronnyporsch.voidcast.ui.LocalCoverArtUrlProvider
import de.ronnyporsch.voidcast.ui.components.*
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SelectionColor

/**
 * The music videos of the server, a tab for each of its music video libraries (shown is [libraryId], or the first).
 * Tapping one (mobile) or double-clicking it (desktop, where a click selects it) plays the shown ones from there.
 */
@Composable
fun MusicVideosScreen(
    repository: MusicRepository,
    libraryId: String?,
    onLibrarySelected: (String) -> Unit,
    onPlay: (videos: List<Song>, startIndex: Int, shuffle: Boolean?) -> Unit,
    onArtistClick: (String) -> Unit,
    currentSongId: String? = null,
) {
    // Null while loading; without any (or if they can't be told) the videos of all libraries are shown
    val libraries by produceState<List<VideoLibrary>?>(null) {
        value = repository.getMusicVideoLibraries().getOrDefault(emptyList())
    }
    val loadedLibraries = libraries ?: run {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AccentColor)
        }
        return
    }
    val library = loadedLibraries.firstOrNull { it.id == libraryId } ?: loadedLibraries.firstOrNull()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (loadedLibraries.size > 1) {
            ScrollableTabRow(
                selectedTabIndex = loadedLibraries.indexOf(library),
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                edgePadding = 16.dp,
            ) {
                loadedLibraries.forEach {
                    Tab(
                        selected = it == library,
                        onClick = { onLibrarySelected(it.id) },
                        text = { Text(it.name, fontWeight = if (it == library) FontWeight.Bold else FontWeight.Normal) },
                    )
                }
            }
        }
        // Each library starts with its own search and scroll position
        key(library?.id) {
            MusicVideosGrid(repository, library, onPlay, onArtistClick, currentSongId)
        }
    }
}

/** The music videos of [library], or of all libraries if null. */
@Composable
private fun MusicVideosGrid(
    repository: MusicRepository,
    library: VideoLibrary?,
    onPlay: (videos: List<Song>, startIndex: Int, shuffle: Boolean?) -> Unit,
    onArtistClick: (String) -> Unit,
    currentSongId: String?,
) {
    // Null while loading
    val loaded by produceState<Result<List<Song>>?>(null) { value = repository.getMusicVideos(library?.id) }
    val videos = loaded?.getOrNull().orEmpty()
    var query by remember { mutableStateOf("") }
    val shown = remember(videos, query) { videos.filter { it.matches(query) } }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val isMobile = remember { getPlatform().isMobile }
    val gridState = rememberLazyGridState()
    RememberScrollPosition(gridState, "videos:${library?.id}")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 220.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 16.dp)) {
                    Text(
                        text = library?.name ?: "Music videos",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (videos.isNotEmpty()) {
                        Text(
                            text = "${videos.size} video${if (videos.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            when {
                loaded == null -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentColor)
                    }
                }
                loaded?.isFailure == true -> item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyText("Couldn't load the music videos: ${loaded?.exceptionOrNull()?.message}")
                }
                videos.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyText("There are no music videos in the library")
                }
                else -> {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedButton(
                                onClick = { if (shown.isNotEmpty()) onPlay(shown, 0, null) },
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(0.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                            ) {
                                Text("▶  Play", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { if (shown.isNotEmpty()) onPlay(shown, 0, true) },
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(0.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                            ) {
                                Text("⇆  Shuffle", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SongFilterField(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "Search in ${library?.name ?: "music videos"}",
                            contentPadding = PaddingValues(vertical = 4.dp),
                        )
                    }
                    itemsIndexed(shown, key = { _, video -> video.id }) { index, video ->
                        MusicVideoCard(
                            video = video,
                            isPlaying = video.id == currentSongId,
                            isSelected = video.id == selectedId,
                            onClick = { if (isMobile) onPlay(shown, index, null) else selectedId = video.id },
                            onDoubleClick = if (isMobile) null else { { onPlay(shown, index, null) } },
                            onArtistClick = onArtistClick,
                        )
                    }
                }
            }
        }
        VerticalScrollbar(Modifier.align(Alignment.CenterEnd).fillMaxHeight(), gridState)
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp),
    )
}

/** Thumbnail with the length of the video, its title and artists. */
@Composable
private fun MusicVideoCard(
    video: Song,
    isPlaying: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDoubleClick: (() -> Unit)?,
    onArtistClick: (String) -> Unit,
) {
    val coverArt = LocalCoverArtUrlProvider.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (isSelected) SelectionColor else Color.Transparent)
            .combinedClickable(onClick = onClick, onDoubleClick = onDoubleClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            CoverArtImage(
                url = coverArt.getUrl(video.coverArtId, 480),
                placeholderText = "▶",
                placeholderFontSize = 32.sp,
                modifier = Modifier.fillMaxSize(),
            )
            if (video.duration > 0) {
                Text(
                    text = video.duration.formatDuration(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = video.title,
            style = MaterialTheme.typography.titleSmall,
            color = if (isPlaying) AccentColor else MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        ClickableArtistText(
            artists = video.artists,
            onArtistClick = onArtistClick,
            fallback = video.artist,
        )
    }
}
