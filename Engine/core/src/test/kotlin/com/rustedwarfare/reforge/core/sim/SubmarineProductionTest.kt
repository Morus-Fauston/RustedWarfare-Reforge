package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class SubmarineProductionTest {
    @Test fun normal_t1_and_t2_factories_sell_a_real_submarine_born_at_surface_with_thirty_frame_protection() =
        WaterTestScene.withMap { map ->
            for (format in listOf("ini", "json", "hjson")) for (tier in listOf("seaFactory", "seaFactoryT2"))
                withSubmarinePackage(format) { root ->
                    val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value
                    val base = SeaFactoryScene.scenario(map, format, tier)
                    val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
                        unitDefinitions = base.unitDefinitions + pack.units))).context
                    try {
                        assertIs<ActionResult.Accepted>(context.submit(ProduceAction("produce-sub", "p1", "factory",
                            "attackSubmarine", "a".repeat(32), context.world)))
                        assertEquals(10200L, context.observePlayer(context.world, "p1").balance)
                        val id = "produced:p1:${"a".repeat(32)}"
                        var q = 0L
                        while (context.observe().units.none { it.unitId == id } && q < 1100L)
                            context.advance(Step(q++, 1.0, "sim-tick"))
                        val birth = context.observe()
                        val sub = birth.units.single { it.unitId == id }
                        assertEquals(Position(510.0, 155.0), sub.position)
                        assertEquals(0.0, sub.height)
                        assertEquals(260.0, sub.health)
                        assertEquals(30f, birth.production!!.units.single { it.unitId == id }.birthRemaining)
                        context.advance(Step(q++, 1.0, "sim-tick"))
                        assertEquals((-.003f).toDouble(), context.observe().units.single { it.unitId == id }.height)
                        repeat(300) { context.advance(Step(q++, 1.0, "sim-tick")) }
                        assertEquals(-8.0, context.observe().units.single { it.unitId == id }.height)
                        assertTrue(context.observe().units.single { it.unitId == id }.position.y > 180.0)
                    } finally { context.close() }
                }
        }
}
