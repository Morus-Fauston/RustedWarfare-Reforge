package com.rustedwarfare.reforge.client

import kotlin.test.*

/** Regressions for the inherited state component; these are not claimed as new TDD red cases. */
class SelectionStateTest {
    @Test fun ordered_unique_primary_snapshots_and_observed_death_are_session_local() {
        var now = 10000L
        val state = SelectionState { now }
        state.select(listOf("a", "b", "a")); state.saveGroup(0)
        val old = state.selectedIds
        assertEquals(listOf("a", "b"), old.toList()); assertEquals("b", state.primaryId)
        state.select(listOf("a"), SelectionMode.TOGGLE)
        assertEquals(setOf("b"), state.selectedIds); assertEquals(listOf("a", "b"), old.toList())
        assertFailsWith<UnsupportedOperationException> { (old as MutableSet).clear() }
        state.reconcile(emptySet(), emptySet())
        assertTrue(state.selectedIds.isEmpty()); assertEquals(setOf("a", "b"), state.groupMembers(0))
        assertNull(state.recallGroup(0, emptySet()))
        now = 10200; assertEquals("b", state.recallGroup(0, setOf("a", "b")))
        state.reconcile(setOf("a"), setOf("b"))
        assertEquals(setOf("a"), state.groupMembers(0)); assertEquals("a", state.primaryId)
        state.clear(); assertEquals(0, state.groupMembers(0).size)
        state.select(listOf("a")); state.saveGroup(0)
        assertNull(state.recallGroup(0, setOf("a")), "clear also removes the previous recall clock")
    }
}
