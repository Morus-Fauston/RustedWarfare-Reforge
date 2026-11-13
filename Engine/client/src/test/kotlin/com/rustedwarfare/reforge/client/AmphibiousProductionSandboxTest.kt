package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

internal object AmphibiousSandboxScene {
    fun load(format: String = "json", choice: String = "commandCenter"): LoadedSandbox {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("amphibious-production/$format")
        val content = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("production-package"))).value
        return SandboxLoader().use { loader ->
            loader.requestPack(root, ContentPackChoice(content, choice))
            val deadline = System.nanoTime() + 5_000_000_000L
            var result: LoadedSandbox? = null
            while (result == null && System.nanoTime() < deadline) {
                result = loader.pollPrepared()?.getOrThrow()
                if (result == null) Thread.sleep(5)
            }
            assertNotNull(result, "$format did not load")
        }
    }
}

class AmphibiousProductionSandboxTest {
    @Test fun three_formats_open_a_normal_command_center_and_pay_for_worker_factory_upgrade_and_jet() {
        for (format in listOf("ini", "json", "hjson")) {
            val loaded = AmphibiousSandboxScene.load(format)
            assertEquals("p009-amphibious-production", loaded.scenario.scenarioId)
            assertEquals(listOf("commandCenter"), loaded.scenario.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
            SandboxSession(loaded.scenario).use { session ->
                fun until(limit: Int, stage: String, ready: () -> Boolean) {
                    repeat(limit) { if (!ready()) session.tick() }
                    assertTrue(ready(), "$format $stage did not finish: ${session.observation.units}; ${session.feedback}")
                }
                session.select(Position(130.0, 130.0))
                assertEquals(listOf("builder"), session.productionActions.map { it.typeId })
                assertIs<ActionResult.Accepted>(session.produce("builder"))
                until(600, "worker") { session.observation.units.any { it.typeId == "builder" } }
                val worker = session.observation.units.single { it.typeId == "builder" }
                session.select(worker.position)
                assertEquals(listOf("airFactory"), session.constructionActions.map { it.typeId })
                val beforeBuild = session.observation.credits!!
                assertIs<ActionResult.Accepted>(session.build("airFactory", Position(270.0, 150.0)))
                assertEquals(beforeBuild, session.observation.credits)
                val beforeBuildTime = session.observation.simulationTime
                until(1200, "construction start") { session.observation.units.any { it.typeId == "airFactory" } }
                // The command center pays 4.5 every ten ticks; construction charges at actual birth.
                val incomePayments = (session.observation.simulationTime / 10.0).toLong() -
                    (beforeBuildTime / 10.0).toLong()
                assertEquals(beforeBuild - 1000.0 + incomePayments * 4.5, session.observation.credits)
                until(3000, "factory") { session.observation.units.any { it.typeId == "airFactory" && it.constructionProgress == 1f } }
                val factory = session.observation.units.single { it.typeId == "airFactory" }
                session.select(factory.position)
                assertEquals(listOf("helicopter"), session.productionActions.map { it.typeId })
                val beforeUpgrade = session.observation.credits!!
                assertIs<ActionResult.Accepted>(session.upgrade("upgrade_t2"))
                assertEquals(beforeUpgrade - 1500.0, session.observation.credits)
                until(3000, "upgrade") { session.observation.units.any { it.unitId == factory.unitId && it.typeId == "airFactoryT2" } }
                assertEquals(factory.unitId, session.selectedId)
                assertEquals(listOf("helicopter", "amphibiousJet"), session.productionActions.map { it.typeId })
                val beforeProduce = session.observation.credits!!
                assertIs<ActionResult.Accepted>(session.produce("amphibiousJet"))
                assertEquals(beforeProduce - 2000.0, session.observation.credits)
                until(1200, "jet") { session.observation.units.any { it.typeId == "amphibiousJet" && it.ownerPlayerId == "p1" } }
                val jet = session.observation.units.single { it.typeId == "amphibiousJet" && it.ownerPlayerId == "p1" }
                assertEquals(530.0, jet.maxHealth)
                assertNotNull(jet.amphibiousJet)
            }
        }
    }
}
