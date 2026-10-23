package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import kotlinx.serialization.json.*

/** System-temp synthetic plan controls. No real approval or runtime is produced. */
class MotionV2CampaignTest {
    private fun ref(p:Path)=obj("path" to s(p.toAbsolutePath().toString()),"bytes" to n(Files.size(p)),"sha256" to s(sha256(p)))
    private data class Fixture(val path:Path,val policy:MotionV2Standard.Policy)
    private fun fixture(change:(JsonObject)->JsonObject={it}):Fixture {
        val temp=Files.createTempDirectory("eng017-synthetic-campaign-")
        fun artifact(name:String):JsonObject { val p=temp.resolve(name);Files.writeString(p,"SYNTHETIC ONLY $name\n");return ref(p) }
        val wrapper=artifact("synthetic-wrapper.txt")
        val tools=listOf(artifact("native-validator.txt"),artifact("python.txt"),artifact("comparison.txt"))
        val mechanisms=MotionV2CoreMechanismsTest().fixture();val originals=MotionV2FrozenOriginalsTest().fixture()
        val core=mechanisms.core.objectAt("coreRuntime");val sources=mechanisms.core.objectAt("coreSources");val build=mechanisms.core.objectAt("coreBuildReceipt")
        val review=temp.resolve("synthetic-review.json")
        Files.writeString(review,obj("kind" to s("motion-v2-core-snapshot-review"),"schemaVersion" to n(1),"status" to s("accepted"),
            "reviewer" to s("synthetic-independent-reviewer"),"author" to s("synthetic-author"),
            "coreRuntime" to core,"coreSources" to sources,"coreBuildReceipt" to build).toString())
        val ids=listOf("OM00","OM01E","OM01W","OM02S","OM02N","OM03SE","OM03NW","OM04","OM05","OM06","OM07","OM08")
        val catalog=obj("cases" to array(ids.map { obj("id" to s(it)) }))
        val policy=MotionV2Standard.Policy("p002.original-motion.O.v2",obj(),catalog,"settle-start","settle-start",listOf(wrapper)+tools,wrapper,tools)
        val runs=array(originals.slots)
        val doc=obj("kind" to s("motion-v2-final-campaign"),"schemaVersion" to n(3),"campaignId" to s(UUID.randomUUID().toString()),
            "createdUtc" to s("2026-10-22T00:00:00Z"),"standard" to wrapper,"sources" to obj("nativeValidator" to tools[0],"python" to tools[1],
                "comparisonRuntime" to tools[2],"coreRuntime" to core,"coreSources" to sources,"coreBuildReceipt" to build,"nonAuthorReview" to ref(review)),"runs" to runs,
            "frozenOriginals" to originals.manifest,"coreMotionMechanisms" to mechanisms.manifest)
        val p=temp.resolve("campaign.json");Files.writeString(p,change(doc).toString()+"\n");return Fixture(p,policy)
    }
    private fun load(f:Fixture)=MotionV2Campaign.load(f.path,sha256(f.path),f.policy)
    @Test fun exact36_slots_and72_ids_load_with_named_qualified_sources_without_runtime_claim() {
        val f=fixture();val p=load(f);assertEquals(36,p.runs.size);assertEquals(ref(f.path),p.identity)
        assertTrue(p.document.objectAt("frozenOriginals") in p.identities)
        assertTrue(p.document.objectAt("coreMotionMechanisms") in p.identities)
        val frozen=OriginalMotionComparison.read(Path.of(p.document.objectAt("frozenOriginals").text("path")))
        assertTrue(frozen.arrayAt("entries").first().jsonObject.objectAt("rawRefs").objectAt("stdout") in p.identities)
    }
    @Test fun explicit_plan_hash_and_approved_wrapper_identity_are_mandatory() {
        val f=fixture();for(hash in listOf("","0".repeat(64)))assertFails { MotionV2Campaign.load(f.path,hash,f.policy) }
        assertFails { load(fixture { JsonObject(it+("standard" to it.objectAt("sources").required("coreRuntime"))) }) }
    }
    @Test fun missing_extra_reordered_and_replaced_slots_are_rejected() {
        for(change in listOf<(JsonArray)->JsonArray>({JsonArray(it.dropLast(1))},{JsonArray(it+it.first())},{JsonArray(it.reversed())},
            {JsonArray(it.mapIndexed { i,r->if(i==0)JsonObject(r.jsonObject+("caseId" to s("OTHER")))else r })}))
            assertFails { load(fixture { JsonObject(it+("runs" to change(it.arrayAt("runs")))) }) }
    }
    @Test fun cross_side_duplicate_UUID_or_noncanonical_ID_is_rejected() {
        assertFails { load(fixture { o->val runs=o.arrayAt("runs").map { r->val row=r.jsonObject;JsonObject(row+("coreRunId" to row.required("originalRunId"))) };JsonObject(o+("runs" to array(runs))) }) }
        for(key in listOf("campaignId","createdUtc"))assertFails { load(fixture { JsonObject(it+(key to s("invalid"))) }) }
    }
    @Test fun tool_identity_must_be_in_the_named_qualification_artifacts() {
        assertFails { load(fixture { o->val sources=o.objectAt("sources");JsonObject(o+("sources" to JsonObject(sources+("python" to sources.required("coreRuntime"))))) }) }
    }
    @Test fun review_must_bind_all_three_exact_core_refs_and_an_independent_acceptance() {
        for(key in listOf("coreRuntime","coreSources","coreBuildReceipt","status","reviewer")) {
            val f=fixture();val o=OriginalMotionComparison.read(f.path);val sources=o.objectAt("sources")
            val reviewPath=Path.of(sources.objectAt("nonAuthorReview").text("path"));val review=OriginalMotionComparison.read(reviewPath)
            val bad=if(key in listOf("status","reviewer"))s(if(key=="status")"pending"else"synthetic-author")else sources.required("python")
            Files.writeString(reviewPath,JsonObject(review+(key to bad)).toString())
            Files.writeString(f.path,JsonObject(o+("sources" to JsonObject(sources+("nonAuthorReview" to ref(reviewPath))))).toString())
            assertFails { load(f) }
        }
    }
    @Test fun wrong_file_size_sha_unknown_field_and_direct_cycle_are_rejected() {
        for(key in listOf("bytes","sha256"))assertFails { load(fixture { o->val sources=o.objectAt("sources");val r=sources.objectAt("coreRuntime")
            JsonObject(o+("sources" to JsonObject(sources+("coreRuntime" to JsonObject(r+(key to if(key=="bytes")n(0)else s("0".repeat(64)))))))) }) }
        assertFails { load(fixture { JsonObject(it+("newRule" to b(true))) }) }
        assertFails { load(fixture { o->val sources=o.objectAt("sources");JsonObject(o+("sources" to JsonObject(sources+("coreRuntime" to o.required("standard"))))) }) }
    }
    @Test fun schema_three_requires_both_manifests_and_rejects_the_old_schema_two_shape() {
        for(key in listOf("frozenOriginals","coreMotionMechanisms"))assertFails { load(fixture { JsonObject(it-key) }) }
        assertFails { load(fixture { JsonObject(it+("schemaVersion" to n(2))) }) }
        assertFails { load(fixture { JsonObject((it-"frozenOriginals"-"coreMotionMechanisms")+("schemaVersion" to n(2))) }) }
    }
    @Test fun nested_original_or_mechanism_dependencies_cannot_drift_behind_an_unchanged_outer_manifest() {
        for(role in listOf("original","mechanism")) {
            val f=fixture();val campaign=OriginalMotionComparison.read(f.path)
            val target=if(role=="original") {
                val frozen=OriginalMotionComparison.read(Path.of(campaign.objectAt("frozenOriginals").text("path")))
                frozen.arrayAt("entries").first().jsonObject.objectAt("rawRefs").objectAt("stdout")
            } else {
                val mechanism=OriginalMotionComparison.read(Path.of(campaign.objectAt("coreMotionMechanisms").text("path")))
                val selected=OriginalMotionComparison.read(Path.of(mechanism.objectAt("selectedTestSources").text("path")))
                selected.arrayAt("sources").first().jsonObject.objectAt("preserved")
            }
            Files.writeString(Path.of(target.text("path")),"changed nested source bytes")
            assertFails { load(f) }
        }
    }
}
