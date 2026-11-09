package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class LandingCraftShoreLoadingTest {
    @Test fun a_land_passenger_approaches_a_craft_at_the_wet_shore_without_needing_a_path_to_its_center() = WaterTestScene.withMap { map ->
        val context = LandingCraftScene.open(LandingCraftScene.scenario(map, spawns = listOf(
            UnitSpawn("craft", "p1", "hovercraft", Position(405.0, 150.0), 270.0),
            UnitSpawn("worker", "p1", "builder", Position(330.0, 150.0), 90.0))))
        try {
            assertIs<ActionResult.Accepted>(context.submit(LoadAction("board", "p1", "worker", "craft", LoadDirection.INTO, context.world)))
            var step = 0L
            while (context.observe().units.single { it.unitId == "worker" }.carriedBy == null && step < 500L)
                context.advance(Step(step++, 1.0, "sim-tick"))
            val passenger = context.observe().units.single { it.unitId == "worker" }
            assertEquals("craft", passenger.carriedBy)
            assertTrue(passenger.position.x < 400.0, passenger.position.toString())
        } finally { context.close() }
    }
}
