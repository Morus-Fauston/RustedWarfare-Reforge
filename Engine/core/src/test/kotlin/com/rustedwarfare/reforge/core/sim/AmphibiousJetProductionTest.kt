package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

internal object AmphibiousJetScene {
    fun scenario(format: String = "json"): Scenario {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val loaded = ContentLoader.loadContentPacks(listOf(fixtures.resolve("amphibious-production/$format/production-package")))
        val content = assertIs<ContentResult.Success<LoadedContentSet>>(loaded, loaded.toString()).value
        return HelicopterFactoryScene.scenario(format).copy(scenarioId = "amphibious-jet-production",
            unitDefinitions = content.units,
            units = listOf(UnitSpawn("worker", "p1", "builder", Position(190.0, 150.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("worker" to 8L), 9L),
            economyContract = EconomyContract(mapOf("p1" to 10000L, "p2" to 0L)))
    }
    fun open(input: Scenario = scenario()): SimulationContext {
        val result = SimulationContext.initialize(input)
        return assertIs<InitResult.Accepted>(result, result.toString()).context
    }
}

class AmphibiousJetProductionTest {
    @Test fun three_formats_build_a_paid_air_factory_upgrade_and_produce_a_real_native_jet() {
        for (format in listOf("ini", "json", "hjson")) {
            val context = AmphibiousJetScene.open(AmphibiousJetScene.scenario(format))
            try {
                var step = 0L
                fun tick() = context.advance(Step(step++, 1.0, "sim-tick"))
                fun until(predicate: (Observation) -> Boolean) {
                    while (!predicate(context.observe()) && step < 9000L) tick()
                    assertTrue(predicate(context.observe()), "$format stopped at $step")
                }
                val factory = "constructed:p1:${"a".repeat(32)}"
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("build-air", "p1", "worker", "airFactory",
                    Position(270.0, 150.0), "a".repeat(32), context.world)))
                until { frame -> frame.units.any { it.unitId == factory && it.constructionProgress == 1f } }
                assertEquals(9000L, context.observePlayer(context.world, "p1").balance)
                assertIs<ActionResult.Rejected>(context.submit(ProduceAction("early-jet", "p1", factory,
                    "amphibiousJet", "b".repeat(32), context.world)))
                assertEquals(9000L, context.observePlayer(context.world, "p1").balance)
                assertIs<ActionResult.Accepted>(context.submit(SelectAction("select-factory", "p1", factory, context.world)))
                assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("upgrade-air", "p1", factory, "upgrade_t2", context.world)))
                assertEquals(7500L, context.observePlayer(context.world, "p1").balance)
                until { frame -> frame.units.any { it.unitId == factory && it.typeId == "airFactoryT2" } }
                val upgraded = context.observe().units.single { it.unitId == factory }
                assertEquals(1000.0, upgraded.health)
                assertEquals(Position(270.0, 150.0), upgraded.position)
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("produce-jet", "p1", factory,
                    "amphibiousJet", "c".repeat(32), context.world)))
                assertEquals(5500L, context.observePlayer(context.world, "p1").balance)
                val id = "produced:p1:${"c".repeat(32)}"
                until { frame -> frame.units.any { it.unitId == id } }
                val birth = context.observe()
                val jet = birth.units.single { it.unitId == id }
                assertEquals("amphibiousJet", jet.typeId)
                assertEquals(530.0, jet.health)
                assertEquals(Position(270.0, 155.0), jet.position)
                assertEquals(0.0, jet.height)
                assertEquals(30f, birth.production!!.units.single { it.unitId == id }.birthRemaining)
            } finally { context.close() }
        }
    }
}
