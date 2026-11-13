package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import java.nio.file.Path
import kotlin.test.*

class HoverKnowledgeTest {
    @Test fun a_new_hard_ground_gid_replans_even_when_both_old_and_new_cells_block_land() {
        val tiles = TileSetDefinition(1, "shore", 20, 20, Path.of("unused.png"), setOf(2),
            3, 3, waterTileIds = setOf(1), hoverBlockedTileIds = setOf(2))
        val gids = IntArray(25) { if (it == 12) 3 else 1 }
        val map = MapDefinition(5, 5, 20, 20, listOf(tiles), gids,
            BlockingGrid(5, 5, BooleanArray(25) { it == 12 }))
        val base = TestContentFactory.openScenario()
        val unit = UnitDefinition("hover", "CustomUnitMetadata", 1.0, 1.0, 1, Path.of("unused.png"), 1.0,
            MovementProfile(MovementType.HOVER, 1.0, .04, .09, 180.0, 0.0, false, false), emptySet())
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(map = map,
            unitDefinitions = mapOf("hover" to unit), units = listOf(UnitSpawn("u1", "p1", "hover", Position(30.0, 50.0), 90.0))))).context
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
