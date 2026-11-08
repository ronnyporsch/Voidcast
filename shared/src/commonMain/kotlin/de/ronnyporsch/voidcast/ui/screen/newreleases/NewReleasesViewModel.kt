package de.ronnyporsch.voidcast.ui.screen.newreleases

import androidx.lifecycle.viewModelScope
import de.ronnyporsch.voidcast.data.repository.MusicRepository
import de.ronnyporsch.voidcast.data.youtube.NewReleasesCache
import de.ronnyporsch.voidcast.data.youtube.NewReleasesCriteria
import de.ronnyporsch.voidcast.data.youtube.NewReleasesScan
import de.ronnyporsch.voidcast.data.youtube.OwnedArtist
import de.ronnyporsch.voidcast.data.youtube.YouTubeService
import de.ronnyporsch.voidcast.data.youtube.artistsNamed
import de.ronnyporsch.voidcast.data.youtube.frequentArtists
import de.ronnyporsch.voidcast.data.youtube.inNewReleasesOrder
import de.ronnyporsch.voidcast.data.youtube.notOwnedBy
import de.ronnyporsch.voidcast.domain.model.Album
import de.ronnyporsch.voidcast.domain.model.AppSettings
import de.ronnyporsch.voidcast.mvi.MviStore
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Lives as long as the app, so a search keeps running while the user looks at what it found so far. */
@OptIn(ExperimentalTime::class)
class NewReleasesViewModel(
    private val repository: MusicRepository,
    private val youTube: YouTubeService,
    private val settings: () -> AppSettings,
    private val cache: NewReleasesCache,
) : MviStore<NewReleasesState, NewReleasesEvent, Nothing>(NewReleasesState()) {

    private var job: Job? = null
    private var opening: Job? = null
    /** The server of what the state shows. */
    private var server: String? = null

    override fun onEvent(event: NewReleasesEvent) {
        when (event) {
            NewReleasesEvent.Open -> open()
            NewReleasesEvent.Refresh -> if (job?.isActive != true) job = viewModelScope.launch { scan() }
            NewReleasesEvent.Connected -> if (server != null && server != settings().serverKey) {
                opening?.cancel()
                job?.cancel()
                server = null
                setState { NewReleasesState() }
            }
        }
    }

    private fun open() {
        if (opening?.isActive == true) return
        opening = viewModelScope.launch {
            val wanted = criteria().getOrElse { return@launch showError(it) }
            val shown = currentState
            if (shown.criteria == wanted && (job?.isActive == true || shown.scannedAt?.let(::isFresh) == true)) return@launch
            job?.cancel()
            job = viewModelScope.launch {
                server = settings().serverKey
                val stored = cache.load()?.takeIf { it.criteria == wanted }
                if (stored != null) {
                    setState {
                        copy(artistCount = stored.artistCount, criteria = wanted, releases = stored.releases, scannedAt = stored.scannedAt)
                    }
                }
                if (stored == null || !isFresh(stored.scannedAt)) scan()
            }
        }
    }

    private suspend fun criteria(): Result<NewReleasesCriteria> {
        val current = settings()
        if (!current.newReleasesFavoritesOnly) return Result.success(NewReleasesCriteria(current.newReleasesMinSongs))
        // Not the app's list of favorites, which is still loading right after connecting
        return repository.getFavorites().map { favorites ->
            NewReleasesCriteria(favoriteArtists = favorites.artists.mapTo(mutableSetOf()) { it.name })
        }
    }

    private fun showError(error: Throwable) {
        if (error is CancellationException) throw error
        setState { copy(isScanning = false, error = error.message) }
    }

    /** Looks for new releases; what is shown stays until the search is done, if it's for the same artists. */
    private suspend fun scan() {
        server = settings().serverKey
        setState { copy(isScanning = true, checkedArtists = 0, failedArtists = 0, error = null) }
        val criteria = criteria().getOrElse { return showError(it) }
        val showWhileScanning = currentState.criteria != criteria || currentState.releases.isEmpty()
        if (showWhileScanning) setState { copy(artistCount = null, releases = emptyList()) }
        setState { copy(criteria = criteria) }
        val songs = repository.getAllSongs().getOrElse { return showError(it) }
        val artists = criteria.favoriteArtists?.let(songs::artistsNamed) ?: songs.frequentArtists(criteria.minSongs)
        setState { copy(artistCount = artists.size) }

        val sinceYear = currentYear() - 1
        val semaphore = Semaphore(MAX_ARTISTS_AT_ONCE)
        var found = emptyList<Album>()
        coroutineScope {
            for (artist in artists) {
                launch {
                    semaphore.withPermit {
                        val releases = try {
                            newReleases(artist, sinceYear)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        if (releases != null) found = (found + releases).inNewReleasesOrder()
                        setState {
                            copy(
                                checkedArtists = checkedArtists + 1,
                                failedArtists = failedArtists + if (releases == null) 1 else 0,
                                releases = if (showWhileScanning) found else this.releases,
                            )
                        }
                    }
                }
            }
        }

        val failed = currentState.failedArtists
        if (artists.isNotEmpty() && failed == artists.size) {
            setState { copy(isScanning = false, error = "Couldn't reach YouTube Music") }
            return
        }
        val scan = NewReleasesScan(Clock.System.now().toEpochMilliseconds(), artists.size, criteria, found)
        setState { copy(isScanning = false, releases = found, scannedAt = scan.scannedAt) }
        // With many failures the next visit should try again
        if (failed <= artists.size / 10) cache.save(scan)
    }

    /** Empty if YouTube Music has no artist of exactly that name. */
    private suspend fun newReleases(artist: OwnedArtist, sinceYear: Int): List<Album> {
        val match = youTube.searchArtists(artist.name).firstOrNull { it.name.equals(artist.name, ignoreCase = true) }
            ?: return emptyList()
        return youTube.getArtistReleases(match.id).notOwnedBy(artist, sinceYear)
            .map { it.copy(releaseDate = youTube.getAlbumReleaseDate(it.id)) }
    }

    private fun isFresh(scannedAt: Long) = Clock.System.now().toEpochMilliseconds() - scannedAt < MAX_AGE_MS

    // Instants print as ISO 8601, starting with the (UTC) year
    private fun currentYear() = Clock.System.now().toString().take(4).toInt()

    private companion object {
        const val MAX_ARTISTS_AT_ONCE = 4
        const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}
