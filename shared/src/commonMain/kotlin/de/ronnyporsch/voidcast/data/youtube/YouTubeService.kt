package de.ronnyporsch.voidcast.data.youtube

import de.ronnyporsch.voidcast.data.jellyfin.DownloadedTrackDto
import de.ronnyporsch.voidcast.data.jellyfin.DownloadJobDto
import de.ronnyporsch.voidcast.data.jellyfin.PlaylistAdditionDto
import de.ronnyporsch.voidcast.data.jellyfin.PluginInfoDto
import de.ronnyporsch.voidcast.data.jellyfin.YouTubeAlbumDto
import de.ronnyporsch.voidcast.data.jellyfin.YouTubeAlbumSummaryDto
import de.ronnyporsch.voidcast.data.jellyfin.YouTubeArtistDto
import de.ronnyporsch.voidcast.data.jellyfin.YouTubeSongDto
import de.ronnyporsch.voidcast.data.repository.MusicServerConnection
import de.ronnyporsch.voidcast.domain.model.Album
import de.ronnyporsch.voidcast.domain.model.Artist
import de.ronnyporsch.voidcast.domain.model.AudioStreamInfo
import de.ronnyporsch.voidcast.domain.model.ArtistInfo
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.domain.model.YOUTUBE_SONG_ID_PREFIX
import de.ronnyporsch.voidcast.domain.model.youTubeId
import de.ronnyporsch.voidcast.domain.model.youTubeVideoId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class YouTubeCapabilities(
    /** Whether YouTube Music can be searched and played. */
    val available: Boolean = false,
    /** Whether tracks can be downloaded into the server's library. */
    val canDownload: Boolean = false,
    /** Whether search finds videos too, which can be played with picture. */
    val videos: Boolean = false,
    /** Whether songs can be added to playlists (the server downloads them into its library first). */
    val addToPlaylists: Boolean = false,
    /** Whether songs and videos can be looked up by id, see [YouTubeService.getSong]. */
    val songLookup: Boolean = false,
)

sealed class DownloadStatus {
    data object InProgress : DownloadStatus()
    data class Done(val message: String) : DownloadStatus()
    data class Failed(val message: String) : DownloadStatus()
    /** Downloaded before, e.g. in an earlier session or by another client. */
    data object InLibrary : DownloadStatus()
}

/**
 * A YouTube Music [song] to download into the library, tagged with its (possibly edited) title, artists, album and
 * genre. Only the part from [startTime] to [endTime] (in seconds) gets downloaded, e.g. without a video's intro; null
 * keeps the start or end.
 */
data class YouTubeDownloadRequest(val song: Song, val startTime: Int? = null, val endTime: Int? = null)

/** A YouTube Music artist page; ids carry [YOUTUBE_SONG_ID_PREFIX]. */
data class YouTubeArtist(
    val artist: Artist,
    val info: ArtistInfo,
    /** Albums, singles and EPs. */
    val albums: List<Album>,
    /** The most popular songs. */
    val songs: List<Song>,
)

/**
 * YouTube Music support, provided by the Voidcast plugin of the connected Jellyfin server: it searches, streams
 * and downloads (into its library) on behalf of all platforms. Without the plugin, YouTube Music is unavailable.
 */
class YouTubeService(private val connection: MusicServerConnection) {

    val capabilities: Flow<YouTubeCapabilities> = connection.plugin.map(::capabilitiesFor)

    val currentCapabilities: YouTubeCapabilities get() = capabilitiesFor(connection.plugin.value)

    /** Whether the connected Jellyfin server lacks the Voidcast plugin, so YouTube Music is unavailable. */
    val pluginMissing: StateFlow<Boolean> = connection.pluginMissing

    // One thread, so the download bookkeeping below needs no locking
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    /** Song ids of running server download jobs, by job id. */
    private val runningJobs = mutableMapOf<String, String>()
    private var pollJob: Job? = null

    private val _downloads = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
    /** Downloads requested in this session, by song id; they keep running when the requesting screen is left. */
    val downloads: StateFlow<Map<String, DownloadStatus>> = _downloads.asStateFlow()

    private val _requestedSongs = MutableStateFlow<List<Song>>(emptyList())
    /** The songs downloaded (or being downloaded) in this session, latest request first; [downloads] has their status. */
    val requestedSongs: StateFlow<List<Song>> = _requestedSongs.asStateFlow()

