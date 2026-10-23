package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import com.rustedwarfare.reforge.testing.cross.MotionV2DiagnosticAdapter.Cause
import com.rustedwarfare.reforge.testing.cross.MotionV2DiagnosticAdapter.Clear
import com.rustedwarfare.reforge.testing.cross.MotionV2DiagnosticAdapter.InputConsumption
import com.rustedwarfare.reforge.testing.cross.MotionV2DiagnosticAdapter.Facts
import kotlinx.serialization.json.*

/** Record consistency only. Internal callers must separately enforce their protocol
 * and runtime qualification. This function never grants source authority or PASS. */
internal object MotionV2RecordNormalizer {
    private fun flag(o: JsonObject,key: String)=o.required(key).jsonPrimitive.boolean
    private fun present(o: JsonObject,key: String)=o.required(key)!=JsonNull
    private fun samePosition(a: JsonObject,b: JsonObject)=a.number("x")==b.number("x") && a.number("y")==b.number("y")
    private fun sameTarget(a: JsonElement,b: JsonElement)=if(a==JsonNull || b==JsonNull)a==b else samePosition(a.jsonObject,b.jsonObject)
    private fun unit(observation: JsonObject)=observation.arrayAt("units").single().jsonObject.also {
        require(it.text("unitId")=="u1" && it.text("ownerPlayerId")=="p1" && it.text("typeId")=="tank")
    }
    private fun events(observation: JsonObject)=observation.arrayAt("events").map { it.jsonObject }.also {
        require(it.all { e->e.text("unitId")=="u1" })
    }.map { it.text("kind") }
    private fun points(document: JsonObject): List<MotionV2Sampling.Point> {
        require(flag(document,"complete") && document.integer("exitCode")==0L)
        val samples=document.arrayAt("samples").map { it.jsonObject }
        require(document.integer("recordCount")==samples.size.toLong())
        val result=samples.mapIndexed { i,s->
            require(s.integer("index")==i.toLong())
            val u=s.objectAt("state")
            require(u.text("id")=="u1" && u.text("owner")=="p1" && u.text("definition")=="tank")
            MotionV2Sampling.Point(s.number("q"),s.integer("phase"),s.integer("inputSequence"),u.number("x"),u.number("y"),u.number("heading"))
        }
        MotionV2Sampling.select(result,0.0,0.0) // Validate the entire ordered list before consuming it.
        return result
    }
    private fun inputRows(document: JsonObject)=document.arrayAt("inputs").map { it.jsonObject }.also { rows->
        require(rows.withIndex().all { (i,r)->r.integer("sequence")==i.toLong() && r.number("q")>=0 && r.text("action") in setOf("move","stop") })
        require(rows.zipWithNext().all { (a,b)->a.number("q")<=b.number("q") })
    }
    fun core(document: JsonObject, expectedActionIdPrefix: String): Facts {
        require(expectedActionIdPrefix.isNotBlank() && document.integer("motionInputSchemaVersion")==1L)
        val points=points(document);val inputs=inputRows(document)
        val samples=document.arrayAt("samples").map { it.jsonObject }
        val motions=document.arrayAt("motionInputs").map { it.jsonObject }
        val calls=mutableListOf<MotionV2Events.Call>();val clears=mutableListOf<Clear>()
        val consumptions=mutableListOf<InputConsumption>()
        var previous:JsonObject?=null;var motionIndex=0;var inputIndex=0;var sequence=-1L
        for((i,sample)in samples.withIndex()) {
            val raw=sample.objectAt("raw");val observation=raw.objectAt("observation")
            val u=unit(observation);val state=sample.objectAt("state");val p=points[i]
            require(p.phase==0L && observation.number("simulationTime")==p.q && samePosition(u.objectAt("position"),state) &&
                u.number("heading")==p.heading && sameTarget(u.required("moveTarget"),state.required("target")))
            if(i==0) {
                require(raw.text("kind")=="initial" && p.q==0.0 && p.input == -1L)
                previous=observation;continue
            }
            val before=requireNotNull(previous);val a=unit(before);val kinds=events(observation)
            require(before.text("worldId")==observation.text("worldId"))
            var cause:Cause?=null
            when(raw.text("kind")) {
                "input-post" -> {
                    require(inputIndex<inputs.size);val input=inputs[inputIndex++]
                    require(input.number("q")==p.q && before.number("simulationTime")==p.q &&
                        before.integer("stepIndex")==observation.integer("stepIndex"))
                    require(input.objectAt("raw").objectAt("before")==before && input.objectAt("raw").objectAt("after")==observation)
                    sequence=input.integer("sequence")
                    val inputRaw=input.objectAt("raw");val submitted=inputRaw.required("submitResult") as? JsonObject
                    require(submitted!=null && submitted.text("status")=="accepted" &&
                        submitted.text("actionId")=="$expectedActionIdPrefix$sequence" && submitted.required("queuedForStep")==JsonNull)
                    consumptions+=InputConsumption(sequence,input.text("action"),p.q,
                        MotionV2Events.Event(MotionV2Math.Interval(p.q,p.q),calls.size),inputRaw)
                    require(samePosition(a.objectAt("position"),u.objectAt("position")) &&
                        listOf("heading","speed","angularVelocity").all { a.number(it)==u.number(it) })
                    require("movement-arrived" !in kinds)
                    if(input.text("action")=="stop") {
                        require(!present(u,"moveTarget") && "movement-cancelled" in kinds && "path-unreachable" !in kinds)
                        if(present(a,"moveTarget"))cause=Cause.EXTERNAL_STOP
                    } else if("path-unreachable" in kinds) {
                        require(!present(u,"moveTarget") && "movement-cancelled" in kinds);cause=Cause.UNREACHABLE
                    } else require(sameTarget(u.required("moveTarget"),input.required("target")) && present(u,"moveTarget"))
                }
                "advance" -> {
                    require(motionIndex<motions.size);val m=motions[motionIndex++]
                    require(m.integer("stepIndex")==motionIndex.toLong()-1 && observation.integer("stepIndex")==m.integer("stepIndex") &&
                        before.integer("stepIndex")==maxOf(0L,m.integer("stepIndex")-1) &&
                        m.number("timeBefore")==before.number("simulationTime") && m.number("delta")>0 &&
                        m.number("timeBefore")+m.number("delta")==p.q && m.text("worldId")==observation.text("worldId") &&
                        m.text("unitId")=="u1" && m.text("ownerPlayerId")=="p1" && m.text("typeId")=="tank")
                    require(samePosition(m.objectAt("positionBefore"),a.objectAt("position")) &&
                        sameTarget(m.required("commandTargetBefore"),a.required("moveTarget")) &&
                        samePosition(m.objectAt("finalPosition"),u.objectAt("position")))
                    for((mk,uk)in listOf("headingBefore" to "heading","speedBefore" to "speed","angularVelocityBefore" to "angularVelocity"))require(m.number(mk)==a.number(uk))
                    for((mk,uk)in listOf("finalHeading" to "heading","finalSpeed" to "speed","finalAngularVelocity" to "angularVelocity"))require(m.number(mk)==u.number(uk))
                    val normal=flag(m,"normalCompletion")
                    require(normal == ("movement-arrived" in kinds))
                    if(normal) {
                        require(sequence>=0 && inputs[sequence.toInt()].text("action")=="move" &&
                            m.text("originMoveId")=="$expectedActionIdPrefix$sequence" && present(a,"moveTarget") && !present(u,"moveTarget") &&
                            sameTarget(a.required("moveTarget"),inputs[sequence.toInt()].required("target")) &&
                            "movement-cancelled" !in kinds && "path-unreachable" !in kinds)
                        cause=Cause.NORMAL
                    } else if(present(a,"moveTarget") && !present(u,"moveTarget")) {
                        require("movement-cancelled" in kinds)
                        cause=if("path-unreachable" in kinds)Cause.UNREACHABLE else Cause.UNKNOWN
                    }
                }
                else -> error("Unexpected noninitial diagnostic sample")
            }
            require(p.input==sequence)
            val ap=a.objectAt("position");val bp=u.objectAt("position")
            val call=MotionV2Events.Call(before.number("simulationTime"),p.q,p.phase,p.input,
                ap.number("x"),ap.number("y"),bp.number("x"),bp.number("y"),present(a,"moveTarget"),present(u,"moveTarget"))
            calls+=call
            if(cause!=null)clears+=Clear(cause,sequence,MotionV2Events.Event(MotionV2Math.Interval(call.entryQ,call.exitQ),calls.lastIndex))
            previous=observation
        }
        require(motionIndex==motions.size && inputIndex==inputs.size && calls.isNotEmpty())
        MotionV2Events.firstMove(calls,0) // Reject any late gap/order corruption before returning findings.
        return Facts(points,calls.toList(),clears.toList(),inputs.map { it.number("q") },consumptions.toList())
    }
    fun original(document: JsonObject, observationSchemaVersion: Int): Facts {
        require(observationSchemaVersion in 1..2)
        val localSource=observationSchemaVersion==2
        val points=points(document);val inputs=inputRows(document)
        val rows=document.arrayAt("nativeRows").map { it.jsonObject }
        val localMarkers=if(localSource)MotionV2LocalClear.markers(document,rows)else emptyMap()
        val samples=document.arrayAt("samples").map { it.jsonObject }
        require(samples.size==rows.size+1)
        fun key(o:JsonObject)=o.integer("phase") to o.integer("callIndex")
        require(rows.map(::key).distinct().size==rows.size)
        val inputByKey=inputs.associateBy { key(it.objectAt("raw")) }
        require(inputByKey.size==inputs.size)
        val facts=document.arrayAt("normalCauseFacts").map { it.jsonObject }
        require(facts.map(::key).distinct().size==facts.size)
        val byKey=facts.associateBy(::key);var factCount=0;var localCount=0
        val calls=mutableListOf<MotionV2Events.Call>();val clears=mutableListOf<Clear>()
        val consumptions=mutableListOf<InputConsumption>();var inputSequence=-1L
        var moveBits:Pair<Long,Long>?=null;var moveToken:Long?=null
        for((i,row)in rows.withIndex()) {
            val sample=samples[i+1];val raw=sample.objectAt("raw");val p=points[i+1]
            require(key(raw)==key(row) && p.phase==row.integer("phase") && p.q==row.number("qAfter"))
            val values=row.arrayAt("values");require(values.size==134 && raw.required("values")==values)
            fun long(index:Int)=values[index].jsonPrimitive.long
            fun number(index:Int)=Float.fromBits(long(index).toInt()).toDouble().also { require(it.isFinite()) }
            fun active(offset:Int)=when(long(offset+16)) { 0L->false;1L->true;else->error("Invalid raw target flag") }
            fun target(offset:Int):JsonElement=if(active(offset))obj("x" to n(number(offset+19)),"y" to n(number(offset+20)))else JsonNull
            val a=3;val z=70
            require(long(2)==long(69) && long(69)==long(z+2) && number(z+2)>=0 &&
                row.number("qBefore")+number(z+2)==row.number("qAfter")) {
                "Native event interval differs from actual effective I"
            }
            val input=inputByKey[key(row)]
            if(input!=null) {
                val r=input.objectAt("raw");val command=r.objectAt("command")
                require(input.integer("sequence")==inputSequence+1 && p.input==input.integer("sequence") &&
                    input.number("q")==row.number("qBefore") && r.number("consumptionEntryQ")==row.number("qBefore") &&
                    r.number("callExitQ")==row.number("qAfter") && r.required("values")==values)
                require(command.integer("inputSequence")==input.integer("sequence") && command.text("action")==input.text("action") &&
                    command.integer("selectedCount")==1L && flag(command,"ownerSame") && flag(command,"queueContainsSameOnly") &&
                    command.integer("queueSize")==1L && flag(command,"p")== (input.text("action")=="stop"))
                require(listOf(long(a+13),long(a+14),long(a+15))==listOf(1L,1L,0L) &&
                    listOf(long(z+13),long(z+14),long(z+15))==listOf(0L,0L,0L))
                for((name,offset)in listOf("stateBefore" to a,"stateAfter" to z)) {
                    val state=r.objectAt(name)
                    require(state.text("id")=="u1" && state.text("owner")=="p1" && state.text("definition")=="tank" &&
                        state.number("x")==number(offset+6) && state.number("y")==number(offset+7) &&
                        state.number("heading")==((number(offset+8)+90.0)%360.0+360.0)%360.0 &&
                        sameTarget(state.required("target"),target(offset)))
                }
                if(input.text("action")=="move") {
                    require(active(z) && long(z+17)==1L && flag(command,"waypointPresent") && flag(command,"waypointIsMove") &&
                        sameTarget(target(z),input.required("target")))
                    for((name,index)in listOf("x" to 19,"y" to 20)) {
                        val bits=command.objectAt(name);val hex=bits.text("bits")
                        require(flag(bits,"finite") && hex.matches(Regex("[0-9a-f]{8}")) &&
                            hex.toLong(16)==(long(z+index) and 0xffffffffL) && bits.number("value")==number(z+index))
                    }
                    moveBits=long(z+19) to long(z+20);moveToken=long(z+27)
                } else {
                    require(!active(z) && long(z+21)==0L && input.required("target")==JsonNull)
                    moveBits=null;moveToken=null
                }
                inputSequence=input.integer("sequence")
                consumptions+=InputConsumption(inputSequence,input.text("action"),row.number("qBefore"),
                    MotionV2Events.Event(MotionV2Math.Interval(row.number("qBefore"),row.number("qAfter")),i),r)
            } else require(listOf(long(a+13),long(a+14),long(a+15),long(z+13),long(z+14),long(z+15)).all { it==0L })
            require(p.input==inputSequence)
            if(i==0) {
                val initial=points.first()
                require(initial.phase==p.phase && initial.x==number(a+6) && initial.y==number(a+7) &&
                    initial.heading==((number(a+8)+90.0)%360.0+360.0)%360.0 &&
                    sameTarget(samples.first().objectAt("state").required("target"),target(a)))
            }
            require(p.x==number(z+6) && p.y==number(z+7) && p.heading==((number(z+8)+90.0)%360.0+360.0)%360.0 &&
                sameTarget(sample.objectAt("state").required("target"),target(z)))
            val call=MotionV2Events.Call(row.number("qBefore"),p.q,p.phase,p.input,number(a+6),number(a+7),p.x,p.y,active(a),active(z))
            require(long(a+28)==0L && long(a+29)==0L && long(z+28)==long(z+29) && long(z+28) in 0L..1L)
            val marked=long(z+28)==1L;val fact=byKey[key(row)]
            require((marked || (call.targetBefore && !call.targetAfter)) == (fact!=null))
            if(fact!=null) {
                factCount++;require(fact.integer("inputSequence")==p.input && !call.targetAfter)
                val interval=fact.arrayAt("intervalQ");require(interval.size==2 && interval[0].jsonPrimitive.double==call.entryQ && interval[1].jsonPrimitive.double==call.exitQ)
                val cause=when(fact.text("cause")) {
                    "normal-completion"->{require(marked && call.targetBefore);Cause.NORMAL}
                    "external-stop"->{require(!marked && call.targetBefore && p.input>=0 && inputs[p.input.toInt()].text("action")=="stop");Cause.EXTERNAL_STOP}
                    "unknown-clear"->{require(!marked && call.targetBefore);Cause.UNKNOWN}
                    "move-cleared-at-last-local-waypoint"->{
                        val proof=requireNotNull(localMarkers[key(row)])
                        require(!marked && call.targetBefore && p.input>=0 && inputs[p.input.toInt()].text("action")=="move" &&
                            proof.integer("inputSequence")==p.input && proof.required("event")==fact.required("branchProof"))
                        val event=proof.objectAt("event")
                        require((event.integer("targetXBits") to event.integer("targetYBits"))==moveBits && event.integer("oldToken")==moveToken)
                        localCount++;Cause.LOCAL_PATH_END
                    }
                    else->error("Unqualified original clear cause")
                }
                clears+=Clear(cause,p.input,MotionV2Events.Event(MotionV2Math.Interval(call.entryQ,call.exitQ),i))
            }
            calls+=call
        }
        require(factCount==facts.size && calls.isNotEmpty() && consumptions.size==inputs.size && localCount==localMarkers.size)
        MotionV2Events.firstMove(calls,0)
        return Facts(points,calls.toList(),clears.toList(),inputs.map { it.number("q") },consumptions.toList())
    }
}
