package com.rustedwarfare.reforge.testing.p001

import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*
import java.nio.file.*
import java.time.Instant

object P001Suite {
    data class Result(val exitCode:Int,val output:Path)
    private val requiredM=listOf("M01","M02","M03","M04a","M04b","M04c","M05","M06","M07")
    private val requiredL=listOf("L01","L02a","L02b","L03a","L03b","L03c")
    private fun status(code:Int)=when(code) { 0->"completed";2->"assertion-failed";3->"evidence-insufficient";else->"execution-failed" }
    private fun outcome(id:String,code:Int,failure:RelationFailure?=null,reason:String?=null)=record("p001-case-result",
        "caseId" to s(id),"exitCode" to n(code),"result" to s(status(code)),"firstDivergence" to failureJson(failure),
        "reason" to nullable(reason),"originalCompatibility" to b(false))

    fun run(repoRoot:Path,suiteRelative:String,topicRelative:String,command:List<String>):Result {
        val root=repoRoot.toAbsolutePath().normalize()
        val archive=P001Evidence(newRun(rootPath(root,topicRelative)))
        val started=Instant.now()
        val results=mutableListOf<JsonObject>()
        val inputs=mutableListOf<JsonObject>()
        var fatalCode:Int?=null
        var reason:String?=null
        var expectedCount=16
        try {
            val suitePath=rootPath(root,suiteRelative)
            val suite=readObject(suitePath);suite.header("p001-suite")
            val naturalTurn = naturalPathContract(suite)
            if(suite.text("pathBase")!="repo-root" || suite.objectAt("approval").text("status")!="accepted" || suite["originalCompatibility"]!=b(false)) throw InputError("Suite scope/approval mismatch")
            if(suite.arrayAt("aggregationPriority")!=array(listOf(64,4,3,2,0).map(::n))) throw InputError("Unsupported aggregation policy")
            val expectedIds=requiredM+requiredL+"L04"
            if(suite.arrayAt("requiredCases").map { it.jsonPrimitive.content }!=expectedIds) throw InputError("Required coverage differs from accepted P001 suite")
            expectedCount=expectedIds.size
            fun check(ref:JsonObject):Path { val path=verifyIdentity(root,ref);inputs+=ref;return path }
            inputs+=obj("path" to s(suiteRelative),"sha256" to s(sha256(suitePath)))
            archive.snapshot("suite.json",suitePath)
            val common=suite.arrayAt("content").map { it.jsonObject }
            val negatives=suite.arrayAt("negativeDependencies").map { it.jsonObject }
            if(common.size!=7 || negatives.size!=7) throw InputError("Expected all 14 accepted content identities")
            (common+negatives).forEach(::check)
            val cases=suite.arrayAt("cases").map { it.jsonObject }
            if(cases.map { it.id("caseId") }!=requiredM) throw InputError("Missing, duplicate or reordered movement case")
            val loaded=linkedMapOf<String,LoadedScenario>()
            val paths=linkedMapOf<String,Pair<Path,Path>>()
            for(case in cases) {
                val id=case.id("caseId")
                val scenario=check(case.objectAt("scenario"));val contract=check(case.objectAt("contract"));val relation=check(case.objectAt("relations"))
                archive.snapshot("$id.scenario.json",scenario);archive.snapshot("$id.basic.json",contract);archive.snapshot("$id.relations.json",relation)
                val metadata=readObject(relation);metadata.header("p001-relations")
                if(id=="M02" && naturalPathContract(metadata)!=naturalTurn) throw InputError("Suite/M02 path contract mismatch")
                if(metadata.text("caseId")!=id || metadata.objectAt("approval").text("status")!="accepted" || metadata.number("arithmeticError")!=1e-9 || metadata.arrayAt("exactFields")!=array(listOf("geometry","zero","idle-tail","identity","target","path","event-order").map(::s))) throw InputError("Unsupported relation identity/policy: $id")
                val input=ScenarioReader.load(scenario,contract)
                validatePlan(id,input,metadata,naturalTurn)
                if(input.identities.map { it.sha256 }.toSet()!=common.map { it.text("sha256") }.toSet()) throw InputError("Scenario content differs from suite common identities")
                loaded[id]=input;paths[id]=scenario to contract
            }
            val loadPath=check(suite.objectAt("loadCases"));archive.snapshot("load-cases.json",loadPath)
            val load=readObject(loadPath);load.header("p001-load-cases")
            if(load.text("pathBase")!="repo-root" || load.objectAt("approval").text("status")!="accepted") throw InputError("Load metadata policy mismatch")
            if(load.arrayAt("recoveryOrder").map { it.jsonPrimitive.content }!=requiredL || load.arrayAt("cases").map { it.jsonObject.id("caseId") }!=requiredL) throw InputError("Load sequence mismatch")
            for(case in load.arrayAt("cases").map { it.jsonObject }) { check(case.objectAt("input"));check(case.objectAt("recovery")) }
            check(load.objectAt("finalStep").objectAt("scenario"))
            // All identities are checked before any core execution, including negative dependencies.
            for(case in cases) {
                val id=case.id("caseId");val input=loaded.getValue(id)
                val child=P001Evidence(archive.directory.resolve(id))
                try {
                    var failure:RelationFailure?=null
                    var code=0
                    val samples:List<Observation>
                    if(id in listOf("M06","M07")) {
                        val capture=P001Lifecycle.capture(input,id);samples=capture.observations;failure=capture.failure
                        child.lines("lifecycle.jsonl",capture.rows)
                    } else {
                        val (scenario,contract)=paths.getValue(id)
                        val run=Runner.run(RunRequest(scenario,contract,child.directory.resolve("runner"),command+listOf("case",id)))
                        code=run.exitCode
                        val check=TraceFiles.compare(scenario,contract,run.output)
                        code=aggregate(listOf(code,check.exitCode));child.document("archive-check.json",check.document)
                        samples=readSamples(run.output.resolve("reforge/raw.jsonl"))
                        if(id=="M05") {
                            val capture=P001Lifecycle.capture(input,id)
                            child.lines("lifecycle.jsonl",capture.rows);failure=capture.failure
                            if(samples.map { it.units to it.events }!=capture.observations.map { it.units to it.events }) failure=failure?:RelationFailure("continuous-cross-check",0,"Runner and continuous public driver agree","different observations")
                        }
                    }
                    code=aggregate(listOf(code,samplingCode(samples,input.steps.size,true)))
                    if(code==0) failure=failure?:P001Relations.evaluate(input,id,samples,naturalTurn)
                    if(failure!=null && code==0) code=2
                    val trace=if(id in listOf("M06","M07")) child.directory.resolve("lifecycle.jsonl") else child.directory.resolve("runner/reforge/raw.jsonl")
                    val result=JsonObject(outcome(id,code,failure)+mapOf("evidenceRefs" to array(listOf(s(trace.toString()))),"path" to s("observations.u1"),"sampleIndexBase" to s("zero-based including initial")))
                    child.document("relations-result.json",result);results+=result
                } catch(error:InputError) { val result=outcome(id,64,reason=error.message);child.document("relations-result.json",result);results+=result }
                  catch(error:Exception) { val result=outcome(id,4,reason="${error.javaClass.simpleName}: ${error.message}");child.document("relations-result.json",result);results+=result }
            }
            results+=runLoads(root,load,loaded.getValue("M01"),archive)
            inputs.forEach { verifyIdentity(root,it) }
        } catch(error:InputError) { fatalCode=64;reason=error.message }
          catch(error:Exception) { fatalCode=4;reason="${error.javaClass.simpleName}: ${error.message}" }
        val codes=results.map { it.integer("exitCode").toInt() }.toMutableList()
        fatalCode?.let { codes+=it }
        if(results.size!=expectedCount) codes+=3
        val code=aggregate(codes)
        archive.document("results.json",record("p001-suite-result","exitCode" to n(code),"result" to s(status(code)),"cases" to array(results),
            "reason" to nullable(reason),"complete" to b(results.size==expectedCount && fatalCode==null),"firstDivergence" to results.firstOrNull { it.integer("exitCode")==2L }?.get("firstDivergence"),"originalCompatibility" to b(false)))
        val files=archive.inventory()
        archive.document("manifest.json",record("p001-suite-manifest","ticket" to s("TEST-P001-004"),"runId" to s(archive.directory.fileName.toString()),
            "startedAt" to s(started.toString()),"finishedAt" to s(Instant.now().toString()),"exitCode" to n(code),"status" to s(status(code)),
            "complete" to b(results.size==expectedCount && fatalCode==null),"expectedCases" to n(expectedCount),"observedCases" to n(results.size),
            "command" to array(command.map(::s)),"environment" to obj("os" to s(System.getProperty("os.name")),"arch" to s(System.getProperty("os.arch")),"java" to s(System.getProperty("java.runtime.version")),"repoRoot" to s(root.toString())),
            "toolArtifacts" to identityArtifacts(),"inputs" to array(inputs),"files" to files,"originalCompatibility" to b(false)))
        return Result(code,archive.directory)
    }

