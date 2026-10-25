package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.InputMultiplexer
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class SandboxInputTest {
    @Test fun actual_multiplexer_keeps_stage_owned_press_and_panel_release_out_of_map_clicks() {
        SandboxSession(ControlledScenario.load(root())).use { s ->
            val map = MapProjection(600.0, 400.0)
            val adapter = input(s, map)
            var stageOwns = false
            val stageBoundary = object : InputAdapter() {
                override fun touchDown(x: Int, y: Int, pointer: Int, button: Int): Boolean {
                    stageOwns = x >= 650; return stageOwns
                }
                override fun touchDragged(x: Int, y: Int, pointer: Int) = stageOwns
                override fun touchUp(x: Int, y: Int, pointer: Int, button: Int): Boolean {
                    val owned = stageOwns; stageOwns = false; return owned
                }
            }
            val mux = InputMultiplexer(stageBoundary, adapter)
            mux.touchDown(700, 290, 0, Input.Buttons.LEFT)
            mux.touchDragged(134, 290, 0)
            mux.touchUp(134, 290, 0, Input.Buttons.LEFT)
            assertTrue(s.selectedIds.isEmpty())
            mux.touchDown(80, 350, 0, Input.Buttons.LEFT)
            mux.touchDragged(700, 250, 0)
            assertNotNull(adapter.box)
            mux.touchUp(700, 250, 0, Input.Buttons.LEFT)
            assertEquals(setOf("a"), s.selectedIds)
            assertNull(adapter.box)
            assertFalse(mux.touchUp(454, 290, 0, Input.Buttons.LEFT))
            assertEquals(setOf("a"), s.selectedIds)
        }
    }

    @Test fun actual_adapter_routes_all_ten_keys_control_precedence_addition_and_real_stop() {
        SandboxSession(ControlledScenario.load(root())).use { s ->
            val map = MapProjection(600.0, 400.0)
            val keys = mutableSetOf<Int>()
            val adapter = input(s, map, keys = keys)
            val numbers = listOf(Input.Keys.NUM_0, Input.Keys.NUM_1, Input.Keys.NUM_2, Input.Keys.NUM_3,
                Input.Keys.NUM_4, Input.Keys.NUM_5, Input.Keys.NUM_6, Input.Keys.NUM_7, Input.Keys.NUM_8, Input.Keys.NUM_9)
            for ((index, key) in numbers.withIndex()) {
                s.select(Position(110.0, 210.0))
                keys += Input.Keys.CONTROL_RIGHT; keys += Input.Keys.SHIFT_LEFT
                assertTrue(adapter.keyDown(key))
                assertEquals(1, s.groupCount(index))
                keys.clear(); s.select(Position(0.0, 0.0))
                assertTrue(adapter.keyDown(key)); assertEquals(setOf("a"), s.selectedIds)
            }
            s.select(Position(430.0, 210.0))
            keys += Input.Keys.SHIFT_RIGHT
            adapter.keyDown(Input.Keys.NUM_1)
            assertEquals(setOf("a", "b"), s.selectedIds)
            keys.clear()
            s.select(Position(110.0, 210.0))
            assertIs<ActionResult.Accepted>(s.move(Position(50.0, 330.0)))
            repeat(70) { s.tick() }
            assertTrue(s.observation.units.single { it.unitId == "a" }.speed > 0)
            assertTrue(adapter.keyDown(Input.Keys.S))
            repeat(30) { s.tick() }
            val stopped = s.observation.units.single { it.unitId == "a" }
            assertNull(stopped.moveTarget); assertEquals(0.0, stopped.speed)
            assertFalse(adapter.keyDown(Input.Keys.F1))
        }
    }

    @Test fun actual_adapter_pointer_modifiers_camera_and_letterbox_are_presentation_only() {
        SandboxSession(ControlledScenario.load(root())).use { s ->
            val map = MapProjection(600.0, 400.0)
            val keys = mutableSetOf<Int>()
            val adapter = SandboxInput({ s }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { it in keys }, insideViewport = { x, _ -> x >= 0 })
            fun click(x: Int, y: Int) {
                assertTrue(adapter.touchDown(x, y, 0, Input.Buttons.LEFT))
                assertTrue(adapter.touchUp(x, y, 0, Input.Buttons.LEFT))
            }
            click(134, 290)
            keys += Input.Keys.SHIFT_LEFT; click(334, 290)
            assertEquals(setOf("a", "f"), s.selectedIds)
            keys += Input.Keys.CONTROL_LEFT; click(134, 290)
            assertEquals(setOf("f"), s.selectedIds)
            keys.clear()
            assertFalse(adapter.touchDown(-1, 290, 0, Input.Buttons.LEFT))
            assertFalse(adapter.touchUp(134, 290, 0, Input.Buttons.LEFT))
            val units = s.observation.units
            adapter.mouseMoved(324, 300)
            assertTrue(adapter.scrolled(0f, -4f))
            assertTrue(map.camera.zoom > 1)
            val zoomed = map.camera
            adapter.touchDown(324, 300, 0, Input.Buttons.MIDDLE)
            adapter.touchDragged(364, 280, 0)
            adapter.touchUp(364, 280, 0, Input.Buttons.MIDDLE)
            assertNotEquals(zoomed, map.camera)
            assertEquals(units, s.observation.units)
            assertEquals(setOf("f"), s.selectedIds)
            adapter.mouseMoved(700, 300)
            val camera = map.camera
            assertFalse(adapter.scrolled(0f, -1f)); assertEquals(camera, map.camera)
        }
    }

    @Test fun actual_adapter_waits_for_release_and_cancels_without_replaying() {
        SandboxSession(ControlledScenario.load(root())).use { s ->
            val map = MapProjection(600.0, 400.0)
            val adapter = input(s, map)
            assertTrue(adapter.touchDown(134, 290, 0, Input.Buttons.LEFT))
            assertTrue(s.selectedIds.isEmpty(), "Down must establish a gesture, not commit the old point selection")
            assertTrue(adapter.touchUp(134, 290, 0, Input.Buttons.LEFT))
            assertEquals(setOf("a"), s.selectedIds)
            adapter.touchDown(454, 290, 0, Input.Buttons.LEFT)
            adapter.cancel()
            assertFalse(adapter.touchUp(454, 290, 0, Input.Buttons.LEFT))
            assertEquals(setOf("a"), s.selectedIds)
        }
    }

    @Test fun actual_adapter_ignores_ui_origin_paused_editing_and_non_primary_pointer() {
        SandboxSession(ControlledScenario.load(root())).use { s ->
            val map = MapProjection(600.0, 400.0)
            var active = true
            var editing = false
            val keys = mutableSetOf<Int>()
            val adapter = input(s, map, { active }, { editing }, keys)
            s.select(Position(110.0, 210.0)); s.saveGroup(1)
            s.select(Position(0.0, 0.0))
            assertFalse(adapter.touchDown(700, 300, 0, Input.Buttons.LEFT))
            assertFalse(adapter.touchUp(134, 290, 0, Input.Buttons.LEFT))
            assertTrue(s.selectedIds.isEmpty())
            assertFalse(adapter.touchDown(134, 290, 1, Input.Buttons.LEFT))
            active = false
            assertFalse(adapter.touchDown(134, 290, 0, Input.Buttons.LEFT))
            assertFalse(adapter.keyDown(Input.Keys.NUM_1))
            active = true; editing = true
            assertFalse(adapter.keyDown(Input.Keys.NUM_1))
            assertFalse(adapter.keyDown(Input.Keys.S))
            assertFalse(adapter.scrolled(0f, -2f))
            assertEquals(MapCamera(Position(300.0, 200.0)), map.camera)
            assertTrue(s.selectedIds.isEmpty())
        }
    }

    private fun input(s: SandboxSession, map: MapProjection, active: () -> Boolean = { true },
                      editing: () -> Boolean = { false }, keys: Set<Int> = emptySet()) =
        SandboxInput({ s }, { map }, { x, y -> Position(x.toDouble(), y.toDouble()) },
            active, editing, { it in keys })

    private fun root(): Path = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-006/20261026-e-selection-contract-01/input")
}
