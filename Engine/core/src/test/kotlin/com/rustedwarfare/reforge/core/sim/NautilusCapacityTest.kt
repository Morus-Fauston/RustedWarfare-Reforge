package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class NautilusCapacityTest {
    @Test fun exactly_three_slots_still_allow_one_internal_completion_then_the_next_head_waits_at_one_and_cancels_for_the_full_price_once() = WaterTestScene.withMap { map ->
        val c = NautilusScene.open(NautilusScene.scenario(map))
        try {
            for (index in 1..5) assertIs<ActionResult.Accepted>(c.submit(ProduceAction("queue-$index", "p1", "mother",
                "hoverTank", index.toString().padStart(32, '0'), c.world)))
            assertEquals(2750L, c.observePlayer(c.world, "p1").balance)
            var step = 0L
            repeat(750) { c.advance(Step(step++, 1.0, "sim-tick")) }
            assertEquals(3, c.observe().units.single { it.unitId == "mother" }.transport!!.occupiedSlots)
            repeat(250) { c.advance(Step(step++, 1.0, "sim-tick")) }
            val four = c.observe()
            assertEquals(4, four.units.single { it.unitId == "mother" }.transport!!.occupiedSlots)
            assertEquals(4L, four.production!!.lastBirthSerial)
            repeat(260) { c.advance(Step(step++, 1.0, "sim-tick")) }
            val waiting = c.observe()
            assertEquals(5, waiting.units.size)
            assertEquals(4L, waiting.production!!.lastBirthSerial)
            val item = waiting.economy!!.queuedProduction.getValue("mother").single()
            assertEquals(1f, item.progress)
            assertEquals(450L, item.cost)
            assertEquals(.002f, item.rate)
            assertEquals(2750L, c.observePlayer(c.world, "p1").balance)
            assertIs<ActionResult.Accepted>(c.submit(CancelProductionAction("cancel", "p1", "mother", "hoverTank", c.world)))
            assertEquals(3200L, c.observePlayer(c.world, "p1").balance)
            assertIs<ActionResult.Rejected>(c.submit(CancelProductionAction("cancel-again", "p1", "mother", "hoverTank", c.world)))
            assertEquals(3200L, c.observePlayer(c.world, "p1").balance)
            assertTrue(c.observe().economy!!.queuedProduction.getValue("mother").isEmpty())
        } finally { c.close() }
    }
}
