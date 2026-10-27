package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class WorldIncomeTest {
    // Trusted synthetic factory fixture exercises the accepted economy API independently of native loading.
    private fun scene(initial: Long = 0L, income: IncomeProfile = IncomeProfile(10f, 4.5f),
                      productRate: Float = .002f): Scenario {
        val base = BuildingTestContent.scenario()
        val factory = base.unitDefinitions.getValue("landFactory")
        val earner = UnitDefinition(factory.id, factory.className, factory.radius, factory.displayRadius,
            factory.totalFrames, factory.imagePath, factory.imageScale, factory.movement,
            factory.retainedUnconsumed, factory.fogOfWarSightRange, factory.combat, factory.durability,
            factory.building, factory.production, factory.build, factory.directHitTargetKind, income = income)
        val tank = base.unitDefinitions.getValue("c_tank")
        val product = UnitDefinition(tank.id, tank.className, tank.radius, tank.displayRadius,
            tank.totalFrames, tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed,
            tank.fogOfWarSightRange, tank.combat, build = BuildProfile(500L, productRate))
        return base.copy(unitDefinitions = base.unitDefinitions + mapOf(earner.id to earner, product.id to product),
            units = base.units.filter { it.unitId == "f" },
            combatContract = CombatContract(12345, -1, mapOf("f" to 10L), 11L),
            economyContract = EconomyContract(mapOf("p1" to initial, "p2" to 0L)))
    }

    @Test fun income_preserves_half_credits_at_the_native_ten_tick_boundary() {
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
        try {
            repeat(9) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertEquals(0L, c.observePlayer(c.world, "p1").balance)
            c.advance(Step(9, 1.0, "sim-tick"))
            val first = c.observePlayer(c.world, "p1")
            assertEquals(4.5, first.credits)
            assertEquals(4L, first.balance)
            assertEquals(0.0, c.observePlayer(c.world, "p2").credits)
            assertEquals(mapOf("p1" to 4.5, "p2" to 0.0), c.observe().economy!!.preciseBalances)
            for (step in 10L..19L) c.advance(Step(step, 1.0, "sim-tick"))
            assertEquals(9.0, c.observePlayer(c.world, "p1").credits)
            assertEquals(9L, c.observePlayer(c.world, "p1").balance)
            assertEquals(4.5, first.credits)
        } finally { c.close() }
    }

    @Test fun income_requires_a_finite_supported_profile_and_an_explicit_initial_balance() {
        fun reject(scene: Scenario, code: String) {
            val result = SimulationContext.initialize(scene)
            if (result is InitResult.Accepted) result.context.close()
            assertEquals(code, assertIs<InitResult.Rejected>(result).error.code)
        }
        reject(scene().copy(economyContract = null), "missing-economy-contract")
        reject(scene(1000000000L), "invalid-economy-contract")
        for (income in listOf(IncomeProfile(0f, 4.5f), IncomeProfile(Float.NaN, 4.5f),
            IncomeProfile(10f, -1f), IncomeProfile(10f, Float.POSITIVE_INFINITY))) {
            reject(scene(income = income), "unsupported-income")
        }
        val legacy = assertIs<InitResult.Accepted>(SimulationContext.initialize(BuildingTestContent.scenario().copy(
            economyContract = EconomyContract(mapOf("p1" to Long.MAX_VALUE, "p2" to 0L))))).context
        try {
            assertEquals(Long.MAX_VALUE, legacy.observePlayer(legacy.world, "p1").balance)
            assertNull(legacy.observePlayer(legacy.world, "p1").credits)
            assertNull(legacy.observe().economy!!.preciseBalances)
        } finally { legacy.close() }
    }

    @Test fun payment_and_cancel_use_real_credits_and_income_alone_applies_the_cap() {
        for (initial in listOf(495L, 496L, 999999999L)) {
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(initial,
                income = IncomeProfile(10f, if (initial > 1000L) 1000f else 4.5f)))).context
            try {
                fun produce(id: String, nonce: String) = c.submit(ProduceAction(id, "p1", "f", "c_tank", nonce, c.world))
                if (initial > 1000L) assertIs<ActionResult.Accepted>(produce("pay-before-income", "0".repeat(32)))
                repeat(10) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                if (initial == 495L) {
                    assertEquals(499.5, c.observePlayer(c.world, "p1").credits)
                    val before = c.observe()
                    assertEquals("insufficient-funds", assertIs<ActionResult.Rejected>(produce("poor", "0".repeat(32))).error.code)
                    assertEquals(before.economy!!.preciseBalances, c.observe().economy!!.preciseBalances)
                    assertTrue(c.observePlayer(c.world, "p1").factoryQueues.single().items.isEmpty())
                    for (step in 10L..19L) c.advance(Step(step, 1.0, "sim-tick"))
                    assertIs<ActionResult.Accepted>(produce("enough", "0".repeat(32)))
                    assertEquals(4.0, c.observePlayer(c.world, "p1").credits)
                } else {
                    if (initial == 496L) {
                        assertEquals(500.5, c.observePlayer(c.world, "p1").credits)
                        assertIs<ActionResult.Accepted>(produce("pay-half", "0".repeat(32)))
                        assertEquals(.5, c.observePlayer(c.world, "p1").credits)
                    } else assertEquals(999999999.0, c.observePlayer(c.world, "p1").credits)
                    assertIs<ActionResult.Accepted>(c.submit(CancelProductionAction("cancel", "p1", "f", "c_tank", c.world)))
                    assertEquals(if (initial == 496L) 500.5 else 1000000499.0, c.observePlayer(c.world, "p1").credits)
                    assertEquals("duplicate-unit-nonce", assertIs<ActionResult.Rejected>(produce("reuse", "0".repeat(32))).error.code)
                    if (initial > 1000L) {
                        for (step in 10L..19L) c.advance(Step(step, 1.0, "sim-tick"))
                        assertEquals(999999999.0, c.observePlayer(c.world, "p1").credits)
                    }
                }
            } finally { c.close() }
        }
    }

    @Test fun destroyed_earner_stops_income_and_reopen_resets_its_phase_and_funds() {
        val base = scene(1750L, productRate = 1f)
        val input = base.copy(players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = base.units + UnitSpawn("a", "p2", "c_tank", Position(190.0, 210.0), 90.0),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "f" to 10L), 11L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            val product = "produced:p1:${"0".repeat(32)}"
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("build", "p1", "f", "c_tank", "0".repeat(32), c.world)))
            c.advance(Step(0, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("leave", "p1", product, Position(510.0, 330.0), c.world)))
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("destroy", "p2", "a", "f", c.world)))
            var step = 1L
            while (c.observe().units.single { it.unitId == "f" }.alive && step < 4000) c.advance(Step(step++, 1.0, "sim-tick"))
            assertFalse(c.observe().units.single { it.unitId == "f" }.alive)
            val credits = assertNotNull(c.observePlayer(c.world, "p1").credits)
            repeat(25) { c.advance(Step(step++, 1.0, "sim-tick")) }
            assertEquals(credits, c.observePlayer(c.world, "p1").credits)
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("survivor", "p1", product, Position(500.0, 300.0), c.world)))
            c.close()
            assertIs<InitResult.Accepted>(c.reopen(input))
            repeat(9) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertEquals(1750.0, c.observePlayer(c.world, "p1").credits)
            c.advance(Step(9, 1.0, "sim-tick"))
            assertEquals(1754.5, c.observePlayer(c.world, "p1").credits)
        } finally { c.close() }
    }

    @Test fun late_birth_failure_restores_income_phase_payment_and_public_snapshots_before_same_step_retry() {
        val base = scene(1750L, IncomeProfile(3f, 4.5f))
        val input = base.copy(players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = base.units + UnitSpawn("z", "p2", "c_tank", Position(439.96, 215.0), 270.0),
            combatContract = CombatContract(12345, -1, mapOf("f" to 10L, "z" to 9L), Long.MAX_VALUE - 1))
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context }
        try {
            for (c in contexts) {
                assertIs<ActionResult.Accepted>(c.submit(ProduceAction("first", "p1", "f", "c_tank", "0".repeat(32), c.world)))
                repeat(500) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                assertIs<ActionResult.Accepted>(c.submit(ProduceAction("scheduled", "p1", "f", "c_tank", "f".repeat(32), c.world, 500)))
            }
            val failed = contexts[0]
            val before = failed.observe()
            val player = failed.observePlayer(failed.world, "p1")
            assertEquals(1997.0, player.credits)
            assertFailsWith<ArithmeticException> { failed.advance(Step(500, 1.0, "sim-tick")) }
            assertSame(before, failed.observe())
            assertSame(player, failed.observePlayer(failed.world, "p1"))
            for (c in contexts) {
                assertIs<ActionResult.Accepted>(c.submit(CancelProductionAction("cancel", "p1", "f", "c_tank", c.world)))
                c.advance(Step(500, 1.0, "sim-tick"))
                assertEquals(2001.5, c.observePlayer(c.world, "p1").credits)
            }
            for (step in 501L..512L) {
                contexts.forEach { it.advance(Step(step, 1.0, "sim-tick")) }
                val expected = contexts[1].observe()
                val actual = failed.observe()
                assertEquals(expected.economy!!.preciseBalances, actual.economy!!.preciseBalances)
                assertEquals(expected.economy.queuedProduction, actual.economy.queuedProduction)
                assertEquals(expected.units, actual.units)
                assertEquals(expected.combat, actual.combat)
                assertEquals(expected.actionResults, actual.actionResults)
                assertEquals(expected.events, actual.events)
            }
            assertEquals(2019.5, failed.observePlayer(failed.world, "p1").credits)
            assertEquals(1997.0, before.economy!!.preciseBalances!!.getValue("p1"))
        } finally { contexts.forEach { it.close() } }
    }
}
