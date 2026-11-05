package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

internal object HeavyMechFactoryScene {
    fun scenario(format: String = "json"): Scenario {
        val root = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val loaded = ContentLoader.loadContentPacks(listOf(root.resolve("heavy-mech-production/$format/production-package")))
        val content = assertIs<ContentResult.Success<LoadedContentSet>>(loaded, loaded.toString()).value
        return HeavyMechTestScene.scenario().copy(scenarioId = "heavy-factory-upgrade",
            unitDefinitions = content.units,
            units = listOf(UnitSpawn("factory", "p1", "mechFactory", Position(270.0, 150.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("factory" to 8L), 9L),
            economyContract = EconomyContract(mapOf("p1" to 16000L, "p2" to 0L)))
    }
}

class HeavyMechFactoryUpgradeTest {
    @Test fun cancelling_mixed_work_refunds_only_the_matching_items_and_keeps_the_earlier_product() {
        val context = HeavyMechTestScene.open(HeavyMechFactoryScene.scenario())
        try {
            for (action in listOf(
                ProduceAction("first-builder", "p1", "factory", "builder", "c".repeat(32), context.world),
                UpgradeAction("upgrade", "p1", "factory", "upgrade_t2", context.world),
                ProduceAction("second-builder", "p1", "factory", "builder", "f".repeat(32), context.world),
                ProduceAction("mech", "p1", "factory", "mechGun", "d".repeat(32), context.world)))
                assertIs<ActionResult.Accepted>(context.submit(action))
            assertEquals(10400L, context.observePlayer(context.world, "p1").balance)
            assertEquals("duplicate-upgrade", assertIs<ActionResult.Rejected>(context.submit(
                UpgradeAction("duplicate", "p1", "factory", "upgrade_t2", context.world))).error.code)
            for (q in 0L..99L) context.advance(Step(q, 1.0, "sim-tick"))
            val before = context.observe()
            assertIs<ActionResult.Accepted>(context.submit(CancelProductionAction("cancel-builder", "p1", "factory", "builder", context.world)))
            assertEquals(10900L, context.observePlayer(context.world, "p1").balance)
            val afterProduct = context.observe().economy!!.queuedProduction.getValue("factory")
            assertEquals(listOf("c".repeat(32), "d".repeat(32)), afterProduct.map { it.unitNonce })
            assertEquals(before.economy!!.queuedProduction.getValue("factory").first(), afterProduct.first())
            assertIs<ActionResult.Accepted>(context.submit(CancelUpgradeAction("cancel-upgrade", "p1", "factory", "upgrade_t2", context.world)))
            assertEquals(14900L, context.observePlayer(context.world, "p1").balance)
            assertEquals("empty-upgrade-queue", assertIs<ActionResult.Rejected>(context.submit(
                CancelUpgradeAction("cancel-empty", "p1", "factory", "upgrade_t2", context.world))).error.code)
            assertEquals(14900L, context.observePlayer(context.world, "p1").balance)
            assertTrue(context.observe().upgrades!!.queues["factory"].orEmpty().isEmpty())
            assertEquals(1, before.upgrades!!.queues.getValue("factory").size, "Earlier mixed queue snapshots remain stable")
            for (q in 100L..1500L) context.advance(Step(q, 1.0, "sim-tick"))
            val units = context.observe().units
            assertTrue(units.any { it.unitId == "produced:p1:${"c".repeat(32)}" })
            assertTrue(units.any { it.unitId == "produced:p1:${"d".repeat(32)}" })
            assertTrue(units.none { it.unitId == "produced:p1:${"f".repeat(32)}" })
            assertEquals("mechFactory", units.single { it.unitId == "factory" }.typeId)
            assertEquals(14900L, context.observePlayer(context.world, "p1").balance)
        } finally { context.close() }
    }

    @Test fun production_and_upgrade_share_one_fifo_head_in_both_enqueue_orders() {
        for (productionFirst in listOf(true, false)) {
            val context = HeavyMechTestScene.open(HeavyMechFactoryScene.scenario())
            try {
                fun builder() = assertIs<ActionResult.Accepted>(context.submit(ProduceAction("builder", "p1", "factory", "builder", "c".repeat(32), context.world)))
                fun upgrade() = assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("upgrade", "p1", "factory", "upgrade_t2", context.world)))
                if (productionFirst) { builder(); upgrade() } else { upgrade(); builder() }
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("mech", "p1", "factory", "mechGun", "d".repeat(32), context.world)))
                assertEquals(10900L, context.observePlayer(context.world, "p1").balance)
                assertIs<ActionResult.Rejected>(context.submit(ProduceAction("too-early", "p1", "factory", "mechHeavyMissile", "e".repeat(32), context.world)))
                val first = context.advance(Step(0, 1.0, "sim-tick"))
                assertEquals(if (productionFirst) 0f else .0004f, first.upgrades!!.queues.getValue("factory").single().progress)
                assertEquals(if (productionFirst) .002f else 0f, first.economy!!.queuedProduction.getValue("factory").first().progress)
                assertEquals(0f, first.economy!!.queuedProduction.getValue("factory")[1].progress)
                var sawUpgrade = false
                var sawBuilder = false
                var sawMech = false
                for (q in 1L..4500L) {
                    val observed = context.advance(Step(q, 1.0, "sim-tick"))
                    if (!sawBuilder && observed.units.any { it.unitId == "produced:p1:${"c".repeat(32)}" }) {
                        sawBuilder = true
                        if (productionFirst) {
                            assertEquals("mechFactory", observed.units.single { it.unitId == "factory" }.typeId)
                            assertEquals(0f, observed.upgrades!!.queues.getValue("factory").single().progress,
                                "The completion tick must not advance the following upgrade")
                        } else assertTrue(sawUpgrade)
                    }
                    if (observed.events.any { it.kind == "upgrade-completed" }) {
                        sawUpgrade = true
                        assertEquals(0f, observed.economy!!.queuedProduction.getValue("factory").first().progress,
                            "The completion tick must not advance the following product")
                        if (productionFirst) assertTrue(sawBuilder)
                    }
                    if (observed.units.any { it.unitId == "produced:p1:${"d".repeat(32)}" }) { sawMech = true; break }
                }
                assertTrue(sawBuilder && sawUpgrade && sawMech, "productionFirst=$productionFirst")
                assertEquals(10900L, context.observePlayer(context.world, "p1").balance)
            } finally { context.close() }
        }
    }

    @Test fun a_paid_t1_upgrade_completes_as_the_same_selected_factory_with_t2_products() {
        val context = HeavyMechTestScene.open(HeavyMechFactoryScene.scenario())
        try {
            assertIs<ActionResult.Accepted>(context.submit(SelectAction("select", "p1", "factory", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("upgrade", "p1", "factory", "upgrade_t2", context.world)))
            assertEquals(12000L, context.observePlayer(context.world, "p1").balance)
            var completed = false
            for (q in 0L..2600L) {
                val observed = context.advance(Step(q, 1.0, "sim-tick"))
                if (observed.events.any { it.kind == "upgrade-completed" }) { completed = true; break }
            }
            assertTrue(completed)
            val unit = context.observe().units.single()
            assertEquals("factory", unit.unitId)
            assertEquals("mechFactoryT2", unit.typeId)
            assertEquals("p1", unit.ownerPlayerId)
            assertEquals(Position(270.0, 150.0), unit.position)
            assertEquals(3100.0, unit.health)
            assertEquals(12000L, context.observePlayer(context.world, "p1").balance)
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("heavy", "p1", "factory", "mechHeavyMissile", "b".repeat(32), context.world)))
            assertEquals(3500L, context.observePlayer(context.world, "p1").balance)
        } finally { context.close() }
    }
}
