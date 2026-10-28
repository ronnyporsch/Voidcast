package de.ronnyporsch.voidcast.ui.screen.player

import de.ronnyporsch.voidcast.ui.components.FavoriteButton
import de.ronnyporsch.voidcast.domain.model.FavoriteKind
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.HighQuality
import de.ronnyporsch.voidcast.domain.model.STANDARD_VIDEO_QUALITIES
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ronnyporsch.voidcast.getPlatform
import de.ronnyporsch.voidcast.domain.model.PlaybackStatus
import de.ronnyporsch.voidcast.domain.model.RepeatMode
import de.ronnyporsch.voidcast.ui.navigation.QueueTab
import de.ronnyporsch.voidcast.domain.model.formatDurationMs
import de.ronnyporsch.voidcast.domain.model.isYouTube
import de.ronnyporsch.voidcast.ui.components.AudioFormatText
import de.ronnyporsch.voidcast.ui.components.VideoFormatText
import de.ronnyporsch.voidcast.ui.LocalCoverArtUrlProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.GraphicEq
import de.ronnyporsch.voidcast.ui.components.LyricsIcons
import de.ronnyporsch.voidcast.ui.components.ClickableArtistText
import de.ronnyporsch.voidcast.ui.components.CoverArtImage
import de.ronnyporsch.voidcast.ui.components.HoverLinkText
import de.ronnyporsch.voidcast.ui.components.TooltipIconButton
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SpotifyLightGray
import de.ronnyporsch.voidcast.ui.theme.SpotifySurface

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onArtistClick: (artistName: String) -> Unit,
    onAlbumClick: (albumId: String) -> Unit,
    onBack: () -> Unit,
    onQueueClick: (QueueTab) -> Unit,
    /** Whether the video is shown fullscreen instead, see [VideoPlayerView]. */
    isVideoFullscreen: Boolean = false,
    onToggleVideoFullscreen: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val playback = state.playback
    val song = playback.currentSong
    val coverArt = LocalCoverArtUrlProvider.current
    val isMobile = remember { getPlatform().isMobile }
    // 6 buttons + play/pause must fit a 360dp-wide phone
    val controlModifier = if (isMobile) Modifier.size(42.dp) else Modifier
    // IconButton enforces a 48dp minimum touch target on desktop
    val controlSlotSize = if (isMobile) 42.dp else 48.dp
    val toggleIconSize = if (isMobile) 32.dp else 24.dp
    val skipIconSize = if (isMobile) 40.dp else 24.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Videos of the library play their original file
                if (state.isVideo && song?.isYouTube == true) {
                    VideoQualityButton(
                        quality = state.videoQuality,
                        heights = state.videoHeights,
                        onSelect = { viewModel.onEvent(PlayerEvent.SetVideoQuality(it)) },
                    )
                }
                DevicesButton(state, viewModel::onEvent, tint = MaterialTheme.colorScheme.onBackground)
                QueueButtons(openTab = null, onClick = onQueueClick)
            }
        }
        PlayingOnText(state)

        // Songs without lyrics show their cover; the lyrics come back for the next song that has some
        val lyricsShown = state.showLyrics && song != null &&
            (state.lyrics is LyricsState.Loaded || state.lyrics == LyricsState.Loading)
        if (lyricsShown) {
            LyricsView(
                state = state.lyrics,
                positionMs = playback.positionMs,
                onSeek = {
                    viewModel.onEvent(PlayerEvent.SeekTo(it))
                    viewModel.onEvent(PlayerEvent.Play)
                },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp),
            )
        } else if (state.isVideo && song != null) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                // The fullscreen view shows the picture meanwhile; a player has only one
                if (!isVideoFullscreen) {
                    VideoPlayerView(
                        player = viewModel.player,
                        aspectRatio = state.videoAspectRatio,
                        thumbnailUrl = coverArt.getUrl(song.coverArtId),
                        isBuffering = playback.status == PlaybackStatus.Buffering,
                        isFullscreen = false,
                        onToggleFullscreen = onToggleVideoFullscreen,
                        // It's in the controls below
                        showFullscreenButton = false,
                        modifier = Modifier.aspectRatio(state.videoAspectRatio ?: (16f / 9f)),
                    )
                }
            }
        } else {
            Spacer(Modifier.weight(0.5f))

            CoverArtImage(
                url = coverArt.getUrl(song?.coverArtId),
                modifier = Modifier.size(280.dp),
                cornerRadius = 0.dp,
                placeholderFontSize = 80.sp,
            )

            Spacer(Modifier.weight(0.5f))
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth()) {
                    HoverLinkText(
                        text = song?.title ?: "Not Playing",
                        onClick = song?.albumId?.takeIf { it.isNotBlank() }?.let { { onAlbumClick(it) } },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }

                if (song != null) {
                    Box(Modifier.fillMaxWidth()) {
                        ClickableArtistText(
                            artists = song.artists,
                            fallback = song.artist,
                            onArtistClick = onArtistClick,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    if (song.album.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.fillMaxWidth()) {
                            HoverLinkText(
                                text = song.album,
                                onClick = song.albumId.takeIf { it.isNotBlank() }?.let { { onAlbumClick(it) } },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                } else {
                    Text(
                        text = "",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            FavoriteButton(FavoriteKind.Song, song?.id, iconSize = 28.dp)
        }

        Spacer(Modifier.height(24.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = if (playback.durationMs > 0) {
                    (playback.positionMs.toFloat() / playback.durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f,
                onValueChange = { fraction ->
                    val pos = (fraction * playback.durationMs).toLong()
                    viewModel.onEvent(PlayerEvent.SeekTo(pos))
                },
                colors = SliderDefaults.colors(thumbColor = AccentColor),
                track = { PlainSliderTrack(it, SpotifySurface) },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(playback.positionMs.formatDurationMs(), style = MaterialTheme.typography.bodySmall)
                if (state.isVideo) {
                    // Video and audio are separate streams with their own codecs
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        state.videoFormat?.let { VideoFormatText(it) }
                        state.audioFormat?.let { AudioFormatText(it) }
                    }
                } else {
                    AudioFormatText(state.audioFormat)
                }
                Text(playback.durationMs.formatDurationMs(), style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TooltipIconButton(
                onClick = { viewModel.onEvent(PlayerEvent.ToggleShuffle) },
                icon = Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                tint = if (playback.shuffle) AccentColor else SpotifyLightGray,
                modifier = controlModifier,
                iconSize = toggleIconSize,
            )

            TooltipIconButton(
                onClick = { viewModel.onEvent(PlayerEvent.ToggleCrossfade) },
                icon = Icons.Default.GraphicEq,
                contentDescription = "Crossfade",
                tint = if (playback.crossfadeEnabled) AccentColor else SpotifyLightGray,
                modifier = controlModifier,
                iconSize = toggleIconSize,
            )

            TooltipIconButton(
                onClick = { viewModel.onEvent(PlayerEvent.Previous) },
                icon = Icons.Default.SkipPrevious,
                contentDescription = "Previous song",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = controlModifier,
                iconSize = skipIconSize,
            )

            Box(
                modifier = Modifier.size(64.dp).clip(CircleShape).background(AccentColor),
                contentAlignment = Alignment.Center,
            ) {
                TooltipIconButton(
                    onClick = { viewModel.onEvent(PlayerEvent.PlayPause) },
                    icon = when (playback.status) {
                        PlaybackStatus.Playing, PlaybackStatus.Buffering -> Icons.Default.Pause
                        PlaybackStatus.Paused, PlaybackStatus.Idle -> Icons.Default.PlayArrow
                    },
                    contentDescription = "Play or pause",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            TooltipIconButton(
                onClick = { viewModel.onEvent(PlayerEvent.Next) },
                icon = Icons.Default.SkipNext,
                contentDescription = "Next song",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = controlModifier,
                iconSize = skipIconSize,
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
                modifier = controlModifier,
                iconSize = toggleIconSize,
            )

            if (state.isVideo) {
                // In the lyrics' slot: videos have none
                TooltipIconButton(
                    onClick = onToggleVideoFullscreen,
                    icon = Icons.Default.Fullscreen,
                    contentDescription = "Fullscreen",
                    tint = SpotifyLightGray,
                    modifier = controlModifier,
                    iconSize = toggleIconSize,
                )
            } else if (state.lyrics is LyricsState.Loaded) {
                TooltipIconButton(
                    onClick = { viewModel.onEvent(PlayerEvent.ToggleLyrics) },
                    icon = LyricsIcons.Lyrics,
                    contentDescription = if (state.showLyrics) "Hide lyrics" else "Show lyrics",
                    tint = if (state.showLyrics) AccentColor else SpotifyLightGray,
                    modifier = controlModifier,
                    iconSize = toggleIconSize,
                )
            } else {
                // Keep the slot so the other controls don't shift between songs
                Spacer(Modifier.size(controlSlotSize))
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

/** Picks the highest resolution videos get streamed in, like YouTube's quality setting. */
@Composable
private fun VideoQualityButton(quality: Int, heights: List<Int>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = heights.ifEmpty { STANDARD_VIDEO_QUALITIES }
    // The video plays in the highest resolution up to the chosen one
    val effective = options.filter { it <= quality }.maxOrNull() ?: options.minOrNull()
    Box {
        TooltipIconButton(
            onClick = { expanded = true },
            icon = Icons.Default.HighQuality,
            contentDescription = "Video quality (${effective?.let { "${it}p" } ?: "${quality}p"})",
            tint = MaterialTheme.colorScheme.onBackground,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                "Video quality",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            options.forEach { height ->
                DropdownMenuItem(
                    text = { Text(videoQualityLabel(height)) },
                    leadingIcon = {
                        if (height == effective) Icon(Icons.Default.Check, contentDescription = null, tint = AccentColor)
                        else Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        expanded = false
                        if (height != effective) onSelect(height)
                    },
                )
            }
        }
    }
}

private fun videoQualityLabel(height: Int) = if (height >= 2160) "${height}p 4K" else "${height}p"
