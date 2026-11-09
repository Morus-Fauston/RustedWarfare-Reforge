package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class SubmarineSpeedTest {
    @Test fun submerged_propulsion_is_point_four_five_and_surface_intent_does_not_change_it_early() =
        WaterTestScene.withMap { map -> withSubmarinePackage { root ->
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value
            val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
                unitDefinitions = pack.units,
                units = listOf(UnitSpawn("sub", "p1", "attackSubmarine", Position(500.0, 200.0), 90.0, -8.0)),
                combatContract = CombatContract(12345, -1, mapOf("sub" to 8L), 9L))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "sub", Position(740.0, 200.0), context.world)))
                val first = context.advance(Step(0L, 1.0, "sim-tick")).units.single()
                assertEquals(.0081, first.speed, 1e-12)
                for (q in 1L..60L) context.advance(Step(q, 1.0, "sim-tick"))
                assertEquals(.45, context.observe().units.single().speed, 1e-12)
                assertIs<ActionResult.Accepted>(context.submit(SubmarineDepthAction("surface", "p1", "sub", true, context.world)))
                val rising = context.advance(Step(61L, 1.0, "sim-tick")).units.single()
                assertEquals(.45, rising.speed, 1e-12)
                assertTrue(rising.height < -1.0)
                for (q in 62L..250L) context.advance(Step(q, 1.0, "sim-tick"))
                val surface = context.observe().units.single()
                assertTrue(surface.height >= -1.0)
                assertEquals(.8, surface.speed, 1e-12)
                assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "sub", context.world)))
                for (q in 251L..270L) context.advance(Step(q, 1.0, "sim-tick"))
                assertEquals(0.0, context.observe().units.single().speed)
                assertTrue(context.observe().units.single().tasks.isEmpty())
            } finally { context.close() }
        } }
}
