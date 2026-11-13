package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.path.PathSearchReason
import java.nio.file.Path
import kotlin.test.*

class OverCliffKnowledgeTest {
    @Test fun newly_known_hard_ground_in_a_later_segment_replans_before_the_current_connection_fails() {
        val tiles = TileSetDefinition(1, "cliff", 20, 20, Path.of("unused.png"), setOf(1, 2),
            3, 3, hoverBlockedTileIds = setOf(2), overCliffBlockedTileIds = setOf(2))
        val map = MapDefinition(5, 5, 20, 20, listOf(tiles),
            IntArray(25) { if (it == 7 || it == 12) 3 else 1 },
            BlockingGrid(5, 5, BooleanArray(25) { it == 7 || it == 12 }))
        val unit = UnitDefinition("climber", "CustomUnitMetadata", 1.0, 1.0, 1, Path.of("unused.png"), 1.0,
            MovementProfile(MovementType.OVER_CLIFF, 1.0, .04, .09, 180.0, 0.0, false, false), emptySet())
        val input = TestContentFactory.openScenario().copy(map = map, unitDefinitions = mapOf("climber" to unit),
            units = listOf(UnitSpawn("u1", "p1", "climber", Position(30.0, 50.0), 90.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            context.enableKnowledge(context.world, KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
            context.acceptVisibleTerrain(context.world, "p1", List(25) { index ->
                VisibleTerrainCell(index % 5, index / 5, index == 7 || index == 12,
                    when (index) { 7 -> 2; 12 -> 3; else -> 1 })
            })
            context.advance(Step(0, 1.0, "sim-tick"))
            context.enablePathDiagnostics(context.world)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "u1", Position(70.0, 50.0), context.world)))
            val original = context.observe().units.single().pathPoints
            assertEquals(listOf(Position(30.0, 30.0), Position(70.0, 30.0), Position(70.0, 50.0)), original)
            context.drainPathDiagnostics(context.world)
            context.acceptVisibleTerrain(context.world, "p1", listOf(VisibleTerrainCell(2, 1, true, 3)))
            val next = context.advance(Step(1, 1.0, "sim-tick")).units.single()
            assertNotEquals(original, next.pathPoints, "The safe first connection must not conceal a blocked later segment")
            assertEquals(original.first(), next.pathPoints.first(), "Keep the still-safe prefix")
            assertEquals(listOf(PathSearchReason.KNOWLEDGE_REPLAN), context.drainPathDiagnostics(context.world).map { it.reason })
        } finally { context.close() }
    }

    @Test fun a_new_hard_ground_gid_replans_even_when_both_old_and_new_cells_block_land() {
        val tiles = TileSetDefinition(1, "shore", 20, 20, Path.of("unused.png"), setOf(1, 2),
            3, 3,  hoverBlockedTileIds = setOf(2), overCliffBlockedTileIds = setOf(2))
        val gids = IntArray(25) { if (it == 12) 3 else 1 }
        val map = MapDefinition(5, 5, 20, 20, listOf(tiles), gids,
            BlockingGrid(5, 5, BooleanArray(25) { it == 12 }))
        val base = TestContentFactory.openScenario()
        val unit = UnitDefinition("climber", "CustomUnitMetadata", 1.0, 1.0, 1, Path.of("unused.png"), 1.0,
            MovementProfile(MovementType.OVER_CLIFF, 1.0, .04, .09, 180.0, 0.0, false, false), emptySet())
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(map = map,
            unitDefinitions = mapOf("climber" to unit), units = listOf(UnitSpawn("u1", "p1", "climber", Position(30.0, 50.0), 90.0))))).context
        try {
            context.enableKnowledge(context.world, KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
            context.acceptVisibleTerrain(context.world, "p1", listOf(VisibleTerrainCell(2, 2, true, 2)))
            context.advance(Step(0, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "u1", Position(70.0, 50.0), context.world)))
            assertEquals(listOf(Position(70.0, 50.0)), context.observePlayer(context.world, "p1").units.single().pathPoints)
            context.acceptVisibleTerrain(context.world, "p1", listOf(VisibleTerrainCell(2, 2, true, 3)))
            val view = context.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(listOf(Position(30.0, 30.0), Position(70.0, 30.0), Position(70.0, 50.0)), view.units.single().pathPoints)
        } finally { context.close() }
    }
}
