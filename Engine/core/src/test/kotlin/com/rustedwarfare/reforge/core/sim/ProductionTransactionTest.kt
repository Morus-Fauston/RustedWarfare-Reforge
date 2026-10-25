package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.UnitDefinition
import kotlin.test.*

class ProductionTransactionTest {
    @Test fun same_step_real_completion_survives_factory_death_and_only_the_pending_tail_is_refunded() {
        val base = BuildingTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        // Only the production rate changes to isolate the S2b/S3 ordering. The factory still
        // takes real ordinary 25-damage hits from full 1200 HP; the normal .002 chain is separate.
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement, original.retainedUnconsumed,
            original.fogOfWarSightRange, original.combat, original.durability, original.building, original.production,
            original.build!!.copy(rate = 1f), original.directHitTargetKind)
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (tank.id to tank),
            units = listOf(base.units.first().copy(ownerPlayerId = "p2", position = Position(190.0, 210.0)),
                base.units.single { it.unitId == "f" }.copy(position = Position(312.0, 210.0))),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "f" to 10L), 11L))
        val oracle = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        val actual = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            for (c in listOf(oracle, actual)) assertIs<ActionResult.Accepted>(c.submit(
                AttackAction("damage", "p2", "a", "f", c.world)))
            var deathStep = 0L
            while (oracle.observe().units.single { it.unitId == "f" }.alive && deathStep < 4000L) {
                oracle.advance(Step(deathStep++, 1.0, "sim-tick"))
            }
            assertFalse(oracle.observe().units.single { it.unitId == "f" }.alive)
            deathStep--
            for (step in 0L until deathStep) actual.advance(Step(step, 1.0, "sim-tick"))
            assertEquals(25.0, actual.observe().units.single { it.unitId == "f" }.health)
            for ((index, nonce) in listOf("0".repeat(32), "f".repeat(32)).withIndex()) {
                assertIs<ActionResult.Accepted>(actual.submit(ProduceAction("produce-$index", "p1", "f", tank.id, nonce, actual.world)))
            }
            assertEquals(1050L, actual.observePlayer(actual.world, "p1").balance)
            val ended = actual.advance(Step(deathStep, 1.0, "sim-tick"))
            assertFalse(ended.units.single { it.unitId == "f" }.alive)
            val born = ended.units.single { it.unitId.startsWith("produced:") }
            assertEquals("produced:p1:${"0".repeat(32)}", born.unitId)
            assertTrue(born.alive)
            assertEquals(210.0, born.health)
            assertEquals(30f, ended.production!!.units.single().birthRemaining)
            assertEquals(1400L, ended.economy!!.balances["p1"])
            assertTrue(ended.economy.queuedProduction.getValue("f").isEmpty())
            assertEquals(1, ended.events.count { it.kind == "production-refunded" })
            assertTrue(actual.observePlayer(actual.world, "p1").factoryQueues.isEmpty())
            for (step in deathStep + 1..deathStep + 100) actual.advance(Step(step, 1.0, "sim-tick"))
            assertEquals(1400L, actual.observePlayer(actual.world, "p1").balance)
            assertEquals(1, actual.observe().production!!.units.size)
            assertTrue(actual.observe().events.none { it.kind == "production-refunded" })
            assertIs<ActionResult.Accepted>(actual.submit(MoveAction("former-factory", "p1", born.unitId,
                Position(312.0, 210.0), actual.world)))
            assertEquals(Position(312.0, 210.0), actual.observe().units.single { it.unitId == born.unitId }.moveTarget)
        } finally { oracle.close(); actual.close() }
    }

    @Test fun a_real_birth_then_old_enemy_shot_overflow_restores_s1_payment_and_every_public_snapshot() {
        val base = BuildingTestContent.scenario()
        // Before birth the factory is just outside range. The new armed tank is inside range,
        // so the later old enemy can acquire it in the same S2b without updating the newborn.
        val scene = base.copy(players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(base.units.single { it.unitId == "f" },
                UnitSpawn("z", "p2", "c_tank", Position(439.96, 215.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("f" to 10L, "z" to 9L), Long.MAX_VALUE - 1))
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context }
        val first = "0".repeat(32)
        val second = "f".repeat(32)
        try {
            for (c in contexts) {
                c.enablePathDiagnostics(c.world)
                assertIs<ActionResult.Accepted>(c.submit(ProduceAction("first", "p1", "f", "c_tank", first, c.world)))
                repeat(500) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                assertTrue(c.observe().projectiles.isEmpty())
                assertIs<ActionResult.Accepted>(c.submit(ProduceAction("scheduled", "p1", "f", "c_tank", second, c.world, 500)))
            }
            val failed = contexts[0]
            val old = failed.observe()
            val oldPlayers = listOf("p1", "p2").map { failed.observePlayer(failed.world, it) }
            assertEquals(1400L, old.economy!!.balances["p1"])
            assertEquals(0.9999942183494568f, old.economy.queuedProduction.getValue("f").single().progress)
            assertFailsWith<ArithmeticException> { failed.advance(Step(500, 1.0, "sim-tick")) }
            assertSame(old, failed.observe())
            oldPlayers.forEach { assertSame(it, failed.observePlayer(failed.world, it.playerId)) }
            assertEquals(0L, old.production!!.lastBirthSerial)
            assertTrue(old.production.units.isEmpty())
            assertEquals(Long.MAX_VALUE - 1, old.combat!!.nextObjectId)
            assertTrue(failed.drainPathDiagnostics(failed.world).isEmpty())
            // This real cancel removes the old nearly completed head. S1's original scheduled
            // payment must still run exactly once on retry, as in the never-failed control.
            for (c in contexts) {
                assertIs<ActionResult.Accepted>(c.submit(CancelProductionAction("cancel", "p1", "f", "c_tank", c.world)))
                c.advance(Step(500, 1.0, "sim-tick"))
            }
            val expected = contexts[1].observe()
            val actual = failed.observe()
            assertEquals(expected.units, actual.units)
            assertEquals(expected.events, actual.events)
            assertEquals(expected.actionResults, actual.actionResults)
            assertEquals(expected.combat, actual.combat)
            assertEquals(expected.economy!!.balances, actual.economy!!.balances)
            assertEquals(expected.economy.queuedProduction, actual.economy.queuedProduction)
            assertEquals(1400L, actual.economy.balances["p1"])
            assertEquals(listOf(second), actual.economy.queuedProduction.getValue("f").map { it.unitNonce })
            assertEquals(.002f, actual.economy.queuedProduction.getValue("f").single().progress)
            assertEquals(0L, actual.production!!.lastBirthSerial)
            assertTrue(actual.production.units.isEmpty())
            assertEquals(Long.MAX_VALUE - 1, actual.combat!!.nextObjectId)
            for (c in contexts) {
                assertEquals("duplicate-unit-nonce", assertIs<ActionResult.Rejected>(c.submit(
                    ProduceAction("reuse", "p1", "f", "c_tank", first, c.world))).error.code)
                assertIs<ActionResult.Accepted>(c.submit(MoveAction("leave", "p2", "z", Position(550.0, 215.0), c.world)))
                for (step in 501L..1000L) c.advance(Step(step, 1.0, "sim-tick"))
            }
            val born = failed.observe().production!!.units.single()
            assertEquals("produced:p1:$second", born.unitId)
            assertEquals(1L, failed.observe().production!!.lastBirthSerial)
            assertEquals(Long.MAX_VALUE - 1, born.objectId)
            assertEquals(Long.MAX_VALUE, failed.observe().combat!!.nextObjectId)
            assertEquals(contexts[1].observe().units, failed.observe().units)
            assertEquals(contexts[1].observe().production!!.units, failed.observe().production!!.units)
            assertEquals(0L, old.production.lastBirthSerial)
            assertEquals(1400L, old.economy.balances["p1"])
        } finally { contexts.forEach { it.close() } }
    }
}
