package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class NavalConstructionChainTest {
    @Test fun a_shore_builder_constructs_t1_upgrades_normally_and_produces_a_ship_with_the_complete_budget_chain() = WaterTestScene.withMap { map ->
        for (format in listOf("ini", "json", "hjson")) {
            val scene = SeaFactoryScene.scenario(map, format).copy(
                units = listOf(UnitSpawn("worker", "p1", "builder", Position(390.0, 150.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("worker" to 8L), 9L),
                economyContract = EconomyContract(mapOf("p1" to 12000L, "p2" to 0L)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                var q = 0L
                val factory = "constructed:p1:${"a".repeat(32)}"
                val ship = "produced:p1:${"b".repeat(32)}"
                fun tick() = context.advance(Step(q++, 1.0, "sim-tick"))
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "worker", "seaFactory",
                    Position(450.0, 150.0), "a".repeat(32), context.world)))
                tick()
                assertEquals(11000L, context.observePlayer(context.world, "p1").balance)
                assertEquals("factory-under-construction", assertIs<ActionResult.Rejected>(context.submit(
                    UpgradeAction("unfinished-upgrade", "p1", factory, "upgrade_t2", context.world))).error.code)
                assertEquals("factory-under-construction", assertIs<ActionResult.Rejected>(context.submit(
                    ProduceAction("unfinished-product", "p1", factory, "heavyBattleship", "b".repeat(32), context.world))).error.code)
                val constructionLimit = q + 1700L
                while (context.observe().units.single { it.unitId == factory }.constructionProgress < 1f && q < constructionLimit) tick()
                assertEquals(1f, context.observe().units.single { it.unitId == factory }.constructionProgress)
                assertIs<ActionResult.Accepted>(context.submit(StopAction("worker-stop", "p1", "worker", context.world)))
                assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("upgrade", "p1", factory, "upgrade_t2", context.world)))
                assertEquals(9000L, context.observePlayer(context.world, "p1").balance)
                val upgradeLimit = q + 2700L
                while (context.observe().units.single { it.unitId == factory }.typeId != "seaFactoryT2" && q < upgradeLimit) tick()
                assertEquals("seaFactoryT2", context.observe().units.single { it.unitId == factory }.typeId)
                assertEquals(1000.0, context.observe().units.single { it.unitId == factory }.health)
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("ship", "p1", factory, "heavyBattleship", "b".repeat(32), context.world)))
                assertEquals(3000L, context.observePlayer(context.world, "p1").balance)
                val productLimit = q + 2600L
                while (context.observe().units.none { it.unitId == ship } && q < productLimit) tick()
                assertEquals(Position(450.0, 170.0), context.observe().units.single { it.unitId == ship }.position)
                repeat(450) { tick() }
                assertTrue(context.observe().units.single { it.unitId == ship }.position.y > 270.0)
                assertEquals(0f, context.observe().production!!.units.single { it.unitId == ship }.birthRemaining)
                assertEquals(3000L, context.observePlayer(context.world, "p1").balance)
            } finally { context.close() }
        }
    }
}
