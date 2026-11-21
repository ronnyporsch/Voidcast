package de.ronnyporsch.voidcast.ui.screen.wrapped

import de.ronnyporsch.voidcast.data.jellyfin.WrappedDto
import de.ronnyporsch.voidcast.platform.AudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Library songs looked up for the slides the top songs have none for, as song ids: the songs of artists by name, of
 * albums by id, and of the top genre (those played in the period first).
 */
data class SoundtrackLookups(
    val artistSongs: Map<String, List<String>> = emptyMap(),
    val albumSongs: Map<String, List<String>> = emptyMap(),
    val genreSongs: List<String> = emptyList(),
)

/** The slides whose song is about something of theirs, in the order they get to pick; the others get what's left. */
private val THEMED_SLIDES = listOf(
    Slide.TopSongs, Slide.OnRepeat, Slide.MostSkipped, Slide.TopArtist, Slide.TopAlbums, Slide.Discoveries, Slide.TopGenres,
)

/**
 * The id of the library song to play on each of [slides]: the slide's own song where it has one (the top song, the
 * song on repeat, the top artist's most played song, …), one of the top songs otherwise. Each slide gets a song no
 * other slide has if there is one, except the summary, which plays the top song again. Slides without any song
 * (e.g. none of the songs is still in the library) are left out.
 */
internal fun planSoundtrack(wrapped: WrappedDto, slides: List<Slide>, lookups: SoundtrackLookups = SoundtrackLookups()): Map<Slide, String> {
    val topSongIds = wrapped.topSongs.mapNotNull { it.id }
    fun songsBy(artist: String?): List<String> {
        if (artist == null) return emptyList()
        val played = wrapped.topSongs.filter { it.id != null && artist.lowercase() in it.artist.lowercase() }.mapNotNull { it.id }
        return played + lookups.artistSongs[artist].orEmpty()
    }

    fun candidates(slide: Slide): List<String> = when (slide) {
        Slide.TopSongs, Slide.Summary -> topSongIds.take(1)
        Slide.OnRepeat -> listOfNotNull(wrapped.onRepeat?.song?.id)
        Slide.MostSkipped -> listOfNotNull(wrapped.mostSkipped?.song?.id)
        Slide.TopArtist -> songsBy(wrapped.topArtists.firstOrNull()?.name)
        Slide.Discoveries -> songsBy(wrapped.topNewArtist?.name)
        Slide.TopAlbums -> wrapped.topAlbums.firstOrNull()?.let { album ->
            wrapped.topSongs.filter { it.albumId == album.id }.mapNotNull { it.id } + lookups.albumSongs[album.id].orEmpty()
        }.orEmpty()
        Slide.TopGenres -> lookups.genreSongs
        Slide.Intro, Slide.Minutes, Slide.Patterns, Slide.Days -> topSongIds
    }

    val used = mutableSetOf<String>()
    val plan = mutableMapOf<Slide, String>()
    val order = THEMED_SLIDES.filter { it in slides } + slides.filter { it !in THEMED_SLIDES }
    for (slide in order) {
        val options = candidates(slide)
        val song = options.firstOrNull { it !in used } ?: options.firstOrNull() ?: continue
        used += song
        plan[slide] = song
    }
    return plan
}

/** Whether the top songs have a song by [artist], so its songs needn't be looked up. */
internal fun WrappedDto.hasTopSongBy(artist: String): Boolean =
    topSongs.any { it.id != null && artist.lowercase() in it.artist.lowercase() }

/** Whether the top songs have a song of [albumId]. */
internal fun WrappedDto.hasTopSongOf(albumId: String): Boolean = topSongs.any { it.id != null && it.albumId == albumId }

/**
 * Plays a part of a song for each Wrapped slide, on players of its own so the queue stays as it is: from a third
 * into the song, where the chorus often is. Two players take turns so that songs crossfade without a gap: the next
 * song loads silently while the last one keeps playing.
 */
class WrappedSoundtrack(
    playerA: AudioPlayer,
    playerB: AudioPlayer,
    private val scope: CoroutineScope,
    private val streamUrl: (songId: String) -> String,
) {
    private val players = listOf(playerA, playerB)
    private val volumes = FloatArray(2)
    private var job: Job? = null
    private var currentSongId: String? = null

    /** Plays [songId], or fades out if null; the song already playing keeps playing. */
    fun play(songId: String?) {
        if (songId == currentSongId) return
        currentSongId = songId
        val previous = job
        job = scope.launch {
            previous?.cancel()
            // The louder one is what's heard, also if the last switch was cut short
            val current = if (volumes[0] >= volumes[1]) 0 else 1
            val next = 1 - current
            silence(next)
            if (songId == null) {
                fade(current, to = 0f, FADE_OUT_MS)
                players[current].stop()
                return@launch
            }
            if (!start(next, songId)) {
                // Couldn't be played: better what was playing than nothing
                players[next].stop()
                return@launch
            }
            crossfade(from = current, to = next)
            players[current].stop()
        }
    }

    /** Fades out and stops, e.g. while something else plays. */
    fun stop() = play(null)

    fun release() {
        job?.cancel()
        players.forEach { it.release() }
    }

    private fun silence(index: Int) {
        volumes[index] = 0f
        players[index].setVolume(0f)
    }

    /** Starts [songId] on the (silent) player [index] at the part to be heard; false if it doesn't start. */
    private suspend fun start(index: Int, songId: String): Boolean {
        val player = players[index]
        player.stop()
        player.play(streamUrl(songId))
        // The state may still be the last song's for a moment, which was further in than this one just started
        val duration = withTimeoutOrNull(START_TIMEOUT_MS) {
            player.state.first { it.isPlaying && it.durationMs > 0 && it.positionMs < JUST_STARTED_MS }.durationMs
        } ?: return false
        if (duration > MIN_SEEK_DURATION_MS) {
            val target = duration / 3
            player.seekTo(target)
            // Fading in while it buffers after the seek would leave a dip
            withTimeoutOrNull(START_TIMEOUT_MS) {
                player.state.first { it.isPlaying && !it.isBuffering && it.positionMs >= target - SEEK_TOLERANCE_MS }
            }
        }
        return true
    }

    private suspend fun crossfade(from: Int, to: Int) {
        val fromStart = volumes[from]
        val toStart = volumes[to]
        val steps = (CROSSFADE_MS / FADE_STEP_MS).toInt()
        for (step in 1..steps) {
            val progress = step.toFloat() / steps
            volumes[from] = fromStart * (1 - progress)
            volumes[to] = toStart + (1 - toStart) * progress
            players[from].setVolume(volumes[from])
            players[to].setVolume(volumes[to])
            delay(FADE_STEP_MS)
        }
    }

    private suspend fun fade(index: Int, to: Float, durationMs: Long) {
        val start = volumes[index]
        if (start == to) return
        val steps = (durationMs / FADE_STEP_MS).toInt().coerceAtLeast(1)
        for (step in 1..steps) {
            volumes[index] = start + (to - start) * step / steps
            players[index].setVolume(volumes[index])
            delay(FADE_STEP_MS)
        }
    }

    private companion object {
        const val CROSSFADE_MS = 1500L
        const val FADE_OUT_MS = 600L
        const val FADE_STEP_MS = 40L
        const val START_TIMEOUT_MS = 8000L
        const val JUST_STARTED_MS = 5000L
        const val SEEK_TOLERANCE_MS = 1000L
        /** Shorter songs play from the start. */
        const val MIN_SEEK_DURATION_MS = 60_000L
    }
}
