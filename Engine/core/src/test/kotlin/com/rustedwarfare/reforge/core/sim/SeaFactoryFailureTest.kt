package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class SeaFactoryFailureTest {
    private fun scene(map: MapDefinition, tier: String, money: Long, enemy: Boolean = false): Scenario {
        val base = SeaFactoryScene.scenario(map, tier = tier)
        val spawns = base.units + if (enemy) listOf(UnitSpawn("enemy", "p2", "heavyBattleship", Position(650.0, 150.0), 270.0)) else emptyList()
        return base.copy(players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)), units = spawns,
            combatContract = CombatContract(12345, -1, spawns.mapIndexed { i, spawn -> spawn.unitId to i + 8L }.toMap(), spawns.size + 8L),
            economyContract = EconomyContract(mapOf("p1" to money, "p2" to 0L)))
    }

    @Test fun insufficient_money_and_last_item_cancellation_preserve_the_typed_queue_nonce_and_owner_boundaries() = WaterTestScene.withMap { map ->
        for ((tier, cost) in listOf("seaFactory" to 2000L, "seaFactoryT2" to 6000L)) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(map, tier, cost - 1))).context
            try {
                val result = if (tier == "seaFactory") context.submit(UpgradeAction("poor", "p1", "factory", "upgrade_t2", context.world))
                    else context.submit(ProduceAction("poor", "p1", "factory", "heavyBattleship", "a".repeat(32), context.world))
                assertEquals("insufficient-funds", assertIs<ActionResult.Rejected>(result).error.code)
                assertEquals(cost - 1, context.observePlayer(context.world, "p1").balance)
                assertTrue(context.observe().economy!!.queuedProduction.getValue("factory").isEmpty())
                assertTrue(context.observe().upgrades?.queues?.get("factory").orEmpty().isEmpty())
            } finally { context.close() }
        }
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(map, "seaFactoryT2", 12000L))).context
        try {
            val other = context.observePlayer(context.world, "p2")
            for (nonce in listOf("a", "b")) assertIs<ActionResult.Accepted>(context.submit(
                ProduceAction("pay-$nonce", "p1", "factory", "heavyBattleship", nonce.repeat(32), context.world)))
            val paid = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(0L, context.observePlayer(context.world, "p1").balance)
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(context.submit(
                ProduceAction("foreign", "p2", "factory", "wrong", "bad", context.world))).error.code)
            assertTrue(context.observePlayer(context.world, "p2").factoryQueues.isEmpty())
            assertEquals(other.balance, context.observePlayer(context.world, "p2").balance)
            assertIs<ActionResult.Accepted>(context.submit(CancelProductionAction("cancel-last", "p1", "factory", "heavyBattleship", context.world)))
            val kept = context.observe().economy!!.queuedProduction.getValue("factory")
            assertEquals(listOf("a".repeat(32)), kept.map { it.unitNonce })
            assertEquals(paid.economy!!.queuedProduction.getValue("factory").first(), kept.single())
            assertEquals(6000L, context.observePlayer(context.world, "p1").balance)
            assertEquals("duplicate-unit-nonce", assertIs<ActionResult.Rejected>(context.submit(
                ProduceAction("duplicate", "p1", "factory", "heavyBattleship", "a".repeat(32), context.world))).error.code)
            assertIs<ActionResult.Accepted>(context.submit(CancelProductionAction("cancel-first", "p1", "factory", "heavyBattleship", context.world)))
            assertEquals(12000L, context.observePlayer(context.world, "p1").balance)
            assertEquals("empty-production-queue", assertIs<ActionResult.Rejected>(context.submit(
                CancelProductionAction("empty", "p1", "factory", "heavyBattleship", context.world))).error.code)
            assertEquals(2, paid.economy.queuedProduction.getValue("factory").size)
            assertEquals(0L, paid.economy.balances.getValue("p1"))
        } finally { context.close() }
    }

    @Test fun real_shell_death_refunds_the_pending_sea_upgrade_or_both_products_once_and_never_completes_them() = WaterTestScene.withMap { map ->
        for (tier in listOf("seaFactory", "seaFactoryT2")) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(map, tier, 18000L, enemy = true))).context
            try {
                if (tier == "seaFactory") assertIs<ActionResult.Accepted>(context.submit(
                    UpgradeAction("upgrade", "p1", "factory", "upgrade_t2", context.world)))
                else for (nonce in listOf("a", "b")) assertIs<ActionResult.Accepted>(context.submit(
                    ProduceAction("product-$nonce", "p1", "factory", "heavyBattleship", nonce.repeat(32), context.world)))
                val paid = context.observe()
                var q = 0L
                while (context.observe().units.single { it.unitId == "factory" }.alive && q < 1200L)
                    context.advance(Step(q++, 1.0, "sim-tick"))
                val death = context.observe()
                assertFalse(death.units.single { it.unitId == "factory" }.alive)
                assertEquals(tier, death.units.single { it.unitId == "factory" }.typeId)
                assertEquals(18000L, context.observePlayer(context.world, "p1").balance)
                val refund = if (tier == "seaFactory") "upgrade-refunded" else "production-refunded"
                assertEquals(1, death.events.count { it.kind == refund && it.unitId == "factory" })
                assertTrue(death.economy!!.queuedProduction.getValue("factory").isEmpty())
                assertTrue(death.upgrades?.queues?.get("factory").orEmpty().isEmpty())
                assertTrue(death.units.none { it.unitId.startsWith("produced:") })
                val later = if (tier == "seaFactory") context.submit(UpgradeAction("later", "p1", "factory", "upgrade_t2", context.world))
                    else context.submit(ProduceAction("later", "p1", "factory", "heavyBattleship", "c".repeat(32), context.world))
                assertEquals("inactive-unit", assertIs<ActionResult.Rejected>(later).error.code)
                repeat(2700) {
                    val frame = context.advance(Step(q++, 1.0, "sim-tick"))
                    assertTrue(frame.events.none { it.kind == refund && it.unitId == "factory" })
                }
                assertEquals(18000L, context.observePlayer(context.world, "p1").balance)
                assertEquals(if (tier == "seaFactory") 16000L else 6000L, paid.economy!!.balances.getValue("p1"))
                assertTrue(context.observe().units.none { it.unitId.startsWith("produced:") })
            } finally { context.close() }
        }
    }
}
