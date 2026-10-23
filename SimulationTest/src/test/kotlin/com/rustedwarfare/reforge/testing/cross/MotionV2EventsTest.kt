package com.rustedwarfare.reforge.testing.cross

import kotlin.test.*
import com.rustedwarfare.reforge.testing.cross.MotionV2Events.Call
import com.rustedwarfare.reforge.testing.cross.MotionV2Events.Event
import com.rustedwarfare.reforge.testing.cross.MotionV2Math.Interval

class MotionV2EventsTest {
    private fun calls(speeds: List<Double>, deltas: List<Double> = List(speeds.size){1.0}): List<Call> {
        var q=0.0;var x=100.0
        return speeds.mapIndexed { i,speed->
            val start=q;val old=x;q+=deltas[i];x+=speed*deltas[i]
            Call(start,q,0,0,old,200.0,x,200.0,true,true)
        }
    }
    private fun stop():List<Call> {
        val speeds=listOf(1.1,0.0,.913,.726,.539,.352,.165,0.0)+List(25){0.0}
        val delta=List(speeds.size){if(it==1)0.0 else 1.0}
        return calls(speeds,delta).mapIndexed { i,c->c.copy(input=if(i==0)0 else 1,
            targetBefore=i<=1,targetAfter=i==0,phase=if(c.exitQ<12)0 else 1) }
    }
    @Test fun first_motion_is_first_positive_I_displacement_strictly_above_point001() {
        // Coordinates near zero preserve the actual binary64 .001 boundary exactly.
        val rows=listOf(Call(0.0,0.0,0,0,0.0,0.0,0.0,0.0,true,true),
            Call(0.0,1.0,0,0,0.0,0.0,.001,0.0,true,true),
            Call(1.0,2.0,0,0,.001,0.0,.003,0.0,true,true))
        assertEquals(Event(Interval(1.0,2.0),2),MotionV2Events.firstMove(rows,0))
        assertNull(MotionV2Events.firstMove(rows,1))
    }
    @Test fun stop_tail_uses_fixed_center_and_actual_s_not_confirmation_or_tail_end() {
        val tail=assertNotNull(MotionV2Events.stableTail(stop(),1,Event(Interval(1.0,1.0),1)))
        // Earliest stable position is the end of the last nonzero displacement,
        // one call before speed first becomes zero; speed is not this predicate.
        assertEquals(Interval(5.0,6.0),tail.event.interval)
        assertEquals(12.0,stop()[tail.event.confirmedIndex].exitQ)
        assertEquals(24.0,stop()[assertNotNull(tail.endIndex)].exitQ)
        assertTrue(tail.eighteenQWithinBudget);assertEquals(true,tail.eighteenQStable)
        assertTrue(tail.fullRawFutureStable);assertTrue(tail.clearLifecycleIntact) // Cross-phase same input is allowed.
    }
    @Test fun fewer_than18_q_or_tail_finishing_only_after600_cannot_pass() {
        val short=assertNotNull(MotionV2Events.stableTail(stop().filter { it.exitQ<24 },1,Event(Interval(1.0,1.0),1)))
        assertEquals(Interval(5.0,6.0),short.event.interval)
        assertFalse(short.eighteenQWithinBudget);assertNull(short.eighteenQStable);assertNull(short.endIndex)
        val late=assertNotNull(MotionV2Events.stableTail(stop(),1,Event(Interval(1.0,1.0),1),maximumQ=23.99))
        assertFalse(late.eighteenQWithinBudget);assertEquals(true,late.eighteenQStable)
    }
    @Test fun future_drift_is_preserved_as_integrity_failure_without_moving_the_earliest_event() {
        val rows=stop().map { if(it.entryQ>=26)it.copy(beforeX=it.beforeX+.01,x=it.x+.01)else it }
        // Keep a continuous actual transition to the drifting position.
        val fixed=rows.map { if(it.entryQ==25.0)it.copy(x=it.x+.01)else it }
        val tail=assertNotNull(MotionV2Events.stableTail(fixed,1,Event(Interval(1.0,1.0),1)))
        assertEquals(Interval(5.0,6.0),tail.event.interval);assertFalse(tail.fullRawFutureStable)
    }
    @Test fun OM06_keeps_a_completed_plateau_through_the_transition_band_and_skips_zero_I_for_confirmation() {
        val speeds=List(7){1.1}+listOf(1.03,1.024,0.0,1.024)
        val rows=calls(speeds,List(speeds.size){if(it==9)0.0 else 1.0})
        val result=MotionV2Events.deceleration(rows,0)
        assertEquals(Event(Interval(8.0,9.0),8,10),result.event);assertNull(result.unavailable)
    }
    @Test fun first_low_without_confirmation_is_not_replaced_by_a_later_more_convenient_low() {
        val rows=calls(List(7){1.1}+listOf(1.024,1.03,1.0,1.0))
        val result=MotionV2Events.deceleration(rows,0)
        assertNull(result.event);assertEquals(Interval(7.0,8.0),result.firstCandidate?.interval)
        assertEquals("first-low-not-confirmed",result.unavailable)
        assertNull(MotionV2Events.deceleration(calls(List(5){1.1}+listOf(1.0,1.0)),0).event)
    }
    @Test fun zero_I_positive_motion_does_not_count_and_malformed_raw_order_is_rejected_before_findings() {
        val rows=calls(listOf(1.1,1.1,1.1))
        assertFailsWith<IllegalArgumentException>{MotionV2Events.firstMove(rows+rows.last().copy(entryQ=4.0,exitQ=5.0),0)}
        assertFailsWith<IllegalArgumentException>{MotionV2Events.deceleration(rows.reversed(),0)}
        val movedAtZero=listOf(Call(0.0,0.0,0,0,100.0,200.0,110.0,200.0,true,true))
        assertNull(MotionV2Events.firstMove(movedAtZero,0))
    }
    @Test fun target_resurrection_before_the_stable_anchor_cannot_be_hidden_by_a_later_valid_tail() {
        val rows=stop().map { if(it.exitQ==4.0)it.copy(targetAfter=true)else it }
        val tail=assertNotNull(MotionV2Events.stableTail(rows,1,Event(Interval(1.0,1.0),1)))
        assertTrue(tail.fullRawFutureStable);assertFalse(tail.clearLifecycleIntact)
    }
    @Test fun entry_only_target_resurrection_after_clear_is_also_preserved() {
        val rows=stop().map { if(it.exitQ==4.0)it.copy(targetBefore=true)else it }
        val tail=assertNotNull(MotionV2Events.stableTail(rows,1,Event(Interval(1.0,1.0),1)))
        assertTrue(tail.fullRawFutureStable);assertFalse(tail.clearLifecycleIntact)
    }
    @Test fun OM06_threshold_edges_use_measured_displacement_not_internal_speed_fields() {
        // The first call ends exactly at zero to preserve the next 1.025 binary64 boundary.
        val rows=listOf(Call(0.0,6.0,0,0,-6.27,0.0,0.0,0.0,true,true),
            Call(6.0,7.0,0,0,0.0,0.0,1.025,0.0,true,true),
            Call(7.0,8.0,0,0,1.025,0.0,2.049,0.0,true,true),
            Call(8.0,9.0,0,0,2.049,0.0,3.073,0.0,true,true))
        assertEquals(1.045,6.27/6.0)
        val result=MotionV2Events.deceleration(rows,0)
        assertEquals(Event(Interval(7.0,8.0),2,3),result.event)
    }
    @Test fun drift_after_six_q_confirmation_cannot_reselect_a_later_anchor_to_make_eighteen_q_pass() {
        val rows=stop().map { c->when {
            c.entryQ==12.0 -> c.copy(x=c.x+.01)
            c.entryQ>12.0 -> c.copy(beforeX=c.beforeX+.01,x=c.x+.01)
            else -> c
        } }
        val tail=assertNotNull(MotionV2Events.stableTail(rows,1,Event(Interval(1.0,1.0),1)))
        assertEquals(Interval(5.0,6.0),tail.event.interval)
        assertEquals(12.0,rows[tail.event.confirmedIndex].exitQ)
        assertEquals(false,tail.eighteenQStable);assertFalse(tail.fullRawFutureStable)
    }
}
