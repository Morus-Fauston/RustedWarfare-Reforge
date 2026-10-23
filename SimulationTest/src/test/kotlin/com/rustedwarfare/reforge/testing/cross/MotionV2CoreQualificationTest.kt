package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Self-owned literal qualification inputs. No original-source, approved-standard or JAR-runtime claim. */
class MotionV2CoreQualificationTest {
    @TempDir lateinit var directory:Path
    private fun ref(p:Path)=MotionV2CoreInputs.reference(p)
    private data class Fixture(val context:Path,val input:MotionV2CoreInputsTest.Fixture)
    private fun fixture(actor:String="p1"):Fixture {
        val fixtures=MotionV2CoreInputsTest().apply { directory=Files.createDirectory(this@MotionV2CoreQualificationTest.directory.resolve(UUID.randomUUID().toString())) }
        val input=fixtures.fixture()
        Files.writeString(input.tsx,Files.readString(input.tsx).replace("<properties><property name=\"trees\" value=\"\"/></properties>",""))
        fixtures.refresh(input)
        val run=readObject(input.request).required("runId")
        fun command(sequence:Int,q:Double,action:String,target:JsonElement)=obj("actorPlayerId" to s(if(sequence==1)actor else"p1"),"unitId" to s("u1"),
            "original" to obj("sequence" to n(sequence),"q" to n(q),"action" to s(action),"target" to target))
        val context=input.request.parent.resolve("execution-context.json")
        Files.writeString(context,obj("kind" to s("motion-v2-core-qualification-context"),"schemaVersion" to n(1),
            "purpose" to s("execution-qualification-only"),"formalCompatibility" to b(false),"caseId" to s("OM00"),"runId" to run,
            "inputRequest" to ref(input.request),"inputManifest" to ref(input.manifest),"start" to obj("x" to n(10),"y" to n(10)),
            "heading" to n(90),"horizon" to n(20),"inputs" to array(listOf(command(0,0.0,"move",obj("x" to n(50),"y" to n(10))),
                command(1,3.2,"stop",JsonNull)))).toString())
        return Fixture(context,input)
    }
    private fun capture(f:Fixture,output:Path,observer:((String)->Unit)?=null)=MotionV2CoreQualificationWriter.capture(
        MotionV2CoreQualification.load(ref(f.context)),output,runtimeGuard={},qualificationObserver=observer)
    private fun failure(output:Path)=readObject(output.resolveSibling(output.fileName.toString()+".failure.json"))
    @Test fun new_protocol_records_real_execution_and_ceiled_input_time_without_formal_authority() {
        val f=fixture();val output=f.context.parent.resolve("raw.json");val result=capture(f,output)
        assertEquals(ref(output),result.reference);assertEquals(readObject(output),result.document)
        val body=result.document
        assertEquals("motion-v2-core-execution-qualification",body.text("kind"));assertEquals(b(false),body.required("formalCompatibility"))
        assertEquals(b(true),body.required("complete"));assertEquals(b(false),body.required("qualificationObserverUsed"))
        assertEquals(ref(f.context),body.required("context"));assertTrue(body.arrayAt("runtimeObservations").isNotEmpty())
        val trace=body.objectAt("trace");assertEquals("accepted",trace.objectAt("initialization").text("status"))
        val operations=trace.arrayAt("operations").map { it.jsonObject };assertEquals(22,operations.size)
        assertEquals(4L,operations.single { it.text("kind")=="submit" && it.objectAt("scheduled").integer("sequence")==1L }.integer("q"))
        val advances=operations.filter { it.text("kind")=="advance" };assertEquals(20,advances.size)
        assertEquals(20.0,advances.last().objectAt("after").number("simulationTime"))
        assertEquals(20,advances.sumOf { it.arrayAt("motion").size })
        assertEquals("Closed",trace.text("closeResult"));assertEquals(JsonNull,trace.required("firstError"))
        assertFalse(Files.exists(output.resolveSibling("raw.json.failure.json")))
    }
    @Test fun real_rejected_submit_stays_in_new_raw_and_is_not_turned_into_a_capture_failure() {
        val f=fixture("unknown");val body=capture(f,f.context.parent.resolve("rejected.json")).document
        val rejected=body.objectAt("trace").arrayAt("operations").map { it.jsonObject }.single {
            it.text("kind")=="submit" && it.objectAt("scheduled").integer("sequence")==1L }
        assertEquals("unknown",rejected.objectAt("issuedAction").text("actorPlayerId"))
        assertEquals("rejected",rejected.objectAt("result").text("status"));assertEquals(b(true),body.required("complete"))
        assertEquals(20,body.objectAt("trace").arrayAt("operations").count { it.jsonObject.text("kind")=="advance" })
    }
    @Test fun context_rejects_formal_flags_wrong_bindings_and_changed_full_refs_before_execution() {
        val changes=listOf(mapOf("kind" to s("motion-v2-runtime-request")),mapOf("formalCompatibility" to b(true)),
            mapOf("approved" to b(true)),mapOf("runId" to s(UUID.randomUUID().toString())),mapOf("caseId" to s("OM08")),
            mapOf("purpose" to s("final-validation")))
        for(change in changes) {
            val f=fixture();Files.writeString(f.context,JsonObject(readObject(f.context)+change).toString())
            assertFails { MotionV2CoreQualification.load(ref(f.context)) }
        }
        val f=fixture();assertFails { MotionV2CoreQualification.load(JsonObject(ref(f.context)+("bytes" to n(Files.size(f.context)+1)))) }
    }
    @Test fun finite_and_integer_boundaries_are_checked_before_quantization_or_simulation() {
        val changes=listOf<(JsonObject)->JsonObject>(
            { JsonObject(it+("heading" to s("NaN"))) }, { JsonObject(it+("horizon" to n(20.2))) },
            { JsonObject(it+("horizon" to n(605))) }, { JsonObject(it+("start" to obj("x" to s("Infinity"),"y" to n(10)))) },
            { d->JsonObject(d+("inputs" to array(d.arrayAt("inputs").mapIndexed { i,row->
                if(i!=0)row else JsonObject(row.jsonObject+("original" to JsonObject(row.jsonObject.objectAt("original")+("q" to n(1e30))))) }))) },
            { d->JsonObject(d+("inputs" to array(d.arrayAt("inputs").mapIndexed { i,row->
                if(i!=0)row else JsonObject(row.jsonObject+("original" to JsonObject(row.jsonObject.objectAt("original")+("q" to n(-1))))) }))) })
        for(change in changes) {
            val f=fixture();Files.writeString(f.context,change(readObject(f.context)).toString())
            assertFails { MotionV2CoreQualification.load(ref(f.context)) }
        }
    }
    @Test fun actual_content_change_after_load_is_rejected_without_initialization_and_retained_in_failure() {
        val f=fixture();val context=MotionV2CoreQualification.load(ref(f.context));val output=f.context.parent.resolve("stale.json")
        Files.writeString(f.input.unit,Files.readString(f.input.unit).replace("moveSpeed: 1.1","moveSpeed: 1.2"))
        assertFails { MotionV2CoreQualificationWriter.capture(context,output,runtimeGuard={}) }
        assertFalse(Files.exists(output));val failed=failure(output)
        assertEquals(b(false),failed.required("rawWritten"));assertEquals(JsonNull,failed.required("raw"))
        assertEquals(JsonNull,failed.required("partialRaw"));assertEquals(JsonNull,failed.required("trace"))
        assertNotEquals(JsonNull,failed.required("start"))
    }
    @Test fun execution_source_failure_and_raw_collision_keep_the_first_error_partial_and_existing_bytes() {
        val f=fixture();val output=f.context.parent.resolve("collision.json")
        val error=assertFails { capture(f,output) { stage->if(stage=="advance:3") {
            Files.writeString(output,"existing bytes must survive")
            Files.writeString(f.input.unit,Files.readString(f.input.unit).replace("moveSpeed: 1.1","moveSpeed: 1.2"))
        } } }
        assertContains(error.message.orEmpty(),"changed")
        assertEquals("existing bytes must survive",Files.readString(output))
        val failed=failure(output);assertEquals(b(false),failed.required("rawWritten"));assertEquals(JsonNull,failed.required("raw"))
        assertEquals(b(true),failed.required("qualificationObserverUsed"))
        val trace=failed.objectAt("partialRaw").objectAt("trace")
        assertEquals("advance:3",trace.objectAt("firstError").text("stage"))
        val ops=trace.arrayAt("operations").map { it.jsonObject }.filter { it.text("kind")=="advance" }
        assertEquals(3,ops.size);assertEquals(2,ops.count { it.required("attempted")==b(true) })
        assertEquals(JsonNull,ops.last().required("after"));assertEquals("Closed",trace.text("closeResult"))
        assertTrue(error.suppressed.any { it is java.nio.file.FileAlreadyExistsException })
    }
    @Test fun completed_capture_collision_retains_full_trace_and_never_overwrites_the_existing_output() {
        val f=fixture();val output=f.context.parent.resolve("late-collision.json")
        assertFails { capture(f,output) { stage->if(stage=="advance:3")Files.writeString(output,"sentinel") } }
        assertEquals("sentinel",Files.readString(output))
        val failed=failure(output);assertEquals(b(false),failed.required("rawWritten"))
        val raw=failed.objectAt("partialRaw");assertEquals(b(true),raw.required("complete"))
        assertEquals(20,raw.objectAt("trace").arrayAt("operations").count { it.jsonObject.text("kind")=="advance" })
    }
    @Test fun postwrite_source_failure_keeps_the_written_raw_but_binds_it_to_an_unsuccessful_attempt() {
        val f=fixture();val output=f.context.parent.resolve("postwrite.json")
        var written:JsonObject?=null
        assertFails { capture(f,output) { stage->if(stage=="after-write") {
            written=ref(output);Files.writeString(f.input.unit,"actual source changed after writing raw")
        } } }
        assertEquals(written,ref(output))
        val failed=failure(output);assertEquals(b(true),failed.required("rawWritten"));assertEquals<JsonElement>(assertNotNull(written),failed.required("raw"))
        assertEquals(JsonNull,failed.required("partialRaw"));assertTrue(failed.arrayAt("inputPostErrors").isNotEmpty())
        assertEquals(b(true),readObject(output).required("complete")) // Execution finished; the attempt is still failed.
    }
    @Test fun a_noncanonical_output_cannot_create_a_failure_file_through_its_alias_parent() {
        val f=fixture();val alias=f.context.parent.resolve("sub/../bad-output.json")
        Files.createDirectory(f.context.parent.resolve("sub"))
        assertFails { capture(f,alias) }
        assertFalse(Files.exists(f.context.parent.resolve("bad-output.json")))
        assertFalse(Files.exists(f.context.parent.resolve("bad-output.json.start.json")))
        assertFalse(Files.exists(f.context.parent.resolve("bad-output.json.failure.json")))
    }
    @Test fun failure_receipt_io_and_independent_postchecks_cannot_hide_the_primary_source_error() {
        val f=fixture();val context=MotionV2CoreQualification.load(ref(f.context));val output=f.context.parent.resolve("double-io.json")
        val side=output.resolveSibling(output.fileName.toString()+".failure.json");Files.writeString(side,"preserved failure sentinel")
        val checked=mutableListOf<String>()
        val error=assertFails { MotionV2CoreQualificationWriter.capture(context,output,
            runtimeGuard={ stage->checked+=stage;if(stage=="writer-finally")error("independent runtime postcheck") },
            qualificationObserver={ stage->if(stage=="advance:3") {
                Files.writeString(output,"preserved raw sentinel");Files.writeString(f.input.unit,"actual source changed")
            } }) }
        assertContains(error.message.orEmpty(),"changed");assertContains(checked,"writer-finally")
        assertEquals("preserved failure sentinel",Files.readString(side));assertEquals("preserved raw sentinel",Files.readString(output))
        assertTrue(error.suppressed.any { it.message=="independent runtime postcheck" })
        assertTrue(error.suppressed.count { it is java.nio.file.FileAlreadyExistsException }>=2)
    }
}
