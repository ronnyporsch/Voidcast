package de.ronnyporsch.voidcast.data.offline

import de.ronnyporsch.voidcast.data.repository.SearchResults
import de.ronnyporsch.voidcast.domain.model.Album
import de.ronnyporsch.voidcast.domain.model.Artist
import de.ronnyporsch.voidcast.domain.model.Genre
import de.ronnyporsch.voidcast.domain.model.Song

/** The library as far as the downloaded [songs] tell it, for browsing while the server isn't reachable. */
class OfflineLibrary(songs: List<Song>) {
    val songs = songs.sortedWith(compareBy({ it.album.lowercase() }, { it.discNumber ?: 1 }, { it.track ?: 0 }))

    val albums: List<Album> = this.songs
        .filter { it.albumId.isNotBlank() }
        .groupBy { it.albumId }
        .map { (id, songs) ->
            val first = songs.first()
            Album(
                id = id,
                name = first.album,
                artist = first.artist,
                artistId = first.artistId,
                artists = first.artists,
                coverArtId = songs.firstNotNullOfOrNull { it.coverArtId },
                songCount = songs.size,
                year = songs.firstNotNullOfOrNull { it.year },
                duration = songs.sumOf { it.duration },
                songs = songs,
                dateAdded = songs.mapNotNull { it.dateAdded }.maxOrNull(),
            )
        }

    val artists: List<Artist> = this.songs
        .flatMap { it.credits() }
        .groupBy { it.id.ifBlank { it.name.lowercase() } }
        .map { (_, credits) ->
            val artist = credits.first()
            artist.copy(albumCount = albums.count { album -> album.songs.any { artist.matches(it) } })
        }
        .sortedBy { it.name.lowercase() }

    /** Genres by name, as far as the songs have one. */
    val genres: List<Genre> = this.songs
        .filter { !it.genre.isNullOrBlank() }
        .groupBy { it.genre!!.lowercase() }
        .map { (_, songs) ->
            val name = songs.first().genre!!
            Genre(id = name, name = name, albumCount = songs.map { it.albumId }.distinct().size, songCount = songs.size)
        }
        .sortedBy { it.name.lowercase() }

    fun albumsOfGenre(name: String): List<Album> =
        albums.filter { album -> album.songs.any { it.genre.equals(name, ignoreCase = true) } }

    fun artist(name: String): Artist? = artists.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Albums whose album artist is [artistId]. */
    fun albumsOf(artistId: String): List<Album> = albums.filter { it.artistId == artistId }

    /** Albums not in [ownAlbumIds] with songs crediting [artistName]. */
    fun appearsOn(artistName: String, ownAlbumIds: Collection<String>): List<Album> = albums.filter { album ->
        album.id !in ownAlbumIds && album.songs.any { song -> song.credits().any { it.name.equals(artistName, ignoreCase = true) } }
    }

    fun album(albumId: String): Album? = albums.firstOrNull { it.id == albumId }

    fun songsOf(artistName: String, albumIds: Collection<String>): List<Song> = songs.filter { song ->
        song.albumId in albumIds || song.credits().any { it.name.equals(artistName, ignoreCase = true) }
    }

    fun search(query: String): SearchResults {
        val q = query.trim()
        if (q.isEmpty()) return SearchResults()
        return SearchResults(
            artists = artists.filter { it.name.contains(q, ignoreCase = true) },
            albums = albums.filter { it.name.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true) },
            songs = songs.filter {
                it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true) ||
                    it.album.contains(q, ignoreCase = true)
            },
        )
    }

    private fun Song.credits(): List<Artist> =
        artists.ifEmpty { listOf(Artist(artistId, artist)) }.filter { it.name.isNotBlank() }

    private fun Artist.matches(song: Song): Boolean =
        song.credits().any { if (id.isNotBlank()) it.id == id else it.name.equals(name, ignoreCase = true) }
}
