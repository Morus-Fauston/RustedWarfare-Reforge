package com.rustedwarfare.reforge.client

import kotlin.test.*

class MapGestureTest {
    @Test fun pan_and_command_own_their_pointer_and_never_generate_selection() {
        val gesture = MapGesture(MapArea(16.0, 64.0, 704.0, 512.0))
        assertTrue(gesture.begin(UiPoint(100.0, 100.0), MapButton.PAN, 0, SelectionMode.REPLACE, emptySet()))
        assertFalse(gesture.begin(UiPoint(120.0, 100.0), MapButton.SELECT, 0, SelectionMode.REPLACE, emptySet()))
        assertNull(gesture.drag(UiPoint(200.0, 200.0), 1))
        assertEquals(UiPoint(20.0, -10.0), gesture.drag(UiPoint(120.0, 90.0), 0))
        assertNull(gesture.rectangle)
        assertNull(gesture.end(UiPoint(120.0, 90.0), MapButton.PAN, 0))
        assertTrue(gesture.begin(UiPoint(100.0, 100.0), MapButton.COMMAND, 0, SelectionMode.REPLACE, emptySet()))
        assertIs<MapGestureResult.Command>(gesture.end(UiPoint(100.0, 100.0), MapButton.COMMAND, 0))
        assertTrue(gesture.begin(UiPoint(100.0, 100.0), MapButton.COMMAND, 0, SelectionMode.REPLACE, emptySet()))
        assertNull(gesture.end(UiPoint(900.0, 100.0), MapButton.COMMAND, 0))
        assertFalse(gesture.begin(UiPoint(100.0, 100.0), MapButton.SELECT, 1, SelectionMode.REPLACE, emptySet()))
    }
    @Test fun one_press_produces_one_click_or_box_and_cancel_never_synthesizes_a_click() {
        val gesture = MapGesture(MapArea(16.0, 64.0, 704.0, 512.0))
        val selected = linkedSetOf("a")
        assertTrue(gesture.begin(UiPoint(100.0, 100.0), MapButton.SELECT, 0, SelectionMode.ADD, selected))
        selected += "late"
        gesture.drag(UiPoint(108.0, 108.0), 0)
        assertNull(gesture.rectangle)
        assertIs<MapGestureResult.Click>(gesture.end(UiPoint(108.0, 108.0), MapButton.SELECT, 0))
        assertNull(gesture.end(UiPoint(108.0, 108.0), MapButton.SELECT, 0))
        assertTrue(gesture.begin(UiPoint(100.0, 100.0), MapButton.SELECT, 0, SelectionMode.TOGGLE, setOf("a")))
        gesture.drag(UiPoint(109.0, 100.0), 0)
        val box = assertIs<MapGestureResult.Box>(gesture.end(UiPoint(900.0, 30.0), MapButton.SELECT, 0))
        assertEquals(UiPoint(720.0, 64.0), box.end)
        assertEquals(setOf("a"), box.baseSelection)
        assertEquals(SelectionMode.TOGGLE, box.mode)
        assertTrue(gesture.begin(UiPoint(100.0, 100.0), MapButton.SELECT, 0, SelectionMode.REPLACE, emptySet()))
        gesture.cancel()
        assertNull(gesture.end(UiPoint(100.0, 100.0), MapButton.SELECT, 0))
        assertFalse(gesture.begin(UiPoint(800.0, 300.0), MapButton.SELECT, 0, SelectionMode.REPLACE, emptySet()))
        assertFalse(gesture.begin(UiPoint(Double.NaN, 300.0), MapButton.SELECT, 0, SelectionMode.REPLACE, emptySet()))
        assertEquals(SelectionMode.TOGGLE, selectionMode(true, true))
    }
}
