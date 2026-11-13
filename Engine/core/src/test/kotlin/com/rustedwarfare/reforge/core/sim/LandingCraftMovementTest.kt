package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class LandingCraftMovementTest {
    @Test fun the_native_craft_uses_its_liquid_speed_and_returns_over_the_same_shore() = WaterTestScene.withMap { map ->
        for ((start, target, speed) in listOf(Triple(190.0, 350.0, .9), Triple(500.0, 700.0, 1.2))) {
            val context = LandingCraftScene.open(LandingCraftScene.scenario(map, spawns = listOf(
                UnitSpawn("craft", "p1", "hovercraft", Position(start, 150.0), 90.0))))
            try {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "craft", Position(target, 150.0), context.world)))
                repeat(80) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                val cruising = context.observe().units.single()
                assertEquals(speed, cruising.speed)
                assertTrue(cruising.height > 0.0)
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("return", "p1", "craft", Position(190.0, 150.0), context.world)))
                var step = 80L
                while ((context.observe().units.single().position.x > 210.0 || context.observe().units.single().moveTarget != null) && step < 1500L)
                    context.advance(Step(step++, 1.0, "sim-tick"))
                assertTrue(context.observe().units.single().position.x < 210.0)
                assertNull(context.observe().units.single().moveTarget)
            } finally { context.close() }
        }
    }
}
