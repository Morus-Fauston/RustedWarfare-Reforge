package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*

/** Checks the qualified P sidecar against its raw calls. It cannot establish
 * APK/transport qualification or turn a last local waypoint into arrival. */
internal object MotionV2LocalClear {
    private fun key(o: JsonObject)=o.integer("phase") to o.integer("callIndex")
    fun markers(document: JsonObject, rows: List<JsonObject>): Map<Pair<Long,Long>,JsonObject> {
        require(document.integer("motionObservationSchemaVersion")==2L)
        val phases=document.arrayAt("phases").map { it.jsonObject }
        require(phases.map { it.objectAt("summary").integer("phase") }.toSet()==rows.map { it.integer("phase") }.toSet())
        require(phases.map { it.objectAt("summary").integer("phase") }.distinct().size==phases.size)
        val found=linkedMapOf<Pair<Long,Long>,JsonObject>()
        for(phase in phases) {
            val p=phase.objectAt("summary").integer("phase");val meta=phase.objectAt("observationMetadata")
            val native=rows.filter { it.integer("phase")==p }
            require(native.map { it.integer("callIndex") }==native.indices.map { it.toLong() })
            val minimal=meta.objectAt("minimalObservation");val side=minimal.objectAt("localPathClear")
            require(minimal.integer("schemaVersion")==2L && minimal.required("firstError")==JsonNull &&
                meta.integer("phase")==p && meta.required("enabled")==b(true) && meta.integer("entered")==native.size.toLong() &&
                meta.integer("exited")==native.size.toLong() && native.size in 1..512)
            require(side.keys==setOf("schemaVersion","phase","completedCallCount","eventCount","events","collectionEnabled","firstError"))
            val events=side.arrayAt("events").map { it.jsonObject }
            require(side.integer("schemaVersion")==1L && side.integer("phase")==p && side.integer("completedCallCount")==native.size.toLong() &&
                side.integer("eventCount")==events.size.toLong() && events.size<=16 && side.required("collectionEnabled")==b(true) && side.required("firstError")==JsonNull)
            val thread=meta.text("threadId");require(thread.toLong()>0 && thread.toLong().toString()==thread)
            val indices=events.map { it.integer("callIndex") };require(indices.zipWithNext().all { (a,z)->a<z })
            for(event in events) {
                val names=setOf("phase","callIndex","threadId","branchId","unitEj","oldToken","targetXBits","targetYBits","kindOrdinal",
                    "beforeXBits","beforeYBits","beforeCountO","s","afPresent","afterUnitEj","afterToken","afterCountO","afterXBits","afterYBits","beforeCount","afterCount")
                require(event.keys==names);for(name in names-"branchId")event.integer(name)
                val index=event.integer("callIndex");require(index in native.indices.map { it.toLong() })
                val row=native[index.toInt()];val values=row.arrayAt("values");require(values.size==134)
                fun raw(i:Int)=values[i].jsonPrimitive.long
                require(event.integer("phase")==p && event.text("branchId")=="bp.P@0297" &&
                    event.integer("threadId")==thread.toLong() && raw(1)==thread.toLong() && raw(68)==thread.toLong())
                require(event.integer("beforeCount")==1L && event.integer("afterCount")==1L && event.integer("beforeCountO")==1L &&
                    event.integer("s")==0L && event.integer("afPresent")==0L && event.integer("unitEj")==event.integer("afterUnitEj") &&
                    event.integer("unitEj")==raw(3+5) && raw(3+5)==raw(70+5))
                require(event.integer("oldToken")>0 && event.integer("oldToken")==raw(3+27) && raw(3+27)==raw(70+58) &&
                    listOf(raw(3+16),raw(3+17),raw(3+21),raw(70+43)).all { it==1L } && raw(70+28)==0L && raw(70+29)==0L)
                val kind=event.integer("kindOrdinal");val enums=meta.arrayAt("enumNames")
                require(kind>=0 && kind<enums.size && enums[kind.toInt()].jsonPrimitive.content=="move" && kind==raw(3+18) && kind==raw(70+61))
                require(listOf(event.integer("afterToken"),event.integer("afterCountO"),raw(70+16),raw(70+21),raw(70+27)).all { it==0L })
                for((axis,position,target)in listOf(Triple("X",6,19),Triple("Y",7,20))) {
                    for(prefix in listOf("target","before","after")) {
                        val bits=event.integer(prefix+axis+"Bits");require(bits in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && Float.fromBits(bits.toInt()).isFinite())
                    }
                    require(event.integer("target${axis}Bits")==raw(3+target) && raw(3+target)==raw(70+target+40))
                    require(event.integer("before${axis}Bits")==event.integer("after${axis}Bits") && event.integer("before${axis}Bits")==raw(3+position))
                }
                require(found.put(key(event),event)==null)
            }
        }
        val flat=document.arrayAt("localClearFacts").map { it.jsonObject }
        require(flat.map(::key).distinct().size==flat.size && flat.size==found.size)
        for(fact in flat) {
            require(fact.keys==setOf("phase","callIndex","inputSequence","intervalQ","event"))
            require(fact.objectAt("event")==found[key(fact)])
            val row=rows.single { key(it)==key(fact) };val interval=fact.arrayAt("intervalQ")
            require(interval.size==2 && interval[0].jsonPrimitive.double==row.number("qBefore") && interval[1].jsonPrimitive.double==row.number("qAfter"))
        }
        return flat.associateBy(::key)
    }
}
