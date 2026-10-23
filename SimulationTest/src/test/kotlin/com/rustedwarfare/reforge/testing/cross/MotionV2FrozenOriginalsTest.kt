package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.json.*

/** Literal format controls only. These temp records are never real capture or approval. */
class MotionV2FrozenOriginalsTest {
    private fun ref(p:Path)=obj("path" to s(p.toAbsolutePath().toString()),"bytes" to n(Files.size(p)),"sha256" to s(sha256(p)))
    internal data class Fixture(val manifest:JsonObject,val slots:List<JsonObject>)
    private val cases=listOf("OM00","OM01E","OM01W","OM02S","OM02N","OM03SE","OM03NW","OM04","OM05","OM06","OM07","OM08")
    internal fun fixture():Fixture {
        val root=Files.createTempDirectory("eng017-synthetic-frozen-originals-")
        fun file(name:String,text:String):JsonObject { val p=root.resolve(name);Files.writeString(p,text);return ref(p) }
        val slots=cases.flatMap { id->(1..3).map { repeat->id to repeat } }.mapIndexed { i,(id,repeat)->
            obj("caseId" to s(id),"repeat" to n(repeat),"originalRunId" to s(UUID(0,i+1L).toString()),"coreRunId" to s(UUID(0,i+101L).toString()))
        }
        val entries=slots.mapIndexed { i,slot->
            val request=obj("schemaVersion" to s("1"),"profile" to s("r22.O.v1.${slot.text("caseId")}"),
                "requestId" to s("s07-${slot.text("caseId")}-${slot.integer("repeat")}"),"runId" to slot.required("originalRunId"),
                "targetApkSha256" to s("a".repeat(64)),"testApkSha256" to s("b".repeat(64)),"signerSha256" to s("c".repeat(64)))
            val raw=obj("stdout" to file("$i.stdout","SYNTHETIC not parsed as a real stream"),"stderr" to file("$i.stderr",""))
            val capture=file("$i.capture.json",obj("kind" to s("synthetic-capture"),"caseId" to slot.required("caseId"),
                "repeat" to n(3),"request" to request,"formalEligible" to b(false),
                "commands" to array(listOf(JsonObject(raw+mapOf("label" to s("instrument"),"argv" to array(listOf(s("SYNTHETIC NEVER EXECUTED")))))))).toString())
            val evidence=file("$i.evidence.txt","SYNTHETIC evidence identity, not a PASS")
            val technical=file("$i.technical.json",obj("kind" to s("motion-v2-original-technical-qualification"),"schemaVersion" to n(1),
                "scope" to s("technical-eligibility-only"),"caseId" to slot.required("caseId"),"originalRunId" to slot.required("originalRunId"),
                "capture" to capture,"rawRefs" to raw,"evidenceRefs" to array(listOf(evidence))).toString())
            JsonObject((slot-"coreRunId")+mapOf("originalRequest" to request,"capture" to capture,"rawRefs" to raw,
                "technicalQualification" to technical,"replacementChain" to array(emptyList()),
                "captureClockEvidence" to array(listOf(obj("raw" to raw.required("stdout"),"family" to s("r12Record"),"sequence" to n(0),
                    "kind" to s("created"),"phase" to JsonNull,"domain" to s("r12-elapsed-realtime"),"field" to s("/elapsedRealtimeNanos")))),
                "absoluteUtc" to JsonNull,"absoluteUtcStatus" to s("not-recorded")))
        }
        return Fixture(file("frozen.json",obj("kind" to s("motion-v2-frozen-originals"),"schemaVersion" to n(1),"entries" to array(entries)).toString()),slots)
    }
    private fun load(f:Fixture)=MotionV2FrozenOriginals.load(f.manifest,f.slots)
    private fun first(f:Fixture,change:(JsonObject)->JsonObject):Fixture {
        val path=Path.of(f.manifest.text("path"));val document=OriginalMotionComparison.read(path)
        val entries=document.arrayAt("entries").mapIndexed { i,e->if(i==0)change(e.jsonObject)else e }
        Files.writeString(path,JsonObject(document+("entries" to array(entries))).toString())
        return f.copy(manifest=ref(path))
    }
    private fun edge(f:Fixture,from:String=UUID(0,901).toString(),to:String=f.slots.first().text("originalRunId")):JsonObject {
        val directory=Path.of(f.manifest.text("path")).parent
        val retired=obj("recordedPath" to s(directory.resolve("missing-retired-capture").toString()),"bytes" to n(99),"sha256" to s("d".repeat(64)))
        return obj("fromOriginalRunId" to s(from),"toOriginalRunId" to s(to),"reasonCode" to s("missing-required-evidence"),
            "reason" to s("required native fields are absent"),"retiredCaptureIdentity" to retired,"reasonEvidenceIdentity" to retired)
    }
    private fun technical(f:Fixture,change:(JsonObject)->JsonObject):Fixture=first(f) { entry->
        val path=Path.of(entry.objectAt("technicalQualification").text("path"))
        Files.writeString(path,change(OriginalMotionComparison.read(path)).toString())
        JsonObject(entry+("technicalQualification" to ref(path)))
    }
    private fun capture(f:Fixture,change:(JsonObject)->JsonObject):Fixture=first(f) { entry->
        val path=Path.of(entry.objectAt("capture").text("path"))
        Files.writeString(path,change(OriginalMotionComparison.read(path)).toString())
        val updated=ref(path);val technical=Path.of(entry.objectAt("technicalQualification").text("path"))
        Files.writeString(technical,JsonObject(OriginalMotionComparison.read(technical)+("capture" to updated)).toString())
        JsonObject(entry+mapOf("capture" to updated,"technicalQualification" to ref(technical)))
    }
    @Test fun full36_identity_load_preserves_embedded_old_request_and_historical_repeat_without_qualifying_streams() {
        val f=fixture();val parsed=load(f)
        assertEquals(36,parsed.entries.size)
        val first=parsed.entries.first()
        assertEquals(1L,first.integer("repeat"))
        assertEquals("s07-OM00-1",first.objectAt("originalRequest").text("requestId"))
        val capture=OriginalMotionComparison.read(Path.of(first.objectAt("capture").text("path")))
        assertEquals(3L,capture.integer("repeat"))
        assertEquals(b(false),capture.required("formalEligible"))
        assertEquals("SYNTHETIC not parsed as a real stream",Files.readString(Path.of(first.objectAt("rawRefs").objectAt("stdout").text("path"))))
    }
    @Test fun replacement_history_keeps_unreadable_retired_paths_while_selected_dependencies_are_still_required() {
        var f=fixture();val directory=Path.of(f.manifest.text("path")).parent
        val retired=obj("recordedPath" to s(directory.resolve("retired-capture-no-longer-readable").toString()),"bytes" to n(99),"sha256" to s("d".repeat(64)))
        val reason=JsonObject(retired+("recordedPath" to s(directory.resolve("retired-reason-no-longer-readable").toString())))
        fun edge(from:String,to:String)=obj("fromOriginalRunId" to s(from),"toOriginalRunId" to s(to),
            "reasonCode" to s("missing-required-evidence"),"reason" to s("retired run lacks the required native terrain fields"),
            "retiredCaptureIdentity" to retired,"reasonEvidenceIdentity" to reason)
        val old=UUID(0,901).toString();val middle=UUID(0,902).toString()
        f=first(f) { JsonObject(it+("replacementChain" to array(listOf(edge(old,middle),edge(middle,it.text("originalRunId")))))) }
        val parsed=load(f);assertEquals(2,parsed.entries.first().arrayAt("replacementChain").size)
        assertFalse(Files.exists(Path.of(retired.text("recordedPath"))))
        val active=Path.of(parsed.entries.first().objectAt("rawRefs").objectAt("stdout").text("path"))
        Files.writeString(active,"active raw changed after the manifest was sealed")
        assertFails { load(f) }
    }
    @Test fun broken_cyclic_wrong_endpoint_or_unexplained_replacements_cannot_select_a_frozen_run() {
        val changes=listOf<(Fixture)->List<JsonObject>>(
            { f->listOf(edge(f,to=UUID(0,902).toString())) },
            { f->listOf(edge(f,from=f.slots[1].text("originalRunId"))) },
            { f->listOf(edge(f,from="bad")) },
            { f->listOf(edge(f,to=UUID(0,901).toString())) },
            { f->listOf(JsonObject(edge(f)+("reasonCode" to s("core comparison looked better")))) },
            { f->listOf(JsonObject(edge(f)+("reason" to s(" ")))) },
            { f->listOf(JsonObject(edge(f)+("retiredCaptureIdentity" to obj("path" to s("fake"),"bytes" to n(99),"sha256" to s("d".repeat(64)))))) },
            { f->listOf(edge(f,to=UUID(0,902).toString()),edge(f,from=UUID(0,903).toString())) },
            { f->listOf(edge(f,to=UUID(0,902).toString()),edge(f,from=UUID(0,902).toString(),to=UUID(0,901).toString())) }
        )
        for(change in changes) {
            val f=fixture();assertFails { load(first(f) { JsonObject(it+("replacementChain" to array(change(f)))) }) }
        }
    }
    @Test fun caller_cannot_shrink_or_duplicate_slots_and_manifest_must_cover_all36_exactly() {
        val f=fixture()
        assertFails { load(f.copy(slots=f.slots.dropLast(1))) }
        assertFails { load(f.copy(slots=f.slots.dropLast(1)+f.slots.first())) }
        assertFails { load(f.copy(slots=f.slots.mapIndexed { i,row->if(i==1)JsonObject(row+("originalRunId" to f.slots.first().required("originalRunId")))else row })) }
        for(change in listOf<(JsonArray)->JsonArray>({JsonArray(it.dropLast(1))},{JsonArray(it+it.first())},{JsonArray(it.reversed())})) {
            val fresh=fixture();val path=Path.of(fresh.manifest.text("path"));val document=OriginalMotionComparison.read(path)
            Files.writeString(path,JsonObject(document+("entries" to change(document.arrayAt("entries")))).toString())
            assertFails { load(fresh.copy(manifest=ref(path))) }
        }
        assertFails { load(first(fixture()) { JsonObject(it+("extraApproval" to b(true))) }) }
    }
    @Test fun old_request_values_and_unique_instrument_binding_cannot_be_replaced_by_matching_outer_hashes() {
        for(value in listOf<JsonElement>(n(1),s("another old request")))
            assertFails { load(first(fixture()) { e->JsonObject(e+("originalRequest" to JsonObject(e.objectAt("originalRequest")+("requestId" to value)))) }) }
        for(count in listOf(0,2))assertFails { load(capture(fixture()) { c->
            JsonObject(c+("commands" to array(List(count) { c.arrayAt("commands").first() })))
        }) }
        assertFails { load(capture(fixture()) { JsonObject(it+("caseId" to s("OM08"))) }) }
        val f=fixture();val broken=first(f) { e->
            val raw=e.objectAt("rawRefs");val swapped=obj("stdout" to raw.required("stderr"),"stderr" to raw.required("stdout"))
            val path=Path.of(e.objectAt("technicalQualification").text("path"))
            Files.writeString(path,JsonObject(OriginalMotionComparison.read(path)+("rawRefs" to swapped)).toString())
            JsonObject(e+mapOf("rawRefs" to swapped,"technicalQualification" to ref(path)))
        }
        assertFails { load(broken) }
    }
    @Test fun technical_container_requires_matching_run_capture_raw_and_live_nonempty_evidence() {
        for(key in listOf("kind","scope","originalRunId","caseId","capture","rawRefs")) {
            assertFails { load(technical(fixture()) { t->JsonObject(t+(key to when(key) {
                "capture"->t.objectAt("rawRefs").required("stdout")
                "rawRefs"->obj("stdout" to t.objectAt("rawRefs").required("stderr"),"stderr" to t.objectAt("rawRefs").required("stdout"))
                else->s("wrong")
            })) }) }
        }
        for(duplicate in listOf(false,true))assertFails { load(technical(fixture()) { t->
            val refs=t.arrayAt("evidenceRefs");JsonObject(t+("evidenceRefs" to if(duplicate)JsonArray(refs+refs.first())else array(emptyList())))
        }) }
        val f=fixture();val parsed=load(f);val first=parsed.entries.first()
        val technical=OriginalMotionComparison.read(Path.of(first.objectAt("technicalQualification").text("path")))
        Files.writeString(Path.of(technical.arrayAt("evidenceRefs").first().jsonObject.text("path")),"changed evidence")
        assertFails { load(f) }
    }
    @Test fun clock_pointers_and_capture_time_metadata_cannot_claim_host_time_or_other_streams() {
        val changes=listOf<Map<String,JsonElement>>(mapOf("domain" to s("host-utc")),mapOf("field" to s("/mtime")),
            mapOf("family" to s("r21Record")),mapOf("sequence" to n(-1)),mapOf("phase" to n(-1)),mapOf("sequence" to s("0")))
        for(change in changes)assertFails { load(first(fixture()) { e->
            val pointer=e.arrayAt("captureClockEvidence").first().jsonObject
            JsonObject(e+("captureClockEvidence" to array(listOf(JsonObject(pointer+change)))))
        }) }
        assertFails { load(first(fixture()) { e->
            val pointer=e.arrayAt("captureClockEvidence").first().jsonObject
            JsonObject(e+("captureClockEvidence" to array(listOf(JsonObject(pointer+("raw" to e.objectAt("rawRefs").required("stderr")))))))
        }) }
        assertFails { load(first(fixture()) { e->JsonObject(e+("captureClockEvidence" to JsonArray(e.arrayAt("captureClockEvidence")+e.arrayAt("captureClockEvidence").first()))) }) }
        assertFails { load(first(fixture()) { JsonObject(it+("absoluteUtc" to s("2026-10-23T00:00:00Z"))) }) }
        assertFails { load(first(fixture()) { JsonObject(it+("absoluteUtcStatus" to s("derived-from-new-host"))) }) }
    }
    @Test fun clock_domains_keep_real_record_paths_and_allow_multiple_fields_without_conflating_phase_or_kind() {
        val f=fixture();val document=OriginalMotionComparison.read(Path.of(f.manifest.text("path")))
        val elapsed=document.arrayAt("entries").first().jsonObject.arrayAt("captureClockEvidence").first().jsonObject
        fun pointer(seq:Int,kind:String,domain:String,field:String)=JsonObject(elapsed+mapOf("family" to s("r21Record"),"sequence" to n(seq),
            "kind" to s(kind),"phase" to n(0),"domain" to s(domain),"field" to s(field)))
        val pointers=listOf(elapsed,JsonObject(elapsed+mapOf("domain" to s("r12-observed-record-utc"),"field" to s("/utc"))),
            pointer(1,"phase-summary","r21-emission-nanotime","/emittedNanoTime"),
            pointer(1,"phase-summary","scene-effective-I-q","/payload/sealedCumulativeQ"),
            pointer(1,"phase-summary","scene-effective-I-q","/payload/qThresholdObserved/completedQ"),
            pointer(4,"trace-metadata","native-trace-monotonic","/payload/firstEntryNanos"),
            pointer(4,"trace-metadata","native-trace-monotonic","/payload/lastExitNanos"))
        val parsed=load(first(f) { JsonObject(it+("captureClockEvidence" to array(pointers))) })
        assertEquals(7,parsed.entries.first().arrayAt("captureClockEvidence").size)
        for((index,change)in listOf(1 to mapOf("kind" to s("contradictory-kind")),1 to mapOf("phase" to n(1)),
            5 to mapOf("kind" to s("native-trace-metadata")),3 to mapOf("phase" to JsonNull),
            6 to mapOf("field" to s("/firstEntryNanos")))) {
            assertFails { load(first(f) { JsonObject(it+("captureClockEvidence" to array(pointers.mapIndexed { i,p->if(i==index)JsonObject(p+change)else p }))) }) }
        }
    }
}
