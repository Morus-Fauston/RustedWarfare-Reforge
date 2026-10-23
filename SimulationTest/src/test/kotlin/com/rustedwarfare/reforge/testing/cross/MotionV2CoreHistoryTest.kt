package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.core.sim.MotionStepSample
import com.rustedwarfare.reforge.core.sim.MotionTerrainDisposition
import com.rustedwarfare.reforge.core.sim.Position
import kotlin.test.*

/** Pure paired-record controls, never a claim that a production run triggered a mutation. */
class MotionV2CoreHistoryTest {
    private fun sample(step:Long,q:Double,delta:Double,before:Double,after:Double,
                       disposition:MotionTerrainDisposition=MotionTerrainDisposition.DIRECT)=MotionStepSample(
        "w","u1","p1","tank",step,q,delta,null,Position(100.0,100.0),90.0,0.0,0.0,null,false,
        false,null,null,null,0.0,90.0,0.0,Position(100.0,100.0),Position(100.0,100.0),disposition,
        Position(100.0,100.0),90.0,0.0,0.0,0.0,0.0,before,after)
    private fun frame(m:MotionStepSample)=MotionV2CoreHistory.Frame(m,emptyList())

    @Test fun corrected_accumulates_and_three_direct_steps_clear_while_boundary_preserves_streak() {
        val rows=listOf(
            sample(0,0.0,1.0,0.0,1.0,MotionTerrainDisposition.CORRECTED),
            sample(1,1.0,.5,1.0,1.0),
            sample(2,1.5,.5,1.0,1.0,MotionTerrainDisposition.CANDIDATE_OUTSIDE_MAP),
            sample(3,2.0,1.0,1.0,1.0),
            sample(4,3.0,1.0,1.0,0.0),
            sample(5,4.0,2.0,0.0,2.0,MotionTerrainDisposition.CORRECTED))
        val good=MotionV2CoreHistory.assess(rows.map(::frame),freshInitialization=true)
        assertEquals(6,good.expected.size);assertTrue(good.issues.isEmpty())
        assertEquals(listOf(1.0,1.0,1.0,1.0,0.0,2.0),good.expected.map { it.correctionAfter })
        val bad=rows.toMutableList();bad[1]=bad[1].copy(correctionTimeAfter=0.0)
        val failed=MotionV2CoreHistory.assess(bad.map(::frame),freshInitialization=true)
        assertTrue(failed.issues.any { it.code=="CORRECTION_TIME_AFTER" && it.status==MotionV2Findings.Status.FAIL })
        assertTrue(failed.issues.none { it.status==MotionV2Findings.Status.EXECUTION_FAILED })
    }

    @Test fun local_progress_counts_positive_propulsion_and_resets_only_to_zero() {
        val start=sample(0,0.0,1.0,0.0,0.0).copy(commandTargetBefore=Position(500.0,100.0),
            steeringInvoked=true,steeringAim=Position(120.0,100.0),finalLocalPoint=false,steeringDifference=0.0,
            targetFraction=1.0,localProgressBefore=5.0,localProgressAfter=6.0)
        fun check(m:MotionStepSample,clear:MotionV2CoreHistory.Clear=MotionV2CoreHistory.Clear.NONE)=
            MotionV2CoreHistory.assess(listOf(MotionV2CoreHistory.Frame(m,listOf(Position(500.0,100.0)),clear)),true)
        for(m in listOf(start,start.copy(localProgressAfter=0.0),
            start.copy(targetFraction=0.0,speedBefore=1.0,localProgressAfter=5.0)))assertTrue(check(m).issues.isEmpty())
        val mutations=listOf(start.copy(localProgressAfter=7.0),
            start.copy(targetFraction=0.0,speedBefore=1.0,localProgressAfter=6.0),
            start.copy(localProgressBefore=-1.0,localProgressAfter=0.0))
        for(m in mutations)assertTrue(check(m).issues.any { it.code=="LOCAL_PROGRESS_TRANSITION"&&it.status==MotionV2Findings.Status.FAIL })
        val cleared=sample(0,0.0,1.0,0.0,0.0).copy(normalCompletion=true,localProgressBefore=7.0)
        assertTrue(check(cleared).issues.isEmpty())
        assertTrue(check(cleared.copy(localProgressAfter=1.0)).issues.any { it.code=="LOCAL_PROGRESS_CLEAR" })
        for(clear in listOf(MotionV2CoreHistory.Clear.STOP,MotionV2CoreHistory.Clear.UNREACHABLE)) {
            assertTrue(check(sample(0,0.0,1.0,0.0,0.0),clear).issues.isEmpty())
            assertTrue(check(sample(0,0.0,1.0,0.0,0.0).copy(localProgressAfter=1.0),clear).issues.any { it.code=="LOCAL_PROGRESS_CLEAR" })
        }
        assertTrue(check(sample(0,0.0,1.0,0.0,0.0),MotionV2CoreHistory.Clear.UNKNOWN).issues.any {
            it.code=="LOCAL_CLEAR_ASSOCIATION_UNKNOWN"&&it.status==MotionV2Findings.Status.INSUFFICIENT })
    }

