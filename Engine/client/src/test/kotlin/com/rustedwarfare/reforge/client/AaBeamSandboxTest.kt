package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

internal object AaBeamSandboxScene {
    fun load(format: String = "json"): LoadedSandbox {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("aa-beam-production/$format")
        val content = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("production-package"))).value
        return SandboxLoader().use { loader ->
            loader.requestPack(root, ContentPackChoice(content, "commandCenter"))
            val deadline = System.nanoTime() + 5_000_000_000L
            var result: LoadedSandbox? = null
            while (result == null && System.nanoTime() < deadline) {
                result = loader.pollPrepared()?.getOrThrow()
                if (result == null) Thread.sleep(5)
            }
            assertNotNull(result)
        }
    }
}

class AaBeamSandboxTest {
    @Test fun three_formats_choose_the_normal_aa_production_scene_and_bind_all_three_beam_images() {
        for (format in listOf("ini", "json", "hjson")) {
            val loaded = AaBeamSandboxScene.load(format)
            assertEquals("p009-aa-beam-production", loaded.scenario.scenarioId)
            assertEquals(listOf("commandCenter"), loaded.scenario.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
            assertTrue(loaded.scenario.units.any { it.ownerPlayerId == "p2" && it.typeId == "aaBeamGunship" })
            val images = loaded.renderResources.units.getValue("aaBeamGunship").imageBindings()
            assertTrue(images.images.keys.map { it.suffix }.containsAll(listOf("beam", "beam_start", "beam_end")))
            assertTrue(NativeActionPanel.supports(loaded.scenario.scenarioId))
            SandboxSession(loaded.scenario).use { session ->
                fun until(limit: Int, ready: () -> Boolean) {
                    repeat(limit) { if (!ready()) session.tick() }
                    assertTrue(ready(), "$format did not finish: ${session.feedback}")
                }
                session.select(Position(130.0, 130.0))
                assertIs<ActionResult.Accepted>(session.produce("builder"))
                until(600) { session.observation.units.any { it.typeId == "builder" } }
                session.select(session.observation.units.single { it.typeId == "builder" }.position)
                assertIs<ActionResult.Accepted>(session.build("airFactory", Position(270.0, 150.0)))
                until(4000) { session.observation.units.any { it.typeId == "airFactory" && it.constructionProgress == 1f } }
                session.select(Position(270.0, 150.0))
                assertTrue(session.productionActions.none { it.typeId == "aaBeamGunship" })
                assertIs<ActionResult.Accepted>(session.upgrade("upgrade_t2"))
                until(3000) { session.observation.units.any { it.typeId == "airFactoryT2" } }
                assertTrue(session.productionActions.any { it.typeId == "aaBeamGunship" && it.price == 6000L })
                assertIs<ActionResult.Accepted>(session.produce("aaBeamGunship"))
                until(2400) { session.observation.units.any { it.typeId == "aaBeamGunship" && it.ownerPlayerId == "p1" } }
                val born = session.observation.units.single { it.typeId == "aaBeamGunship" && it.ownerPlayerId == "p1" }
                assertEquals(EnergyObservation(6f, 6f, false), born.energy)
                session.select(born.displayedBodyPosition())
                assertEquals("AA beam gunship", session.actionMenu.selectedTitle)
                assertTrue(session.actionMenu.selectedUnitInfo!!.contains("Energy 6.000 / 6.000"))
                assertEquals(mapOf("current" to 6f, "maximum" to 6f, "recharging" to false), born.fields()["energy"])
            }
        }
    }
}
