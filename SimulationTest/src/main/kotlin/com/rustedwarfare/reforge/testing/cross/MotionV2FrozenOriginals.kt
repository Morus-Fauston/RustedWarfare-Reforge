package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.*

/** Frozen-slot format and file identities only. Neither raw, clocks, executed
 * argv nor qualification evidence is interpreted or granted source eligibility. */
internal object MotionV2FrozenOriginals {
    data class Parsed(val document:JsonObject,val entries:List<JsonObject>,val identities:List<JsonObject>)
    fun load(reference:JsonObject,slots:List<JsonObject>):Parsed {
        val consumed=mutableListOf<JsonObject>()
        fun keys(o:JsonObject,expected:Set<String>) { require(o.keys==expected) { "Unexpected frozen-original fields" } }
        fun uuid(value:String) { require(UUID.fromString(value).toString()==value) }
        fun bound(r:JsonObject):Path {
            keys(r,setOf("path","bytes","sha256"));val p=Path.of(r.text("path"))
            require(p.isAbsolute && p==p.normalize() && Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))
            require(r.integer("bytes")>=0 && Files.size(p)==r.integer("bytes") &&
                r.text("sha256").matches(Regex("[0-9a-f]{64}")) && sha256(p)==r.text("sha256"))
            consumed+=r;return p
        }
        fun historical(r:JsonObject) {
            keys(r,setOf("recordedPath","bytes","sha256"));val p=Path.of(r.text("recordedPath"))
            require(p.isAbsolute && p==p.normalize() && r.integer("bytes")>=0 && r.text("sha256").matches(Regex("[0-9a-f]{64}")))
        }
        val cases=setOf("OM00","OM01E","OM01W","OM02S","OM02N","OM03SE","OM03NW","OM04","OM05","OM06","OM07","OM08")
        require(slots.size==36)
        for(slot in slots) {
            keys(slot,setOf("caseId","repeat","originalRunId","coreRunId"))
            require(slot.text("caseId") in cases && slot.integer("repeat") in 1L..3L)
            uuid(slot.text("originalRunId"));uuid(slot.text("coreRunId"))
        }
        require(slots.map { it.text("caseId") to it.integer("repeat") }.toSet()==cases.flatMap { id->(1L..3L).map { id to it } }.toSet())
        val activeIds=slots.flatMap { listOf(it.text("originalRunId"),it.text("coreRunId")) }.toSet()
        require(activeIds.size==72)
        val retiredIds=mutableSetOf<String>()
        val document=OriginalMotionComparison.read(bound(reference))
        keys(document,setOf("kind","schemaVersion","entries"))
        require(document.text("kind")=="motion-v2-frozen-originals" && document.integer("schemaVersion")==1L)
        val entries=document.arrayAt("entries").map { it.jsonObject };require(entries.size==36)
        for((index,entry) in entries.withIndex()) {
            keys(entry,setOf("caseId","repeat","originalRunId","originalRequest","capture","rawRefs","technicalQualification",
                "replacementChain","captureClockEvidence","absoluteUtc","absoluteUtcStatus"))
            val slot=slots[index]
            for(key in listOf("caseId","repeat","originalRunId"))require(entry.required(key)==slot.required(key))
            val request=entry.objectAt("originalRequest")
            keys(request,setOf("schemaVersion","profile","requestId","runId","targetApkSha256","testApkSha256","signerSha256"))
            for(key in request.keys)require(request.text(key).isNotBlank())
            require(request.text("schemaVersion")=="1" && request.required("runId")==entry.required("originalRunId"))
            for(key in listOf("targetApkSha256","testApkSha256","signerSha256"))require(request.text(key).matches(Regex("[0-9a-f]{64}")))
            val capture=OriginalMotionComparison.read(bound(entry.objectAt("capture")))
            require(capture.required("caseId")==entry.required("caseId") && capture.required("request")==request)
            val instrument=capture.arrayAt("commands").map { it.jsonObject }.single { it.text("label")=="instrument" }
            val raw=entry.objectAt("rawRefs");keys(raw,setOf("stdout","stderr"))
            for(key in raw.keys) { require(raw.required(key)==instrument.required(key));bound(raw.objectAt(key)) }
            val technical=OriginalMotionComparison.read(bound(entry.objectAt("technicalQualification")))
            keys(technical,setOf("kind","schemaVersion","scope","caseId","originalRunId","capture","rawRefs","evidenceRefs"))
            require(technical.text("kind")=="motion-v2-original-technical-qualification" && technical.integer("schemaVersion")==1L &&
                technical.text("scope")=="technical-eligibility-only")
            for(key in listOf("caseId","originalRunId","capture","rawRefs"))require(technical.required(key)==entry.required(key))
            val evidence=technical.arrayAt("evidenceRefs").map { it.jsonObject }
            require(evidence.isNotEmpty() && evidence.distinct().size==evidence.size)
            for(r in evidence)bound(r)
            val chain=entry.arrayAt("replacementChain").map { it.jsonObject }
            var previous:String?=null;val chainIds=mutableSetOf<String>()
            for(edge in chain) {
                keys(edge,setOf("fromOriginalRunId","toOriginalRunId","reasonCode","reason","retiredCaptureIdentity","reasonEvidenceIdentity"))
                val from=edge.text("fromOriginalRunId");val to=edge.text("toOriginalRunId");uuid(from);uuid(to)
                if(previous==null)chainIds+=from else require(from==previous)
                require(chainIds.add(to) && from !in activeIds && retiredIds.add(from))
                require(edge.text("reasonCode") in setOf("missing-required-evidence","source-no-longer-applicable") && edge.text("reason").isNotBlank())
                historical(edge.objectAt("retiredCaptureIdentity"));historical(edge.objectAt("reasonEvidenceIdentity"))
                previous=to
            }
            require(chain.isEmpty() || previous==entry.text("originalRunId"))
            require(entry.required("absoluteUtc")==JsonNull && entry.text("absoluteUtcStatus")=="not-recorded")
            val clocks=entry.arrayAt("captureClockEvidence").map { it.jsonObject }
            require(clocks.isNotEmpty() && clocks.distinct().size==clocks.size)
            for(clock in clocks) {
                keys(clock,setOf("raw","family","sequence","kind","phase","domain","field"))
                require(clock.required("raw")==raw.required("stdout") && clock.integer("sequence")>=0 && clock.text("kind").isNotBlank())
                require(clock.required("phase")==JsonNull || clock.integer("phase")>=0)
                val expected=when(clock.text("domain")) {
                    "r12-elapsed-realtime"->"r12Record" to setOf("/elapsedRealtimeNanos")
                    "r12-observed-record-utc"->"r12Record" to setOf("/utc")
                    "r21-emission-nanotime"->"r21Record" to setOf("/emittedNanoTime")
                    "native-trace-monotonic"->{
                        require(clock.text("kind")=="trace-metadata" && clock.required("phase")!=JsonNull)
                        "r21Record" to setOf("/payload/firstEntryNanos","/payload/lastExitNanos")
                    }
                    "scene-effective-I-q"->{
                        require(clock.text("kind")=="phase-summary" && clock.required("phase")!=JsonNull)
                        "r21Record" to setOf("/payload/sealedCumulativeQ","/payload/qThresholdObserved/completedQ")
                    }
                    else->error("Unsupported clock pointer domain")
                }
                require(clock.text("family")==expected.first && clock.text("field") in expected.second)
            }
            for(group in clocks.groupBy { it.text("family") to it.integer("sequence") }.values)
                require(group.map { it.required("kind") to it.required("phase") }.distinct().size==1)
        }
        for(r in consumed.toList())bound(r)
        return Parsed(document,entries,consumed.distinct())
    }
}