    private fun readSamples(path:Path):List<Observation> = Files.readAllLines(path).map { json.parseToJsonElement(it).jsonObject }.filter { it.text("kind")=="observation" }.map { row ->
        val value=row.objectAt("observation")
        Observation(value.id("sampleId"),value.id("worldId"),value.id("scenarioId"),value.integer("stepIndex"),value.number("simulationTime"),
            value.arrayAt("units").map { v->v.jsonObject.let { u->UnitObservation(u.id("unitId"),u.id("ownerPlayerId"),u.id("typeId"),position(u.objectAt("position")),u.number("heading"),u.number("speed"),u.number("angularVelocity"),u.required("moveTarget").let { if(it==JsonNull) null else position(it.jsonObject) },u.arrayAt("pathPoints").map { position(it.jsonObject) },MovementState.valueOf(u.text("movementState"))) } },
            value.arrayAt("events").map { e->e.jsonObject.let { Event(it.text("kind"),it["unitId"]?.jsonPrimitive?.contentOrNull) } },emptyList())
    }

    private fun validatePlan(id:String,input:LoadedScenario,metadata:JsonObject,naturalTurn:Boolean) {
        val counts=mapOf("M01" to 81,"M02" to 21,"M03" to 43,"M04a" to 901,"M04b" to 901,"M04c" to 901,"M05" to 27,"M06" to 27,"M07" to 27)
        if(input.steps.size!=counts.getValue(id) || metadata.integer("requiredSteps")!=input.steps.size.toLong() || metadata.text("scenarioId")!=input.core.scenarioId) throw InputError("Fixed sampling plan differs: $id")
        val start=when(id) { "M02"->Position(100.0,100.0);"M04a"->Position(100.0,190.0);"M04b"->Position(100.0,210.0);"M04c"->Position(100.0,230.0);else->Position(110.0,110.0) }
        val heading=if(id in listOf("M01","M02")) 0.0 else 90.0
        val expectedSpawn=UnitSpawn("u1","p1","p001.weaponless-tank",start,heading)
        if(input.core.units!=listOf(expectedSpawn) || input.core.players!=listOf(Player("p1",PlayerControl.LOCAL),Player("p2",PlayerControl.NONE)) || input.core.movementContract!=MovementContract(16.0,4.0)) throw InputError("Accepted initial state/contract differs: $id")
        val targets=when(id) { "M01"->listOf(0L to Position(110.0,90.0));"M02"->listOf(0L to Position(100.0,140.0));"M04a","M04b","M04c"->listOf(0L to Position(500.0,start.y));"M05"->listOf(0L to Position(250.0,110.0),1L to Position(450.0,100.0));"M06"->listOf(0L to Position(250.0,110.0),1L to Position(180.0,100.0));else->listOf(0L to Position(250.0,110.0)) }
        val moves=input.actions.filter { it.text("kind")=="move" }.map { it.integer("atStep") to position(it.objectAt("target")) }
        val stops=input.actions.filter { it.text("kind")=="stop" }.map { it.integer("atStep") }
        if(moves!=targets || stops!=(when(id) { "M03"->listOf(16L);"M06"->listOf(1L);else->emptyList() }) || input.actions.size!=moves.size+stops.size || input.actions.withIndex().any { (i,a)-> a.text("unitId")!="u1" || a.text("actor")!=if(id=="M06" && i>0) "p2" else "p1" }) throw InputError("Accepted action schedule differs: $id")
        val unit=input.core.unitDefinitions.values.single();val m=unit.movement
        if(unit.radius!=11.0 || m.moveSpeed!=1.1 || m.moveAccelerationSpeed!=.07 || m.moveDecelerationSpeed!=.17 || m.maxTurnSpeed!=4.1 || m.turnAcceleration!=.25) throw InputError("P001 arithmetic input mismatch")
        if(id=="M07") {
            val lifecycle=metadata.objectAt("lifecycle")
            if(lifecycle.keys!=setOf("firstGenerationSteps","queuedAtStep","queuedTarget","newGenerationSteps") || lifecycle.integer("firstGenerationSteps")!=1L || lifecycle.integer("queuedAtStep")!=2L || position(lifecycle.objectAt("queuedTarget"))!=Position(150.0,110.0) || lifecycle.integer("newGenerationSteps")!=27L) throw InputError("M07 lifecycle differs from accepted constants")
        } else if(metadata["lifecycle"]!=null) throw InputError("Unexpected lifecycle policy")
        val checks=mapOf("M01" to "arrival","M02" to "turn-prefix","M03" to "stop","M04a" to "arrival","M04b" to "arrival","M04c" to "arrival","M05" to "unreachable","M06" to "ownership","M07" to "reopen")
        val expectedCheck = if(id=="M02" && naturalTurn) "turn-prefix-natural-v2" else checks.getValue(id)
        if(metadata.text("check")!=expectedCheck) throw InputError("Unknown P001 relation check")
    }

