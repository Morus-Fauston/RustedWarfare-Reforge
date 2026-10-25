package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Position
import kotlin.test.*

class MinimapTest {
    @Test fun minimap_click_and_owned_drag_only_return_bounded_camera_positions() {
        val mini = Minimap(600.0, 400.0)
        assertEquals(Position(300.0, 200.0), mini.locate(840.0, 512.0))
        assertEquals(Position(0.0, 0.0), mini.locate(752.0, 568.0))
        assertNull(mini.locate(928.0, 512.0)); assertNull(mini.locate(840.0, 456.0))
        assertNull(mini.locate(751.0, 512.0)); assertNull(mini.locate(Double.NaN, 512.0))
        assertEquals(Position(600.0, 400.0), mini.locate(1200.0, 0.0, true))
        assertEquals(840f, mini.x(300.0)); assertEquals(512f, mini.y(200.0))
    }
}