    private val _libraryFormats = MutableStateFlow<Map<String, AudioStreamInfo>>(emptyMap())
    /** Codec and bitrate of the downloaded files of songs in the library ([downloads] says which), by song id. */
    val libraryFormats: StateFlow<Map<String, AudioStreamInfo>> = _libraryFormats.asStateFlow()

    private val _streamFormats = MutableStateFlow<Map<String, AudioStreamInfo>>(emptyMap())
    /** Codec and bitrate of the streams of songs the user asked for, see [lookUpStreamFormat], by song id. */
    val streamFormats: StateFlow<Map<String, AudioStreamInfo>> = _streamFormats.asStateFlow()

    /** [libraryFormats], else [streamFormats]. */
    val knownFormats: Flow<Map<String, AudioStreamInfo>> = combine(_streamFormats, _libraryFormats) { stream, library -> stream + library }

    private fun capabilitiesFor(plugin: PluginInfoDto?): YouTubeCapabilities {
        val server = plugin?.takeIf { it.ytDlpAvailable }
        return YouTubeCapabilities(
            available = server != null,
            canDownload = server?.canDownload == true,
            videos = server?.videoAvailable == true,
            addToPlaylists = server?.playlistAdditionAvailable == true,
            songLookup = server?.songLookupAvailable == true,
        )
    }

    suspend fun searchSongs(query: String): List<Song> =
        connection.jellyfinApi.searchYouTube(query, includeVideos = currentCapabilities.videos).map { it.toSong() }
            .also(::checkDownloaded)

    suspend fun searchArtists(query: String): List<Artist> = connection.jellyfinApi.searchYouTubeArtists(query).map {
        Artist(id = YOUTUBE_SONG_ID_PREFIX + it.id, name = it.name, coverArtId = it.thumbnailUrl)
    }

    /** The song or video with [videoId], e.g. from a [YouTubeLink]. */
    suspend fun getSong(videoId: String): Song =
        connection.jellyfinApi.getYouTubeSong(videoId).toSong().also { checkDownloaded(listOf(it)) }

    /** [artistId] is a YouTube Music artist id, i.e. with [YOUTUBE_SONG_ID_PREFIX]. */
    suspend fun getArtist(artistId: String): YouTubeArtist {
        val channelId = artistId.youTubeId ?: throw IllegalArgumentException("Not a YouTube Music artist: $artistId")
        val page = connection.jellyfinApi.getYouTubeArtist(channelId)
        val artist = Artist(id = artistId, name = page.name, albumCount = page.albums.size, coverArtId = page.thumbnailUrl)
        return YouTubeArtist(
            artist = artist,
            // The header image is a wide banner; a square crop of it fits the artist header
            info = ArtistInfo(biography = page.description, largeImageUrl = page.thumbnailUrl?.let { resizeYouTubeThumbnail(it, 544) }),
            albums = page.albums.map { it.toAlbum(artist) },
            songs = page.songs.map { it.toSong() }.also(::checkDownloaded),
        )
    }

    /** [albumId] is a YouTube Music album id, i.e. with [YOUTUBE_SONG_ID_PREFIX]. */
    suspend fun getAlbum(albumId: String): Album {
        val browseId = albumId.youTubeId ?: throw IllegalArgumentException("Not a YouTube Music album: $albumId")
        return connection.jellyfinApi.getYouTubeAlbum(browseId).toAlbum().also { checkDownloaded(it.songs) }
    }

    /** Marks those of [songs] that are already in the server's library, so they show as downloaded. */
    private fun checkDownloaded(songs: List<Song>) {
        if (!currentCapabilities.canDownload) return
        val videoIds = songs.mapNotNull { song -> song.youTubeVideoId?.takeIf { _downloads.value[song.id] == null } }
        if (videoIds.isEmpty()) return
        scope.launch {
            for (track in downloadedTracks(videoIds)) {
                val songId = YOUTUBE_SONG_ID_PREFIX + track.videoId
                if (_downloads.value[songId] == null) setStatus(songId, DownloadStatus.InLibrary)
            }
        }
    }

