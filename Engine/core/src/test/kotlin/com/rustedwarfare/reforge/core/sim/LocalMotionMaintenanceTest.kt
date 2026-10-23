package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class LocalMotionMaintenanceTest {
    private val segment = LocalMotionSegment.create(Position(0.0, 0.0), Position(200.0, 0.0))
    private fun step(x: Double, time: Double, positive: Boolean = true, capture: Boolean = false,
                     correction: Double = 0.0, allowed: Boolean = true): LocalMotionProgress =
        LocalMotionMaintenance.advance(segment, time, 1.0, positive, (x - 40.0) * (x - 40.0), capture, correction,
            { (x - it.x) * (x - it.x) + it.y * it.y }, { allowed })

    @Test fun next_closer_requires_strict_time_and_distance_and_preserves_time() {
        assertEquals(Position(40.0, 0.0), step(60.0, 39.0).segment?.aim)
        val after = step(60.0, 40.0)
        assertEquals(Position(60.0, 0.0), after.segment?.aim)
        assertEquals(41.0, after.activeTime)
        // Equal distances select ordinary capture (reset), rather than the waiting skip.
        val equal = step(50.0, 40.0, capture = true)
        assertEquals(Position(60.0, 0.0), equal.segment?.aim)
        assertEquals(0.0, equal.activeTime)
    }

    @Test fun timeout_is_strict_and_later_conditions_use_the_old_distance() {
        assertEquals(Position(40.0, 0.0), step(0.0, 199.0).segment?.aim)
        assertEquals(Position(60.0, 0.0), step(0.0, 200.0).segment?.aim)
        assertEquals(Position(40.0, 0.0), step(-20.0, 200.0).segment?.aim) // old d² == 3600
        // First timeout moves 40->60; next is80, d²225 < old625 but > new-current25.
        val two = step(65.0, 200.0)
        assertEquals(Position(80.0, 0.0), two.segment?.aim)
        assertEquals(201.0, two.activeTime)
        val staleCapture = step(45.0, 200.0, capture = true)
        assertEquals(Position(80.0, 0.0), staleCapture.segment?.aim)
        assertEquals(0.0, staleCapture.activeTime)
    }

    @Test fun closed_propulsion_pauses_time_and_never_uses_inertial_speed_as_qualification() {
        val paused = step(65.0, 201.0, positive = false)
        assertEquals(Position(40.0, 0.0), paused.segment?.aim)
        assertEquals(201.0, paused.activeTime)
        val captured = step(45.0, 201.0, positive = false, capture = true)
        assertEquals(Position(60.0, 0.0), captured.segment?.aim)
        assertEquals(0.0, captured.activeTime)
    }

    @Test fun refresh_clears_only_private_progress_and_owner_safety_blocks_each_skip() {
        assertNotNull(step(-100.0, 599.0).segment)
        assertNull(step(-100.0, 600.0).segment)
        assertNotNull(step(0.0, 79.0, correction = 31.0).segment)
        assertNotNull(step(0.0, 80.0, correction = 30.0).segment)
        val refreshed = step(0.0, 80.0, correction = 31.0)
        assertNull(refreshed.segment); assertEquals(0.0, refreshed.activeTime)
        val unsafe = step(65.0, 200.0, allowed = false)
        assertSame(segment, unsafe.segment)
        assertEquals(201.0, unsafe.activeTime)
        assertEquals(Position(40.0, 0.0), segment.aim) // no mutable cursor alias
    }

    @Test fun each_sequential_consumption_gets_its_own_owner_safety_check() {
        val checked = mutableListOf<Position>()
        val result = LocalMotionMaintenance.advance(segment, 200.0, 1.0, true, 625.0, false, 0.0,
            { (65.0 - it.x) * (65.0 - it.x) }, { checked += it; it.x == 60.0 })
        assertEquals(listOf(Position(60.0, 0.0), Position(80.0, 0.0)), checked)
        assertEquals(Position(60.0, 0.0), result.segment?.aim)
        assertEquals(201.0, result.activeTime)
    }
}
