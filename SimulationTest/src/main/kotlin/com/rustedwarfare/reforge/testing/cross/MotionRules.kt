package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*
import kotlin.math.*

internal data class MotionEvaluation(val code: Int,val category: String,val first: JsonElement,val details: JsonObject)
internal data class MotionInterval(val low: Double,val high: Double,val index: Int,val confirmed: Int = index) {
    fun json(trace: MotionTrace) = obj("lowQ" to n(low),"highQ" to n(high),"sampleIndex" to n(index),
        "confirmedIndex" to n(confirmed),"raw" to trace.points[index].raw,
        "neighbors" to trace.context(index))
}

/** O.v1's immutable numeric and observable-event rules, shared by fixtures and native evidence. */
internal object MotionRules {
    fun compare(case: JsonObject, core: MotionTrace, original: MotionTrace): MotionEvaluation {
        val category = case.text("category")
        val obstacle=category in setOf("simple-static-obstacle-reachable","unreachable-enclosed-goal")
        for(trace in listOf(core,original))MotionGeometry.failure(case,trace)?.let { failure ->
            return if(obstacle)failure.copy(details=obj("geometry" to failure.details,
                "experienceDiagnostics" to MotionExperience.compare(case,core,original,allowRatio=false).details))else failure
        }
        val inputsC = core.inputs
        val inputsO = original.inputs
        val points = mutableListOf<JsonElement>()
        val metrics = linkedMapOf("position" to mutableListOf<Double>(),"heading" to mutableListOf<Double>(),"speed" to mutableListOf<Double>())
        val inputEvents=mutableListOf<JsonElement>()
        val unavailable=mutableListOf<JsonElement>()
        val incompleteMetrics=mutableSetOf<String>()
        var first: JsonElement = JsonNull
        fun inputFailure(reason: String,index: Int): MotionEvaluation = MotionEvaluation(2,reason,
            obj("field" to s(reason),"inputIndex" to n(index),"actual" to inputsC.getOrNull(index),"expected" to inputsO.getOrNull(index),
                "approvedCase" to case),obj("inputEvents" to array(inputEvents)))
        fun divergence(field: String,a: MotionPoint,e: MotionPoint,actual: JsonElement,expected: JsonElement) {
            if(first==JsonNull)first=obj("field" to s(field),"q" to n(a.q),"actual" to actual,"expected" to expected,
                "rawActual" to a.raw,"rawExpected" to e.raw,"actualContext" to core.context(a.index),"expectedContext" to original.context(e.index))
        }
        if(inputsC.size != inputsO.size)throw MotionFailure(3,"missing-input","Input counts differ")
        if(category=="stationary" && inputsO.isNotEmpty())return inputFailure("wrong-input",0)
        if(category!="stationary" && inputsO.isEmpty())throw MotionFailure(3,"missing-input","Move command evidence is absent")
        for((index,pair) in inputsC.zip(inputsO).withIndex()) {
            val (c,o)=pair
            val expectedAction=if(category=="stop-in-motion" && index==1) "stop" else "move"
            if(c.text("action")!=expectedAction || o.text("action")!=expectedAction || c.required("target")!=o.required("target")) {
                return inputFailure("wrong-input",index)
            }
            if(expectedAction=="move") {
                val target=case.arrayAt("move")
                val observed=o.objectAt("target")
                if(observed.number("x")!=target[0].jsonPrimitive.double || observed.number("y")!=target[1].jsonPrimitive.double) {
                    return inputFailure("wrong-target",index)
                }
            }
            if(c.number("q")!=ceil(o.number("q")))return inputFailure("input-timing",index)
            val rawO=o.objectAt("raw");val rawC=c.objectAt("raw")
            val exitQ=(rawO["callExitQ"] as? JsonPrimitive)?.doubleOrNull ?: o.number("q")
            if(exitQ-o.number("q")>1.2)unavailable+=obj("reason" to s("input-interval-width"),"actualRaw" to rawC,"expectedRaw" to rawO)
            inputEvents+=obj("sequence" to n(index),"originalEntryQ" to o.required("q"),"originalCallExitQ" to n(exitQ),
                "coreAcceptedQ" to c.required("q"),"quantizationDelayQ" to n(c.number("q")-o.number("q")),
                "comparison" to s("same-sequence pre/post event pair; not a same-time periodic numeric point"),"actualRaw" to rawC,"expectedRaw" to rawO)
        }
        if(category=="stop-in-motion" && inputsO.size!=2)throw MotionFailure(3,"missing-stop","Move/stop pair required")
        if(category!="stop-in-motion" && category!="stationary" && inputsO.size!=1)return inputFailure("extra-input",1)
        if(obstacle) {
            val experience=MotionExperience.compare(case,core,original)
            val code=if(experience.code==0 && unavailable.isNotEmpty())3 else experience.code
            return experience.copy(code=code,category=if(code==3)"incomplete-comparison-coverage"else experience.category,
                details=JsonObject(experience.details+mapOf("inputEvents" to array(inputEvents),"unavailableComparisons" to array(unavailable))))
        }
        val horizon=min(original.points.last().q,600.0)
        if(horizon<=0 || core.points.last().q<floor(horizon))throw MotionFailure(3,"missing-duration","Invalid or incomplete simulation coverage")
        val requested=sortedSetOf(0.0,horizon)
        var tick=6.0
        while(tick<horizon){requested+=tick;tick+=6}
        for(input in inputsO) for(offset in listOf(3.0,6.0,9.0,12.0,18.0)) {
            val time=input.number("q")+offset
            if(time<=horizon)requested+=time
        }
        for(time in requested) {
            val pair=try { core.at(time,1.0) to original.at(time,1.2) } catch(error: MotionFailure) {
                if(error.code!=3)throw error
                incompleteMetrics+=listOf("position","speed");if(category!="stationary" || time==0.0)incompleteMetrics+="heading"
                unavailable+=obj("reason" to s(error.category),"requestedQ" to n(time),"message" to error.message?.let(::s));continue
            }
            val (a,e)=pair
            if(a.input!=e.input){incompleteMetrics+=metrics.keys;unavailable+=obj("reason" to s("input-phase-mismatch"),"requestedQ" to n(time),"actualRaw" to a.raw,"expectedRaw" to e.raw);continue}
            val position=hypot(a.x-e.x,a.y-e.y)
            val heading=circular(a.heading,e.heading)
            metrics.getValue("position")+=position
            if(category!="stationary" || time==0.0)metrics.getValue("heading")+=heading
            var velocity: Double? = null
            if(time>=6) {
                val start=try {core.at(time-6,1.0) to original.at(time-6,1.2)} catch(error: MotionFailure) {
                    if(error.code!=3)throw error
                    incompleteMetrics+="speed";unavailable+=obj("reason" to s(error.category),"speedWindowEndQ" to n(time));null
                }
                val c0=start?.first;val o0=start?.second
                if(c0!=null && o0!=null && a.input==c0.input && e.input==o0.input && a.phase==c0.phase && e.phase==o0.phase && a.q>c0.q && e.q>o0.q) {
                    val cv=hypot(a.x-c0.x,a.y-c0.y)/(a.q-c0.q)
                    val ov=hypot(e.x-o0.x,e.y-o0.y)/(e.q-o0.q)
                    velocity=abs(cv-ov);metrics.getValue("speed")+=velocity
                }
            }
            points+=obj("requestedQ" to n(time),"actualQ" to n(a.q),"expectedQ" to n(e.q),
                "actualAgeQ" to n(time-a.q),"expectedAgeQ" to n(time-e.q),"positionError" to n(position),
                "headingError" to n(heading),"speedError" to velocity?.let(::n),
                "actualIndex" to n(a.index),"expectedIndex" to n(e.index))
            if(position>4)divergence("position",a,e,obj("x" to n(a.x),"y" to n(a.y)),obj("x" to n(e.x),"y" to n(e.y)))
            if(heading>10 && (category!="stationary" || time==0.0))divergence("heading",a,e,n(a.heading),n(e.heading))
            if(velocity!=null && velocity>.2)divergence("displacement-speed",a,e,n(velocity),n(.2))
        }
        val summary=metrics.mapValues { (name,values) ->
            if(values.isEmpty())unavailable+=obj("reason" to s("missing-metric-window"))
            obj("rmse" to if(values.isEmpty())JsonNull else n(sqrt(values.sumOf { it*it }/values.size)),"worst" to values.maxOrNull()?.let(::n),"count" to n(values.size),
                "completeMetricCoverage" to b(name !in incompleteMetrics && values.isNotEmpty()))
        }
        for((name,limit) in listOf("position" to 2.0,"heading" to 5.0,"speed" to .08)) {
            if(name !in incompleteMetrics && (summary.getValue(name)["rmse"] as? JsonPrimitive)?.doubleOrNull?.let{it>limit}==true && first==JsonNull)first=obj("field" to s("$name.rmse"),
                "actual" to summary.getValue(name).required("rmse"),"expectedMaximum" to n(limit),"pointContributions" to array(points))
        }
        val eventRows=linkedMapOf<String,JsonElement>()
        if(category!="stationary") {
            val ce=events(core,case);val oe=events(original,case)
            for(name in listOf("start")+when(category){"stop-in-motion"->listOf("stop-settle");"unreachable-enclosed-goal"->emptyList();else->listOf("arrival","arrival-settle")}+
                if(category=="arrival-deceleration-settle")listOf("arrival-deceleration")else emptyList()) {
                val a=ce[name];val e=oe[name]
                if(a==null || e==null){unavailable+=obj("event" to s(name),"coreObserved" to b(a!=null),"originalObserved" to b(e!=null),
                    "actualLastRaw" to core.points.last().raw,"expectedLastRaw" to original.points.last().raw);continue}
                if(a.high-a.low>1 || e.high-e.low>1.2){unavailable+=obj("reason" to s("event-interval-width"),"event" to s(name),"actual" to a.json(core),"expected" to e.json(original));continue}
                val limit=if(name.startsWith("arrival"))6.0 else 3.0
                eventRows[name]=obj("actual" to a.json(core),"expected" to e.json(original),"allowedQ" to n(limit))
                if(max(abs(a.low-e.low),abs(a.high-e.high))>limit && first==JsonNull)first=obj("field" to s("event.$name"),
                    "actual" to a.json(core),"expected" to e.json(original))
            }
        }
        val code=if(first!=JsonNull)2 else if(unavailable.isNotEmpty())3 else 0
        return MotionEvaluation(code,when(code){0->"approved-O-tool-control";2->"motion-difference";else->"incomplete-comparison-coverage"},first,
            obj("caseId" to case.required("id"),"points" to array(points),"metrics" to JsonObject(summary),"events" to JsonObject(eventRows),
                "inputEvents" to array(inputEvents),"unavailableComparisons" to array(unavailable),"judgmentHorizonQ" to n(horizon),"rawOriginalHorizonQ" to n(original.points.last().q)))
    }

