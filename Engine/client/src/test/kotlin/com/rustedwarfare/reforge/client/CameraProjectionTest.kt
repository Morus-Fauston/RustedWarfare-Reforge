package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Position
import kotlin.test.*

class CameraProjectionTest {
    @Test fun area_fit_pointer_anchor_and_clamped_pan_share_one_projection() {
        val map = MapProjection(600.0, 400.0, MapArea(16.0, 64.0, 704.0, 512.0))
        assertEquals(16f, map.x(0.0))
        assertEquals(576.0 - 64.0 / 3.0, map.y(0.0).toDouble(), 0.0001)
        val before = assertNotNull(map.toWorld(280.0, 320.0))
        map.zoomAt(280.0, 320.0, -1.0)
        assertEquals(1.2, map.camera.zoom, 0.000001)
        val after = assertNotNull(map.toWorld(280.0, 320.0))
        assertEquals(before.x, after.x, 0.000001); assertEquals(before.y, after.y, 0.000001)
        map.panBy(10000.0, 10000.0)
        assertEquals(250.0, map.camera.center.x, 0.000001)
        assertEquals(2400.0 / 11.0, map.camera.center.y, 0.000001)
        map.zoomAt(368.0, 320.0, -100.0)
        assertEquals(4.0, map.camera.zoom)
        map.centerOn(Position(600.0, 0.0))
        assertEquals(525.0, map.camera.center.x, 0.000001)
        assertEquals(600.0 / 11.0, map.camera.center.y, 0.000001)
        assertNull(map.toWorld(720.0, 320.0))
        assertNull(map.toWorld(368.0, 64.0))
        assertNull(map.toWorld(Double.NaN, 100.0))
        val camera = map.camera
        map.zoomAt(Double.NaN, 320.0, -1.0); map.panBy(Double.POSITIVE_INFINITY, 1.0)
        map.centerOn(Position(Double.NaN, 10.0))
        assertEquals(camera, map.camera)
        map.zoomAt(368.0, 320.0, 100.0)
        assertEquals(MapCamera(Position(300.0, 200.0)), map.camera)
    }
}
