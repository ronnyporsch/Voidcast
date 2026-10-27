package de.ronnyporsch.voidcast.ui.screen.player

import de.ronnyporsch.voidcast.data.remote.RemoteCommand
import de.ronnyporsch.voidcast.data.remote.RemoteControl
import de.ronnyporsch.voidcast.data.remote.RemoteDevice
import de.ronnyporsch.voidcast.data.repository.MusicRepository
import de.ronnyporsch.voidcast.data.repository.PlayHistory
import de.ronnyporsch.voidcast.domain.model.PlaybackState
import de.ronnyporsch.voidcast.domain.model.audioFormat
import de.ronnyporsch.voidcast.domain.model.PlaybackStatus
import de.ronnyporsch.voidcast.domain.model.RepeatMode
import de.ronnyporsch.voidcast.domain.model.SettingsHolder
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.mvi.MviStore
import de.ronnyporsch.voidcast.platform.AudioPlayer
import de.ronnyporsch.voidcast.platform.CrossfadeAudioPlayer
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class PlayerViewModel(
    private val audioPlayer: AudioPlayer,
    private val repository: MusicRepository,
    private val settingsHolder: SettingsHolder,
    private val history: PlayHistory,
    /** Null where there is no remote control, e.g. in tests. */
    private val remote: RemoteControl? = null,
) : MviStore<PlayerState, PlayerEvent, PlayerEffect>(PlayerState()) {

    private var hasAutoAdvanced = false
    private var hasPreparedNext = false
    private var hasCrossfadeStarted = false
    private var lastError: String? = null

    /** Session id of [PlayerState.remoteDevice]; while set, the local player is stopped. */
    private var remoteSessionId: String? = null
    private var remoteJob: Job? = null

    /** Where to continue once the song that's starting is loaded, e.g. after moving playback here. */
    private var pendingStart: PendingStart? = null

    private class PendingStart(val positionMs: Long, val paused: Boolean)

    /** The songs last played, in their own order; turning shuffle off goes back to it. */
    private var unshuffledSongs: List<Song> = emptyList()

    /** Song whose lyrics are in [PlayerState.lyrics], unless they failed to load. */
    private var lyricsSongId: String? = null

    private val crossfadePlayer: CrossfadeAudioPlayer?
        get() = audioPlayer as? CrossfadeAudioPlayer

    /** Plays the songs; shows the picture of videos, see [de.ronnyporsch.voidcast.platform.VideoSurface]. */
    val player: AudioPlayer get() = audioPlayer

    init {
        launch {
            audioPlayer.state.collectLatest { playerState ->
                // The playback shown is the remote device's
                if (remoteSessionId != null) return@collectLatest
                pendingStart?.let { start ->
                    if (playerState.durationMs > 0 && !playerState.isBuffering) {
                        pendingStart = null
                        if (start.positionMs > 0) audioPlayer.seekTo(start.positionMs)
                        if (start.paused) audioPlayer.pause()
                    }
                }
                if (playerState.error != null && playerState.error != lastError) {
                    emitEffect(PlayerEffect.PlaybackError(playerState.error))
                }
                lastError = playerState.error

                setState {
                    copy(
                        playback = playback.copy(
                            status = when {
                                playerState.isBuffering -> PlaybackStatus.Buffering
                                playerState.isPlaying -> PlaybackStatus.Playing
                                playback.currentSong != null && !playerState.isPlaying -> PlaybackStatus.Paused
                                else -> PlaybackStatus.Idle
                            },
                            positionMs = playerState.positionMs,
                            durationMs = playerState.durationMs,
                        ),
                        videoAspectRatio = playerState.videoAspectRatio,
                    )
                }

                // Reset flags when a new track starts playing
                if (playerState.isPlaying && playerState.positionMs < 1000) {
                    hasAutoAdvanced = false
                    hasPreparedNext = false
                    hasCrossfadeStarted = false
                }

                val playback = currentState.playback
                val settings = settingsHolder.settings.value.playback
                val durationMs = playerState.durationMs
                val positionMs = playerState.positionMs
                val remainingMs = durationMs - positionMs

                // Videos play on their own, the transitions are for songs
                if (durationMs > 0 && playback.currentSong != null && playerState.isPlaying && !currentState.isVideo) {
                    val nextSong = getNextSong()?.takeIf { !playsAsVideo(it) }

                    // Pre-buffer the next track early, so skipping to it doesn't have to wait for it to load either.
                    // Not right away, in case this song gets skipped too; short songs need it before their transition
                    if (!hasPreparedNext && nextSong != null && crossfadePlayer != null) {
                        val transitionLeadMs = if (settings.crossfadeEnabled) {
                            (settings.crossfadeDurationSeconds * 1000L) + 3000L
                        } else {
                            5000L
                        }
                        if (positionMs >= PREFETCH_AFTER_MS || remainingMs <= transitionLeadMs) {
                            hasPreparedNext = true
                            streamUrl(nextSong)?.let { crossfadePlayer?.prepareNext(it) }
                        }
                    }

                    // Start crossfade
                    if (settings.crossfadeEnabled && !hasCrossfadeStarted && nextSong != null) {
                        val crossfadeDurationMs = settings.crossfadeDurationSeconds * 1000L
                        if (remainingMs <= crossfadeDurationMs) {
                            hasCrossfadeStarted = true
                            hasAutoAdvanced = true
                            crossfadePlayer?.startCrossfade(crossfadeDurationMs)
                            advanceToNextSong()
                        }
                    }
                }

                // Auto-advance when track ends (no crossfade case)
                if (!hasAutoAdvanced
                    && !playerState.isPlaying && !playerState.isBuffering
                    && durationMs > 0
                    && positionMs >= durationMs - 500
                    && playback.currentSong != null
                ) {
                    hasAutoAdvanced = true
                    val nextSong = getNextSong()
                    if (settings.gaplessEnabled && crossfadePlayer != null && nextSong != null && hasPreparedNext
                        && !currentState.isVideo && !playsAsVideo(nextSong)
                    ) {
                        crossfadePlayer?.playNextGapless()
                        advanceToNextSong()
                    } else {
                        playNext()
                    }
                }
            }
        }
        launch {
            // Loaded for every song since the lyrics buttons are only shown for songs that have some
            state
                .map { it.playback.currentSong }
                .distinctUntilChanged { old, new -> old?.id == new?.id }
                .collectLatest { song -> if (song != null && song.id != lyricsSongId) loadLyrics(song) }
        }
        launch {
            state
                .map { it.playback.currentSong }
                .distinctUntilChanged { old, new -> old?.id == new?.id }
                .collectLatest { song ->
                    setState { copy(audioFormat = song?.audioFormat, videoFormat = null) }
                    // Videos have their own audio stream, see loadVideoFormat
                    if (song != null && song.audioFormat == null && !playsAsVideo(song)) {
                        // Only informational, e.g. offline there is none
                        repository.getAudioFormat(song).getOrNull()?.let { setState { copy(audioFormat = it) } }
                    }
                }
        }
        launch {
            state
                .map { it.playback.currentSong }
                .distinctUntilChanged { old, new -> old?.id == new?.id }
                .collectLatest { song ->
                    setState { copy(videoHeights = emptyList()) }
                    if (song != null && remoteSessionId == null && playsAsVideo(song)) {
                        repository.getVideoHeights(song).getOrNull()?.let { setState { copy(videoHeights = it) } }
                    }
                }
        }
        launch {
            // Each server has its own history
            settingsHolder.settings.map { it.serverKey }.distinctUntilChanged().collect { history.reload() }
        }
        launch {
            history.songs.collect { setState { copy(recentlyPlayed = it) } }
        }
        launch {
            state
                .map { it.playback.currentSong }
                .distinctUntilChanged { old, new -> old?.id == new?.id }
                // Songs played on other devices are in their history
                .collect { song -> if (song != null && remoteSessionId == null) history.add(song) }
        }
        launch {
            settingsHolder.settings.collectLatest { settings ->
                setState {
                    copy(
                        playback = playback.copy(crossfadeEnabled = settings.playback.crossfadeEnabled),
                        videoQuality = settings.playback.videoQuality,
                    )
                }
            }
        }
    }

    init {
        remote?.let { remote ->
            launch { remote.available.collect { setState { copy(remoteControlAvailable = it) } } }
            launch { remote.devices.collect(::onDevicesChanged) }
            launch { remote.commands.collect(::onRemoteCommand) }
            launch {
                state.map { it.playback }.distinctUntilChanged().collect { if (remoteSessionId == null) remote.report(it) }
            }
        }
    }

    override fun onEvent(event: PlayerEvent) {
        if (remoteSessionId != null && event.toRemoteCommand() != null) {
            sendToRemote(event)
            return
        }
        when (event) {
            is PlayerEvent.PlaySongs -> playSongs(event.songs, event.startIndex, event.shuffle)
            is PlayerEvent.PlayPause -> togglePlayPause()
            is PlayerEvent.Play -> when (currentState.playback.status) {
                PlaybackStatus.Paused -> audioPlayer.resume()
                PlaybackStatus.Idle -> playCurrent()
                PlaybackStatus.Playing, PlaybackStatus.Buffering -> {}
            }
            is PlayerEvent.Pause -> when (currentState.playback.status) {
                PlaybackStatus.Playing, PlaybackStatus.Buffering -> audioPlayer.pause()
                PlaybackStatus.Paused, PlaybackStatus.Idle -> {}
            }
            is PlayerEvent.Next -> playNext()
            is PlayerEvent.Previous -> playPrevious()
            is PlayerEvent.ToggleShuffle -> toggleShuffle()
            is PlayerEvent.ToggleRepeat -> toggleRepeat()
            is PlayerEvent.ToggleCrossfade -> toggleCrossfade()
            is PlayerEvent.ToggleLyrics -> setState { copy(showLyrics = !showLyrics) }
            is PlayerEvent.ShowLyrics -> setState { copy(showLyrics = true) }
            is PlayerEvent.AddToQueue -> addToQueue(event.songs)
            is PlayerEvent.MoveInQueue -> moveInQueue(event.from, event.to)
            is PlayerEvent.RemoveFromQueue -> removeFromQueue(event.index)
            is PlayerEvent.PlayQueueIndex -> playQueueIndex(event.index)
            is PlayerEvent.PlayNow -> playNow(event.song)
            is PlayerEvent.SeekTo -> {
                audioPlayer.seekTo(event.positionMs)
                setState { copy(playback = playback.copy(positionMs = event.positionMs)) }
            }
            is PlayerEvent.SetVideoQuality -> setVideoQuality(event.height)
            is PlayerEvent.SelectDevice -> selectDevice(event.sessionId)
        }
    }

    // Remote control

    private fun PlayerEvent.toRemoteCommand(): RemoteCommand? = when (this) {
        is PlayerEvent.PlaySongs -> RemoteCommand.PlaySongs(songs, startIndex, shuffle)
        PlayerEvent.PlayPause -> RemoteCommand.PlayPause
        PlayerEvent.Play -> RemoteCommand.Play
        PlayerEvent.Pause -> RemoteCommand.Pause
        PlayerEvent.Next -> RemoteCommand.Next
        PlayerEvent.Previous -> RemoteCommand.Previous
        PlayerEvent.ToggleShuffle -> RemoteCommand.SetShuffle(!currentState.playback.shuffle)
        PlayerEvent.ToggleRepeat -> RemoteCommand.SetRepeat(currentState.playback.repeatMode.next())
        is PlayerEvent.AddToQueue -> RemoteCommand.AddToQueue(songs)
        is PlayerEvent.MoveInQueue -> RemoteCommand.MoveInQueue(from, to)
        is PlayerEvent.RemoveFromQueue -> RemoteCommand.RemoveFromQueue(index)
        is PlayerEvent.PlayQueueIndex -> RemoteCommand.PlayQueueIndex(index)
        is PlayerEvent.PlayNow -> RemoteCommand.PlayNow(song)
        is PlayerEvent.SeekTo -> RemoteCommand.SeekTo(positionMs)
        PlayerEvent.ToggleCrossfade, PlayerEvent.ToggleLyrics, PlayerEvent.ShowLyrics,
        is PlayerEvent.SetVideoQuality, is PlayerEvent.SelectDevice -> null
    }

    private fun sendToRemote(event: PlayerEvent) {
        val device = currentState.remoteDevice ?: return
        val command = event.toRemoteCommand() ?: return
        // Shown right away, the device reports back the same (or something newer)
        when (command) {
            is RemoteCommand.SeekTo -> setState { copy(playback = playback.copy(positionMs = command.positionMs)) }
            is RemoteCommand.MoveInQueue -> if (device.isVoidcast) moveInQueue(command.from, command.to)
            else -> {}
        }
        sendToRemote(device, command)
    }

    private fun sendToRemote(device: RemoteDevice, command: RemoteCommand) {
        val remote = remote ?: return
        viewModelScope.launch {
            remote.send(device, command).onFailure {
                emitEffect(PlayerEffect.Message(it.message ?: "Couldn't reach ${device.name}"))
            }
        }
    }

    private fun onDevicesChanged(devices: List<RemoteDevice>) {
        setState { copy(devices = devices) }
        val sessionId = remoteSessionId ?: return
        val device = devices.find { it.sessionId == sessionId }
        if (device == null) {
            val name = currentState.remoteDevice?.name
            leaveRemoteDevice()
            emitEffect(PlayerEffect.Message("${name ?: "The device"} isn't connected anymore"))
        } else {
            showRemotePlayback(device)
        }
    }

    private fun showRemotePlayback(device: RemoteDevice) {
        setState { copy(remoteDevice = device, playback = device.playbackNow().copy(crossfadeEnabled = playback.crossfadeEnabled)) }
    }

    private fun selectDevice(sessionId: String?) {
        if (sessionId == remoteSessionId) return
        val previous = currentState.remoteDevice
        if (sessionId == null) {
            // Continues here where the device is, and stops it there
            val playback = currentState.playback
            leaveRemoteDevice()
            if (previous != null && playback.currentSong != null) {
                load(playback, paused = playback.status == PlaybackStatus.Paused)
                sendToRemote(previous, RemoteCommand.Stop)
            }
            return
        }
        val device = currentState.devices.find { it.sessionId == sessionId } ?: return
        // What plays here (or on the previous device) continues there
        val playback = currentState.playback
        if (previous != null) {
            leaveRemoteDevice()
        } else {
            audioPlayer.stop()
            pendingStart = null
        }
        if (playback.currentSong != null) {
            val load = RemoteCommand.Load(playback, paused = playback.status == PlaybackStatus.Paused)
            viewModelScope.launch {
                remote?.send(device, load)
                    ?.onSuccess { if (previous != null) remote.send(previous, RemoteCommand.Stop) }
                    ?.onFailure { emitEffect(PlayerEffect.Message(it.message ?: "Couldn't reach ${device.name}")) }
            }
        }
        remote?.report(PlaybackState())
        remoteSessionId = sessionId
        setState { copy(isVideo = false, videoAspectRatio = null) }
        showRemotePlayback(device)
        remoteJob = viewModelScope.launch {
            launch { remote?.follow(device) }
            // Moves the position on between the device's reports
            while (true) {
                delay(500)
                currentState.devices.find { it.sessionId == sessionId }?.let(::showRemotePlayback)
            }
        }
    }

    /** Back to this device's player, with nothing playing. */
    private fun leaveRemoteDevice() {
        remoteSessionId = null
        remoteJob?.cancel()
        remoteJob = null
        setState {
            copy(
                remoteDevice = null,
                playback = PlaybackState(
                    shuffle = playback.shuffle,
                    repeatMode = playback.repeatMode,
                    crossfadeEnabled = playback.crossfadeEnabled,
                ),
            )
        }
    }

    /** What another device wants this one to play. */
    private fun onRemoteCommand(command: RemoteCommand) {
        if (remoteSessionId != null) {
            // Starting something here makes this the device that plays; the rest is about what isn't playing here
            when (command) {
                is RemoteCommand.PlaySongs, is RemoteCommand.Load, is RemoteCommand.PlayNow -> leaveRemoteDevice()
                else -> return
            }
        }
        when (command) {
            is RemoteCommand.PlaySongs -> playSongs(
                command.songs,
                command.startIndex,
                command.shuffle,
                PendingStart(command.startPositionMs, paused = false).takeIf { command.startPositionMs > 0 },
            )
            is RemoteCommand.Load -> load(command.playback, command.paused)
            RemoteCommand.Play -> onEvent(PlayerEvent.Play)
            RemoteCommand.Pause -> onEvent(PlayerEvent.Pause)
            RemoteCommand.PlayPause -> onEvent(PlayerEvent.PlayPause)
            RemoteCommand.Stop -> stopPlayback()
            RemoteCommand.Next -> playNext()
            RemoteCommand.Previous -> playPrevious()
            is RemoteCommand.SeekTo -> onEvent(PlayerEvent.SeekTo(command.positionMs))
            is RemoteCommand.SeekBy -> {
                val playback = currentState.playback
                val position = (playback.positionMs + command.deltaMs).coerceIn(0, playback.durationMs.coerceAtLeast(0))
                onEvent(PlayerEvent.SeekTo(position))
            }
            is RemoteCommand.SetShuffle -> if (command.shuffle != currentState.playback.shuffle) toggleShuffle()
            is RemoteCommand.SetRepeat -> setState { copy(playback = playback.copy(repeatMode = command.repeatMode)) }
            is RemoteCommand.AddToQueue -> addToQueue(command.songs)
            is RemoteCommand.MoveInQueue -> moveInQueue(command.from, command.to)
            is RemoteCommand.RemoveFromQueue -> removeFromQueue(command.index)
            is RemoteCommand.PlayQueueIndex -> playQueueIndex(command.index)
            is RemoteCommand.PlayNow -> playNow(command.song)
        }
    }

    /** Continues [playback] here, at its song and position. */
    private fun load(playback: PlaybackState, paused: Boolean) {
        val index = playback.currentIndex.takeIf { it in playback.queue.indices } ?: return
        // A shuffled queue's own order isn't known here
        unshuffledSongs = playback.queue
        hasAutoAdvanced = false
        hasPreparedNext = false
        hasCrossfadeStarted = false
        setState {
            copy(
                playback = this.playback.copy(
                    queue = playback.queue,
                    currentIndex = index,
                    currentSong = playback.queue[index],
                    queuedCount = playback.queuedCount,
                    shuffle = playback.shuffle,
                    repeatMode = playback.repeatMode,
                    positionMs = playback.positionMs,
                    status = PlaybackStatus.Buffering,
                )
            )
        }
        playCurrent(PendingStart(playback.positionMs, paused))
    }

    private suspend fun loadLyrics(song: Song) {
        lyricsSongId = null
        setState { copy(lyrics = LyricsState.Loading) }
        val result = repository.getLyrics(song)
        // Repositories catch the cancellation when the song changes meanwhile
        currentCoroutineContext().ensureActive()
        // Cached unless loading failed, so playing the song again retries
        result.onSuccess { lyricsSongId = song.id }
        setState {
            copy(
                lyrics = result.fold(
                    onSuccess = { lyrics -> lyrics?.let { LyricsState.Loaded(it) } ?: LyricsState.NotFound },
                    onFailure = { LyricsState.Failed(it.message ?: "Couldn't load lyrics") },
                )
            )
        }
    }

    private fun getNextSong(): Song? {
        val state = currentState.playback
        return when {
            state.repeatMode == RepeatMode.One -> state.currentSong
            state.currentIndex + 1 < state.queue.size -> state.queue[state.currentIndex + 1]
            state.repeatMode == RepeatMode.All && state.queue.isNotEmpty() -> state.queue[0]
            else -> null
        }
    }

    private fun advanceToNextSong() {
        val state = currentState.playback
        val nextIndex = state.currentIndex + 1
        when {
            state.repeatMode == RepeatMode.One -> {
                // state stays the same
            }
            nextIndex < state.queue.size -> {
                setState {
                    copy(playback = playback.movedTo(nextIndex))
                }
            }
            state.repeatMode == RepeatMode.All && state.queue.isNotEmpty() -> {
                setState {
                    copy(playback = playback.movedTo(0))
                }
            }
        }
    }

    private fun playSongs(songs: List<Song>, startIndex: Int, shuffle: Boolean?, start: PendingStart? = null) {
        val targetShuffle = shuffle ?: currentState.playback.shuffle
        unshuffledSongs = songs
        val queue: List<Song>
        val index: Int

        // A "Shuffle" button shuffles everything; a song clicked while shuffle is on gets played first
        val clickedSong = if (shuffle == true) null else songs.getOrNull(startIndex)
        if (targetShuffle && songs.isNotEmpty()) {
            if (clickedSong != null) {
                val otherSongs = songs.toMutableList()
                otherSongs.removeAt(startIndex)
                queue = listOf(clickedSong) + otherSongs.shuffled()
                index = 0
            } else {
                queue = songs.shuffled()
                index = 0
            }
        } else {
            queue = songs
            index = startIndex
        }

        hasAutoAdvanced = false
        hasPreparedNext = false
        hasCrossfadeStarted = false
        setState {
            copy(
                playback = playback.copy(
                    queue = queue,
                    currentIndex = index,
                    currentSong = queue.getOrNull(index),
                    queuedCount = 0,
                    shuffle = targetShuffle,
                    status = PlaybackStatus.Buffering,
                )
            )
        }
        playCurrent(start)
    }

    /** Current song at [index]; skipping forward past queued songs counts them as played. */
    private fun PlaybackState.movedTo(index: Int) = copy(
        currentIndex = index,
        currentSong = queue[index],
        queuedCount = if (index > currentIndex) (queuedCount - (index - currentIndex)).coerceAtLeast(0) else 0,
    )

    /** Plays [songs] after the current song and the songs queued before them; starts playing if nothing is. */
    private fun addToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        val playback = currentState.playback
        if (playback.currentSong == null) {
            playSongs(songs, 0, null)
            return
        }
        val insertAt = (playback.currentIndex + 1 + playback.queuedCount).coerceAtMost(playback.queue.size)
        val queue = playback.queue.take(insertAt) + songs + playback.queue.drop(insertAt)
        setState { copy(playback = playback.copy(queue = queue, queuedCount = playback.queuedCount + songs.size)) }
        onUpcomingChanged()
    }

    private fun moveInQueue(from: Int, to: Int) {
        val playback = currentState.playback
        if (from !in playback.queue.indices || to !in playback.queue.indices || from == to) return
        val queue = playback.queue.toMutableList().apply { add(to, removeAt(from)) }
        val current = playback.currentIndex
        val currentIndex = when {
            from == current -> to
            from < current && to >= current -> current - 1
            from > current && to <= current -> current + 1
            else -> current
        }
        val queuedCount = if (from > current && to > current) {
            // The queued songs follow the current one; a song moved into or out of them joins or leaves them
            val fromQueued = from <= current + playback.queuedCount
            val remaining = playback.queuedCount - if (fromQueued) 1 else 0
            val toQueued = to <= current + remaining + if (fromQueued) 1 else 0
            remaining + if (toQueued) 1 else 0
        } else {
            0
        }
        setState {
            copy(playback = playback.copy(queue = queue, currentIndex = currentIndex, queuedCount = queuedCount))
        }
        onUpcomingChanged()
    }

    private fun removeFromQueue(index: Int) {
        val playback = currentState.playback
        // The current song is skipped rather than removed
        if (index !in playback.queue.indices || index == playback.currentIndex) return
        val current = playback.currentIndex
        setState {
            copy(
                playback = playback.copy(
                    queue = playback.queue.filterIndexed { i, _ -> i != index },
                    currentIndex = if (index < current) current - 1 else current,
                    queuedCount = if (index > current && index <= current + playback.queuedCount) {
                        playback.queuedCount - 1
                    } else {
                        playback.queuedCount
                    },
                )
            )
        }
        onUpcomingChanged()
    }

    private fun playQueueIndex(index: Int) {
        if (index !in currentState.playback.queue.indices) return
        hasAutoAdvanced = false
        hasPreparedNext = false
        hasCrossfadeStarted = false
        setState { copy(playback = playback.movedTo(index)) }
        playCurrent()
    }

    /** Plays [song] right away, keeping the rest of the queue. */
    private fun playNow(song: Song) {
        val playback = currentState.playback
        if (playback.currentSong == null) {
            playSongs(listOf(song), 0, null)
            return
        }
        val index = playback.currentIndex + 1
        hasAutoAdvanced = false
        hasPreparedNext = false
        hasCrossfadeStarted = false
        setState {
            copy(
                playback = playback.copy(
                    queue = playback.queue.take(index) + song + playback.queue.drop(index),
                    currentIndex = index,
                    currentSong = song,
                )
            )
        }
        playCurrent()
    }

    /** The song after the current one may be a different one now; then the pre-buffered one is replaced. */
    private fun onUpcomingChanged() {
        if (!hasPreparedNext || hasCrossfadeStarted || remoteSessionId != null) return
        val url = getNextSong()?.takeIf { !playsAsVideo(it) }?.let { streamUrl(it) } ?: return
        crossfadePlayer?.prepareNext(url)
    }

    /** Videos play with picture where the player can show it, otherwise as songs. */
    private fun playsAsVideo(song: Song) = song.isVideo && audioPlayer.supportsVideo

    private fun videoUrl(song: Song): String? =
        if (playsAsVideo(song)) repository.getVideoUrl(song, settingsHolder.settings.value.playback.videoQuality) else null

    private fun setVideoQuality(height: Int) {
        settingsHolder.update { copy(playback = playback.copy(videoQuality = height)) }
        setState { copy(videoQuality = height) }
        val song = currentState.playback.currentSong ?: return
        if (!currentState.isVideo) return
        // Continues where it was, in the new resolution
        val url = videoUrl(song) ?: return
        audioPlayer.playVideo(url, currentState.playback.positionMs)
        loadVideoFormat(song)
    }

    private var videoFormatJob: Job? = null

    /** Codecs and bitrates of the streams the current video plays with; only informational. */
    private fun loadVideoFormat(song: Song) {
        videoFormatJob?.cancel()
        setState { copy(videoFormat = null, audioFormat = null) }
        val quality = settingsHolder.settings.value.playback.videoQuality
        videoFormatJob = viewModelScope.launch {
            val format = repository.getVideoFormat(song, quality).getOrNull() ?: return@launch
            if (currentState.playback.currentSong?.id == song.id && currentState.isVideo) {
                setState { copy(videoFormat = format.video, audioFormat = format.audio) }
            }
        }
    }

    /** [start] is where to continue, once the song is loaded. */
    private fun playCurrent(start: PendingStart? = null) {
        // Before playing: players may report their first state right away
        pendingStart = start
        val song = currentState.playback.currentSong ?: return
        val videoUrl = videoUrl(song)
        setState { copy(isVideo = videoUrl != null) }
        if (videoUrl != null) {
            audioPlayer.playVideo(videoUrl)
            loadVideoFormat(song)
            return
        }
        val url = streamUrl(song)
        if (url == null) {
            audioPlayer.stop()
            emitEffect(PlayerEffect.PlaybackError("\"${song.title}\" isn't available offline"))
            return
        }
        if (crossfadePlayer?.playPrepared(url) != true) audioPlayer.play(url)
    }

    /** Null if the song can't be played, i.e. while not connected to the server it isn't downloaded. */
    private fun streamUrl(song: Song): String? =
        try {
            repository.getStreamUrl(song.id)
        } catch (_: IllegalStateException) {
            null
        }

    private fun togglePlayPause() {
        val status = currentState.playback.status
        when (status) {
            PlaybackStatus.Playing, PlaybackStatus.Buffering -> audioPlayer.pause()
            PlaybackStatus.Paused -> audioPlayer.resume()
            PlaybackStatus.Idle -> playCurrent()
        }
    }

    private fun playNext() {
        val state = currentState.playback
        val nextIndex = state.currentIndex + 1
        hasPreparedNext = false
        hasCrossfadeStarted = false
        when {
            state.repeatMode == RepeatMode.One -> {
                hasAutoAdvanced = false
                playCurrent()
            }
            nextIndex < state.queue.size -> {
                hasAutoAdvanced = false
                setState {
                    copy(playback = playback.movedTo(nextIndex))
                }
                playCurrent()
            }
            state.repeatMode == RepeatMode.All && state.queue.isNotEmpty() -> {
                hasAutoAdvanced = false
                setState {
                    copy(playback = playback.movedTo(0))
                }
                playCurrent()
            }
            else -> stopPlayback()
        }
    }

    private fun stopPlayback() {
        audioPlayer.stop()
        pendingStart = null
        setState {
            copy(
                playback = playback.copy(
                    status = PlaybackStatus.Idle,
                    currentSong = null,
                    currentIndex = -1,
                    queuedCount = 0,
                )
            )
        }
    }

    private fun playPrevious() {
        val state = currentState.playback
        if (state.positionMs > 3000) {
            audioPlayer.seekTo(0)
            return
        }
        val prevIndex = state.currentIndex - 1
        if (prevIndex >= 0) {
            hasAutoAdvanced = false
            hasPreparedNext = false
            hasCrossfadeStarted = false
            setState {
                copy(playback = playback.movedTo(prevIndex))
            }
            playCurrent()
        }
    }

    /**
     * Reorders the queue: on, the songs other than the current and the queued ones follow them in random order; off,
     * they go back to the order they were played in, around the current song.
     */
    private fun toggleShuffle() {
        val playback = currentState.playback
        val shuffle = !playback.shuffle
        val current = playback.currentIndex
        if (current !in playback.queue.indices) {
            setState { copy(playback = playback.copy(shuffle = shuffle)) }
            return
        }
        val fixed = playback.queue.subList(current, current + 1 + playback.queuedCount)
        val others = playback.queue.filterIndexed { i, _ -> i < current || i > current + playback.queuedCount }
        val queue: List<Song>
        val index: Int
        if (shuffle) {
            queue = fixed + others.shuffled()
            index = 0
        } else {
            // Songs not among them, e.g. added to the queue, come last
            val order = unshuffledSongs.withIndex().reversed().associate { (i, song) -> song.id to i }
            val sorted = others.sortedBy { order[it.id] ?: Int.MAX_VALUE }
            val currentOrder = order[playback.currentSong?.id] ?: -1
            val before = sorted.takeWhile { (order[it.id] ?: Int.MAX_VALUE) < currentOrder }
            queue = before + fixed + sorted.drop(before.size)
            index = before.size
        }
        setState {
            copy(playback = playback.copy(queue = queue, currentIndex = index, shuffle = shuffle))
        }
        onUpcomingChanged()
    }

    private fun toggleRepeat() {
        setState { copy(playback = playback.copy(repeatMode = playback.repeatMode.next())) }
    }

    private fun RepeatMode.next() = when (this) {
        RepeatMode.Off -> RepeatMode.All
        RepeatMode.All -> RepeatMode.One
        RepeatMode.One -> RepeatMode.Off
    }

    private fun toggleCrossfade() {
        settingsHolder.update {
            copy(playback = playback.copy(crossfadeEnabled = !playback.crossfadeEnabled))
        }
    }
}

/** How long a song plays before the next one gets pre-buffered. */
private const val PREFETCH_AFTER_MS = 3000L
