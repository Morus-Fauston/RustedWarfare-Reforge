package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class ProductionCommandTest {
    private fun open(money: Long = 1750L) = assertIs<InitResult.Accepted>(SimulationContext.initialize(
        BuildingTestContent.scenario().copy(economyContract = EconomyContract(mapOf("p1" to money, "p2" to 0L))))).context
    private fun nonce(value: Int) = value.toString(16).padStart(32, '0')
    private fun produce(c: SimulationContext, id: String, n: Int, at: Long? = null) =
        c.submit(ProduceAction(id, "p1", "f", "c_tank", nonce(n), c.world, at))
    private fun cancel(c: SimulationContext, id: String, at: Long? = null) =
        c.submit(CancelProductionAction(id, "p1", "f", "c_tank", c.world, at))

    @Test fun real_commands_pay_once_cancel_the_tail_and_keep_published_snapshots_frozen() {
        val c = open()
        try {
            val initial = c.observePlayer(c.world, "p1")
            assertIs<ActionResult.Accepted>(produce(c, "first", 1))
            val first = c.observePlayer(c.world, "p1")
            assertEquals(1400L, first.balance)
            assertEquals(listOf(ProductionQueueItem("c_tank", 0f)), first.factoryQueues.single().items)
            assertEquals("f", first.factoryQueues.single().factoryId)
            assertIs<ActionResult.Accepted>(produce(c, "second", 2))
            assertEquals(1050L, c.observePlayer(c.world, "p1").balance)
            assertEquals("duplicate-action", assertIs<ActionResult.Rejected>(produce(c, "second", 3)).error.code)
            assertEquals("duplicate-unit-nonce", assertIs<ActionResult.Rejected>(produce(c, "duplicate", 1)).error.code)
            assertEquals(1, c.observe().actionResults.count { it.actionId == "duplicate" })
            assertIs<ActionResult.Accepted>(c.submit(StopAction("stop", "p1", "f", c.world)))
            assertEquals(2, c.observePlayer(c.world, "p1").factoryQueues.single().items.size)
            assertIs<ActionResult.Accepted>(cancel(c, "cancel-tail"))
            val host = c.observe()
            assertEquals(listOf(nonce(1)), host.economy!!.queuedProduction.getValue("f").map { it.unitNonce })
            assertEquals(1400L, c.observePlayer(c.world, "p1").balance)
            assertEquals("duplicate-unit-nonce", assertIs<ActionResult.Rejected>(produce(c, "cancelled-nonce", 2)).error.code)
            assertIs<ActionResult.Accepted>(cancel(c, "cancel-head"))
            assertEquals("empty-production-queue", assertIs<ActionResult.Rejected>(cancel(c, "empty")).error.code)
            assertEquals(1750L, c.observePlayer(c.world, "p1").balance)
            assertEquals(1750L, initial.balance)
            assertTrue(initial.factoryQueues.single().items.isEmpty())
            assertEquals(1400L, first.balance)
            assertEquals(1, first.factoryQueues.single().items.size)
            assertFailsWith<UnsupportedOperationException> {
                (first.factoryQueues.single().items as MutableList<ProductionQueueItem>).clear()
            }
            assertFailsWith<UnsupportedOperationException> {
                (host.economy.queuedProduction as MutableMap<String, List<QueuedProductionObservation>>).clear()
            }
        } finally { c.close() }
    }

    @Test fun exact_funds_and_scheduled_requests_use_execution_time_money_in_submission_order() {
        val low = open(349L)
        try {
            assertEquals("insufficient-funds", assertIs<ActionResult.Rejected>(produce(low, "low", 1)).error.code)
            assertEquals(349L, low.observePlayer(low.world, "p1").balance)
        } finally { low.close() }
        val c = open(350L)
        try {
            assertIs<ActionResult.Accepted>(produce(c, "one", 1, 0L))
            assertIs<ActionResult.Accepted>(produce(c, "two", 2, 0L))
            assertEquals(350L, c.observePlayer(c.world, "p1").balance)
            assertTrue(c.observePlayer(c.world, "p1").factoryQueues.single().items.isEmpty())
            val step = c.advance(Step(0, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(step.actionResults.first { it.actionId == "one" })
            assertEquals("insufficient-funds", assertIs<ActionResult.Rejected>(step.actionResults.first { it.actionId == "two" }).error.code)
            assertEquals(0L, c.observePlayer(c.world, "p1").balance)
            assertEquals(1, c.observePlayer(c.world, "p1").factoryQueues.single().items.size)
            assertIs<ActionResult.Accepted>(cancel(c, "restore"))
            assertIs<ActionResult.Accepted>(produce(c, "retry-second-nonce", 2))
        } finally { c.close() }
    }

    @Test fun actor_and_capability_checks_precede_private_production_errors() {
        val c = open()
        try {
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(c.submit(
                ProduceAction("foreign", "p2", "f", "wrong", "bad", c.world))).error.code)
            assertEquals("cannot-produce", assertIs<ActionResult.Rejected>(c.submit(
                ProduceAction("tank", "p1", "a", "wrong", "bad", c.world))).error.code)
            assertEquals("unsupported-production-type", assertIs<ActionResult.Rejected>(c.submit(
                ProduceAction("type", "p1", "f", "wrong", "bad", c.world))).error.code)
            assertEquals("invalid-unit-nonce", assertIs<ActionResult.Rejected>(c.submit(
                ProduceAction("nonce", "p1", "f", "c_tank", "bad", c.world))).error.code)
            val before = c.observePlayer(c.world, "p2")
            assertIs<ActionResult.Accepted>(produce(c, "owner", 1))
            val after = c.observePlayer(c.world, "p2")
            assertEquals(before.sampleId, after.sampleId)
            assertEquals(before.balance, after.balance)
            assertTrue(after.factoryQueues.isEmpty())
            assertTrue(after.events.none { it.kind.startsWith("production-") })
            assertEquals(before.actionResults, after.actionResults)
            val world = c.world
            c.close()
            assertIs<InitResult.Accepted>(c.reopen(BuildingTestContent.scenario()))
            assertEquals("stale-world", assertIs<ActionResult.Rejected>(c.submit(
                ProduceAction("fresh", "p1", "f", "c_tank", nonce(1), world))).error.code)
            assertIs<ActionResult.Accepted>(produce(c, "fresh", 1))
        } finally { c.close() }
    }

    @Test fun real_factory_death_refunds_pending_money_once_and_scheduled_work_rechecks_life() {
        val base = BuildingTestContent.scenario()
        val scene = base.copy(units = listOf(base.units.first().copy(ownerPlayerId = "p2", position = Position(190.0, 210.0)),
            base.units.first { it.unitId == "f" }.copy(position = Position(312.0, 210.0))),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            combatContract = base.combatContract!!.copy(unitObjectIds = mapOf("a" to 8L, "f" to 10L)))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("damage", "p2", "a", "f", c.world)))
            var step = 0L
            while (c.observe().units.single { it.unitId == "f" }.health!! > 25.0 && step < 4000L)
                c.advance(Step(step++, 1.0, "sim-tick"))
            assertEquals(25.0, c.observe().units.single { it.unitId == "f" }.health)
            assertIs<ActionResult.Accepted>(produce(c, "pending", 1))
            val future = step + 200L
            assertIs<ActionResult.Accepted>(produce(c, "scheduled-after-death", 2, future))
            var death: Observation? = null
            repeat(100) {
                if (death == null) c.advance(Step(step++, 1.0, "sim-tick")).let { sample ->
                    if (!sample.units.single { it.unitId == "f" }.alive) death = sample
                }
            }
            val ended = assertNotNull(death)
            assertEquals(1750L, ended.economy!!.balances["p1"])
            assertTrue(ended.economy.queuedProduction.getValue("f").isEmpty())
            assertEquals(1, ended.events.count { it.kind == "production-refunded" && it.unitId == "f" })
            assertTrue(c.observePlayer(c.world, "p1").factoryQueues.isEmpty())
            while (step <= future) c.advance(Step(step++, 1.0, "sim-tick"))
            val result = c.observe().actionResults.single { it.actionId == "scheduled-after-death" }
            assertEquals("inactive-unit", assertIs<ActionResult.Rejected>(result).error.code)
            assertEquals(1750L, c.observePlayer(c.world, "p1").balance)
            assertTrue(c.observe().events.none { it.kind == "production-refunded" })
        } finally { c.close() }
    }

    @Test fun scheduled_payment_and_nonce_roll_back_with_a_real_later_shot_overflow() {
        val base = BuildingTestContent.scenario()
        val tank = base.unitDefinitions.getValue("c_tank")
        val witness = com.rustedwarfare.reforge.core.content.UnitDefinition("witness", tank.className,
            tank.radius, tank.displayRadius, tank.totalFrames, tank.imagePath, tank.imageScale, tank.movement,
            tank.retainedUnconsumed, tank.fogOfWarSightRange, tank.combat!!.copy(turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (witness.id to witness),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(base.units.first(), base.units.first { it.unitId == "b" }.copy(position = Position(220.0, 210.0)),
                UnitSpawn("c", "p1", witness.id, Position(220.0, 330.0), 180.0),
                base.units.first { it.unitId == "f" }.copy(position = Position(510.0, 110.0))),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "c" to 10L, "f" to 11L), Long.MAX_VALUE - 1))
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context }
        try {
            for (c in contexts) {
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("near", "p2", "b", "a", c.world)))
                assertIs<ActionResult.Accepted>(produce(c, "scheduled-pay", 1, 0L))
            }
            val failed = contexts[0]
            val before = failed.observe()
            val player = failed.observePlayer(failed.world, "p1")
            assertFailsWith<ArithmeticException> { failed.advance(Step(0, 1.0, "sim-tick")) }
            assertSame(before, failed.observe())
            assertSame(player, failed.observePlayer(failed.world, "p1"))
            assertEquals(1750L, before.economy!!.balances["p1"])
            assertTrue(before.economy.queuedProduction.getValue("f").isEmpty())
            for (c in contexts) {
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("side", "p2", "b", "c", c.world)))
                c.advance(Step(0, 1.0, "sim-tick"))
            }
            val actual = failed.observe()
            val expected = contexts[1].observe()
            assertEquals(expected.economy!!.balances, actual.economy!!.balances)
            assertEquals(expected.economy.queuedProduction, actual.economy.queuedProduction)
            assertEquals(1400L, actual.economy.balances["p1"])
            assertEquals(listOf(nonce(1)), actual.economy.queuedProduction.getValue("f").map { it.unitNonce })
            assertEquals(expected.units, actual.units)
            assertEquals(expected.projectiles, actual.projectiles)
            assertEquals(expected.combat, actual.combat)
            assertEquals(expected.events, actual.events)
            assertEquals(expected.actionResults, actual.actionResults)
            assertEquals(1, actual.actionResults.count { it.actionId == "scheduled-pay" })
        } finally { contexts.forEach { it.close() } }
    }
}
