package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class LandingCraftActivityTest {
    @Test fun an_onboard_patrol_does_not_maintain_targets_or_rewrite_its_suspended_route() = WaterTestScene.withMap { map ->
        val input = LandingCraftScene.scenario(map, spawns = listOf(
            UnitSpawn("craft", "p1", "hovercraft", Position(190.0, 150.0), 90.0),
            UnitSpawn("tank", "p1", "c_tank", Position(200.0, 150.0), 90.0),
            UnitSpawn("guard", "p1", "c_tank", Position(310.0, 150.0), 270.0),
            UnitSpawn("enemy", "p2", "builder", Position(300.0, 150.0), 180.0)))
        val context = LandingCraftScene.open(input.copy(players = input.players.map { it.copy(control = PlayerControl.LOCAL) }))
        try {
            assertIs<ActionResult.Accepted>(context.submit(PatrolAction("patrol", "p1", "tank", Position(300.0, 150.0), false, context.world)))
            context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals("enemy", context.observe().units.single { it.unitId == "tank" }.attackTargetId)
            assertIs<ActionResult.Accepted>(context.submit(LoadAction("board", "p1", "craft", "tank", LoadDirection.UP, context.world)))
            val before = context.observe().units.single { it.unitId == "tank" }
            assertEquals("craft", before.carriedBy)
            repeat(600) { context.advance(Step(it + 1L, 1.0, "sim-tick")) }
            assertFalse(context.observe().units.single { it.unitId == "enemy" }.alive)
            val after = context.observe().units.single { it.unitId == "tank" }
            assertEquals(before, after)
        } finally { context.close() }
    }
}
