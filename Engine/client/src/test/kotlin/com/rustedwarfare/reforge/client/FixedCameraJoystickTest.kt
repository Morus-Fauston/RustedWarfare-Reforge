package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.OfflineCameraMotion
import com.rustedwarfare.reforge.core.sim.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixedCameraJoystickTest {
    @Test fun held_direction_moves_each_frame_and_only_the_owner_can_release() {
        val stick = FixedCameraJoystick()
        val view = zoomedView()
        assertTrue(stick.press(2, 1.0, 0.0))
        assertFalse(stick.press(1, -1.0, 0.0))
        assertFalse(stick.drag(1, -1.0, 0.0))
        assertFalse(stick.release(1))
        assertEquals(2, stick.pointer)
        assertTrue(stick.apply(view, .02) {})
        val first = view.camera.center
        assertTrue(first.x > 300)
        assertTrue(stick.apply(view, .02) {})
        assertTrue(view.camera.center.x > first.x)
        assertTrue(stick.release(2))
        val stopped = view.camera
        assertFalse(stick.apply(view, .03) {})
        assertEquals(stopped, view.camera)
        assertNull(stick.pointer)
    }

    @Test fun global_cancellation_clears_a_nonzero_owner_slot_and_does_not_resubmit_motion() {
        val stick = FixedCameraJoystick()
        val view = zoomedView()
        assertTrue(stick.press(3, 0.0, 1.0))
        assertTrue(stick.apply(view, .02) {})
        assertTrue(view.camera.center.y < 200)
        assertTrue(stick.cancel())
        assertFalse(stick.cancel())
        val stopped = view.camera
        assertFalse(stick.apply(view, .02) {})
        assertFalse(stick.release(3))
        assertEquals(Position(0.0, 0.0), stick.direction)
        assertEquals(stopped, view.camera)
    }

    @Test fun a_directional_hold_takes_over_following_and_respects_the_existing_map_edge() {
        val view = zoomedView()
        val control = OfflineCameraController(view)
        val stick = FixedCameraJoystick()
        val tracked = Position(300.0, 200.0)
        control.advance(0, listOf(OfflineCameraMotion.Follow("a"))) { tracked }
        assertTrue(control.followsUnit)
        assertTrue(stick.press(0, 0.0, 0.0))
        assertFalse(stick.apply(view, .02) { control.takeOver("joystick") })
        assertTrue(control.followsUnit)
        assertTrue(stick.drag(0, 5.0, 0.0))
        assertEquals(Position(1.0, 0.0), stick.direction)
        repeat(200) { stick.apply(view, .03) { control.takeOver("joystick") } }
        assertFalse(control.followsUnit)
        assertEquals(Position(450.0, 200.0), view.camera.center)
        assertFalse(stick.apply(view, .05) {})
    }

    private fun zoomedView() = MapProjection(600.0, 400.0).apply {
        restoreCamera(camera.copy(zoom = FixedCameraJoystick.startingZoom))
    }
}
