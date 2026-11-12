package de.ronnyporsch.voidcast

import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.domain.model.gainToAmplitude
import de.ronnyporsch.voidcast.domain.model.normalizationGain
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NormalizationTest {
    private fun song(album: String, track: Int?, trackGain: Float? = -8f, albumGain: Float? = -5f, disc: Int? = null) =
        Song(id = "$album-$disc-$track", title = "", albumId = album, track = track, discNumber = disc, trackGain = trackGain, albumGain = albumGain)

    @Test
    fun albumPlayedInOrderUsesAlbumGain() {
        val queue = listOf(song("a", 1), song("a", 2), song("a", 3))
        for (index in queue.indices) assertEquals(-5f, normalizationGain(queue, index))
    }

    @Test
    fun albumContinuesOnNextDisc() {
        val queue = listOf(song("a", 9, disc = 1), song("a", 1, disc = 2))
        assertEquals(-5f, normalizationGain(queue, 0))
        assertEquals(-5f, normalizationGain(queue, 1))
    }

    @Test
    fun mixedSongsUseTheirOwnGain() {
        // Other albums, the same album out of order, and songs without an album or track number
        val queue = listOf(song("a", 1), song("b", 2), song("b", 5), song("b", 4), song("", 1), song("", 2), song("c", null), song("c", null))
        for (index in queue.indices) assertEquals(-8f, normalizationGain(queue, index), "index $index")
    }

    @Test
    fun albumWithoutGainFallsBackToTheSongs() {
        val queue = listOf(song("a", 1, albumGain = null), song("a", 2, albumGain = null))
        assertEquals(-8f, normalizationGain(queue, 0))
    }

    @Test
    fun unknownOrPositiveGainLeavesTheSongAsItIs() {
        assertEquals(0f, normalizationGain(listOf(song("a", 1, trackGain = null)), 0))
        assertEquals(0f, normalizationGain(listOf(song("a", 1, trackGain = 4f)), 0))
        assertEquals(0f, normalizationGain(emptyList(), 0))
    }

    @Test
    fun gainBecomesAmplitude() {
        assertEquals(1f, gainToAmplitude(0f))
        assertEquals(1f, gainToAmplitude(6f))
        assertTrue(abs(gainToAmplitude(-20f) - 0.1f) < 1e-6f)
    }
}
