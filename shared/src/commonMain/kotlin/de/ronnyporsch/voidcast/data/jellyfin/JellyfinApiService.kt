package de.ronnyporsch.voidcast.data.jellyfin

import de.ronnyporsch.voidcast.AppVersion
import de.ronnyporsch.voidcast.getPlatform
import de.ronnyporsch.voidcast.platform.createHttpClient
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class JellyfinSession(
    val serverUrl: String,
    val userId: String,
    val accessToken: String,
    val deviceId: String,
)

class JellyfinApiException(val status: HttpStatusCode, message: String) : Exception(message)

/** Client for the Jellyfin 12 REST API (and the endpoints of the Voidcast Jellyfin plugin). */
class JellyfinApiService {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val client = createHttpClient {
        install(ContentNegotiation) {
            json(json)
        }
        install(WebSockets)
        expectSuccess = false
    }

    var session: JellyfinSession? = null
        private set

    val isConfigured: Boolean get() = session != null

    private fun requireSession() = session ?: throw IllegalStateException("Server not configured")

    private fun baseUrl() = requireSession().serverUrl

    val userId: String get() = requireSession().userId

    private fun authorizationHeader(deviceId: String, token: String?): String {
        // Quotes and commas would break the header format
        val device = getPlatform().deviceName.replace(Regex("""[",]"""), "")
        return buildString {
            append("""MediaBrowser Client="Voidcast", Device="$device", DeviceId="$deviceId", Version="$CLIENT_VERSION"""")
            if (token != null) append(""", Token="$token"""")
        }
    }

    private fun HttpRequestBuilder.authorize() {
        val s = requireSession()
        header(HttpHeaders.Authorization, authorizationHeader(s.deviceId, s.accessToken))
    }

    suspend fun authenticate(serverUrl: String, username: String, password: String, deviceId: String): JellyfinSession {
        val url = serverUrl.trimEnd('/')
        val response = client.post("$url/Users/AuthenticateByName") {
            header(HttpHeaders.Authorization, authorizationHeader(deviceId, null))
            contentType(ContentType.Application.Json)
            setBody(AuthenticateByNameRequest(username, password))
        }
        if (response.status == HttpStatusCode.Unauthorized) throw JellyfinApiException(response.status, "Wrong username or password")
        response.ensureSuccess()
        val result: AuthenticationResultDto = response.body()
        return JellyfinSession(url, result.user.id, result.accessToken, deviceId).also { session = it }
    }

    /** Ends the session on the server, which revokes its access token; forgets it locally even if that fails. */
    suspend fun logout() {
        try {
            client.post("${baseUrl()}/Sessions/Logout") { authorize() }.ensureSuccess()
        } finally {
            session = null
        }
    }

    suspend fun ping(): Boolean = try {
        client.get("${baseUrl()}/System/Info") { authorize() }.status.isSuccess()
    } catch (e: Exception) {
        false
    }

    suspend fun getItems(params: Map<String, Any?>): ItemsResultDto = get("Items", params + ("userId" to userId))

    suspend fun getItem(itemId: String): BaseItemDto = get("Items/$itemId", mapOf("userId" to userId))

    suspend fun getAlbumArtists(params: Map<String, Any?> = emptyMap()): ItemsResultDto =
        get("Artists/AlbumArtists", params + ("userId" to userId))

    suspend fun getArtists(params: Map<String, Any?>): ItemsResultDto = get("Artists", params + ("userId" to userId))

    suspend fun getMusicGenres(params: Map<String, Any?>): ItemsResultDto = get("MusicGenres", params + ("userId" to userId))

    suspend fun getPlaylistItems(playlistId: String): ItemsResultDto =
        get("Playlists/$playlistId/Items", mapOf("userId" to userId))

    suspend fun createPlaylist(name: String, songIds: List<String>): PlaylistCreationResultDto {
        val response = client.post("${baseUrl()}/Playlists") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(CreatePlaylistDto(name = name, ids = songIds, userId = userId))
        }
        response.ensureSuccess()
        return response.body()
    }

    suspend fun addToPlaylist(playlistId: String, songIds: List<String>) {
        client.post("${baseUrl()}/Playlists/$playlistId/Items") {
            authorize()
            parameter("ids", songIds.joinToString(","))
            parameter("userId", userId)
        }.ensureSuccess()
    }

    suspend fun removeFromPlaylist(playlistId: String, entryIds: List<String>) {
        client.delete("${baseUrl()}/Playlists/$playlistId/Items") {
            authorize()
            parameter("entryIds", entryIds.joinToString(","))
        }.ensureSuccess()
    }

    /** Marks an item (song, album, artist) as favorite of the user or unmarks it. */
    suspend fun setFavorite(itemId: String, favorite: Boolean) {
        val url = "${baseUrl()}/UserFavoriteItems/$itemId"
        val response = if (favorite) {
            client.post(url) { authorize(); parameter("userId", userId) }
        } else {
            client.delete(url) { authorize(); parameter("userId", userId) }
        }
        response.ensureSuccess()
    }

    /** Scans all libraries. Requires an administrator. */
    suspend fun refreshLibrary(): Boolean =
        client.post("${baseUrl()}/Library/Refresh") { authorize() }.status.isSuccess()

    /** Lyrics stored on the server (embedded, sidecar file or downloaded before); null if there are none. */
    suspend fun getLyrics(itemId: String): LyricDto? {
        val response = client.get("${baseUrl()}/Audio/$itemId/Lyrics") { authorize() }
        if (response.status == HttpStatusCode.NotFound) return null
        response.ensureSuccess()
        return response.body()
    }

    /**
     * Asks the server's lyrics providers (e.g. the LrcLib plugin) for lyrics of the item.
     * Requires the lyric management permission.
     */
    suspend fun searchRemoteLyrics(itemId: String): List<RemoteLyricInfoDto> = get("Audio/$itemId/RemoteSearch/Lyrics", emptyMap())

    /** Saves the [searchRemoteLyrics] result [lyricId] for the item, so [getLyrics] returns it from now on. */
    suspend fun downloadRemoteLyrics(itemId: String, lyricId: String): LyricDto {
        val response = client.post("${baseUrl()}/Audio/$itemId/RemoteSearch/Lyrics/${lyricId.encodeURLPathPart()}") { authorize() }
        response.ensureSuccess()
        return response.body()
    }

    /**
     * Stream URL that plays the original file if the platform can decode its container ([containers])
     * and an MP3 transcode otherwise. Carries the token as query parameter since players can't set headers.
     */
    fun getStreamUrl(itemId: String, containers: List<String>): String {
        val s = requireSession()
        return URLBuilder("${s.serverUrl}/Audio/$itemId/universal").apply {
            parameters.append("UserId", s.userId)
            parameters.append("DeviceId", s.deviceId)
            parameters.append("Container", containers.joinToString(","))
            parameters.append("TranscodingContainer", "mp3")
            parameters.append("TranscodingProtocol", "http")
            parameters.append("AudioCodec", "mp3")
            parameters.append("MaxStreamingBitrate", "999999999")
            parameters.append("ApiKey", s.accessToken)
        }.buildString()
    }

    /** The original file, without transcoding. Unlike /Items/{id}/Download, this needs no download permission. */
    fun getOriginalAudioUrl(itemId: String): String {
        val s = requireSession()
        return URLBuilder("${s.serverUrl}/Audio/$itemId/stream").apply {
            parameters.append("static", "true")
            parameters.append("ApiKey", s.accessToken)
        }.buildString()
    }

    fun getImageUrl(itemId: String, size: Int?, imageType: String = "Primary"): String {
        val s = requireSession()
        return URLBuilder("${s.serverUrl}/Items/$itemId/Images/$imageType").apply {
            if (size != null) {
                parameters.append("fillWidth", size.toString())
                parameters.append("fillHeight", size.toString())
            }
            parameters.append("quality", "90")
        }.buildString()
    }

    // Sessions: remote control between clients and playback reporting

    /** Lets other clients of the user control this one, see [runSocket]. */
    suspend fun postCapabilities(capabilities: ClientCapabilitiesDto) = postJson("Sessions/Capabilities/Full", capabilities)

    suspend fun reportPlaybackStart(report: PlaybackReportDto) = postJson("Sessions/Playing", report)

    suspend fun reportPlaybackProgress(report: PlaybackReportDto) = postJson("Sessions/Playing/Progress", report)

    suspend fun reportPlaybackStopped(report: PlaybackReportDto) = postJson("Sessions/Playing/Stopped", report)

    /** The sessions the user can control, this client's own included. */
    suspend fun getSessions(): List<SessionInfoDto> = get("Sessions", mapOf("controllableByUserId" to userId))

    /** The id of this client's session; null until the server has one, e.g. right after logging in. */
    suspend fun getOwnSessionId(): String? =
        get<List<SessionInfoDto>>("Sessions", mapOf("deviceId" to requireSession().deviceId)).firstOrNull()?.id

    /** Makes the client of [sessionId] play the items; [playCommand] is one of [PlayRequestDto.playCommand]. */
    suspend fun sendPlay(sessionId: String, itemIds: List<String>, playCommand: String, startIndex: Int? = null, startPositionTicks: Long? = null) {
        client.post("${baseUrl()}/Sessions/$sessionId/Playing") {
            authorize()
            parameter("playCommand", playCommand)
            parameter("itemIds", itemIds.joinToString(","))
            if (startIndex != null) parameter("startIndex", startIndex)
            if (startPositionTicks != null) parameter("startPositionTicks", startPositionTicks)
        }.ensureSuccess()
    }

    /** [command] is one of [PlaystateRequestDto.command]. */
    suspend fun sendPlaystate(sessionId: String, command: String, seekPositionTicks: Long? = null) {
        client.post("${baseUrl()}/Sessions/$sessionId/Playing/$command") {
            authorize()
            if (seekPositionTicks != null) parameter("seekPositionTicks", seekPositionTicks)
        }.ensureSuccess()
    }

    suspend fun sendGeneralCommand(sessionId: String, command: GeneralCommandDto) = postJson("Sessions/$sessionId/Command", command)

    /**
     * Connects to the server's websocket, through which it forwards what other clients send to this one, and passes
     * its messages to [onMessage] until it closes. [onConnected] gets to send messages meanwhile.
     */
    suspend fun runSocket(
        onConnected: suspend (send: suspend (SocketMessageDto) -> Unit) -> Unit,
        onMessage: suspend (SocketMessageDto) -> Unit,
    ) {
        val s = requireSession()
        val url = URLBuilder(s.serverUrl).apply {
            protocol = if (protocol == URLProtocol.HTTPS) URLProtocol.WSS else URLProtocol.WS
            appendPathSegments("socket")
            parameters.append("ApiKey", s.accessToken)
            parameters.append("deviceId", s.deviceId)
        }.buildString()
        client.webSocket(url) {
            val send: suspend (SocketMessageDto) -> Unit = { send(Frame.Text(json.encodeToString(it))) }
            launch { onConnected(send) }
            var keepAlive: Job? = null
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val message = runCatching { json.decodeFromString<SocketMessageDto>(frame.readText()) }.getOrNull() ?: continue
                if (message.messageType == "ForceKeepAlive") {
                    // The server drops the connection if it doesn't hear from the client within that many seconds
                    val timeoutSeconds = message.data?.toString()?.toLongOrNull() ?: 60L
                    keepAlive?.cancel()
                    keepAlive = launch {
                        while (true) {
                            send(SocketMessageDto("KeepAlive"))
                            delay(timeoutSeconds * 1000L / 2)
                        }
                    }
                    continue
                }
                onMessage(message)
            }
        }
    }

