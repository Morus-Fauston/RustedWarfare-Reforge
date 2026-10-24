package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.json.*

/** Complete synthetic loader chain only. Never reserves IDs or starts a runtime. */
class MotionV2SourceAdmissionTest {
    // Test-only configurable runtime: clean builds must consume this invocation's JARs.
    private fun runtime(root:Path):List<Path> = System.getProperty("workflow.test.runtime")?.split(java.io.File.pathSeparator)
        ?.map(Path::of) ?: Files.list(root.resolve(".scratch/开发票据/引擎重写/P002-视野知识寻路/evidence/ENG-P002-017/20261021-preflight/post-gh-headless-diagnostics-63a6dfcdf0ac4c30bd10b07d81cfcff7/runtime/lib"))
        .use { paths -> paths.filter { it.fileName.toString().endsWith(".jar") }.sorted().map { p ->
            when(p.fileName.toString()) {
                "simulation-test.jar" -> root.resolve("SimulationTest/build/libs/simulation-test.jar")
                "core.jar" -> root.resolve("SimulationTest/build/core/libs/core.jar")
                else -> p
            }
        }.toList() }
    private fun ref(p:Path)=obj("path" to s(p.toAbsolutePath().toString()),"bytes" to n(Files.size(p)),"sha256" to s(sha256(p)))
    private data class Fixture(val wrapper:Path,val campaign:Path,val request:JsonObject)
    private fun fixture(role:String="original",extraArtifact:JsonObject?=null):Fixture {
        val standard=MotionV2StandardTest().fixture(qualificationChange={ q ->
            if(extraArtifact==null)q else JsonObject(q+("artifacts" to array(q.arrayAt("artifacts")+extraArtifact)))
        });val policy=MotionV2Standard.loadApproved(standard.wrapper,sha256(standard.wrapper))
        val originals=MotionV2FrozenOriginalsTest().fixture();val mechanisms=MotionV2CoreMechanismsTest().fixture()
        val temp=Files.createTempDirectory("eng017-synthetic-admission-")
        val review=temp.resolve("snapshot-review.json")
        Files.writeString(review,JsonObject(mechanisms.core+mapOf("kind" to s("motion-v2-core-snapshot-review"),"schemaVersion" to n(1),
            "status" to s("accepted"),"reviewer" to s("synthetic-reviewer"),"author" to s("synthetic-author"))).toString())
        val tool=policy.qualificationArtifacts.first()
        val sources=JsonObject(mechanisms.core+mapOf("nativeValidator" to tool,"python" to tool,"comparisonRuntime" to tool,"nonAuthorReview" to ref(review)))
        val campaign=temp.resolve("campaign.json")
        Files.writeString(campaign,obj("kind" to s("motion-v2-final-campaign"),"schemaVersion" to n(3),"campaignId" to s(UUID.randomUUID().toString()),
            "createdUtc" to s("2026-10-22T00:00:00Z"),"standard" to policy.wrapperRef,"sources" to sources,"runs" to array(originals.slots),
            "frozenOriginals" to originals.manifest,"coreMotionMechanisms" to mechanisms.manifest).toString())
        val slot=originals.slots.single { it.text("caseId")=="OM08" && it.integer("repeat")==2L }
        val request=temp.resolve("request.json")
        Files.writeString(request,obj("kind" to s("motion-v2-runtime-request"),"schemaVersion" to n(3),"campaign" to ref(campaign),
            "standard" to policy.wrapperRef,"caseId" to s("OM08"),"repeat" to n(2),"role" to s(role),
            "runId" to slot.required(if(role=="original")"originalRunId"else"coreRunId"),"hostAttemptId" to s(UUID.randomUUID().toString()),"sources" to sources).toString())
        return Fixture(standard.wrapper,campaign,ref(request))
    }
    private fun admit(f:Fixture,role:String="original")=MotionV2SourceAdmission.admit(
        f.wrapper,sha256(f.wrapper),f.campaign,sha256(f.campaign),f.request,"OM08",2,role)
    @Test fun original_read_preparation_requires_actual_started_role_and_approved_graph_without_running_a_reader() {
        val root=Path.of(System.getProperty("repo.root"))
        val evidence=System.getProperty("workflow.test.helpers")?.let(Path::of) ?: root.resolve(".scratch/开发票据/引擎重写/P002-视野知识寻路/evidence/ENG-P002-017/20261021-preflight")
        val temp=Files.createTempDirectory("eng017-synthetic-original-preparation-")
        val cli=temp.resolve("original_source_read_cli_v1.py");Files.writeString(cli,"SYNTHETIC IDENTITY ONLY; NEVER EXECUTED")
        val code=ref(cli)
        val graph=temp.resolve("graph.json")
        val names=listOf("python","assessment","deliveryManifest","assessmentReceipt","executionGraph","replayPlan","startupDependencies","terrainTransport")
        val sources=obj(*(names.map { it to code }+listOf("pythonVersion" to s("SYNTHETIC NEVER EXECUTED"),"allowedCode" to array(listOf(code)))).toTypedArray())
        Files.writeString(graph,record("motion-v2-original-read-sources","sources" to sources,"cli" to code).toString())
        val f=fixture("original",ref(graph))
        val jars=runtime(root).map(::ref)
        val input=temp.resolve("control.json")
        Files.writeString(input,obj("wrapper" to ref(f.wrapper),"campaign" to ref(f.campaign),"request" to f.request,"graph" to ref(graph),
            "java" to ref(Path.of(System.getProperty("java.home")).resolve("bin/java.exe")),"jars" to array(jars)).toString())
        val child=ProcessBuilder("python","-B",evidence.resolve("test_original_preparation_v1.py").toString(),input.toString())
            .redirectOutput(temp.resolve("stdout").toFile()).redirectError(temp.resolve("stderr").toFile()).start()
        try { assertTrue(child.waitFor(210,java.util.concurrent.TimeUnit.SECONDS),"finite original preparation controls timed out") }
        finally { if(child.isAlive) {
            val descendants=child.toHandle().descendants().toList()
            for(process in descendants.asReversed())process.destroyForcibly()
            child.destroyForcibly();check(child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS))
        } }
        val stdout=Files.readString(temp.resolve("stdout"));val stderr=Files.readString(temp.resolve("stderr"));println(stdout)
        assertEquals(0,child.exitValue(),stdout+stderr)
    }
    @Test fun actual_jar_admission_to_temp_ledger_preserves_all_attempts_without_starting_a_simulation() {
        val f=fixture("core");val root=Path.of(System.getProperty("repo.root"))
        val evidence=System.getProperty("workflow.test.helpers")?.let(Path::of) ?: root.resolve(".scratch/开发票据/引擎重写/P002-视野知识寻路/evidence/ENG-P002-017/20261021-preflight")
        val jars=runtime(root).map(::ref)
        val input=f.campaign.parent.resolve("bridge-input.json")
        Files.writeString(input,obj("wrapper" to ref(f.wrapper),"campaign" to ref(f.campaign),"request" to f.request,
            "java" to ref(Path.of(System.getProperty("java.home")).resolve("bin/java.exe")),"jars" to array(jars),
            "protectedSources" to array(listOf(ref(root.resolve("SimulationTest/src/main/kotlin/com/rustedwarfare/reforge/testing/cross/MotionV2SourceLaunch.kt"))))).toString())
        val child=ProcessBuilder("python","-B",evidence.resolve("test_source_launch_reserve_v1.py").toString(),input.toString())
            .redirectOutput(f.campaign.parent.resolve("bridge.stdout").toFile()).redirectError(f.campaign.parent.resolve("bridge.stderr").toFile()).start()
        try { assertTrue(child.waitFor(180,java.util.concurrent.TimeUnit.SECONDS),"finite loader/Temp-ledger control timed out") }
        finally {
            if(child.isAlive) {
                val descendants=child.toHandle().descendants().toList()
                for(process in descendants.asReversed())process.destroyForcibly()
                child.destroyForcibly()
                check(child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)) { "Owned bridge process did not terminate" }
            }
        }
        val stdout=Files.readString(f.campaign.parent.resolve("bridge.stdout"));val stderr=Files.readString(f.campaign.parent.resolve("bridge.stderr"))
        println(stdout);assertEquals(0,child.exitValue(),stdout+stderr)
    }
    @Test fun reservation_export_contains_the_actual_admitted_request_for_both_roles_without_starting_a_host() {
        for(role in listOf("original","core")) {
            val f=fixture(role);val output=f.campaign.parent.resolve("admission-export.json")
            val exported=MotionV2SourceLaunch.export(f.wrapper,sha256(f.wrapper),f.campaign,sha256(f.campaign),f.request,"OM08",2,role,output)
            val expected=admit(f,role);val d=OriginalMotionComparison.read(output)
            assertEquals("motion-v2-source-admission-export",d.text("kind"));assertEquals(1L,d.integer("schemaVersion"))
            assertEquals(expected.request.document,d.objectAt("request"));assertEquals(f.request,d.required("requestRef"))
            assertEquals(expected.slot,d.objectAt("slot"));assertEquals(expected.originalEntry,d.objectAt("originalEntry"))
            assertEquals(expected.identities.toSet(),d.arrayAt("identities").toSet())
            assertEquals(ref(output),exported.reference);assertEquals(d,exported.document);exported.verifyStable()
        }
    }
    @Test fun both_roles_load_the_same_predeclared_slot_and_original_entry_without_runtime_authority() {
        for(role in listOf("original","core")) {
            val f=fixture(role);val result=admit(f,role)
            assertEquals("OM08",result.slot.text("caseId"));assertEquals(2L,result.slot.integer("repeat"))
            assertEquals(result.slot.required("originalRunId"),result.originalEntry.required("originalRunId"))
            assertEquals(role,result.request.document.text("role"));assertEquals(f.request,result.request.identity)
            val oldCapture=OriginalMotionComparison.read(Path.of(result.originalEntry.objectAt("capture").text("path")))
            assertEquals(3L,oldCapture.integer("repeat"));assertEquals(2L,result.originalEntry.integer("repeat"))
            result.verifyStable()
        }
    }
    @Test fun export_refuses_collision_and_alias_paths_and_checks_export_and_admitted_drift_independently() {
        val f=fixture();val output=f.campaign.parent.resolve("admission-export.json")
        fun emit(path:Path)=MotionV2SourceLaunch.export(f.wrapper,sha256(f.wrapper),f.campaign,sha256(f.campaign),f.request,"OM08",2,"original",path)
        Files.writeString(output,"preserved collision")
        assertFails { emit(output) };assertEquals("preserved collision",Files.readString(output))
        Files.createDirectory(f.campaign.parent.resolve("sub"))
        assertFails { emit(f.campaign.parent.resolve("sub/../alias.json")) }
        assertFalse(Files.exists(f.campaign.parent.resolve("alias.json")))
        assertFalse(Files.exists(f.campaign.parent.resolve("alias.json.failure.json")))
        val exported=emit(f.campaign.parent.resolve("new-export.json"));exported.verifyStable()
        Files.writeString(Path.of(exported.reference.text("path")),"actual export changed")
        val frozen=exported.document.objectAt("originalEntry").objectAt("rawRefs").objectAt("stdout")
        Files.writeString(Path.of(frozen.text("path")),"actual admitted dependency changed")
        val error=assertFails { exported.verifyStable() }
        assertEquals("Admission export changed",error.message);assertEquals(1,error.suppressed.size)
    }
    @Test fun coherent_chain_referencing_the_actual_pending_standard_is_rejected_at_the_approval_gate() {
        val f=fixture();val root=Path.of(System.getProperty("repo.root"))
        val pendingPath=root.resolve("TestLab/contracts/P002-original-motion/O.pending.v2.json")
        val pending=OriginalMotionComparison.read(pendingPath);val wrapper=OriginalMotionComparison.read(f.wrapper)
        val approval=JsonObject(wrapper.objectAt("approval")+("status" to pending.objectAt("approval").required("status")))
        Files.writeString(f.wrapper,JsonObject(wrapper+mapOf("approval" to approval,"normative" to obj(
            "specification" to pending.required("specification"),"contract" to ref(pendingPath),"scenarios" to pending.required("scenarios")))).toString())
        val campaign=OriginalMotionComparison.read(f.campaign)
        Files.writeString(f.campaign,JsonObject(campaign+("standard" to ref(f.wrapper))).toString())
        val requestPath=Path.of(f.request.text("path"));val request=OriginalMotionComparison.read(requestPath)
        Files.writeString(requestPath,JsonObject(request+mapOf("standard" to ref(f.wrapper),"campaign" to ref(f.campaign))).toString())
        val error=assertFailsWith<IllegalArgumentException> { admit(f.copy(request=ref(requestPath))) }
        assertEquals("Whole O.v2 approval is required",error.message)
        val output=f.campaign.parent.resolve("pending-export.json")
        assertEquals("Whole O.v2 approval is required",assertFailsWith<IllegalArgumentException> {
            MotionV2SourceLaunch.export(f.wrapper,sha256(f.wrapper),f.campaign,sha256(f.campaign),ref(requestPath),"OM08",2,"original",output) }.message)
        assertFalse(Files.exists(output))
    }
    @Test fun explicit_expected_identities_and_caller_slot_cannot_be_replaced_by_current_files() {
        val f=fixture()
        assertEquals("Explicit approved wrapper identity differs",assertFailsWith<IllegalArgumentException> {
            MotionV2SourceAdmission.admit(f.wrapper,"0".repeat(64),f.campaign,sha256(f.campaign),f.request,"OM08",2,"original") }.message)
        assertEquals("Explicit campaign identity differs",assertFailsWith<IllegalArgumentException> {
            MotionV2SourceAdmission.admit(f.wrapper,sha256(f.wrapper),f.campaign,"0".repeat(64),f.request,"OM08",2,"original") }.message)
        for(change in listOf(mapOf("bytes" to n(f.request.integer("bytes")+1)),mapOf("sha256" to s("0".repeat(64)))))
            assertFails { admit(f.copy(request=JsonObject(f.request+change))) }
        for((caseId,repeat,role)in listOf(Triple("OM07",2,"original"),Triple("OM08",1,"original"),Triple("OM08",2,"core"))) {
            val error=assertFailsWith<IllegalArgumentException> { MotionV2SourceAdmission.admit(
                f.wrapper,sha256(f.wrapper),f.campaign,sha256(f.campaign),f.request,caseId,repeat,role) }
            assertTrue(error.message.orEmpty().startsWith("Request belongs to another slot or source:"))
        }
    }
    @Test fun resealed_request_cannot_use_another_planned_run_and_admitted_raw_drift_is_detected() {
        val f=fixture("core");val campaign=OriginalMotionComparison.read(f.campaign)
        val other=campaign.arrayAt("runs").map { it.jsonObject }.single { it.text("caseId")=="OM08" && it.integer("repeat")==1L }
        val path=Path.of(f.request.text("path"));val old=OriginalMotionComparison.read(path)
        Files.writeString(path,JsonObject(old+("runId" to other.required("coreRunId"))).toString())
        assertEquals("Request belongs to another slot or source: runId",assertFailsWith<IllegalArgumentException> {
            admit(f.copy(request=ref(path)),"core") }.message)
        for(role in listOf("original","core")) {
            val admitted=admit(fixture(role),role)
            val raw=admitted.originalEntry.objectAt("rawRefs").objectAt("stdout")
            assertTrue(raw in admitted.identities)
            Files.writeString(Path.of(raw.text("path")),"changed frozen raw after admission, before actual host startup")
            assertFails { admitted.verifyStable() }
        }
    }
}
