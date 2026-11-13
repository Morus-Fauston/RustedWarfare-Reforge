package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class NautilusShoreRouteTest {
    @Test fun one_move_order_survives_land_surface_land_replanning_at_both_shores() = WaterTestScene.withMap { map ->
        val c = NautilusScene.open(NautilusScene.scenario(map).copy(units = listOf(
            UnitSpawn("mother", "p1", "nautilusSubmarineLand", Position(300.0, 180.0), 90.0))))
        try {
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("out", "p1", "mother", Position(600.0, 180.0), c.world)))
            for (step in 0L..999L) c.advance(Step(step, 1.0, "sim-tick"))
            val surface = c.observe().units.single()
            assertEquals("nautilusSubmarineSurface", surface.typeId)
            assertTrue(surface.position.x > 590.0, "The converted unit must finish the original destination")
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("back", "p1", "mother", Position(200.0, 180.0), c.world)))
            for (step in 1000L..2199L) c.advance(Step(step, 1.0, "sim-tick"))
            val land = c.observe().units.single()
            assertEquals("nautilusSubmarineLand", land.typeId)
            assertTrue(land.position.x < 210.0, "Replanning must also preserve the return command")
        } finally { c.close() }
    }
}
