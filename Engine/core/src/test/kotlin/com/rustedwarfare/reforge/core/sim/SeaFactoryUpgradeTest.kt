package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

internal object SeaFactoryScene {
    fun scenario(map: MapDefinition, format: String = "ini", tier: String = "seaFactory"): Scenario {
        val root = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("naval-production/$format/production-package"))).value
        return WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
            unitDefinitions = pack.units,
            units = listOf(UnitSpawn("factory", "p1", tier, Position(510.0, 150.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("factory" to 8L), 9L),
            economyContract = EconomyContract(mapOf("p1" to 11000L, "p2" to 0L)))
    }
}

class SeaFactoryUpgradeTest {
    @Test fun a_wounded_sea_factory_keeps_exact_health_when_its_native_tier_change_does_not_write_health() = WaterTestScene.withMap { map ->
        val original = CombatTestContent.scenario().unitDefinitions.getValue("c_tank")
        // Explicit trusted-host damage/cooldown control; the hit still uses the real public combat lifecycle.
        val shooter = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange, original.combat!!.let {
                it.copy(shootDelay = 100000.0, projectile = it.projectile.copy(directDamage = 493.0)) })
        val base = SeaFactoryScene.scenario(map)
        val input = base.copy(unitDefinitions = base.unitDefinitions + ("c_tank" to shooter),
            units = listOf(UnitSpawn("factory", "p1", "seaFactory", Position(450.0, 150.0), 0.0),
                UnitSpawn("shooter", "p2", "c_tank", Position(370.0, 150.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("factory" to 8L, "shooter" to 9L), 10L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            var q = 0L
            while (context.observe().units.single { it.unitId == "factory" }.health == 1000.0 && q < 200L)
                context.advance(Step(q++, 1.0, "sim-tick"))
            val before = context.observe().units.single { it.unitId == "factory" }
            assertEquals(507.0, before.health)
            assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("wounded", "p1", "factory", "upgrade_t2", context.world)))
            val limit = q + 2700L
            while (context.observe().units.single { it.unitId == "factory" }.typeId != "seaFactoryT2" && q < limit)
                context.advance(Step(q++, 1.0, "sim-tick"))
            val after = context.observe().units.single { it.unitId == "factory" }
            assertEquals("seaFactoryT2", after.typeId)
            assertEquals(before.health, after.health)
            assertEquals(before.position, after.position)
            assertEquals(9000L, context.observePlayer(context.world, "p1").balance)
            assertEquals(507.0, before.health)
        } finally { context.close() }
    }

    @Test fun native_t1_has_no_product_and_uses_the_ordinary_paid_cancellable_upgrade_to_t2() = WaterTestScene.withMap { map ->
        for (format in listOf("ini", "json", "hjson")) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(SeaFactoryScene.scenario(map, format))).context
            try {
                assertTrue(context.observe().economy!!.queuedProduction["factory"].orEmpty().isEmpty())
                assertEquals("unsupported-production-type", assertIs<ActionResult.Rejected>(context.submit(
                    ProduceAction("early", "p1", "factory", "heavyBattleship", "a".repeat(32), context.world))).error.code)
                assertEquals(11000L, context.observePlayer(context.world, "p1").balance)
                assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("queue", "p1", "factory", "upgrade_t2", context.world)))
                assertEquals(9000L, context.observePlayer(context.world, "p1").balance)
                assertEquals("duplicate-upgrade", assertIs<ActionResult.Rejected>(context.submit(
                    UpgradeAction("duplicate", "p1", "factory", "upgrade_t2", context.world))).error.code)
                val first = context.advance(Step(0, 1.0, "sim-tick"))
                assertEquals(.0004f, first.upgrades!!.queues.getValue("factory").single().progress)
                assertIs<ActionResult.Accepted>(context.submit(CancelUpgradeAction("cancel", "p1", "factory", "upgrade_t2", context.world)))
                assertEquals(11000L, context.observePlayer(context.world, "p1").balance)
                assertEquals(1, first.upgrades!!.queues.getValue("factory").size)
                assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("again", "p1", "factory", "upgrade_t2", context.world)))
                var q = 1L
                while (context.observe().units.single().typeId != "seaFactoryT2" && q < 2700L)
                    context.advance(Step(q++, 1.0, "sim-tick"))
                val upgraded = context.observe().units.single()
                assertEquals("seaFactoryT2", upgraded.typeId)
                assertEquals(1000.0, upgraded.health)
                assertTrue(context.observe().upgrades!!.queues["factory"].orEmpty().isEmpty())
                assertEquals(9000L, context.observePlayer(context.world, "p1").balance)
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("ship", "p1", "factory", "heavyBattleship", "a".repeat(32), context.world)))
                assertEquals(3000L, context.observePlayer(context.world, "p1").balance)
                assertEquals("heavyBattleship", context.observe().economy!!.queuedProduction.getValue("factory").single().typeId)
            } finally { context.close() }
        }
    }
}
