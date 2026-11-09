package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class AmphibiousFactoryFailureTest {
    private fun scene(format: String, money: Long = 10000) = AmphibiousJetScene.scenario(format).copy(
        units = listOf(UnitSpawn("factory", "p1", "airFactory", Position(270.0, 150.0), 0.0)),
        combatContract = CombatContract(12345, -1, mapOf("factory" to 8L), 9L),
        economyContract = EconomyContract(mapOf("p1" to money, "p2" to 0L)))

    @Test fun three_format_t2_upgrade_and_jet_queue_failures_preserve_payment_nonce_and_owner_boundaries() {
        for (format in listOf("ini", "json", "hjson")) {
            val poor = AmphibiousJetScene.open(scene(format, 1499))
            try {
                assertEquals("insufficient-funds", assertIs<ActionResult.Rejected>(poor.submit(
                    UpgradeAction("poor", "p1", "factory", "upgrade_t2", poor.world))).error.code)
                assertEquals(1499L, poor.observePlayer(poor.world,"p1").balance)
            } finally { poor.close() }
            val context = AmphibiousJetScene.open(scene(format))
            try {
                val world = context.world
                fun balance() = context.observePlayer(world,"p1").balance
                assertEquals("unsupported-production-type", assertIs<ActionResult.Rejected>(context.submit(
                    ProduceAction("early", "p1", "factory", "amphibiousJet", "a".repeat(32), world))).error.code)
                assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("queue", "p1", "factory", "upgrade_t2", world)))
                assertEquals(8500L,balance())
                assertEquals("duplicate-upgrade", assertIs<ActionResult.Rejected>(context.submit(
                    UpgradeAction("twice", "p1", "factory", "upgrade_t2", world))).error.code)
                context.advance(Step(0,1.0,"sim-tick"))
                assertIs<ActionResult.Accepted>(context.submit(CancelUpgradeAction("cancel", "p1", "factory", "upgrade_t2", world)))
                assertEquals(10000L,balance())
                assertEquals("airFactory",context.observe().units.single().typeId)
                assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("again", "p1", "factory", "upgrade_t2", world)))
                var step=1L
                while(context.observe().units.single().typeId!="airFactoryT2" && step<2700)
                    context.advance(Step(step++,1.0,"sim-tick"))
                assertEquals("airFactoryT2",context.observe().units.single().typeId)
                assertEquals(8500L,balance())
                assertEquals("invalid-unit-nonce", assertIs<ActionResult.Rejected>(context.submit(
                    ProduceAction("bad", "p1", "factory", "amphibiousJet", "bad", world))).error.code)
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("jet", "p1", "factory", "amphibiousJet", "b".repeat(32), world)))
                assertEquals(6500L,balance())
                assertEquals("not-owner", assertIs<ActionResult.Rejected>(context.submit(
                    ProduceAction("foreign", "p2", "factory", "wrong", "bad", world))).error.code)
                repeat(10){context.advance(Step(step++,1.0,"sim-tick"))}
                assertIs<ActionResult.Accepted>(context.submit(CancelProductionAction("refund", "p1", "factory", "amphibiousJet", world)))
                assertEquals(8500L,balance())
                assertEquals("duplicate-unit-nonce", assertIs<ActionResult.Rejected>(context.submit(
                    ProduceAction("reused", "p1", "factory", "amphibiousJet", "b".repeat(32), world))).error.code)
                assertTrue(context.observePlayer(world,"p2").factoryQueues.isEmpty())
                assertEquals(listOf("airFactoryT2"),context.observe().units.map { it.typeId })
            } finally { context.close() }
        }
    }
}
