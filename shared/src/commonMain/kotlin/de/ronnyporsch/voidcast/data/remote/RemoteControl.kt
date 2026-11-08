package de.ronnyporsch.voidcast.data.remote

import de.ronnyporsch.voidcast.data.jellyfin.ClientCapabilitiesDto
import de.ronnyporsch.voidcast.data.jellyfin.GeneralCommandDto
import de.ronnyporsch.voidcast.data.jellyfin.PlayRequestDto
import de.ronnyporsch.voidcast.data.jellyfin.PlaybackReportDto
import de.ronnyporsch.voidcast.data.jellyfin.PlaystateRequestDto
import de.ronnyporsch.voidcast.data.jellyfin.QueueItemDto
import de.ronnyporsch.voidcast.data.jellyfin.SessionInfoDto
import de.ronnyporsch.voidcast.data.jellyfin.SocketMessageDto
import de.ronnyporsch.voidcast.data.jellyfin.toSong
import de.ronnyporsch.voidcast.data.repository.MusicServerConnection
import de.ronnyporsch.voidcast.domain.model.PlaybackState
import de.ronnyporsch.voidcast.domain.model.PlaybackStatus
import de.ronnyporsch.voidcast.domain.model.RepeatMode
import de.ronnyporsch.voidcast.domain.model.ServerType
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.domain.model.isYouTubeId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.math.abs
import kotlin.time.TimeSource

/**
 * Remote control between the user's clients through Jellyfin's sessions: lists the other clients ([devices]),
 * controls them ([send]), takes their commands for this device's player ([commands]) and reports its playback
 * ([report]), which also keeps the server's play counts and "now playing" up to date.
 *
 * Jellyfin's own commands only carry library items and it doesn't pass on what a client has queued, so between
 * Voidcast instances [RemoteCommand]s and the whole player state travel as JSON in the arguments of a general command.
 */
class RemoteControl(private val connection: MusicServerConnection, scope: CoroutineScope) {
    private val api get() = connection.jellyfinApi

    /** Whether the server lets clients control each other; only Jellyfin does. */
    val available: StateFlow<Boolean> = connection.serverType.map { it == ServerType.Jellyfin }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _commands = MutableSharedFlow<RemoteCommand>(extraBufferCapacity = 16)

    /** What other clients want this device's player to do. */
    val commands: SharedFlow<RemoteCommand> = _commands.asSharedFlow()

    /** The other clients, as Jellyfin reports them, with when their playback last changed. */
    private val sessions = MutableStateFlow<Map<String, ReportedSession>>(emptyMap())

    /** Player states of other Voidcast instances that are being followed, by session id. */
    private val peerStates = MutableStateFlow<Map<String, Reported>>(emptyMap())

