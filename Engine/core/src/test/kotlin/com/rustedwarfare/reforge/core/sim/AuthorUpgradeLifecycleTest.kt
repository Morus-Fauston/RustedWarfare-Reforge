package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class AuthorUpgradeLifecycleTest {
    private fun scene(money: Long = 10000L): Scenario {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val content = assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(
            listOf(fixtures.resolve("author-upgrade/ini/package")))).value
        return BuildingTestContent.scenario().copy(unitDefinitions = content.units,
            units = listOf(UnitSpawn("f", "p1", "spfactory", Position(312.0, 210.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("f" to 8L), 9L),
            economyContract = EconomyContract(mapOf("p1" to money, "p2" to 0L)))
    }

    @Test fun author_upgrade_authority_funds_and_cancel_leave_the_adjacent_paid_production_in_order() {
        val poor = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(2099))).context
        try {
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(poor.submit(
                UpgradeAction("foreign", "p2", "f", "spfactoryt2", poor.world))).error.code)
            assertEquals("unsupported-upgrade", assertIs<ActionResult.Rejected>(poor.submit(
                UpgradeAction("wrong", "p1", "f", "upgrade_t2", poor.world))).error.code)
            assertEquals("insufficient-funds", assertIs<ActionResult.Rejected>(poor.submit(
                UpgradeAction("poor", "p1", "f", "spfactoryt2", poor.world))).error.code)
            assertEquals(2099L, poor.observePlayer(poor.world, "p1").balance)
            assertTrue(poor.observePlayer(poor.world, "p1").upgradeQueues.single().items.isEmpty())
        } finally { poor.close() }
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("first", "p1", "f", "builder", "a".repeat(32), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("middle", "p1", "f", "spfactoryt2", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("last", "p1", "f", "builder", "b".repeat(32), context.world)))
            assertEquals("duplicate-upgrade", assertIs<ActionResult.Rejected>(context.submit(
                UpgradeAction("again", "p1", "f", "spfactoryt2", context.world))).error.code)
            repeat(10) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val old = context.observe()
            assertIs<ActionResult.Accepted>(context.submit(CancelUpgradeAction("cancel", "p1", "f", "spfactoryt2", context.world)))
            assertEquals(9000L, context.observePlayer(context.world, "p1").balance)
            assertEquals(old.economy!!.queuedProduction.getValue("f"), context.observe().economy!!.queuedProduction.getValue("f"))
            assertEquals("empty-upgrade-queue", assertIs<ActionResult.Rejected>(context.submit(
                CancelUpgradeAction("empty", "p1", "f", "spfactoryt2", context.world))).error.code)
            for (step in 10L..1100L) context.advance(Step(step, 1.0, "sim-tick"))
            assertEquals("spfactory", context.observe().units.single { it.unitId == "f" }.typeId)
            assertEquals(2L, context.observe().production!!.lastBirthSerial)
            assertEquals(9000L, context.observePlayer(context.world, "p1").balance)
            assertEquals(listOf("a".repeat(32), "b".repeat(32)), context.observe().units
                .filter { it.unitId.startsWith("produced:") }.map { it.unitId.removePrefix("produced:p1:") })
            assertEquals(6900L, old.economy.balances["p1"])
        } finally { context.close() }
    }

    @Test fun real_factory_death_refunds_the_mixed_queue_once_and_cannot_complete_the_upgrade() {
        val base = scene()
        val input = base.copy(players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = base.units + UnitSpawn("enemy", "p2", "c_tank", Position(430.0, 210.0), 270.0),
            combatContract = CombatContract(12345, -1, mapOf("f" to 8L, "enemy" to 9L), 10L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p2", "enemy", "f", context.world)))
            var step = 0L
            while (context.observe().units.single { it.unitId == "f" }.health!! > 25.0 && step < 5000L)
                context.advance(Step(step++, 1.0, "sim-tick"))
            assertEquals(25.0, context.observe().units.single { it.unitId == "f" }.health)
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("product", "p1", "f", "builder", "a".repeat(32), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("upgrade", "p1", "f", "spfactoryt2", context.world)))
            assertEquals(7400L, context.observePlayer(context.world, "p1").balance)
            while (context.observe().units.single { it.unitId == "f" }.alive && step < 5200L)
                context.advance(Step(step++, 1.0, "sim-tick"))
            val dead = context.observe()
            assertFalse(dead.units.single { it.unitId == "f" }.alive)
            assertEquals("spfactory", dead.units.single { it.unitId == "f" }.typeId)
            assertEquals(1, dead.events.count { it.kind == "upgrade-refunded" })
            assertEquals(1, dead.events.count { it.kind == "production-refunded" })
            assertEquals(10000L, context.observePlayer(context.world, "p1").balance)
            assertEquals(0L, dead.production!!.lastBirthSerial)
            assertIs<ActionResult.Rejected>(context.submit(UpgradeAction("dead", "p1", "f", "spfactoryt2", context.world)))
            repeat(10) { context.advance(Step(step++, 1.0, "sim-tick")) }
            assertEquals(10000L, context.observePlayer(context.world, "p1").balance)
            assertTrue(context.observe().events.none { it.kind == "upgrade-completed" || it.kind.endsWith("refunded") })
        } finally { context.close() }
    }
}
