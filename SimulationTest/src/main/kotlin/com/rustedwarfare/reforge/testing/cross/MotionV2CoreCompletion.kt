package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.core.sim.MotionStepSample
import com.rustedwarfare.reforge.core.sim.MotionTerrainDisposition
import com.rustedwarfare.reforge.core.sim.Position

/** Paired history only; does not grant source, event or local-aim qualification. */
internal object MotionV2CoreCompletion {
    enum class CommandKind { MOVE, STOP, UNREACHABLE, UNKNOWN }
    enum class Clear { NONE, NORMAL, UNREACHABLE, BOUNDARY, UNKNOWN }
    data class Command(val sequence:Long,val q:Double,val kind:CommandKind,val targetAfter:Position?)
    data class Frame(val sample:MotionStepSample,val targetAfter:Position?,val commands:List<Command> = emptyList(),val clear:Clear = Clear.NONE)
    data class Expected(val nearTime:Double?,val radius:Double?,val normal:Boolean?)
    data class Result(val expected:List<Expected>,val issues:List<MotionV2CoreAssessment.Issue>)
    fun assess(frames:List<Frame>,freshInitialization:Boolean=false,expectedActionPrefix:String="diag."):Result {
        require(expectedActionPrefix.isNotBlank())
        val expected=mutableListOf<Expected>();val issues=mutableListOf<MotionV2CoreAssessment.Issue>()
        var near=0.0;var known=freshInitialization;var associationKnown=freshInitialization
        var activeGoal:Position?=null;var activeSequence:Long?=null
        val baseStep=if(freshInitialization)0L else frames.firstOrNull()?.sample?.stepIndex ?: 0L
        var lastSequence=-1L;var end=if(freshInitialization)0.0 else frames.firstOrNull()?.sample?.timeBefore ?: 0.0
        var previous:Frame?=null
        fun same(a:Position?,b:Position?)=if(a==null||b==null)a==b else a.x.toBits()==b.x.toBits()&&a.y.toBits()==b.y.toBits()
        fun finite(p:Position?)=p==null||p.x.isFinite()&&p.y.isFinite()
        fun add(status:MotionV2Findings.Status,code:String,i:Int) { issues+=MotionV2CoreAssessment.Issue(if(status==MotionV2Findings.Status.FAIL)"behavior" else "coverage",status,code,i) }
        fun unknown(i:Int) { known=false;associationKnown=false;add(MotionV2Findings.Status.INSUFFICIENT,"NORMAL_HISTORY_UNKNOWN",i) }
        for((i,frame) in frames.withIndex()) {
            val m=frame.sample
            var sequence=lastSequence
            val commandsValid=frame.commands.all { c ->
                val ok=c.sequence>sequence&&c.q.isFinite()&&c.q==m.timeBefore&&finite(c.targetAfter)&&
                    when(c.kind) { CommandKind.MOVE -> c.targetAfter!=null;CommandKind.STOP,CommandKind.UNREACHABLE -> c.targetAfter==null;CommandKind.UNKNOWN -> true }
                sequence=c.sequence;ok
            }
            val initial=frames.first().sample
            val previousGoal=if(frame.commands.isNotEmpty())frame.commands.last().targetAfter else previous?.targetAfter
            val valid=commandsValid&&m.timeBefore.isFinite()&&m.delta.isFinite()&&m.delta>0.0&&(m.timeBefore+m.delta).isFinite()&&
                m.timeBefore>=0.0&&m.timeBefore==end&&baseStep>=0&&baseStep<=Long.MAX_VALUE-i&&m.stepIndex==baseStep+i&&
                finite(m.positionBefore)&&finite(m.finalPosition)&&finite(m.commandTargetBefore)&&finite(frame.targetAfter)&&
                m.worldId==initial.worldId&&m.unitId==initial.unitId&&m.ownerPlayerId==initial.ownerPlayerId&&m.typeId==initial.typeId&&
                (previous==null||same(m.positionBefore,previous.sample.finalPosition))&&
                (previous==null&&!freshInitialization&&frame.commands.isEmpty()||same(m.commandTargetBefore,previousGoal))
            if(!valid) {
                issues+=MotionV2CoreAssessment.Issue("source",MotionV2Findings.Status.EXECUTION_FAILED,"COMPLETION_HISTORY_PAIRING",i)
                break
            }
            lastSequence=sequence
            for(command in frame.commands) when(command.kind) {
                CommandKind.MOVE -> { near=0.0;known=true;associationKnown=true;activeGoal=command.targetAfter;activeSequence=command.sequence }
                CommandKind.STOP,CommandKind.UNREACHABLE -> { near=0.0;known=true;associationKnown=true;activeGoal=null;activeSequence=null }
                CommandKind.UNKNOWN -> { activeGoal=null;activeSequence=null;unknown(i) }
            }
            val goal=m.commandTargetBefore
            if(goal!=null && associationKnown && (!same(goal,activeGoal)||m.originMoveId!="$expectedActionPrefix$activeSequence")) {
                add(MotionV2Findings.Status.FAIL,"COMPLETION_MOVE_BINDING",i);unknown(i)
            }
            if(goal==null) {
                if(associationKnown&&m.originMoveId!=null) { add(MotionV2Findings.Status.FAIL,"COMPLETION_INACTIVE_ORIGIN",i);unknown(i) }
                expected+=Expected(if(known)near else null,null,false)
                if(m.normalCompletion) add(MotionV2Findings.Status.FAIL,"NORMAL_COMPLETION_ELIGIBILITY",i)
            } else if(!known || !associationKnown) {
                expected+=Expected(null,null,null);add(MotionV2Findings.Status.INSUFFICIENT,"NORMAL_HISTORY_UNKNOWN",i)
            } else {
                val dx=goal.x-m.positionBefore.x;val dy=goal.y-m.positionBefore.y;val d2=dx*dx+dy*dy
                if(d2<1681.0) near+=m.delta
                val radius=when { near>340.0 -> 36.0;near>240.0 -> 16.0;else -> 7.0 }
                val normal=d2<radius*radius
                expected+=Expected(near,radius,normal)
                if(normal!=m.normalCompletion) add(MotionV2Findings.Status.FAIL,"NORMAL_COMPLETION_ELIGIBILITY",i)
            }
            if(goal==null && frame.targetAfter!=null) {
                add(MotionV2Findings.Status.FAIL,"COMPLETION_COMMAND_RESURRECTED",i);unknown(i)
            } else if(goal!=null && frame.targetAfter!=null && (!same(goal,frame.targetAfter)||m.normalCompletion)) {
                add(MotionV2Findings.Status.FAIL,"COMPLETION_COMMAND_RETAINED_OR_CHANGED",i);unknown(i)
            } else if(goal!=null && frame.targetAfter==null) {
                val reset=frame.clear==Clear.NORMAL&&m.normalCompletion || frame.clear==Clear.UNREACHABLE ||
                    frame.clear==Clear.BOUNDARY&&m.terrainDisposition in setOf(MotionTerrainDisposition.CANDIDATE_OUTSIDE_MAP,MotionTerrainDisposition.RESOLVED_OUTSIDE_MAP)
                activeGoal=null;activeSequence=null
                if(reset) { near=0.0;known=true;associationKnown=true } else unknown(i)
            }
            end=m.timeBefore+m.delta;previous=frame
        }
        if(frames.isEmpty()) add(MotionV2Findings.Status.INSUFFICIENT,"NORMAL_HISTORY_MISSING",0)
        return Result(expected,issues)
    }
}