    internal fun events(trace: MotionTrace,case: JsonObject): Map<String,MotionInterval> {
        val result=linkedMapOf<String,MotionInterval>()
        val points=trace.points.filter{it.q<=600}
        for((a,z) in points.zipWithNext()) {
            val entry=z.intervalStart(a)
            if(z.input>=0 && z.q>entry.q && hypot(z.x-entry.x,z.y-entry.y)>.001 && "start" !in result)result["start"]=MotionInterval(entry.q,z.q,z.index)
        }
        fun settled(start: Int): MotionInterval? {
            for(i in start until points.size) {
                val p=points[i]
                if(p.target!=JsonNull)continue
                val end=points.indexOfFirst { it.index>=i && it.q>=p.q+6 }
                if(end<0)continue
                if((i..end).all { j-> points[j].target==JsonNull && hypot(points[j].x-p.x,points[j].y-p.y)<=.001 }) {
                    return MotionInterval(points[max(0,i-1)].q,p.q,i,end)
                }
            }
            return null
        }
        val category=case.text("category")
        if(category=="arrival-deceleration-settle") {
            var plateauStart: Double?=null
            var plateauEnd: Double?=null
            for(i in 1 until points.lastIndex) {
                val z=points[i];val a=z.intervalStart(points[i-1])
                if(z.q<=a.q)continue
                if(a.input!=z.input || a.phase!=z.phase || a.input<0 || a.target==JsonNull){plateauStart=null;plateauEnd=null;continue}
                val speed=hypot(z.x-a.x,z.y-a.y)/(z.q-a.q)
                if(speed>=1.045){if(plateauStart==null)plateauStart=a.q;plateauEnd=z.q;continue}
                if(speed<1.025 && plateauStart!=null && plateauEnd!=null && plateauEnd-plateauStart>=6) {
                    val next=points.drop(i+1).firstOrNull{it.q>z.q}
                    val nextEntry=next?.intervalStart(points[next.index-1])
                    if(next!=null && next.input==z.input && next.phase==z.phase &&
                        nextEntry!=null && next.q>nextEntry.q && hypot(next.x-nextEntry.x,next.y-nextEntry.y)/(next.q-nextEntry.q)<1.025) {
                        result["arrival-deceleration"]=MotionInterval(a.q,z.q,z.index,next.index)
                    }
                    break // The first below-threshold interval is fixed, even if its confirmation is absent.
                }
                // Once the >=6q platform is complete, the transition band cannot erase it.
                if(plateauStart==null || plateauEnd==null || plateauEnd-plateauStart<6){plateauStart=null;plateauEnd=null}
            }
        }
        if(category=="stop-in-motion") {
            val stop=trace.inputs.last().number("q")
            settled(points.indexOfFirst { it.q>=stop }.coerceAtLeast(0))?.let { result["stop-settle"]=it }
        } else if(category!="unreachable-enclosed-goal") {
            val target=case.arrayAt("move");val x=target[0].jsonPrimitive.double;val y=target[1].jsonPrimitive.double
            val arrival=points.indexOfFirst { it.input>=0 && it.target==JsonNull && hypot(it.x-x,it.y-y)<=4 }
            if(arrival>=0) {
                settled(arrival)?.let {
                    result["arrival"]=MotionInterval(points[max(0,arrival-1)].q,points[arrival].q,arrival,it.confirmed)
                    result["arrival-settle"]=it
                }
            }
        }
        return result
    }
    private fun circular(a: Double,b: Double)=abs(((a-b+180)%360+360)%360-180)
}

