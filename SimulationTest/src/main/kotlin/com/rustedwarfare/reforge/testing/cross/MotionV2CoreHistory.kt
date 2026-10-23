package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.core.sim.MotionStepSample
import com.rustedwarfare.reforge.core.sim.MotionTerrainDisposition
import com.rustedwarfare.reforge.core.sim.Position

/** Observable history only. Private cursor coverage and same-build mechanisms are separate. */
internal object MotionV2CoreHistory {
    enum class Clear { NONE, STOP, UNREACHABLE, UNKNOWN }
    data class Frame(val sample:MotionStepSample,val pathBefore:List<Position>?,val clear:Clear=Clear.NONE,
                     val provenRouteGoal:Position?=null)
    data class Expected(val correctionBefore:Double?,val correctionAfter:Double?)
    data class Result(val expected:List<Expected>,val issues:List<MotionV2CoreAssessment.Issue>,
                      val diagnosticCoverage:List<MotionV2CoreAssessment.Issue>)
    fun assess(frames:List<Frame>,freshInitialization:Boolean=false):Result {
        val expected=mutableListOf<Expected>();val issues=mutableListOf<MotionV2CoreAssessment.Issue>()
        var correction:Double?=if(freshInitialization)0.0 else null
        var direct=0
        val initial=frames.firstOrNull()?.sample
        val baseStep=if(freshInitialization)0L else initial?.stepIndex ?: 0L
        var end=if(freshInitialization)0.0 else initial?.timeBefore ?: 0.0
        var previous:MotionStepSample?=null
        fun fail(ok:Boolean,code:String,i:Int) {
            if(!ok)issues+=MotionV2CoreAssessment.Issue("behavior",MotionV2Findings.Status.FAIL,code,i)
        }
        fun same(a:Position?,b:Position?)=if(a==null||b==null)a==b else a.x.toBits()==b.x.toBits()&&a.y.toBits()==b.y.toBits()
        fun finite(p:Position?)=p==null||p.x.isFinite()&&p.y.isFinite()
        for((i,frame)in frames.withIndex()) {
            val m=frame.sample
            val valid=m.timeBefore.isFinite()&&m.timeBefore>=0.0&&m.timeBefore==end&&m.delta.isFinite()&&m.delta>0.0&&
                (m.timeBefore+m.delta).isFinite()&&baseStep>=0&&baseStep<=Long.MAX_VALUE-i&&m.stepIndex==baseStep+i&&
                m.worldId==initial?.worldId&&m.unitId==initial.unitId&&m.ownerPlayerId==initial.ownerPlayerId&&m.typeId==initial.typeId&&
                finite(m.positionBefore)&&finite(m.finalPosition)&&finite(m.commandTargetBefore)&&finite(m.steeringAim)&&finite(frame.provenRouteGoal)&&
                (frame.provenRouteGoal==null||same(frame.provenRouteGoal,m.commandTargetBefore))&&
                (frame.pathBefore==null||frame.pathBefore.all(::finite))&&
                listOf(m.correctionTimeBefore,m.correctionTimeAfter,m.localProgressBefore,m.localProgressAfter,m.targetFraction).all { it.isFinite() }&&
                (previous==null||same(m.positionBefore,previous.finalPosition))
            if(!valid) {
                issues+=MotionV2CoreAssessment.Issue("source",MotionV2Findings.Status.EXECUTION_FAILED,"LOCAL_HISTORY_PAIRING",i)
                break
            }
            val before=correction
            fail(m.correctionTimeBefore>=0.0&&m.correctionTimeAfter>=0.0,"CORRECTION_TIME_DOMAIN",i)
            if(before!=null)fail(m.correctionTimeBefore.toBits()==before.toBits(),"CORRECTION_TIME_BEFORE",i)
            when(m.terrainDisposition) {
                MotionTerrainDisposition.CORRECTED -> { correction=correction?.plus(m.delta);direct=0 }
                MotionTerrainDisposition.DIRECT -> { direct=minOf(3,direct+1);if(direct==3)correction=0.0 }
                MotionTerrainDisposition.CANDIDATE_OUTSIDE_MAP,MotionTerrainDisposition.RESOLVED_OUTSIDE_MAP -> Unit
            }
            val after=correction
            if(after!=null)fail(m.correctionTimeAfter.toBits()==after.toBits(),"CORRECTION_TIME_AFTER",i)
            if(before==null||after==null)issues+=MotionV2CoreAssessment.Issue("coverage",MotionV2Findings.Status.INSUFFICIENT,"CORRECTION_HISTORY_UNKNOWN",i)
            expected+=Expected(before,after)
            val cleared=m.normalCompletion||frame.clear in setOf(Clear.STOP,Clear.UNREACHABLE)||
                m.terrainDisposition in setOf(MotionTerrainDisposition.CANDIDATE_OUTSIDE_MAP,MotionTerrainDisposition.RESOLVED_OUTSIDE_MAP)
            val incremented=m.localProgressBefore+if(m.targetFraction>0.0)m.delta else 0.0
            fail(m.localProgressBefore>=0.0&&m.localProgressAfter>=0.0&&
                (m.localProgressAfter.toBits()==0.0.toBits()||m.localProgressAfter.toBits()==incremented.toBits()),"LOCAL_PROGRESS_TRANSITION",i)
            if(cleared)fail(m.localProgressAfter.toBits()==0.0.toBits(),"LOCAL_PROGRESS_CLEAR",i)
            if(frame.clear==Clear.UNKNOWN)issues+=MotionV2CoreAssessment.Issue("coverage",MotionV2Findings.Status.INSUFFICIENT,"LOCAL_CLEAR_ASSOCIATION_UNKNOWN",i)
            if(m.steeringInvoked&&m.finalLocalPoint==true) {
                val path=frame.pathBefore
                if(path!=null)fail(path.size==1&&same(m.steeringAim,path.singleOrNull()),"FINAL_LOCAL_ANCHOR",i)
                else if(frame.provenRouteGoal!=null)fail(same(m.steeringAim,frame.provenRouteGoal),"FINAL_LOCAL_GOAL",i)
                else issues+=MotionV2CoreAssessment.Issue("coverage",MotionV2Findings.Status.INSUFFICIENT,"LOCAL_AIM_PUBLIC_PATH_MISSING",i)
            }
            end=m.timeBefore+m.delta;previous=m
        }
        if(frames.isEmpty())issues+=MotionV2CoreAssessment.Issue("coverage",MotionV2Findings.Status.INSUFFICIENT,"LOCAL_HISTORY_MISSING",null)
        return Result(expected,issues,listOf(MotionV2CoreAssessment.Issue("diagnostic-coverage",MotionV2Findings.Status.INSUFFICIENT,"PRIVATE_CURSOR_SEQUENCE_UNOBSERVED",null)))
    }
}
