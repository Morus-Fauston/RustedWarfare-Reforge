package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*
import kotlin.math.ceil
import kotlin.math.hypot

private typealias RuleStatus = MotionV2Findings.Status
private typealias RuleFinding = MotionV2Findings.Finding

/** Pure rule wiring. No file identity, approval, runtime or package qualification is granted here. */
internal object MotionV2Rules {
    data class Side(val facts: MotionV2DiagnosticAdapter.Facts, val completeRaw: Boolean, val requiredHorizonQ: Double) {
        init { require(requiredHorizonQ.isFinite() && requiredHorizonQ in 0.0..600.0) }
    }
    data class Endpoints(val length: String?, val duration: String?)
    private val externalGates=setOf("identity","raw-integrity","terrain","core-map-outer","core-knowledge-regressions")
    private fun status(s:MotionV2Math.Status)=RuleStatus.valueOf(s.name)
    private fun deadline(side:Side)=(side.facts.calls.lastOrNull()?.exitQ ?: 0.0)>=600
    private fun terminal(side:Side,cause:MotionV2DiagnosticAdapter.Cause,input:Long)=
        side.facts.clears.firstOrNull { it.input==input }?.takeIf { it.cause==cause && it.event.interval.high<=600 }
    private fun missingTerminal(side:Side,cause:MotionV2DiagnosticAdapter.Cause,input:Long):RuleStatus {
        val observed=side.facts.clears.filter { it.input==input }
        if(observed.any { it.cause!=cause && it.cause!=MotionV2DiagnosticAdapter.Cause.UNKNOWN })return RuleStatus.FAIL
        if(observed.any { it.cause==MotionV2DiagnosticAdapter.Cause.UNKNOWN })return RuleStatus.INSUFFICIENT
        return if(deadline(side))RuleStatus.FAIL else RuleStatus.INSUFFICIENT
    }
    private fun tail(side:Side,clear:MotionV2DiagnosticAdapter.Clear?)=clear?.let {
        MotionV2Events.stableTail(side.facts.calls,it.input,it.event) }
    private fun tailProofStatus(side:Side,value:MotionV2Events.Tail?):RuleStatus = when {
        value==null->if(deadline(side))RuleStatus.FAIL else RuleStatus.INSUFFICIENT
        !value.clearLifecycleIntact || !value.fullRawFutureStable || value.eighteenQStable==false->RuleStatus.FAIL
        !value.eighteenQWithinBudget || value.eighteenQStable==null->if(deadline(side))RuleStatus.FAIL else RuleStatus.INSUFFICIENT
        else->RuleStatus.PASS
    }
    private fun tailStatus(side:Side,value:MotionV2Events.Tail?):RuleStatus {
        val proof=tailProofStatus(side,value)
        return if(proof==RuleStatus.PASS && !side.completeRaw)RuleStatus.INSUFFICIENT else proof
    }
    fun evaluate(case: JsonObject, core: Side, original: Side, endpoints: Endpoints,
                 external: List<RuleFinding>): List<RuleFinding> {
        require(core.requiredHorizonQ==original.requiredHorizonQ) { "Pair must use the same explicitly required numerical horizon" }
        val id=case.text("id")
        require(id in setOf("OM00","OM01E","OM01W","OM02S","OM02N","OM03SE","OM03NW","OM04","OM05","OM06","OM07","OM08"))
        val required=case.arrayAt("applicable").map { it.jsonPrimitive.content }.toSet()
        require(required.isNotEmpty() && required.size==case.arrayAt("applicable").size)
        require(external.all { it.gateId in externalGates && it.gateId in required && it.detail.isNotBlank() })
        if(id=="OM07") {
            require(endpoints.length in setOf("normal-clear","settle-start","declared-stable-tail-end","raw-captured-end") &&
                endpoints.duration in setOf("normal-clear","settle-start")) { "Explicit frozen OM07 endpoints required" }
        }
        val result=external.toMutableList()
        fun emit(gate:String,s:RuleStatus,detail:String) { if(gate in required)result+=RuleFinding(gate,s,detail) }
        fun compare(gate:String,c:MotionV2Events.Event?,o:MotionV2Events.Event?,tolerance:Double) {
            if(c==null || o==null)emit(gate,RuleStatus.INSUFFICIENT,"matching qualified event missing")
            else { val r=MotionV2Math.interval(c.interval,o.interval,tolerance)
                emit(gate,status(r.status),"core call ${c.index} ${c.interval}; original call ${o.index} ${o.interval}; L=${r.minimumDifference}; U=${r.maximumDifference}; T=$tolerance") }
        }
        val sides=listOf("core" to core,"original" to original)
        for((name,side)in sides)if(!side.completeRaw)
            emit("raw-integrity",RuleStatus.INSUFFICIENT,"$name raw incomplete; paired prefix findings remain independently applicable")
        if(id=="OM00") {
            for((name,side)in sides) {
                val f=side.facts
                val empty=f.inputs.isEmpty() && f.consumptions.isEmpty() && f.clears.isEmpty() && f.calls.all { !it.targetBefore && !it.targetAfter }
                emit("zero-input-integrity",if(!empty)RuleStatus.FAIL else if(!side.completeRaw)RuleStatus.INSUFFICIENT else RuleStatus.PASS,"$name zero-input=$empty")
                val initial=f.points.first();val maximum=f.points.maxOf { hypot(it.x-initial.x,it.y-initial.y) }
                emit("stationary-position",if(maximum>.001)RuleStatus.FAIL else if(!side.completeRaw || f.points.last().q<18)RuleStatus.INSUFFICIENT else RuleStatus.PASS,
                    "$name maximum raw drift=$maximum; lastQ=${f.points.last().q}")
                emit("initial-heading",if(initial.heading==case.number("coreHeading"))RuleStatus.PASS else RuleStatus.FAIL,"$name initial normalized heading=${initial.heading}")
            }
        } else {
            val expected=if(id=="OM05")listOf("move","stop")else listOf("move")
            for((name,side)in sides) {
                val consumed=side.facts.consumptions
                val wrong=consumed.withIndex().any { (index,input)->index !in expected.indices || input.sequence!=index.toLong() || input.action!=expected[index] } ||
                    side.facts.inputs!=consumed.map { it.quantizationEntryQ }
                if(wrong)emit("input-consumption",RuleStatus.FAIL,"$name observed wrong input sequence/action/entry")
                if(consumed.size!=expected.size)emit("input-consumption",if(side.completeRaw)RuleStatus.FAIL else RuleStatus.INSUFFICIENT,"$name required inputs missing or extra")
                if(!side.completeRaw)emit("input-consumption",RuleStatus.INSUFFICIENT,"$name raw incompleteness retained separately from any observed wrong input")
            }
            if(core.facts.consumptions.size==expected.size && original.facts.consumptions.size==expected.size)
                for((c,o)in core.facts.consumptions.zip(original.facts.consumptions)) {
                    if(c.quantizationEntryQ!=ceil(o.quantizationEntryQ))emit("input-consumption",RuleStatus.FAIL,"input ${c.sequence} core entry not ceil(original entry)")
                    compare("input-consumption",c.event,o.event,3.0)
                }
        }
        if(required.any { it in setOf("position-rmse-max","heading-rmse-max","speed-window-rmse-max") }) {
            // The qualified plan supplies the horizon, including when either
            // source ended early. Observed end times never reduce this set.
            val horizon=original.requiredHorizonQ
            if(horizon<=0)for(gate in listOf("position-rmse-max","heading-rmse-max","speed-window-rmse-max"))emit(gate,RuleStatus.INSUFFICIENT,"reference numerical horizon missing")
            else {
                // All inputs remain in the input-consumption gate. Only the
                // offsets belonging to this numerical window are sampled here.
                val sampled=MotionV2Sampling.evaluate(core.facts.points,original.facts.points,original.facts.inputs.filter { it<=horizon },horizon)
                for((gate,m)in listOf("position-rmse-max" to sampled.position,"heading-rmse-max" to sampled.heading,"speed-window-rmse-max" to sampled.speed)) {
                    emit(gate,if(m==null)RuleStatus.INSUFFICIENT else status(m.status),"fixed original horizon=$horizon; metric=$m")
                    if(m?.status==MotionV2Math.Status.FAIL && m.value?.count!=m.expectedCount)
                        emit(gate,RuleStatus.INSUFFICIENT,"known numerical failure retained separately; required points missing: observed=${m.value?.count}, expected=${m.expectedCount}")
                    val incomplete=sides.filter { !it.second.completeRaw }.map { it.first }
                    if(incomplete.isNotEmpty())emit(gate,RuleStatus.INSUFFICIENT,"raw incomplete for $incomplete; observed numerical result retained separately")
                }
            }
        }
        if("first-motion" in required)compare("first-motion",MotionV2Events.firstMove(core.facts.calls,0),MotionV2Events.firstMove(original.facts.calls,0),3.0)
        if("arrival-deceleration" in required)compare("arrival-deceleration",MotionV2Events.deceleration(core.facts.calls,0).event,MotionV2Events.deceleration(original.facts.calls,0).event,6.0)
        val cause=when(id) { "OM05"->MotionV2DiagnosticAdapter.Cause.EXTERNAL_STOP;"OM08"->MotionV2DiagnosticAdapter.Cause.UNREACHABLE;else->MotionV2DiagnosticAdapter.Cause.NORMAL }
        val sequence=if(id=="OM05")1L else 0L
        val cClear=if(id!="OM00")terminal(core,cause,sequence)else null
        val oClear=if(id !in setOf("OM00","OM08"))terminal(original,cause,sequence)else null
        val cTail=tail(core,cClear);val oTail=tail(original,oClear)
        if(id !in setOf("OM00","OM05","OM08"))for((name,side)in sides) {
            val clear=if(name=="core")cClear else oClear
            val multiple=side.facts.clears.count { it.input==sequence }>1
            emit("normal-cause",if(multiple)RuleStatus.FAIL else if(clear!=null)RuleStatus.PASS else missingTerminal(side,cause,sequence),"$name first qualified normal clear=${clear?.event}; multiple clears=$multiple")
            if(multiple && side.facts.clears.any { it.input==sequence && it.cause==MotionV2DiagnosticAdapter.Cause.UNKNOWN })
                emit("normal-cause",RuleStatus.INSUFFICIENT,"$name unknown first/extra clear reason retained alongside proved lifecycle failure")
        }
        if(id=="OM08")emit("core-unreachable-cancel",if(core.facts.clears.count { it.input==sequence }>1)RuleStatus.FAIL else if(cClear!=null)RuleStatus.PASS else missingTerminal(core,cause,sequence),"actual first core unreachable clear=${cClear?.event}")
        if(id=="OM05")for((name,side)in sides) {
            val clear=if(name=="core")cClear else oClear
            if(clear==null)emit("stop-clear",missingTerminal(side,cause,sequence),"$name actual stop clearing the active Move missing or wrong cause")
            if(side.facts.clears.count { it.input==sequence }>1)emit("stop-clear",RuleStatus.FAIL,"$name multiple clear events for one stop")
        }
        compare("normal-clear",cClear?.event,oClear?.event,6.0)
        compare("stop-clear",cClear?.event,oClear?.event,3.0)
        compare("normal-settle",cTail?.event,oTail?.event,6.0)
        compare("stop-settle",cTail?.event,oTail?.event,3.0)
        for((name,side)in sides) {
            if(id=="OM00" || id=="OM08" && name=="original")continue
            val value=if(name=="core")cTail else oTail
            emit("normal-settle-presence",if(value!=null)RuleStatus.PASS else if(deadline(side))RuleStatus.FAIL else RuleStatus.INSUFFICIENT,"$name earliest settle=${value?.event}")
            emit("core-settle-presence",if(value!=null)RuleStatus.PASS else if(deadline(side))RuleStatus.FAIL else RuleStatus.INSUFFICIENT,"$name earliest settle=${value?.event}")
            emit(if(id=="OM08")"core-stable-tail" else "stable-tail",tailStatus(side,value),"$name earliest fixed tail=$value")
        }
        if("final-position-difference" in required) {
            if(tailProofStatus(core,cTail)==RuleStatus.PASS && tailProofStatus(original,oTail)==RuleStatus.PASS) {
                val cp=core.facts.calls[requireNotNull(cTail?.endIndex)];val op=original.facts.calls[requireNotNull(oTail?.endIndex)]
                val distance=hypot(cp.x-op.x,cp.y-op.y);val target=case.arrayAt("move")
                val x=target[0].jsonPrimitive.double;val y=target[1].jsonPrimitive.double
                emit("final-position-difference",if(distance<=4)RuleStatus.PASS else RuleStatus.FAIL,
                    "tail-end core call ${cTail.endIndex}; original call ${oTail.endIndex}; difference=$distance; goal distances=${hypot(cp.x-x,cp.y-y)},${hypot(op.x-x,op.y-y)}")
                for((name,side)in sides)if(!side.completeRaw)
                    emit("final-position-difference",RuleStatus.INSUFFICIENT,"$name raw incomplete; qualified fixed endpoint result retained separately")
            } else emit("final-position-difference",RuleStatus.INSUFFICIENT,"qualified normal fixed tail endpoint missing")
        }
        if(id=="OM07") {
            val cMove=core.facts.consumptions.singleOrNull()?.takeIf { it.sequence==0L && it.action=="move" }
            val oMove=original.facts.consumptions.singleOrNull()?.takeIf { it.sequence==0L && it.action=="move" }
            fun length(side:Side,move:MotionV2DiagnosticAdapter.InputConsumption,clear:MotionV2DiagnosticAdapter.Clear,value:MotionV2Events.Tail):Double {
                val end=when(endpoints.length) { "normal-clear"->clear.event.index;"settle-start"->value.event.index
                    "declared-stable-tail-end"->requireNotNull(value.endIndex);else->side.facts.calls.lastIndex }
                val begin=move.event.index
                return side.facts.calls.subList(begin,end+1).sumOf { hypot(it.x-it.beforeX,it.y-it.beforeY) }
            }
            fun duration(move:MotionV2DiagnosticAdapter.InputConsumption,clear:MotionV2DiagnosticAdapter.Clear,value:MotionV2Events.Tail):MotionV2Math.Interval {
                val event=if(endpoints.duration=="normal-clear")clear.event else value.event
                val entry=move.quantizationEntryQ
                return MotionV2Math.Interval(event.interval.low-entry,event.interval.high-entry)
            }
            if(cMove!=null && oMove!=null && cClear!=null && oClear!=null && tailProofStatus(core,cTail)==RuleStatus.PASS && tailProofStatus(original,oTail)==RuleStatus.PASS) {
                val cl=length(core,cMove,cClear,requireNotNull(cTail));val ol=length(original,oMove,oClear,requireNotNull(oTail))
                val l=MotionV2Math.oneWayRatio(cl,ol)
                emit("length-one-way-ratio",status(l.first),"endpoint=${endpoints.length}; core=$cl; original=$ol; ratio=${l.second}")
                val c=duration(cMove,cClear,cTail);val o=duration(oMove,oClear,oTail)
                val s=if(o.low<=0 || c.low<0)RuleStatus.INSUFFICIENT else if(c.low/o.high>1.5)RuleStatus.FAIL else if(c.high/o.low<=1.5)RuleStatus.PASS else RuleStatus.INSUFFICIENT
                emit("duration-one-way-ratio",s,"endpoint=${endpoints.duration}; core=$c; original=$o; worst ratio interval=${if(o.high>0)c.low/o.high else null}..${if(o.low>0)c.high/o.low else null}")
                for((name,side)in sides)if(!side.completeRaw)
                    for(gate in listOf("length-one-way-ratio","duration-one-way-ratio"))
                        emit(gate,RuleStatus.INSUFFICIENT,"$name raw incomplete; qualified fixed endpoint result retained separately")
            } else for(gate in listOf("length-one-way-ratio","duration-one-way-ratio"))emit(gate,RuleStatus.INSUFFICIENT,"both unique initial Move consumptions and qualified normal fixed tail endpoints required")
        }
        if(id in setOf("OM07","OM08"))for((name,side)in sides) {
            val index=MotionV2Math.firstReversal(side.facts.calls.map { MotionV2Math.Vector(it.input.toInt(),it.entryQ,it.exitQ,it.x-it.beforeX,it.y-it.beforeY) })
            emit(if(name=="core")"core-reversal-gate" else "original-reversal-reference",
                if(name=="core" && index!=null)RuleStatus.FAIL else if(!side.completeRaw)RuleStatus.INSUFFICIENT else RuleStatus.PASS,"$name first reversal call=$index; original is reference only")
        }
        if(id=="OM08") {
            val originalClear=original.facts.clears.firstOrNull { it.input==0L }
            val unknown=originalClear?.cause==MotionV2DiagnosticAdapter.Cause.UNKNOWN
            emit("original-fallback-reference",if(!original.completeRaw || unknown)RuleStatus.INSUFFICIENT else RuleStatus.PASS,"actual original clear=$originalClear; lastQ=${original.facts.calls.last().exitQ}")
            emit("original-full-600-if-ongoing",when {
                unknown->RuleStatus.INSUFFICIENT
                originalClear!=null->if(original.completeRaw)RuleStatus.PASS else RuleStatus.INSUFFICIENT
                deadline(original)->if(original.completeRaw)RuleStatus.PASS else RuleStatus.INSUFFICIENT
                else->RuleStatus.INSUFFICIENT
            },"actual reference reason=${originalClear?.cause ?: "ongoing"}; lastQ=${original.facts.calls.last().exitQ}")
        }
        for(gate in required)if(result.none { it.gateId==gate })emit(gate,RuleStatus.INSUFFICIENT,"required source/qualification or unimplemented gate missing")
        return result.toList()
    }
}
