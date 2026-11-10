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
import com.sun.jna.NativeLibrary
import com.sun.jna.ptr.IntByReference
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
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

/** libVLC of an installed VLC (3.x), which plays songs and videos. */
private object Vlc {
    private val onLinux = System.getProperty("os.name").orEmpty().startsWith("Linux")

    /** What playback needs that isn't installed; null if nothing. */
    @Volatile var missing: String? = null
        private set

    /** Null if something is [missing]. */
    val factory: MediaPlayerFactory? by lazy {
        try {
            if (!NativeDiscovery().discover()) {
                missing = "Playback needs VLC 3, see videolan.org"
                return@lazy null
            }
            val factory = MediaPlayerFactory("--no-video-title-show", "--quiet", *audioOutputArgs.toTypedArray())
            if (onLinux && factory.audio().audioOutputs().none { it.name == "jack" }) {
                factory.release()
                missing = "Playback needs VLC's JACK plugin and pipewire-jack"
                return@lazy null
            }
            factory
        } catch (e: Throwable) {
            // E.g. an incompatible VLC version
            System.err.println("VLC not usable: $e")
            missing = "Playback needs VLC 3, see videolan.org"
            null
        }
    }

    @Volatile private var jackWorked = false

    /**
     * What's wrong if VLC's audio output can't be used, null if it can. VLC itself would only play silently then.
     * That's without PipeWire's JACK, or with a real JACK whose server isn't running.
     */
    fun audioOutputError(): String? {
        if (!onLinux || jackWorked) return null
        try {
            // The library VLC's JACK plugin uses. 1: don't start a server
            val jack = NativeLibrary.getInstance("libjack.so.0")
            val client = jack.getFunction("jack_client_open")
                .invokePointer(arrayOf("Voidcast check", 1, IntByReference()))
            if (client != null) {
                jack.getFunction("jack_client_close").invokeInt(arrayOf(client))
                jackWorked = true
                return null
            }
        } catch (e: Throwable) {
            // No JACK library installed
        }
        return "Playback needs pipewire-jack and a running PipeWire"
    }

    /**
     * Whether VLC plays through JACK, where it sets the volume itself: on what it decodes, which is heard only after
     * what VLC has buffered ahead.
     */
    val viaJack: Boolean get() = onLinux

    /**
     * On Linux, VLC plays through the JACK of PipeWire. VLC 3 has no output for PipeWire of its own, and the other
     * ones each fail (seen with PipeWire 1.6.10):
     * - PulseAudio (VLC's default): VLC starts every song without the latency of the device and corrects that
     *   afterwards. Up to 180 ms it resamples, which stutters; above (Bluetooth headphones) it drops what it has
     *   buffered and fills up with silence, which mutes moments in the first seconds of a song.
     * - ALSA: the end of nearly every song hangs in pipewire-alsa waiting for the output to drain (it only finishes
     *   if the last samples exactly fill a period), and then stopping or releasing the player never returns.
     * JACK has neither problem: VLC knows the latency from the first sample on and waits out the end itself. It also
     * follows a change of the default device.
     */
    private val audioOutputArgs: List<String>
        get() = if (onLinux) {
            // "none": VLC doesn't fall back to another output if JACK can't be used.
            // Not saving the volume: VLC would start every song with the volume last set on any player
            listOf("--aout=jack,none", "--jack-name=Voidcast", "--no-volume-save")
        } else emptyList()
}

/**
 * The player of [createAudioPlayer]: plays songs and videos (picture and sound) through libVLC, streaming them.
 * The picture is drawn by [PlatformVideoSurface].
 */
class VlcPlayer : AudioPlayer {
    private val _state = MutableStateFlow(AudioPlayerState())
    override val state: StateFlow<AudioPlayerState> = _state.asStateFlow()

    internal val frames = VideoFrames { aspectRatio -> _state.value = _state.value.copy(videoAspectRatio = aspectRatio) }
    /** Null if VLC can't be used, see [Vlc.missing]. */
    private val player: EmbeddedMediaPlayer? by lazy { Vlc.factory?.let(::createPlayer) }
    private val lock = Any()
    /**
     * Runs everything that calls libVLC, in order. These calls can take long or never return (e.g. stopping a stream
     * that stalled, or an audio output that hangs), which must not freeze the UI thread they're requested from.
     */
    private val commands = Executors.newSingleThreadExecutor { Thread(it, "VLC control").apply { isDaemon = true } }

    // libVLC only applies these once the media plays: pausing before would stop it, and a volume set
    // before there is an audio output may get lost
    @Volatile private var volume = 100
    @Volatile private var pauseRequested = false
    /** What's loaded, to start it again; null after [stop]. */
    @Volatile private var currentUrl: String? = null
    @Volatile private var currentIsVideo = false

