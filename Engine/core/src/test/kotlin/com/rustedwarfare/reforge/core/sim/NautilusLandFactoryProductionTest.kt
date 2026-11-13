package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class NautilusLandFactoryProductionTest {
    @Test fun the_paid_experimental_factory_produces_land_form_with_native_birth_and_no_technology_queue() = WaterTestScene.withMap { map ->
        for (format in listOf("ini", "json", "hjson")) {
            val loaded = ContentLoader.loadContentPack(Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
                .resolve("nautilus-production/$format/production-package"))
            val definitions = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value.units
            val scene = NautilusScene.scenario(map).copy(unitDefinitions = definitions,
                units = listOf(UnitSpawn("factory", "p1", "experimentalLandFactory", Position(200.0, 150.0), 0.0)),
                combatContract = CombatContract(12345, -1, mapOf("factory" to 8L), 9L),
                economyContract = EconomyContract(mapOf("p1" to 20000L, "p2" to 0L)))
            val c = NautilusScene.open(scene)
            try {
                assertIs<ActionResult.Accepted>(c.submit(ProduceAction("land", "p1", "factory", "nautilusSubmarineLand", "b".repeat(32), c.world)))
                assertEquals(2000L, c.observePlayer(c.world, "p1").balance)
                var step = 0L
                while (c.observe().units.size == 1 && step < 3400) c.advance(Step(step++, 1.0, "sim-tick"))
                val child = c.observe().units.single { it.unitId != "factory" }
                assertEquals("nautilusSubmarineLand", child.typeId)
                assertEquals(Position(200.0, 155.0), child.position)
                assertEquals(180.0, child.heading)
                assertEquals(Position(199.0, 315.0), child.moveTarget)
                assertEquals(120f, c.observe().production!!.units.single().birthRemaining)
                assertEquals(4000.0, child.health)
                assertEquals(0.0, child.height)
                assertTrue(c.observePlayer(c.world, "p1").upgradeQueues.all { it.items.isEmpty() })
            } finally { c.close() }
        }
    }
}
