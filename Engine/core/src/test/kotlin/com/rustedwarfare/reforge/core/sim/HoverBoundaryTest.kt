package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import java.nio.file.Path
import kotlin.test.*

class HoverBoundaryTest {
    @Test fun hidden_building_blocks_actual_hover_motion_on_water_without_revealing_its_identity_or_ground() = withHoverPackage { root ->
        val hover = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value.units.getValue("hoverTank")
        val blind = UnitDefinition(hover.id, hover.className, hover.radius, hover.displayRadius, hover.totalFrames,
            hover.imagePath, hover.imageScale, hover.movement, emptySet(), 0)
        val building = UnitDefinition("wall", "AuthoredBuilding", 6.0, 6.0, 1, hover.imagePath, 1.0,
            MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(), 0,
            durability = DurabilityProfile(100.0, null), building = BuildingProfile(BuildingFootprint(-1, -1, 1, 1),
                placementFootprint = BuildingFootprint(-1, -1, 1, 3), foundationMovementType = MovementType.WATER), build = BuildProfile(100L, .001f),
            construction = ConstructionProfile(100L, .001f))
        val tiles = TileSetDefinition(1, "water", 20, 20, Path.of("unused.png"), emptySet(), 1, 1, waterTileIds = setOf(0))
        val base = TestContentFactory.openScenario()
        val scenario = base.copy(map = MapDefinition(40, 30, 20, 20, listOf(tiles), IntArray(1200) { 1 },
            BlockingGrid(40, 30, BooleanArray(1200) { true })), unitDefinitions = mapOf("hover" to blind, "wall" to building),
            units = listOf(UnitSpawn("h", "p1", "hover", Position(500.0, 210.0), 90.0),
                UnitSpawn("wall", "p2", "wall", Position(610.0, 210.0), 0.0)),
            economyContract = EconomyContract(mapOf("p1" to 0L, "p2" to 0L)),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        val initialized = SimulationContext.initialize(scenario)
        val context = assertIs<InitResult.Accepted>(initialized, initialized.toString()).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("blind", "p1", "h", Position(700.0, 210.0), context.world)))
            assertEquals(listOf(Position(700.0, 210.0)), context.observe().units.single { it.unitId == "h" }.pathPoints)
            var corrections = 0
            repeat(450) { index ->
                val frame = context.advance(Step(index.toLong(), 1.0, "sim-tick"))
                assertTrue(frame.units.single { it.unitId == "h" }.position.x < 600.0)
                corrections += frame.events.count { it.kind == "movement-blocked" }
            }
            assertTrue(corrections > 0)
            val view = context.observePlayer(context.world, "p1")
            assertTrue(view.units.none { it.unitId == "wall" })
            assertNull(view.knowledge!!.cell(30, 10).lastKnownGroundGid)
            val outside = assertIs<ActionResult.Accepted>(context.submit(MoveAction("outside", "p1", "h", Position(900.0, 210.0), context.world)))
            assertTrue(outside.events.any { it.kind == "path-unreachable" })
            assertNull(context.observe().units.single { it.unitId == "h" }.moveTarget)
        } finally { context.close() }
    }

    @Test fun an_enclosed_hard_ground_target_never_allows_hover_to_cross_the_wall() = withHoverPackage { root ->
        val hover = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value.units.getValue("hoverTank")
        val unit = UnitDefinition(hover.id, hover.className, hover.radius, hover.displayRadius, hover.totalFrames,
            hover.imagePath, hover.imageScale, hover.movement, emptySet())
        val tiles = TileSetDefinition(1, "ground", 20, 20, Path.of("unused.png"), setOf(1), 2, 2, hoverBlockedTileIds = setOf(1))
        val gids = IntArray(1200) { if (it % 40 == 20) 2 else 1 }
        val base = TestContentFactory.openScenario()
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
            map = MapDefinition(40, 30, 20, 20, listOf(tiles), gids, BlockingGrid(40, 30, BooleanArray(1200) { it % 40 == 20 })),
            unitDefinitions = mapOf("hover" to unit), units = listOf(UnitSpawn("h", "p1", "hover", Position(300.0, 210.0), 90.0))))).context
        try {
            context.submit(MoveAction("closed", "p1", "h", Position(510.0, 210.0), context.world))
            repeat(500) { index ->
                assertTrue(context.advance(Step(index.toLong(), 1.0, "sim-tick")).units.single().position.x < 400.0)
            }
        } finally { context.close() }
    }
}
