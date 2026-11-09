package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class AmphibiousJetMovementTest {
    @Test fun diving_preserves_the_order_but_cannot_follow_an_old_air_route_across_the_coast() =
        WaterTestScene.withMap { map ->
            val context = AmphibiousJetScene.open(AmphibiousJetScene.scenario().copy(map = map,
                units = listOf(UnitSpawn("jet", "p1", "amphibiousJet", Position(500.0, 200.0), 270.0)),
                combatContract = CombatContract(12345, -1, mapOf("jet" to 8L), 9L)))
            try {
                var step = 0L
                fun advance(count: Int) { repeat(count) { context.advance(Step(step++, 1.0, "sim-tick")) } }
                fun move(id: String, x: Double) = context.submit(MoveAction(id, "p1", "jet", Position(x, 200.0), context.world))
                assertIs<ActionResult.Accepted>(move("air-order", 300.0))
                val before = context.observe().units.single()
                assertIs<ActionResult.Accepted>(context.submit(AmphibiousFlightAction("dive", "p1", "jet", false, context.world)))
                assertEquals(before.tasks, context.observe().units.single().tasks)
                advance(500)
                val stopped = context.observe().units.single()
                assertEquals(-8.0, stopped.height)
                assertTrue(stopped.position.x >= 400.0, "A stale AIR route crossed onto dry land: ${stopped.position}")
                assertTrue(stopped.speed <= .4)
                assertEquals(before.tasks, stopped.tasks)
                assertIs<ActionResult.Accepted>(move("new-water-order", 650.0))
                advance(800)
                assertTrue(context.observe().units.single().position.x > 630.0)
                assertIs<ActionResult.Accepted>(context.submit(AmphibiousFlightAction("fly", "p1", "jet", true, context.world)))
                advance(350)
                assertIs<ActionResult.Accepted>(move("new-air-order", 300.0))
                advance(600)
                val landedOverDry = context.observe().units.single()
                assertTrue(landedOverDry.position.x < 320.0)
                assertEquals("dive-requires-water", assertIs<ActionResult.Rejected>(context.submit(
                    AmphibiousFlightAction("dry-dive", "p1", "jet", false, context.world))).error.code)
                assertEquals(landedOverDry, context.observe().units.single())
            } finally { context.close() }
        }
}
