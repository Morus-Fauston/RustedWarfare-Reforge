package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

/** Pure history controls. These do not claim real capture faults or raw pairing qualification. */
class MotionV2CoreCompletionTest {
    private fun sample(x:Double,target:Position,normal:Boolean,step:Long=0,time:Double=0.0,delta:Double=1.0)=
        MotionStepSample("w","u1","p1","tank",step,time,delta,"diag.0",Position(x,100.0),90.0,0.0,0.0,target,normal,
            !normal,if(normal)null else target,if(normal)null else true,if(normal)null else 0.0,0.0,90.0,0.0,
            Position(x,100.0),Position(x,100.0),MotionTerrainDisposition.DIRECT,Position(x,100.0),90.0,0.0,0.0,0.0,0.0,0.0,0.0)
    private fun move(target:Position,sequence:Long=0,q:Double=0.0)=
        MotionV2CoreCompletion.Command(sequence,q,MotionV2CoreCompletion.CommandKind.MOVE,target)
    private fun one(distance:Double,normal:Boolean):MotionV2CoreCompletion.Frame {
        val target=Position(distance,100.0)
        return MotionV2CoreCompletion.Frame(sample(0.0,target,normal),if(normal)null else target,listOf(move(target)),
            if(normal)MotionV2CoreCompletion.Clear.NORMAL else MotionV2CoreCompletion.Clear.NONE)
    }
    @Test fun strict_seven_uses_pre_move_position_and_checks_marker_both_ways() {
        for((distance,normal) in listOf(Math.nextDown(7.0) to true,7.0 to false)) {
            val good=MotionV2CoreCompletion.assess(listOf(one(distance,normal)),true)
            assertEquals(normal,good.expected.single().normal)
            assertEquals(1.0,good.expected.single().nearTime);assertEquals(7.0,good.expected.single().radius)
            assertTrue(good.issues.isEmpty())
            val bad=MotionV2CoreCompletion.assess(listOf(one(distance,!normal)),true)
            assertTrue(bad.issues.any { it.code=="NORMAL_COMPLETION_ELIGIBILITY" && it.status==MotionV2Findings.Status.FAIL })
            assertTrue(bad.issues.none { it.status==MotionV2Findings.Status.EXECUTION_FAILED })
        }
    }
    @Test fun accumulated_time_is_added_before_strict_240_340_and_distance_boundaries() {
        for((distance,boundary,radius) in listOf(Triple(15.0,240.0,16.0),Triple(35.0,340.0,36.0))) {
            val goal=Position(distance,100.0)
            val frames=listOf(
                MotionV2CoreCompletion.Frame(sample(0.0,goal,false,0,0.0,boundary-1),goal,listOf(move(goal))),
                MotionV2CoreCompletion.Frame(sample(0.0,goal,false,1,boundary-1,1.0),goal),
                MotionV2CoreCompletion.Frame(sample(0.0,goal,true,2,boundary,.25),null,clear=MotionV2CoreCompletion.Clear.NORMAL))
            val good=MotionV2CoreCompletion.assess(frames,true)
            assertEquals(listOf(boundary-1,boundary,boundary+.25),good.expected.map { it.nearTime })
            assertEquals(if(boundary==240.0)7.0 else 16.0,good.expected[1].radius)
            assertEquals(radius,good.expected[2].radius);assertTrue(good.issues.isEmpty())
            val late=MotionV2CoreCompletion.assess(frames.dropLast(1)+frames.last().copy(sample=frames.last().sample.copy(normalCompletion=false)),true)
            assertTrue(late.issues.any { it.code=="NORMAL_COMPLETION_ELIGIBILITY" && it.status==MotionV2Findings.Status.FAIL })
        }
        data class Vector(val distance:Double,val elapsed:Double,val radius:Double,val normal:Boolean)
        for((distance,elapsed,radius,normal) in listOf(
            Vector(16.0,241.0,16.0,false),Vector(Math.nextDown(16.0),241.0,16.0,true),
            Vector(36.0,341.0,36.0,false),Vector(Math.nextDown(36.0),341.0,36.0,true))) {
            val goal=Position(distance,100.0)
            val frame=MotionV2CoreCompletion.Frame(sample(0.0,goal,normal,delta=elapsed),if(normal)null else goal,listOf(move(goal)),
                if(normal)MotionV2CoreCompletion.Clear.NORMAL else MotionV2CoreCompletion.Clear.NONE)
            val result=MotionV2CoreCompletion.assess(listOf(frame),true)
            assertEquals(radius,result.expected.single().radius);assertTrue(result.issues.isEmpty())
        }
    }
    @Test fun unknown_clear_never_grants_zero_history_and_a_proven_new_move_restores_it() {
        val goal=Position(35.0,100.0)
        val idle=sample(0.0,goal,false,1,241.0).copy(commandTargetBefore=null,originMoveId=null,
            steeringInvoked=false,steeringAim=null,finalLocalPoint=null,steeringDifference=null)
        val frames=listOf(
            MotionV2CoreCompletion.Frame(sample(0.0,goal,false,delta=241.0),null,listOf(move(goal)),MotionV2CoreCompletion.Clear.UNKNOWN),
            MotionV2CoreCompletion.Frame(idle,goal),
            MotionV2CoreCompletion.Frame(sample(0.0,goal,true,2,242.0),null,clear=MotionV2CoreCompletion.Clear.UNKNOWN),
            MotionV2CoreCompletion.Frame(sample(0.0,goal,false,3,243.0).copy(originMoveId="diag.2"),goal,listOf(move(goal,2,243.0))))
        val result=MotionV2CoreCompletion.assess(frames,true)
        assertEquals(241.0,result.expected[0].nearTime)
        assertNull(result.expected[2].nearTime);assertNull(result.expected[2].normal)
        assertEquals(1.0,result.expected[3].nearTime);assertEquals(false,result.expected[3].normal)
        assertTrue(result.issues.any { it.code=="COMPLETION_COMMAND_RESURRECTED" && it.status==MotionV2Findings.Status.FAIL })
        assertTrue(result.issues.any { it.code=="NORMAL_HISTORY_UNKNOWN" && it.status==MotionV2Findings.Status.INSUFFICIENT })
        assertTrue(result.issues.none { it.code=="NORMAL_COMPLETION_ELIGIBILITY" || it.status==MotionV2Findings.Status.EXECUTION_FAILED })
    }
    @Test fun same_goal_move_and_stop_reset_in_actual_order_with_rejected_sequence_gaps() {
        val goal=Position(35.0,100.0)
        val first=MotionV2CoreCompletion.Frame(sample(0.0,goal,false,delta=241.0),goal,listOf(move(goal)))
        val stop=MotionV2CoreCompletion.Command(2,241.0,MotionV2CoreCompletion.CommandKind.STOP,null)
        for(commands in listOf(listOf(move(goal,2,241.0)),listOf(stop,move(goal,4,241.0)))) {
            val next=MotionV2CoreCompletion.Frame(sample(0.0,goal,false,1,241.0).copy(originMoveId="diag.${commands.last().sequence}"),goal,commands)
            val result=MotionV2CoreCompletion.assess(listOf(first,next),true)
            assertEquals(listOf(241.0,1.0),result.expected.map { it.nearTime });assertTrue(result.issues.isEmpty())
        }
        val idle=sample(0.0,goal,false,1,241.0).copy(commandTargetBefore=null,originMoveId=null,steeringInvoked=false,steeringAim=null,finalLocalPoint=null,steeringDifference=null)
        val stopped=MotionV2CoreCompletion.assess(listOf(first,MotionV2CoreCompletion.Frame(idle,null,listOf(stop))),true)
        assertEquals(0.0,stopped.expected[1].nearTime);assertEquals(false,stopped.expected[1].normal);assertTrue(stopped.issues.isEmpty())
    }
    @Test fun strict_41_gate_pauses_accumulation_without_erasing_it() {
        val goal=Position(41.0,100.0)
        data class V(val x:Double,val after:Double,val time:Double,val delta:Double,val near:Double)
        val vectors=listOf(V(0.0,1.0,0.0,40.0,0.0),V(1.0,-1.0,40.0,240.0,240.0),V(-1.0,1.0,280.0,1.0,240.0),V(1.0,1.0,281.0,.25,240.25))
        val frames=vectors.mapIndexed { i,v ->
            val after=Position(v.after,100.0)
            MotionV2CoreCompletion.Frame(sample(v.x,goal,false,i.toLong(),v.time,v.delta).copy(finalPosition=after,candidate=after,terrainResolved=after),goal,
                if(i==0)listOf(move(goal)) else emptyList())
        }
        val result=MotionV2CoreCompletion.assess(frames,true)
        assertEquals(vectors.map { it.near },result.expected.map { it.nearTime });assertTrue(result.issues.isEmpty())
    }
    @Test fun missing_initial_history_stays_unknown_until_a_proven_new_move() {
        val goal=Position(35.0,100.0)
        val frames=listOf(MotionV2CoreCompletion.Frame(sample(0.0,goal,false,delta=241.0),goal),
            MotionV2CoreCompletion.Frame(sample(0.0,goal,false,1,241.0).copy(originMoveId="diag.2"),goal,listOf(move(goal,2,241.0))))
        val result=MotionV2CoreCompletion.assess(frames)
        assertNull(result.expected[0].nearTime);assertNull(result.expected[0].normal)
        assertEquals(1.0,result.expected[1].nearTime);assertEquals(false,result.expected[1].normal)
        assertTrue(result.issues.any { it.code=="NORMAL_HISTORY_UNKNOWN" && it.status==MotionV2Findings.Status.INSUFFICIENT })
        assertTrue(result.issues.none { it.status in setOf(MotionV2Findings.Status.FAIL,MotionV2Findings.Status.EXECUTION_FAILED) })
    }
    @Test fun corrupt_late_frame_stops_only_its_prefix_and_preserves_a_known_marker_failure() {
        val first=one(7.0,true);val goal=Position(35.0,100.0)
        val clean=MotionV2CoreCompletion.Frame(sample(0.0,goal,false,1,1.0).copy(originMoveId="diag.2"),goal,listOf(move(goal,2,1.0)))
        val badFrames=listOf(clean.copy(sample=clean.sample.copy(delta=Double.NaN)),clean.copy(sample=clean.sample.copy(timeBefore=2.0)),
            clean.copy(sample=clean.sample.copy(worldId="other")),clean.copy(commands=listOf(move(goal,0,1.0))),
            clean.copy(commands=listOf(move(goal,2,0.0))),clean.copy(sample=clean.sample.copy(positionBefore=Position(Double.NaN,100.0))))
        for(bad in badFrames) {
            val result=MotionV2CoreCompletion.assess(listOf(first,bad),true)
            assertEquals(1,result.expected.size)
            assertTrue(result.issues.any { it.code=="NORMAL_COMPLETION_ELIGIBILITY" && it.status==MotionV2Findings.Status.FAIL })
            assertTrue(result.issues.any { it.code=="COMPLETION_HISTORY_PAIRING" && it.status==MotionV2Findings.Status.EXECUTION_FAILED })
        }
    }
    @Test fun middle_prefix_recovers_at_a_new_move() {
        val goal=Position(35.0,100.0)
        val frames=listOf(MotionV2CoreCompletion.Frame(sample(0.0,goal,false,250,250.0),goal),
            MotionV2CoreCompletion.Frame(sample(0.0,goal,false,251,251.0).copy(originMoveId="diag.2"),goal,listOf(move(goal,2,251.0))))
        val middle=MotionV2CoreCompletion.assess(frames)
        assertEquals(2,middle.expected.size);assertNull(middle.expected[0].nearTime)
        assertEquals(1.0,middle.expected[1].nearTime)
        assertTrue(middle.issues.none { it.status in setOf(MotionV2Findings.Status.FAIL,MotionV2Findings.Status.EXECUTION_FAILED) })
    }
    @Test fun known_inactive_origin_must_be_null() {
        val goal=Position(35.0,100.0)
        val idle=sample(0.0,goal,false).copy(commandTargetBefore=null,originMoveId=null,steeringInvoked=false,steeringAim=null,finalLocalPoint=null,steeringDifference=null)
        val good=MotionV2CoreCompletion.assess(listOf(MotionV2CoreCompletion.Frame(idle,null)),true)
        assertTrue(good.issues.isEmpty())
        for(origin in listOf("diag.0","diag.99")) {
            val bad=MotionV2CoreCompletion.assess(listOf(MotionV2CoreCompletion.Frame(idle.copy(originMoveId=origin),null)),true)
            assertTrue(bad.issues.any { it.code=="COMPLETION_INACTIVE_ORIGIN" && it.status==MotionV2Findings.Status.FAIL })
            assertTrue(bad.issues.none { it.status==MotionV2Findings.Status.EXECUTION_FAILED })
        }
    }
}
