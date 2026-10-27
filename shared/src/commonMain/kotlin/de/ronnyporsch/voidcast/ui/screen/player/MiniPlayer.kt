package de.ronnyporsch.voidcast.ui.screen.player

import de.ronnyporsch.voidcast.ui.components.FavoriteButton
import de.ronnyporsch.voidcast.domain.model.FavoriteKind
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import de.ronnyporsch.voidcast.getPlatform
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ronnyporsch.voidcast.domain.model.PlaybackStatus
import de.ronnyporsch.voidcast.domain.model.formatDurationMs
import de.ronnyporsch.voidcast.ui.LocalCoverArtUrlProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.GraphicEq
import de.ronnyporsch.voidcast.ui.components.LyricsIcons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import de.ronnyporsch.voidcast.ui.components.ClickableArtistText
import de.ronnyporsch.voidcast.ui.components.CoverArtImage
import de.ronnyporsch.voidcast.ui.components.HoverLinkText
import de.ronnyporsch.voidcast.ui.components.TooltipIconButton
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SpotifyLightGray
import de.ronnyporsch.voidcast.ui.theme.SpotifySurface
import de.ronnyporsch.voidcast.domain.model.RepeatMode
import de.ronnyporsch.voidcast.ui.navigation.QueueTab

@Composable
fun MiniPlayer(
    viewModel: PlayerViewModel,
    onArtistClick: (artistName: String) -> Unit,
    onAlbumClick: (albumId: String) -> Unit,
    onClick: () -> Unit,
    /** Opens the player with the lyrics shown. */
    onLyricsClick: () -> Unit,
    /** Opens or closes the queue or the recently played songs; desktop only. */
    onQueueClick: (QueueTab) -> Unit = {},
    openQueueTab: QueueTab? = null,
) {
    val state by viewModel.state.collectAsState()
    val playback = state.playback
    val song = playback.currentSong ?: return
    val coverArt = LocalCoverArtUrlProvider.current
    val isMobile = remember { getPlatform().isMobile }
    val iconSize = if (isMobile) 32.dp else 24.dp
    val buttonSize = if (isMobile) 48.dp else 32.dp
    // Mobile: swipe the song info left/right to skip to the next/previous song.
    val swipeOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val lyricsButton: @Composable () -> Unit = {
        if (state.lyrics is LyricsState.Loaded) {
            TooltipIconButton(
                onClick = {
                    viewModel.onEvent(PlayerEvent.ShowLyrics)
                    onLyricsClick()
                },
                icon = LyricsIcons.Lyrics,
                contentDescription = "Lyrics",
                tint = SpotifyLightGray,
                modifier = Modifier.size(buttonSize),
                iconSize = iconSize,
            )
        } else if (!isMobile) {
            // Desktop keeps the slot so the controls don't shift between songs
            Spacer(Modifier.size(buttonSize))
        }
    }

    val progress = if (playback.durationMs > 0) {
        (playback.positionMs.toFloat() / playback.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp))
            .background(SpotifySurface)
            .clickable(onClick = onClick),
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Slider(
                value = progress,
                onValueChange = {
                    viewModel.onEvent(PlayerEvent.SeekTo((it * playback.durationMs).toLong()))
                },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                // Mobile: taps open the big player instead of seeking
                enabled = !isMobile,
                thumb = {
                    // Empty thumb to keep it looking like a progress bar
                },
                track = { PlainSliderTrack(it, MaterialTheme.colorScheme.surfaceVariant) },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (isMobile) Modifier
                            .graphicsLayer { translationX = swipeOffset.value }
                            .pointerInput(Unit) {
                                val threshold = size.width / 4f
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        val offset = swipeOffset.value
                                        when {
                                            offset <= -threshold -> viewModel.onEvent(PlayerEvent.Next)
                                            offset >= threshold -> viewModel.onEvent(PlayerEvent.Previous)
                                        }
                                        scope.launch { swipeOffset.animateTo(0f) }
                                    },
                                    onDragCancel = { scope.launch { swipeOffset.animateTo(0f) } },
                                ) { change, dragAmount ->
                                    change.consume()
                                    scope.launch { swipeOffset.snapTo(swipeOffset.value + dragAmount) }
                                }
                            }
                        else Modifier
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoverArtImage(
                    url = coverArt.getUrl(song.coverArtId),
                    modifier = Modifier.size(40.dp),
                    cornerRadius = 0.dp,
                    placeholderFontSize = 16.sp,
                )

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f, fill = false)) {
                    // Mobile: no link, so taps fall through and open the big player
                    HoverLinkText(
                        text = song.title,
                        onClick = song.albumId.takeIf { it.isNotBlank() && !isMobile }?.let { { onAlbumClick(it) } },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(2.dp))
                    if (isMobile && state.remoteDevice != null) {
                        PlayingOnText(state)
                    } else if (isMobile) {
                        Text(
                            text = song.artists.joinToString { it.name }.ifEmpty { song.artist },
                            style = MaterialTheme.typography.bodySmall,
                            color = SpotifyLightGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        ClickableArtistText(
                            artists = song.artists,
                            fallback = song.artist,
                            onArtistClick = onArtistClick,
                        )
                    }
                }

                // Mobile: the big player has it
                if (!isMobile) FavoriteButton(FavoriteKind.Song, song.id, iconSize = 20.dp)
            }

            Row(
                modifier = if (isMobile) Modifier else Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isMobile) {
                    TooltipIconButton(
                        onClick = { viewModel.onEvent(PlayerEvent.ToggleShuffle) },
                        icon = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (playback.shuffle) AccentColor else SpotifyLightGray,
                        modifier = Modifier.size(32.dp)
                    )

                    TooltipIconButton(
                        onClick = { viewModel.onEvent(PlayerEvent.ToggleCrossfade) },
                        icon = Icons.Default.GraphicEq,
                        contentDescription = "Crossfade",
                        tint = if (playback.crossfadeEnabled) AccentColor else SpotifyLightGray,
                        modifier = Modifier.size(32.dp)
                    )

                    TooltipIconButton(
                        onClick = { viewModel.onEvent(PlayerEvent.Previous) },
                        icon = Icons.Default.SkipPrevious,
                        contentDescription = "Previous song",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Mobile: lyrics sit left of play/pause so play/pause stays at the edge
                if (isMobile && (state.devices.isNotEmpty() || state.remoteDevice != null)) {
                    DevicesButton(state, viewModel::onEvent, Modifier.size(buttonSize), iconSize = iconSize)
                }
                if (isMobile) lyricsButton()

                TooltipIconButton(
                    onClick = { viewModel.onEvent(PlayerEvent.PlayPause) },
                    icon = when (playback.status) {
                        PlaybackStatus.Playing, PlaybackStatus.Buffering -> Icons.Default.Pause
                        PlaybackStatus.Paused, PlaybackStatus.Idle -> Icons.Default.PlayArrow
                    },
                    contentDescription = "Play or pause",
                    tint = MaterialTheme.colorScheme.onSurface,
                    iconSize = if (isMobile) 36.dp else 24.dp,
                    modifier = if (isMobile) Modifier.size(buttonSize) else Modifier,
                )

                if (!isMobile) {
                    TooltipIconButton(
                        onClick = { viewModel.onEvent(PlayerEvent.Next) },
                        icon = Icons.Default.SkipNext,
                        contentDescription = "Next song",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )

                    TooltipIconButton(
                        onClick = { viewModel.onEvent(PlayerEvent.ToggleRepeat) },
                        icon = when (playback.repeatMode) {
                            RepeatMode.Off -> Icons.Default.Repeat
                            RepeatMode.All -> Icons.Default.Repeat
                            RepeatMode.One -> Icons.Default.RepeatOne
                        },
                        contentDescription = "Repeat",
                        tint = if (playback.repeatMode != RepeatMode.Off) AccentColor else SpotifyLightGray,
                        modifier = Modifier.size(32.dp)
                    )

                    lyricsButton()
                }
            }

            // Same width as the song info so the controls stay centered
            if (!isMobile) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayingOnText(state, Modifier.weight(1f, fill = false).padding(end = 4.dp))
                    val durationMs = playback.durationMs.takeIf { it > 0 } ?: (song.duration * 1000L)
                    Text(
                        text = "${playback.positionMs.formatDurationMs()} / ${durationMs.formatDurationMs()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SpotifyLightGray,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    DevicesButton(state, viewModel::onEvent, Modifier.size(32.dp))
                    QueueButtons(openQueueTab, onQueueClick, Modifier.size(32.dp))
                }
            }
        }
    }
}
