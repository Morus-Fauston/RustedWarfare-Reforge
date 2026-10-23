package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/** Literal protocol controls in system temp; no real approval or runtime. */
class MotionV2SourceReceiptTest {
    private fun ref(p:Path)=obj("path" to s(p.toAbsolutePath().toString()),"bytes" to n(Files.size(p)),"sha256" to s(sha256(p)))
    private data class Fixture(val receipt:JsonObject,val plan:MotionV2Campaign.Plan)
    private fun alter(f:Fixture,values:Map<String,JsonElement>):Fixture {
        val p=Path.of(f.receipt.text("path"));Files.writeString(p,JsonObject(OriginalMotionComparison.read(p)+values).toString())
        return f.copy(receipt=ref(p))
    }
    private fun load(f:Fixture)=MotionV2SourceReceipt.load(f.receipt,f.plan,"OM08",2,"original")
    private fun fixture():Fixture {
        val temp=Files.createTempDirectory("eng017-synthetic-receipt-")
        fun file(name:String,text:String="synthetic only")=temp.resolve(name).also { Files.writeString(it,text) }
        val standard=ref(file("standard.txt"));val campaign=ref(file("campaign.txt"));val source=ref(file("source.txt"))
        val sources=obj("nativeValidator" to source,"python" to source,"comparisonRuntime" to source,"coreRuntime" to source,
            "coreSources" to source,"coreBuildReceipt" to source,"nonAuthorReview" to source)
        val run=obj("caseId" to s("OM08"),"repeat" to n(2),"originalRunId" to s("00000000-0000-4000-8000-000000000001"),"coreRunId" to s("00000000-0000-4000-8000-000000000002"))
        val planDoc=obj("schemaVersion" to n(3),"createdUtc" to s("2026-10-22T00:00:00Z"),"standard" to standard)
        val plan=MotionV2Campaign.Plan(planDoc,campaign,listOf(run),sources,listOf(campaign,standard,source))
        val binding=obj("campaign" to campaign,"standard" to standard,"caseId" to s("OM08"),"repeat" to n(2),"role" to s("original"),"runId" to run.required("originalRunId"),
            "hostAttemptId" to s("00000000-0000-4000-8000-000000000003"))
        val request=JsonObject(binding+mapOf("kind" to s("motion-v2-runtime-request"),"schemaVersion" to n(3),"sources" to sources))
        val requestRef=ref(file("request.json",request.toString()))
        val receipt=JsonObject(binding+mapOf("kind" to s("motion-v2-runtime-receipt"),"schemaVersion" to n(3),
            "hostStartUtc" to s("2026-10-22T00:00:01Z"),"hostFinishUtc" to s("2026-10-22T00:00:02Z"),
            "request" to requestRef,"raw" to ref(file("raw.txt","SYNTHETIC RAW, not qualified")),"status" to s("completed"),"error" to JsonNull))
        return Fixture(ref(file("receipt.json",receipt.toString())),plan)
    }
    @Test fun predeclared_receipt_loads_actual_bound_files_without_runtime_or_raw_qualification() {
        val f=fixture();val parsed=MotionV2SourceReceipt.load(f.receipt,f.plan,"OM08",2,"original")
        assertEquals("completed",parsed.document.text("status"))
        assertEquals("SYNTHETIC RAW, not qualified",Files.readString(assertNotNull(parsed.raw)))
        assertEquals("original",parsed.request.text("role"))
    }
    @Test fun request_is_bound_before_any_runtime_receipt_without_granting_execution() {
        val f=fixture();val receiptPath=Path.of(f.receipt.text("path"));val receipt=OriginalMotionComparison.read(receiptPath)
        val requestRef=receipt.objectAt("request");val expected=OriginalMotionComparison.read(Path.of(requestRef.text("path")))
        Files.writeString(receiptPath,"SYNTHETIC post-run receipt does not exist yet")
        val parsed=MotionV2SourceReceipt.loadRequest(requestRef,f.plan,"OM08",2,"original")
        assertEquals(requestRef,parsed.identity);assertEquals(expected,parsed.document)
        assertFalse(f.receipt in parsed.identities)
        assertTrue(requestRef in parsed.identities)
    }
    private fun requestRef(f:Fixture)=OriginalMotionComparison.read(Path.of(f.receipt.text("path"))).objectAt("request")
    private fun request(f:Fixture,change:(JsonObject)->JsonObject):JsonObject {
        val path=Path.of(requestRef(f).text("path"));Files.writeString(path,change(OriginalMotionComparison.read(path)).toString())
        return ref(path)
    }
    @Test fun standalone_request_checks_exact_shape_caller_and_planned_binding_before_execution() {
        val f=fixture()
        for((caseId,repeat,role)in listOf(Triple("OM07",2,"original"),Triple("OM08",1,"original"),
            Triple("OM08",2,"core"),Triple("OM08",2,"other"),Triple("OM08",0,"original")))
            assertFails { MotionV2SourceReceipt.loadRequest(requestRef(f),f.plan,caseId,repeat,role) }
        val changes=listOf<(JsonObject)->JsonObject>(
            { JsonObject(it+("schemaVersion" to n(2))) }, { JsonObject(it+("qualified" to b(true))) },
            { JsonObject(it-"hostAttemptId") }, { JsonObject(it+("hostAttemptId" to s("bad"))) },
            { JsonObject(it+("hostAttemptId" to it.required("runId"))) },
            { JsonObject(it+("hostAttemptId" to s("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"))) },
            { JsonObject(it+("runId" to s("00000000-0000-4000-8000-000000000002"))) },
            { JsonObject(it+("campaign" to it.required("standard"))) },
            { JsonObject(it+("standard" to it.required("campaign"))) },
            { JsonObject(it+("sources" to obj())) }, { JsonObject(it+("repeat" to b(true))) })
        for(change in changes) {
            val fresh=fixture();val reference=request(fresh,change)
            assertFails { MotionV2SourceReceipt.loadRequest(reference,fresh.plan,"OM08",2,"original") }
        }
        // A different canonical attempt is valid input here. Its freshness is the ledger's responsibility.
        val another=fixture();val reference=request(another) { JsonObject(it+("hostAttemptId" to s("00000000-0000-4000-8000-000000000004"))) }
        assertEquals("00000000-0000-4000-8000-000000000004",
            MotionV2SourceReceipt.loadRequest(reference,another.plan,"OM08",2,"original").document.text("hostAttemptId"))
    }
    @Test fun standalone_request_rechecks_full_reference_and_already_loaded_plan_inputs() {
        val f=fixture();val reference=requestRef(f)
        for(change in listOf(mapOf("bytes" to n(reference.integer("bytes")+1)),mapOf("sha256" to s("0".repeat(64))),
            mapOf("extra" to b(true))))
            assertFails { MotionV2SourceReceipt.loadRequest(JsonObject(reference+change),f.plan,"OM08",2,"original") }
        val stale=fixture();val source=Path.of(stale.plan.sources.objectAt("coreSources").text("path"))
        Files.writeString(source,"changed after plan load, before host startup")
        assertFails { MotionV2SourceReceipt.loadRequest(requestRef(stale),stale.plan,"OM08",2,"original") }
    }
    @Test fun caller_expected_slot_and_side_cannot_be_replaced_by_another_receipt() {
        val f=fixture()
        for((caseId,repeat,role)in listOf(Triple("OM07",2,"original"),Triple("OM08",1,"original"),Triple("OM08",2,"core"),Triple("OM08",2,"other")))
            assertFails { MotionV2SourceReceipt.load(f.receipt,f.plan,caseId,repeat,role) }
    }
    @Test fun failure_retains_partial_raw_or_no_raw_but_completion_cannot_hide_error_or_missing_raw() {
        val failed=mapOf("status" to s("failed"),"error" to s("process exited before first frame"),"raw" to JsonNull)
        assertNull(load(alter(fixture(),failed)).raw)
        val partial=load(alter(fixture(),failed-"raw"));assertNotNull(partial.raw)
        for(bad in listOf(mapOf("raw" to JsonNull),mapOf("error" to s("failed but called completed")),
            mapOf("status" to s("unknown")),mapOf("status" to s("failed"),"error" to s(" ")),
            mapOf("status" to s("failed"),"error" to b(true)),mapOf("status" to s("failed"),"error" to obj())))
            assertFails { load(alter(fixture(),bad)) }
    }
    @Test fun host_execution_cannot_precede_plan_or_finish_before_start_or_use_another_time_domain() {
        for(bad in listOf(mapOf("hostStartUtc" to s("2026-10-21T23:59:59Z")),mapOf("hostFinishUtc" to s("2026-10-22T00:00:00Z")),
            mapOf("hostStartUtc" to s("2026-10-22T08:00:01+08:00")),mapOf("hostStartUtc" to n(1792627201))))
            assertFails { load(alter(fixture(),bad)) }
    }
    @Test fun unknown_receipt_or_request_semantics_cannot_claim_qualification() {
        assertFails { load(alter(fixture(),mapOf("formalCompatibility" to b(true)))) }
        val f=fixture();val receiptPath=Path.of(f.receipt.text("path"));val r=OriginalMotionComparison.read(receiptPath)
        val requestPath=Path.of(r.objectAt("request").text("path"))
        Files.writeString(requestPath,JsonObject(OriginalMotionComparison.read(requestPath)+("qualified" to b(true))).toString())
        assertFails { load(alter(f,mapOf("request" to ref(requestPath)))) }
    }
    @Test fun a_previously_loaded_campaign_cannot_keep_using_changed_source_files() {
        val f=fixture();val sourcePath=Path.of(f.plan.sources.objectAt("coreSources").text("path"))
        Files.writeString(sourcePath,"changed after campaign was loaded")
        assertFails { load(f) }
    }

    @Test fun schema_three_preserves_original_run_and_binds_a_distinct_canonical_host_attempt() {
        val f=fixture();val parsed=load(f)
        assertEquals("00000000-0000-4000-8000-000000000001",parsed.document.text("runId"))
        assertEquals("00000000-0000-4000-8000-000000000003",parsed.document.text("hostAttemptId"))
        for(value in listOf(s("bad"),s("00000000-0000-4000-8000-000000000001"),s("00000000-0000-4000-8000-000000000004"),JsonNull))
            assertFails { load(alter(fixture(),mapOf("hostAttemptId" to value))) }
        assertFails { load(alter(fixture(),mapOf("schemaVersion" to n(2)))) }
        val missing=fixture();val path=Path.of(missing.receipt.text("path"));val old=OriginalMotionComparison.read(path)
        Files.writeString(path,JsonObject(old-"hostAttemptId").toString());assertFails { load(missing.copy(receipt=ref(path))) }
    }
}
