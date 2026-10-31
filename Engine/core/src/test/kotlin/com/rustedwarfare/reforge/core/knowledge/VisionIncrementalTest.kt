package com.rustedwarfare.reforge.core.knowledge

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class VisionIncrementalTest {
    private val players = listOf(Player("p1", PlayerControl.LOCAL, "t"), Player("p2", PlayerControl.LOCAL, "t"),
        Player("p3", PlayerControl.LOCAL))
    private val source = VisionSource("p1", Position(30.0, 30.0), 1)
    private fun project(previous: AutomaticVisionState?, epoch: Long, sources: List<VisionSource>,
        sharing: Boolean = true, ps: List<Player> = players, width: Int = 5, height: Int = 5,
        tileWidth: Int = 20, tileHeight: Int = 20, world: String = "w") =
        AutomaticVision.project(previous, world, epoch, width, height, tileWidth, tileHeight, ps, sources, sharing)

    @Test fun unchanged_sources_reuse_cells_but_advance_the_supplied_epoch() {
        val first = project(null, 0, listOf(source))
        val next = project(first.state, 1, listOf(source))
        for (player in players) {
            val before = first.views.getValue(player.playerId); val after = next.views.getValue(player.playerId)
            assertSame(before.basicCells, after.basicCells)
            assertSame(before.effectiveCells, after.effectiveCells)
            assertEquals(0L, before.epoch); assertEquals(1L, after.epoch)
            assertNotSame(before, after)
        }
    }

    @Test fun movement_radius_owner_and_last_overlapping_source_removal_refresh_visibility() {
        val first = project(null, 0, listOf(source, source))
        val one = project(first.state, 1, listOf(source))
        assertEquals(first.views.getValue("p1").basicCells, one.views.getValue("p1").basicCells)
        val movedSource = source.copy(position = Position(50.0, 30.0))
        val moved = project(one.state, 2, listOf(movedSource))
        assertEquals(listOf(VisionCell(2, 0), VisionCell(1, 1), VisionCell(2, 1), VisionCell(3, 1), VisionCell(2, 2)),
            moved.views.getValue("p1").basicCells)
        val transferred = project(moved.state, 3, listOf(movedSource.copy(owner = "p2")), false)
        assertTrue(transferred.views.getValue("p1").basicCells.isEmpty())
        assertEquals(moved.views.getValue("p1").basicCells, transferred.views.getValue("p2").basicCells)
        val blind = project(transferred.state, 4, listOf(movedSource.copy(owner = "p2", radius = 0)))
        assertTrue(blind.views.values.all { it.basicCells.isEmpty() })
        val gone = project(blind.state, 5, emptyList())
        assertTrue(gone.views.values.all { it.effectiveCells.isEmpty() })
        assertEquals(5, first.views.getValue("p1").basicCells.size)
        assertTrue(first.views.getValue("p1").contains(0, 1))
    }

    @Test fun sharing_and_team_changes_refresh_even_when_sources_are_stationary() {
        val first = project(null, 0, listOf(source))
        val disabled = project(first.state, 1, listOf(source), false)
        assertTrue(disabled.views.getValue("p2").effectiveCells.isEmpty())
        val changedPlayers = players.map { if (it.playerId == "p2") it.copy(teamId = "other") else it }
        val changed = project(disabled.state, 2, listOf(source), ps = changedPlayers)
        assertTrue(changed.views.getValue("p2").effectiveCells.isEmpty())
        val restored = project(changed.state, 3, listOf(source))
        assertEquals(first.views.getValue("p2").effectiveCells, restored.views.getValue("p2").effectiveCells)
        assertTrue(restored.views.getValue("p2").basicCells.isEmpty())
    }

    @Test fun map_geometry_and_world_changes_invalidate_source_coverage() {
        val rectangular = source.copy(position = Position(30.0, 15.0))
        val first = project(null, 0, listOf(rectangular), tileHeight = 10)
        val changed = project(first.state, 1, listOf(rectangular), tileWidth = 10)
        assertEquals(listOf(VisionCell(2, 0), VisionCell(3, 0), VisionCell(2, 1), VisionCell(3, 1)),
            changed.views.getValue("p1").basicCells)
        val cropped = project(changed.state, 2, listOf(rectangular), width = 3, height = 2, tileHeight = 10, world = "new")
        assertEquals(listOf(VisionCell(1, 0), VisionCell(0, 1), VisionCell(1, 1), VisionCell(2, 1)),
            cropped.views.getValue("p1").basicCells)
        assertEquals("new", cropped.views.getValue("p1").worldId)
        assertEquals(5, first.views.getValue("p1").basicCells.size)
    }

    @Test fun deterministic_source_histories_match_an_independent_all_cell_oracle() {
        var prior: AutomaticVisionState? = null
        val retained = mutableListOf<Pair<VisionSnapshot, List<VisionCell>>>()
        for (step in 0..199) {
            val ps = players.map { if (it.playerId == "p2" && step % 5 == 0) it.copy(teamId = null) else it }
            val sharing = step % 3 != 0
            val sources = (0 until (step % 6)).map { index -> VisionSource(ps[index % 3].playerId,
                Position(10.0 + ((step + index * 3) % 6) * 20, 5.0 + ((step * 2 + index) % 7) * 10), (step + index) % 4 - 1) }
            val input = if (step % 4 == 0) sources + sources else sources
            val next = project(prior, step.toLong(), input, sharing, ps, 6, 7, 20, 10)
            for (player in ps) {
                fun expected(effective: Boolean) = (0 until 7).flatMap { row -> (0 until 6).mapNotNull { column ->
                    val visible = input.any { s ->
                        val owner = ps.single { it.playerId == s.owner }
                        val allowed = owner.playerId == player.playerId || (effective && sharing &&
                            player.teamId != null && owner.teamId == player.teamId)
                        val dx = column + .5 - s.position.x / 20
                        val dy = row + .5 - s.position.y / 10
                        allowed && s.radius > 0 && dx * dx + dy * dy <= s.radius.toDouble() * s.radius
                    }
                    if (visible) VisionCell(column, row) else null
                } }
                val view = next.views.getValue(player.playerId)
                assertEquals(expected(false), view.basicCells, "basic $step/${player.playerId}")
                assertEquals(expected(true), view.effectiveCells, "effective $step/${player.playerId}")
                for (row in 0 until 7) for (column in 0 until 6)
                    assertEquals(VisionCell(column, row) in view.effectiveCells, view.contains(column, row))
                retained.add(view to view.effectiveCells.toList())
            }
            prior = next.state
        }
        for ((view, expected) in retained) assertEquals(expected, view.effectiveCells)
    }

    @Test fun formal_context_keeps_versions_and_reuses_stationary_cells() {
        val scene = TestContentFactory.openScenario().copy(automaticVision =
            KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, true))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val before = context.observePlayer(context.world, "p1")
            context.advance(Step(0, 1.0, "sim-tick"))
            val after = context.observePlayer(context.world, "p1")
            assertEquals(checkNotNull(before.vision).epoch + 1, checkNotNull(after.vision).epoch)
            assertEquals(checkNotNull(before.knowledge).revision + 1, checkNotNull(after.knowledge).revision)
            assertSame(before.vision.basicCells, after.vision.basicCells)
            assertSame(before.vision.effectiveCells, after.vision.effectiveCells)
            assertSame(before.knowledge.cells, after.knowledge.cells)
            val oldWorld = context.world
            context.close()
            assertIs<InitResult.Accepted>(context.reopen(scene))
            assertEquals(0L, context.visionSnapshot(context.world, "p1").epoch)
            assertEquals("stale-world", assertFailsWith<KnowledgeException> { context.visionSnapshot(oldWorld, "p1") }.code)
            assertEquals(before.vision.basicCells, after.vision.basicCells)
        } finally { context.close() }
    }
}
