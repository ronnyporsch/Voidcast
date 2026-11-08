package de.ronnyporsch.voidcast

import de.ronnyporsch.voidcast.data.youtube.OwnedArtist
import de.ronnyporsch.voidcast.data.youtube.artistsNamed
import de.ronnyporsch.voidcast.data.youtube.frequentArtists
import de.ronnyporsch.voidcast.data.youtube.inNewReleasesOrder
import de.ronnyporsch.voidcast.data.youtube.normalizedTitle
import de.ronnyporsch.voidcast.data.youtube.notOwnedBy
import de.ronnyporsch.voidcast.domain.model.Album
import de.ronnyporsch.voidcast.domain.model.Artist
import de.ronnyporsch.voidcast.domain.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals

class NewReleasesTest {
    private fun songs(count: Int, vararg artists: String, album: String = "Album") = List(count) { index ->
        Song(id = "${artists.first()}$index", title = "Song $index", album = album, artists = artists.map { Artist(id = it, name = it) })
    }

    private fun release(name: String, year: Int? = 2026, type: String? = "Album", artist: String = "A") =
        Album(id = "$artist/$name/$type", name = name, artist = artist, year = year, releaseType = type)

    private val artist = OwnedArtist("A", albums = setOf(normalizedTitle("Owned")), songs = setOf(normalizedTitle("Hit")))

    @Test
    fun onlyArtistsWithEnoughSongsCount() {
        val library = songs(14, "Few") + songs(15, "Enough")
        assertEquals(listOf("Enough"), library.frequentArtists(15).map { it.name })
    }

    @Test
    fun creditsOnSharedSongsAndDifferentCasesAddUp() {
        val library = songs(10, "Main", "Guest") + songs(5, "GUEST") + songs(5, "main")
        assertEquals(setOf("main", "guest"), library.frequentArtists(15).map { it.name.lowercase() }.toSet())
    }

    @Test
    fun artistsCreditedTwiceOnASongCountOnce() {
        assertEquals(emptyList(), songs(10, "Twin", "twin").frequentArtists(15))
    }

    @Test
    fun favoriteArtistsCountHoweverFewSongsTheyHave() {
        val library = songs(2, "Fave") + songs(20, "Other") + songs(5, "LOVED")
        val artists = library.artistsNamed(setOf("Fave", "Loved", "Absent"))
        assertEquals(listOf("LOVED", "Fave", "Absent"), artists.map { it.name })
        assertEquals(emptySet(), artists.last().albums)
    }

    @Test
    fun releasesOfTheLibraryAreLeftOut() {
        val releases = listOf(release("Owned (Deluxe Edition)"), release("Owned - Single", type = "Single"), release("Fresh"))
        assertEquals(listOf("Fresh"), releases.notOwnedBy(artist, 2025).map { it.name })
    }

    @Test
    fun singlesOfOwnedSongsAreLeftOutButAlbumsOfThatNameAreNot() {
        val releases = listOf(release("Hit", type = "Single"), release("Hit", type = "EP"), release("Hit"))
        assertEquals(listOf("Album"), releases.notOwnedBy(artist, 2025).map { it.releaseType })
    }

    @Test
    fun oldReleasesAndOnesWithoutAYearAreLeftOut() {
        val releases = listOf(release("Old", year = 2024), release("Last year", year = 2025), release("Unknown", year = null))
        assertEquals(listOf("Last year"), releases.notOwnedBy(artist, 2025).map { it.name })
    }

    @Test
    fun newestComeFirstWithAlbumsBeforeSinglesAndOneEditionEach() {
        val releases = listOf(
            release("Single", type = "Single"),
            release("Older", year = 2025),
            release("Record (Deluxe)"),
            release("Record"),
        )
        assertEquals(listOf("Record", "Single", "Older"), releases.inNewReleasesOrder().map { it.name })
    }

    @Test
    fun withinAYearTheLatestDateComesFirstAndUnknownDatesLast() {
        val releases = listOf(
            release("Unknown"),
            release("March", type = "Single").copy(releaseDate = "2026-03-14"),
            release("January").copy(releaseDate = "2026-01-02"),
            release("October", artist = "B", type = "EP").copy(releaseDate = "2026-10-30"),
            release("Older", year = 2025).copy(releaseDate = "2025-12-31"),
        )
        assertEquals(listOf("October", "March", "January", "Unknown", "Older"), releases.inNewReleasesOrder().map { it.name })
    }

    @Test
    fun titlesConsistingOfQualifiersKeepThem() {
        assertEquals("untitled", normalizedTitle("(Untitled)"))
    }
}
