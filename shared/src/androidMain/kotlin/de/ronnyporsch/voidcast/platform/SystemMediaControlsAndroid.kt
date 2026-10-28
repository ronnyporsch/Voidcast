package de.ronnyporsch.voidcast.platform

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import de.ronnyporsch.voidcast.AppSession
import de.ronnyporsch.voidcast.domain.model.PlaybackState
import de.ronnyporsch.voidcast.domain.model.PlaybackStatus
import de.ronnyporsch.voidcast.domain.model.RepeatMode
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.ui.screen.player.PlayerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.abs

actual fun createSystemMediaControls(
    playback: Flow<PlaybackState>,
    onEvent: (PlayerEvent) -> Unit,
    coverArtUrl: (Song) -> String?,
): SystemMediaControls? {
    val context = AppContextHolder.context
    val owner = Any()
    PlaybackBridgePlayer.attach(owner, onEvent, coverArtUrl)
    val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    scope.launch { playback.collect { PlaybackBridgePlayer.update(owner, it) } }
    // Binding a controller starts the service, which then shows the notification (in the foreground) while playing
    val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    val controller = MediaController.Builder(context, token).buildAsync()
    return object : SystemMediaControls {
        override fun release() {
            scope.cancel()
            PlaybackBridgePlayer.detach(owner)
            MediaController.releaseFuture(controller)
        }
    }
}

/**
 * Shows the media notification for [PlaybackBridgePlayer], and offers the library to media browsers like Google Maps
 * and Android Auto, which may start it without the app being open. Declared in the app's manifest.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private var library: MediaLibraryCallback? = null

    override fun onCreate() {
        super.onCreate()
        // Without the activity, e.g. when Google Maps connects, this sets up the connection and the player
        AppContextHolder.init(this)
        val app = AppSession.get()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val sessionActivity = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Cover art goes through the app's HTTP client, so it works with an allowed untrusted certificate;
        // downloaded covers are local files, which the default data source reads itself
        val bitmapLoader = CacheBitmapLoader(
            DataSourceBitmapLoader(
                DataSourceBitmapLoader.DEFAULT_EXECUTOR_SERVICE.get(),
                DefaultDataSource.Factory(this, OkHttpDataSource.Factory(sharedOkHttpClient)),
            )
        )
        val library = MediaLibraryCallback(app.connection).also { library = it }
        session = MediaLibrarySession.Builder(this, PlaybackBridgePlayer, library)
            .setSessionActivity(sessionActivity)
            .setBitmapLoader(bitmapLoader)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    // Swiping the app away from the recent apps stops the music; the next start creates a new session
    override fun onTaskRemoved(rootIntent: Intent?) {
        AppSession.release()
        stopSelf()
    }

    override fun onDestroy() {
        library?.release()
        library = null
        session?.release()
        session = null
        super.onDestroy()
    }
}

/**
 * A Media3 player that only mirrors the app's [PlaybackState] (the actual playback happens in [CrossfadeAudioPlayer]'s
 * two ExoPlayers, with the queue handled by the player view model) and passes the session's commands back as
 * [PlayerEvent]s.
 */
