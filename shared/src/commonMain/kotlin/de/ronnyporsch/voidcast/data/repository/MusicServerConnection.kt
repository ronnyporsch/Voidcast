package de.ronnyporsch.voidcast.data.repository

import de.ronnyporsch.voidcast.data.api.SubsonicApiService
import de.ronnyporsch.voidcast.data.jellyfin.JellyfinApiException
import de.ronnyporsch.voidcast.data.jellyfin.JellyfinApiService
import de.ronnyporsch.voidcast.data.jellyfin.JellyfinMusicRepository
import de.ronnyporsch.voidcast.data.jellyfin.PluginInfoDto
import de.ronnyporsch.voidcast.data.offline.OfflineMusicRepository
import de.ronnyporsch.voidcast.data.offline.PlaylistCache
import de.ronnyporsch.voidcast.data.offline.PlaylistDownloads
import de.ronnyporsch.voidcast.data.youtube.resizeYouTubeThumbnail
import de.ronnyporsch.voidcast.domain.model.*
import de.ronnyporsch.voidcast.getPlatform
import de.ronnyporsch.voidcast.platform.createSongFileStore
import de.ronnyporsch.voidcast.platform.untrustedCertificateHost
import de.ronnyporsch.voidcast.util.generateSalt
import de.ronnyporsch.voidcast.util.md5
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The server rejected the username or password, as opposed to e.g. not being reachable. */
class InvalidCredentialsException(message: String) : Exception(message.ifBlank { "Wrong username or password" })

/**
 * The music server the app is connected to (Navidrome/Subsonic or Jellyfin). [repository] always talks to the
 * current one, so view models can keep it across reconnects.
 */
class MusicServerConnection(private val settingsHolder: SettingsHolder) {
    private val subsonicApi = SubsonicApiService()
    val jellyfinApi = JellyfinApiService()
    private val subsonic = MusicRepositoryImpl(subsonicApi)
    private val jellyfin = JellyfinMusicRepository(jellyfinApi)
    private var active: MusicRepository = subsonic

    /** The Jellyfin library, also while connected to a Subsonic server (then it can't be used). */
    val jellyfinLibrary: JellyfinMusicRepository get() = jellyfin

    private val _serverType = MutableStateFlow<ServerType?>(null)
    /** The type of the connected server, or null while not connected. */
    val serverType: StateFlow<ServerType?> = _serverType.asStateFlow()

    private val _plugin = MutableStateFlow<PluginInfoDto?>(null)
    /** The Voidcast plugin of the connected Jellyfin server, or null if there is none. */
    val plugin: StateFlow<PluginInfoDto?> = _plugin.asStateFlow()

    private val _pluginMissing = MutableStateFlow(false)
    /** Whether the connected server is a Jellyfin server known not to have the Voidcast plugin installed. */
    val pluginMissing: StateFlow<Boolean> = _pluginMissing.asStateFlow()

    private val _connections = MutableStateFlow(0)
    /** Counts successful [connect]s. */
    val connections: StateFlow<Int> = _connections.asStateFlow()

    private val online: MusicRepository = ActiveRepository()
    private val songFiles = createSongFileStore()

    /** Playlists downloaded for offline playback. */
    val downloads = PlaylistDownloads(online, songFiles, ::serverKey)

    val repository = OfflineMusicRepository(online, PlaylistCache(::serverKey), downloads, songFiles)

    /** The user's favorite songs, albums and artists on the connected server. */
    val favorites = Favorites(online)

    /** Identifies the saved server for the offline data. */
    private fun serverKey(): String = settingsHolder.settings.value.serverKey

