package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class DropshipMovementTest {
    @Test fun a_short_ordinary_move_behind_the_body_reverses_without_turning_away() = WaterTestScene.withMap { map ->
        val context = DropshipScene.open(DropshipScene.scenario(map))
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("back", "p1", "air", Position(100.0, 150.0), context.world)))
            val after = context.advance(Step(0, 1.0, "sim-tick")).units.single()
            assertEquals(90.0, after.heading, .01)
            assertTrue(after.position.x < 190.0, after.position.toString())
            assertTrue(after.speed < 0.0)
        } finally { context.close() }
    }
}
