package com.rustedwarfare.reforge.client

import kotlin.test.*

class SelectionStateTest {
    @Test fun visibility_clears_current_selection_but_only_observed_death_removes_group_members() {
        var millis = 10_000L
        val selection = SelectionState { millis }
        selection.select(listOf("a", "b")); selection.saveGroup(9)
        selection.reconcile(setOf("a"), emptySet())
        assertEquals(setOf("a"), selection.selectedIds)
        assertEquals(setOf("a", "b"), selection.groupMembers(9))
        assertNull(selection.recallGroup(9, emptySet()))
        assertTrue(selection.selectedIds.isEmpty())
        millis = 10_200L
        assertEquals("b", selection.recallGroup(9, setOf("a", "b")))
        selection.reconcile(setOf("a"), setOf("b"))
        assertEquals(setOf("a"), selection.groupMembers(9))
        selection.clear()
        assertTrue(selection.selectedIds.isEmpty())
        repeat(10) { assertTrue(selection.groupMembers(it).isEmpty()) }
        selection.select(listOf("a")); selection.saveGroup(9)
        millis = 10_300L
        assertNull(selection.recallGroup(9, setOf("a")), "close resets the invocation history too")
    }

    @Test fun exact_time_boundaries_and_ctrl_replacement_use_each_groups_last_actual_recall() {
        for (gap in listOf(699L, 700L)) {
            var millis = 10_000L
            val state = SelectionState { millis }
            state.select(listOf("a", "b")); state.saveGroup(0)
            assertNull(state.recallGroup(0, setOf("a", "b")))
            millis += gap
            assertEquals(if (gap == 699L) "b" else null, state.recallGroup(0, setOf("a", "b")))
        }
        var millis = 10_000L
        val state = SelectionState { millis }
        state.select(listOf("a")); state.saveGroup(0)
        assertNull(state.recallGroup(0, setOf("a")))
        millis = 10_100L
        state.select(listOf("d")); state.saveGroup(0)
        millis = 10_200L
        assertEquals("d", state.recallGroup(0, setOf("d")))
        state.select(emptyList()); state.saveGroup(0)
        assertNull(state.recallGroup(0, setOf("d")))
        assertTrue(state.selectedIds.isEmpty())
    }

    @Test fun ten_groups_are_independent_and_return_immutable_snapshots() {
        val state = SelectionState()
        repeat(10) { state.select(listOf("unit-$it")); state.saveGroup(it) }
        repeat(10) { assertEquals(setOf("unit-$it"), state.groupMembers(it)) }
        val old = state.groupMembers(0)
        assertFailsWith<UnsupportedOperationException> { (old as MutableSet).clear() }
        assertFailsWith<UnsupportedOperationException> { (state.selectedIds as MutableSet).clear() }
        state.select(listOf("replacement")); state.saveGroup(0)
        assertEquals(setOf("unit-0"), old)
        assertFailsWith<IllegalArgumentException> { state.saveGroup(-1) }
        assertFailsWith<IllegalArgumentException> { state.recallGroup(10, emptySet()) }
    }

    @Test fun returning_to_a_group_after_another_recall_keeps_its_own_time() {
        for (additive in listOf(false, true)) {
        var millis = 10_000L
        val selection = SelectionState { millis }
        selection.select(listOf("a", "b")); selection.saveGroup(0)
        selection.select(listOf("c")); selection.saveGroup(1)
        val eligible = setOf("a", "b", "c")
        assertNull(selection.recallGroup(0, eligible))
        assertEquals(listOf("a", "b"), selection.selectedIds.toList())
        millis = 10_100L
        assertNull(selection.recallGroup(1, eligible))
        assertEquals(setOf("c"), selection.selectedIds)
        millis = 10_200L
        assertEquals("b", selection.recallGroup(0, eligible, additive = additive))
        assertEquals(if (additive) listOf("c", "a", "b") else listOf("a", "b"), selection.selectedIds.toList())
        assertEquals(listOf("a", "b"), selection.groupMembers(0).toList())
        }
    }

    @Test fun replacing_and_adding_selection_keeps_order_and_old_snapshots() {
        val selection = SelectionState()
        selection.select(listOf("a", "b", "a"))
        val before = selection.selectedIds
        assertEquals(listOf("a", "b"), before.toList())
        assertEquals("b", selection.primaryId)
        selection.select(listOf("b", "c"), SelectionMode.ADD)
        assertEquals(listOf("a", "b", "c"), selection.selectedIds.toList())
        assertEquals("c", selection.primaryId)
        assertEquals(listOf("a", "b"), before.toList())
        selection.select(listOf("b", "c", "d"), SelectionMode.TOGGLE)
        assertEquals(listOf("a", "d"), selection.selectedIds.toList())
        assertEquals("d", selection.primaryId)
        selection.select(emptyList())
        assertTrue(selection.selectedIds.isEmpty())
        assertNull(selection.primaryId)
    }
}
