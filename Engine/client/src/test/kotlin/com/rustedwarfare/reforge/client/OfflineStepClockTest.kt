package com.rustedwarfare.reforge.client

import kotlin.test.*

class OfflineStepClockTest {
    @Test fun upshift_preserves_fraction_and_rejected_frame_time_never_changes_the_existing_budget() {
        val clock = OfflineStepClock()
        assertEquals(OfflinePacingFrame(0, .5), clock.advance(1.0 / 120) {})
        clock.setRate(OfflineRate.QUADRUPLE)
        assertEquals(OfflinePacingFrame(2, .5), clock.advance(1.0 / 120) {})
        for (invalid in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { clock.advance(invalid) { fail("Invalid time reached a simulation tick") } }
        }
        assertEquals(OfflinePacingFrame(1, 0.0), clock.advance(1.0 / 480) {})
    }

    @Test fun platform_catchup_discard_preserves_the_players_pause_and_rate() {
        val clock = OfflineStepClock()
        clock.setRate(OfflineRate.QUADRUPLE)
        clock.setPaused(true)
        clock.discardCatchup()
        assertEquals(OfflineRate.QUADRUPLE, clock.rate)
        assertTrue(clock.isPaused)
        assertEquals(OfflinePacingFrame(0, 0.0), clock.advance(120.0) { fail("Suspended world advanced") })
        clock.discardCatchup()
        assertTrue(clock.isPaused)
        clock.setPaused(false)
        assertEquals(OfflinePacingFrame(4, 0.0), clock.advance(1.0 / 60) {})
    }

    @Test fun a_failed_callback_is_never_counted_as_a_completed_step() {
        val clock = OfflineStepClock()
        val original = IllegalStateException("failed complete step")
        assertSame(original, assertFailsWith<IllegalStateException> { clock.advance(1.0 / 60) { throw original } })
        assertEquals(OfflinePacingFrame(1, 0.0), clock.advance(0.0) {})
    }

    @Test fun measured_rate_reports_completed_steps_and_exposes_slow_frames_instead_of_claiming_requested_speed() {
        val clock = OfflineStepClock()
        clock.setRate(OfflineRate.QUADRUPLE)
        assertNull(clock.actualMultiplier)
        repeat(64) { clock.advance(1.0 / 128) {} }
        assertEquals(4.0, clock.actualMultiplier)
        clock.setPaused(true)
        assertEquals(0.0, clock.actualMultiplier)
        clock.reset()
        assertNull(clock.actualMultiplier)
        clock.setRate(OfflineRate.QUADRUPLE)
        clock.advance(.5) {}
        assertEquals(8.0 / 30.0, clock.actualMultiplier)
        assertTrue(checkNotNull(clock.actualMultiplier) < clock.rate.multiplier)
    }

    @Test fun pausing_keeps_the_rate_without_scheduling_paused_wall_time_or_a_resume_catchup() {
        val clock = OfflineStepClock()
        clock.setRate(OfflineRate.QUADRUPLE)
        var completed = 0
        assertEquals(OfflinePacingFrame(8, 112.0), clock.advance(.5) { completed++ })
        clock.setPaused(true)
        assertEquals(OfflinePacingFrame(0, 112.0), clock.advance(60.0) { completed++ })
        assertEquals(8, completed)
        assertEquals(OfflineRate.QUADRUPLE, clock.rate)
        clock.setPaused(false)
        assertEquals(OfflinePacingFrame(0, 0.0), clock.advance(0.0) { completed++ })
        clock.reset()
        assertEquals(OfflineRate.NORMAL, clock.rate)
        assertFalse(clock.isPaused)
        assertEquals(OfflinePacingFrame(1, 0.0), clock.advance(1.0 / 60) { completed++ })
    }

    @Test fun ordinary_frames_keep_bounded_backlog_but_slowing_discards_old_wall_catchup() {
        val clock = OfflineStepClock()
        clock.setRate(OfflineRate.QUADRUPLE)
        assertEquals(OfflinePacingFrame(8, 112.0), clock.advance(.5) {})
        assertEquals(OfflinePacingFrame(8, 104.0), clock.advance(0.0) {})
        clock.setRate(OfflineRate.NORMAL)
        assertEquals(OfflinePacingFrame(0, 0.0), clock.advance(0.0) {})
        assertEquals(OfflinePacingFrame(1, 0.0), clock.advance(1.0 / 60) {})
    }

    @Test fun rates_schedule_more_complete_steps_without_changing_the_step_callback() {
        for ((rate, expected) in listOf(OfflineRate.NORMAL to 10, OfflineRate.DOUBLE to 20, OfflineRate.QUADRUPLE to 40)) {
            val clock = OfflineStepClock()
            clock.setRate(rate)
            var completed = 0
            repeat(10) {
                val frame = clock.advance(1.0 / 60) { completed++ }
                assertEquals(rate.multiplier, frame.executedSteps)
                assertEquals(0.0, frame.pendingSteps)
            }
            assertEquals(expected, completed)
        }
    }
}
