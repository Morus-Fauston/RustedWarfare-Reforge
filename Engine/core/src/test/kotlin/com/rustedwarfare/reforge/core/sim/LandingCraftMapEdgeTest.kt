package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.test.*

class LandingCraftMapEdgeTest {
    @Test fun north_candidate_truncation_does_not_release_into_the_same_blocked_cell_as_an_earlier_candidate() = WaterTestScene.withMap { original ->
        val blocked = BooleanArray(original.widthTiles * original.heightTiles) { index ->
            original.blocking.isBlocked(index % original.widthTiles, index / original.widthTiles) ||
                index % original.widthTiles in 0..1 && index / original.widthTiles == 0
        }
        val map = MapDefinition(original.widthTiles, original.heightTiles, original.tileWidth, original.tileHeight,
            original.tileSets, original.groundGids, BlockingGrid(original.widthTiles, original.heightTiles, blocked))
        val context = LandingCraftScene.open(LandingCraftScene.scenario(map, spawns = listOf(
            UnitSpawn("passenger", "p1", "builder", Position(10.0, 25.0), 0.0),
            UnitSpawn("craft", "p1", "hovercraft", Position(18.0, 15.0), 270.0))))
        try {
            assertIs<ActionResult.Accepted>(context.submit(LoadAction("board", "p1", "passenger", "craft", LoadDirection.INTO, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnloadAction("release", "p1", "craft", true, context.world)))
            // The fifth y is slightly negative, but both it and the fourth y
            // truncate to row zero. A blocked earlier cell cannot be bypassed.
            var blockedEvents = 0
            repeat(60) { blockedEvents += context.advance(Step(it.toLong(), 1.0, "sim-tick")).events.count { e -> e.kind == "unload-blocked" } }
            assertEquals(2, blockedEvents)
            assertEquals("craft", context.observe().units.single { it.unitId == "passenger" }.carriedBy)
            assertEquals(listOf("passenger"), context.observe().units.single { it.unitId == "craft" }.transport!!.passengerIds)
            assertTrue(context.observePlayer(context.world, "p1").units.none { it.unitId == "passenger" })
        } finally { context.close() }
    }

    @Test fun native_negative_west_exit_publishes_stays_visible_and_accepts_a_route_back_inside() = WaterTestScene.withMap { original ->
        val blocked = BooleanArray(original.widthTiles * original.heightTiles) { index ->
            original.blocking.isBlocked(index % original.widthTiles, index / original.widthTiles) ||
                index % original.widthTiles in 0..1 && index / original.widthTiles == 1
        }
        val map = MapDefinition(original.widthTiles, original.heightTiles, original.tileWidth, original.tileHeight,
            original.tileSets, original.groundGids, BlockingGrid(original.widthTiles, original.heightTiles, blocked))
        val context = LandingCraftScene.open(LandingCraftScene.scenario(map, spawns = listOf(
            UnitSpawn("a", "p1", "builder", Position(30.0, 10.0), 0.0),
            UnitSpawn("b", "p1", "builder", Position(31.0, 10.0), 0.0),
            UnitSpawn("craft", "p1", "hovercraft", Position(24.0, 18.0), 0.0))).copy(
                automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false)))
        try {
            for (id in listOf("a", "b")) assertIs<ActionResult.Accepted>(context.submit(
                LoadAction("board-$id", "p1", id, "craft", LoadDirection.INTO, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnloadAction("release", "p1", "craft", true, context.world)))
            repeat(30) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val exit = context.observe().units.single { it.unitId == "b" }
            assertNull(exit.carriedBy)
            // First four native cells are blocked. The fifth is the legal (0,0)
            // cell after JVM truncation, while its actual center remains negative.
            assertTrue(exit.position.x in -6.0..-4.0 && exit.position.y in 14.0..16.0, exit.position.toString())
            assertEquals(listOf("a"), context.observe().units.single { it.unitId == "craft" }.transport!!.passengerIds)
            assertEquals(exit.position, context.observePlayer(context.world, "p1").units.single { it.unitId == "b" }.position)
            assertTrue(context.observePlayer(context.world, "p2").units.none { it.unitId == "b" })
            assertIs<ActionResult.Accepted>(context.submit(SelectAction("select", "p1", "b", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnloadAction("cancel", "p1", "craft", false, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "b", context.world)))
            repeat(60) { context.advance(Step(it + 30L, 1.0, "sim-tick")) }
            assertNull(context.observe().units.single { it.unitId == "b" }.transportExit)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("outside", "p1", "b", Position(-30.0, 10.0), context.world)))
            assertTrue(context.observe().units.single { it.unitId == "b" }.tasks.isEmpty())
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("back-in", "p1", "b", Position(100.0, 10.0), context.world)))
            repeat(300) { context.advance(Step(it + 90L, 1.0, "sim-tick")) }
            assertTrue(context.observe().units.single { it.unitId == "b" }.position.x > 80)
            assertTrue(context.observePlayer(context.world, "p1").units.any { it.unitId == "b" })
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("ordinary-boundary", "p1", "b", Position(0.0, 10.0), context.world)))
            assertTrue(context.observe().units.single { it.unitId == "b" }.tasks.isEmpty(), "The normal radius is restored after returning inside")
        } finally { context.close() }
    }

    @Test fun native_cell_legal_exit_near_the_map_edge_publishes_and_remains_a_valid_world() = WaterTestScene.withMap { map ->
        val context = LandingCraftScene.open(LandingCraftScene.scenario(map, spawns = listOf(
            UnitSpawn("a-passenger", "p1", "builder", Position(30.0, 150.0), 90.0),
            UnitSpawn("craft", "p1", "hovercraft", Position(15.0, 150.0), 90.0))))
        try {
            assertIs<ActionResult.Accepted>(context.submit(LoadAction("board", "p1", "a-passenger", "craft", LoadDirection.INTO, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnloadAction("release", "p1", "craft", true, context.world)))
            repeat(30) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val passenger = context.observe().units.single { it.unitId == "a-passenger" }
            assertNull(passenger.carriedBy)
            // Native lookup-table trigonometry is approximate; the independent property here
            // is a legal center in the edge cell with part of the radius beyond the inset.
            assertTrue(passenger.position.x in 0.0..<10.0 && passenger.position.y in 150.0..<160.0)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-exit", "p1", "a-passenger", context.world)))
            repeat(60) { context.advance(Step(it + 30L, 1.0, "sim-tick")) }
            assertNull(context.observe().units.single { it.unitId == "a-passenger" }.transportExit)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("back-in", "p1", "a-passenger", Position(70.0, 159.0), context.world)))
            repeat(240) { context.advance(Step(it + 90L, 1.0, "sim-tick")) }
            assertTrue(context.observe().units.single { it.unitId == "a-passenger" }.position.x > 60)
        } finally { context.close() }
    }
}
