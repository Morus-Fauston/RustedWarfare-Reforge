package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class NautilusTransactionTest {
    @Test fun a_second_birth_overflow_keeps_the_paid_head_nonce_manifest_and_identity_until_the_player_cancels_it() = WaterTestScene.withMap { map ->
        val base = NautilusScene.scenario(map)
        val c = NautilusScene.open(base.copy(combatContract = base.combatContract!!.copy(nextObjectId = Long.MAX_VALUE - 1)))
        try {
            for (index in 1..2) assertIs<ActionResult.Accepted>(c.submit(ProduceAction("queue-$index", "p1", "mother",
                "hoverTank", index.toString().padStart(32, '0'), c.world)))
            var step = 0L
            repeat(499) { c.advance(Step(step++, 1.0, "sim-tick")) }
            val before = c.observe()
            val player = c.observePlayer(c.world, "p1")
            assertEquals(1, before.units.single { it.unitId == "mother" }.transport!!.passengerCount)
            assertEquals(1L, before.production!!.lastBirthSerial)
            assertEquals(4100L, player.balance)
            assertFailsWith<ArithmeticException> { c.advance(Step(step, 1.0, "sim-tick")) }
            assertSame(before, c.observe())
            assertSame(player, c.observePlayer(c.world, "p1"))
            assertFailsWith<ArithmeticException> { c.advance(Step(step, 1.0, "sim-tick")) }
            assertSame(before, c.observe())
            assertIs<ActionResult.Accepted>(c.submit(CancelProductionAction("cancel", "p1", "mother", "hoverTank", c.world)))
            assertEquals(4550L, c.observePlayer(c.world, "p1").balance)
            val after = c.advance(Step(step++, 1.0, "sim-tick"))
            assertEquals(1, after.units.single { it.unitId == "mother" }.transport!!.passengerCount)
            assertEquals(1L, after.production!!.lastBirthSerial)
            assertEquals(2, after.units.size)
            assertIs<ActionResult.Rejected>(c.submit(ProduceAction("reserved-nonce", "p1", "mother", "hoverTank", "2".padStart(32, '0'), c.world)))
            assertEquals(4550L, c.observePlayer(c.world, "p1").balance)
        } finally { c.close() }
    }
}
