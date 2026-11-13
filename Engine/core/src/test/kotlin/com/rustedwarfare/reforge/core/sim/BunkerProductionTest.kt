package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class BunkerProductionTest {
    @Test fun both_normal_factory_tiers_sell_the_4500_credit_mobile_form_and_keep_paid_identity_when_deployed() {
        val root = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) for (tier in listOf("mechFactory", "mechFactoryT2")) {
            val loaded = ContentLoader.loadContentPacks(listOf(root.resolve("bunker-production/$format/production-package")))
            val pack = assertIs<ContentResult.Success<LoadedContentSet>>(loaded, loaded.toString()).value
            val scene = HeavyMechFactoryScene.scenario(format).copy(unitDefinitions = pack.units,
                units = listOf(UnitSpawn("factory", "p1", tier, Position(270.0, 150.0), 0.0)))
            val context = HeavyMechTestScene.open(scene)
            try {
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("bunker", "p1", "factory", "mechBunker", "b".repeat(32), context.world)))
                assertEquals(11500L, context.observePlayer(context.world, "p1").balance)
                assertIs<ActionResult.Rejected>(context.submit(ProduceAction("fixed", "p1", "factory", "mechBunkerDeployed", "c".repeat(32), context.world)))
                val id = "produced:p1:${"b".repeat(32)}"
                var step = 0L
                while (context.observe().units.none { it.unitId == id } && step < 1700)
                    context.advance(Step(step++, 1.0, "sim-tick"))
                assertEquals(800.0, context.observe().units.single { it.unitId == id }.health)
                repeat(100) { context.advance(Step(step++, 1.0, "sim-tick")) }
                assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", id, context.world)))
                assertIs<ActionResult.Accepted>(context.submit(UnitAction("deploy", "p1", id, "deploy", context.world)))
                repeat(167) { context.advance(Step(step++, 1.0, "sim-tick")) }
                val fixed = context.observe().units.single { it.unitId == id }
                assertEquals("mechBunkerDeployed", fixed.typeId)
                assertEquals(0f, fixed.shield!!.current)
                assertEquals(11500L, context.observePlayer(context.world, "p1").balance)
            } finally { context.close() }
        }
    }
}
