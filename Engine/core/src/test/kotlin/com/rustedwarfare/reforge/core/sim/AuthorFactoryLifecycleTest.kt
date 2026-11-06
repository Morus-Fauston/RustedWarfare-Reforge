package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class AuthorFactoryLifecycleTest {
    private fun scene(money: Long = 3000): Scenario {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val definitions = assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(
            listOf(fixtures.resolve("author-production/ini/package")))).value.units
        return BuildingTestContent.scenario().copy(unitDefinitions = definitions,
            units = listOf(UnitSpawn("f", "p1", "spfactory", Position(312.0, 210.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("f" to 8L), 9L),
            economyContract = EconomyContract(mapOf("p1" to money, "p2" to 0L)))
    }

    @Test fun author_factory_rejections_and_cancel_preserve_funds_and_retired_nonces() {
        val poor = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(349))).context
        try {
            assertEquals("insufficient-funds", assertIs<ActionResult.Rejected>(poor.submit(
                ProduceAction("poor", "p1", "f", "c_tank", "a".repeat(32), poor.world))).error.code)
            assertEquals(349L, poor.observePlayer(poor.world, "p1").balance)
            assertTrue(poor.observePlayer(poor.world, "p1").factoryQueues.single().items.isEmpty())
        } finally { poor.close() }
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(1000))).context
        try {
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(context.submit(
                ProduceAction("foreign", "p2", "f", "c_tank", "a".repeat(32), context.world))).error.code)
            assertEquals("unsupported-production-type", assertIs<ActionResult.Rejected>(context.submit(
                ProduceAction("wrong-type", "p1", "f", "spfactory", "a".repeat(32), context.world))).error.code)
            assertEquals("invalid-unit-nonce", assertIs<ActionResult.Rejected>(context.submit(
                ProduceAction("bad-nonce", "p1", "f", "builder", "bad", context.world))).error.code)
            assertTrue(context.observePlayer(context.world, "p2").factoryQueues.isEmpty())
            for ((type, nonce) in listOf("builder" to "a", "c_tank" to "b"))
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("buy-$type", "p1", "f", type,
                    nonce.repeat(32), context.world)))
            assertEquals(150L, context.observePlayer(context.world, "p1").balance)
            assertIs<ActionResult.Accepted>(context.submit(CancelProductionAction("cancel", "p1", "f", "c_tank", context.world)))
            assertEquals(500L, context.observePlayer(context.world, "p1").balance)
            assertEquals(listOf("builder"), context.observePlayer(context.world, "p1").factoryQueues.single().items.map { it.typeId })
            assertEquals("duplicate-unit-nonce", assertIs<ActionResult.Rejected>(context.submit(
                ProduceAction("reuse", "p1", "f", "c_tank", "b".repeat(32), context.world))).error.code)
            assertEquals("empty-production-queue", assertIs<ActionResult.Rejected>(context.submit(
                CancelProductionAction("again", "p1", "f", "c_tank", context.world))).error.code)
            repeat(700) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertEquals(listOf("builder"), context.observe().units.filter { it.unitId.startsWith("produced:") }.map { it.typeId })
            assertEquals(500L, context.observePlayer(context.world, "p1").balance)
        } finally { context.close() }
    }

    @Test fun real_author_factory_death_refunds_only_pending_products_once() {
        val base = scene()
        val input = base.copy(players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = base.units + UnitSpawn("enemy", "p2", "c_tank", Position(430.0, 210.0), 270.0),
            combatContract = CombatContract(12345, -1, mapOf("f" to 8L, "enemy" to 9L), 10L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p2", "enemy", "f", context.world)))
            var step = 0L
            while (context.observe().units.single { it.unitId == "f" }.health!! > 25.0 && step < 5000)
                context.advance(Step(step++, 1.0, "sim-tick"))
            assertEquals(25.0, context.observe().units.single { it.unitId == "f" }.health)
            for ((index, type) in listOf("builder", "c_tank").withIndex())
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("buy-$index", "p1", "f", type,
                    ('a' + index).toString().repeat(32), context.world)))
            assertEquals(2150L, context.observePlayer(context.world, "p1").balance)
            while (context.observe().units.single { it.unitId == "f" }.alive && step < 5200)
                context.advance(Step(step++, 1.0, "sim-tick"))
            assertFalse(context.observe().units.single { it.unitId == "f" }.alive)
            assertEquals(3000L, context.observePlayer(context.world, "p1").balance)
            assertTrue(context.observe().economy!!.queuedProduction.getValue("f").isEmpty())
            assertTrue(context.observe().units.none { it.unitId.startsWith("produced:") })
            repeat(10) { context.advance(Step(step++, 1.0, "sim-tick")) }
            assertEquals(3000L, context.observePlayer(context.world, "p1").balance)
        } finally { context.close() }
    }

    @Test fun an_external_hard_wall_leaves_one_paid_product_and_an_explicit_recovery_move_can_free_it() {
        val base = scene()
        val map = base.map
        val blocked = MapDefinition(map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight,
            map.tileSets, map.groundGids, BlockingGrid(map.widthTiles, map.heightTiles,
                BooleanArray(map.widthTiles * map.heightTiles) { index ->
                    index / map.widthTiles == 14 || map.blocking.isBlocked(index % map.widthTiles, index / map.widthTiles)
                }))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(map = blocked))).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("buy", "p1", "f", "c_tank", "a".repeat(32), context.world)))
            var step = 0L
            repeat(1200) { context.advance(Step(step++, 1.0, "sim-tick")) }
            val product = context.observe().units.single { it.unitId.startsWith("produced:") }
            assertTrue(product.position.y < 280.0, "$product")
            assertEquals(0f, context.observe().production!!.units.single().birthRemaining)
            assertEquals(1L, context.observe().production!!.lastBirthSerial)
            assertEquals(2650L, context.observePlayer(context.world, "p1").balance)
            assertTrue(context.observePlayer(context.world, "p1").factoryQueues.single().items.isEmpty())
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("recover", "p1", product.unitId,
                Position(510.0, 250.0), context.world)))
            repeat(500) { context.advance(Step(step++, 1.0, "sim-tick")) }
            assertTrue(context.observe().units.single { it.unitId == product.unitId }.position.x > 490.0)
            assertEquals(1L, context.observe().production!!.lastBirthSerial)
            assertEquals(2650L, context.observePlayer(context.world, "p1").balance)
        } finally { context.close() }
    }

    @Test fun a_real_post_birth_projectile_failure_restores_the_author_factory_and_same_step_retry_matches_control() {
        val base = scene()
        val input = base.copy(players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            // Parent distance is sqrt(127^2+30^2)>130; newborn at (312,215) is within 130.
            units = base.units + UnitSpawn("z", "p2", "c_tank", Position(439.0, 240.0), 281.14),
            combatContract = CombatContract(12345, -1, mapOf("f" to 8L, "z" to 9L), Long.MAX_VALUE - 1))
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context }
        try {
            for (context in contexts) {
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("first", "p1", "f", "c_tank", "a".repeat(32), context.world)))
                repeat(500) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                assertTrue(context.observe().projectiles.isEmpty())
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("scheduled", "p1", "f", "c_tank", "b".repeat(32), context.world, 500)))
            }
            val failed = contexts[0]
            val old = failed.observe()
            val players = listOf("p1", "p2").map { failed.observePlayer(failed.world, it) }
            assertFailsWith<ArithmeticException> { failed.advance(Step(500, 1.0, "sim-tick")) }
            assertSame(old, failed.observe())
            players.forEach { assertSame(it, failed.observePlayer(failed.world, it.playerId)) }
            assertEquals(0L, old.production!!.lastBirthSerial)
            for (context in contexts) {
                assertIs<ActionResult.Accepted>(context.submit(CancelProductionAction("cancel", "p1", "f", "c_tank", context.world)))
                context.advance(Step(500, 1.0, "sim-tick"))
            }
            val actual = failed.observe()
            val expected = contexts[1].observe()
            assertEquals(expected.units, actual.units)
            assertEquals(expected.events, actual.events)
            assertEquals(expected.actionResults, actual.actionResults)
            assertEquals(expected.combat, actual.combat)
            assertEquals(expected.economy!!.balances, actual.economy!!.balances)
            assertEquals(expected.economy.queuedProduction, actual.economy.queuedProduction)
            assertEquals(2650L, actual.economy.balances["p1"])
            assertEquals(listOf("b".repeat(32)), actual.economy.queuedProduction.getValue("f").map { it.unitNonce })
            for (context in contexts) {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("leave", "p2", "z", Position(550.0, 240.0), context.world)))
                for (step in 501L..1001L) context.advance(Step(step, 1.0, "sim-tick"))
            }
            assertEquals("produced:p1:${"b".repeat(32)}", failed.observe().production!!.units.single().unitId)
            assertEquals(Long.MAX_VALUE - 1, failed.observe().production!!.units.single().objectId)
            assertEquals(contexts[1].observe().units, failed.observe().units)
            assertEquals(contexts[1].observe().production!!.units, failed.observe().production!!.units)
            assertEquals(2650L, failed.observePlayer(failed.world, "p1").balance)
        } finally { contexts.forEach { it.close() } }
    }
}