    // Voidcast plugin

    /** Returns null if the Voidcast plugin isn't installed on the server. */
    suspend fun getPluginInfo(): PluginInfoDto? {
        val response = client.get("${baseUrl()}/Voidcast/Info") { authorize() }
        if (!response.status.isSuccess()) return null
        // Jellyfin answers unknown routes of its web client with HTML
        if (response.contentType()?.match(ContentType.Application.Json) != true) return null
        return response.body()
    }

    /** [includeVideos] needs plugin 1.8.0.0, see [PluginInfoDto.videoAvailable]. */
    suspend fun searchYouTube(query: String, includeVideos: Boolean): List<YouTubeSongDto> =
        get("Voidcast/YouTube/Search", mapOf("query" to query, "includeVideos" to includeVideos.takeIf { it }))

    suspend fun searchYouTubeArtists(query: String): List<YouTubeArtistSummaryDto> =
        get("Voidcast/YouTube/Artists/Search", mapOf("query" to query))

    suspend fun getYouTubeArtist(channelId: String): YouTubeArtistPageDto = get("Voidcast/YouTube/Artists/$channelId", emptyMap())

    suspend fun getYouTubeAlbum(albumId: String): YouTubeAlbumDto = get("Voidcast/YouTube/Albums/$albumId", emptyMap())

