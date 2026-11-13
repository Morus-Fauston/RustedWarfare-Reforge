package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class AmphibiousFlightInputTest {
    @Test fun available_intent_requires_its_own_press_and_release_and_disabled_or_stale_presses_do_nothing() {
        val base = AmphibiousSandboxScene.load().scenario
        val scene = base.copy(units = listOf(
            UnitSpawn("wet", "p1", "amphibiousJet", Position(500.0, 300.0), 90.0),
            UnitSpawn("dry", "p1", "amphibiousJet", Position(300.0, 300.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("wet" to 8L, "dry" to 9L), 10L))
        SandboxSession(scene).use { session ->
            val input = AmphibiousFlightButtonInput({ session }, { true })
            val actor = Actor().apply { setBounds(752f, 188f, 176f, 40f); addListener(input) }
            fun intent(id: String = "wet") = session.observation.units.single { it.unitId == id }.amphibiousJet!!.flyingIntent
            fun event(down: Boolean) = actor.fire(InputEvent().apply {
                type = if (down) InputEvent.Type.touchDown else InputEvent.Type.touchUp
                button = Input.Buttons.LEFT; pointer = 0; stageX = 762f; stageY = 198f; touchFocus = false
            })
            session.select(Position(500.0, 300.0))
            event(false)
            assertEquals(true, intent())
            event(true)
            assertEquals(true, intent())
            event(false)
            assertEquals(false, intent(), "Owned Dive release changes only the requested intent")
            event(true)
            assertIs<ActionResult.Accepted>(session.amphibiousFlight(true))
            event(false)
            assertEquals(true, intent(), "Stale Fly release must not become a Dive")
            event(true)
            session.select(Position(300.0, 300.0))
            event(false)
            assertEquals(true, intent("dry"))
            event(true)
            assertFalse(input.pressed, "Dive on land is disabled at press time")
            event(false)
            session.select(Position(500.0, 300.0))
            event(true); input.cancel(); event(false)
            assertEquals(true, intent())
            assertTrue(NativeActionPanel.supports(base.scenarioId))
        }
    }
}
