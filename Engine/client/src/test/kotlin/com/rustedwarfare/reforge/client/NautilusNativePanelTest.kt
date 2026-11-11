package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class NautilusNativePanelTest {
    @Test fun right_release_cancels_a_real_dive_queue_and_stale_selection_cannot_cancel_it() {
        val base = NautilusSandboxScene.scenario()
        val scene = base.copy(units = listOf(UnitSpawn("mother", "p1", "nautilusSubmarineSurface", Position(500.0, 300.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("mother" to 8L), 9L))
        SandboxSession(scene).use { session ->
            session.select(Position(500.0, 300.0))
            val bounds = NativeActionPanel.customActions.getValue("dive")
            val actor = Actor().apply {
                setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
                addListener(CustomUnitActionButtonInput({ session }, { true }, "dive"))
            }
            press(actor, true); press(actor, false)
            session.tick()
            assertTrue(session.actionMenu.unitActions.single().queued)
            press(actor, false, Input.Buttons.RIGHT)
            assertTrue(session.actionMenu.unitActions.single().queued)
            press(actor, true, Input.Buttons.RIGHT)
            session.select(Position(0.0, 0.0)); session.select(Position(500.0, 300.0))
            press(actor, false, Input.Buttons.RIGHT)
            assertTrue(session.actionMenu.unitActions.single().queued)
            press(actor, true, Input.Buttons.RIGHT)
            assertTrue(session.actionMenu.unitActions.single().queued)
            press(actor, false, Input.Buttons.RIGHT)
            assertFalse(session.actionMenu.unitActions.single().queued)
            repeat(135) { session.tick() }
            assertEquals("nautilusSubmarineSurface", session.observation.units.single().typeId)
        }
    }

    @Test fun native_surface_and_dive_cards_dispatch_on_owned_release_and_leave_room_for_unload() {
        val base = NautilusSandboxScene.scenario()
        val scene = base.copy(units = listOf(UnitSpawn("mother", "p1", "nautilusSubmarine", Position(500.0, 300.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("mother" to 8L), 9L))
        SandboxSession(scene).use { session ->
            session.select(Position(500.0, 300.0))
            fun card(name: String): Actor {
                val bounds = NativeActionPanel.customActions.getValue(name)
                val unload = NativeActionPanel.transportWithCustomAction
                assertFalse(bounds.x < unload.x + unload.width && bounds.x + bounds.width > unload.x &&
                    bounds.y < unload.y + unload.height && bounds.y + bounds.height > unload.y)
                return Actor().apply {
                    setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
                    addListener(CustomUnitActionButtonInput({ session }, { true }, name))
                }
            }
            val surface = card("surface")
            press(surface, false)
            assertFalse(session.actionMenu.unitActions.single().queued)
            press(surface, true)
            assertFalse(session.actionMenu.unitActions.single().queued)
            press(surface, false)
            assertTrue(session.actionMenu.unitActions.single().queued)
            session.tick()
            assertEquals("nautilusSubmarineSurface", session.observation.units.single().typeId)
            val dive = card("dive")
            press(dive, true); press(dive, false)
            assertTrue(session.actionMenu.unitActions.single().queued)
            repeat(135) { session.tick() }
            assertEquals("nautilusSubmarine", session.observation.units.single().typeId)
        }
    }

    private fun press(actor: Actor, down: Boolean, button: Int = Input.Buttons.LEFT) = actor.fire(InputEvent().apply {
        type = if (down) InputEvent.Type.touchDown else InputEvent.Type.touchUp
        this.button = button; pointer = 0; stageX = actor.x + 10f; stageY = actor.y + 10f; touchFocus = false
    })
}
