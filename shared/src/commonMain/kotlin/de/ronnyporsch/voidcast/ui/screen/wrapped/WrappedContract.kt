package de.ronnyporsch.voidcast.ui.screen.wrapped

import de.ronnyporsch.voidcast.data.jellyfin.WrappedDto
import kotlinx.datetime.LocalDate

data class WrappedState(
    val period: WrappedPeriod = WrappedPeriod(WrappedPreset.ThisYear),
    /** Today in the user's time zone, for the names of the periods. */
    val today: LocalDate,
    val isLoading: Boolean = false,
    /** The stats of [period]; null until they are loaded. */
    val wrapped: WrappedDto? = null,
    val error: String? = null,
)

sealed interface WrappedEvent {
    /** Loads the stats of a [WrappedPreset] other than [WrappedPreset.Custom]. */
    data class PresetSelected(val preset: WrappedPreset) : WrappedEvent
    /** Loads the stats of the days from [from] to [to], both included. */
    data class CustomSelected(val from: LocalDate, val to: LocalDate) : WrappedEvent
    data object Retry : WrappedEvent
}
