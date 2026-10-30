package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DefensePanelInputTest {
    @Test fun both_defense_choices_have_their_observed_names_and_an_ordinary_card_press_opens_only_the_chosen_mode() {
        val root = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("maintenance-defense/construction") } ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
                ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-007/20261029-implementation-01/defense-input"))
        val scene = ControlledScenario.load(root).copy(
            units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
        SandboxSession(scene).use { session ->
            session.select(Position(120.0, 170.0))
            val choices = session.constructionActions.filter { it.typeId in setOf("c_turret_t1", "laserDefence") }
            assertEquals(listOf("Turret", "Laser Shield"), choices.map { it.title })
            assertEquals(listOf(500L, 1200L), choices.map { it.price })
            val projection = MapProjection(600.0, 400.0)
            val input = SandboxInput({ session }, { projection }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { false })
            for (choice in choices) {
                val listener = ConstructionButtonInput({ session }, { true }, choice.typeId,
                    { assertTrue(input.beginConstruction(it)) }, modeRevision = { input.modeRevision })
                val card = Actor().apply { setBounds(0f, 0f, 84f, 54f); addListener(listener) }
                fun fire(down: Boolean) = card.fire(InputEvent().apply {
                    type = if (down) InputEvent.Type.touchDown else InputEvent.Type.touchUp
                    button = Input.Buttons.LEFT; pointer = 0; stageX = 10f; stageY = 10f; touchFocus = false
                })
                fire(true)
                assertNull(input.constructionTypeId, "Press alone must not choose a site mode")
                fire(false)
                assertEquals(choice.typeId, input.constructionTypeId)
                assertEquals(1750.0, session.observation.credits, "Choosing a card must not create or pay for a building")
                assertTrue(session.observation.units.single().tasks.isEmpty())
                assertTrue(input.keyDown(Input.Keys.ESCAPE))
                assertNull(input.constructionTypeId)
                assertEquals(setOf("worker"), session.selectedIds)
            }
        }
    }
}
