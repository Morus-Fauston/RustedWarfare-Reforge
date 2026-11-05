package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class SeaFactoryProductionTest {
    @Test fun the_paid_water_hull_is_born_at_the_native_clamp_with_sixty_frame_protection_and_leaves_the_factory() = WaterTestScene.withMap { map ->
        for (format in listOf("ini", "json", "hjson")) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(SeaFactoryScene.scenario(map, format, "seaFactoryT2"))).context
            try {
                val id = "produced:p1:${"b".repeat(32)}"
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("ship", "p1", "factory", "heavyBattleship", "b".repeat(32), context.world)))
                var q = 0L
                while (context.observe().units.none { it.unitId == id } && q < 2600L)
                    context.advance(Step(q++, 1.0, "sim-tick"))
                val birth = context.observe()
                val ship = birth.units.single { it.unitId == id }
                assertEquals(Position(510.0, 170.0), ship.position)
                assertEquals(Position(509.0, 285.0), ship.moveTarget)
                assertEquals(180.0, ship.heading)
                assertEquals(60f, birth.production!!.units.single { it.unitId == id }.birthRemaining)
                assertNull(ship.mechPose)
                repeat(59) { context.advance(Step(q++, 1.0, "sim-tick")) }
                assertEquals(1f, context.observe().production!!.units.single { it.unitId == id }.birthRemaining)
                repeat(350) { context.advance(Step(q++, 1.0, "sim-tick")) }
                assertEquals(0f, context.observe().production!!.units.single { it.unitId == id }.birthRemaining)
                assertTrue(context.observe().units.single { it.unitId == id }.position.y > 270.0, format)
                assertEquals(5000L, context.observePlayer(context.world, "p1").balance)
            } finally { context.close() }
        }
    }
}
