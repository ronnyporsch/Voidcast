package de.ronnyporsch.voidcast.platform

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.roundToInt

/** libVLC of an installed VLC (3.x), which plays songs and videos. */
private object Vlc {
    /** Null if VLC isn't installed. */
    val factory: MediaPlayerFactory? by lazy {
        try {
            if (NativeDiscovery().discover()) MediaPlayerFactory("--no-video-title-show", "--quiet") else null
        } catch (e: Throwable) {
            // E.g. an incompatible VLC version
            System.err.println("VLC not usable: $e")
            null
        }
    }
}

/**
 * The player of [createAudioPlayer]: plays songs and videos (picture and sound) through libVLC, streaming them.
 * The picture is drawn by [PlatformVideoSurface].
 */
class VlcPlayer : AudioPlayer {
    private val _state = MutableStateFlow(AudioPlayerState())
    override val state: StateFlow<AudioPlayerState> = _state.asStateFlow()

    internal val frames = VideoFrames { aspectRatio -> _state.value = _state.value.copy(videoAspectRatio = aspectRatio) }
    /** Null if VLC isn't installed. */
    private val player: EmbeddedMediaPlayer? by lazy { Vlc.factory?.let(::createPlayer) }
    private val lock = Any()

    // libVLC only applies these once the media plays: pausing before would stop it, and a volume set
    // before there is an audio output may get lost
    @Volatile private var volume = 100
    @Volatile private var pauseRequested = false

    init {
        // Finding and loading libVLC takes a moment, don't let the start of the app or the first song wait for it
        thread(isDaemon = true, name = "VLC init") { player }
    }

    private fun createPlayer(factory: MediaPlayerFactory): EmbeddedMediaPlayer =
        factory.mediaPlayers().newEmbeddedMediaPlayer().apply {
            videoSurface().set(factory.videoSurfaces().newVideoSurface(frames, frames, true))
            // Called on a libVLC thread, which must not call back into the player (only through submit)
            events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) {
                    mediaPlayer.submit {
                        mediaPlayer.audio().setVolume(volume)
                        if (pauseRequested) mediaPlayer.controls().setPause(true)
                    }
                    update { copy(isPlaying = !pauseRequested, isBuffering = false) }
                }
                // The audio output may come up with a volume of its own (e.g. the one PulseAudio restores), which
                // would make a song that is prepared for a crossfade audible
                override fun volumeChanged(mediaPlayer: MediaPlayer, volume: Float) {
                    if (volume >= 0 && (volume * 100).roundToInt() != this@VlcPlayer.volume) {
                        mediaPlayer.submit { mediaPlayer.audio().setVolume(this@VlcPlayer.volume) }
                    }
                }
                override fun paused(mediaPlayer: MediaPlayer) = update { copy(isPlaying = false, isBuffering = false) }
                override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) =
                    update { copy(isBuffering = newCache < 100f && !pauseRequested) }
                override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) = update { copy(positionMs = newTime) }
                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = update { copy(durationMs = newLength) }
                // At the end, so the end of the song is noticed like with the other players
                override fun finished(mediaPlayer: MediaPlayer) =
                    update { copy(isPlaying = false, isBuffering = false, positionMs = durationMs) }
                override fun error(mediaPlayer: MediaPlayer) =
                    update { AudioPlayerState(error = "VLC could not play it") }
            })
        }

    private fun update(transform: AudioPlayerState.() -> AudioPlayerState) {
        synchronized(lock) { _state.value = _state.value.transform() }
    }

    override val supportsVideo: Boolean get() = player != null

    override fun play(url: String) = load(url, video = false, startPositionMs = 0)

    override fun playVideo(url: String, startPositionMs: Long) = load(url, video = true, startPositionMs = startPositionMs)

    private fun load(url: String, video: Boolean, startPositionMs: Long) {
        val player = player ?: return update { AudioPlayerState(error = "Playback needs VLC 3, see videolan.org") }
        pauseRequested = false
        if (!video) frames.clear()
        update { AudioPlayerState(isBuffering = true, positionMs = startPositionMs) }
        val options = buildList {
            // Cover art embedded in songs isn't shown as video
            if (!video) add(":no-video")
            if (startPositionMs > 0) add(":start-time=${startPositionMs / 1000.0}")
        }
        player.media().play(url, *options.toTypedArray())
    }

    override fun pause() {
        pauseRequested = true
        val player = player ?: return
        if (player.status().isPlaying) player.controls().setPause(true)
        update { copy(isPlaying = false, isBuffering = false) }
    }

    override fun resume() {
        pauseRequested = false
        val player = player ?: return
        if (!player.status().isPlayable) return player.controls().play()
        // Also when the pause hasn't taken effect yet, libVLC pauses asynchronously
        player.controls().setPause(false)
        // libVLC reports nothing if the pause was only requested while loading
        update { copy(isPlaying = true) }
    }

    override fun seekTo(positionMs: Long) {
        player?.controls()?.setTime(positionMs)
        update { copy(positionMs = positionMs) }
    }

    override fun stop() {
        pauseRequested = false
        player?.controls()?.stop()
        frames.clear()
        update { AudioPlayerState() }
    }

    override fun setVolume(volume: Float) {
        this.volume = (volume.coerceIn(0f, 1f) * 100).roundToInt()
        player?.audio()?.setVolume(this.volume)
    }

    override fun release() {
        player?.release()
    }
}