    /**
     * Connects to the server (and remembers the connection if [save]). Throws with a user-facing message on failure,
     * an [InvalidCredentialsException] if the server rejected the login.
     */
    suspend fun connect(
        type: ServerType,
        serverUrl: String,
        username: String,
        password: String,
        allowUntrustedCertificate: Boolean,
        save: Boolean = true,
    ) {
        val url = serverUrl.trim().trimEnd('/')
        untrustedCertificateHost = if (allowUntrustedCertificate) parseUrl(url)?.host else null
        var deviceId = settingsHolder.settings.value.jellyfinDeviceId
        when (type) {
            ServerType.Subsonic -> {
                val salt = generateSalt()
                subsonicApi.configure(ServerConfig(url, username, md5(password + salt), salt))
                val error = subsonicApi.checkCredentials()
                // 40: wrong username or password, 41: token authentication not supported for this user
                if (error != null && error.code in 40..41) throw InvalidCredentialsException(error.message)
                if (error != null) throw IllegalStateException(error.message.ifBlank { "Connection failed" })
                active = subsonic
                _plugin.value = null
                _pluginMissing.value = false
            }
            ServerType.Jellyfin -> {
                if (deviceId.isBlank()) deviceId = "voidcast-" + generateSalt(24)
                try {
                    jellyfinApi.authenticate(url, username, password, deviceId)
                } catch (e: JellyfinApiException) {
                    if (e.status == HttpStatusCode.Unauthorized) throw InvalidCredentialsException(e.message ?: "")
                    throw e
                }
                active = jellyfin
                refreshPlugin()
            }
        }
        _serverType.value = type
        if (save) {
            settingsHolder.update {
                copy(
                    serverType = type,
                    serverUrl = url,
                    username = username,
                    password = password,
                    allowUntrustedCertificate = allowUntrustedCertificate,
                    jellyfinDeviceId = deviceId,
                )
            }
        } else if (deviceId != settingsHolder.settings.value.jellyfinDeviceId) {
            settingsHolder.update { copy(jellyfinDeviceId = deviceId) }
        }
        _connections.value++
        favorites.reload()
        downloads.syncAll()
    }

