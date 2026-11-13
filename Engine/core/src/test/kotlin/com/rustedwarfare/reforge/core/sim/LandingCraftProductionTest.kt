package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

internal object LandingCraftScene {
    fun definitions(format: String = "json"): Map<String, UnitDefinition> {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val loaded = ContentLoader.loadContentPack(fixtures.resolve("transport-production/$format/production-package"))
        return assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value.units
    }
    fun scenario(map: MapDefinition, format: String = "json", spawns: List<UnitSpawn>? = null): Scenario {
        val base = SeaFactoryScene.scenario(map, format)
        val units = spawns ?: base.units
        return base.copy(scenarioId = "landing-craft", unitDefinitions = definitions(format), units = units,
            combatContract = CombatContract(12345, -1, units.mapIndexed { i, unit -> unit.unitId to i + 8L }.toMap(), units.size + 8L))
    }
    fun open(input: Scenario): SimulationContext =
        assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
}

class LandingCraftProductionTest {
    @Test fun native_t1_sells_a_600_credit_unarmed_landing_craft_in_all_three_formats() = WaterTestScene.withMap { map ->
        for (format in listOf("ini", "json", "hjson")) {
            val context = LandingCraftScene.open(LandingCraftScene.scenario(map, format))
            try {
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("craft", "p1", "factory",
                    "hovercraft", "a".repeat(32), context.world)))
                assertEquals(10400L, context.observePlayer(context.world, "p1").balance)
                val id = "produced:p1:${"a".repeat(32)}"
                var step = 0L
                while (context.observe().units.none { it.unitId == id } && step < 400L)
                    context.advance(Step(step++, 1.0, "sim-tick"))
                val birth = context.observe()
                val craft = birth.units.single { it.unitId == id }
                assertEquals(450.0, craft.health)
                assertEquals(Position(510.0, 155.0), craft.position)
                assertEquals(0.0, craft.height)
                assertEquals(30f, birth.production!!.units.single { it.unitId == id }.birthRemaining)
                assertNull(craft.cooldown)
            } finally { context.close() }
        }
    }
}
