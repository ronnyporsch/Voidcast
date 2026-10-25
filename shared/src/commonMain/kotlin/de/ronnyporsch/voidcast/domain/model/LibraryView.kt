package de.ronnyporsch.voidcast.domain.model

import kotlinx.serialization.Serializable

/** How the library's sidebar orders its items. Release date and date added put the newest first unless reversed. */
@Serializable
enum class LibrarySort(val title: String) {
    Alphabetical("Alphabetical"),
    ReleaseDate("Release date"),
    DateAdded("Date added"),
}

/** What the library's sidebar lists, and the orders that make sense for it. */
@Serializable
enum class LibraryCategory(val title: String, val sorts: List<LibrarySort>) {
    Playlists("Playlists", listOf(LibrarySort.Alphabetical, LibrarySort.DateAdded)),
    Artists("Artists", listOf(LibrarySort.Alphabetical, LibrarySort.DateAdded)),
    Albums("Albums", LibrarySort.entries),
    Songs("Songs", LibrarySort.entries),
    Genres("Genres", listOf(LibrarySort.Alphabetical)),
    Favorites("Favorites", LibrarySort.entries),
}

/** A [sort], [reversed] from its usual direction (A to Z, newest first). */
@Serializable
data class LibraryOrder(
    val sort: LibrarySort = LibrarySort.Alphabetical,
    val reversed: Boolean = false,
) {
    /** A to Z, oldest first. */
    val isAscending: Boolean get() = (sort == LibrarySort.Alphabetical) != reversed

    val description: String
        get() = sort.title + when (sort) {
            LibrarySort.Alphabetical -> if (reversed) ", Z to A" else ", A to Z"
            else -> if (reversed) ", oldest first" else ", newest first"
        }
}

/** What the library's sidebar showed last, kept across restarts. */
@Serializable
data class LibraryViewSettings(
    val category: LibraryCategory = LibraryCategory.Playlists,
    /** Categories missing here are sorted A to Z. */
    val orders: Map<LibraryCategory, LibraryOrder> = emptyMap(),
) {
    fun orderOf(category: LibraryCategory): LibraryOrder = orders[category] ?: LibraryOrder()
}
