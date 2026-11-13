package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class AfterburnInputTest {
    @Test fun an_owned_release_queues_once_and_stale_selection_disabled_and_inactive_presses_do_nothing() {
        val base = AaBeamSandboxScene.load().scenario
        val scene = base.copy(units = listOf(
            UnitSpawn("aa", "p1", "aaBeamGunship", Position(300.0, 300.0), 90.0),
            UnitSpawn("other", "p1", "aaBeamGunship", Position(500.0, 300.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("aa" to 8L, "other" to 9L), 10L))
        SandboxSession(scene).use { session ->
            var active = true
            val input = CustomUnitActionButtonInput({ session }, { active }, "afterBurn")
            val actor = Actor().apply { setBounds(752f, 188f, 84f, 40f); addListener(input) }
            fun event(typeValue: InputEvent.Type) = actor.fire(InputEvent().apply {
                type = typeValue; button = Input.Buttons.LEFT; pointer = 0
                stageX = 762f; stageY = 198f; touchFocus = false
            })
            fun queued() = session.actionMenu.unitActions.single().queued
            session.select(Position(300.0, 300.0))
            event(InputEvent.Type.touchUp)
            assertFalse(queued())
            event(InputEvent.Type.touchDown)
            session.select(Position(500.0, 300.0)); session.select(Position(300.0, 300.0))
            event(InputEvent.Type.touchUp)
            assertFalse(queued())
            event(InputEvent.Type.touchDown); active = false; event(InputEvent.Type.touchUp)
            assertFalse(queued())
            active = true
            event(InputEvent.Type.touchDown)
            assertTrue(input.pressed)
            assertFalse(queued())
            event(InputEvent.Type.touchUp)
            assertTrue(queued())
            val before = session.observation
            event(InputEvent.Type.touchUp)
            assertSame(before, session.observation)
            event(InputEvent.Type.touchDown)
            assertFalse(input.pressed, "Already queued action cannot own another press")
            event(InputEvent.Type.touchUp)
            session.tick()
            assertEquals("aaBeamGunship_afterburn", session.observation.units.single { it.unitId == "aa" }.typeId)
            assertFalse(session.actionMenu.unitActions.first().available)
            event(InputEvent.Type.touchDown)
            assertFalse(input.pressed, "Inherited action remains cooling down")
        }
    }
}