@OptIn(UnstableApi::class)
internal object PlaybackBridgePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var owner: Any? = null
    private var onEvent: (PlayerEvent) -> Unit = {}
    private var coverArtUrl: (Song) -> String? = { null }

    private var playback = PlaybackState()
    private var lastPositionMs = 0L
    private var lastPositionTime = 0L
    private var seeked = false

    // Only the songs around the current one, so a huge queue doesn't get sent to every controller
    private var items: List<MediaItemData> = emptyList()
    private var currentItemIndex = 0
    private var itemsKey: Any? = null

    // Lets media browsers start playback; the session asks [MediaLibraryCallback] what to play
    private val idleCommands = Player.Commands.Builder()
        .addAll(COMMAND_SET_MEDIA_ITEM, COMMAND_PREPARE)
        .build()

    private val commands = Player.Commands.Builder()
        .addAll(
            COMMAND_PLAY_PAUSE,
            COMMAND_STOP,
            COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            COMMAND_SEEK_TO_NEXT,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            COMMAND_GET_CURRENT_MEDIA_ITEM,
            COMMAND_GET_TIMELINE,
            COMMAND_GET_METADATA,
        )
        .addAll(idleCommands)
        .build()

    fun attach(owner: Any, onEvent: (PlayerEvent) -> Unit, coverArtUrl: (Song) -> String?) {
        this.owner = owner
        this.onEvent = onEvent
        this.coverArtUrl = coverArtUrl
    }

    /** Plays [songs] from [startIndex] on, e.g. chosen in Google Maps. */
    fun play(songs: List<Song>, startIndex: Int) {
        onEvent(PlayerEvent.PlaySongs(songs, startIndex))
    }

    /** Called when the app's player is gone, e.g. with the activity; hides the notification. */
    fun detach(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        onEvent = {}
        update(owner = null, PlaybackState())
    }

    fun update(owner: Any?, playback: PlaybackState) {
        if (this.owner !== owner) return
        val now = SystemClock.elapsedRealtime()
        val wasPlaying = this.playback.status == PlaybackStatus.Playing
        val expectedPositionMs = lastPositionMs + if (wasPlaying) now - lastPositionTime else 0
        // A jump within the same song is a seek, which controllers wouldn't learn about otherwise
        seeked = seeked || (playback.currentIndex == this.playback.currentIndex &&
            playback.currentSong != null && abs(playback.positionMs - expectedPositionMs) > 2_000)
        this.playback = playback
        lastPositionMs = playback.positionMs
        lastPositionTime = now
        updateItems()
        invalidateState()
    }

    private fun updateItems() {
        val queue = playback.queue
        val index = playback.currentIndex
        val current = playback.currentSong
        if (current == null || index !in queue.indices) {
            items = emptyList()
            itemsKey = null
            return
        }
        val previous = queue.getOrNull(index - 1)
        val next = when {
            playback.repeatMode == RepeatMode.One -> current
            index + 1 < queue.size -> queue[index + 1]
            playback.repeatMode == RepeatMode.All -> queue.first()
            else -> null
        }
        val key = listOf(index, previous?.id, current.id, next?.id, playback.durationMs)
        if (key == itemsKey) return
        itemsKey = key
        items = buildList {
            previous?.let { add(item("previous:${index - 1}:${it.id}", it, it.duration * 1000L)) }
            add(item("current:$index:${current.id}", current, playback.durationMs.takeIf { it > 0 } ?: (current.duration * 1000L)))
            next?.let { add(item("next:${index + 1}:${it.id}", it, it.duration * 1000L)) }
        }
        currentItemIndex = if (previous != null) 1 else 0
    }

    private fun item(uid: String, song: Song, durationMs: Long): MediaItemData {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(coverArtUrl(song)?.let(Uri::parse))
            .build()
        val mediaItem = MediaItem.Builder().setMediaId(song.id).setMediaMetadata(metadata).build()
        return MediaItemData.Builder(uid)
            .setMediaItem(mediaItem)
            .setDurationUs(if (durationMs > 0) durationMs * 1000 else C.TIME_UNSET)
            .build()
    }

    override fun getState(): State {
        if (items.isEmpty()) {
            return State.Builder()
                .setAvailableCommands(idleCommands)
                .setPlaybackState(STATE_IDLE)
                .build()
        }
        val playing = playback.status == PlaybackStatus.Playing || playback.status == PlaybackStatus.Buffering
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(items)
            .setCurrentMediaItemIndex(currentItemIndex)
            .setContentPositionMs(
                if (playback.status == PlaybackStatus.Playing) {
                    PositionSupplier.getExtrapolating(playback.positionMs, 1f)
                } else {
                    PositionSupplier.getConstant(playback.positionMs)
                }
            )
            .setPlayWhenReady(playing, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (playback.status == PlaybackStatus.Buffering) STATE_BUFFERING else STATE_READY)
        if (seeked) {
            seeked = false
            builder.setPositionDiscontinuity(DISCONTINUITY_REASON_SEEK, playback.positionMs)
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        onEvent(if (playWhenReady) PlayerEvent.Play else PlayerEvent.Pause)
        return Futures.immediateVoidFuture()
    }

    // [MediaLibraryCallback] already started the songs, the state follows from the app's player
    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        onEvent(PlayerEvent.Pause)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> onEvent(PlayerEvent.Next)
            // Also when restarting the current song, the view model decides that the same way
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> onEvent(PlayerEvent.Previous)
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> onEvent(PlayerEvent.SeekTo(positionMs))
        }
        return Futures.immediateVoidFuture()
    }
}
