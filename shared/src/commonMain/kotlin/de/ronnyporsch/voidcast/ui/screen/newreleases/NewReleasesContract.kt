package de.ronnyporsch.voidcast.ui.screen.newreleases

import de.ronnyporsch.voidcast.data.youtube.NewReleasesCriteria
import de.ronnyporsch.voidcast.domain.model.Album

data class NewReleasesState(
    val isScanning: Boolean = false,
    /** Those of the shown or running search. */
    val criteria: NewReleasesCriteria = NewReleasesCriteria(),
    /** How many artists get looked at; null until that is known. */
    val artistCount: Int? = null,
    val checkedArtists: Int = 0,
    /** How many artists couldn't be looked up in the latest scan. */
    val failedArtists: Int = 0,
    /** Newest first. */
    val releases: List<Album> = emptyList(),
    /** When the shown [releases] got found, in milliseconds since the epoch; null if no scan finished yet. */
    val scannedAt: Long? = null,
    val error: String? = null,
)

sealed interface NewReleasesEvent {
    /** The screen got opened: shows the stored releases, searching again if they are outdated or for other artists. */
    data object Open : NewReleasesEvent
    /** Searches again, unless a search is running. */
    data object Refresh : NewReleasesEvent
    /** A server got connected; what was found on another one is dropped. */
    data object Connected : NewReleasesEvent
}
