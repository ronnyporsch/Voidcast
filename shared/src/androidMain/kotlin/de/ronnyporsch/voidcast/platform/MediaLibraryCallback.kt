package de.ronnyporsch.voidcast.platform

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import de.ronnyporsch.voidcast.data.repository.MusicServerConnection
import de.ronnyporsch.voidcast.domain.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val ROOT_ID = "root"
private const val PLAYLISTS_ID = "playlists"
private const val ARTISTS_ID = "artists"
private const val PLAYLIST_PREFIX = "playlist:"
private const val ARTIST_PREFIX = "artist:"
private const val ALBUM_PREFIX = "album:"

// A song within its playlist or album, so choosing it plays the whole list: "track:<index>:<playlist or album id>"
private const val TRACK_PREFIX = "track:"

/**
 * The library as media browsers like Google Maps and Android Auto see it: playlists, and artists with their albums.
 * Choosing a song plays its playlist or album from there on.
 */
@OptIn(UnstableApi::class)
internal class MediaLibraryCallback(private val connection: MusicServerConnection) : MediaLibrarySession.Callback {
    private val repository get() = connection.repository
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    fun release() {
        scope.cancel()
    }

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
        if (mayControl(AppContextHolder.context, controller)) super.onConnect(session, controller) else MediaSession.ConnectionResult.reject()

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        future { LibraryResult.ofItem(rootItem(), params) }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = libraryFuture {
        val item = when {
            mediaId == ROOT_ID -> rootItem()
            mediaId == PLAYLISTS_ID -> folder(PLAYLISTS_ID, "Playlists")
            mediaId == ARTISTS_ID -> folder(ARTISTS_ID, "Artists")
            // Only the artist's albums are known, not its name
            mediaId.startsWith(ARTIST_PREFIX) -> folder(mediaId, "Artist")
            mediaId.startsWith(PLAYLIST_PREFIX) -> awaitConnection().let {
                val playlist = repository.getPlaylist(mediaId.removePrefix(PLAYLIST_PREFIX)).getOrThrow()
                folder(mediaId, playlist.name, coverArtId = playlist.coverArtId, playable = true)
            }
            mediaId.startsWith(ALBUM_PREFIX) -> awaitConnection().let {
                val album = repository.getAlbum(mediaId.removePrefix(ALBUM_PREFIX)).getOrThrow()
                folder(mediaId, album.name, subtitle = album.artist, coverArtId = album.coverArtId, playable = true)
            }
            mediaId.startsWith(TRACK_PREFIX) -> {
                val (songs, index) = resolveSongs(mediaId) ?: return@libraryFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                songItem(mediaId, songs[index])
            }
            else -> return@libraryFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }
        LibraryResult.ofItem(item, null)
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = libraryFuture {
        val children = when {
            parentId == ROOT_ID -> listOf(folder(PLAYLISTS_ID, "Playlists"), folder(ARTISTS_ID, "Artists"))
            parentId == PLAYLISTS_ID -> awaitConnection().let {
                repository.getPlaylists().getOrThrow().map {
                    folder(
                        PLAYLIST_PREFIX + it.id, it.name,
                        subtitle = "${it.songCount} songs", coverArtId = it.coverArtId, playable = true,
                    )
                }
            }
            parentId == ARTISTS_ID -> awaitConnection().let {
                repository.getArtists().getOrThrow().map {
                    folder(ARTIST_PREFIX + it.id, it.name, coverArtId = it.coverArtId)
                }
            }
            parentId.startsWith(ARTIST_PREFIX) -> awaitConnection().let {
                repository.getArtistAlbums(parentId.removePrefix(ARTIST_PREFIX)).getOrThrow().map {
                    folder(
                        ALBUM_PREFIX + it.id, it.name,
                        subtitle = listOfNotNull(it.artist.ifBlank { null }, it.year?.toString()).joinToString(" · "),
                        coverArtId = it.coverArtId, playable = true,
                    )
                }
            }
            parentId.startsWith(PLAYLIST_PREFIX) || parentId.startsWith(ALBUM_PREFIX) -> {
                awaitConnection()
                songsOf(parentId).mapIndexed { index, song -> songItem("$TRACK_PREFIX$index:$parentId", song) }
            }
            else -> return@libraryFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }
        val paged = if (pageSize in 1 until Int.MAX_VALUE) children.drop(page * pageSize).take(pageSize) else children
        LibraryResult.ofItemList(paged, params)
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> = future {
        val requested = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: throw IllegalArgumentException("Nothing to play")
        val (songs, index) = resolveSongs(requested.mediaId) ?: throw IllegalArgumentException("Can't play ${requested.mediaId}")
        PlaybackBridgePlayer.play(songs, index)
        // A single item, the bridge player shows the app's queue anyway
        MediaItemsWithStartPosition(listOf(songItem(requested.mediaId, songs[index])), 0, C.TIME_UNSET)
    }

    /** The songs to play for [mediaId] and the index of the first one, or null if there are none. */
    private suspend fun resolveSongs(mediaId: String): Pair<List<Song>, Int>? {
        awaitConnection()
        val (containerId, index) = if (mediaId.startsWith(TRACK_PREFIX)) {
            val rest = mediaId.removePrefix(TRACK_PREFIX)
            rest.substringAfter(':') to (rest.substringBefore(':').toIntOrNull() ?: return null)
        } else {
            mediaId to 0
        }
        if (!containerId.startsWith(PLAYLIST_PREFIX) && !containerId.startsWith(ALBUM_PREFIX)) return null
        val songs = songsOf(containerId)
        return if (index in songs.indices) songs to index else null
    }

    private suspend fun songsOf(containerId: String): List<Song> = when {
        containerId.startsWith(PLAYLIST_PREFIX) ->
            repository.getPlaylist(containerId.removePrefix(PLAYLIST_PREFIX)).getOrThrow().songs
        else -> repository.getAlbum(containerId.removePrefix(ALBUM_PREFIX)).getOrThrow().songs
    }

    /**
     * When the browser started the app, the saved login may still be connecting. Without a connection the
     * repository still has the playlists stored on the device.
     */
    private suspend fun awaitConnection() {
        withTimeoutOrNull(15_000) { connection.connections.first { it > 0 } }
    }

    private fun rootItem() = folder(ROOT_ID, "Voidcast")

    private fun folder(
        id: String,
        title: String,
        subtitle: String? = null,
        coverArtId: String? = null,
        playable: Boolean = false,
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(coverArtUri(coverArtId))
            .setIsBrowsable(true)
            .setIsPlayable(playable)
            .setMediaType(
                when {
                    id.startsWith(PLAYLIST_PREFIX) -> MediaMetadata.MEDIA_TYPE_PLAYLIST
                    id.startsWith(ALBUM_PREFIX) -> MediaMetadata.MEDIA_TYPE_ALBUM
                    id.startsWith(ARTIST_PREFIX) -> MediaMetadata.MEDIA_TYPE_ARTIST
                    id == PLAYLISTS_ID -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                    id == ARTISTS_ID -> MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
                    else -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                }
            )
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
    }

    private fun songItem(id: String, song: Song): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setSubtitle(song.artist)
            .setArtworkUri(coverArtUri(song.coverArtId))
            .setDurationMs(song.duration * 1000L)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
    }

    private fun coverArtUri(coverArtId: String?): Uri? =
        coverArtId?.let { runCatching { Uri.parse(repository.getCoverArtUrl(it, 300)) }.getOrNull() }

    /** Like [future], with failures (e.g. the server not being reachable) as an error result. */
    private fun <T : Any> libraryFuture(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> = future {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LibraryResult.ofError(SessionError.ERROR_IO)
        }
    }

    /** Runs [block] on the main thread, which the session and the repository's callers expect. */
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        scope.launch {
            try {
                future.set(block())
            } catch (e: CancellationException) {
                future.cancel(false)
                throw e
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        return future
    }
}