internal val MotionTrace.inputs get()=document.arrayAt("inputs").map { it.jsonObject }
internal fun MotionTrace.at(q: Double,maximumAge: Double): MotionPoint {
    val point=points.lastOrNull { it.q<=q } ?: throw MotionFailure(3,"missing-point","No sample at/before q=$q")
    if(q-point.q>maximumAge)throw MotionFailure(3,"stale-point","$role sample age exceeds O at q=$q")
    return point
}
internal fun MotionTrace.context(index: Int)=array((max(0,index-1)..min(points.lastIndex,index+1)).map { points[it].raw })

/** Native callback entry is an observed state too; preserve it for swept geometry and interval events. */
internal fun MotionPoint.intervalStart(previous: MotionPoint): MotionPoint {
    val values=(raw["raw"] as? JsonObject)?.get("values") as? JsonArray ?: return previous
    if(values.size!=50)throw MotionFailure(3,"native-raw-width","Expected all 50 pre/post longs")
    fun value(index: Int)=Float.fromBits(values[index].jsonPrimitive.long.toInt()).toDouble()
    val target=if(values[19].jsonPrimitive.long==0L)JsonNull else obj("x" to n(value(22)),"y" to n(value(23)))
    return copy(q=q-value(30),x=value(9),y=value(10),heading=((value(11)+90)%360+360)%360,target=target,
        raw=obj("kind" to s("native-call-entry"),"q" to n(q-value(30)),"sourcePostRecord" to raw))
}
