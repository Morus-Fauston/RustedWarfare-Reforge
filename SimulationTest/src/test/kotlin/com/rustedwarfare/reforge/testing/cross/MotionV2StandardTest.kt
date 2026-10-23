package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/** Synthetic structural controls in system temp. No real standard approval,
 * native runtime, or compatibility result is created by these fixtures. */
class MotionV2StandardTest {
    private val finalValidation="qualified frozen original fixed12x3 reused; collect only affected missing evidence or invalidated source slots; fresh current-core fixed12x3 after whole-version approval; preserve original UUIDs and slot mapping, no duplicate-run padding or best-batch selection (D-097)"
    private val root=Path.of(System.getProperty("repo.root"))
    private fun read(p: Path)=OriginalMotionComparison.read(p)
    private fun write(p: Path,o: JsonObject) { Files.writeString(p,o.toString()+"\n") }
    private fun ref(p: Path)=obj("path" to s(p.toAbsolutePath().toString()),"bytes" to n(Files.size(p)),"sha256" to s(sha256(p)))
    private fun changed(o: JsonObject,key: String,value: JsonElement)=JsonObject(o+(key to value))
    internal data class Fixture(val wrapper: Path,val specification: Path,val contract: Path,val catalog: Path,
                               val qualification: Path,val artifact: Path,val review: Path)
    internal fun fixture(contractChange:(JsonObject)->JsonObject={it},catalogChange:(JsonObject)->JsonObject={it},
                        qualificationChange:(JsonObject)->JsonObject={it},wrapperChange:(JsonObject)->JsonObject={it}):Fixture {
        val folder=Files.createTempDirectory("reforge-v2-policy-fixture-")
        val specification=folder.resolve("synthetic.md");Files.writeString(specification,"SYNTHETIC format fixture; no maintainer approval or runtime qualification.\n")
        val originalContract=root.resolve("TestLab/contracts/P002-original-motion/O.candidate.v2.json")
        val originalCatalog=root.resolve("TestLab/scenarios/P002-original-motion/O.candidate.v2.json")
        val approval=obj("status" to s("approved"),"source" to s("maintainer-user-message"),"date" to s("2026-10-22"),
            "threadId" to s("synthetic-test-fixture"),"text" to s("SYNTHETIC TEST DATA ONLY"),"scope" to s("synthetic loader structure"))
        val catalog=folder.resolve("catalog.json")
        write(catalog,catalogChange(JsonObject(read(originalCatalog)+mapOf("kind" to s("motion-scenario-catalog-v2"),"schemaVersion" to n(2),
            "id" to s("p002.original-motion.O.v2"),"approval" to approval,"executable" to b(true),"basedOnCandidate" to ref(originalCatalog)))))
        val contract=folder.resolve("contract.json");val base=read(originalContract)
        val choices=array(base.arrayAt("pendingFreezeChoices").map {
            val row=it.jsonObject
            changed(row,"value",s(if(row.text("id")=="OM07-length-endpoint")"declared-stable-tail-end" else "settle-start"))
        })
        write(contract,contractChange(JsonObject(base+mapOf("kind" to s("motion-cross-contract-v2"),"schemaVersion" to n(2),
            "id" to s("p002.original-motion.O.v2"),"approval" to approval,"executable" to b(true),"basedOnCandidate" to ref(originalContract),
            "specification" to ref(specification),"scenarios" to ref(catalog),"pendingFreezeChoices" to choices,
            "provenance" to changed(base.objectAt("provenance"),"finalValidation",s(finalValidation))))))
        val artifact=folder.resolve("synthetic-tool.bin");Files.writeString(artifact,"synthetic artifact, not an actual runtime")
        val review=folder.resolve("synthetic-review.txt");Files.writeString(review,"synthetic review shape, no actual review claimed")
        val evidence=folder.resolve("synthetic-evidence.txt");Files.writeString(evidence,"synthetic evidence shape")
        val qualification=folder.resolve("qualification.json")
        write(qualification,qualificationChange(obj("kind" to s("motion-v2-tool-qualification"),"schemaVersion" to n(1),"status" to s("qualified"),
            "caseIds" to array(read(originalCatalog).arrayAt("cases").map { s(it.jsonObject.text("id")) }),
            "artifacts" to array(listOf(ref(artifact))),"evidenceRefs" to array(listOf(ref(evidence))),"nonAuthorReview" to ref(review))))
        val wrapper=folder.resolve("synthetic-standard.json")
        write(wrapper,wrapperChange(obj("kind" to s("original-motion-standard"),"schemaVersion" to n(2),"standardId" to s("p002.original-motion.O.v2"),
            "approval" to approval,"normative" to obj("specification" to ref(specification),"contract" to ref(contract),"scenarios" to ref(catalog)),
            "toolQualification" to ref(qualification))))
        return Fixture(wrapper,specification,contract,catalog,qualification,artifact,review)
    }
    private fun load(f: Fixture)=MotionV2Standard.loadApproved(f.wrapper,sha256(f.wrapper))
    @Test fun explicit_synthetic_format_loads_selected_endpoints_and_all12_cases_without_runtime_claim() {
        val f=fixture();val policy=load(f)
        assertEquals("p002.original-motion.O.v2",policy.standardId)
        assertEquals("declared-stable-tail-end",policy.lengthEndpoint);assertEquals("settle-start",policy.durationEndpoint)
        assertEquals(12,policy.scenarios.arrayAt("cases").size);assertTrue(policy.identities.size>=8)
        assertEquals(ref(f.wrapper),policy.wrapperRef);assertEquals(listOf(ref(f.artifact)),policy.qualificationArtifacts)
    }
    @Test fun d097_frozen_original_reuse_and_d096_endpoints_are_the_only_supported_approved_semantics() {
        val policy=load(fixture())
        assertEquals(finalValidation,policy.contract.objectAt("provenance").text("finalValidation"))
        assertEquals("declared-stable-tail-end",policy.lengthEndpoint)
        assertEquals("settle-start",policy.durationEndpoint)
        for(value in listOf("fresh original and fresh core fixed12x3 after whole-version approval","reuse any original trajectory for all repeats"))
            assertFails { load(fixture(contractChange={o->changed(o,"provenance",changed(o.objectAt("provenance"),"finalValidation",s(value)))})) }
        for(id in listOf("OM07-length-endpoint","OM07-duration-endpoint"))
            assertFails { load(fixture(contractChange={o->changed(o,"pendingFreezeChoices",array(o.arrayAt("pendingFreezeChoices").map {
                val row=it.jsonObject;if(row.text("id")==id)changed(row,"value",s("normal-clear"))else row
            }))})) }
    }
    @Test fun current_pending_candidate_and_v1_wrapper_are_not_v2_approval() {
        for(relative in listOf("TestLab/contracts/P002-original-motion/O.candidate.v2.json","TestLab/contracts/P002-original-motion/O.pending.v2.json","TestLab/standards/P002-original-motion/O.v1.json")) {
            val p=root.resolve(relative);assertFails { MotionV2Standard.loadApproved(p,sha256(p)) }
        }
    }
    @Test fun absent_or_wrong_external_identity_cannot_be_replaced_by_self_declared_approval() {
        val f=fixture()
        for(hash in listOf("","0".repeat(64),"xyz")) assertFails { MotionV2Standard.loadApproved(f.wrapper,hash) }
    }
    @Test fun pending_wrapper_contract_or_catalog_and_disabled_execution_are_rejected() {
        assertFails { load(fixture(wrapperChange={changed(it,"approval",changed(it.objectAt("approval"),"status",s("pending")))})) }
        assertFails { load(fixture(contractChange={changed(it,"executable",b(false))})) }
        assertFails { load(fixture(catalogChange={changed(it,"executable",b(false))})) }
        assertFails { load(fixture(contractChange={changed(it,"approval",changed(it.objectAt("approval"),"status",s("pending")))})) }
    }
    @Test fun missing_duplicate_or_unknown_OM07_choice_is_never_defaulted() {
        assertFails { load(fixture(contractChange={o->changed(o,"pendingFreezeChoices",array(o.arrayAt("pendingFreezeChoices").map { changed(it.jsonObject,"value",JsonNull) }))})) }
        assertFails { load(fixture(contractChange={o->changed(o,"pendingFreezeChoices",array(listOf(o.arrayAt("pendingFreezeChoices").first(),o.arrayAt("pendingFreezeChoices").first())))})) }
        assertFails { load(fixture(contractChange={o->changed(o,"pendingFreezeChoices",array(o.arrayAt("pendingFreezeChoices").map { changed(it.jsonObject,"value",s("choose-after-result")) }))})) }
    }
    @Test fun unsupported_nested_numeric_semantics_are_rejected_even_with_rebound_hashes() {
        assertFails { load(fixture(contractChange={o->changed(o,"numeric",changed(o.objectAt("numeric"),"position",changed(o.objectAt("numeric").objectAt("position"),"maximum",n(5))))})) }
        assertFails { load(fixture(contractChange={o->changed(o,"numeric",changed(o.objectAt("numeric"),"unknownTolerance",n(999)))})) }
    }
    @Test fun altered_scene_or_removed_applicable_gate_is_rejected_even_with_rebound_hashes() {
        assertFails { load(fixture(catalogChange={o->changed(o,"cases",array(o.arrayAt("cases").mapIndexed { i,v->if(i==0)changed(v.jsonObject,"start",array(listOf(n(301),n(200))))else v }))})) }
        assertFails { load(fixture(catalogChange={o->changed(o,"cases",array(o.arrayAt("cases").mapIndexed { i,v->if(i==1)changed(v.jsonObject,"applicable",array(emptyList()))else v }))})) }
    }
    @Test fun drift_in_any_normative_source_is_rejected() {
        for(which in 0..2) {
            val f=fixture();val p=listOf(f.specification,f.contract,f.catalog)[which]
            Files.writeString(p,Files.readString(p)+" ");assertFails { load(f) }
        }
    }
    @Test fun incomplete_or_duplicate_tool_case_qualification_is_rejected() {
        assertFails { load(fixture(qualificationChange={o->changed(o,"caseIds",array(o.arrayAt("caseIds").dropLast(1)))})) }
        assertFails { load(fixture(qualificationChange={o->changed(o,"caseIds",array(o.arrayAt("caseIds").dropLast(1)+o.arrayAt("caseIds").first()))})) }
    }
    @Test fun tool_artifact_or_non_author_review_drift_is_rejected() {
        for(which in 0..1) {
            val f=fixture();Files.writeString(if(which==0)f.artifact else f.review,"changed after qualification")
            assertFails { load(f) }
        }
    }
    @Test fun unknown_wrapper_fields_and_redundant_approval_hash_are_rejected() {
        assertFails { load(fixture(wrapperChange={changed(it,"ignoreMissing",b(true))})) }
        assertFails { load(fixture(wrapperChange={changed(it,"approval",changed(it.objectAt("approval"),"approvedStandardSha256",JsonNull))})) }
    }
    @Test fun self_referencing_qualification_is_rejected_without_recursive_path_guessing() {
        val f=fixture();write(f.qualification,changed(read(f.qualification),"evidenceRefs",array(listOf(ref(f.qualification)))))
        write(f.wrapper,changed(read(f.wrapper),"toolQualification",ref(f.qualification)))
        assertFails { load(f) }
    }
}
