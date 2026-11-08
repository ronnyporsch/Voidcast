package de.ronnyporsch.voidcast.util

import de.ronnyporsch.voidcast.domain.model.Album
import de.ronnyporsch.voidcast.domain.model.Artist
import de.ronnyporsch.voidcast.domain.model.LibraryOrder
import de.ronnyporsch.voidcast.domain.model.LibrarySort
import de.ronnyporsch.voidcast.domain.model.Playlist
import de.ronnyporsch.voidcast.domain.model.Song
import kotlin.jvm.JvmName

// Items without a release date or date added go last in either direction; ties go A to Z.
// Dates are ISO 8601 timestamps, which order like their strings.

/** Orders by [key] in the [order]'s direction, items without one last. */
private fun <T, K : Comparable<K>> LibraryOrder.by(key: (T) -> K?): Comparator<T> =
    compareBy(nullsLast(if (isAscending) naturalOrder() else reverseOrder<K>()), key)

@JvmName("sortedArtists")
fun List<Artist>.sortedBy(order: LibraryOrder): List<Artist> = when (order.sort) {
    LibrarySort.DateAdded -> sortedWith(order.by<Artist, String> { it.dateAdded }.thenBy { it.name.lowercase() })
    LibrarySort.SongCount -> sortedWith(order.by<Artist, Int> { it.songCount }.thenBy { it.name.lowercase() })
    else -> sortedWith(order.by { it.name.lowercase() })
}

/**
 * Fills in [Artist.songCount] from all [albums] of the library, each counting for all its album artists. Leaves it
 * unknown while there are no albums yet.
 */
fun List<Artist>.withSongCounts(albums: List<Album>): List<Artist> {
    if (albums.isEmpty()) return this
    val counts = mutableMapOf<String, Int>()
    for (album in albums) {
        val credits = album.artists.ifEmpty { listOf(Artist(album.artistId, album.artist)) }
        for (key in credits.map { it.songCountKey }.distinct()) counts[key] = (counts[key] ?: 0) + album.songCount
    }
    return map { it.copy(songCount = counts[it.songCountKey] ?: 0) }
}

private val Artist.songCountKey: String get() = id.ifBlank { name.lowercase() }

@JvmName("sortedAlbums")
fun List<Album>.sortedBy(order: LibraryOrder): List<Album> = when (order.sort) {
    LibrarySort.Alphabetical, LibrarySort.SongCount -> sortedWith(order.by { it.name.lowercase() })
    LibrarySort.ReleaseDate -> sortedWith(order.by<Album, Int> { it.year }.thenBy { it.name.lowercase() })
    LibrarySort.DateAdded -> sortedWith(order.by<Album, String> { it.dateAdded }.thenBy { it.name.lowercase() })
}

/** Songs of the same album keep their track order when sorted by release date or date added. */
@JvmName("sortedSongs")
fun List<Song>.sortedBy(order: LibraryOrder): List<Song> {
    val trackOrder = compareBy<Song>({ it.album.lowercase() }, { it.discNumber ?: 1 }, { it.track ?: 0 }, { it.title.lowercase() })
    return when (order.sort) {
        LibrarySort.Alphabetical, LibrarySort.SongCount -> sortedWith(order.by<Song, String> { it.title.lowercase() }.thenBy { it.artist.lowercase() })
        LibrarySort.ReleaseDate -> sortedWith(order.by<Song, Int> { it.year }.then(trackOrder))
        LibrarySort.DateAdded -> sortedWith(order.by<Song, String> { it.dateAdded }.then(trackOrder))
    }
}

@JvmName("sortedPlaylists")
fun List<Playlist>.sortedBy(order: LibraryOrder): List<Playlist> = when (order.sort) {
    LibrarySort.DateAdded -> sortedWith(order.by<Playlist, String> { it.dateAdded }.thenBy { it.name.lowercase() })
    else -> sortedWith(order.by { it.name.lowercase() })
}
