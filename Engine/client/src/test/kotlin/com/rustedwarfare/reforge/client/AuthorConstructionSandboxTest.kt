package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class AuthorConstructionSandboxTest {
    private val fixtures: Path get() = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("../../SimulationTest/public/inputs")

    private fun load(format: String): LoadedSandbox {
        val root = fixtures.resolve("author-construction/$format")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("package"))).value
        return SandboxLoader().use { loader ->
            loader.requestPack(root, ContentPackChoice(pack, "spbuilder"))
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                loader.pollPrepared()?.let { return@use it.getOrThrow() }
                Thread.sleep(5)
            }
            error("Author construction normal content choice did not load")
        }
    }

    @Test fun normal_author_constructor_pays_once_creates_and_finishes_the_declared_plain_building() {
        for (format in listOf("ini", "json", "hjson")) {
            val loaded = load(format)
            assertEquals("p008-content-pack", loaded.scenario.scenarioId)
            SandboxSession(loaded.scenario).use { session ->
                assertEquals(3000L, session.observation.balance)
                session.select(Position(110.0, 210.0))
                assertEquals("a", session.selectedId)
                assertEquals(listOf("workshop"), session.constructionActions.map { it.typeId })
                assertEquals("workshop", session.constructionActions.single().title)
                assertIs<ActionResult.Accepted>(session.build("workshop", Position(230.0, 210.0)))
                var steps = 0
                while (session.observation.units.none { it.typeId == "workshop" } && steps < 400) {
                    session.tick(); steps++
                }
                val site = session.observation.units.single { it.typeId == "workshop" }
                assertEquals("p1", site.ownerPlayerId)
                assertTrue(site.constructionProgress < 1f)
                assertEquals(2300L, session.observation.balance, "$format exactly one construction payment")
                while (session.observation.units.single { it.unitId == site.unitId }.constructionProgress < 1f && steps < 2500) {
                    session.tick(); steps++
                }
                val complete = session.observation.units.single { it.unitId == site.unitId }
                assertEquals(1f, complete.constructionProgress)
                assertEquals(1200.0, complete.health)
                assertEquals(2300L, session.observation.balance)
                assertEquals(1, session.observation.units.count { it.typeId == "workshop" })
            }
        }
    }
}
