package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class AmphibiousJetActionTest {
    @Test fun fly_and_dive_change_only_intent_while_altitude_moves_later_and_stop_keeps_the_intent() =
        WaterTestScene.withMap { map ->
            val base = AmphibiousJetScene.scenario()
            val context = AmphibiousJetScene.open(base.copy(map = map, units = listOf(
                UnitSpawn("jet", "p1", "amphibiousJet", Position(500.0, 200.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("jet" to 8L), 9L)))
            try {
                val world = context.world
                assertEquals("not-owner", assertIs<ActionResult.Rejected>(context.submit(
                    AmphibiousFlightAction("intrude", "p2", "jet", false, world))).error.code)
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "jet", Position(650.0, 200.0), world)))
                val before = context.observe().units.single()
                val dive = context.submit(AmphibiousFlightAction("dive", "p1", "jet", false, world))
                assertIs<ActionResult.Accepted>(dive, dive.toString())
                val after = context.observe().units.single()
                assertEquals(before.height, after.height)
                assertEquals(before.tasks, after.tasks)
                assertEquals(before.pathPoints, after.pathPoints)
                assertEquals(before.moveTarget, after.moveTarget)
                assertEquals("amphibious-state-unchanged", assertIs<ActionResult.Rejected>(context.submit(
                    AmphibiousFlightAction("repeat-dive", "p1", "jet", false, world))).error.code)
                context.advance(Step(0, 1.0, "sim-tick"))
                assertEquals((-.006f).toDouble(), context.observe().units.single().height)
                assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "jet", world)))
                for (q in 1L..300L) context.advance(Step(q, 1.0, "sim-tick"))
                assertEquals(-8.0, context.observe().units.single().height)
                assertTrue(context.observe().units.single().tasks.isEmpty())
                assertIs<ActionResult.Accepted>(context.submit(AmphibiousFlightAction("fly", "p1", "jet", true, world)))
                assertEquals(-8.0, context.observe().units.single().height)
                context.advance(Step(301, 1.0, "sim-tick"))
                assertTrue(context.observe().units.single().height > -8.0)
                for (q in 302L..650L) context.advance(Step(q, 1.0, "sim-tick"))
                assertTrue(context.observe().units.single().height in 18.5..21.5)
            } finally { context.close() }
        }
}
