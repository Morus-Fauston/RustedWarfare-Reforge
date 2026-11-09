package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class LandingCraftUnloadInputTest {
    @Test fun an_unload_press_cannot_turn_into_cancel_or_target_another_carrier_on_release() {
        val base = LandingCraftSandboxScene.load().scenario
        val input = base.copy(units = listOf(
            UnitSpawn("a", "p1", "builder", Position(360.0, 150.0), 90.0),
            UnitSpawn("craft", "p1", "hovercraft", Position(375.0, 150.0), 90.0),
            UnitSpawn("other", "p1", "hovercraft", Position(270.0, 250.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "craft" to 9L, "other" to 10L), 11L))
        SandboxSession(input).use { session ->
            session.select(Position(360.0, 150.0))
            assertIs<ActionResult.Accepted>(session.command(Position(375.0, 150.0)))
            session.select(Position(375.0, 150.0))
            val listener = TransportUnloadButtonInput({ session }, { true })
            val actor = Actor().apply { setBounds(752f, 188f, 176f, 40f); addListener(listener) }
            fun event(down: Boolean) = actor.fire(InputEvent().apply {
                type = if (down) InputEvent.Type.touchDown else InputEvent.Type.touchUp
                button = Input.Buttons.LEFT; pointer = 0; stageX = 762f; stageY = 198f; touchFocus = false
            })
            fun unloading() = session.observation.units.single { it.unitId == "craft" }.transport!!.unloading
            event(false)
            assertFalse(unloading())
            event(true)
            assertFalse(unloading())
            event(false)
            assertTrue(unloading())
            event(true)
            session.select(Position(270.0, 250.0))
            event(false)
            assertTrue(unloading())
            session.select(Position(375.0, 150.0))
            event(true)
            assertIs<ActionResult.Accepted>(session.unload(false))
            event(false)
            assertFalse(unloading())
            event(true); listener.cancel(); event(false)
            assertFalse(unloading())
        }
    }
}
