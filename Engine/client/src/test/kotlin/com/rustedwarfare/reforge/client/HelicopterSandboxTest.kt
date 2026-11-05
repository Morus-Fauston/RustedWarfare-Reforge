package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class HelicopterSandboxTest {
    @Test
    fun a_real_session_evidence_sample_preserves_every_authorized_flight_field_and_its_physical_height() {
        for (format in listOf("ini", "json", "hjson")) {
            SandboxSession(load(format).scenario).use { session ->
                val old = session.observation
                session.select(Position(110.0, 210.0))
                assertIs<ActionResult.Accepted>(session.move(Position(250.0, 210.0)))
                session.tick()
                val unit = session.observation.units.single { it.unitId == "a" }
                val pose = assertNotNull(unit.flightPose)
                val fields = unit.fields()
                val encoded = assertIs<Map<*, *>>(fields["flightPose"])
                assertEquals(setOf("velocity", "height", "heightPhase", "rotorPhase", "rotorHeading", "rotorHeightOffset"), encoded.keys)
                assertEquals(mapOf("x" to pose.velocity.x, "y" to pose.velocity.y), encoded["velocity"])
                assertEquals(pose.height, encoded["height"])
                assertEquals(pose.heightPhase, encoded["heightPhase"])
                assertEquals(pose.rotorPhase, encoded["rotorPhase"])
                assertEquals(pose.rotorHeading, encoded["rotorHeading"])
                assertEquals(pose.rotorHeightOffset, encoded["rotorHeightOffset"])
                assertEquals(fields["height"], encoded["height"])
                assertEquals(0.0, old.units.single { it.unitId == "a" }.height)
                assertEquals(0.0, old.units.single { it.unitId == "a" }.flightPose!!.rotorPhase)
            }
        }
    }

    @Test
    fun selection_box_click_and_attack_use_the_displayed_air_body_while_move_keeps_the_ground_plane() {
        for (format in listOf("ini", "json", "hjson")) {
            SandboxSession(load(format).scenario).use { session ->
                repeat(150) { session.tick() }
                val own = session.observation.units.single { it.unitId == "a" }
                val enemy = session.observation.units.single { it.unitId == "b" }
                assertTrue(own.height > 9.0)
                val displayed = Position(own.position.x, own.position.y - own.height)
                session.select(displayed)
                assertEquals(own.unitId, session.selectedId)
                session.select(Position(displayed.x + 9.01, displayed.y))
                assertNull(session.selectedId, "The finite nine-unit display radius must still apply")
                session.selectBox(Position(displayed.x - 1, displayed.y - 1), Position(displayed.x + 1, displayed.y + 1))
                assertEquals(setOf(own.unitId), session.selectedIds)
                val enemyDisplay = Position(enemy.position.x, enemy.position.y - enemy.height)
                assertEquals(enemy.unitId, session.commandTargetAt(enemyDisplay))
                assertIs<ActionResult.Accepted>(session.command(enemyDisplay))
                assertEquals(enemy.unitId, session.observation.units.single { it.unitId == own.unitId }.attackTargetId)
                val destination = Position(250.0, 300.0)
                assertIs<ActionResult.Accepted>(session.command(destination))
                assertEquals(destination, session.observation.units.single { it.unitId == own.unitId }.moveTarget)
            }
        }
    }

    @Test
    fun checked_builder_and_air_factory_choices_build_then_produce_a_real_helicopter_using_the_existing_cards() {
        for (format in listOf("ini", "json", "hjson")) for (choice in listOf("builder", "airFactory")) {
            val loaded = load(format, choice, production = true)
            assertEquals("p008-helicopter-production", loaded.scenario.scenarioId)
            assertTrue(loaded.scenario.units.none { it.typeId == "airFactory" })
            assertEquals(1, loaded.scenario.units.count { it.typeId == "builder" && it.ownerPlayerId == "p1" })
            SandboxSession(loaded.scenario).use { session ->
                session.select(Position(190.0, 150.0))
                val card = session.constructionActions.single()
                assertEquals("airFactory", card.typeId)
                assertEquals("Air factory", card.title)
                assertEquals(1000L, card.price)
                assertEquals(PlacementState.CLEAR, session.previewConstruction(card.typeId, Position(270.0, 150.0))!!.state)
                assertIs<ActionResult.Accepted>(session.build(card.typeId, Position(270.0, 150.0)))
                session.tick()
                assertEquals(2000L, session.observation.balance)
                session.select(Position(270.0, 150.0))
                assertTrue(session.productionActions.isEmpty(), "An unfinished air factory cannot offer production")
                repeat(1200) { session.tick() }
                val factory = session.observation.units.single { it.typeId == "airFactory" }
                assertEquals(1f, factory.constructionProgress)
                session.select(factory.position)
                val action = session.productionActions.single()
                assertEquals("helicopter", action.typeId)
                assertEquals("Helicopter", action.title)
                assertEquals(700L, action.price)
                assertIs<ActionResult.Accepted>(session.produce("helicopter"))
                assertEquals(1300L, session.observation.balance)
                repeat(1100) { session.tick() }
                val product = session.observation.units.single { it.typeId == "helicopter" && it.ownerPlayerId == "p1" }
                assertTrue(product.height >= 4.0)
                assertTrue(product.position.y > 220.0)
                assertNotNull(product.flightPose)
                session.select(Position(product.position.x, product.position.y - product.height))
                assertEquals(product.unitId, session.selectedId)
                assertIs<ActionResult.Accepted>(session.move(Position(410.0, 270.0)))
                repeat(20) { session.tick() }
                assertIs<ActionResult.Accepted>(session.stop())
                assertNull(session.observation.units.single { it.unitId == product.unitId }.moveTarget)
                assertEquals("Helicopter created", session.productionNotice!!.text)
            }
        }
    }

    private fun load(format: String, choice: String = "helicopter", production: Boolean = false): LoadedSandbox {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val root = fixtures.resolve("helicopter-production/$format")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve(if (production) "production-package" else "package"))).value
        return SandboxLoader().use { loader ->
            loader.requestPack(root, ContentPackChoice(pack, choice))
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                loader.pollPrepared()?.let { return@use it.getOrThrow() }
                Thread.sleep(5)
            }
            error("The real helicopter sandbox did not load")
        }
    }
}
