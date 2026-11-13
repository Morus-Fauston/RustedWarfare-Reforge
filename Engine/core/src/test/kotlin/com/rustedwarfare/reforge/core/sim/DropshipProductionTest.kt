package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class DropshipProductionTest {
    @Test fun a_tier_two_transport_only_menu_needs_only_its_declared_product_closure() {
        val base = AmphibiousJetScene.scenario()
        val t2 = base.unitDefinitions.getValue("airFactoryT2")
        val factory = UnitDefinition(t2.id, t2.className, t2.radius, t2.displayRadius, t2.totalFrames,
            t2.imagePath, t2.imageScale, t2.movement, t2.retainedUnconsumed, t2.fogOfWarSightRange,
            durability = t2.durability, building = t2.building, build = t2.build, construction = t2.construction,
            production = ProductionProfile(listOf("dropship"), FactoryExitProfile.NATIVE_AIR),
            directHitTargetKind = t2.directHitTargetKind)
        val context = DropshipScene.open(base.copy(
            unitDefinitions = mapOf("airFactoryT2" to factory, "dropship" to DropshipScene.definition()),
            units = listOf(UnitSpawn("factory", "p1", "airFactoryT2", Position(270.0, 150.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("factory" to 8L), 9L),
            economyContract = EconomyContract(mapOf("p1" to 1000L, "p2" to 0L))))
        try {
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("produce", "p1", "factory",
                "dropship", "d".repeat(32), context.world)))
            assertEquals(200L, context.observePlayer(context.world, "p1").balance)
            repeat(1100) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertEquals(500.0, context.observe().units.single { it.typeId == "dropship" }.health)
        } finally { context.close() }
    }

    @Test fun a_paid_air_factory_upgrade_unlocks_the_eight_hundred_credit_transport() {
        val base = AmphibiousJetScene.scenario()
        val t2 = base.unitDefinitions.getValue("airFactoryT2")
        val upgraded = UnitDefinition(t2.id, t2.className, t2.radius, t2.displayRadius, t2.totalFrames,
            t2.imagePath, t2.imageScale, t2.movement, t2.retainedUnconsumed, t2.fogOfWarSightRange,
            durability = t2.durability, building = t2.building, build = t2.build, construction = t2.construction,
            production = ProductionProfile(listOf("helicopter", "amphibiousJet", "dropship"), FactoryExitProfile.NATIVE_AIR),
            directHitTargetKind = t2.directHitTargetKind)
        val context = DropshipScene.open(base.copy(unitDefinitions = base.unitDefinitions +
            ("dropship" to DropshipScene.definition()) +
            ("airFactoryT2" to upgraded)))
        try {
            var step = 0L
            fun until(predicate: (Observation) -> Boolean) {
                while (!predicate(context.observe()) && step < 9000L) context.advance(Step(step++, 1.0, "sim-tick"))
                assertTrue(predicate(context.observe()), "Stopped at $step")
            }
            val factory = "constructed:p1:${"a".repeat(32)}"
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("build-air", "p1", "worker", "airFactory",
                Position(270.0, 150.0), "a".repeat(32), context.world)))
            until { frame -> frame.units.any { it.unitId == factory && it.constructionProgress == 1f } }
            assertEquals(9000L, context.observePlayer(context.world, "p1").balance)
            assertIs<ActionResult.Rejected>(context.submit(ProduceAction("early-air", "p1", factory,
                "dropship", "b".repeat(32), context.world)))
            assertEquals(9000L, context.observePlayer(context.world, "p1").balance)
            assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("upgrade-air", "p1", factory, "upgrade_t2", context.world)))
            assertEquals(7500L, context.observePlayer(context.world, "p1").balance)
            until { frame -> frame.units.any { it.unitId == factory && it.typeId == "airFactoryT2" } }
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("produce-air", "p1", factory,
                "dropship", "c".repeat(32), context.world)))
            assertEquals(6700L, context.observePlayer(context.world, "p1").balance)
            val id = "produced:p1:${"c".repeat(32)}"
            until { frame -> frame.units.any { it.unitId == id } }
            val birth = context.observe()
            val air = birth.units.single { it.unitId == id }
            assertEquals(500.0, air.health)
            assertEquals(Position(270.0, 155.0), air.position)
            assertEquals(0.0, air.height)
            assertEquals(30f, birth.production!!.units.single { it.unitId == id }.birthRemaining)
            assertEquals(0, air.transport!!.passengerCount)
            assertEquals(UnitTaskKind.MOVE, air.tasks.single().kind,
                "The native producer calls ordinary y.d; its exit order must keep aq false")
            repeat(2) { context.advance(Step(step++, 1.0, "sim-tick")) }
            assertEquals(.7, context.observe().units.single { it.unitId == id }.height, .00001)
        } finally { context.close() }
    }
}
