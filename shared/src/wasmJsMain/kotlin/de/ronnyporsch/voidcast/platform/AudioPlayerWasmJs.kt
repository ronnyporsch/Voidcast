package de.ronnyporsch.voidcast.platform

import de.ronnyporsch.voidcast.domain.model.gainToAmplitude
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import kotlinx.browser.document
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.w3c.dom.HTMLVideoElement
import kotlin.js.ExperimentalWasmJsInterop

@OptIn(ExperimentalWasmJsInterop::class)
class AudioPlayerWasmJs : AudioPlayer {

    private val _state = MutableStateFlow(AudioPlayerState())
    override val state: StateFlow<AudioPlayerState> = _state.asStateFlow()

    // A video element plays audio-only streams just the same. Compose draws into a canvas, so the picture is an
    // element behind it, placed where the PlatformVideoSurface is, which makes the canvas transparent there (so
    // Compose can still draw controls on top)
    private val media = (document.createElement("video") as HTMLVideoElement).apply {
        style.apply {
            position = "fixed"
            display = "none"
            setProperty("pointer-events", "none")
            setProperty("object-fit", "contain")
            background = "black"
            zIndex = "-1"
        }
        setAttribute("playsinline", "")
        // Browsers' own video overlays (e.g. Firefox's "Pop out this video") would show on top of the app
        setAttribute("disablepictureinpicture", "")
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var positionJob: Job? = null
    private var isVideo = false

    init {
        media.addEventListener("playing", {
            updateState(isPlaying = true)
            startPositionTracking()
        })
        media.addEventListener("pause", {
            updateState(isPlaying = false)
            positionJob?.cancel()
        })
        media.addEventListener("ended", {
            _state.value = AudioPlayerState()
            positionJob?.cancel()
        })
        media.addEventListener("waiting", {
            _state.value = _state.value.copy(isBuffering = true)
        })
        media.addEventListener("error", {
            positionJob?.cancel()
            _state.value = AudioPlayerState(error = describeMediaError(media.error?.code?.toInt()))
        })
        media.addEventListener("resize", {
            _state.value = _state.value.copy(videoAspectRatio = videoAspectRatio())
        })
    }

    private fun videoAspectRatio(): Float? =
        if (isVideo && media.videoWidth > 0 && media.videoHeight > 0) media.videoWidth.toFloat() / media.videoHeight else null

    private fun updateState(isPlaying: Boolean) {
        _state.value = AudioPlayerState(
            isPlaying = isPlaying,
            positionMs = (media.currentTime * 1000).toLong(),
            durationMs = if (media.duration.isNaN()) 0L else (media.duration * 1000).toLong(),
            videoAspectRatio = videoAspectRatio(),
        )
    }

    private fun startPositionTracking() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                updateState(isPlaying = true)
                delay(500)
            }
        }
    }

    private fun describeMediaError(code: Int?) = when (code) {
        2 -> "Network error while loading the audio stream"
        3 -> "The audio stream could not be decoded"
        4 -> "The audio stream is unreachable or its format is not supported"
        else -> "Could not load the audio stream"
    }

    override fun play(url: String) = load(url, video = false, startPositionMs = 0)

    override val supportsVideo: Boolean = true

    override fun playVideo(url: String, startPositionMs: Long) = load(url, video = true, startPositionMs = startPositionMs)

    private fun load(url: String, video: Boolean, startPositionMs: Long) {
        // Not before: ComposeViewport replaces the page's content when it starts
        if (!media.isConnected) document.body?.appendChild(media)
        isVideo = video
        _state.value = AudioPlayerState(isBuffering = true)
        // Media fragment: starts there without loading the beginning first
        media.src = if (startPositionMs > 0) "$url#t=${startPositionMs / 1000.0}" else url
        media.load()
        media.play()
    }

    /** Shows the picture at these CSS pixel bounds of the page. */
    internal fun showVideo(left: Float, top: Float, width: Float, height: Float) {
        media.style.apply {
            this.left = "${left}px"
            this.top = "${top}px"
            this.width = "${width}px"
            this.height = "${height}px"
            display = "block"
        }
    }

    internal fun hideVideo() {
        media.style.display = "none"
    }

    override fun pause() {
        media.pause()
    }

    override fun resume() {
        media.play()
    }

    override fun seekTo(positionMs: Long) {
        media.currentTime = positionMs / 1000.0
        updateState(isPlaying = !media.paused)
    }

    private var volume = 1.0
    private var gain = 1.0

    override fun setVolume(volume: Float) {
        this.volume = volume.toDouble().coerceIn(0.0, 1.0)
        media.volume = this.volume * gain
    }

    override fun setGain(gainDb: Float) {
        gain = gainToAmplitude(gainDb).toDouble()
        media.volume = volume * gain
    }

    override fun stop() {
        media.pause()
        media.currentTime = 0.0
        positionJob?.cancel()
        _state.value = AudioPlayerState()
    }

    override fun release() {
        stop()
        scope.cancel()
        media.remove()
    }
}

actual fun createAudioPlayer(): AudioPlayer = AudioPlayerWasmJs()

@Composable
internal actual fun PlatformVideoSurface(player: AudioPlayer, modifier: Modifier) {
    val webPlayer = player as? AudioPlayerWasmJs ?: return
    // Compose pixels are device pixels, the element is placed in CSS pixels
    val density = LocalDensity.current.density
    val hasPicture = webPlayer.state.collectAsState().value.videoAspectRatio != null
    DisposableEffect(webPlayer) {
        onDispose { webPlayer.hideVideo() }
    }
    Box(
        modifier
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                webPlayer.showVideo(bounds.left / density, bounds.top / density, bounds.width / density, bounds.height / density)
            }
            // Lets the element behind the canvas show through, once there is a picture (until then e.g. a thumbnail)
            .drawBehind { if (hasPicture) drawRect(Color.Black, blendMode = BlendMode.Clear) }
    )
}
