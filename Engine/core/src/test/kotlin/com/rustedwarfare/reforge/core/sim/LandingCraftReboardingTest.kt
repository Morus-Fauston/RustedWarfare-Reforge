package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class LandingCraftReboardingTest {
    @Test fun reboarding_keeps_the_previous_exit_reference_and_suspends_its_active_countdown() = WaterTestScene.withMap { map ->
        val context = LandingCraftScene.open(LandingCraftScene.scenario(map, spawns = listOf(
            UnitSpawn("a-passenger", "p1", "builder", Position(200.0, 150.0), 90.0),
            UnitSpawn("craft", "p1", "hovercraft", Position(190.0, 150.0), 90.0))))
        try {
            assertIs<ActionResult.Accepted>(context.submit(LoadAction("board", "p1", "a-passenger", "craft", LoadDirection.INTO, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnloadAction("release", "p1", "craft", true, context.world)))
            repeat(30) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val exit = context.observe().units.single { it.unitId == "a-passenger" }.transportExit
            assertEquals(45f, assertNotNull(exit).remaining)
            assertIs<ActionResult.Accepted>(context.submit(LoadAction("reboard", "p1", "craft", "a-passenger", LoadDirection.UP, context.world)))
            repeat(50) { context.advance(Step(it + 30L, 1.0, "sim-tick")) }
            val passenger = context.observe().units.single { it.unitId == "a-passenger" }
            assertEquals("craft", passenger.carriedBy)
            assertEquals(exit, passenger.transportExit)
        } finally { context.close() }
    }
}