    /** Ends the session with the server and forgets the saved password, so the next start asks for the login. */
    suspend fun logout() {
        if (jellyfinApi.isConfigured) {
            // Still log out locally if the server can't be reached
            try {
                jellyfinApi.logout()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        subsonicApi.reset()
        _serverType.value = null
        _plugin.value = null
        _pluginMissing.value = false
        settingsHolder.update { copy(password = "") }
    }

    /** Re-reads what the Voidcast Jellyfin plugin offers, e.g. after an admin changed its settings. */
    suspend fun refreshPlugin() {
        if (active !== jellyfin) {
            _plugin.value = null
            _pluginMissing.value = false
            return
        }
        try {
            val plugin = jellyfinApi.getPluginInfo()
            _plugin.value = plugin
            _pluginMissing.value = plugin == null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // E.g. not reachable; that doesn't tell whether the plugin is installed
            _plugin.value = null
        }
    }

    private inner class ActiveRepository : MusicRepository {
        override val isConfigured: Boolean get() = active.isConfigured
        override suspend fun ping() = active.ping()
        override suspend fun getArtists() = active.getArtists()
        override suspend fun getArtistAlbums(artistId: String) = active.getArtistAlbums(artistId)
        override suspend fun getAppearsOnAlbums(artistName: String, ownAlbumIds: List<String>) =
            active.getAppearsOnAlbums(artistName, ownAlbumIds)
        override suspend fun getArtistInfo(artistId: String) = active.getArtistInfo(artistId)
        override suspend fun getAlbum(albumId: String) = active.getAlbum(albumId)
        override suspend fun getPlaylists() = active.getPlaylists()
        override suspend fun getPlaylist(playlistId: String) = active.getPlaylist(playlistId)
        override suspend fun createPlaylist(name: String, songIds: List<String>) = active.createPlaylist(name, songIds)
        override suspend fun addSongsToPlaylist(playlistId: String, songIds: List<String>) =
            active.addSongsToPlaylist(playlistId, songIds)
        override suspend fun removeSongsFromPlaylist(playlistId: String, songIndices: List<Int>) =
            active.removeSongsFromPlaylist(playlistId, songIndices)
        override suspend fun findArtistByName(name: String) = active.findArtistByName(name)
        override suspend fun findSongsByArtistName(name: String) = active.findSongsByArtistName(name)
        override suspend fun getSongsForArtist(artistName: String, albumIds: List<String>) =
            active.getSongsForArtist(artistName, albumIds)
        override suspend fun search(query: String) = active.search(query)
        override suspend fun startScan() = active.startScan()

        // YouTube Music tracks aren't library items, the server has no lyrics for them
        override suspend fun getLyrics(song: Song) =
            if (song.isYouTube) Result.success(null) else active.getLyrics(song)

        override suspend fun getAudioFormat(song: Song): Result<AudioStreamInfo?> {
            val videoId = song.youTubeVideoId ?: return active.getAudioFormat(song)
            // The downloaded file if there is one (cheap), else the stream yt-dlp picks (served the same way)
            return runCatching {
                jellyfinApi.getDownloadedYouTube(listOf(videoId)).firstOrNull()
                    ?.takeIf { it.codec != null || it.bitRate != null }
                    ?.let { AudioStreamInfo(it.codec, it.bitRate) }
                    ?: jellyfinApi.getYouTubeStreamInfo(videoId)?.let { AudioStreamInfo(it.codec, it.bitRate) }
            }
        }

        override suspend fun getFavorites() = active.getFavorites()

        override suspend fun setFavorite(kind: FavoriteKind, id: String, favorite: Boolean): Result<Unit> =
            if (id.isYouTubeId) {
                Result.failure(IllegalArgumentException("Only library items can be favorites"))
            } else {
                active.setFavorite(kind, id, favorite)
            }

        override fun getStreamUrl(songId: String): String {
            val videoId = songId.takeIf { it.startsWith(YOUTUBE_SONG_ID_PREFIX) }?.removePrefix(YOUTUBE_SONG_ID_PREFIX)
                ?: return active.getStreamUrl(songId)
            // YouTube Music tracks are only streamed by the Voidcast Jellyfin plugin; the original stream is Opus or AAC
            return jellyfinApi.getYouTubeAudioUrl(videoId, mp3 = "opus" !in getPlatform().audioContainers)
        }

        override fun getVideoUrl(song: Song, maxHeight: Int): String? {
            val videoId = song.youTubeVideoId?.takeIf { song.isVideo && plugin.value?.videoAvailable == true } ?: return null
            return jellyfinApi.getYouTubeVideoUrl(videoId, maxHeight)
        }

        override suspend fun getVideoFormat(song: Song, maxHeight: Int): Result<VideoFormat?> {
            val videoId = song.youTubeVideoId?.takeIf { song.isVideo && plugin.value?.videoAvailable == true }
                ?: return Result.success(null)
            return runCatching {
                jellyfinApi.getYouTubeVideoInfo(videoId, maxHeight)?.let {
                    VideoFormat(
                        VideoStreamInfo(it.videoCodec, it.videoBitRate, it.width, it.height, it.frameRate),
                        AudioStreamInfo(it.audioCodec, it.audioBitRate),
                    )
                }
            }
        }

        override suspend fun getVideoHeights(song: Song): Result<List<Int>> {
            val videoId = song.youTubeVideoId?.takeIf { song.isVideo && plugin.value?.videoAvailable == true }
                ?: return Result.success(emptyList())
            return runCatching { jellyfinApi.getYouTubeVideoHeights(videoId) }
        }

        override fun getDownloadUrl(songId: String): String {
            val videoId = songId.takeIf { it.startsWith(YOUTUBE_SONG_ID_PREFIX) }?.removePrefix(YOUTUBE_SONG_ID_PREFIX)
                ?: return active.getDownloadUrl(songId)
            return jellyfinApi.getYouTubeAudioUrl(videoId, mp3 = false)
        }

        // YouTube Music songs carry their thumbnail URL as cover art id
        override fun getCoverArtUrl(coverArtId: String, size: Int): String =
            if (coverArtId.startsWith("https://")) {
                resizeYouTubeThumbnail(coverArtId, size)
            } else {
                active.getCoverArtUrl(coverArtId, size)
            }
    }
}