    private fun runLoads(root:Path,metadata:JsonObject,legal:LoadedScenario,archive:P001Evidence):List<JsonObject> {
        val child=P001Evidence(archive.directory.resolve("loading"));val rows=mutableListOf<JsonObject>();val results=mutableListOf<JsonObject>()
        var recoveryFailure:RelationFailure?=null
        rows+=record("p001-load-sequence-start","sameJvm" to b(true))
        for(case in metadata.arrayAt("cases").map { it.jsonObject }) {
            val id=case.id("caseId"); val input=verifyIdentity(root,case.objectAt("input"));val recovery=verifyIdentity(root,case.objectAt("recovery"))
            fun load(path:Path):ContentResult<*> = when(case.text("loader")) { "unit"->ContentLoader.loadUnitVariant(path);"map"->ContentLoader.loadMap(path);else->throw InputError("Unknown loader") }
            val actual=load(input)
            var failure:RelationFailure?=null
            if(actual !is ContentResult.Failure) failure=RelationFailure("load-rejected",0,"ContentResult.Failure",actual.toString())
            else {
                val e=actual.error;val expected=case.objectAt("expected")
                val value=obj("code" to s(e.code.name),"file" to s(e.file),"section" to nullable(e.section),"field" to nullable(e.field),"objectRef" to nullable(e.objectRef),"reason" to s(e.reason))
                rows+=record("p001-load-result","caseId" to s(id),"phase" to s("negative"),"error" to value)
                for(key in listOf("code","section","field","objectRef")) if(value[key]!=expected.required(key)) failure=failure?:RelationFailure("load-location",rows.lastIndex,expected.toString(),value.toString())
                if(e.file!=rootPath(root,expected.text("file")).toString() || e.reason.isBlank() || expected["reasonNonEmpty"]!=b(true)) failure=failure?:RelationFailure("load-location",rows.lastIndex,expected.toString(),value.toString())
            }
            val recovered=load(recovery)
            val valid=when(recovered) { is ContentResult.Success<*>->when(val value=recovered.value) { is UnitDefinition->value.id=="p001_weaponless_tank";is MapDefinition->value.blocking.blockedCount==33;else->false };else->false }
            rows+=record("p001-load-result","caseId" to s(id),"phase" to s("recovery"),"success" to b(valid),"input" to case.objectAt("recovery"))
            if(!valid) recoveryFailure=recoveryFailure?:RelationFailure("load-recovery",rows.lastIndex,"legal success/id or blockedCount=33",recovered.toString())
            results+=outcome(id,if(failure==null) 0 else 2,failure)
        }
        val finalPath=verifyIdentity(root,metadata.objectAt("finalStep").objectAt("scenario"))
        if(finalPath!=legal.scenarioIdentity.path || metadata.objectAt("finalStep").text("kind")!="legal-initialize-observe-close") throw InputError("Unknown final loading step")
        when(val initialized=SimulationContext.initialize(legal.core)) {
            is InitResult.Rejected->recoveryFailure=recoveryFailure?:RelationFailure("recovery-initialize",rows.lastIndex,"accepted",initialized.error.toString())
            is InitResult.Accepted->{ val context=initialized.context;try {
                val observation=context.observe();rows+=observationRow("legal-final-initial",observation)
                if(observation.units.single().position!=Position(110.0,110.0) || observation.units.single().heading!=0.0 || observation.simulationTime!=0.0 || observation.events.isNotEmpty() || observation.units.single().moveTarget!=null) recoveryFailure=recoveryFailure?:RelationFailure("recovery-initial-state",rows.lastIndex,"M01 clean initial",observation.units.toString())
            } finally { rows+=record("p001-call","call" to s("legal-final-close"),"result" to s(context.close().toString())) } }
        }
        rows+=record("p001-load-sequence-end","complete" to b(true),"negativeCount" to n(6),"recoveryCount" to n(6))
        child.lines("sequence.jsonl",rows)
        val l04Code=aggregate(results.map { it.integer("exitCode").toInt() }+if(recoveryFailure==null) 0 else 2)
        results+=outcome("L04",l04Code,recoveryFailure?:results.firstOrNull { it.integer("exitCode")!=0L }?.objectAt("firstDivergence")?.let { RelationFailure(it.text("ruleId"),it.integer("sampleIndex").toInt(),it.text("expected"),it.text("actual")) })
        child.document("results.json",array(results));return results
    }

