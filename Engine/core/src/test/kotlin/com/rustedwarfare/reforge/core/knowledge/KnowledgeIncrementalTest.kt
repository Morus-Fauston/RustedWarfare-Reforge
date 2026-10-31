package com.rustedwarfare.reforge.core.knowledge

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class KnowledgeIncrementalTest {
    private fun world(): WorldHandle {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(TestContentFactory.openScenario())).context
        return context.world.also { context.close() }
    }
    private fun store(world: WorldHandle, retention: TerrainRetention = TerrainRetention.MEMORY) =
        PlayerTerrainKnowledge.create(world, "p1", 5, 5,
            KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, false))

    @Test fun kc_stationary_projection_refreshes_terrain_and_advances_revision_even_without_changes() {
        val world = world(); val store = store(world)
        val firstInput = listOf(VisibleTerrainCell(1, 1, false, 7))
        store.accept(world, firstInput)
        val first = store.snapshot(world)
        store.accept(world, firstInput)
        assertEquals(2L, store.snapshot(world).revision)
        store.accept(world, listOf(VisibleTerrainCell(1, 1, true, 9)))
        val changed = store.snapshot(world)
        assertEquals(3L, changed.revision)
        assertEquals(CellKnowledge(true, true, 9), changed.cell(1, 1))
        assertEquals(CellKnowledge(true, false, 7), first.cell(1, 1))
        store.close()
        assertEquals(CellKnowledge(true, true, 9), changed.cell(1, 1))
    }

    @Test fun kc_lost_visibility_retains_or_forgets_both_facts_and_keeps_players_independent() {
        val world = world()
        for (retention in TerrainRetention.entries) {
            val one = store(world, retention)
            val two = PlayerTerrainKnowledge.create(world, "p2", 5, 5, one.rules)
            one.accept(world, listOf(VisibleTerrainCell(0, 0, true, 8)))
            val first = one.snapshot(world)
            one.accept(world, listOf(VisibleTerrainCell(4, 4, false, 3)))
            assertEquals(if (retention == TerrainRetention.MEMORY) CellKnowledge(false, true, 8)
                else CellKnowledge(false, null), one.snapshot(world).cell(0, 0))
            assertEquals(CellKnowledge(false, null), two.snapshot(world).cell(0, 0))
            assertEquals(CellKnowledge(true, true, 8), first.cell(0, 0))
            one.close(); two.close()
        }
    }

    @Test fun kc_rejected_projection_preserves_cached_snapshot_and_revision() {
        val world = world(); val store = store(world)
        store.accept(world, listOf(VisibleTerrainCell(0, 0, false, 4)))
        val before = store.snapshot(world)
        assertEquals("duplicate-cell", assertFailsWith<KnowledgeException> {
            store.accept(world, listOf(VisibleTerrainCell(0, 0, true, 9), VisibleTerrainCell(0, 0, false, 8)))
        }.code)
        assertSame(before, store.snapshot(world))
        assertEquals(CellKnowledge(true, false, 4), before.cell(0, 0))
        store.close()
    }

    @Test fun vc_overlapping_sources_sharing_and_team_changes_preserve_sorted_geometry() {
        val players = listOf(Player("p1", PlayerControl.LOCAL, "t"), Player("p2", PlayerControl.LOCAL, "t"),
            Player("p3", PlayerControl.LOCAL))
        val source = VisionSource("p1", Position(30.0, 15.0), 1)
        fun view(epoch: Long, sources: List<VisionSource>, sharing: Boolean = true, ps: List<Player> = players) =
            AutomaticVision.compute("w", epoch, 5, 5, 20, 10, ps, sources, sharing)
        val expected = listOf(VisionCell(1, 0), VisionCell(0, 1), VisionCell(1, 1), VisionCell(2, 1), VisionCell(1, 2))
        val first = view(0, listOf(source, source))
        assertEquals(expected, first.getValue("p1").basicCells)
        assertEquals(expected, first.getValue("p2").effectiveCells)
        assertTrue(first.getValue("p2").basicCells.isEmpty())
        assertTrue(first.getValue("p3").effectiveCells.isEmpty())
        assertEquals(expected, view(1, listOf(source)).getValue("p1").basicCells)
        assertTrue(view(2, emptyList()).getValue("p1").basicCells.isEmpty())
        assertTrue(view(3, listOf(source), false).getValue("p2").effectiveCells.isEmpty())
        assertTrue(view(4, listOf(source), ps = players.map { if (it.playerId == "p2") it.copy(teamId = "x") else it })
            .getValue("p2").effectiveCells.isEmpty())
        assertEquals(expected, first.getValue("p2").effectiveCells)
        assertFailsWith<UnsupportedOperationException> { (first.getValue("p1").basicCells as MutableList).clear() }
    }

    @Test fun vc_nonpositive_source_and_exact_circle_boundary_are_unchanged() {
        val players = listOf(Player("p1", PlayerControl.LOCAL))
        val sources = listOf(VisionSource("p1", Position(30.0, 30.0), 1),
            VisionSource("not-a-player", Position(0.0, 0.0), 0))
        val view = AutomaticVision.compute("w", 17, 5, 5, 20, 20, players, sources, false).getValue("p1")
        assertEquals(17L, view.epoch)
        assertTrue(view.contains(2, 1))
        assertFalse(view.contains(2, 2))
        assertFalse(view.contains(-1, 0))
        assertFalse(view.contains(5, 0))
    }

    @Test fun reuse_identical_cells_while_public_revision_keeps_advancing() {
        val world = world(); val store = store(world)
        val input = listOf(VisibleTerrainCell(0, 0, false, 1))
        store.accept(world, input); val before = store.snapshot(world)
        store.accept(world, input); val after = store.snapshot(world)
        assertEquals(before.revision + 1, after.revision)
        assertNotSame(before, after)
        assertSame(before.cells, after.cells)
        assertFailsWith<UnsupportedOperationException> { (after.cells as MutableList).clear() }
        store.close()
    }

    @Test fun reuse_untouched_cell_values_during_local_change() {
        val world = world(); val store = store(world)
        store.accept(world, listOf(VisibleTerrainCell(0, 0, false, 1), VisibleTerrainCell(4, 4, true, 2)))
        val before = store.snapshot(world)
        store.accept(world, listOf(VisibleTerrainCell(0, 0, true, 3), VisibleTerrainCell(4, 4, true, 2)))
        val after = store.snapshot(world)
        assertSame(before.cell(4, 4), after.cell(4, 4))
        assertSame(before.cell(2, 2), after.cell(2, 2))
        assertEquals(CellKnowledge(true, false, 1), before.cell(0, 0))
        assertEquals(CellKnowledge(true, true, 3), after.cell(0, 0))
        store.close()
    }

    @Test fun projected_delta_reports_only_actual_changes_and_keeps_the_source_unpublished() {
        val world = world(); val original = store(world)
        val before = original.snapshot(world)
        val first = original.projected(world, listOf(VisibleTerrainCell(1, 1, false, 7)))
        assertEquals(listOf(KnowledgeCellChange(6, CellKnowledge(false, null), CellKnowledge(true, false, 7))), first.changes)
        assertSame(before, original.snapshot(world))
        val repeated = first.store.projected(world, listOf(VisibleTerrainCell(1, 1, false, 7)))
        assertTrue(repeated.changes.isEmpty())
        assertEquals(2L, repeated.store.snapshot(world).revision)
        val terrain = repeated.store.projected(world, listOf(VisibleTerrainCell(1, 1, true, 9)))
        assertEquals(listOf(KnowledgeCellChange(6, CellKnowledge(true, false, 7), CellKnowledge(true, true, 9))), terrain.changes)
        val hidden = terrain.store.projected(world, emptyList())
        assertEquals(listOf(KnowledgeCellChange(6, CellKnowledge(true, true, 9), CellKnowledge(false, true, 9))), hidden.changes)
        assertFailsWith<UnsupportedOperationException> { (terrain.changes as MutableList).clear() }
        listOf(original, first.store, repeated.store, terrain.store, hidden.store).forEach { it.close() }
    }
}