    /** Queues a download of [song], a result of [searchYouTube]. */
    suspend fun requestDownload(song: YouTubeSongDto): DownloadJobDto {
        val response = client.post("${baseUrl()}/Voidcast/Downloads") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(song)
        }
        response.ensureSuccess()
        return response.body()
    }

    /** Adds [songs] to the playlist in the background, downloading them first, see [PluginInfoDto.playlistAdditionAvailable]. */
    suspend fun addYouTubeToPlaylist(playlistId: String, songs: List<YouTubeSongDto>): PlaylistAdditionDto {
        val response = client.post("${baseUrl()}/Voidcast/YouTube/Playlists/$playlistId/Items") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(songs)
        }
        response.ensureSuccess()
        return response.body()
    }

    suspend fun getPlaylistAddition(additionId: String): PlaylistAdditionDto =
        get("Voidcast/YouTube/Playlists/Additions/$additionId", emptyMap())

    suspend fun getDownload(jobId: String): DownloadJobDto = get("Voidcast/Downloads/$jobId", emptyMap())

    /**
     * Those of [videoIds] whose tracks are already in the library. Empty with plugins that can't tell; without
     * codec and bitrate with plugins before 1.7.3.1.
     */
    suspend fun getDownloadedYouTube(videoIds: List<String>): List<DownloadedTrackDto> {
        val response = client.post("${baseUrl()}/Voidcast/YouTube/Downloaded/Details") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(videoIds)
        }
        if (response.status != HttpStatusCode.NotFound) {
            response.ensureSuccess()
            return response.body()
        }
        val legacy = client.post("${baseUrl()}/Voidcast/YouTube/Downloaded") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(videoIds)
        }
        if (legacy.status == HttpStatusCode.NotFound) return emptyList()
        legacy.ensureSuccess()
        return legacy.body<List<String>>().map { DownloadedTrackDto(it) }
    }

    /** Those of [albumIds] whose tracks are all in the library. Empty with plugins that can't tell. */
    suspend fun getDownloadedYouTubeAlbums(albumIds: List<String>): List<String> {
        val response = client.post("${baseUrl()}/Voidcast/YouTube/Albums/Downloaded") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(albumIds)
        }
        if (response.status == HttpStatusCode.NotFound) return emptyList()
        response.ensureSuccess()
        return response.body()
    }

    /** The user's recent downloads. */
    suspend fun getDownloads(): List<DownloadJobDto> = get("Voidcast/Downloads", emptyMap())

    suspend fun getYouTubeStreamInfo(videoId: String): AudioStreamInfoDto? {
        val response = client.get("${baseUrl()}/Voidcast/YouTube/$videoId/StreamInfo") { authorize() }
        if (response.status == HttpStatusCode.NoContent) return null
        response.ensureSuccess()
        return response.body()
    }

    /** Audio of a YouTube Music track, served by the plugin. [mp3] converts it for players without Opus/AAC support. */
    fun getYouTubeAudioUrl(videoId: String, mp3: Boolean): String {
        val s = requireSession()
        return URLBuilder("${s.serverUrl}/Voidcast/YouTube/$videoId/Audio").apply {
            if (mp3) parameters.append("format", "mp3")
            parameters.append("ApiKey", s.accessToken)
        }.buildString()
    }

    /** A YouTube video with picture and sound (MP4), in the highest resolution up to [maxHeight]. */
    fun getYouTubeVideoUrl(videoId: String, maxHeight: Int): String {
        val s = requireSession()
        return URLBuilder("${s.serverUrl}/Voidcast/YouTube/$videoId/Video").apply {
            parameters.append("maxHeight", maxHeight.toString())
            parameters.append("ApiKey", s.accessToken)
        }.buildString()
    }

    /** Codecs and bitrates of the streams of [getYouTubeVideoUrl]; null if unknown or with plugins before 1.8.1.0. */
    suspend fun getYouTubeVideoInfo(videoId: String, maxHeight: Int): VideoStreamInfoDto? {
        val response = client.get("${baseUrl()}/Voidcast/YouTube/$videoId/VideoInfo") {
            authorize()
            parameter("maxHeight", maxHeight)
        }
        if (response.status == HttpStatusCode.NoContent || response.status == HttpStatusCode.NotFound) return null
        response.ensureSuccess()
        return response.body()
    }

    /** Heights (e.g. 1080) the video is available in, highest first. */
    suspend fun getYouTubeVideoHeights(videoId: String): List<Int> = get("Voidcast/YouTube/$videoId/VideoHeights", emptyMap())

    private suspend inline fun <reified T> get(path: String, params: Map<String, Any?>): T {
        val response = client.get("${baseUrl()}/$path") {
            authorize()
            params.forEach { (key, value) -> if (value != null) parameter(key, value) }
        }
        response.ensureSuccess()
        return response.body()
    }

    private suspend inline fun <reified T> postJson(path: String, body: T) {
        client.post("${baseUrl()}/$path") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(body)
        }.ensureSuccess()
    }

    private suspend fun HttpResponse.ensureSuccess() {
        if (status.isSuccess()) return
        // ASP.NET problem details carry a "detail" message
        val detail = runCatching { Regex(""""detail"\s*:\s*"([^"]*)"""").find(bodyAsText())?.groupValues?.get(1) }.getOrNull()
        throw JellyfinApiException(status, detail ?: "Jellyfin request failed: $status")
    }

    companion object {
        const val CLIENT_VERSION = AppVersion
    }
}
