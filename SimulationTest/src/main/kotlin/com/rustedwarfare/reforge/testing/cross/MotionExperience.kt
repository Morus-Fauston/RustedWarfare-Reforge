package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*
import kotlin.math.*

/** O obstacle level compares legal results and experience. Full geometry is checked before this entry. */
internal object MotionExperience {
    private data class Summary(val trace: MotionTrace,val length: Double,val end: MotionPoint?,val reversal: MotionPoint?,val vectors: List<JsonElement>) {
        val duration get()=end?.let{it.q-(trace.inputs.firstOrNull()?.number("q") ?: 0.0)}
        fun json()=obj("role" to s(trace.role),"routeLength" to n(length),"timeQ" to duration?.let(::n),
            "timeSeconds" to duration?.let{n(it/60)},"rawHorizonQ" to n(trace.points.last().q),"judgmentHorizonQ" to n(min(600.0,trace.points.last().q)),
            "terminal" to end?.raw,"lastRaw" to trace.points.last().raw,"reversal" to reversal?.raw,"fullFrequencyVectors" to array(vectors),
            "targetActiveStationaryIntervals" to array(vectors.filter{v->val row=v.jsonObject;row["targetActive"]==b(true) && row.number("displacement")<=.001}))
    }
    fun compare(case: JsonObject,core: MotionTrace,original: MotionTrace,allowRatio: Boolean=true): MotionEvaluation {
        fun summarize(trace: MotionTrace): Summary {
            val points=trace.points.filter{it.q<=600}
            var length=0.0;var previousVector: Pair<Double,Double>?=null;var reversed=0;var firstReversal: MotionPoint?=null
            val vectors=mutableListOf<JsonElement>()
            for((a,z)in points.zipWithNext()) {
                val entry=z.intervalStart(a)
                if(a.input!=z.input){reversed=0;previousVector=null}
                length+=hypot(entry.x-a.x,entry.y-a.y)
                val dx=z.x-entry.x;val dy=z.y-entry.y;val size=hypot(dx,dy);length+=size
                val prior=previousVector
                val angle=if(size>=.05 && prior!=null)Math.toDegrees(acos(((dx*prior.first+dy*prior.second)/(size*hypot(prior.first,prior.second))).coerceIn(-1.0,1.0)))else null
                vectors+=obj("sampleIndex" to n(z.index),"entryQ" to n(entry.q),"exitQ" to n(z.q),"dx" to n(dx),"dy" to n(dy),"displacement" to n(size),
                    "deltaQ" to n(z.q-entry.q),"angleFromPrevious" to angle?.let(::n),"targetActive" to b(z.target!=JsonNull),"sourceRaw" to z.raw)
                if(size>=.05 && z.q>entry.q && z.input==entry.input) {
                    if(angle!=null && angle>=120)reversed++ else reversed=0
                    if(reversed>=3 && firstReversal==null)firstReversal=z
                    previousVector=dx to dy
                }else{reversed=0;previousVector=null}
            }
            val reachable=case.text("category")=="simple-static-obstacle-reachable"
            val target=case.arrayAt("move");val tx=target[0].jsonPrimitive.double;val ty=target[1].jsonPrimitive.double
            val end=points.firstOrNull{p->
                if(p.input<0 || p.target!=JsonNull || reachable && hypot(p.x-tx,p.y-ty)>4)false else {
                    val confirmation=points.firstOrNull{it.index>=p.index && it.q>=p.q+18}
                    confirmation!=null && points.filter{it.index in p.index..confirmation.index}.all{it.target==JsonNull && hypot(it.x-p.x,it.y-p.y)<=.001}
                }
            }
            return Summary(trace,length,end,firstReversal,vectors)
        }
        val a=summarize(core);val e=summarize(original)
        fun ratio(x: Double,y: Double)=if(x==0.0 && y==0.0)1.0 else if(min(x,y)==0.0)Double.POSITIVE_INFINITY else max(x,y)/min(x,y)
        val ratioApplicable=allowRatio && case.text("category")=="simple-static-obstacle-reachable" && a.end!=null && e.end!=null && min(a.length,e.length)>0 && min(a.duration!!,e.duration!!)>0
        val lengthRatio=if(ratioApplicable)ratio(a.length,e.length)else null
        val timeRatio=if(ratioApplicable)ratio(a.duration!!,e.duration!!)else null
        val details=obj("caseId" to case.required("id"),"actual" to a.json(),"expected" to e.json(),"sameRouteRequired" to b(false),
            "routeLengthRatio" to lengthRatio?.takeIf{it.isFinite()}?.let(::n),"durationRatio" to timeRatio?.takeIf{it.isFinite()}?.let(::n),
            "ratioGateApplied" to b(ratioApplicable),"sameFallbackPolicyRequired" to b(false))
        for(summary in listOf(a,e)) {
            summary.reversal?.let{return MotionEvaluation(2,"repeated-reversal",obj("role" to s(summary.trace.role),"raw" to it.raw,"context" to summary.trace.context(it.index)),details)}
            if(summary.end==null) {
                val budgetCovered=summary.trace.points.last().q>=600
                return MotionEvaluation(if(budgetCovered)2 else 3,if(budgetCovered)"no-required-terminal-within-budget"else"missing-terminal-coverage",
                    obj("role" to s(summary.trace.role),"raw" to summary.trace.points.last().raw),details)
            }
        }
        if(ratioApplicable && (lengthRatio!!>1.5 || timeRatio!!>1.5))return MotionEvaluation(2,"obstacle-experience-difference",
            obj("routeLengthRatio" to details.required("routeLengthRatio"),"durationRatio" to details.required("durationRatio"),"allowedRatio" to n(1.5),
                "actualTerminal" to a.end!!.raw,"expectedTerminal" to e.end!!.raw),details)
        return MotionEvaluation(0,"approved-O-tool-obstacle-control",JsonNull,details)
    }
}
