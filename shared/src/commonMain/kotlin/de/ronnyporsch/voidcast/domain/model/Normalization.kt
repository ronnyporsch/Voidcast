package de.ronnyporsch.voidcast.domain.model

import kotlin.math.pow

/**
 * By how many dB the song at [index] of [queue] is played louder (never, see below) or quieter, so that songs are
 * equally loud. A song played right before or after its neighbour on the album gets the album's gain, so the album
 * keeps its loud and quiet songs; every other song gets its own. 0 for songs the server knows no gain of.
 *
 * Songs aren't amplified, players can only turn them down: a song that would have to be gets played as it is.
 */
fun normalizationGain(queue: List<Song>, index: Int): Float {
    val song = queue.getOrNull(index) ?: return 0f
    val withinAlbum = queue.getOrNull(index - 1)?.isFollowedOnAlbumBy(song) == true ||
        queue.getOrNull(index + 1)?.let { song.isFollowedOnAlbumBy(it) } == true
    val gain = (if (withinAlbum) song.albumGain else null) ?: song.trackGain ?: return 0f
    return gain.coerceAtMost(0f)
}

/** Whether [next] is the track after this one on the same album. */
private fun Song.isFollowedOnAlbumBy(next: Song): Boolean {
    if (albumId.isBlank() || albumId != next.albumId) return false
    val track = track ?: return false
    val disc = discNumber ?: 1
    val nextDisc = next.discNumber ?: 1
    return (nextDisc == disc && next.track == track + 1) || (nextDisc == disc + 1 && next.track == 1)
}

/** The factor a gain in dB changes the amplitude by, e.g. 0.5 for -6 dB; at most 1. */
fun gainToAmplitude(gainDb: Float): Float = 10f.pow(gainDb.coerceAtMost(0f) / 20f)
