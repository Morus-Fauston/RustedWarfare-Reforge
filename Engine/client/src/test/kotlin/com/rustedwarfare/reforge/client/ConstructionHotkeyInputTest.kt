package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.Input.Keys
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ConstructionHotkeyInputTest {
    @Test fun current_builder_shortcuts_choose_observed_actions_without_paying_or_submitting() {
        val actions = mutableListOf<String>()
        SandboxSession(scene(), ClientEvidence { kind, data ->
            if (kind == "action") actions += data["type"].toString()
        }).use { session ->
            session.select(Position(120.0, 170.0))
            val input = input({ session })
            for ((key, type) in listOf(Keys.X to "c_turret_t1", Keys.J to "laserDefence")) {
                assertTrue(session.constructionActions.any { it.typeId == type })
                val before = session.observation.credits
                val beforeActions = actions.size
                assertTrue(input.keyDown(key), type)
                assertEquals(type, input.constructionTypeId)
                assertEquals(before, session.observation.credits)
                assertTrue(session.observation.units.single().tasks.isEmpty())
                assertEquals(beforeActions, actions.size, "Choosing a preview submits no core action")
                val revision = input.modeRevision
                repeat(3) { assertTrue(input.keyDown(key)) }
                assertEquals(revision, input.modeRevision, "Held keys cannot recreate the preview")
                assertTrue(input.keyUp(key))
                assertEquals(type, input.constructionTypeId, "Release only releases the key")
                assertEquals(beforeActions, actions.size)
            }
        }
    }

    @Test fun modified_build_keys_wait_for_the_letter_release_before_an_unmodified_press() {
        val modifiers = listOf(Keys.CONTROL_LEFT, Keys.CONTROL_RIGHT, Keys.SHIFT_LEFT,
            Keys.SHIFT_RIGHT, Keys.ALT_LEFT, Keys.ALT_RIGHT)
        SandboxSession(scene()).use { session ->
            session.select(Position(120.0, 170.0))
            for (key in listOf(Keys.X, Keys.J)) {
                for (heldModifiers in modifiers.map { setOf(it) } + listOf(modifiers.toSet())) {
                    val held = heldModifiers.toMutableSet()
                    val input = input({ session }, pressed = { it in held })
                    assertFalse(input.keyDown(key), "Modifiers $heldModifiers must reject $key")
                    assertNull(input.constructionTypeId)
                    held.clear()
                    input.keyDown(key)
                    assertNull(input.constructionTypeId, "Releasing only a modifier cannot activate a held letter")
                    input.keyUp(key)
                    assertTrue(input.keyDown(key))
                    assertEquals(if (key == Keys.X) "c_turret_t1" else "laserDefence", input.constructionTypeId)
                    assertEquals(1750.0, session.observation.credits)
                    assertTrue(session.observation.units.single().tasks.isEmpty())
                }
            }
        }
    }

    @Test fun cancelling_a_screen_gesture_allows_a_fresh_press_without_an_old_release_command() {
        var current: SandboxSession? = SandboxSession(scene())
        val input = input({ current })
        try {
            current!!.select(Position(120.0, 170.0))
            assertTrue(input.keyDown(Keys.X))
            input.cancel()
            assertNull(input.constructionTypeId)
            assertTrue(input.keyDown(Keys.X))
            assertEquals("c_turret_t1", input.constructionTypeId, "Lifecycle cancellation resets held input")
            input.cancel()
            current!!.close()
            current = SandboxSession(scene())
            current!!.select(Position(120.0, 170.0))
            input.keyUp(Keys.X)
            assertNull(input.constructionTypeId, "An old release cannot choose an action in a new world")
            assertTrue(input.keyDown(Keys.J))
            input.keyUp(Keys.J)
            assertEquals("laserDefence", input.constructionTypeId)
            assertEquals(1750.0, current!!.observation.credits)
            assertTrue(current!!.observation.units.single().tasks.isEmpty())
        } finally { current?.close() }
    }

    @Test fun construction_cards_show_the_adopted_desktop_bindings_and_keep_the_other_names() {
        SandboxSession(scene()).use { session ->
            session.select(Position(120.0, 170.0))
            val cards = session.constructionActions.associateBy { it.typeId }
            assertEquals("X Turret", cards.getValue("c_turret_t1").panelTitle)
            assertEquals("J Laser Shield", cards.getValue("laserDefence").panelTitle)
            assertEquals("Turret", cards.getValue("c_turret_t1").title)
            assertEquals("Laser Shield", cards.getValue("laserDefence").title)
            assertEquals(listOf("Extractor T1", "Factory", "Fabricator T1"),
                listOf("extractorT1", "landFactory", "fabricatorT1").map { cards.getValue(it).panelTitle })
        }
    }

    @Test fun build_keys_require_an_active_desktop_and_a_current_controllable_build_menu() {
        fun rejected(input: SandboxInput) {
            for (key in listOf(Keys.X, Keys.J)) {
                assertFalse(input.keyDown(key))
                input.keyUp(key)
                assertNull(input.constructionTypeId)
            }
        }
        rejected(input({ null }))
        SandboxSession(scene()).use { session ->
            session.select(Position(120.0, 170.0))
            rejected(input({ session }, active = { false }))
            rejected(input({ session }, editing = { true }))
            rejected(input({ session }, touch = true))
            session.select(Position(590.0, 390.0))
            rejected(input({ session }))
            session.close()
            rejected(input({ session }))
        }
        val base = scene()
        for ((type, owner) in listOf("builder" to "p2", "c_turret_t1" to "p1", "commandCenter" to "p1")) {
            SandboxSession(base.copy(units = listOf(UnitSpawn("worker", owner, type, Position(120.0, 170.0), 0.0)))).use {
                it.select(Position(120.0, 170.0))
                assertTrue(it.constructionActions.isEmpty())
                rejected(input({ it }))
            }
        }
        SandboxSession(base.copy(players = base.players.map { it.copy(control = PlayerControl.NONE) })).use {
            it.select(Position(120.0, 170.0))
            rejected(input({ it }))
        }
        val mixed = base.copy(units = base.units + UnitSpawn("tank", "p1", "c_tank", Position(120.0, 210.0), 0.0),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L, "tank" to 2L), 3L))
        SandboxSession(mixed).use {
            it.select(Position(120.0, 170.0)); it.select(Position(120.0, 210.0), SelectionMode.ADD)
            assertEquals(2, it.selectedIds.size)
            assertTrue(it.constructionActions.isEmpty())
            rejected(input({ it }))
        }
    }

    @Test fun insufficient_funds_do_not_block_choosing_a_construction_preview() {
        val poor = scene().copy(economyContract = EconomyContract(mapOf("p1" to 0L, "p2" to 0L)))
        SandboxSession(poor).use { session ->
            session.select(Position(120.0, 170.0))
            val input = input({ session })
            for ((key, type) in listOf(Keys.X to "c_turret_t1", Keys.J to "laserDefence")) {
                assertTrue(input.keyDown(key))
                assertEquals(type, input.constructionTypeId)
                assertNotNull(session.previewConstruction(type, Position(310.0, 210.0)))
                input.keyUp(key)
                assertEquals(0.0, session.observation.credits)
                assertTrue(session.observation.units.single().tasks.isEmpty())
            }
        }
    }

    @Test fun switching_build_keys_invalidates_an_old_map_press_and_preserves_submitted_work() {
        val actions = mutableListOf<String>()
        SandboxSession(scene(), ClientEvidence { kind, data ->
            if (kind == "action") actions += data["type"].toString()
        }).use { session ->
            session.select(Position(120.0, 170.0))
            assertIs<ActionResult.Accepted>(session.move(Position(50.0, 170.0)))
            session.tick()
            val submitted = session.observation.units.single().tasks
            assertTrue(submitted.isNotEmpty())
            val map = MapProjection(600.0, 400.0)
            val input = SandboxInput({ session }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { false })
            assertTrue(input.keyDown(Keys.X))
            val x = map.x(310.0).toInt(); val y = map.y(210.0).toInt()
            assertTrue(input.touchDown(x, y, 0, Input.Buttons.LEFT))
            assertTrue(input.keyDown(Keys.J))
            input.touchUp(x, y, 0, Input.Buttons.LEFT)
            assertEquals("laserDefence", input.constructionTypeId)
            assertTrue(actions.none { it == "BuildAction" })
            assertEquals(submitted, session.observation.units.single().tasks)
        }
    }

    private fun input(current: () -> SandboxSession?, active: () -> Boolean = { true },
                      editing: () -> Boolean = { false }, pressed: (Int) -> Boolean = { false },
                      touch: Boolean = false) = SandboxInput(current, { null }, { _, _ -> null },
        active, editing, pressed, touchControls = touch)

    private fun scene(): Scenario {
        val root = System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("maintenance-defense/construction") }
            ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
                    ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-007/20261029-implementation-01/defense-input")
        return ControlledScenario.load(root).copy(
            units = listOf(UnitSpawn("worker", "p1", "builder", Position(120.0, 170.0), 90.0)),
            combatContract = CombatContract(1, -1, mapOf("worker" to 1L), 2L))
    }
}
