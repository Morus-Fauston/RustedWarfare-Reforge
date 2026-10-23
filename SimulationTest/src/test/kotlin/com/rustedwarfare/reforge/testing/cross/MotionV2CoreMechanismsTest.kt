package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/** Temp format fixtures only: no worker, valid JAR, approval or mechanism PASS. */
class MotionV2CoreMechanismsTest {
    private fun ref(p:Path)=obj("path" to s(p.toAbsolutePath().toString()),"bytes" to n(Files.size(p)),"sha256" to s(sha256(p)))
    internal data class Fixture(val manifest:JsonObject,val core:JsonObject,val directory:Path)
    internal fun fixture(includeToolCopy:Boolean=false,corruptSavedSource:Boolean=false,withEnvelope:Boolean=false,omitAfter:Boolean=false):Fixture {
        val directory=Files.createTempDirectory("eng017-synthetic-mechanism-evidence-")
        val batch=directory.resolve("batch");Files.createDirectories(batch)
        val roles=linkedMapOf<JsonObject,String>();val docs=mutableListOf<JsonObject>()
        fun file(name:String,text:String,tracked:Boolean=true):JsonObject {
            val p=batch.resolve(name);Files.createDirectories(p.parent);Files.writeString(p,text)
            return ref(p).also { if(tracked)roles[it]="activeFrozen" }
        }
        fun document(name:String,kind:String,values:JsonObject,tracked:Boolean=true):JsonObject {
            val r=file(name,JsonObject(values+("kind" to s(kind))).toString(),tracked)
            if(tracked)docs+=obj("reference" to r,"schema" to s(kind));return r
        }
        fun historical(name:String,text:String):JsonObject {
            val r=obj("path" to s(directory.resolve(name).toString()),"bytes" to n(text.toByteArray().size),"sha256" to s(digest(text.toByteArray())))
            roles[r]="historicalOnly";return r
        }
        val source=historical("old/Source.kt","synthetic core source")
        val savedSource=file("Source.kt",if(corruptSavedSource)"resealed different source"else"synthetic core source")
        val oldJar=historical("old/core.jar","SYNTHETIC NOT A JAR");val jar=file("core.jar","SYNTHETIC NOT A JAR")
        val runtime=document("runtime.json","core-motion-canonical-runtime",obj("coreJar" to jar,"buildOutput" to oldJar,"classes" to obj()))
        val sources=document("sources.json","core-motion-canonical-sources",obj("files" to array(listOf(source)),
            "preservedFiles" to array(listOf(obj("source" to source,"preserved" to savedSource)))))
        val testPairs=(1..6).map { i->val text="synthetic test source $i";obj("historicalSource" to historical("old/Test$i.kt",text),"preserved" to file("Test$i.kt",text,false)) }
        val historicalTests=testPairs.map { it.objectAt("historicalSource") }
        val stdout=file("command.stdout","SYNTHETIC no command executed");val stderr=file("command.stderr","")
        fun command(name:String)=document(name,"eng-p002-017-command",obj("argv" to array(listOf(s("SYNTHETIC"))),"cwd" to s(directory.toString()),
            "startedUtc" to s("2026-10-22T00:00:00Z"),"timeoutSeconds" to n(1),"sourcesBefore" to array(historicalTests+source),
            "exitCode" to n(0),"stdout" to stdout,"stderr" to stderr,"sourcesAfter" to array((if(omitAfter)historicalTests.drop(1)else historicalTests)+source),
            "sourcesStable" to b(true),"finishedUtc" to s("2026-10-22T00:00:01Z"),"testXml" to array(emptyList())))
        val buildCommand=command("build.json");val testCommand=command("test.json")
        val build=document("build-receipt.json","core-motion-canonical-build-receipt",obj("commandReceipt" to buildCommand,
            "sourceFiles" to array(listOf(source)),"outputJar" to oldJar,"preservedJar" to jar))
        val core=obj("coreRuntime" to runtime,"coreSources" to sources,"coreBuildReceipt" to build)
        val agent=file("agent.jar","SYNTHETIC agent bytes");val oldAgent=historical("old/agent.jar","SYNTHETIC agent bytes")
        val init=file("init.gradle","SYNTHETIC");val python=historical("old/python.exe","old python identity")
        val plan=document("plan.json","core-motion-mechanisms-plan",JsonObject(core+mapOf("formalEligible" to b(false),"createdUtc" to s("2026-10-22T00:00:00Z"),
            "selectedMethods" to array(emptyList()),"requiredProductClasses" to array(emptyList()),"agent" to agent,"actualAgentCopy" to oldAgent,"initScript" to init,
            "actualMainRoot" to s(directory.resolve("old/main").toString()),"actualTestRoot" to s(directory.resolve("old/test").toString()),
            "mainClasses" to obj(),"testClasses" to obj(),"boundary" to s("SYNTHETIC no execution"))))
        val journal=file("identity.jsonl",obj("kind" to s("start"),"pid" to n(1),"javaExecutable" to s("old"),"javaHome" to s("old"),
            "javaVersion" to s("synthetic"),"commandLine" to JsonNull,"sunJavaCommand" to s("synthetic"),"inputArguments" to array(emptyList())).toString()+"\n"+
            obj("kind" to s("finish"),"pid" to n(1),"classCount" to n(0),"failed" to b(false)).toString()+"\n")
        docs+=obj("reference" to journal,"schema" to s("identity-journal-jsonl"))
        if(includeToolCopy) { historical("run_command.py","SYNTHETIC captured outer");file("sources/run_command.py","SYNTHETIC captured outer") }
        val commands=if(withEnvelope) {
            val envelope=file("outer.stdout",obj("result" to testCommand,"exitCode" to n(0),"stdoutTail" to s("SYNTHETIC")).toString())
            docs+=obj("reference" to envelope,"schema" to s("command-envelope"))
            array(listOf(obj("stdout" to envelope,"stderr" to stderr)))
        } else array(emptyList())
        val result=document("result.json","core-motion-mechanisms-qualification",JsonObject(core+mapOf("formalEligible" to b(false),
            "startedUtc" to s("2026-10-22T00:00:00Z"),"commands" to commands,"error" to JsonNull,"python" to python,"javaRuntime" to array(emptyList()),
            "plan" to plan,"testReceipt" to testCommand,"journal" to journal,"workerPid" to n(1),"productClassEquivalence" to array(emptyList()),
            "testHelperDefinitions" to array(emptyList()),"testXml" to array(emptyList()),"observedMethods" to array(emptyList()),"scopedPassed" to b(true),
            "boundary" to s("SYNTHETIC no actual mechanisms qualified"),"pins" to array(roles.keys),"drift" to array(emptyList()),"finishedUtc" to s("2026-10-22T00:00:01Z"))))
        val scope=s("eng017-core-mechanisms-19-v1")
        val inventory=document("inventory.json","core-mechanism-dependency-role-research",JsonObject(core+mapOf("schemaVersion" to n(1),"formalEligible" to b(false),
            "approvedCampaign" to b(false),"scope" to scope,"root" to result,"activeJsonDocuments" to array(docs),
            "references" to array(roles.map { (r,role)->obj("reference" to r,"role" to s(role),"categories" to array(emptyList()),
                "requiredCounterpart" to b(false),"counterpartEdges" to array(emptyList()),"occurrences" to array(emptyList())) }),
            "identityEdges" to array(emptyList()),"missingCounterparts" to array(emptyList()),"selectedTestSourceIdentityOnly" to array(historicalTests),
            "summary" to obj("uniqueReferences" to n(roles.size),"roles" to obj("activeFrozen" to n(roles.values.count { it=="activeFrozen" }),
                "historicalOnly" to n(roles.values.count { it=="historicalOnly" }))),"limits" to array(listOf(s("SYNTHETIC format only"))))),false)
        val audit=obj("path" to s(directory.resolve("missing-audit-snapshot.json").toString()),"bytes" to n(1),"sha256" to s("a".repeat(64)))
        val selected=document("selected-sources.json","eng017-core-mechanism-selected-test-source-byte-binding",obj("schemaVersion" to n(1),"formalEligible" to b(false),
            "createdUtc" to s("2026-10-23T00:00:00Z"),"mechanismResult" to result,"mechanismTestReceipt" to testCommand,
            "researchInventory" to inventory,"laterSourceSnapshot" to audit,"sources" to array(testPairs),"boundary" to s("Later copies, no old copy path claim")),false)
        val record=file("review.txt","SYNTHETIC review format, not actual independent acceptance",false)
        val review=document("review.json","motion-v2-core-mechanisms-review",JsonObject(core+mapOf("schemaVersion" to n(1),"scope" to scope,
            "decision" to s("accepted-scoped"),"reviewer" to s("synthetic-reviewer"),"author" to s("synthetic-author"),"result" to result,
            "dependencyInventory" to inventory,"selectedTestSources" to selected,"record" to record,
            "exclusions" to array(listOf("full-sources","formal-compatibility","performance","mid-advance-rollback").map(::s)))) ,false)
        val manifest=document("manifest.json","motion-v2-core-mechanisms-evidence",JsonObject(core+mapOf("schemaVersion" to n(1),"scope" to scope,
            "formalEligible" to b(false),"result" to result,"dependencyInventory" to inventory,"selectedTestSources" to selected,"nonAuthorReview" to review)),false)
        return Fixture(manifest,core,directory)
    }
    private fun load(f:Fixture)=MotionV2CoreMechanisms.load(f.manifest,f.core)
    private fun replace(value:JsonElement,old:JsonObject,new:JsonObject):JsonElement=when {
        value==old->new
        value is JsonObject->JsonObject(value.mapValues { replace(it.value,old,new) })
        value is JsonArray->JsonArray(value.map { replace(it,old,new) })
        else->value
    }
    /** Reseal all dependent containers, so an obsolete outer hash cannot mask the intended guard. */
    private fun rewrite(f:Fixture,component:String,change:(JsonObject)->JsonObject):Fixture {
        val path=Path.of(f.manifest.text("path"));var manifest=OriginalMotionComparison.read(path)
        val order=listOf("result","dependencyInventory","selectedTestSources","nonAuthorReview")
        val substitutions=mutableListOf<Pair<JsonObject,JsonObject>>()
        for(key in order.drop(order.indexOf(component))) {
            val before=manifest.objectAt(key);val file=Path.of(before.text("path"));var document=OriginalMotionComparison.read(file)
            for((old,new)in substitutions)document=replace(document,old,new).jsonObject
            if(key==component)document=change(document)
            Files.writeString(file,document.toString());val after=ref(file)
            manifest=JsonObject(manifest+(key to after));substitutions+=before to after
        }
        Files.writeString(path,manifest.toString());return f.copy(manifest=ref(path))
    }
    @Test fun typed_dependency_closure_reads_saved_bytes_without_opening_missing_historical_paths_or_qualifying_execution() {
        val f=fixture();val parsed=load(f)
        assertEquals("eng017-core-mechanisms-19-v1",parsed.document.text("scope"))
        assertTrue(parsed.identities.isNotEmpty())
        assertTrue(parsed.historical.isNotEmpty())
        assertTrue(parsed.historical.none { Files.exists(Path.of(it.text("path"))) })
        assertEquals(6,parsed.testSources.size)
        assertEquals("SYNTHETIC NOT A JAR",Files.readString(Path.of(OriginalMotionComparison.read(Path.of(f.core.objectAt("coreRuntime").text("path"))).objectAt("coreJar").text("path"))))
    }
    @Test fun captured_tool_copy_and_command_envelope_get_typed_roles_even_if_the_copy_also_appears_in_the_pin_ledger() {
        val parsed=load(fixture(includeToolCopy=true,withEnvelope=true))
        assertEquals(1,parsed.copies.count { Path.of(it.preserved.text("path")).endsWith(Path.of("sources/run_command.py")) })
        assertTrue(parsed.historical.any { Path.of(it.text("path")).fileName.toString()=="run_command.py" })
    }
    @Test fun inventory_cannot_downgrade_omit_duplicate_or_add_untyped_dependencies_after_all_containers_are_resealed() {
        for(mode in listOf("downgrade","omit","duplicate","untyped-metadata")) {
            val f=fixture();val changed=rewrite(f,"dependencyInventory") { d->
                val rows=d.arrayAt("references")
                when(mode) {
                    "downgrade"->JsonObject(d+("references" to array(rows.map { row->val r=row.jsonObject
                        if(r.required("reference")==f.core.required("coreRuntime"))JsonObject(r+("role" to s("historicalOnly")))else r })))
                    "omit"->JsonObject(d+("references" to JsonArray(rows.dropLast(1))))
                    "duplicate"->JsonObject(d+("references" to JsonArray(rows+rows.first())))
                    else->JsonObject(d+("limits" to array(listOf(obj("path" to s(f.directory.resolve("untyped.bin").toString()),"bytes" to n(1),"sha256" to s("c".repeat(64)))))))
                }
            }
            assertFails { load(changed) }
        }
    }
    @Test fun an_unknown_pin_cannot_get_a_historical_default_even_with_a_matching_inventory_label() {
        var f=fixture();val unknown=obj("path" to s(f.directory.resolve("unclassified.bin").toString()),"bytes" to n(1),"sha256" to s("c".repeat(64)))
        f=rewrite(f,"result") { JsonObject(it+("pins" to JsonArray(it.arrayAt("pins")+unknown))) }
        f=rewrite(f,"dependencyInventory") { d->JsonObject(d+("references" to JsonArray(d.arrayAt("references")+
            obj("reference" to unknown,"role" to s("historicalOnly"),"categories" to array(emptyList()),"requiredCounterpart" to b(false),
                "counterpartEdges" to array(emptyList()),"occurrences" to array(emptyList()))))) }
        assertFails { load(f) }
    }
    @Test fun saved_bytes_need_the_historical_identity_even_when_their_new_hashes_are_consistently_resealed() {
        assertFails { load(fixture(corruptSavedSource=true)) }
        val f=fixture();val document=OriginalMotionComparison.read(Path.of(f.manifest.text("path")))
        val runtime=OriginalMotionComparison.read(Path.of(f.core.objectAt("coreRuntime").text("path")))
        Files.writeString(Path.of(runtime.objectAt("coreJar").text("path")),"changed frozen JAR bytes")
        assertFails { load(f) }
        assertEquals(b(false),document.required("formalEligible"))
    }
    @Test fun selected_sources_bind_both_old_receipt_sides_and_saved_bytes_without_reading_audit_paths() {
        assertFails { load(fixture(omitAfter=true)) }
        val f=fixture()
        val changed=rewrite(f,"selectedTestSources") { d->JsonObject(d+("sources" to array(d.arrayAt("sources").mapIndexed { i,item->
            if(i==0)JsonObject(item.jsonObject+("preserved" to f.core.required("coreRuntime")))else item
        }))) }
        assertFails { load(changed) }
    }
    @Test fun scoped_review_cannot_bind_other_components_or_expand_qualification_or_hide_a_changed_record() {
        for(key in listOf("decision","scope","reviewer","result","dependencyInventory","selectedTestSources","exclusions")) {
            val f=fixture();assertFails { load(rewrite(f,"nonAuthorReview") { d->JsonObject(d+(key to when(key) {
                "reviewer"->d.required("author")
                "exclusions"->array(emptyList())
                "result","dependencyInventory","selectedTestSources"->f.core.required("coreRuntime")
                else->s("wrong")
            })) }) }
        }
        val f=fixture();val m=OriginalMotionComparison.read(Path.of(f.manifest.text("path")))
        val review=OriginalMotionComparison.read(Path.of(m.objectAt("nonAuthorReview").text("path")))
        Files.writeString(Path.of(review.objectAt("record").text("path")),"changed after scoped review was indexed")
        assertFails { load(f) }
        assertFails { MotionV2CoreMechanisms.load(fixture().manifest,obj("coreRuntime" to f.core.required("coreSources"),"coreSources" to f.core.required("coreSources"),"coreBuildReceipt" to f.core.required("coreBuildReceipt"))) }
    }
}