    /** Other clients of the user whose playback can be controlled, the ones playing something first. */
    val devices: StateFlow<List<RemoteDevice>> = combine(sessions, peerStates) { sessions, peers ->
        sessions.values.map { (session, reported) ->
            val peer = peers[session.id]
            RemoteDevice(
                sessionId = session.id,
                name = session.deviceName.ifBlank { session.client },
                client = session.client,
                isVoidcast = session.client == CLIENT_NAME,
                playback = peer?.playback ?: reported.playback,
                reportedAt = peer?.at ?: reported.at,
            )
        }.sortedWith(compareBy({ it.playback.currentSong == null }, { it.name.lowercase() }))
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private var ownSessionId: String? = null
    private var sendToSocket: (suspend (SocketMessageDto) -> Unit)? = null
    private val sendMutex = Mutex()

    /** Last state of this device's player, see [report]. */
    private val localPlayback = MutableStateFlow(PlaybackState())

    /** Other Voidcast instances following this one's playback, with when they last asked to. */
    private val followers = mutableMapOf<String, TimeSource.Monotonic.ValueTimeMark>()
    private val followerUpdates = Channel<String>(Channel.UNLIMITED)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    init {
        scope.launch {
            // Also stops on logout, which resets the server type
            combine(connection.connections, connection.serverType, ::Pair).collectLatest { (count, serverType) ->
                sessions.value = emptyMap()
                peerStates.value = emptyMap()
                followers.clear()
                if (count > 0 && serverType == ServerType.Jellyfin) {
                    coroutineScope {
                        launch { runSocket() }
                        launch { runReporting() }
                    }
                }
            }
        }
    }

    /** Tells the server (and followers) about this device's playback; call on every change of it. */
    fun report(playback: PlaybackState) {
        localPlayback.value = playback
    }

    /** Sends [command] to the [device]'s player; fails with a user-facing message if that can't do it. */
    suspend fun send(device: RemoteDevice, command: RemoteCommand): Result<Unit> = sendMutex.withLock {
        runCatching {
            if (device.isVoidcast) {
                sendToPeer(device.sessionId, PeerMessage.Command(command))
            } else {
                sendNative(device, command)
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    /**
     * Keeps getting [device]'s whole player state while this runs (until cancelled), if it's a Voidcast instance;
     * other clients only report their current song, which is in [devices] anyway.
     */
    suspend fun follow(device: RemoteDevice) {
        if (!device.isVoidcast) return
        try {
            while (true) {
                val own = ownSessionId ?: api.getOwnSessionId()?.also { ownSessionId = it }
                if (own != null) {
                    runCatching { sendToPeer(device.sessionId, PeerMessage.Follow(own)) }
                        .onFailure { if (it is CancellationException) throw it }
                }
                delay(FOLLOW_RENEWAL_MS)
            }
        } finally {
            peerStates.value -= device.sessionId
        }
    }

    // Connection to the server

    private suspend fun runSocket() {
        var retryDelayMs = 2_000L
        while (true) {
            try {
                api.runSocket(
                    onConnected = { send ->
                        sendToSocket = send
                        api.postCapabilities(CAPABILITIES)
                        ownSessionId = api.getOwnSessionId()
                        // Pushes the sessions whenever one changes, at most every 1.5 s
                        send(SocketMessageDto("SessionsStart", JsonPrimitive("0,1500")))
                        sessions.value = api.getSessions().toReported(sessions.value)
                        retryDelayMs = 2_000L
                    },
                    onMessage = ::handle,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline or the server restarts; the next connection gets everything again
            } finally {
                sendToSocket = null
            }
            delay(retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
        }
    }

    private suspend fun handle(message: SocketMessageDto) {
        val data = message.data ?: return
        try {
            when (message.messageType) {
                "Sessions" -> sessions.value = json.decodeFromJsonElement<List<SessionInfoDto>>(data).toReported(sessions.value)
                "Play" -> handlePlay(json.decodeFromJsonElement(data))
                "Playstate" -> handlePlaystate(json.decodeFromJsonElement(data))
                "GeneralCommand" -> handleGeneralCommand(json.decodeFromJsonElement(data))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Something this client doesn't understand
        }
    }

    private suspend fun handlePlay(request: PlayRequestDto) {
        val songs = connection.jellyfinLibrary.getSongs(request.itemIds).getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        val startIndex = (request.startIndex ?: 0).coerceIn(songs.indices)
        val command = when (request.playCommand) {
            "PlayNext" -> RemoteCommand.PlayNext(songs)
            "PlayLast" -> RemoteCommand.AddToQueue(songs)
            "PlayShuffle" -> RemoteCommand.PlaySongs(songs, 0, shuffle = true)
            else -> RemoteCommand.PlaySongs(songs, startIndex, startPositionMs = (request.startPositionTicks ?: 0) / TICKS_PER_MS)
        }
        _commands.emit(command)
    }

    private suspend fun handlePlaystate(request: PlaystateRequestDto) {
        val command = when (request.command) {
            "Stop" -> RemoteCommand.Stop
            "Pause" -> RemoteCommand.Pause
            "Unpause" -> RemoteCommand.Play
            "PlayPause" -> RemoteCommand.PlayPause
            "NextTrack" -> RemoteCommand.Next
            "PreviousTrack" -> RemoteCommand.Previous
            "Seek" -> RemoteCommand.SeekTo((request.seekPositionTicks ?: return) / TICKS_PER_MS)
            "Rewind" -> RemoteCommand.SeekBy(-SKIP_MS)
            "FastForward" -> RemoteCommand.SeekBy(SKIP_MS)
            else -> return
        }
        _commands.emit(command)
    }

    private suspend fun handleGeneralCommand(command: GeneralCommandDto) {
        command.arguments[PEER_ARGUMENT]?.let { return handlePeerMessage(json.decodeFromString<PeerMessage>(it)) }
        when (command.name) {
            "SetRepeatMode" -> command.arguments["RepeatMode"]?.toRepeatMode()?.let { _commands.emit(RemoteCommand.SetRepeat(it)) }
            "SetShuffleQueue" -> command.arguments["ShuffleMode"]?.let { _commands.emit(RemoteCommand.SetShuffle(it == "Shuffle")) }
            "SetPlaybackOrder" -> command.arguments["PlaybackOrder"]?.let { _commands.emit(RemoteCommand.SetShuffle(it == "Shuffle")) }
        }
    }

    private suspend fun handlePeerMessage(message: PeerMessage) {
        when (message) {
            is PeerMessage.Command -> _commands.emit(message.command)
            is PeerMessage.Follow -> {
                followers[message.sessionId] = TimeSource.Monotonic.markNow()
                followerUpdates.trySend(message.sessionId)
            }
            is PeerMessage.State -> peerStates.value += message.sessionId to Reported(message.playback, TimeSource.Monotonic.markNow())
        }
    }

    private suspend fun sendToPeer(sessionId: String, message: PeerMessage) {
        api.sendGeneralCommand(sessionId, GeneralCommandDto(PEER_COMMAND, mapOf(PEER_ARGUMENT to json.encodeToString(message))))
    }

    /** Jellyfin's own commands, for clients other than Voidcast. */
    private suspend fun sendNative(device: RemoteDevice, command: RemoteCommand) {
        val id = device.sessionId
        when (command) {
            is RemoteCommand.PlaySongs -> playNative(device, command.songs, command.startIndex, command.shuffle == true, command.startPositionMs)
            is RemoteCommand.Load -> {
                val playback = command.playback
                playNative(device, playback.queue, playback.currentIndex.coerceAtLeast(0), false, playback.positionMs)
                if (command.paused) api.sendPlaystate(id, "Pause")
            }
            RemoteCommand.Play -> api.sendPlaystate(id, "Unpause")
            RemoteCommand.Pause -> api.sendPlaystate(id, "Pause")
            RemoteCommand.PlayPause -> api.sendPlaystate(id, "PlayPause")
            RemoteCommand.Stop -> api.sendPlaystate(id, "Stop")
            RemoteCommand.Next -> api.sendPlaystate(id, "NextTrack")
            RemoteCommand.Previous -> api.sendPlaystate(id, "PreviousTrack")
            is RemoteCommand.SeekTo -> api.sendPlaystate(id, "Seek", command.positionMs * TICKS_PER_MS)
            is RemoteCommand.SeekBy -> api.sendPlaystate(id, "Seek", (device.playbackNow().positionMs + command.deltaMs).coerceAtLeast(0) * TICKS_PER_MS)
            is RemoteCommand.SetShuffle ->
                api.sendGeneralCommand(id, GeneralCommandDto("SetShuffleQueue", mapOf("ShuffleMode" to if (command.shuffle) "Shuffle" else "Sorted")))
            is RemoteCommand.SetRepeat ->
                api.sendGeneralCommand(id, GeneralCommandDto("SetRepeatMode", mapOf("RepeatMode" to command.repeatMode.toJellyfin())))
            is RemoteCommand.AddToQueue -> api.sendPlay(id, librarySongIds(device, command.songs), "PlayNext")
            is RemoteCommand.PlayNext -> api.sendPlay(id, librarySongIds(device, command.songs), "PlayNext")
            is RemoteCommand.PlayNow -> api.sendPlay(id, librarySongIds(device, listOf(command.song)), "PlayNow")
            is RemoteCommand.MoveInQueue, is RemoteCommand.RemoveFromQueue, is RemoteCommand.PlayQueueIndex ->
                throw UnsupportedOperationException("${device.name} doesn't share its queue")
        }
    }

    private suspend fun playNative(device: RemoteDevice, songs: List<Song>, startIndex: Int, shuffle: Boolean, startPositionMs: Long) {
        val ids = librarySongIds(device, songs)
        val library = songs.withIndex().filter { !it.value.id.isYouTubeId }
        // Starts with the song that was asked for, or the next one from the library
        val start = library.indexOfFirst { it.index >= startIndex }.coerceAtLeast(0)
        val exact = library.getOrNull(start)?.index == startIndex
        api.sendPlay(
            device.sessionId,
            ids,
            if (shuffle) "PlayShuffle" else "PlayNow",
            startIndex = start,
            startPositionTicks = if (exact && startPositionMs > 0) startPositionMs * TICKS_PER_MS else null,
        )
    }

    private fun librarySongIds(device: RemoteDevice, songs: List<Song>): List<String> =
        songs.map { it.id }.filter { !it.isYouTubeId }.ifEmpty {
            throw UnsupportedOperationException("${device.name} can only play songs from the library")
        }

    // Reporting this device's playback

    private suspend fun runReporting(): Unit = coroutineScope {
        launch { reportToServer() }
        launch { reportToFollowers() }
    }

    /** Start, progress (every 10 s and on changes) and stop of library songs, like other Jellyfin clients do. */
    private suspend fun reportToServer() {
        var reported: PlaybackState? = null
        var reportedAt = TimeSource.Monotonic.markNow()
        // Not collectLatest: a slow request mustn't get cancelled by the next position update, which come in often
        localPlayback.collect { playback ->
            val previous = reported
            val song = playback.currentSong?.takeIf { !it.id.isYouTubeId && playback.status != PlaybackStatus.Idle }
            val previousSong = previous?.currentSong
            try {
                if (previous != null && previousSong != null &&
                    (song == null || song.id != previousSong.id || playback.currentIndex != previous.currentIndex)
                ) {
                    reported = null
                    api.reportPlaybackStopped(previous.toReport(previousSong))
                }
                if (song == null) return@collect
                if (reported == null) {
                    reported = playback
                    reportedAt = TimeSource.Monotonic.markNow()
                    api.reportPlaybackStart(playback.toReport(song))
                    return@collect
                }
                val event = when {
                    previous == null -> null
                    playback.isPaused != previous.isPaused -> if (playback.isPaused) "pause" else "unpause"
                    playback.repeatMode != previous.repeatMode -> "repeatmodechange"
                    playback.shuffle != previous.shuffle -> "shufflemodechange"
                    playback.jumpedFrom(previous, reportedAt) -> "timeupdate"
                    reportedAt.elapsedNow().inWholeMilliseconds >= PROGRESS_INTERVAL_MS -> "timeupdate"
                    else -> null
                } ?: return@collect
                reported = playback
                reportedAt = TimeSource.Monotonic.markNow()
                api.reportPlaybackProgress(playback.toReport(song, event))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Only informational; reported again with the next change
            }
        }
    }

    /** The whole player state, to the Voidcast instances following it (see [follow]), on changes and every 10 s. */
    private suspend fun reportToFollowers(): Unit = coroutineScope {
        var sent: PlaybackState? = null
        var sentAt = TimeSource.Monotonic.markNow()
        launch {
            // A new follower gets the state right away
            for (sessionId in followerUpdates) {
                val own = ownSessionId ?: continue
                runCatching { sendToPeer(sessionId, PeerMessage.State(own, localPlayback.value)) }
                    .onFailure { if (it is CancellationException) throw it }
            }
        }
        // The position isn't sent with every update, followers move it on themselves while playing
        localPlayback.collect { playback ->
            val previous = sent
            val changed = previous == null || playback.withoutProgress() != previous.withoutProgress() ||
                playback.jumpedFrom(previous, sentAt) || sentAt.elapsedNow().inWholeMilliseconds >= PROGRESS_INTERVAL_MS
            if (!changed) return@collect
            followers.entries.removeAll { it.value.elapsedNow().inWholeMilliseconds > FOLLOW_RENEWAL_MS * 3 }
            val own = ownSessionId ?: return@collect
            sent = playback
            sentAt = TimeSource.Monotonic.markNow()
            for (follower in followers.keys.toList()) {
                runCatching { sendToPeer(follower, PeerMessage.State(own, playback)) }
                    .onFailure { if (it is CancellationException) throw it }
            }
        }
    }

    private fun PlaybackState.toReport(song: Song, event: String? = null) = PlaybackReportDto(
        itemId = song.id,
        positionTicks = positionMs * TICKS_PER_MS,
        isPaused = isPaused,
        repeatMode = repeatMode.toJellyfin(),
        playbackOrder = if (shuffle) "Shuffle" else "Default",
        eventName = event,
        // Library songs only, the server rejects others
        nowPlayingQueue = queue.mapIndexedNotNull { index, it ->
            if (it.id.isYouTubeId) null else QueueItemDto(it.id, "playlistItem$index")
        },
        playlistItemId = "playlistItem$currentIndex",
    )

    private fun List<SessionInfoDto>.toReported(previous: Map<String, ReportedSession>): Map<String, ReportedSession> {
        val ownDeviceId = api.session?.deviceId
        val userId = api.session?.userId
        return filter { it.deviceId != ownDeviceId && it.userId == userId && it.supportsRemoteControl && "Audio" in it.playableMediaTypes }
            .associate { session ->
                val playback = session.toPlayback()
                val old = previous[session.id]
                // Jellyfin sends every session whenever one changes; the position is as of the session's last report
                val reported = if (old != null && old.reported.playback == playback) old.reported else Reported(playback, TimeSource.Monotonic.markNow())
                session.id to ReportedSession(session, reported)
            }
    }

    private fun SessionInfoDto.toPlayback(): PlaybackState {
        val song = nowPlayingItem?.takeIf { it.mediaType == "Audio" || it.type == "Audio" }?.toSong()
        return PlaybackState(
            status = when {
                song == null -> PlaybackStatus.Idle
                playState.isPaused -> PlaybackStatus.Paused
                else -> PlaybackStatus.Playing
            },
            currentSong = song,
            queue = listOfNotNull(song),
            currentIndex = if (song != null) 0 else -1,
            positionMs = (playState.positionTicks ?: 0) / TICKS_PER_MS,
            durationMs = (nowPlayingItem?.runTimeTicks ?: 0) / TICKS_PER_MS,
            shuffle = playState.playbackOrder == "Shuffle",
            repeatMode = playState.repeatMode?.toRepeatMode() ?: RepeatMode.Off,
        )
    }

    private data class Reported(val playback: PlaybackState, val at: TimeSource.Monotonic.ValueTimeMark)

    private data class ReportedSession(val session: SessionInfoDto, val reported: Reported)

    /** Between Voidcast instances, as JSON in the [PEER_ARGUMENT] of a general command. */
    @Serializable
    private sealed interface PeerMessage {
        @Serializable @SerialName("Command")
        data class Command(val command: RemoteCommand) : PeerMessage

        /** The session [sessionId] wants to get the player state for a while, see [follow]. */
        @Serializable @SerialName("Follow")
        data class Follow(val sessionId: String) : PeerMessage

        /** The player state of the session [sessionId]. */
        @Serializable @SerialName("State")
        data class State(val sessionId: String, val playback: PlaybackState) : PeerMessage
    }

    companion object {
        const val CLIENT_NAME = "Voidcast"

        private const val TICKS_PER_MS = 10_000L
        private const val SKIP_MS = 10_000L
        private const val PROGRESS_INTERVAL_MS = 10_000L
        private const val FOLLOW_RENEWAL_MS = 20_000L

        /** Jellyfin only forwards commands of its own; other clients never get this one from Voidcast. */
        private const val PEER_COMMAND = "SendString"
        private const val PEER_ARGUMENT = "Voidcast"

        private val CAPABILITIES = ClientCapabilitiesDto(
            playableMediaTypes = listOf("Audio"),
            supportedCommands = listOf("SetRepeatMode", "SetShuffleQueue", "SetPlaybackOrder", "PlayState", "Play", "PlayNext"),
            supportsMediaControl = true,
        )
    }
}

private val PlaybackState.isPaused: Boolean get() = status == PlaybackStatus.Paused

/** Without what changes as it plays; buffering counts as playing. */
private fun PlaybackState.withoutProgress(): PlaybackState = copy(
    positionMs = 0,
    durationMs = 0,
    status = if (status == PlaybackStatus.Buffering) PlaybackStatus.Playing else status,
)

/** Whether the position moved other than by playing since [previous] (reported at [since]), i.e. it was seeked. */
private fun PlaybackState.jumpedFrom(previous: PlaybackState, since: TimeSource.Monotonic.ValueTimeMark): Boolean {
    val expected = previous.positionMs + if (previous.status == PlaybackStatus.Playing) since.elapsedNow().inWholeMilliseconds else 0
    return abs(positionMs - expected) > 3_000
}

private fun RepeatMode.toJellyfin() = when (this) {
    RepeatMode.Off -> "RepeatNone"
    RepeatMode.All -> "RepeatAll"
    RepeatMode.One -> "RepeatOne"
}

private fun String.toRepeatMode() = when (this) {
    "RepeatAll" -> RepeatMode.All
    "RepeatOne" -> RepeatMode.One
    "RepeatNone" -> RepeatMode.Off
    else -> null
}