    /** Read-only integrity gate. The manifest must list every evidence file, including completion records. */
    fun verifyArchive(directory:Path):Int = try {
        val manifest=readObject(directory.resolve("manifest.json"));manifest.header("p001-suite-manifest")
        if(manifest["complete"]!=b(true) || manifest.integer("expectedCases")!=16L || manifest.integer("observedCases")!=16L) 3
        else {
            val files=manifest.arrayAt("files").map { it.jsonObject }
            val names=files.map { it.text("path") }
            val actual=Files.walk(directory).use { paths->paths.filter { Files.isRegularFile(it) && it!=directory.resolve("manifest.json") }.map { directory.relativize(it).toString().replace('\\','/') }.toList().toSet() }
            if(names.size!=names.toSet().size || names.toSet()!=actual || files.isEmpty() || files.any { ref->
                val path=directory.resolve(ref.text("path")).normalize()
                !path.startsWith(directory.normalize()) || !Files.isRegularFile(path) || sha256(path)!=ref.text("sha256")
            }) 3 else {
                val suitePath=directory.resolve("inputs/suite.json")
                if(Files.exists(suitePath)) {
                    val suite=readObject(suitePath)
                    val relation=readObject(directory.resolve("inputs/M02.relations.json"))
                    if(naturalPathContract(suite)!=naturalPathContract(relation)) throw InputError("Archived suite/M02 path contract mismatch")
                    val check=if(naturalPathContract(suite)) "turn-prefix-natural-v2" else "turn-prefix"
                    if(relation.text("check")!=check) throw InputError("Archived relation check mismatch")
                }
                manifest.integer("exitCode").toInt()
            }
        }
    } catch(_:InputError) { 64 } catch(_:Exception) { 3 }

    private fun naturalPathContract(value:JsonObject):Boolean = when(value["pathContract"]) {
        null -> false
        s("natural-direct.v2") -> true
        else -> throw InputError("Unknown P001 path contract")
    }

    fun aggregate(codes: List<Int>): Int = if(codes.isEmpty()) 3 else listOf(64,4,3,2).firstOrNull { it in codes } ?: 0
    fun samplingCode(samples: List<Observation>, steps: Int, complete: Boolean): Int {
        if(!complete || samples.size!=steps+1 || samples.first().simulationTime!=0.0) return 3
        val world=samples.first().worldId
        if(samples.map { it.sampleId }.distinct().size!=samples.size) return 3
        return if(samples.drop(1).withIndex().any { (i,s) -> s.stepIndex!=i.toLong() || s.simulationTime!=i+1.0 || s.worldId!=world }) 3 else 0
    }
}
