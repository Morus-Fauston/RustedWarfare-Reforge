package com.rustedwarfare.reforge.core.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OwnershipTest {
    @Test
    fun m06_non_owner_move_and_stop_leave_every_observable_field_unchanged() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(
            ScenarioFixtures.openScenario().copy(
                players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
                units = listOf(UnitSpawn("u2", "p2", "tank", Position(110.0, 110.0), 90.0)),
            ),
        )).context
        traceObservation("M06", "initial", context.observe())
        assertIs<ActionResult.Accepted>(context.submit(MoveAction("a1", "p2", "u2", Position(250.0, 110.0), context.world)))
        traceObservation("M06", "step", context.advance(Step(0, 1.0, "sim-tick")))
        val before = context.observe().units.single()

        val move = assertIs<ActionResult.Rejected>(context.submit(MoveAction("a2", "p1", "u2", Position(180.0, 100.0), context.world)))
        val stop = assertIs<ActionResult.Rejected>(context.submit(StopAction("a3", "p1", "u2", context.world)))
        assertEquals("not-owner", move.error.code)
        assertEquals("not-owner", stop.error.code)
        assertEquals(before, context.observe().units.single())
        traceObservation("M06", "rejected", context.observe())
        println("M06 before=$before move=$move stop=$stop after=${context.observe().units.single()}")
    }
}
