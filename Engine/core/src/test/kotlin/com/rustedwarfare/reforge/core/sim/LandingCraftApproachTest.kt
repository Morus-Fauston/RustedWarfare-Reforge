package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class LandingCraftApproachTest {
    @Test fun both_loading_orders_approach_but_only_load_up_boards_at_exactly_twenty_one() = WaterTestScene.withMap { map ->
        for (direction in LoadDirection.entries) for (distance in listOf(21.0, 60.0)) {
            val context = LandingCraftScene.open(LandingCraftScene.scenario(map, spawns = listOf(
                UnitSpawn("craft", "p1", "hovercraft", Position(190.0, 150.0), 90.0),
                UnitSpawn("worker", "p1", "builder", Position(190.0 + distance, 150.0), 270.0))))
            try {
                val actor = if (direction == LoadDirection.UP) "craft" else "worker"
                val target = if (direction == LoadDirection.UP) "worker" else "craft"
                val result = context.submit(LoadAction("load", "p1", actor, target, direction, context.world))
                assertIs<ActionResult.Accepted>(result, "$direction/$distance: $result")
                val immediate = context.observe().units.single { it.unitId == "worker" }.carriedBy
                assertEquals(if (direction == LoadDirection.UP && distance == 21.0) "craft" else null, immediate)
                var step = 0L
                while (context.observe().units.single { it.unitId == "worker" }.carriedBy == null && step < 500L)
                    context.advance(Step(step++, 1.0, "sim-tick"))
                assertEquals("craft", context.observe().units.single { it.unitId == "worker" }.carriedBy, "$direction/$distance")
                assertEquals(1, context.observe().units.single { it.unitId == "craft" }.transport!!.occupiedSlots)
            } finally { context.close() }
        }
    }
}
