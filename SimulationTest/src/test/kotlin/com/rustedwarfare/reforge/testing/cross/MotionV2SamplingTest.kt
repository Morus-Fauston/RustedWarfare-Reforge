package com.rustedwarfare.reforge.testing.cross

import kotlin.test.*
import com.rustedwarfare.reforge.testing.cross.MotionV2Sampling.Point
import com.rustedwarfare.reforge.testing.cross.MotionV2Math.Status

class MotionV2SamplingTest {
    private fun trace(last: Int=18, heading: Double=90.0)=(0..last).map { i->Point(i.toDouble(),0,0,i.toDouble(),0.0,heading) }
    @Test fun same_q_record_order_and_age_are_causal_without_interpolation() {
        val points=listOf(Point(0.0,0,-1,0.0,0.0,90.0),Point(0.0,0,0,1.0,0.0,90.0),Point(1.0,0,0,2.0,0.0,90.0))
        assertEquals(points[1],MotionV2Sampling.select(points,0.0,1.2))
        assertEquals(points[1],MotionV2Sampling.select(points,.9,1.2))
        assertEquals(points[2],MotionV2Sampling.select(points,2.0,1.0))
        assertNull(MotionV2Sampling.select(points,2.0001,1.0))
        assertNull(MotionV2Sampling.select(points,-.1,1.0))
    }
    @Test fun every_predeclared_point_and_extra_offset_is_retained() {
        val a=trace();val result=MotionV2Sampling.evaluate(a,a,listOf(0.0),18.0)
        assertEquals(listOf(0.0,3.0,6.0,9.0,12.0,18.0),result.points.map { it.requestedQ })
        assertEquals(Status.PASS,result.position.status);assertEquals(Status.PASS,result.heading.status)
        assertEquals(Status.PASS,assertNotNull(result.speed).status)
        assertEquals(4,result.speed.expectedCount);assertTrue(result.points.all { it.positionError==0.0 })
    }
    @Test fun circular_heading_and_actual_q_speed_windows_use_their_own_sample_times() {
        val a=trace(12,359.0);val b=trace(12,1.0)
        val result=MotionV2Sampling.evaluate(a,b,emptyList(),12.0)
        assertEquals(2.0,result.heading.value?.rmse)
        assertTrue(result.points.all { it.headingError==2.0 })
        val original=(0..12).map { i->Point(i.toDouble()-.5,0,0,i.toDouble()-.5,0.0,90.0) }.drop(1)
        val withInitial=listOf(Point(0.0,0,0,0.0,0.0,90.0))+original
        val speed=MotionV2Sampling.evaluate(trace(12),withInitial,emptyList(),12.0).speed
        assertEquals(0.0,assertNotNull(speed).value?.maximum)
    }
    @Test fun missing_stale_and_input_mismatch_never_shrink_the_required_point_count() {
        val full=trace(12);val shortened=trace(6)
        val missing=MotionV2Sampling.evaluate(shortened,full,emptyList(),12.0)
        assertEquals(3,missing.position.expectedCount);assertEquals(Status.INSUFFICIENT,missing.position.status)
        val mismatch=full.map { if(it.q>=6.0)it.copy(input=1)else it }
        val result=MotionV2Sampling.evaluate(mismatch,full,emptyList(),12.0)
        assertEquals(Status.INSUFFICIENT,result.position.status);assertEquals(3,result.position.expectedCount)
        val failing=shortened.map { it.copy(x=it.x+5.0) }
        assertEquals(Status.FAIL,MotionV2Sampling.evaluate(failing,full,emptyList(),12.0).position.status)
    }
    @Test fun phase_or_input_boundaries_make_only_those_speed_windows_inapplicable() {
        val original=trace(12).map { it.copy(phase=if(it.q<6)0 else 1) }
        val result=MotionV2Sampling.evaluate(trace(12),original,emptyList(),12.0)
        assertEquals(listOf(6.0),result.notApplicableWindows)
        assertEquals(1,result.speed?.expectedCount);assertEquals(Status.PASS,result.speed?.status)
        val everyBoundary=trace(12).map { it.copy(phase=it.q.toLong()) }
        val none=MotionV2Sampling.evaluate(trace(12),everyBoundary,emptyList(),12.0)
        assertEquals(0,none.speed?.expectedCount);assertEquals(Status.INSUFFICIENT,none.speed?.status)
    }
    @Test fun stationary_heading_is_initial_only_and_speed_is_not_an_arrival_requirement() {
        val a=trace(12);val b=a.map { if(it.q==0.0)it else it.copy(heading=180.0) }
        val result=MotionV2Sampling.evaluate(a,b,emptyList(),12.0,stationary=true)
        assertEquals(1,result.heading.expectedCount);assertEquals(Status.PASS,result.heading.status);assertNull(result.speed)
        // This pure numerical slice does not prove the independent full-raw stationary drift gate.
    }
    @Test fun malformed_or_reordered_traces_and_horizon_are_rejected() {
        assertFailsWith<IllegalArgumentException>{MotionV2Sampling.evaluate(trace().reversed(),trace(),emptyList(),18.0)}
        assertFailsWith<IllegalArgumentException>{MotionV2Sampling.evaluate(trace(),trace(),emptyList(),Double.NaN)}
        assertFailsWith<IllegalArgumentException>{MotionV2Sampling.evaluate(trace(),trace().map { it.copy(x=Double.NaN) },emptyList(),18.0)}
    }
    @Test fun interior_phase_or_input_switch_cannot_be_hidden_by_equal_window_end_labels() {
        val full=trace(12)
        for (points in listOf(full.map { if(it.q==3.0)it.copy(phase=1)else it },
            full.map { if(it.q==3.0)it.copy(input=1)else it })) {
            assertFailsWith<IllegalArgumentException>{MotionV2Sampling.evaluate(full,points,emptyList(),12.0)}
        }
    }
}