    @Test fun final_local_aim_requires_the_unique_public_anchor_without_assuming_a_current_position_line() {
        val anchor=Position(500.0,150.0)
        val m=sample(0,0.0,1.0,0.0,0.0).copy(commandTargetBefore=anchor,steeringInvoked=true,
            steeringAim=anchor,finalLocalPoint=true,steeringDifference=0.0,targetFraction=1.0,localProgressAfter=1.0)
        fun check(value:MotionStepSample,path:List<Position>?)=
            MotionV2CoreHistory.assess(listOf(MotionV2CoreHistory.Frame(value,path)),true)
        val good=check(m,listOf(anchor));assertTrue(good.issues.isEmpty())
        assertTrue(good.diagnosticCoverage.any { it.code=="PRIVATE_CURSOR_SEQUENCE_UNOBSERVED" })
        assertTrue(good.issues.none { it.code=="PRIVATE_CURSOR_SEQUENCE_UNOBSERVED" })
        assertTrue(check(m.copy(steeringAim=Position(499.0,150.0)),listOf(anchor)).issues.any {
            it.code=="FINAL_LOCAL_ANCHOR"&&it.status==MotionV2Findings.Status.FAIL })
        assertTrue(check(m,listOf(Position(200.0,100.0),anchor)).issues.any { it.code=="FINAL_LOCAL_ANCHOR" })
        assertTrue(check(m,null).issues.any { it.code=="LOCAL_AIM_PUBLIC_PATH_MISSING"&&it.status==MotionV2Findings.Status.INSUFFICIENT })
        // A private fixed-origin segment may be off the current-position line.
        assertTrue(check(m.copy(steeringAim=Position(120.0,100.0),finalLocalPoint=false),listOf(anchor)).issues.isEmpty())
    }

    @Test fun missing_initial_history_recovers_b_after_three_direct_steps_and_commands_do_not_clear_it() {
        val middle=(0..3).map { i -> frame(sample(8L+i,250.0+i,1.0,if(i<3)7.0 else 0.0,if(i<2)7.0 else 0.0)) }
        val unknown=MotionV2CoreHistory.assess(middle)
        assertTrue(unknown.issues.none { it.status==MotionV2Findings.Status.FAIL })
        assertEquals(listOf(null,null,0.0,0.0),unknown.expected.map { it.correctionAfter })
        assertTrue(unknown.issues.any { it.code=="CORRECTION_HISTORY_UNKNOWN" })
        assertTrue(unknown.issues.none { it.sample==3 })
        val commands=listOf(frame(sample(0,0.0,1.0,0.0,1.0,MotionTerrainDisposition.CORRECTED)),
            MotionV2CoreHistory.Frame(sample(1,1.0,1.0,1.0,1.0),emptyList(),MotionV2CoreHistory.Clear.STOP),
            MotionV2CoreHistory.Frame(sample(2,2.0,1.0,1.0,1.0),emptyList(),MotionV2CoreHistory.Clear.UNREACHABLE),
            frame(sample(3,3.0,1.0,1.0,0.0)))
        assertTrue(MotionV2CoreHistory.assess(commands,true).issues.isEmpty())
    }

