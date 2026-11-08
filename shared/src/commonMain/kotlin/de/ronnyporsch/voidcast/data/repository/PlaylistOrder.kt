package de.ronnyporsch.voidcast.data.repository

/**
 * The list with the entries at [indices] taken out and inserted, in their order, at [to]: their first position in the
 * result, counted in the list without them (see [MusicRepository.moveSongsInPlaylist]).
 */
internal fun <T> List<T>.movedTo(indices: Collection<Int>, to: Int): List<T> {
    val moving = indices.filter { it in this.indices }.distinct().sorted()
    val movingSet = moving.toSet()
    val rest = filterIndexed { index, _ -> index !in movingSet }
    val at = to.coerceIn(0, rest.size)
    return rest.subList(0, at) + moving.map { this[it] } + rest.subList(at, rest.size)
}

/** Moving one entry from position [from] to [to], its position after the move. */
internal data class SingleMove(val from: Int, val to: Int)

/**
 * Single moves (like Jellyfin's) that turn the list into [movedTo]: at most one per moved entry. Each one gets placed
 * right after the one before it, which doesn't disturb the entries already placed.
 */
internal fun <T> List<T>.singleMovesTo(indices: Collection<Int>, to: Int): List<SingleMove> {
    val moving = indices.filter { it in this.indices }.distinct().sorted()
    if (moving.isEmpty()) return emptyList()
    val movingSet = moving.toSet()
    // Positions in the simulated list, as entries can be in it more than once
    val list = this.indices.toMutableList()
    val rest = list.filter { it !in movingSet }
    val at = to.coerceIn(0, rest.size)
    var anchor: Int? = rest.getOrNull(at - 1)
    val moves = mutableListOf<SingleMove>()
    for (entry in moving) {
        val from = list.indexOf(entry)
        val anchorIndex = anchor?.let { list.indexOf(it) }
        val target = when {
            anchorIndex == null -> 0
            from > anchorIndex -> anchorIndex + 1
            else -> anchorIndex
        }
        if (from != target) {
            list.removeAt(from)
            list.add(target, entry)
            moves += SingleMove(from, target)
        }
        anchor = entry
    }
    return moves
}