    /** Looks up those of [videoIds] that are in the library and records their [libraryFormats]. */
    private suspend fun downloadedTracks(videoIds: List<String>): List<DownloadedTrackDto> = try {
        connection.jellyfinApi.getDownloadedYouTube(videoIds).also { tracks ->
            val formats = tracks.filter { it.codec != null || it.bitRate != null }
                .associate { YOUTUBE_SONG_ID_PREFIX + it.videoId to AudioStreamInfo(it.codec, it.bitRate) }
            if (formats.isNotEmpty()) _libraryFormats.value += formats
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Only informational
        emptyList()
    }

    /** Ids of those of [albums] (YouTube Music ones) whose songs are all in the server's library. */
    suspend fun downloadedAlbumIds(albums: List<Album>): Set<String> {
        val browseIds = albums.mapNotNull { it.id.youTubeId }
        if (browseIds.isEmpty()) return emptySet()
        return connection.jellyfinApi.getDownloadedYouTubeAlbums(browseIds).mapTo(mutableSetOf()) { YOUTUBE_SONG_ID_PREFIX + it }
    }

    /**
     * Codec and bitrate of the stream a download of [song] would get, or null if unknown; also recorded in
     * [streamFormats]. Takes seconds (the server runs yt-dlp), so only on request.
     */
    suspend fun lookUpStreamFormat(song: Song): AudioStreamInfo? {
        val videoId = song.youTubeVideoId ?: return null
        return connection.jellyfinApi.getYouTubeStreamInfo(videoId)
            ?.let { AudioStreamInfo(it.codec, it.bitRate) }
            ?.also { _streamFormats.value += song.id to it }
    }

    /** Downloads the [requests]' songs into the server's library; [downloads] reports their progress. */
    fun download(requests: List<YouTubeDownloadRequest>) {
        if (!currentCapabilities.canDownload) return
        scope.launch {
            for (request in requests) {
                val song = request.song
                val status = _downloads.value[song.id]
                if (status == DownloadStatus.InProgress || status is DownloadStatus.Done || status == DownloadStatus.InLibrary) continue
                addRequested(song)
                setStatus(song.id, DownloadStatus.InProgress)
                try {
                    val job = connection.jellyfinApi.requestDownload(request.toDto())
                    update(job.id, song.id, job.state, job.message, job.path)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setStatus(song.id, DownloadStatus.Failed(e.message ?: "Download failed"))
                }
            }
            startPolling()
        }
    }

    fun download(request: YouTubeDownloadRequest) = download(listOf(request))

    /**
     * Adds the [requests]' songs to the playlist with [playlistId], in their order. The server downloads those that aren't in its
     * library first ([downloads] reports their progress) and adds them in the background, also if this client goes
     * away. Calls [onStarted] once the server accepted, [onProgress] whenever more songs got added, and returns the
     * outcome once all songs are added or failed.
     */
    suspend fun addToPlaylist(
        playlistId: String,
        requests: List<YouTubeDownloadRequest>,
        onStarted: suspend (PlaylistAdditionDto) -> Unit = {},
        onProgress: suspend (PlaylistAdditionDto) -> Unit = {},
    ): PlaylistAdditionDto {
        var addition = connection.jellyfinApi.addYouTubeToPlaylist(playlistId, requests.map { it.toDto() })
        track(addition.jobs, requests.map { it.song })
        onStarted(addition)
        var failures = 0
        while (!addition.finished) {
            delay(2000)
            val previous = addition
            addition = try {
                connection.jellyfinApi.getPlaylistAddition(addition.id).also { failures = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (++failures >= 5) throw e
                continue
            }
            if (addition.added != previous.added) onProgress(addition)
        }
        return addition
    }

    /** Shows the progress of download [jobs] the server started on its own for some of [songs]. */
    private fun track(jobs: List<DownloadJobDto>, songs: List<Song>) {
        if (jobs.isEmpty()) return
        scope.launch {
            for (job in jobs) {
                songs.firstOrNull { it.youTubeVideoId == job.videoId }?.let(::addRequested)
                update(job.id, YOUTUBE_SONG_ID_PREFIX + job.videoId, job.state, job.message, job.path)
            }
            startPolling()
        }
    }

    private fun startPolling() {
        if (runningJobs.isNotEmpty() && pollJob?.isActive != true) pollJob = scope.launch { poll() }
    }

    /** Polls all running jobs at once, so that downloading an album doesn't poll the server for every track. */
    private suspend fun poll() {
        var failures = 0
        while (runningJobs.isNotEmpty()) {
            delay(1500)
            try {
                val jobs = connection.jellyfinApi.getDownloads().associateBy { it.id }
                failures = 0
                for ((jobId, songId) in runningJobs.toList()) {
                    // Jobs vanish if the server restarted or pruned them
                    val job = jobs[jobId]
                    if (job == null) update(jobId, songId, "Failed", "The server lost the download", null)
                    else update(jobId, songId, job.state, job.message, job.path)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (++failures < 5) continue
                for ((jobId, songId) in runningJobs.toList()) update(jobId, songId, "Failed", e.message, null)
            }
        }
    }

    private fun update(jobId: String, songId: String, state: String, message: String?, path: String?) {
        when (state) {
            // Done once the file is in the library folder; the server scans it in the background
            "Queued", "Downloading" -> {
                runningJobs[jobId] = songId
                if (_downloads.value[songId] != DownloadStatus.InProgress) setStatus(songId, DownloadStatus.InProgress)
            }
            "Failed" -> {
                runningJobs.remove(jobId)
                setStatus(songId, DownloadStatus.Failed(message ?: "Download failed"))
            }
            else -> {
                runningJobs.remove(jobId)
                setStatus(songId, DownloadStatus.Done(message ?: "Saved to $path on the server"))
                songId.youTubeId?.let { videoId -> scope.launch { downloadedTracks(listOf(videoId)) } }
            }
        }
    }

    private fun addRequested(song: Song) {
        _requestedSongs.value = listOf(song) + _requestedSongs.value.filterNot { it.id == song.id }
    }

    private fun setStatus(songId: String, status: DownloadStatus) {
        _downloads.value += songId to status
    }
}

/** YouTube thumbnail URLs encode their size (e.g. `=w60-h60-l90-rj`); request a specific one instead. */
fun resizeYouTubeThumbnail(url: String, size: Int): String =
    url.replace(Regex("""=w\d+-h\d+"""), "=w$size-h$size")

private fun prefixed(id: String) = if (id.isEmpty()) "" else YOUTUBE_SONG_ID_PREFIX + id

private fun YouTubeArtistDto.toArtist() = Artist(id = prefixed(id), name = name)

private fun YouTubeSongDto.toSong() = Song(
    id = YOUTUBE_SONG_ID_PREFIX + videoId,
    title = title,
    artist = artist,
    artists = artists.map { it.toArtist() },
    album = album,
    albumId = prefixed(albumId),
    coverArtId = thumbnailUrl,
    duration = duration,
    isVideo = isVideo,
)

private fun YouTubeAlbumSummaryDto.toAlbum(artist: Artist) = Album(
    id = YOUTUBE_SONG_ID_PREFIX + id,
    name = title,
    artist = artist.name,
    artistId = artist.id,
    artists = listOf(artist),
    coverArtId = thumbnailUrl,
    year = year,
    releaseType = releaseType,
)

private fun YouTubeAlbumDto.toAlbum(): Album {
    val songs = songs.mapIndexed { index, song -> song.toSong().copy(track = index + 1, year = year) }
    val artists = artists.map { it.toArtist() }
    return Album(
        id = YOUTUBE_SONG_ID_PREFIX + id,
        name = title,
        artist = artist,
        artistId = artists.firstOrNull()?.id.orEmpty(),
        artists = artists,
        coverArtId = thumbnailUrl,
        songCount = songs.size,
        year = year,
        duration = songs.sumOf { it.duration },
        songs = songs,
        releaseType = releaseType,
    )
}

private fun YouTubeDownloadRequest.toDto() = YouTubeSongDto(
    videoId = song.youTubeVideoId ?: throw IllegalArgumentException("Not a YouTube Music song: ${song.id}"),
    title = song.title,
    artist = song.artist,
    artists = song.artists.map { YouTubeArtistDto(id = it.id.youTubeId ?: it.id, name = it.name) },
    album = song.album,
    albumId = song.albumId.youTubeId ?: song.albumId,
    thumbnailUrl = song.coverArtId,
    duration = song.duration,
    isVideo = song.isVideo,
    genre = song.genre?.takeIf { it.isNotBlank() },
    startTime = startTime,
    endTime = endTime,
)
