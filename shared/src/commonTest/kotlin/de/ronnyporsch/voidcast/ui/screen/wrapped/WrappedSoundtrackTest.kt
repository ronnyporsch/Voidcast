package de.ronnyporsch.voidcast.ui.screen.wrapped

import de.ronnyporsch.voidcast.data.jellyfin.WrappedAlbumDto
import de.ronnyporsch.voidcast.data.jellyfin.WrappedArtistDto
import de.ronnyporsch.voidcast.data.jellyfin.WrappedDto
import de.ronnyporsch.voidcast.data.jellyfin.WrappedRepeatDto
import de.ronnyporsch.voidcast.data.jellyfin.WrappedSongDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WrappedSoundtrackTest {

    private fun song(id: String?, artist: String = "Other", albumId: String? = null) =
        WrappedSongDto(id = id, name = "Song $id", artist = artist, albumId = albumId, plays = 1)

    private val wrapped = WrappedDto(
        playCount = 10,
        topSongs = listOf(song("s1"), song("s2", artist = "Band", albumId = "a1"), song("s3"), song(null), song("s4")),
        topArtists = listOf(WrappedArtistDto(name = "Band")),
        topAlbums = listOf(WrappedAlbumDto(id = "a1", name = "Album")),
        onRepeat = WrappedRepeatDto(song = song("r1")),
    )

    @Test
    fun slidesPlayTheirOwnSongs() {
        val plan = planSoundtrack(wrapped, slidesOf(wrapped))
        assertEquals("s1", plan[Slide.TopSongs])
        assertEquals("s1", plan[Slide.Summary])
        assertEquals("r1", plan[Slide.OnRepeat])
        assertEquals("s2", plan[Slide.TopArtist])
    }

    @Test
    fun otherSlidesGetTopSongsNoOtherSlidePlays() {
        val plan = planSoundtrack(wrapped, slidesOf(wrapped))
        // The top album's only top song is the top artist's, so it plays it too
        assertEquals("s2", plan[Slide.TopAlbums])
        assertEquals("s3", plan[Slide.Intro])
        assertEquals("s4", plan[Slide.Minutes])
        // Nothing left: the top song again
        assertEquals("s1", plan[Slide.Patterns])
    }

    @Test
    fun lookedUpSongsAreUsed() {
        val lookups = SoundtrackLookups(albumSongs = mapOf("a1" to listOf("a1-1")), genreSongs = listOf("g1"))
        val withGenre = wrapped.copy(topGenres = listOf(de.ronnyporsch.voidcast.data.jellyfin.WrappedGenreDto("Rock", 3)))
        val plan = planSoundtrack(withGenre, slidesOf(withGenre), lookups)
        assertEquals("a1-1", plan[Slide.TopAlbums])
        assertEquals("g1", plan[Slide.TopGenres])
    }

    @Test
    fun slidesWithoutSongsAreLeftOut() {
        val gone = WrappedDto(playCount = 1, topSongs = listOf(song(null)))
        val plan = planSoundtrack(gone, slidesOf(gone))
        assertFalse(plan.containsKey(Slide.Intro))
        assertEquals(emptyMap(), plan)
    }
}
