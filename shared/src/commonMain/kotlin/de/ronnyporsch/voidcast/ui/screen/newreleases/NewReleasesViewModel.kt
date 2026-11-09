package de.ronnyporsch.voidcast.ui.screen.newreleases

import androidx.lifecycle.viewModelScope
import de.ronnyporsch.voidcast.data.jellyfin.NewReleasesStatusDto
import de.ronnyporsch.voidcast.data.youtube.YouTubeService
import de.ronnyporsch.voidcast.mvi.MviStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shows what the server's scans for new releases found. Lives as long as the app. */
class NewReleasesViewModel(private val youTube: YouTubeService) : MviStore<NewReleasesState, NewReleasesEvent, Nothing>(NewReleasesState()) {

    private var job: Job? = null
    /** Whether the screen got opened, so a new connection needs to load again. */
    private var opened = false

    override fun onEvent(event: NewReleasesEvent) {
        when (event) {
            NewReleasesEvent.Load -> {
                opened = true
                if (job?.isActive != true) start { load() }
            }
            NewReleasesEvent.Scan -> if (!currentState.isScanning) start {
                setState { copy(isScanning = true, checkedArtists = 0, artistsToCheck = null, error = null) }
                youTube.startNewReleasesScan()
                followScan()
            }
            NewReleasesEvent.Connected -> if (opened) {
                setState { NewReleasesState() }
                start { load() }
            }
        }
    }

    private fun start(block: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { copy(isLoading = false, isScanning = false, error = e.message) }
            }
        }
    }

    private suspend fun load() {
        setState { copy(isLoading = scan == null) }
        val status = showLatest()
        if (status.isScanning) followScan()
    }

    private suspend fun showLatest(): NewReleasesStatusDto {
        val latest = youTube.getNewReleases()
        setState { copy(isLoading = false, scan = latest.scan) }
        show(latest.status)
        return latest.status
    }

    private suspend fun followScan() {
        var failures = 0
        while (true) {
            delay(POLL_INTERVAL_MS)
            val status = try {
                youTube.getNewReleasesStatus().also { failures = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (++failures >= MAX_POLL_FAILURES) throw e
                continue
            }
            if (!status.isScanning) break
            show(status)
        }
        showLatest()
    }

    private fun show(status: NewReleasesStatusDto) = setState {
        copy(isScanning = status.isScanning, checkedArtists = status.checkedArtists, artistsToCheck = status.artistCount, error = status.error)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2000L
        const val MAX_POLL_FAILURES = 5
    }
}
