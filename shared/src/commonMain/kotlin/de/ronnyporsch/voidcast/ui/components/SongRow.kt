package de.ronnyporsch.voidcast.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ronnyporsch.voidcast.domain.model.AudioStreamInfo
import de.ronnyporsch.voidcast.domain.model.FavoriteKind
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.domain.model.formatDuration
import de.ronnyporsch.voidcast.domain.model.isYouTube
import de.ronnyporsch.voidcast.getPlatform
import de.ronnyporsch.voidcast.ui.LocalCoverArtUrlProvider
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SelectionColor

/** Ids of the songs that are downloaded on this device; [SongRow] marks them. */
val LocalDownloadedSongIds = compositionLocalOf<Set<String>> { emptySet() }

/** Songs waiting for their download and those being downloaded right now; [SongRow] marks them. */
data class PendingSongDownloads(val queued: Set<String> = emptySet(), val downloading: Set<String> = emptySet()) {
    operator fun contains(songId: String) = songId in queued || songId in downloading
}

val LocalPendingSongDownloads = compositionLocalOf { PendingSongDownloads() }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onSongClick: () -> Unit,
    /** Makes the artist names links; null shows them as plain text. */
    onArtistClick: ((String) -> Unit)?,
    index: Int? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    showCoverArt: Boolean = true,
    showArtist: Boolean = true,
    showAlbum: Boolean = false,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    isSelected: Boolean = false,
    isPlaying: Boolean = false,
    onSongDoubleClick: (() -> Unit)? = null,
    onRemoveClick: (() -> Unit)? = null,
    removeDescription: String = "Remove from playlist",
    trailingContent: (@Composable () -> Unit)? = null,
    /** Codec and bitrate, shown left of the duration. */
    audioFormat: AudioStreamInfo? = null,
    /** What the item is, e.g. "Song" or "Video", shown before the artist. */
    typeLabel: String? = null,
    /** Shows a heart to mark the song as favorite (library songs only). */
    showFavorite: Boolean = false,
    /** Click while Ctrl (Cmd on macOS) is held, to add the song to or remove it from the selection. Desktop and web only. */
    onSongCtrlClick: (() -> Unit)? = null,
    /** Click while Shift is held, to select the songs between this one and the selection. Desktop and web only. */
    onSongShiftClick: (() -> Unit)? = null,
    /** The songs the context menu acts on, see [contextMenuSongs]. */
    contextMenuSongs: () -> List<Song> = { listOf(song) },
    /** Adds "Remove from playlist" to the context menu; removes the songs the menu acts on. */
    onRemoveFromPlaylist: (() -> Unit)? = null,
    /** Mobile: a long press selects the song (see [TouchSelection]) rather than opening the context menu. */
    longPressSelects: Boolean = false,
) {
    val coverArt = LocalCoverArtUrlProvider.current
    val platform = getPlatform()
    val density = LocalDensity.current

    val hasContextMenu = LocalPlaylistEditor.current != null || LocalAddToQueue.current != null ||
        onRemoveFromPlaylist != null || LocalSongDownloads.current != null
    // YouTube Music results aren't in the library; dropping them onto playlists needs the server to download them
    val isDraggable = LocalPlaylistEditor.current?.let { !song.isYouTube || it.canAddYouTube } == true
    var menuSongs by remember { mutableStateOf<List<Song>?>(null) }
    var pressPosition by remember { mutableStateOf(Offset.Zero) }
    var ctrlPressed by remember { mutableStateOf(false) }
    var shiftPressed by remember { mutableStateOf(false) }
    var rowHeight by remember { mutableStateOf(0) }
    val currentContextMenuSongs by rememberUpdatedState(contextMenuSongs)

    // Mobile: swiping the row to the right adds the song to the queue
    val addToQueue = LocalAddToQueue.current
    val swipeEnabled = platform.isMobile && addToQueue != null
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    val swipeThreshold = with(density) { 72.dp.toPx() }
    val swipeMax = with(density) { 120.dp.toPx() }
    val haptics = LocalHapticFeedback.current

    val openMenu = { if (hasContextMenu) menuSongs = currentContextMenuSongs() }
    val background = when {
        isSelected -> SelectionColor
        menuSongs != null -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }

    val rowModifier = if (platform.isMobile) {
        modifier
            .fillMaxWidth()
            .offset { IntOffset(swipeOffset.roundToInt(), 0) }
            .draggable(
                orientation = Orientation.Horizontal,
                enabled = swipeEnabled,
                state = rememberDraggableState { delta ->
                    val new = (swipeOffset + delta).coerceIn(0f, swipeMax)
                    if (swipeOffset < swipeThreshold && new >= swipeThreshold) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    swipeOffset = new
                },
                onDragStopped = {
                    if (swipeOffset >= swipeThreshold) addToQueue?.invoke(listOf(song))
                    animate(swipeOffset, 0f) { value, _ -> swipeOffset = value }
                },
            )
            // Opaque while swiping so the queue icon behind the row doesn't show through
            .then(if (swipeOffset != 0f) Modifier.background(MaterialTheme.colorScheme.background) else Modifier)
            .background(background)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) pressPosition = event.changes.first().position
                    }
                }
            }
            .combinedClickable(onClick = onSongClick, onLongClick = if (longPressSelects) null else openMenu)
    } else {
        modifier
            .fillMaxWidth()
            .background(background)
            // Library songs can be dragged onto playlists; with a selection the drag takes all selected songs
            .songDragSource(enabled = isDraggable) { currentContextMenuSongs() }
            .pointerInput(hasContextMenu) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Press) continue
                        pressPosition = event.changes.first().position
                        ctrlPressed = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                        shiftPressed = event.keyboardModifiers.isShiftPressed
                        if (event.buttons.isSecondaryPressed && hasContextMenu) {
                            event.changes.forEach { it.consume() }
                            openMenu()
                        }
                    }
                }
            }
            .combinedClickable(
                onClick = {
                    val ctrlClick = onSongCtrlClick
                    val shiftClick = onSongShiftClick
                    when {
                        shiftPressed && shiftClick != null -> shiftClick()
                        ctrlPressed && ctrlClick != null -> ctrlClick()
                        else -> onSongClick()
                    }
                },
                onDoubleClick = onSongDoubleClick,
            )
    }

    Box {
        if (swipeOffset > 0f) {
            Box(
                modifier = Modifier.matchParentSize().background(AccentColor.copy(alpha = if (swipeOffset >= swipeThreshold) 0.35f else 0.15f)),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "Add to queue",
                    tint = AccentColor,
                    modifier = Modifier.padding(start = 24.dp),
                )
            }
        }
        Row(
            modifier = rowModifier.onSizeChanged { rowHeight = it.height }.padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index != null) {
                Text(
                    text = "${index + 1}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(32.dp),
                )
            }

            if (showCoverArt) {
                CoverArtImage(
                    url = coverArt.getUrl(song.coverArtId),
                    modifier = Modifier.size(40.dp),
                    cornerRadius = 0.dp,
                    placeholderFontSize = 16.sp,
                )
                Spacer(Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isPlaying) AccentColor else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (song.id in LocalDownloadedSongIds.current) {
                        Icon(
                            Icons.Default.DownloadForOffline,
                            contentDescription = "Downloaded",
                            tint = AccentColor,
                            modifier = Modifier.padding(end = 4.dp).size(14.dp),
                        )
                    } else if (song.id in LocalPendingSongDownloads.current.downloading) {
                        CircularProgressIndicator(
                            color = AccentColor,
                            strokeWidth = 1.5.dp,
                            modifier = Modifier.padding(end = 4.dp).size(12.dp),
                        )
                    } else if (song.id in LocalPendingSongDownloads.current.queued) {
                        Icon(
                            Icons.Default.Downloading,
                            contentDescription = "Waiting for download",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 4.dp).size(14.dp),
                        )
                    }
                    if (typeLabel != null) {
                        Text(
                            text = if (showArtist || (showAlbum && song.album.isNotEmpty())) "$typeLabel \u2022 " else typeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (showArtist) {
                        if (onArtistClick != null) {
                            ClickableArtistText(
                                artists = song.artists,
                                fallback = song.artist.ifBlank { "Unknown artist" },
                                onArtistClick = onArtistClick,
                            )
                        } else {
                            Text(
                                text = song.artists.joinToString(", ") { it.name }.ifBlank { song.artist.ifBlank { "Unknown artist" } },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (showAlbum && song.album.isNotEmpty()) {
                        if (showArtist) {
                            Text(text = " \u2022 ", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            text = song.album,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (onAlbumClick != null) AccentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (onAlbumClick != null) Modifier.clickable { onAlbumClick(song.albumId) } else Modifier,
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            if (showFavorite) {
                FavoriteButton(FavoriteKind.Song, song.id, Modifier.size(32.dp).padding(end = 4.dp), iconSize = 18.dp)
            }

            AudioFormatText(audioFormat, Modifier.padding(end = 12.dp))

            Text(
                text = song.duration.formatDuration(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (onRemoveClick != null) {
                Spacer(Modifier.width(12.dp))
                TooltipIconButton(
                    onClick = onRemoveClick,
                    icon = Icons.Default.Close,
                    contentDescription = removeDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
            }

            if (trailingContent != null) {
                Spacer(Modifier.width(12.dp))
                trailingContent()
            }
        }

        // The menu is placed below its anchor, so move it up to where the row got clicked
        SongContextMenu(
            expanded = menuSongs != null,
            onDismiss = { menuSongs = null },
            songs = menuSongs.orEmpty(),
            offset = with(density) { DpOffset(pressPosition.x.toDp(), (pressPosition.y - rowHeight).toDp()) },
            onRemoveFromPlaylist = onRemoveFromPlaylist,
        )
    }
}
