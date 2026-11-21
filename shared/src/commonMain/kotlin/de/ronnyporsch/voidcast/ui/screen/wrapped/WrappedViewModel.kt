package de.ronnyporsch.voidcast.ui.screen.wrapped

import androidx.lifecycle.viewModelScope
import de.ronnyporsch.voidcast.data.jellyfin.JellyfinApiService
import de.ronnyporsch.voidcast.data.jellyfin.WrappedDto
import de.ronnyporsch.voidcast.data.repository.MusicRepository
import de.ronnyporsch.voidcast.mvi.MviStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** The user's listening stats of a period ("Wrapped"), from the Jellyfin server's Playback Reporting history. */
class WrappedViewModel(
    private val api: JellyfinApiService,
    /** For the songs the soundtrack plays beyond the top songs. */
    private val repository: MusicRepository,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : MviStore<WrappedState, WrappedEvent, Nothing>(WrappedState(today = Clock.System.todayIn(timeZone))) {

    private var job: Job? = null

    init {
        load(currentState.period)
    }

    override fun onEvent(event: WrappedEvent) {
        when (event) {
            is WrappedEvent.PresetSelected -> if (event.preset != WrappedPreset.Custom) load(WrappedPeriod(event.preset))
            is WrappedEvent.CustomSelected -> load(WrappedPeriod(WrappedPreset.Custom, event.from, event.to))
            WrappedEvent.Retry -> load(currentState.period)
        }
    }

    private fun load(period: WrappedPeriod) {
        job?.cancel()
        val now = Clock.System.now()
        val today = Clock.System.todayIn(timeZone)
        setState { copy(period = period, today = today, isLoading = true, wrapped = null, error = null, soundtrackLookups = SoundtrackLookups()) }
        job = viewModelScope.launch {
            try {
                val (from, to) = period.bounds(today, timeZone)
                val offsetMinutes = timeZone.offsetAt(now).totalSeconds / 60
                val wrapped = api.getWrapped(from, to, offsetMinutes)
                setState { copy(isLoading = false, wrapped = wrapped) }
                lookUpSoundtrack(wrapped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { copy(isLoading = false, error = e.message ?: "Couldn't load your stats") }
            }
        }
    }

    /**
     * Looks up songs for the slides whose songs aren't among the top songs: of the top artist, the top album, the top
     * discovery and the top genre. Each is optional, the soundtrack plays a top song instead.
     */
    private suspend fun lookUpSoundtrack(wrapped: WrappedDto) = coroutineScope {
        fun add(reducer: SoundtrackLookups.() -> SoundtrackLookups) =
            setState { copy(soundtrackLookups = soundtrackLookups.reducer()) }

        val artists = listOfNotNull(wrapped.topArtists.firstOrNull()?.name, wrapped.topNewArtist?.name)
            .distinct()
            .filterNot { wrapped.hasTopSongBy(it) }
        for (artist in artists) launch {
            repository.findSongsByArtistName(artist).onSuccess { songs ->
                add { copy(artistSongs = artistSongs + (artist to songs.map { it.id })) }
            }
        }
        wrapped.topAlbums.firstOrNull()?.id?.takeUnless { wrapped.hasTopSongOf(it) }?.let { albumId ->
            launch {
                repository.getAlbum(albumId).onSuccess { album ->
                    add { copy(albumSongs = albumSongs + (albumId to album.songs.map { it.id })) }
                }
            }
        }
        wrapped.topGenres.firstOrNull()?.name?.let { name ->
            launch {
                val genre = repository.getGenres().getOrNull()?.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return@launch
                val songs = repository.getGenreSongs(genre).getOrNull()?.map { it.id } ?: return@launch
                val inGenre = songs.toSet()
                val played = wrapped.topSongs.mapNotNull { it.id }.filter { it in inGenre }
                add { copy(genreSongs = played + songs.shuffled().take(1)) }
            }
        }
    }
}
