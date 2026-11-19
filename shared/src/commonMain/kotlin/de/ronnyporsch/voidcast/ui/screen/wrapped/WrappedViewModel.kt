package de.ronnyporsch.voidcast.ui.screen.wrapped

import androidx.lifecycle.viewModelScope
import de.ronnyporsch.voidcast.data.jellyfin.JellyfinApiService
import de.ronnyporsch.voidcast.mvi.MviStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** The user's listening stats of a period ("Wrapped"), from the Jellyfin server's Playback Reporting history. */
class WrappedViewModel(
    private val api: JellyfinApiService,
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
        setState { copy(period = period, today = today, isLoading = true, wrapped = null, error = null) }
        job = viewModelScope.launch {
            try {
                val (from, to) = period.bounds(today, timeZone)
                val offsetMinutes = timeZone.offsetAt(now).totalSeconds / 60
                val wrapped = api.getWrapped(from, to, offsetMinutes)
                setState { copy(isLoading = false, wrapped = wrapped) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { copy(isLoading = false, error = e.message ?: "Couldn't load your stats") }
            }
        }
    }
}
