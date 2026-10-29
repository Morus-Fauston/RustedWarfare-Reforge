package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

/** Module representation checks, not a player-visible thirtieth order or a gameplay trace. */
class UnitTaskQueueStateTest {
    @Test fun full_append_validates_the_hidden_slot_and_pop_moves_it_to_the_next_append_position() {
        var original = UnitTaskQueue()
        repeat(29) { original = original.append(point("active-$it")) }
        val snapshot = original.tasks
        val invalid = UnitTask(UnitTaskKind.MOVE, "invalid-spare", Position(Double.NaN, 100.0))
        val full = original.append(invalid)
        assertEquals(29, full.size)
        assertEquals(snapshot, full.tasks)
        assertFailsWith<IllegalArgumentException> { full.validateCandidate() }
        val shifted = full.pop()
        assertEquals(28, shifted.size)
        assertEquals(snapshot.drop(1), shifted.tasks)
        assertFailsWith<IllegalArgumentException> { shifted.validateCandidate() }
        val replacement = point("replacement")
        val overwritten = shifted.append(replacement)
        assertEquals(29, overwritten.size)
        assertEquals(snapshot.drop(1) + replacement, overwritten.tasks)
        overwritten.validateCandidate()
        original.validateCandidate()
        assertEquals(snapshot, original.tasks, "Immutable prior queue retains no spare write")
        assertFailsWith<IllegalArgumentException> { full.validateCandidate() }
    }

    @Test fun a_valid_capacity_write_stays_inactive_when_the_head_is_popped() {
        var queue = UnitTaskQueue()
        repeat(29) { queue = queue.append(point("active-$it")) }
        val spare = point("spare")
        val full = queue.append(spare)
        full.validateCandidate()
        assertEquals(queue.tasks, full.tasks)
        val popped = full.pop()
        popped.validateCandidate()
        assertEquals(28, popped.size)
        assertEquals(queue.tasks.drop(1), popped.tasks)
        assertFalse(spare in popped.tasks)
        assertEquals(29, queue.size)
    }

    private fun point(id: String) = UnitTask(UnitTaskKind.PATROL, id, Position(150.0, 170.0))
}
