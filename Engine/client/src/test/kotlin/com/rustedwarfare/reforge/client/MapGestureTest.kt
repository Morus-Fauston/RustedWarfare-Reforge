package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Position
import kotlin.test.*

class MapGestureTest {
    @Test fun vertical_threshold_latched_drag_and_invalid_release_never_replay_clicks() {
        val gesture = MapGesture(MapProjection(600.0, 400.0))
        for (dy in listOf(-9.0, 9.0)) {
            gesture.begin(200.0, 300.0, MapButton.SELECT, SelectionMode.REPLACE, emptySet())
            gesture.drag(200.0, 300.0 + dy)
            assertNotNull(gesture.box)
            assertIs<MapIntent.Box>(gesture.release(200.0, 300.0))
        }
        for (button in MapButton.entries) {
            gesture.begin(200.0, 300.0, button, SelectionMode.REPLACE, emptySet())
            gesture.drag(Double.NaN, 400.0)
            assertNull(gesture.box)
            assertNull(gesture.release(Double.POSITIVE_INFINITY, 400.0))
            assertNull(gesture.release(200.0, 300.0))
        }
    }
    @Test fun one_press_owns_one_click_or_strict_eight_unit_box_with_press_selection() {
        val map = MapProjection(600.0, 400.0)
        val gesture = MapGesture(map)
        assertFalse(gesture.begin(650.0, 300.0, MapButton.SELECT, SelectionMode.REPLACE, emptySet()))
        assertNull(gesture.release(100.0, 300.0))
        assertFalse(gesture.begin(Double.NaN, 300.0, MapButton.SELECT, SelectionMode.REPLACE, emptySet()))
        for (dx in listOf(-1.0, 1.0)) for (dy in listOf(-1.0, 1.0)) {
            val selected = linkedSetOf("a")
            assertTrue(gesture.begin(200.0, 300.0, MapButton.SELECT, SelectionMode.ADD, selected))
            selected.clear()
            gesture.drag(200.0 + dx * 8, 300.0 + dy * 8)
            assertNull(gesture.box)
            val click = assertIs<MapIntent.Click>(gesture.release(200.0 + dx * 8, 300.0 + dy * 8))
            assertEquals(SelectionMode.ADD, click.mode)
            assertNull(gesture.release(200.0, 300.0))
            assertTrue(gesture.begin(200.0, 300.0, MapButton.SELECT, SelectionMode.TOGGLE, setOf("a")))
            gesture.drag(200.0 + dx * 9, 300.0)
            assertNotNull(gesture.box)
            val box = assertIs<MapIntent.Box>(gesture.release(200.0 + dx * 9, 300.0 + dy * 9))
            assertEquals(Position(176.0, 200.0), box.start)
            assertEquals(setOf("a"), box.selectionAtStart)
            assertEquals(SelectionMode.TOGGLE, box.mode)
            assertFailsWith<UnsupportedOperationException> { (box.selectionAtStart as MutableSet).clear() }
        }
        gesture.begin(200.0, 300.0, MapButton.SELECT, SelectionMode.REPLACE, emptySet())
        val clipped = assertIs<MapIntent.Box>(gesture.release(900.0, 0.0))
        assertEquals(Position(600.0, 400.0), clipped.end)
        gesture.begin(200.0, 300.0, MapButton.SELECT, SelectionMode.REPLACE, emptySet())
        gesture.cancel(); assertNull(gesture.release(200.0, 300.0))
        gesture.begin(200.0, 300.0, MapButton.COMMAND, SelectionMode.REPLACE, emptySet())
        assertIs<MapIntent.Command>(gesture.release(200.0, 300.0))
        assertNull(gesture.release(200.0, 300.0))
        map.zoomAt(300.0, 300.0, -4.0)
        val camera = map.camera
        gesture.begin(200.0, 300.0, MapButton.PAN, SelectionMode.REPLACE, emptySet())
        gesture.drag(240.0, 280.0)
        assertNotEquals(camera, map.camera)
        assertNull(gesture.release(240.0, 280.0))
        assertNull(gesture.box)
    }
}
