package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.ArrayDeque
import kotlinx.serialization.json.*

/** Typed evidence references and saved bytes, not mechanism or Sources PASS.
 * Worker/class/XML semantics and the actual nonauthor review remain upper-layer obligations. */
internal object MotionV2CoreMechanisms {
    private const val SCOPE="eng017-core-mechanisms-19-v1"
    private val REF=setOf("path","bytes","sha256")
    private val CORE=listOf("coreRuntime","coreSources","coreBuildReceipt")
    private fun fields(value:String)=value.split(' ').toSet()
    private val SCHEMAS=mapOf(
        "core-motion-mechanisms-qualification" to fields("kind formalEligible startedUtc commands error python javaRuntime plan coreRuntime coreSources coreBuildReceipt testReceipt journal workerPid productClassEquivalence testHelperDefinitions testXml observedMethods scopedPassed boundary pins drift finishedUtc"),
        "core-motion-mechanisms-plan" to fields("kind formalEligible createdUtc coreRuntime coreSources coreBuildReceipt selectedMethods requiredProductClasses agent actualAgentCopy initScript actualMainRoot actualTestRoot mainClasses testClasses boundary"),
        "core-motion-canonical-runtime" to fields("kind coreJar buildOutput classes"),
        "core-motion-canonical-sources" to fields("kind files preservedFiles"),
        "core-motion-canonical-build-receipt" to fields("kind commandReceipt sourceFiles outputJar preservedJar"),
        "eng-p002-017-command" to fields("kind argv cwd startedUtc timeoutSeconds sourcesBefore exitCode stdout stderr sourcesAfter sourcesStable finishedUtc testXml"),
        "command-envelope" to fields("result exitCode stdoutTail"))
    data class Copy(val historical:JsonObject,val preserved:JsonObject)
    data class Parsed(val document:JsonObject,val identities:List<JsonObject>,val historical:List<JsonObject>,
                      val copies:List<Copy>,val testSources:List<Copy>)
    fun load(reference:JsonObject,expectedCore:JsonObject):Parsed {
        fun keys(o:JsonObject,wanted:Set<String>) { require(o.keys==wanted) { "Unsupported mechanism evidence fields" } }
        fun shape(r:JsonObject):Path {
            keys(r,REF);val p=Path.of(r.text("path"))
            require(p.isAbsolute && p==p.normalize() && r.integer("bytes")>=0 && r.text("sha256").matches(Regex("[0-9a-f]{64}")))
            return p
        }
        val consumed=mutableListOf<JsonObject>()
        fun bound(r:JsonObject):Path {
            val p=shape(r)
            require(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS) && Files.size(p)==r.integer("bytes") && sha256(p)==r.text("sha256")) { "Mechanism evidence identity differs: $p" }
            consumed+=r;return p
        }
        fun read(r:JsonObject)=OriginalMotionComparison.read(bound(r))
        val manifest=read(reference)
        keys(manifest,fields("kind schemaVersion scope formalEligible result coreRuntime coreSources coreBuildReceipt dependencyInventory selectedTestSources nonAuthorReview"))
        require(manifest.text("kind")=="motion-v2-core-mechanisms-evidence" && manifest.integer("schemaVersion")==1L &&
            manifest.text("scope")==SCOPE && manifest.required("formalEligible")==b(false))
        for(name in CORE)require(manifest.required(name)==expectedCore.required(name))
        val resultRef=manifest.objectAt("result")
        val active=linkedSetOf<JsonObject>();val history=linkedSetOf<JsonObject>();val ledger=linkedSetOf<JsonObject>()
        val wantedTypes=linkedMapOf<JsonObject,String>();val queue=ArrayDeque<JsonObject>()
        val documents=linkedMapOf<JsonObject,String>();val objects=linkedMapOf<JsonObject,JsonObject>()
        fun observe(r:JsonObject,role:String,type:String="@file") {
            shape(r)
            when(role) {
                "ledger"->ledger+=r
                "historicalOnly"->if(r !in active)history+=r
                "activeFrozen"->{
                    active+=r;history-=r
                    val old=wantedTypes[r]
                    val next=when {
                        old==null || old=="@file"->type
                        type=="@file" || type==old->old
                        old=="@envelope"->type
                        type=="@envelope"->old
                        else->error("Conflicting typed mechanism documents")
                    }
                    if(old==null || next!=old) { wantedTypes[r]=next;queue.add(r) }
                }
                else->error("Unknown mechanism dependency role")
            }
        }
        fun classify(kind:String,parts:List<String>):Pair<String,String> {
            val p=parts.joinToString("/")
            fun h()="historicalOnly" to "@file"
            fun a(type:String="@file")="activeFrozen" to type
            fun coreType(name:String)=when(name) {
                "coreRuntime"->"core-motion-canonical-runtime"
                "coreSources"->"core-motion-canonical-sources"
                else->"core-motion-canonical-build-receipt"
            }
            if(p in CORE && kind in setOf("core-motion-mechanisms-qualification","core-motion-mechanisms-plan"))return a(coreType(p))
            return when(kind) {
                "core-motion-mechanisms-qualification"->when {
                    p=="python" || p.matches(Regex("javaRuntime/\\d+"))->h()
                    p.matches(Regex("pins/\\d+"))->"ledger" to "@file"
                    p.matches(Regex("(productClassEquivalence|testHelperDefinitions)/\\d+/actualClass")) || p.matches(Regex("testXml/\\d+/actual"))->h()
                    p=="plan"->a("core-motion-mechanisms-plan")
                    p=="testReceipt"->a("eng-p002-017-command")
                    p=="journal"->a("@journal")
                    p.matches(Regex("commands/\\d+/(stdout|stderr)"))->a("@envelope")
                    p.matches(Regex("productClassEquivalence/\\d+/canonicalEntry/jar")) ||
                        p.matches(Regex("testHelperDefinitions/\\d+/preservedClass")) || p.matches(Regex("testXml/\\d+/preserved"))->a()
                    else->error("Unclassified mechanism result reference: $p")
                }
                "core-motion-mechanisms-plan"->when {
                    p in setOf("agent","initScript")->a()
                    p=="actualAgentCopy" || (parts.size==2 && parts.first() in setOf("mainClasses","testClasses"))->h()
                    else->error("Unclassified mechanism plan reference: $p")
                }
                "core-motion-canonical-runtime"->when(p) { "coreJar"->a();"buildOutput"->h();else->error("Unclassified canonical runtime reference") }
                "core-motion-canonical-sources"->when {
                    p.matches(Regex("files/\\d+")) || p.matches(Regex("preservedFiles/\\d+/source"))->h()
                    p.matches(Regex("preservedFiles/\\d+/preserved"))->a()
                    else->error("Unclassified canonical source reference")
                }
                "core-motion-canonical-build-receipt"->when {
                    p=="commandReceipt"->a("eng-p002-017-command")
                    p=="preservedJar"->a()
                    p=="outputJar" || p.matches(Regex("sourceFiles/\\d+"))->h()
                    else->error("Unclassified build receipt reference")
                }
                "eng-p002-017-command"->when {
                    p.matches(Regex("(sourcesBefore|sourcesAfter)/\\d+"))->h()
                    p in setOf("stdout","stderr")->a("@envelope")
                    p.matches(Regex("testXml/\\d+"))->a()
                    else->error("Unclassified mechanism command reference")
                }
                "command-envelope"->if(p=="result")a("eng-p002-017-command")else error("Unclassified command envelope reference")
                else->error("Unsupported typed mechanism reference: $kind $p")
            }
        }
        fun scan(value:JsonElement,parts:List<String>,kind:String) {
            if(value is JsonObject && value.keys.containsAll(REF)) {
                require(value.keys==REF || (kind=="eng-p002-017-command" && parts.firstOrNull()=="testXml" && value.keys==REF+"module" && value.text("module").isNotBlank()))
                val r=JsonObject(value.filterKeys { it in REF });val (role,type)=classify(kind,parts);observe(r,role,type)
            }
            when(value) {
                is JsonObject->for((k,v)in value)scan(v,parts+k,kind)
                is JsonArray->for((i,v)in value.withIndex())scan(v,parts+i.toString(),kind)
                else->Unit
            }
        }
        observe(resultRef,"activeFrozen","core-motion-mechanisms-qualification")
        val processed=mutableSetOf<Pair<JsonObject,String>>()
        while(queue.isNotEmpty()) {
            val r=queue.remove();val type=wantedTypes.getValue(r)
            if(!processed.add(r to type))continue
            val path=bound(r)
            if(type=="@file")continue
            if(type=="@journal") {
                val rows=Files.readAllLines(path).map { json.parseToJsonElement(it).jsonObject }
                for(row in rows) {
                    val expected=when(row.text("kind")) {
                        "start"->fields("kind pid javaExecutable javaHome javaVersion commandLine sunJavaCommand inputArguments")
                        "class"->fields("kind pid name origin bytes sha256")
                        "finish"->fields("kind pid classCount failed")
                        else->error("Unknown identity journal record")
                    }
                    keys(row,expected)
                }
                scan(array(rows),emptyList(),"identity-journal-jsonl");documents[r]="identity-journal-jsonl";continue
            }
            val text=Files.readString(path).trim()
            if(type=="@envelope" && !text.startsWith("{") && !text.startsWith("["))continue
            val document=json.parseToJsonElement(text).jsonObject
            val kind=if(type=="@envelope")"command-envelope"else type
            keys(document,SCHEMAS.getValue(kind))
            if(type!="@envelope")require(document.text("kind")==kind)
            documents[r]=kind;objects[r]=document;scan(document,emptyList(),kind)
        }
        val root=objects.getValue(resultRef)
        val plan=objects.getValue(root.objectAt("plan"))
        for(name in CORE)require(root.required(name)==manifest.required(name) && plan.required(name)==manifest.required(name))
        require(root.required("formalEligible")==b(false) && root.required("error")==JsonNull && root.required("scopedPassed")==b(true) && root.arrayAt("drift").isEmpty())
        val copies=mutableListOf<Copy>()
        fun copy(old:JsonObject,saved:JsonObject) {
            shape(old);bound(saved)
            require(old.required("bytes")==saved.required("bytes") && old.required("sha256")==saved.required("sha256")) { "Saved bytes differ from historical identity" }
            copies+=Copy(old,saved)
        }
        val copyNames=setOf("run_core_motion_mechanisms_v1.py","Eng017CoreIdentityAgent.java","original_host_io_v1.py","core_runtime_file_v1.py","run_command.py")
        val batch=shape(resultRef).parent
        for(r in ledger.filter { it !in active && it !in history }.filter {
            val origin=shape(it);origin.fileName.toString() in copyNames && origin==batch.parent.resolve(origin.fileName)
        }) {
            val origin=shape(r);val name=origin.fileName.toString()
            val saved=batch.resolve("sources").resolve(name)
            val identity=obj("path" to s(saved.toString()),"bytes" to r.required("bytes"),"sha256" to r.required("sha256"))
            observe(r,"historicalOnly");active+=identity;history-=identity;copy(r,identity)
        }
        require(ledger.all { it in active || it in history }) { "Unclassified mechanism pin" }
        val runtime=objects.getValue(manifest.objectAt("coreRuntime"));val sources=objects.getValue(manifest.objectAt("coreSources"))
        val build=objects.getValue(manifest.objectAt("coreBuildReceipt"))
        require(build.required("outputJar")==runtime.required("buildOutput") && build.required("preservedJar")==runtime.required("coreJar") && build.required("sourceFiles")==sources.required("files"))
        copy(runtime.objectAt("buildOutput"),runtime.objectAt("coreJar"));copy(plan.objectAt("actualAgentCopy"),plan.objectAt("agent"))
        val preservedSources=sources.arrayAt("preservedFiles").map { it.jsonObject }
        require(preservedSources.map { it.required("source") }==sources.arrayAt("files").toList())
        for(item in preservedSources) { keys(item,fields("source preserved"));copy(item.objectAt("source"),item.objectAt("preserved")) }
        for(item in root.arrayAt("testXml").map { it.jsonObject }) { keys(item,fields("actual preserved"));copy(item.objectAt("actual"),item.objectAt("preserved")) }
        for(item in root.arrayAt("testHelperDefinitions").map { it.jsonObject })copy(item.objectAt("actualClass"),item.objectAt("preservedClass"))
        val inventoryRef=manifest.objectAt("dependencyInventory");val inventory=read(inventoryRef)
        keys(inventory,fields("kind schemaVersion formalEligible approvedCampaign scope root coreRuntime coreSources coreBuildReceipt activeJsonDocuments references identityEdges missingCounterparts selectedTestSourceIdentityOnly summary limits"))
        require(inventory.text("kind")=="core-mechanism-dependency-role-research" && inventory.integer("schemaVersion")==1L && inventory.text("scope")==SCOPE &&
            inventory.required("formalEligible")==b(false) && inventory.required("approvedCampaign")==b(false) && inventory.required("root")==resultRef)
        for(name in CORE)require(inventory.required(name)==manifest.required(name))
        val declared=inventory.arrayAt("references").map { it.jsonObject }
        for(item in declared)keys(item,fields("reference role categories requiredCounterpart counterpartEdges occurrences"))
        require(declared.map { it.required("reference") }.distinct().size==declared.size)
        val roles=active.associateWith { "activeFrozen" }+history.associateWith { "historicalOnly" }
        require(declared.associate { it.objectAt("reference") to it.text("role") }==roles) { "Dependency inventory differs from typed reference closure" }
        fun inventoryRefs(value:JsonElement) {
            if(value is JsonObject && value.keys.containsAll(REF))require(JsonObject(value.filterKeys { it in REF }) in roles)
            when(value) {
                is JsonObject->for(v in value.values)inventoryRefs(v)
                is JsonArray->for(v in value)inventoryRefs(v)
                else->Unit
            }
        }
        inventoryRefs(inventory)
        require(inventory.arrayAt("activeJsonDocuments").map { val d=it.jsonObject;keys(d,fields("reference schema"));d.objectAt("reference") to d.text("schema") }.toSet()==documents.entries.map { it.key to it.value }.toSet())
        val selectedRef=manifest.objectAt("selectedTestSources");val selected=read(selectedRef)
        keys(selected,fields("kind schemaVersion formalEligible createdUtc mechanismResult mechanismTestReceipt researchInventory laterSourceSnapshot sources boundary"))
        require(selected.text("kind")=="eng017-core-mechanism-selected-test-source-byte-binding" && selected.integer("schemaVersion")==1L &&
            selected.required("formalEligible")==b(false) && selected.required("mechanismResult")==resultRef && selected.required("mechanismTestReceipt")==root.required("testReceipt") && selected.required("researchInventory")==inventoryRef)
        shape(selected.objectAt("laterSourceSnapshot")) // Audit locator, not an input to this mechanism scope.
        val testReceipt=objects.getValue(root.objectAt("testReceipt"))
        val testSources=selected.arrayAt("sources").map { val item=it.jsonObject;keys(item,fields("historicalSource preserved"));Copy(item.objectAt("historicalSource"),item.objectAt("preserved")) }
        require(testSources.size==6 && testSources.map { it.historical }.distinct().size==6 &&
            testSources.map { it.historical }.toSet()==inventory.arrayAt("selectedTestSourceIdentityOnly").map { it.jsonObject }.toSet())
        for(pair in testSources) {
            require(testReceipt.arrayAt("sourcesBefore").count { it==pair.historical }==1 && testReceipt.arrayAt("sourcesAfter").count { it==pair.historical }==1)
            copy(pair.historical,pair.preserved)
        }
        val review=read(manifest.objectAt("nonAuthorReview"))
        keys(review,fields("kind schemaVersion scope decision reviewer author result coreRuntime coreSources coreBuildReceipt dependencyInventory selectedTestSources record exclusions"))
        require(review.text("kind")=="motion-v2-core-mechanisms-review" && review.integer("schemaVersion")==1L && review.text("scope")==SCOPE &&
            review.text("decision")=="accepted-scoped" && review.text("reviewer")!=review.text("author")) { "Scoped mechanism review is not accepted for this profile" }
        for(name in CORE+listOf("result","dependencyInventory","selectedTestSources"))require(review.required(name)==manifest.required(name))
        require(review.arrayAt("exclusions").map { it.jsonPrimitive.content }.toSet()==setOf("full-sources","formal-compatibility","performance","mid-advance-rollback"))
        bound(review.objectAt("record"))
        for(r in consumed.toList())bound(r)
        return Parsed(manifest,consumed.distinct(),history.toList(),copies,testSources)
    }
}
