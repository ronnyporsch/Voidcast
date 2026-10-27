package de.ronnyporsch.voidcast.data.offline

import de.ronnyporsch.voidcast.data.repository.MusicRepository
import de.ronnyporsch.voidcast.domain.model.Album
import de.ronnyporsch.voidcast.domain.model.Playlist
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.platform.SongFileStore
import de.ronnyporsch.voidcast.platform.readAppFile
import de.ronnyporsch.voidcast.platform.writeAppFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
private data class DownloadedPlaylist(val server: String, val playlist: Playlist)

@Serializable
private data class DownloadedSong(val server: String, val song: Song)

@Serializable
private data class DownloadedAlbum(val server: String, val albumId: String, val name: String, val songs: List<Song>)

/** How far the songs of a downloaded playlist or album are downloaded. */
data class PlaylistDownloadStatus(
    val downloadedSongs: Int,
    val totalSongs: Int,
    /** Songs are being downloaded right now. */
    val isRunning: Boolean,
) {
    val isComplete: Boolean get() = !isRunning && downloadedSongs >= totalSongs
}

/**
 * Playlists, albums and songs the user downloaded for offline playback. Their songs are downloaded in original quality
 * into [files]; playlists and albums are kept in sync with the server: songs added to them get downloaded, removed ones
 * deleted. A song's file stays as long as a downloaded playlist or album contains it or it got downloaded on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDownloads(
    /** Must talk to the server, not fall back to offline data. */
    private val repository: MusicRepository,
    private val files: SongFileStore?,
    private val serverKey: () -> String,
) {
    val isSupported: Boolean get() = files != null

    // One thread, so the state below needs no locking; the downloads themselves run on the file store's dispatcher
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var entries: List<DownloadedPlaylist>? = null
    private var songEntries: List<DownloadedSong>? = null
    private var albumEntries: List<DownloadedAlbum>? = null
    /** The single songs changed since [syncSongs]' job read them. */
    private var songsChanged = false
    private val jobs = mutableMapOf<String, Job>()

    private val _status = MutableStateFlow<Map<String, PlaylistDownloadStatus>>(emptyMap())
    /** The downloaded playlists of the current server, by playlist id. */
    val status: StateFlow<Map<String, PlaylistDownloadStatus>> = _status.asStateFlow()

    private val _albumStatus = MutableStateFlow<Map<String, PlaylistDownloadStatus>>(emptyMap())
    /** The downloaded albums of the current server, by album id. */
    val albumStatus: StateFlow<Map<String, PlaylistDownloadStatus>> = _albumStatus.asStateFlow()

    private val _downloadedSongIds = MutableStateFlow<Set<String>>(emptySet())
    /** The songs of the current server whose files are on the device. */
    val downloadedSongIds: StateFlow<Set<String>> = _downloadedSongIds.asStateFlow()

    private val _queuedSongIds = MutableStateFlow<Set<String>>(emptySet())
    /** Songs of the current server waiting to be downloaded by a running sync. */
    val queuedSongIds: StateFlow<Set<String>> = _queuedSongIds.asStateFlow()

    private val _downloadingSongIds = MutableStateFlow<Set<String>>(emptySet())
    /** Songs whose files are being downloaded right now. */
    val downloadingSongIds: StateFlow<Set<String>> = _downloadingSongIds.asStateFlow()
    private val transferring = mutableSetOf<String>()

    private val _singleSongIds = MutableStateFlow<Set<String>>(emptySet())
    /** The songs of the current server that got downloaded on their own, whether their files are there yet or not. */
    val singleSongIds: StateFlow<Set<String>> = _singleSongIds.asStateFlow()

    init {
        if (files != null) {
            scope.launch { publish() }
            // Picks up songs added to downloaded playlists elsewhere, e.g. by another client, while the app runs
            scope.launch {
                while (true) {
                    delay(SYNC_INTERVAL)
                    if (repository.isConfigured) syncAll()
                }
            }
        }
    }

    /** Downloads [playlist] and keeps it downloaded. */
    fun download(playlist: Playlist) {
        if (files == null) return
        scope.launch {
            val server = serverKey()
            if (entry(server, playlist.id) == null) {
                save(load() + DownloadedPlaylist(server, playlist))
            }
            sync(server, playlist.id)
        }
    }

    fun remove(playlistId: String) {
        if (files == null) return
        scope.launch {
            val server = serverKey()
            jobs.remove(key(server, playlistId))?.cancelAndJoin()
            val entry = entry(server, playlistId) ?: return@launch
            save(load() - entry)
            deleteUnused(entry.playlist.songs.map { it.id })
            publish()
        }
    }

    /** Downloads [album] (with its songs, as far as known) and keeps it downloaded. */
    fun downloadAlbum(album: Album) {
        if (files == null) return
        scope.launch {
            val server = serverKey()
            if (albumEntry(server, album.id) == null) {
                saveAlbums(loadAlbums() + DownloadedAlbum(server, album.id, album.name, album.songs))
            }
            syncAlbum(server, album.id)
        }
    }

    fun removeAlbum(albumId: String) {
        if (files == null) return
        scope.launch {
            val server = serverKey()
            jobs.remove(albumKey(server, albumId))?.cancelAndJoin()
            val entry = albumEntry(server, albumId) ?: return@launch
            saveAlbums(loadAlbums() - entry)
            deleteUnused(entry.songs.map { it.id })
            publish()
        }
    }

    /** Downloads [songs] on their own and keeps them until [removeSongs]. */
    fun downloadSongs(songs: List<Song>) {
        if (files == null || songs.isEmpty()) return
        scope.launch {
            val server = serverKey()
            val known = loadSongs().filter { it.server == server }.mapTo(mutableSetOf()) { it.song.id }
            val new = songs.distinctBy { it.id }.filterNot { it.id in known }
            if (new.isNotEmpty()) saveSongs(loadSongs() + new.map { DownloadedSong(server, it) })
            syncSongs(server)
        }
    }

    /** Removes the downloads of [songIds] made by [downloadSongs]; files that downloaded playlists need stay. */
    fun removeSongs(songIds: Collection<String>) {
        if (files == null) return
        scope.launch {
            val server = serverKey()
            val removed = loadSongs().filter { it.server == server && it.song.id in songIds }
            if (removed.isEmpty()) return@launch
            // A running download of one of them must not write its file after the deletion
            jobs.remove(songsKey(server))?.cancelAndJoin()
            saveSongs(loadSongs() - removed.toSet())
            deleteUnused(removed.map { it.song.id })
            syncSongs(server)
        }
    }

    /** Resumes unfinished downloads and syncs all downloaded playlists of the current server, e.g. after connecting. */
    fun syncAll() {
        if (files == null) return
        scope.launch {
            val server = serverKey()
            load().filter { it.server == server }.forEach { sync(server, it.playlist.id) }
            loadAlbums().filter { it.server == server }.forEach { syncAlbum(server, it.albumId) }
            syncSongs(server)
        }
    }

    /** Call after [playlistId] got changed, to download its new songs. */
    fun onPlaylistChanged(playlistId: String) {
        if (files == null) return
        scope.launch {
            val server = serverKey()
            if (entry(server, playlistId) == null) return@launch
            // A running sync may have fetched the playlist before the change
            jobs.remove(key(server, playlistId))?.cancelAndJoin()
            sync(server, playlistId)
        }
    }

    /**
     * Call with [playlists] just fetched from the server: syncs those downloaded ones that changed since, e.g. got
     * songs added by another client. Playlists without their songs are compared by their number of songs.
     */
    fun onPlaylistsFetched(playlists: List<Playlist>) {
        if (files == null || playlists.isEmpty()) return
        scope.launch {
            val server = serverKey()
            for (playlist in playlists) {
                val stored = entry(server, playlist.id)?.playlist ?: continue
                val changed = if (playlist.songs.isNotEmpty()) {
                    playlist.songs.map { it.id } != stored.songs.map { it.id }
                } else {
                    playlist.songCount != stored.songs.size
                }
                if (changed) sync(server, playlist.id)
            }
        }
    }

    /** The downloaded [playlistId] of the current server, with its songs. */
    suspend fun playlist(playlistId: String): Playlist? = withContext(dispatcher) {
        entry(serverKey(), playlistId)?.playlist?.takeIf { it.songs.isNotEmpty() }
    }

    suspend fun playlists(): List<Playlist> = withContext(dispatcher) {
        val server = serverKey()
        load().filter { it.server == server }.map { it.playlist }
    }

    /** The songs of the current server whose files are on the device, with what is known about them. */
    suspend fun downloadedSongs(): List<Song> = withContext(dispatcher) {
        val files = files ?: return@withContext emptyList()
        val server = serverKey()
        (loadSongs().filter { it.server == server }.map { it.song } +
            load().filter { it.server == server }.flatMap { it.playlist.songs } +
            loadAlbums().filter { it.server == server }.flatMap { it.songs })
            .distinctBy { it.id }
            .filter { files.uriOf(it.id) != null }
    }

    private suspend fun sync(server: String, playlistId: String) {
        val files = files ?: return
        val key = key(server, playlistId)
        if (jobs[key]?.isActive == true) return
        jobs[key] = scope.launch {
            // Picks up changes made elsewhere; offline the stored song list is downloaded as far as possible
            repository.getPlaylist(playlistId).onSuccess { fresh ->
                val entry = entry(server, playlistId) ?: return@launch
                if (serverKey() != server) return@launch
                save(load().map { if (it == entry) entry.copy(playlist = fresh) else it })
                deleteUnused(entry.playlist.songs.map { it.id } - fresh.songs.mapTo(mutableSetOf()) { it.id })
            }
            publish()
            downloadMissing(files, server, entry(server, playlistId)?.playlist?.songs.orEmpty())
        }.also { job ->
            job.invokeOnCompletion {
                scope.launch {
                    if (jobs[key] === job) jobs.remove(key)
                    publish()
                }
            }
        }
        publish()
    }

    private suspend fun syncAlbum(server: String, albumId: String) {
        val files = files ?: return
        val key = albumKey(server, albumId)
        if (jobs[key]?.isActive == true) return
        jobs[key] = scope.launch {
            // Offline the stored song list is downloaded as far as possible
            repository.getAlbum(albumId).onSuccess { fresh ->
                val entry = albumEntry(server, albumId) ?: return@launch
                if (serverKey() != server) return@launch
                saveAlbums(loadAlbums().map { if (it == entry) entry.copy(name = fresh.name, songs = fresh.songs) else it })
                deleteUnused(entry.songs.map { it.id } - fresh.songs.mapTo(mutableSetOf()) { it.id })
            }
            publish()
            downloadMissing(files, server, albumEntry(server, albumId)?.songs.orEmpty())
        }.also { job ->
            job.invokeOnCompletion {
                scope.launch {
                    if (jobs[key] === job) jobs.remove(key)
                    publish()
                }
            }
        }
        publish()
    }

    /** Downloads the missing files of the songs downloaded on their own. */
    private fun syncSongs(server: String) {
        val files = files ?: return
        val key = songsKey(server)
        songsChanged = true
        // A running job picks up the change once it is through its current list
        if (jobs[key]?.isActive == true) return
        jobs[key] = scope.launch {
            while (songsChanged && serverKey() == server) {
                songsChanged = false
                downloadMissing(files, server, loadSongs().filter { it.server == server }.map { it.song })
            }
        }.also { job ->
            job.invokeOnCompletion {
                scope.launch {
                    if (jobs[key] === job) jobs.remove(key)
                    publish()
                }
            }
        }
        scope.launch { publish() }
    }

    private suspend fun downloadMissing(files: SongFileStore, server: String, songs: List<Song>) {
        for (song in songs.distinctBy { it.id }) {
            if (files.uriOf(song.id) != null) continue
            if (serverKey() != server) break
            transferring += song.id
            publish()
            try {
                files.download(song.id, repository.getDownloadUrl(song.id))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Stays missing; the next sync tries again
            } finally {
                transferring -= song.id
            }
            publish()
        }
    }

    /** Deletes the files of [songIds] that no downloaded playlist or album nor a single download (of any server) needs anymore. */
    private suspend fun deleteUnused(songIds: Collection<String>) {
        val files = files ?: return
        val used = load().flatMapTo(mutableSetOf()) { entry -> entry.playlist.songs.map { it.id } }
        loadAlbums().flatMapTo(used) { entry -> entry.songs.map { it.id } }
        loadSongs().mapTo(used) { it.song.id }
        songIds.filterNot { it in used }.forEach { files.delete(it) }
    }

    private suspend fun publish() {
        val files = files ?: return
        val server = serverKey()
        val entries = load().filter { it.server == server }
        val singleSongs = loadSongs().filter { it.server == server }
        val albums = loadAlbums().filter { it.server == server }
        _singleSongIds.value = singleSongs.mapTo(mutableSetOf()) { it.song.id }
        _downloadedSongIds.value = (entries.flatMap { it.playlist.songs } + albums.flatMap { it.songs } + singleSongs.map { it.song })
            .mapNotNullTo(mutableSetOf()) { song -> song.id.takeIf { files.uriOf(it) != null } }
        val pending = entries.filter { jobs[key(server, it.playlist.id)]?.isActive == true }.flatMap { it.playlist.songs } +
            albums.filter { jobs[albumKey(server, it.albumId)]?.isActive == true }.flatMap { it.songs } +
            (if (jobs[songsKey(server)]?.isActive == true) singleSongs.map { it.song } else emptyList())
        _downloadingSongIds.value = transferring.toSet()
        _queuedSongIds.value = pending.mapNotNullTo(mutableSetOf()) { song ->
            song.id.takeIf { files.uriOf(it) == null && it !in transferring }
        }
        _status.value = entries
            .associate { entry ->
                val songs = entry.playlist.songs.distinctBy { it.id }
                entry.playlist.id to PlaylistDownloadStatus(
                    downloadedSongs = songs.count { files.uriOf(it.id) != null },
                    // Until the songs got fetched only the count is known
                    totalSongs = if (songs.isEmpty()) entry.playlist.songCount else songs.size,
                    isRunning = jobs[key(server, entry.playlist.id)]?.isActive == true,
                )
            }
        _albumStatus.value = albums.associate { entry ->
            val songs = entry.songs.distinctBy { it.id }
            entry.albumId to PlaylistDownloadStatus(
                downloadedSongs = songs.count { files.uriOf(it.id) != null },
                totalSongs = songs.size,
                isRunning = jobs[albumKey(server, entry.albumId)]?.isActive == true,
            )
        }
    }

    private suspend fun entry(server: String, playlistId: String): DownloadedPlaylist? =
        load().firstOrNull { it.server == server && it.playlist.id == playlistId }

    private suspend fun load(): List<DownloadedPlaylist> = entries ?: (
        readAppFile(FILE_NAME)?.let {
            runCatching { offlineJson.decodeFromString<List<DownloadedPlaylist>>(it) }.getOrNull()
        } ?: emptyList()
    ).also { entries = it }

    private suspend fun save(new: List<DownloadedPlaylist>) {
        entries = new
        writeAppFile(FILE_NAME, offlineJson.encodeToString(new))
    }

    private suspend fun loadSongs(): List<DownloadedSong> = songEntries ?: (
        readAppFile(SONGS_FILE_NAME)?.let {
            runCatching { offlineJson.decodeFromString<List<DownloadedSong>>(it) }.getOrNull()
        } ?: emptyList()
    ).also { songEntries = it }

    private suspend fun saveSongs(new: List<DownloadedSong>) {
        songEntries = new
        writeAppFile(SONGS_FILE_NAME, offlineJson.encodeToString(new))
    }

    private suspend fun albumEntry(server: String, albumId: String): DownloadedAlbum? =
        loadAlbums().firstOrNull { it.server == server && it.albumId == albumId }

    private suspend fun loadAlbums(): List<DownloadedAlbum> = albumEntries ?: (
        readAppFile(ALBUMS_FILE_NAME)?.let {
            runCatching { offlineJson.decodeFromString<List<DownloadedAlbum>>(it) }.getOrNull()
        } ?: emptyList()
    ).also { albumEntries = it }

    private suspend fun saveAlbums(new: List<DownloadedAlbum>) {
        albumEntries = new
        writeAppFile(ALBUMS_FILE_NAME, offlineJson.encodeToString(new))
    }

    private fun key(server: String, playlistId: String) = "$server\n$playlistId"

    /** Key of the job syncing [albumId]; the prefix keeps it apart from playlists with the same id. */
    private fun albumKey(server: String, albumId: String) = key(server, "album:$albumId")

    /** Key of the job downloading the single songs; playlist ids aren't empty, so it can't collide with [key]. */
    private fun songsKey(server: String) = key(server, "")

    private companion object {
        const val FILE_NAME = "downloaded-playlists.json"
        const val SONGS_FILE_NAME = "downloaded-songs.json"
        const val ALBUMS_FILE_NAME = "downloaded-albums.json"
        const val SYNC_INTERVAL = 15 * 60 * 1000L
    }
}
