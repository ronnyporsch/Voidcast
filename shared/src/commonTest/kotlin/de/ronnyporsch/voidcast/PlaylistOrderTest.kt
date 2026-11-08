package de.ronnyporsch.voidcast

import de.ronnyporsch.voidcast.data.repository.movedTo
import de.ronnyporsch.voidcast.data.repository.singleMovesTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistOrderTest {
    private val list = listOf("A", "B", "C", "D", "E")

    @Test
    fun movesOneEntry() {
        assertEquals(listOf("B", "C", "A", "D", "E"), list.movedTo(listOf(0), 2))
        assertEquals(listOf("E", "A", "B", "C", "D"), list.movedTo(listOf(4), 0))
        assertEquals(listOf("B", "C", "D", "E", "A"), list.movedTo(listOf(0), 4))
    }

    @Test
    fun movesSeveralEntriesTogether() {
        assertEquals(listOf("B", "D", "A", "C", "E"), list.movedTo(listOf(2, 0), 2))
        assertEquals(listOf("A", "C", "E", "B", "D"), list.movedTo(listOf(1, 3), 3))
    }

    /** Applying the single moves gives the same order, for every selection and target. */
    @Test
    fun singleMovesMatchMovedTo() {
        val entries = listOf("A", "B", "A", "C", "D", "E")
        for (mask in 1 until (1 shl entries.size)) {
            val indices = entries.indices.filter { mask and (1 shl it) != 0 }
            for (to in 0..entries.size - indices.size) {
                val list = entries.toMutableList()
                val moves = entries.singleMovesTo(indices, to)
                assertTrue(moves.size <= indices.size)
                for (move in moves) list.add(move.to, list.removeAt(move.from))
                assertEquals(entries.movedTo(indices, to), list, "indices $indices to $to")
            }
        }
    }
}
