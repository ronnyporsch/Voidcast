package de.ronnyporsch.voidcast.ui.screen.newreleases

import de.ronnyporsch.voidcast.data.youtube.NewReleasesScan

data class NewReleasesState(
    val isLoading: Boolean = false,
    /** Whether the server is scanning; what the latest scan found stays shown meanwhile. */
    val isScanning: Boolean = false,
    val checkedArtists: Int = 0,
    /** How many artists the running scan looks at; null until that is known. */
    val artistsToCheck: Int? = null,
    /** The latest finished scan; null if there is none. */
    val scan: NewReleasesScan? = null,
    val error: String? = null,
)

sealed interface NewReleasesEvent {
    /** Shows what the server's latest scan found, and follows the scan if one is running. */
    data object Load : NewReleasesEvent
    /** Has the server scan now, unless it already does. */
    data object Scan : NewReleasesEvent
    /** A server got connected; what was found on another one is dropped. */
    data object Connected : NewReleasesEvent
}
