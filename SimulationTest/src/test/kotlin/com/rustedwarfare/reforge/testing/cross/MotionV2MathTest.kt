package com.rustedwarfare.reforge.testing.cross

import kotlin.test.*
import com.rustedwarfare.reforge.testing.cross.MotionV2Math.Interval
import com.rustedwarfare.reforge.testing.cross.MotionV2Math.IntervalResult
import com.rustedwarfare.reforge.testing.cross.MotionV2Math.Status
import com.rustedwarfare.reforge.testing.cross.MotionV2Math.Vector

class MotionV2MathTest {
    @Test fun worst_interval_difference_distinguishes_proven_failure_from_uncertainty() {
        assertEquals(IntervalResult(Status.PASS, 1.0, 3.0), MotionV2Math.interval(Interval(0.0,1.0), Interval(2.0,3.0),3.0))
        assertEquals(IntervalResult(Status.FAIL, 4.0, 6.0), MotionV2Math.interval(Interval(0.0,1.0), Interval(5.0,6.0),3.0))
        assertEquals(IntervalResult(Status.INSUFFICIENT, 1.0, 4.0), MotionV2Math.interval(Interval(0.0,2.0), Interval(3.0,4.0),3.0))
        // Overlap alone does not prove a narrow enough worst difference. No old width gate.
        assertEquals(Status.INSUFFICIENT, MotionV2Math.interval(Interval(0.0,8.0),Interval(1.0,2.0),3.0).status)
        assertEquals(Status.PASS, MotionV2Math.interval(Interval(0.0,3.0),Interval(0.0,3.0),3.0).status)
        assertEquals(Status.PASS, MotionV2Math.interval(Interval(10.0,10.0),Interval(16.0,16.0),6.0).status)
    }
    @Test fun invalid_event_intervals_and_nonfinite_limits_are_rejected() {
        for (bad in listOf(Interval(2.0,1.0),Interval(Double.NaN,1.0),Interval(0.0,Double.POSITIVE_INFINITY)))
            assertFailsWith<IllegalArgumentException> { MotionV2Math.interval(bad,Interval(0.0,0.0),3.0) }
        for (limit in listOf(-1.0,Double.NaN,Double.POSITIVE_INFINITY))
            assertFailsWith<IllegalArgumentException> { MotionV2Math.interval(Interval(0.0,0.0),Interval(0.0,0.0),limit) }
    }
    @Test fun numeric_missing_points_never_pass_and_cannot_mask_a_proven_worst_failure() {
        val valid=MotionV2Math.numeric(listOf(0.0,2.0),2,2.0,4.0)
        assertEquals(Status.PASS,valid.status);assertEquals(kotlin.math.sqrt(2.0),valid.rmse)
        assertEquals(Status.INSUFFICIENT,MotionV2Math.numeric(listOf(0.0,2.0),3,2.0,4.0).status)
        assertEquals(Status.FAIL,MotionV2Math.numeric(listOf(4.01),3,2.0,4.0).status)
        assertEquals(Status.INSUFFICIENT,MotionV2Math.numeric(emptyList(),3,2.0,4.0).status)
        // One available error can exceed the subset RMSE but not prove the full requested RMSE.
        assertEquals(Status.INSUFFICIENT,MotionV2Math.numeric(listOf(3.0),100,2.0,4.0).status)
        assertEquals(Status.FAIL,MotionV2Math.numeric(listOf(3.0,3.0),2,2.0,4.0).status)
        val lowerBound=MotionV2Math.numeric(listOf(4.0,4.0),3,2.0,4.0)
        assertEquals(Status.FAIL,lowerBound.status);assertNull(lowerBound.rmse)
        assertTrue(lowerBound.fullRmseLowerBound > 2.0)
        assertFailsWith<IllegalArgumentException>{MotionV2Math.numeric(listOf(Double.NaN),1,2.0,4.0)}
    }
    @Test fun shorter_or_faster_is_accepted_by_the_one_way_ratio_only() {
        assertEquals(Status.PASS to .5,MotionV2Math.oneWayRatio(5.0,10.0))
        assertEquals(Status.PASS to 1.5,MotionV2Math.oneWayRatio(15.0,10.0))
        assertEquals(Status.FAIL,MotionV2Math.oneWayRatio(15.01,10.0).first)
        assertEquals(Status.INSUFFICIENT to null,MotionV2Math.oneWayRatio(10.0,0.0))
        assertFailsWith<IllegalArgumentException>{MotionV2Math.oneWayRatio(-1.0,10.0)}
    }
    private fun vectors()=(0..3).map { i-> Vector(0,i.toDouble(),i+1.0,if(i%2==0).05 else -.05,0.0) }
    @Test fun reversal_needs_four_positive_I_vectors_and_is_not_broken_by_phase_labels() {
        assertNull(MotionV2Math.firstReversal(vectors().take(3)))
        assertEquals(3,MotionV2Math.firstReversal(vectors()))
        val straight=(0..3).map { i->Vector(0,i.toDouble(),i+1.0,.05,0.0) }
        assertNull(MotionV2Math.firstReversal(straight))
    }
    @Test fun short_zero_I_and_new_input_each_break_the_reversal_chain() {
        for (replacement in listOf(Vector(0,1.0,2.0,.049,0.0),Vector(0,1.0,1.0,-.05,0.0),Vector(1,1.0,2.0,-.05,0.0))) {
            val broken=vectors().toMutableList();broken[1]=replacement
            if (replacement.entryQ == replacement.exitQ) {
                broken[2]=broken[2].copy(entryQ=1.0,exitQ=2.0)
                broken[3]=broken[3].copy(entryQ=2.0,exitQ=3.0)
            }
            assertNull(MotionV2Math.firstReversal(broken))
        }
        assertFailsWith<IllegalArgumentException>{MotionV2Math.firstReversal(listOf(Vector(0,1.0,0.0,1.0,0.0)))}
    }
    @Test fun reversal_angle_has_an_inclusive_120_degree_boundary_without_epsilon() {
        fun turns(degrees: Double)=(0..3).map { i->
            val angle=Math.toRadians(i*degrees)
            Vector(0,i.toDouble(),i+1.0,kotlin.math.cos(angle),kotlin.math.sin(angle))
        }
        assertNull(MotionV2Math.firstReversal(turns(119.0)))
        assertEquals(3,MotionV2Math.firstReversal(turns(121.0)))
        // Exact axis/diagonal construction gives the same angle in each direction.
        val edge=listOf(1.0 to 0.0,-.5 to kotlin.math.sqrt(.75),1.0 to 0.0,-.5 to kotlin.math.sqrt(.75))
        assertEquals(3,MotionV2Math.firstReversal(edge.mapIndexed { i,p->Vector(0,i.toDouble(),i+1.0,p.first,p.second) }))
    }
    @Test fun missing_reordered_or_later_corrupt_raw_calls_cannot_prove_consecutive_reversal() {
        val missing=vectors().toMutableList();missing[2]=missing[2].copy(entryQ=2.5)
        assertFailsWith<IllegalArgumentException>{MotionV2Math.firstReversal(missing)}
        assertFailsWith<IllegalArgumentException>{MotionV2Math.firstReversal(vectors().reversed())}
        assertFailsWith<IllegalArgumentException>{MotionV2Math.firstReversal(vectors()+Vector(0,5.0,6.0,1.0,0.0))}
    }
}
