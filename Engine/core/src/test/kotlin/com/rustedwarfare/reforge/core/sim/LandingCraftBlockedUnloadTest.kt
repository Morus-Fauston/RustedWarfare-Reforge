package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class LandingCraftBlockedUnloadTest {
    @Test fun five_blocked_candidates_keep_the_same_passenger_until_the_carrier_moves_to_a_legal_place() = WaterTestScene.withMap { original ->
        // Fixed trusted terrain before initialization: only LAND is blocked at all five native candidates.
        val blocked = BooleanArray(original.widthTiles * original.heightTiles) { index ->
            original.blocking.isBlocked(index % original.widthTiles, index / original.widthTiles) ||
                index % original.widthTiles in 8..9 && index / original.widthTiles in 6..8
        }
        val map = MapDefinition(original.widthTiles, original.heightTiles, original.tileWidth, original.tileHeight,
            original.tileSets, original.groundGids, BlockingGrid(original.widthTiles, original.heightTiles, blocked))
        val context = LandingCraftScene.open(LandingCraftScene.scenario(map, spawns = listOf(
            UnitSpawn("craft", "p1", "hovercraft", Position(190.0, 150.0), 90.0),
            UnitSpawn("passenger", "p1", "builder", Position(210.0, 150.0), 90.0))))
        try {
            assertIs<ActionResult.Accepted>(context.submit(LoadAction("load", "p1", "passenger", "craft", LoadDirection.INTO, context.world)))
            val initial = context.observe().units.single { it.unitId == "passenger" }
            assertIs<ActionResult.Accepted>(context.submit(UnloadAction("unload", "p1", "craft", true, context.world)))
            var blockedEvents = 0
            repeat(60) { blockedEvents += context.advance(Step(it.toLong(), 1.0, "sim-tick")).events.count { e -> e.kind == "unload-blocked" } }
            assertEquals(2, blockedEvents)
            val failed = context.observe()
            assertEquals("craft", failed.units.single { it.unitId == "passenger" }.carriedBy)
            assertEquals(initial.position, failed.units.single { it.unitId == "passenger" }.position)
            assertEquals(listOf("passenger"), failed.units.single { it.unitId == "craft" }.transport!!.passengerIds)
            assertTrue(failed.units.single { it.unitId == "craft" }.transport!!.unloading)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("relocate", "p1", "craft", Position(300.0, 150.0), context.world)))
            var step = 60L
            while (context.observe().units.single { it.unitId == "passenger" }.carriedBy != null && step < 700L)
                context.advance(Step(step++, 1.0, "sim-tick"))
            assertNull(context.observe().units.single { it.unitId == "passenger" }.carriedBy)
            assertEquals(0, context.observe().units.single { it.unitId == "craft" }.transport!!.passengerCount)
            assertFalse(context.observe().units.single { it.unitId == "craft" }.transport!!.unloading)
        } finally { context.close() }
    }
}
