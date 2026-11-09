package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class SubmarineDepthInputTest {
    @Test fun native_depth_button_requires_an_owned_release_and_cancels_when_selection_or_available_intent_changes() {
        val base = NavalSandboxScene.load("json", "attackSubmarine").scenario
        assertTrue(NativeActionPanel.supports(base.scenarioId))
        // Explicit short input-isolation scene; normal paid production is tested separately.
        val scene = base.copy(units = listOf(
            UnitSpawn("s1", "p1", "attackSubmarine", Position(450.0, 250.0), 90.0),
            UnitSpawn("s2", "p1", "attackSubmarine", Position(550.0, 250.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("s1" to 8L, "s2" to 9L), 10L))
        SandboxSession(scene).use { session ->
            session.select(Position(450.0, 250.0))
            val input = SubmarineDepthButtonInput({ session }, { true })
            val actor = Actor().apply { setBounds(752f, 188f, 176f, 40f); addListener(input) }
            fun intent(id: String = "s1") = session.observation.units.single { it.unitId == id }.submarine!!.surfaceIntent
            fun event(down: Boolean) = actor.fire(InputEvent().apply {
                type = if (down) InputEvent.Type.touchDown else InputEvent.Type.touchUp
                button = Input.Buttons.LEFT; pointer = 0; stageX = 762f; stageY = 198f; touchFocus = false
            })
            assertEquals(false, intent())
            event(true)
            assertEquals(false, intent())
            event(false)
            assertEquals(true, intent())
            event(false)
            assertEquals(true, intent(), "Unowned release does not toggle intent")
            event(true)
            session.select(Position(550.0, 250.0))
            event(false)
            assertEquals(false, intent("s2"))
            session.select(Position(450.0, 250.0))
            event(true)
            assertIs<ActionResult.Accepted>(session.submarineDepth(false))
            event(false)
            assertEquals(false, intent(), "A stale Dive release cannot become a new Surface")
            event(true); input.cancel(); event(false)
            assertEquals(false, intent())
        }
    }
}
