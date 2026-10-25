package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Position
import kotlin.test.*

class CameraProjectionTest {
    @Test fun camera_fits_zooms_at_pointer_pans_clamps_and_rejects_letterbox_and_invalid_input() {
        val map = MapProjection(1200.0, 800.0, MapArea(16.0, 64.0, 704.0, 512.0))
        assertEquals(16f, map.x(0.0))
        assertEquals(720f, map.x(1200.0))
        assertNull(map.toWorld(15.0, 300.0))
        assertNull(map.toWorld(20.0, 575.0))
        val centerX = map.x(600.0).toDouble()
        val centerY = map.y(400.0).toDouble()
        map.zoomAt(centerX, centerY, 2.0)
        assertEquals(2.0, map.zoom)
        val anchor = map.toWorld(450.0, 360.0)!!
        map.zoomAt(450.0, 360.0, 1.2)
        assertEquals(anchor.x, map.toWorld(450.0, 360.0)!!.x, 1e-9)
        assertEquals(anchor.y, map.toWorld(450.0, 360.0)!!.y, 1e-9)
        val oldX = map.x(anchor.x)
        val oldY = map.y(anchor.y)
        map.pan(20.0, -10.0)
        assertEquals(oldX + 20f, map.x(anchor.x), .001f)
        assertEquals(oldY - 10f, map.y(anchor.y), .001f)
        map.focus(Position(0.0, 0.0))
        assertEquals(16f, map.x(0.0), .001f)
        assertEquals(576f, map.y(0.0), .001f)
        map.zoomAt(368.0, 320.0, 100.0)
        assertEquals(4.0, map.zoom)
        map.zoomAt(368.0, 320.0, .001)
        assertEquals(1.0, map.zoom)
        val before = map.x(100.0)
        map.pan(Double.NaN, 0.0)
        map.focus(Position(Double.POSITIVE_INFINITY, 0.0))
        map.zoomAt(Double.NaN, 0.0, 2.0)
        assertEquals(before, map.x(100.0))
    }
}
