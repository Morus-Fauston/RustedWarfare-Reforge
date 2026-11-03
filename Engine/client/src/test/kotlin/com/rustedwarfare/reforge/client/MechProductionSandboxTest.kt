package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class MechProductionSandboxTest {
    @Test
    fun checked_builder_and_factory_choices_open_a_real_buildable_production_scene_with_working_cards() {
        for (format in listOf("ini", "json", "hjson")) for (choiceId in listOf("builder", "mechFactory")) {
            val loaded = load(format, choiceId)
            assertEquals("p008-mech-production", loaded.scenario.scenarioId)
            assertTrue(loaded.scenario.units.none { it.typeId == "mechFactory" }, "The user must build the factory")
            assertEquals(1, loaded.scenario.units.count { it.typeId == "builder" && it.ownerPlayerId == "p1" })
            SandboxSession(loaded.scenario).use { session ->
                session.select(Position(190.0, 150.0))
                val card = session.constructionActions.single()
                assertEquals("mechFactory", card.typeId)
                assertEquals("Mech factory", card.title)
                assertEquals(1000L, card.price)
                assertEquals(PlacementState.CLEAR, session.previewConstruction(card.typeId, Position(270.0, 150.0))!!.state)
                assertIs<ActionResult.Accepted>(session.build(card.typeId, Position(270.0, 150.0)))
                session.tick()
                assertEquals(2000L, session.observation.balance)
                session.select(Position(270.0, 150.0))
                assertTrue(session.productionActions.isEmpty(), "An unfinished mech factory cannot offer an enabled produce card")
                repeat(1200) { session.tick() }
                val factory = session.observation.units.single { it.typeId == "mechFactory" }
                assertEquals(1f, factory.constructionProgress)
                session.select(factory.position)
                assertEquals(listOf("builder", "mechGun"), session.productionActions.map { it.typeId })
                val mech = session.productionActions.single { it.typeId == "mechGun" }
                assertEquals("Gun mech", mech.title)
                assertEquals(600L, mech.price)
                assertIs<ActionResult.Accepted>(session.produce("mechGun"))
                assertEquals(1400L, session.observation.balance)
                repeat(900) { session.tick() }
                var extra = 0
                fun produced() = session.observation.units.single { it.ownerPlayerId == "p1" && it.typeId == "mechGun" }
                while (produced().position.y <= 220.0 && extra++ < 500) session.tick()
                assertTrue(produced().position.y > 220.0)
                val movingPoint = produced().position
                repeat(17) { session.tick() }
                session.select(movingPoint)
                assertNull(session.selectedId, "A delayed click outside the finite display radius must miss a moving mech")
                while ((produced().moveTarget != null || produced().speed != 0.0) && extra++ < 600) session.tick()
                assertNull(produced().moveTarget)
                assertEquals(0.0, produced().speed)
                val product = session.observation.units.single { it.ownerPlayerId == "p1" && it.typeId == "mechGun" }
                repeat(17) { session.tick() }
                session.select(product.position)
                assertEquals(product.unitId, session.selectedId)
                assertNotNull(product.mechPose)
                assertIs<ActionResult.Accepted>(session.command(Position(410.0, 270.0)))
                repeat(15) { session.tick() }
                assertIs<ActionResult.Accepted>(session.stop())
                assertNull(session.observation.units.single { it.unitId == product.unitId }.moveTarget)
                assertEquals("Gun mech created", session.productionNotice!!.text)
            }
        }
    }

    private fun load(format: String, choice: String): LoadedSandbox {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val root = fixtures.resolve("mech-production/$format")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("production-package"))).value
        return SandboxLoader().use { loader ->
            loader.requestPack(root, ContentPackChoice(pack, choice))
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                loader.pollPrepared()?.let { return@use it.getOrThrow() }
                Thread.sleep(5)
            }
            error("The real production scene did not load")
        }
    }
}
