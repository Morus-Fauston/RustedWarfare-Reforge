package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class HelicopterFactoryFailureTest {
    private fun scene(money: Long = 3000L, enemy: Boolean = false): Scenario {
        val base = HelicopterFactoryScene.scenario()
        val definitions = if (enemy) base.unitDefinitions +
            ("c_tank" to CombatTestContent.scenario().unitDefinitions.getValue("c_tank")) else base.unitDefinitions
        val spawns = listOf(UnitSpawn("factory", "p1", "airFactory", Position(270.0, 150.0), 0.0)) +
            if (enemy) listOf(UnitSpawn("enemy", "p2", "c_tank", Position(390.0, 150.0), 270.0)) else emptyList()
        return base.copy(scenarioId = "p008-air-factory-failures", unitDefinitions = definitions,
            players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)), units = spawns,
            combatContract = CombatContract(12345, -1, spawns.mapIndexed { i, unit -> unit.unitId to 8L + i }.toMap(), 8L + spawns.size),
            economyContract = EconomyContract(mapOf("p1" to money, "p2" to 0L)))
    }

    @Test fun unpaid_or_invalid_build_sites_create_no_air_factory_and_spend_no_money() {
        for ((money, target, expected) in listOf(
            Triple(999L, Position(270.0, 150.0), "construction-insufficient-funds"),
            Triple(3000L, Position(10.0, 150.0), "construction-unavailable"),
            Triple(3000L, Position(270.0, 370.0), "construction-unavailable"))) {
            val base = scene(money)
            val context = HelicopterTestScene.open(base.copy(
                units = listOf(UnitSpawn("worker", "p1", "builder", Position(190.0, 150.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("worker" to 8L), 9L)))
            try {
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "worker", "airFactory", target,
                    "a".repeat(32), context.world)))
                val events = (0L..399L).flatMap { context.advance(Step(it, 1.0, "sim-tick")).events }.map { it.kind }
                assertTrue(expected in events, "$target: $events")
                assertEquals(money, context.observePlayer(context.world, "p1").balance)
                assertEquals(listOf("worker"), context.observe().units.map { it.unitId })
            } finally { context.close() }
        }
    }

    @Test fun queue_failures_cancel_and_duplicate_nonce_preserve_money_and_owner_privacy() {
        val poor = HelicopterTestScene.open(scene(699L))
        try {
            assertEquals("insufficient-funds", assertIs<ActionResult.Rejected>(poor.submit(
                ProduceAction("poor", "p1", "factory", "helicopter", "a".repeat(32), poor.world))).error.code)
            assertEquals(699L, poor.observePlayer(poor.world, "p1").balance)
            assertTrue(poor.observe().economy!!.queuedProduction.getValue("factory").isEmpty())
        } finally { poor.close() }
        val context = HelicopterTestScene.open(scene(1400L))
        try {
            val nonce = "a".repeat(32)
            assertEquals("unsupported-production-type", assertIs<ActionResult.Rejected>(context.submit(
                ProduceAction("wrong-type", "p1", "factory", "builder", nonce, context.world))).error.code)
            assertEquals("invalid-unit-nonce", assertIs<ActionResult.Rejected>(context.submit(
                ProduceAction("wrong-nonce", "p1", "factory", "helicopter", "bad", context.world))).error.code)
            val enemyBefore = context.observePlayer(context.world, "p2")
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("paid", "p1", "factory", "helicopter", nonce, context.world)))
            val paid = context.observePlayer(context.world, "p1")
            assertEquals(700L, paid.balance)
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(context.submit(
                ProduceAction("foreign", "p2", "factory", "wrong", "bad", context.world))).error.code)
            assertTrue(context.observePlayer(context.world, "p2").factoryQueues.isEmpty())
            assertEquals(enemyBefore.balance, context.observePlayer(context.world, "p2").balance)
            assertIs<ActionResult.Accepted>(context.submit(CancelProductionAction("cancel", "p1", "factory", "helicopter", context.world)))
            assertEquals(1400L, context.observePlayer(context.world, "p1").balance)
            assertEquals(700L, paid.balance)
            assertIs<ActionResult.Rejected>(context.submit(CancelProductionAction("cancel-again", "p1", "factory", "helicopter", context.world)))
            assertEquals(1400L, context.observePlayer(context.world, "p1").balance)
            assertEquals("duplicate-unit-nonce", assertIs<ActionResult.Rejected>(context.submit(
                ProduceAction("reused", "p1", "factory", "helicopter", nonce, context.world))).error.code)
        } finally { context.close() }
    }

    @Test fun consecutive_paid_nonces_each_create_one_aircraft_on_the_native_origin_and_reset_on_reopen() {
        val context = HelicopterTestScene.open(scene())
        try {
            for ((index, nonce) in listOf("a".repeat(32), "b".repeat(32)).withIndex())
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("queue-$index", "p1", "factory", "helicopter", nonce, context.world)))
            val births = mutableListOf<UnitObservation>()
            var step = 0L
            while (births.size < 2 && step < 2000) {
                val observation = context.advance(Step(step++, 1.0, "sim-tick"))
                for (event in observation.events.filter { it.kind == "unit-produced" })
                    births += observation.units.single { it.unitId == event.unitId }
            }
            assertEquals(listOf(Position(270.0, 155.0), Position(270.0, 155.0)), births.map { it.position })
            assertEquals(2, births.map { it.unitId }.distinct().size)
            assertEquals(2L, context.observe().production!!.lastBirthSerial)
            assertEquals(1600L, context.observePlayer(context.world, "p1").balance)
            assertTrue(context.observe().economy!!.queuedProduction.getValue("factory").isEmpty())
            val oldWorld = context.world
            context.close()
            assertIs<InitResult.Accepted>(context.reopen(scene()))
            assertEquals(0L, context.observe().production!!.lastBirthSerial)
            assertEquals("stale-world", assertIs<ActionResult.Rejected>(context.submit(
                ProduceAction("stale", "p1", "factory", "helicopter", "a".repeat(32), oldWorld))).error.code)
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("fresh", "p1", "factory", "helicopter", "a".repeat(32), context.world)))
        } finally { context.close() }
    }

    @Test fun the_produced_helicopter_traverses_an_external_ground_wall_without_duplicate_birth_or_charge() {
        val base = scene()
        val map = base.map
        val blocked = MapDefinition(map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight, map.tileSets,
            map.groundGids, BlockingGrid(map.widthTiles, map.heightTiles, BooleanArray(map.widthTiles * map.heightTiles) { it / map.widthTiles == 11 }))
        val context = HelicopterTestScene.open(base.copy(map = blocked))
        try {
            val nonce = "a".repeat(32)
            val id = "produced:p1:$nonce"
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("produce", "p1", "factory", "helicopter", nonce, context.world)))
            var step = 0L
            repeat(1200) { context.advance(Step(step++, 1.0, "sim-tick")) }
            assertTrue(context.observe().units.single { it.unitId == id }.position.y > 220.0)
            assertEquals(0f, context.observe().production!!.units.single { it.unitId == id }.birthRemaining)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("cross-wall", "p1", id, Position(410.0, 300.0), context.world)))
            repeat(450) { context.advance(Step(step++, 1.0, "sim-tick")) }
            val crossed = context.observe().units.single { it.unitId == id }
            assertTrue(crossed.position.x > 390.0 && crossed.position.y > 280.0)
            assertEquals(1L, context.observe().production!!.lastBirthSerial)
            assertEquals(2300L, context.observePlayer(context.world, "p1").balance)
            assertTrue(context.observe().economy!!.queuedProduction.getValue("factory").isEmpty())
        } finally { context.close() }
    }

    @Test fun actual_weapon_death_refunds_both_pending_helicopters_once() {
        val context = HelicopterTestScene.open(scene(enemy = true))
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack-factory", "p2", "enemy", "factory", context.world)))
            var step = 0L
            while (context.observe().units.single { it.unitId == "factory" }.health!! > 29.0 && step < 4000)
                context.advance(Step(step++, 1.0, "sim-tick"))
            val factory = context.observe().units.single { it.unitId == "factory" }
            assertTrue(factory.alive && factory.health!! <= 29.0)
            for ((index, nonce) in listOf("a".repeat(32), "b".repeat(32)).withIndex())
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("pending-$index", "p1", "factory", "helicopter", nonce, context.world)))
            assertEquals(1600L, context.observePlayer(context.world, "p1").balance)
            while (context.observe().units.single { it.unitId == "factory" }.alive && step < 4200)
                context.advance(Step(step++, 1.0, "sim-tick"))
            assertFalse(context.observe().units.single { it.unitId == "factory" }.alive)
            assertEquals(3000L, context.observePlayer(context.world, "p1").balance)
            assertTrue(context.observe().economy!!.queuedProduction.getValue("factory").isEmpty())
            assertTrue(context.observe().units.none { it.unitId.startsWith("produced:") })
            repeat(10) { context.advance(Step(step++, 1.0, "sim-tick")) }
            assertEquals(3000L, context.observePlayer(context.world, "p1").balance)
            assertIs<ActionResult.Rejected>(context.submit(ProduceAction("dead", "p1", "factory", "helicopter", "c".repeat(32), context.world)))
        } finally { context.close() }
    }
}