actual fun createAudioPlayer(): AudioPlayer = VlcPlayer()


/** Receives the decoded frames from libVLC and keeps the latest one for drawing. */
internal class VideoFrames(private val onAspectRatio: (Float?) -> Unit) : BufferFormatCallback, RenderCallback {
    private val lock = Any()
    private var info: ImageInfo? = null
    private var bytes = ByteArray(0)
    private val bitmap = Bitmap()
    private var hasFrame = false
    /** The picture without the padding decoders add at the bottom/right of the buffer; null until known. */
    private var visibleSize: IntSize? = null

    /** Changes with every frame; reading it while drawing redraws for every frame. */
    val frameCount = mutableLongStateOf(0)

    override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
        synchronized(lock) {
            // RV32 is BGRX in memory
            info = ImageInfo(sourceWidth, sourceHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            bytes = ByteArray(sourceWidth * sourceHeight * 4)
            hasFrame = false
            visibleSize = null
        }
        return RV32BufferFormat(sourceWidth, sourceHeight)
    }

    override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {}

    override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat) {
        val buffer = nativeBuffers.firstOrNull() ?: return
        var aspectRatio: Float? = null
        synchronized(lock) {
            val info = info ?: return
            if (visibleSize == null) {
                // The buffer size may include padding, e.g. 1280x738 for a 1280x720 video
                val dimension = mediaPlayer.video().videoDimension()
                val size = if (dimension != null && dimension.width > 0 && dimension.height > 0) {
                    IntSize(min(dimension.width, info.width), min(dimension.height, info.height))
                } else {
                    IntSize(info.width, info.height)
                }
                visibleSize = size
                aspectRatio = size.width.toFloat() / size.height
            }
            buffer.rewind()
            buffer.get(bytes, 0, min(bytes.size, buffer.remaining()))
            hasFrame = bitmap.installPixels(info, bytes, info.width * 4)
        }
        aspectRatio?.let(onAspectRatio)
        frameCount.longValue++
    }

    fun clear() {
        synchronized(lock) {
            hasFrame = false
            visibleSize = null
        }
        onAspectRatio(null)
        frameCount.longValue++
    }

    /** Draws the latest frame, scaled to fit and centered. */
    fun draw(scope: DrawScope) {
        synchronized(lock) {
            val visible = visibleSize
            if (!hasFrame || visible == null) return
            val scale = min(scope.size.width / visible.width, scope.size.height / visible.height)
            val size = Size(visible.width * scale, visible.height * scale)
            scope.drawImage(
                bitmap.asComposeImageBitmap(),
                srcSize = visible,
                dstOffset = IntOffset(((scope.size.width - size.width) / 2).roundToInt(), ((scope.size.height - size.height) / 2).roundToInt()),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )
        }
    }
}

@Composable
internal actual fun PlatformVideoSurface(player: AudioPlayer, modifier: Modifier) {
    val frames = (player as? VlcPlayer)?.frames ?: return
    Canvas(modifier) {
        frames.frameCount.longValue
        frames.draw(this)
    }
}
