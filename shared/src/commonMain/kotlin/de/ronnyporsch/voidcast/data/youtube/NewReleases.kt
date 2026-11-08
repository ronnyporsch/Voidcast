package de.ronnyporsch.voidcast.data.youtube

import de.ronnyporsch.voidcast.domain.model.Album
import de.ronnyporsch.voidcast.domain.model.DEFAULT_NEW_RELEASES_MIN_SONGS
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.platform.readAppFile
import de.ronnyporsch.voidcast.platform.writeAppFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A library artist; [albums] and [songs] are the [normalizedTitle]s of what the library has of them. */
internal data class OwnedArtist(val name: String, val albums: Set<String>, val songs: Set<String>)

/**
 * The artists credited on at least [minSongs] of these songs, those with the most first. Artists are told apart by
 * their name, ignoring case.
 */
internal fun List<Song>.frequentArtists(minSongs: Int): List<OwnedArtist> =
    creditsByArtist().values
        .filter { it.size >= minSongs }
        .sortedByDescending { it.size }
        .map { it.toOwnedArtist() }

/** The artists with these [names], those with the most songs first; they may have no songs in the library at all. */
internal fun List<Song>.artistsNamed(names: Set<String>): List<OwnedArtist> {
    val credits = creditsByArtist()
    return names.distinctBy { it.lowercase() }
        .sortedByDescending { credits[it.lowercase()]?.size ?: 0 }
        .map { credits[it.lowercase()]?.toOwnedArtist() ?: OwnedArtist(it, emptySet(), emptySet()) }
}

/** The songs crediting each artist together with the artist's name, by that name in lower case. */
private fun List<Song>.creditsByArtist(): Map<String, List<Pair<String, Song>>> =
    flatMap { song -> song.artists.map { it.name }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.map { it to song } }
        .groupBy({ (name, _) -> name.lowercase() })

private fun List<Pair<String, Song>>.toOwnedArtist(): OwnedArtist {
    val songs = map { (_, song) -> song }
    return OwnedArtist(
        name = first().first,
        albums = songs.filter { it.album.isNotBlank() }.mapTo(mutableSetOf()) { normalizedTitle(it.album) },
        songs = songs.mapTo(mutableSetOf()) { normalizedTitle(it.title) },
    )
}

/**
 * Those of the YouTube Music releases of [artist] from [sinceYear] on that the library doesn't have. A single or EP
 * also counts as owned if the library has a song named like it, e.g. from the album that followed it.
 */
internal fun List<Album>.notOwnedBy(artist: OwnedArtist, sinceYear: Int): List<Album> = filter { release ->
    val title = normalizedTitle(release.name)
    val isAlbum = release.releaseType == null || release.releaseType == "Album"
    (release.year ?: 0) >= sinceYear && title !in artist.albums && (isAlbum || title !in artist.songs)
}

/**
 * Newest first, those without a known date last in their year, otherwise albums before singles; of several editions
 * of a release only the first stays.
 */
internal fun List<Album>.inNewReleasesOrder(): List<Album> =
    sortedWith(
        compareByDescending<Album> { it.year ?: 0 }
            // yyyy-MM-dd sorts like the dates, null lowest
            .thenByDescending { it.releaseDate }
            .thenBy { if (it.releaseType == null || it.releaseType == "Album") 0 else 1 }
            .thenBy { it.artist.lowercase() }
            .thenBy { it.name.lowercase() }
    )
        .distinctBy { it.id }
        .distinctBy { it.artist.lowercase() to normalizedTitle(it.name) }

/** For comparing the titles of different sources: without case, punctuation and qualifiers like "(Deluxe Edition)". */
internal fun normalizedTitle(title: String): String {
    fun letters(text: String) = text.filter { it.isLetterOrDigit() }
    val lower = title.lowercase()
    return letters(lower.replace(TITLE_QUALIFIERS, "")).ifEmpty { letters(lower) }
}

private val TITLE_QUALIFIERS = Regex("""[(\[].*?[)\]]|\s-\s(single|ep)$""")

/** Which artists a search for new releases looks at. */
@Serializable
data class NewReleasesCriteria(
    /** The fewest songs in the library an artist needs; doesn't matter with [favoriteArtists]. */
    val minSongs: Int = DEFAULT_NEW_RELEASES_MIN_SONGS,
    /** The names of the artists to look at instead; null for all with [minSongs]. */
    val favoriteArtists: Set<String>? = null,
)

/** The result of a search for new releases. */
@Serializable
data class NewReleasesScan(
    /** When it finished, in milliseconds since the epoch. */
    val scannedAt: Long,
    /** How many artists got looked at. */
    val artistCount: Int,
    val criteria: NewReleasesCriteria = NewReleasesCriteria(),
    val releases: List<Album>,
)

@Serializable
private data class NewReleasesFile(val server: String, val scan: NewReleasesScan)

/** The latest [NewReleasesScan] of the server identified by [serverKey], stored on the device. */
class NewReleasesCache(private val serverKey: () -> String) {
    suspend fun load(): NewReleasesScan? =
        readAppFile(FILE_NAME)
            ?.let { runCatching { json.decodeFromString<NewReleasesFile>(it) }.getOrNull() }
            ?.takeIf { it.server == serverKey() }
            ?.scan

    suspend fun save(scan: NewReleasesScan) = writeAppFile(FILE_NAME, json.encodeToString(NewReleasesFile(serverKey(), scan)))

    private companion object {
        const val FILE_NAME = "new-releases.json"
        val json = Json { ignoreUnknownKeys = true }
    }
}
