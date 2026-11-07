package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.*

class AuthorShoreSandboxTest {
    private val fixtures: Path get() = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("../../SimulationTest/public/inputs")

    private fun load(format: String): LoadedSandbox {
        val root = fixtures.resolve("author-shore/$format")
        val result = ContentLoader.loadContentPack(root.resolve("package"))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, "$format: $result").value
        return SandboxLoader().use { loader ->
            loader.requestPack(root, ContentPackChoice(pack, "spbuilder"))
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                loader.pollPrepared()?.let { return@use it.getOrThrow() }
                Thread.sleep(5)
            }
            error("Author shore normal content choice did not load")
        }
    }

    @Test fun author_constructor_normally_builds_a_water_factory_and_pays_for_an_existing_ship_that_sails() {
        for (format in listOf("ini", "json", "hjson")) SandboxSession(load(format).scenario).use { session ->
            session.select(Position(110.0, 210.0))
            assertEquals("a", session.selectedId)
            assertEquals(listOf("spshipyard"), session.constructionActions.map { it.typeId })
            assertIs<ActionResult.Accepted>(session.build("spshipyard", Position(250.0, 150.0)))
            var elapsed = 0
            while (session.observation.units.none { it.typeId == "spshipyard" } && elapsed++ < 1500) session.tick()
            val site = session.observation.units.single { it.typeId == "spshipyard" }
            assertEquals("p1", site.ownerPlayerId)
            assertTrue(site.constructionProgress < 1f)
            assertEquals(2300L, session.observation.balance)
            while (session.observation.units.single { it.unitId == site.unitId }.constructionProgress < 1f && elapsed++ < 2500)
                session.tick()
            val factory = session.observation.units.single { it.unitId == site.unitId }
            assertEquals(1f, factory.constructionProgress)
            assertEquals(1200.0, factory.health)
            session.select(factory.position)
            assertEquals(site.unitId, session.selectedId)
            assertEquals(listOf("spcruiser"), session.productionActions.map { it.typeId })
            assertIs<ActionResult.Accepted>(session.produce("spcruiser"))
            assertEquals(1950L, session.observation.balance)
            while (session.observation.units.none { it.unitId.startsWith("produced:") } && elapsed++ < 4000) session.tick()
            val ship = session.observation.units.single { it.unitId.startsWith("produced:") }
            assertEquals("spcruiser", ship.typeId)
            assertEquals("p1", ship.ownerPlayerId)
            session.select(ship.position)
            assertEquals(ship.unitId, session.selectedId)
            val destination = Position(250.0, 350.0)
            assertIs<ActionResult.Accepted>(session.move(destination))
            repeat(600) { session.tick() }
            val moved = session.observation.units.single { it.unitId == ship.unitId }
            // The existing ordinary scene declares MovementContract.targetRadius = 4.
            assertTrue(hypot(moved.position.x - destination.x, moved.position.y - destination.y) <= 4.0)
            assertNotNull(moved.waterPose)
            assertTrue(moved.alive)
            assertEquals(1950L, session.observation.balance)
            assertEquals(1, session.observation.units.count { it.typeId == "spshipyard" })
            assertEquals(1, session.observation.units.count { it.unitId.startsWith("produced:") })
        }
    }

    @Test fun the_normal_preview_rejects_dry_cross_shore_outside_and_occupied_foundations_without_payment() {
        for (format in listOf("ini", "json", "hjson")) SandboxSession(load(format).scenario).use { session ->
            session.select(Position(110.0, 210.0))
            val before = session.observation
            for (position in listOf(Position(90.0, 150.0), Position(190.0, 150.0), Position(250.0, 590.0))) {
                assertEquals(PlacementState.BLOCKED, session.previewConstruction("spshipyard", position)?.state)
                assertNull(session.build("spshipyard", position))
                assertEquals("Cannot build here", session.playerFeedback)
                assertEquals(before, session.observation)
                assertEquals("a", session.selectedId)
            }
            assertIs<ActionResult.Accepted>(session.build("spshipyard", Position(250.0, 150.0)))
            var ticks = 0
            while (session.observation.units.none { it.typeId == "spshipyard" } && ticks++ < 1500) session.tick()
            val site = session.observation.units.single { it.typeId == "spshipyard" }
            session.select(site.position)
            assertTrue(session.productionActions.isEmpty())
            assertNull(session.produce("spcruiser"))
            assertEquals(2300L, session.observation.balance)
            while (session.observation.units.single { it.unitId == site.unitId }.constructionProgress < 1f && ticks++ < 2500)
                session.tick()
            session.select(session.observation.units.single { it.unitId == "a" }.position)
            // Eighty units away, but the five-row placement footprints share one row.
            val overlap = Position(250.0, 230.0)
            assertEquals(PlacementState.BLOCKED, session.previewConstruction("spshipyard", overlap)?.state)
            assertNull(session.build("spshipyard", overlap))
            assertEquals("Cannot build here", session.playerFeedback)
            repeat(50) { session.tick() }
            assertEquals(2300L, session.observation.balance)
            assertEquals(1, session.observation.units.count { it.typeId == "spshipyard" })
        }
    }
}
