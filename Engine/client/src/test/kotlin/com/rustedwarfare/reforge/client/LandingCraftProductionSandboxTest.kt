package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

internal object LandingCraftSandboxScene {
    fun load(format: String = "json"): LoadedSandbox {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("transport-production/$format")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("production-package"))).value
        return SandboxLoader().use { loader ->
            loader.requestPack(root, ContentPackChoice(pack, "commandCenter"))
            val deadline = System.nanoTime() + 5_000_000_000L
            var loaded: LoadedSandbox? = null
            while (loaded == null && System.nanoTime() < deadline) {
                loaded = loader.pollPrepared()?.getOrThrow()
                if (loaded == null) Thread.sleep(5)
            }
            assertNotNull(loaded)
        }
    }
}

class LandingCraftProductionSandboxTest {
    @Test fun three_formats_start_at_a_command_center_and_pay_for_a_worker_sea_factory_and_landing_craft() {
        for (format in listOf("ini", "json", "hjson")) {
            val loaded = LandingCraftSandboxScene.load(format)
            assertEquals("p009-transport-production", loaded.scenario.scenarioId)
            assertEquals(listOf("commandCenter"), loaded.scenario.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
            SandboxSession(loaded.scenario).use { session ->
                fun until(limit: Int, stage: String, ready: () -> Boolean) {
                    repeat(limit) { if (!ready()) session.tick() }
                    assertTrue(ready(), "$format $stage: ${session.feedback}")
                }
                session.select(Position(130.0, 130.0))
                assertEquals(listOf("builder"), session.productionActions.map { it.typeId })
                assertIs<ActionResult.Accepted>(session.produce("builder"))
                until(600, "worker") { session.observation.units.any { it.typeId == "builder" } }
                val worker = session.observation.units.single { it.typeId == "builder" }
                session.select(worker.position)
                assertEquals(listOf("seaFactory"), session.constructionActions.map { it.typeId })
                val beforeBuild = session.observation.credits!!
                val buildTime = session.observation.simulationTime
                assertIs<ActionResult.Accepted>(session.build("seaFactory", Position(450.0, 250.0)))
                until(1600, "construction start") { session.observation.units.any { it.typeId == "seaFactory" } }
                val payments = (session.observation.simulationTime / 10.0).toLong() - (buildTime / 10.0).toLong()
                assertEquals(beforeBuild - 1000.0 + payments * 4.5, session.observation.credits)
                until(3000, "factory") { session.observation.units.any { it.typeId == "seaFactory" && it.constructionProgress == 1f } }
                val factory = session.observation.units.single { it.typeId == "seaFactory" }
                session.select(factory.position)
                assertTrue(session.productionActions.any { it.typeId == "hovercraft" })
                val beforeProduce = session.observation.credits!!
                assertIs<ActionResult.Accepted>(session.produce("hovercraft"))
                assertEquals(beforeProduce - 600.0, session.observation.credits)
                until(800, "craft") { session.observation.units.any { it.typeId == "hovercraft" } }
                val craft = session.observation.units.single { it.typeId == "hovercraft" }
                assertEquals(450.0, craft.maxHealth)
                assertEquals(0, craft.transport?.passengerCount)
            }
        }
    }
}
