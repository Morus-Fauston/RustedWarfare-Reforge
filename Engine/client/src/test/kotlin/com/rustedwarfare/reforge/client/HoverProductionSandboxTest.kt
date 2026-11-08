package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class HoverProductionSandboxTest {
    @Test fun choosing_another_unit_in_hover_content_preserves_that_start_choice() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        for (format in listOf("json", "ini", "hjson")) {
            val root = fixtures.resolve("hover-production/$format")
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("package"))).value
            for (selected in listOf("builder", "landFactory", "c_tank", "hoverTank")) {
                val scenario = contentPackScenario(root, ContentPackChoice(pack, selected))
                assertEquals("p008-content-pack", scenario.scenarioId, "$format/$selected")
                assertEquals(listOf(selected), scenario.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
            }
        }
    }

    @Test fun hover_content_does_not_intercept_an_existing_air_factory_start() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val temporary = Files.createTempDirectory("p009-hover-choice-").toAbsolutePath().normalize()
        fun copyTree(source: Path, target: Path) {
            Files.walk(source).use { paths -> paths.forEach { file ->
                val destination = target.resolve(source.relativize(file)).toAbsolutePath().normalize()
                require(destination.startsWith(temporary))
                if (Files.isDirectory(file)) Files.createDirectories(destination) else Files.copy(file, destination)
            } }
        }
        try {
            val root = fixtures.resolve("hover-production/json")
            copyTree(root.resolve("package"), temporary)
            for (id in listOf("airFactory", "helicopter")) copyTree(
                fixtures.resolve("phase-five-sandbox/json/package/units/$id"), temporary.resolve("units/$id"))
            val manifest = temporary.resolve("pack.json")
            Files.writeString(manifest, Files.readString(manifest).replace("\"units\": [", """"units": [
                {"type":"air-factory","file":"units/airFactory/airFactory.json"},
                {"type":"helicopter","file":"units/helicopter/helicopter.json"},"""))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(temporary)).value
            val scenario = contentPackScenario(root, ContentPackChoice(pack, "airFactory"))
            assertEquals("p008-helicopter-production", scenario.scenarioId)
            assertEquals(listOf("builder"), scenario.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
            assertEquals("p009-hover-production", contentPackScenario(root, ContentPackChoice(pack, "commandCenter")).scenarioId)
        } finally {
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                require(owned.toAbsolutePath().normalize().startsWith(temporary))
                Files.deleteIfExists(owned)
            } }
        }
    }

    @Test fun checked_hover_content_opens_a_normal_center_start_and_produces_a_controllable_hover() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        for (format in listOf("json", "ini", "hjson")) {
            val root = fixtures.resolve("hover-production/$format")
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("package"))).value
            val loaded = SandboxLoader().use { loader ->
                loader.requestPack(root, ContentPackChoice(pack, "commandCenter"))
                val deadline = System.nanoTime() + 5_000_000_000L
                var ready: LoadedSandbox? = null
                while (ready == null && System.nanoTime() < deadline) {
                    ready = loader.pollPrepared()?.getOrThrow()
                    if (ready == null) Thread.sleep(5)
                }
                assertNotNull(ready)
            }
            assertEquals("p009-hover-production", loaded.scenario.scenarioId)
            assertEquals(listOf("commandCenter"), loaded.scenario.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
            SandboxSession(loaded.scenario).use { session ->
                session.select(Position(130.0, 130.0))
                assertIs<ActionResult.Accepted>(session.produce("builder"))
                repeat(681) { session.tick() }
                val worker = session.observation.units.single { it.typeId == "builder" }
                session.select(worker.displayedBodyPosition())
                assertIs<ActionResult.Accepted>(session.build("landFactory", Position(270.0, 190.0)))
                repeat(2000) { session.tick() }
                val factory = session.observation.units.single { it.typeId == "landFactory" }
                assertEquals(1f, factory.constructionProgress)
                session.select(factory.position)
                assertEquals(listOf("builder", "c_tank", "hoverTank"), session.productionActions.map { it.typeId })
                val card = session.productionActions.single { it.typeId == "hoverTank" }
                assertEquals("Hover tank", card.title)
                assertEquals(450L, card.price)
                val balance = session.observation.balance!!
                assertIs<ActionResult.Accepted>(session.produce(card.typeId))
                assertEquals(balance - 450L, session.observation.balance)
                repeat(681) { session.tick() }
                val hover = session.observation.units.single { it.typeId == "hoverTank" }
                session.select(hover.displayedBodyPosition())
                assertEquals(hover.unitId, session.selectedId)
                for (target in listOf(Position(490.0, 350.0), Position(350.0, 350.0))) {
                    assertIs<ActionResult.Accepted>(session.move(target))
                    repeat(600) { session.tick() }
                    val arrived = session.observation.units.single { it.unitId == hover.unitId }
                    assertEquals(target.x, arrived.position.x, 8.0)
                    assertEquals(target.y, arrived.position.y, 8.0)
                }
                assertIs<ActionResult.Accepted>(session.stop())
                assertNull(session.observation.units.single { it.unitId == hover.unitId }.moveTarget)
                assertIs<ActionResult.Accepted>(session.command(Position(650.0, 350.0)))
                repeat(650) { session.tick() }
                assertTrue(session.observation.units.single { it.unitId == "b" }.health!! < 210.0)
            }
            SandboxSession(loaded.scenario).use { reopened ->
                assertNull(reopened.selectedId)
                assertEquals(5000L, reopened.observation.balance)
                assertEquals(listOf("commandCenter"), reopened.observation.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
            }
        }
    }
}
