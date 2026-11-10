package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class MechEngineerProductionTest {
    @Test fun each_syntax_requires_a_real_t2_upgrade_before_paying_for_and_producing_the_engineer() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        for (format in listOf("ini", "json", "hjson")) {
            val loaded = ContentLoader.loadContentPack(root.resolve("mech-engineer-production/$format/production-package"))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value
            val scene = HeavyMechFactoryScene.scenario(format).copy(unitDefinitions = pack.units)
            val opened = SimulationContext.initialize(scene)
            val c = assertIs<InitResult.Accepted>(opened, opened.toString()).context
            try {
                var step = 0L
                fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
                assertIs<ActionResult.Rejected>(c.submit(ProduceAction("too-early", "p1", "factory",
                    "mechEngineer", "a".repeat(32), c.world)))
                assertEquals(16000L, c.observePlayer(c.world, "p1").balance)
                assertIs<ActionResult.Accepted>(c.submit(UpgradeAction("upgrade", "p1", "factory", "upgrade_t2", c.world)))
                while (c.observe().units.single().typeId != "mechFactoryT2" && step < 2600L) tick()
                assertEquals("mechFactoryT2", c.observe().units.single().typeId)
                assertEquals(12000L, c.observePlayer(c.world, "p1").balance)
                assertIs<ActionResult.Accepted>(c.submit(ProduceAction("engineer", "p1", "factory",
                    "mechEngineer", "b".repeat(32), c.world)))
                assertEquals(7000L, c.observePlayer(c.world, "p1").balance)
                val id = "produced:p1:${"b".repeat(32)}"
                while (c.observe().units.none { it.unitId == id } && step < 4000L) tick()
                val born = c.observe().units.single { it.unitId == id }
                assertEquals("mechEngineer", born.typeId)
                assertEquals(2000.0, born.health)
                assertEquals(4, born.mechPose!!.legs.size)
                val credits = c.observePlayer(c.world, "p1").credits
                repeat(40) { tick() }
                assertEquals(credits!! + 1.0, c.observePlayer(c.world, "p1").credits)
            } finally { c.close() }
        }
    }
}
