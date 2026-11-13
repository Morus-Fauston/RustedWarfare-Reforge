package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class LandingCraftLoadingTest {
    @Test fun loading_a_moving_passenger_removes_selection_commands_and_world_activity() = WaterTestScene.withMap { map ->
        val input = LandingCraftScene.scenario(map, spawns = listOf(
            UnitSpawn("craft", "p1", "hovercraft", Position(190.0, 150.0), 90.0),
            UnitSpawn("worker", "p1", "builder", Position(200.0, 150.0), 90.0)))
        val context = LandingCraftScene.open(input)
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("walk", "p1", "worker", Position(270.0, 150.0), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(SelectAction("select", "p1", "worker", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(LoadAction("load", "p1", "worker", "craft", LoadDirection.INTO, context.world)))
            val loaded = context.observe().units.single { it.unitId == "worker" }
            assertTrue(loaded.alive)
            assertTrue(context.observePlayer(context.world, "p1").units.none { it.unitId == "worker" })
            assertIs<ActionResult.Rejected>(context.submit(SelectAction("select-again", "p1", "worker", context.world)))
            assertIs<ActionResult.Rejected>(context.submit(MoveAction("walk-again", "p1", "worker", Position(270.0, 150.0), context.world)))
            repeat(60) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val after = context.observe().units.single { it.unitId == "worker" }
            assertEquals(loaded.position, after.position)
            assertEquals(loaded.health, after.health)
        } finally { context.close() }
    }
}
