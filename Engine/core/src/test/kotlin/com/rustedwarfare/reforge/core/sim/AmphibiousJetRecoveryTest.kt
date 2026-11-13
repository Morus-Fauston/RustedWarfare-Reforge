package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class AmphibiousJetRecoveryTest {
    @Test fun flying_again_resumes_a_preserved_move_that_was_paused_by_water_terrain() =
        WaterTestScene.withMap { map ->
            val context = AmphibiousJetScene.open(AmphibiousJetScene.scenario().copy(map = map,
                units = listOf(UnitSpawn("jet", "p1", "amphibiousJet", Position(500.0, 200.0), 270.0)),
                combatContract = CombatContract(12345, -1, mapOf("jet" to 8L), 9L)))
            try {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "jet", Position(300.0, 200.0), context.world)))
                val task = context.observe().units.single().tasks
                assertIs<ActionResult.Accepted>(context.submit(AmphibiousFlightAction("dive", "p1", "jet", false, context.world)))
                for (q in 0L..300L) context.advance(Step(q, 1.0, "sim-tick"))
                assertEquals(task, context.observe().units.single().tasks)
                assertTrue(context.observe().units.single().position.x >= 400.0)
                assertIs<ActionResult.Accepted>(context.submit(AmphibiousFlightAction("fly", "p1", "jet", true, context.world)))
                for (q in 301L..1000L) context.advance(Step(q, 1.0, "sim-tick"))
                val arrived = context.observe().units.single()
                assertTrue(arrived.position.x < 320.0, "The preserved move should resume after AIR becomes physically valid")
                assertTrue(arrived.tasks.isEmpty())
            } finally { context.close() }
        }
}
