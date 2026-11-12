package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.sim.ActionResult
import com.rustedwarfare.reforge.core.sim.Position
import java.nio.file.Path
import kotlin.test.*

class BomberSandboxTest {
    @Test fun normal_construction_and_upgrade_expose_only_the_paid_t2_bomber() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        for (format in listOf("ini", "json", "hjson")) {
            val root = fixtures.resolve("bomber-production/$format")
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("package"))).value
            val scenario = contentPackScenario(root, ContentPackChoice(pack, "commandCenter"))
            assertEquals("p009-bomber-production", scenario.scenarioId)
            assertTrue(NativeActionPanel.supports(scenario.scenarioId))
            SandboxSession(scenario).use { session ->
                fun until(limit: Int, ready: () -> Boolean) {
                    repeat(limit) { if (ready()) return else session.tick() }
                    assertTrue(ready(), "$format did not finish: ${session.feedback}")
                }
                session.select(Position(130.0, 130.0))
                assertIs<ActionResult.Accepted>(session.produce("builder"))
                until(700) { session.observation.units.any { it.typeId == "builder" } }
                session.select(session.observation.units.single { it.typeId == "builder" }.position)
                val factory = Position(270.0, 150.0)
                assertIs<ActionResult.Accepted>(session.build("airFactory", factory))
                until(4000) { session.observation.units.any { it.typeId == "airFactory" && it.constructionProgress == 1f } }
                session.select(factory)
                assertTrue(session.productionActions.none { it.typeId == "bomber" })
                assertIs<ActionResult.Accepted>(session.upgrade("upgrade_t2"))
                until(3000) { session.observation.units.any { it.typeId == "airFactoryT2" } }
                assertEquals(4000L, session.productionActions.single { it.typeId == "bomber" }.price)
                assertIs<ActionResult.Accepted>(session.produce("bomber"))
                until(4100) { session.observation.units.any { it.typeId == "bomber" } }
                val bomber = session.observation.units.single { it.typeId == "bomber" }
                session.select(bomber.displayedBodyPosition())
                assertEquals("Bomber", session.actionMenu.selectedTitle)
                assertEquals(3000.0, bomber.health)
            }
        }
    }
}
