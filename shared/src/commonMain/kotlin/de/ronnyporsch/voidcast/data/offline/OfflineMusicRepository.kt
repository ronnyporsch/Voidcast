package de.ronnyporsch.voidcast.data.offline

import de.ronnyporsch.voidcast.data.repository.MusicRepository
import de.ronnyporsch.voidcast.data.repository.SearchResults
import de.ronnyporsch.voidcast.domain.model.Album
import de.ronnyporsch.voidcast.domain.model.Artist
import de.ronnyporsch.voidcast.domain.model.ArtistInfo
import de.ronnyporsch.voidcast.domain.model.AudioStreamInfo
import de.ronnyporsch.voidcast.domain.model.Genre
import de.ronnyporsch.voidcast.domain.model.Playlist
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.domain.model.audioFormat
import de.ronnyporsch.voidcast.platform.SongFileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * [server], except that while the server isn't reachable playlists come from [cache] and [downloads], and artists,
 * albums and search results from the downloaded songs ([OfflineLibrary]); downloaded songs play from their [files].
 */
class OfflineMusicRepository(
    private val server: MusicRepository,
    private val cache: PlaylistCache,
    private val downloads: PlaylistDownloads,
    private val files: SongFileStore?,
) : MusicRepository by server {

    private val _playlistChanges = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Ids of playlists that got changed through this repository. */
    val playlistChanges: SharedFlow<String> = _playlistChanges.asSharedFlow()

    override suspend fun getPlaylists(): Result<List<Playlist>> =
        server.getPlaylists()
            .onSuccess {
                cache.putPlaylists(it)
                downloads.onPlaylistsFetched(it)
            }
            .recoverOffline {
                val cached = cache.playlists()
                val cachedIds = cached.mapTo(mutableSetOf()) { it.id }
                (cached + downloads.playlists().filterNot { it.id in cachedIds }.map { it.copy(songs = emptyList()) })
                    .sortedBy { it.name.lowercase() }
                    .ifEmpty { null }
            }

    override suspend fun getPlaylist(playlistId: String): Result<Playlist> =
        server.getPlaylist(playlistId)
            .onSuccess {
                cache.putPlaylist(it)
                downloads.onPlaylistsFetched(listOf(it))
            }
            .recoverOffline { downloads.playlist(playlistId) ?: cache.playlist(playlistId) }

    override suspend fun getArtists(): Result<List<Artist>> =
        server.getArtists().recoverOffline { library().artists.ifEmpty { null } }

    override suspend fun getArtistAlbums(artistId: String): Result<List<Album>> =
        server.getArtistAlbums(artistId).recoverOffline { library().albumsOf(artistId) }

    override suspend fun getAppearsOnAlbums(artistName: String, ownAlbumIds: List<String>): Result<List<Album>> =
        server.getAppearsOnAlbums(artistName, ownAlbumIds).recoverOffline { library().appearsOn(artistName, ownAlbumIds) }

    override suspend fun getArtistInfo(artistId: String): Result<ArtistInfo> =
        server.getArtistInfo(artistId).recoverOffline { ArtistInfo() }

    override suspend fun getAlbum(albumId: String): Result<Album> =
        server.getAlbum(albumId).recoverOffline { library().album(albumId) }

    override suspend fun getAlbums(): Result<List<Album>> =
        server.getAlbums().recoverOffline { library().albums.map { it.copy(songs = emptyList()) }.ifEmpty { null } }

    override suspend fun getAllSongs(): Result<List<Song>> =
        server.getAllSongs().recoverOffline { library().songs.ifEmpty { null } }

    override suspend fun getGenres(): Result<List<Genre>> =
        server.getGenres().recoverOffline { library().genres.ifEmpty { null } }

    override suspend fun getGenreSongs(genre: Genre): Result<List<Song>> =
        server.getGenreSongs(genre).recoverOffline { library().songsOfGenre(genre.name) }

    // Artists without an id are looked up by name again, so the songs crediting them get found
    override suspend fun findArtistByName(name: String): Result<Artist?> =
        server.findArtistByName(name).recoverOffline { library().artist(name)?.takeIf { it.id.isNotBlank() } }

    override suspend fun findSongsByArtistName(name: String): Result<List<Song>> =
        server.findSongsByArtistName(name).recoverOffline { library().songsOf(name, emptyList()) }

    override suspend fun getSongsForArtist(artistName: String, albumIds: List<String>): Result<List<Song>> =
        server.getSongsForArtist(artistName, albumIds).recoverOffline { library().songsOf(artistName, albumIds) }

    override suspend fun search(query: String): Result<SearchResults> =
        server.search(query).recoverOffline { library().search(query) }

    override suspend fun getAudioFormat(song: Song): Result<AudioStreamInfo?> =
        server.getAudioFormat(song).recoverOffline { song.audioFormat }

    override suspend fun createPlaylist(name: String, songIds: List<String>): Result<Playlist> =
        server.createPlaylist(name, songIds).onSuccess { _playlistChanges.emit(it.id) }

    override suspend fun addSongsToPlaylist(playlistId: String, songIds: List<String>): Result<Unit> =
        server.addSongsToPlaylist(playlistId, songIds).onSuccess { changed(playlistId) }

    override suspend fun removeSongsFromPlaylist(playlistId: String, songIndices: List<Int>): Result<Unit> =
        server.removeSongsFromPlaylist(playlistId, songIndices).onSuccess { changed(playlistId) }

    override fun getStreamUrl(songId: String): String = files?.uriOf(songId) ?: server.getStreamUrl(songId)

    // Downloaded covers also show while the server can't be reached
    override fun getCoverArtUrl(coverArtId: String, size: Int): String =
        files?.coverUriOf(coverArtId) ?: server.getCoverArtUrl(coverArtId, size)

    private suspend fun library() = OfflineLibrary(downloads.downloadedSongs())

    /** Tells about a change of a playlist made without this repository, e.g. by the server. */
    suspend fun changed(playlistId: String) {
        downloads.onPlaylistChanged(playlistId)
        _playlistChanges.emit(playlistId)
    }

    /** Replaces a failure with [fallback], unless that is null too. */
    private suspend fun <T> Result<T>.recoverOffline(fallback: suspend () -> T?): Result<T> {
        val error = exceptionOrNull() ?: return this
        if (error is CancellationException) return this
        return fallback()?.let { Result.success(it) } ?: this
    }
}