    @Test fun a_late_broken_history_retains_prior_behavior_failure_and_stops_derivation() {
        val first=frame(sample(0,0.0,1.0,0.0,2.0,MotionTerrainDisposition.CORRECTED))
        val next=sample(1,1.0,1.0,1.0,1.0)
        val damaged=listOf(next.copy(timeBefore=2.0),next.copy(stepIndex=3),next.copy(worldId="other"),
            next.copy(unitId="other"),next.copy(delta=Double.NaN),next.copy(correctionTimeAfter=Double.NaN),
            next.copy(localProgressBefore=Double.NaN),next.copy(positionBefore=Position(101.0,100.0)))
        for(m in damaged) {
            val result=MotionV2CoreHistory.assess(listOf(first,frame(m)),true)
            assertEquals(1,result.expected.size)
            assertTrue(result.issues.any { it.code=="CORRECTION_TIME_AFTER"&&it.status==MotionV2Findings.Status.FAIL })
            assertTrue(result.issues.any { it.code=="LOCAL_HISTORY_PAIRING"&&it.status==MotionV2Findings.Status.EXECUTION_FAILED&&it.sample==1 })
        }
    }

    @Test fun empty_history_is_required_insufficient_even_when_private_cursor_is_only_diagnostic() {
        val result=MotionV2CoreHistory.assess(emptyList(),true)
        assertTrue(result.issues.any { it.code=="LOCAL_HISTORY_MISSING"&&it.status==MotionV2Findings.Status.INSUFFICIENT })
        assertTrue(result.diagnosticCoverage.any { it.code=="PRIVATE_CURSOR_SEQUENCE_UNOBSERVED" })
    }

    @Test fun a_proven_route_goal_checks_only_the_final_aim_relation_and_does_not_forge_an_entry_path() {
        val goal=Position(500.0,150.0)
        val m=sample(0,0.0,1.0,0.0,0.0).copy(commandTargetBefore=goal,steeringInvoked=true,
            steeringAim=goal,finalLocalPoint=true,steeringDifference=0.0,targetFraction=1.0,localProgressAfter=1.0)
        fun check(value:MotionStepSample,path:List<Position>?=null)=MotionV2CoreHistory.assess(
            listOf(MotionV2CoreHistory.Frame(value,path,provenRouteGoal=goal)),true)
        val good=check(m);assertTrue(good.issues.isEmpty())
        assertTrue(good.diagnosticCoverage.any { it.code=="PRIVATE_CURSOR_SEQUENCE_UNOBSERVED" })
        assertTrue(check(m.copy(steeringAim=Position(499.0,150.0))).issues.any { it.code=="FINAL_LOCAL_GOAL"&&it.status==MotionV2Findings.Status.FAIL })
        // The stronger known-entry check remains, even when the goal relation holds.
        assertTrue(check(m,listOf(Position(200.0,100.0),goal)).issues.any { it.code=="FINAL_LOCAL_ANCHOR" })
        for(proof in listOf(Position(499.0,150.0),Position(Double.NaN,150.0))) {
            val wrong=MotionV2CoreHistory.assess(listOf(MotionV2CoreHistory.Frame(m,null,provenRouteGoal=proof)),true)
            assertTrue(wrong.expected.isEmpty())
            assertTrue(wrong.issues.any { it.code=="LOCAL_HISTORY_PAIRING"&&it.status==MotionV2Findings.Status.EXECUTION_FAILED })
        }
        val zero=m.copy(commandTargetBefore=Position(0.0,150.0),steeringAim=Position(0.0,150.0))
        val wrongZero=MotionV2CoreHistory.assess(listOf(MotionV2CoreHistory.Frame(zero,null,provenRouteGoal=Position(-0.0,150.0))),true)
        assertTrue(wrongZero.expected.isEmpty());assertTrue(wrongZero.issues.any { it.code=="LOCAL_HISTORY_PAIRING" })
    }
}
