package de.ronnyporsch.voidcast.domain.model

/** What the songs of the queue got started from, e.g. to go back to it. */
sealed interface PlaybackSource {
    data class Playlist(val id: String) : PlaybackSource
    data class Album(val id: String) : PlaybackSource
    /** [id] is null for artists only known by name. */
    data class Artist(val id: String?, val name: String) : PlaybackSource
}