    init {
        // Finding and loading libVLC takes a moment, don't let the start of the app or the first song wait for it
        command { player }
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
                        mediaPlayer.submit {
                            // An audio output that just came up reports its volume before it can be set, and
                            // reports nothing again. Where VLC sets the volume itself, it must be set before the
                            // song's first samples are decoded, so it's tried again right away
                            for (attempt in 1..100) {
                                if (mediaPlayer.audio().setVolume(this@VlcPlayer.volume)) break
                                Thread.sleep(1)
                            }
                        }
                    }
                }
                override fun paused(mediaPlayer: MediaPlayer) {
                    // Resumed while it was still starting paused (see prepare)
                    if (!pauseRequested) return mediaPlayer.submit { mediaPlayer.controls().setPause(false) }
                    update { copy(isPlaying = false, isBuffering = false) }
                }
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

    private fun command(block: () -> Unit) = commands.execute(block)

    private fun update(transform: AudioPlayerState.() -> AudioPlayerState) {
        synchronized(lock) { _state.value = _state.value.transform() }
    }

    override val supportsVideo: Boolean get() = player != null

    override fun play(url: String) = command { load(url, video = false, startPositionMs = 0) }

    // Paused before any of it is played, so it starts at its beginning
    override fun prepare(url: String) = command { load(url, video = false, startPositionMs = 0, paused = true) }

    override fun playVideo(url: String, startPositionMs: Long) =
        command { load(url, video = true, startPositionMs = startPositionMs) }

    private fun load(url: String, video: Boolean, startPositionMs: Long, paused: Boolean = false) {
        val player = player ?: return update { AudioPlayerState(error = Vlc.missing) }
        Vlc.audioOutputError()?.let { return update { AudioPlayerState(error = it) } }
        pauseRequested = paused
        // Loading it again (see seekTo) doesn't change its length
        val durationMs = if (url == currentUrl) _state.value.durationMs else 0
        currentUrl = url
        currentIsVideo = video
        if (!video) frames.clear()
        update { AudioPlayerState(isBuffering = !paused, positionMs = startPositionMs, durationMs = durationMs) }
        val options = buildList {
            // Cover art embedded in songs isn't shown as video
            if (!video) add(":no-video")
            // VLC's own Ogg demuxer ignores seeking backwards in a stream (not in a file) and often finds no length;
            // FFmpeg's seeks every song format correctly
            if (!video) add(":demux=avformat")
            // A change of the volume is only heard after what VLC has buffered ahead (1 s of a stream by default),
            // which a crossfade would be late by
            if (!video && Vlc.viaJack) add(":network-caching=300")
            if (startPositionMs > 0) add(":start-time=${startPositionMs / 1000.0}")
            if (paused) add(":start-paused")
        }
        player.media().play(url, *options.toTypedArray())
    }

    override fun pause() = command {
        pauseRequested = true
        val player = player ?: return@command
        if (player.status().isPlaying) player.controls().setPause(true)
        update { copy(isPlaying = false, isBuffering = false) }
    }

    override fun resume() = command {
        pauseRequested = false
        val player = player ?: return@command
        if (!player.status().isPlayable) return@command player.controls().play()
        // Also when the pause hasn't taken effect yet, libVLC pauses asynchronously
        player.controls().setPause(false)
        // libVLC reports nothing if the pause was only requested while loading
        update { copy(isPlaying = true) }
    }

    override fun seekTo(positionMs: Long) = command {
        val player = player ?: return@command
        val url = currentUrl
        // A stream the server transcodes while sending it can only be seeked forward (VLC reads up to the position),
        // so it's loaded again to go back
        if (url != null && !player.status().isSeekable && positionMs < player.status().time()) {
            return@command load(url, currentIsVideo, startPositionMs = positionMs, paused = pauseRequested)
        }
        player.controls().setTime(positionMs)
        update { copy(positionMs = positionMs) }
    }

    override fun stop() = command {
        pauseRequested = false
        currentUrl = null
        player?.controls()?.stop()
        frames.clear()
        update { AudioPlayerState() }
    }

    override fun setVolume(volume: Float) = command {
        this.volume = (volume.coerceIn(0f, 1f) * 100).roundToInt()
        player?.audio()?.setVolume(this.volume)
    }

    /** Waits for the commands before it; don't call it on the UI thread. */
    override fun release() {
        commands.submit { player?.release() }.get()
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
    @Volatile private var visibleSize: IntSize? = null

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
        // Not while holding the lock: this call into libVLC can wait for a stop in progress, and drawing needs the lock
        val needsSize = visibleSize == null
        val dimension = if (needsSize) mediaPlayer.video().videoDimension() else null
        synchronized(lock) {
            val info = info ?: return
            if (needsSize && visibleSize == null) {
                // The buffer size may include padding, e.g. 1280x738 for a 1280x720 video
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
