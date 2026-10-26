package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.math.ceil

/** Technical replay only. It accepts no standard approval and produces no compatibility judgment. */
object MotionDiagnosticCapture {
    internal fun submitAndRecord(context:SimulationContext,action:Action,original:JsonObject,sequence:Int,q:Int,
                                 attempts:MutableList<JsonElement>):ActionResult.Accepted {
        require(action !is PatrolAction) { "Movement diagnostic capture does not support PatrolAction" }
        require(action !is ProduceAction && action !is CancelProductionAction && action !is UpgradeAction && action !is CancelUpgradeAction) {
            "Movement diagnostic capture does not support production actions"
        }
        val before=context.observe().json()
        val submitted=context.submit(action)
        val issued=obj("actionId" to s(action.actionId),"actorPlayerId" to s(action.actorPlayerId),"unitId" to s(action.unitId),
            "worldId" to s(action.world.id),"atStep" to action.atStep?.let(::n),
            "kind" to s(when(action) { is MoveAction -> "move";is StopAction -> "stop";is SelectAction -> "select"
                is AttackAction -> error("Movement diagnostic capture does not support AttackAction")
                is PatrolAction -> error("Movement diagnostic capture does not support PatrolAction")
                is ProduceAction, is CancelProductionAction, is UpgradeAction, is CancelUpgradeAction -> error("Movement diagnostic capture does not support economy actions") }),
            "target" to (action as? MoveAction)?.target?.json())
        attempts+=obj("attempted" to b(true),"sequence" to n(sequence),"q" to n(q),
            "actualSimulationTime" to before.required("simulationTime"),"actualStepIndex" to before.required("stepIndex"),
            "issuedAction" to issued,"originalInput" to original,"before" to before,"after" to context.observe().json(),
            "submitResult" to submitted.json())
        require(submitted is ActionResult.Accepted)
        return submitted
    }

    fun capture(requestPath: Path, output: Path): JsonObject = captureInternal(requestPath,output,null)

    fun captureWithInputs(requestPath:Path,output:Path,manifestPath:Path):JsonObject =
        captureWithInputs(requestPath,output,manifestPath,null)

    /** The observer is for explicit qualification fault controls only, never selected by CLI or input JSON. */
    fun captureWithInputs(requestPath:Path,output:Path,manifestPath:Path,validationObserver:((Int)->Unit)?):JsonObject {
        require(!Files.exists(output,LinkOption.NOFOLLOW_LINKS)) { "Capture output already exists" }
        val attempt=InputAttempt(requestPath,manifestPath,validationObserver)
        try {
            attempt.requestRef=MotionV2CoreInputs.reference(requestPath)
            attempt.manifestRef=MotionV2CoreInputs.reference(manifestPath)
            attempt.runtime=runtimeArtifacts()
            attempt.loaded=MotionV2CoreInputs.load(manifestPath,requestPath)
            return captureInternal(requestPath,output,attempt)
        } catch(error:Exception) {
            attempt.saveFailure(output,error)
            throw error
        }
    }

    private fun captureInternal(requestPath: Path, output: Path, attempt:InputAttempt?): JsonObject {
        attempt?.stage="request"
        val request = OriginalMotionComparison.read(requestPath)
        val requestIdentity=obj("path" to s(requestPath.toAbsolutePath().toString()),"sha256" to s(sha256(requestPath)))
        request.header("motion-diagnostic-request")
        OriginalMotionComparison.only(request, setOf("kind", "schemaVersion", "formalCompatibility", "caseId", "runId", "preparedInputs", "catalog", "original"))
        require(request["formalCompatibility"] == b(false))
        val caseId = request.text("caseId")
        require(caseId in setOf("OM00", "OM01E", "OM01W", "OM02S", "OM02N", "OM03SE", "OM03NW", "OM04", "OM05", "OM06", "OM07", "OM08"))
        val runId = request.text("runId"); require(UUID.fromString(runId).toString() == runId)
        val identities = mutableListOf<JsonObject>()
        fun bound(ref: JsonObject): Path {
            val p = Path.of(ref.text("path"))
            require(p.isAbsolute && Files.isRegularFile(p) && sha256(p) == ref.text("sha256"))
            identities += obj("path" to s(p.toString()), "sha256" to s(sha256(p)))
            return p
        }
        attempt?.stage="original"
        val original = OriginalMotionComparison.read(bound(request.objectAt("original")))
        original.header("motion-evidence")
        require(original.text("sourceKind") in setOf("native-diagnostic-scene-runtime", "native-local-clear-diagnostic-scene-runtime") && original.text("role") == "original")
        require(original["complete"] == b(true) && original.integer("exitCode") == 0L && original.text("caseId") == caseId)
        MotionV2DiagnosticAdapter.original(original) // Includes schema2 sidecar/input binding for new P observations.
        attempt?.stage="catalog-and-prepared"
        val catalog = OriginalMotionComparison.read(bound(request.objectAt("catalog")))
        val case = catalog.arrayAt("cases").map { it.jsonObject }.single { it.text("id") == caseId }
        val prepared = OriginalMotionComparison.read(bound(request.objectAt("preparedInputs")))
        for (ref in prepared.arrayAt("inputs")) bound(ref.jsonObject)
        val mapRef = prepared.arrayAt("cases").map { it.jsonObject }.single { it.text("caseId") == caseId }.objectAt("core")
        fun <T> content(r: ContentResult<T>): T = when (r) {
            is ContentResult.Success -> r.value
            is ContentResult.Failure -> throw CoreFailure("Diagnostic content load failed: ${r.error}")
        }
        attempt?.stage="loaded-inputs"
        val mapPath=bound(mapRef);val unitPath=bound(prepared.objectAt("coreUnit"))
        val loaded=attempt?.loaded
        if(loaded!=null) require(loaded.evidence.required("caseId")==request.required("caseId") &&
            loaded.evidence.required("runId")==request.required("runId") && loaded.evidence.required("request")==attempt.requestRef &&
            loaded.evidence.required("map")==mapRef && loaded.evidence.required("unitInput")==prepared.required("coreUnit"))
        val map = loaded?.map ?: content(ContentLoader.loadMap(mapPath))
        val definition = loaded?.unit ?: content(ContentLoader.loadUnitVariant(unitPath))
        val runtime=attempt?.runtime ?: runtimeArtifacts()
        require(runtime.errors.isEmpty()) { "Runtime artifacts unavailable: ${runtime.errors}" }
        val initial = case["start"] as? JsonArray ?: catalog.objectAt("common").arrayAt("start")
        val scenario = Scenario("eng017.diagnostic.$caseId.$runId", map, mapOf("tank" to definition), listOf(Player("p1", PlayerControl.LOCAL)),
            listOf(UnitSpawn("u1", "p1", "tank", Position(initial[0].jsonPrimitive.double, initial[1].jsonPrimitive.double), case.number("coreHeading"))),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0))
        val horizon = ceil(original.arrayAt("samples").last().jsonObject.number("q")).toInt()
        require(horizon in 18..604)
        val commands = original.arrayAt("inputs").map { it.jsonObject }
        attempt?.stage="initialize"
        val initialized=SimulationContext.initialize(scenario)
        attempt?.initialization=when(initialized) {
            is InitResult.Accepted -> obj("status" to s("accepted"),"worldId" to s(initialized.context.world.id))
            is InitResult.Rejected -> obj("status" to s("rejected"),"error" to obj("code" to s(initialized.error.code),"message" to s(initialized.error.message)))
        }
        val context = (initialized as? InitResult.Accepted)?.context ?: throw CoreFailure("Diagnostic initialization rejected")
        val samples = mutableListOf<JsonElement>(); val inputs = mutableListOf<JsonElement>()
        val motionInputs = mutableListOf<JsonElement>(); var sequence = -1
        var failure: Exception? = null
        val inputIdentityErrors=mutableListOf<String>();val runtimeIdentityErrors=mutableListOf<String>()
        fun inputGuard() {
            val errors=loaded?.verifyStable().orEmpty()
            inputIdentityErrors+=errors
            if(errors.isNotEmpty()) throw InputError("Qualified inputs changed before the next operation")
        }
        fun record(q: Int, observation: Observation, kind: String) {
            require(observation.simulationTime == q.toDouble() && observation.units.size == 1)
            val u = observation.units.single()
            samples += obj("index" to n(samples.size), "q" to n(q), "phase" to n(0), "inputSequence" to n(sequence),
                "state" to obj("id" to s(u.unitId), "owner" to s(u.ownerPlayerId), "definition" to s(u.typeId), "x" to n(u.position.x), "y" to n(u.position.y), "heading" to n(u.heading), "target" to u.moveTarget?.json()),
                "raw" to obj("kind" to s(kind), "observation" to observation.json()))
            attempt?.stage="observed:$q:$kind"
            attempt?.observer?.invoke(q)
        }
        try {
            inputGuard()
            context.enableMotionDiagnostics(context.world)
            record(0, context.observe(), "initial")
            for (q in 0..horizon) {
                inputGuard()
                if (q > 0) {
                    attempt?.stage="advance:$q"
                    val observation = context.advance(Step(q.toLong() - 1, 1.0, "sim-tick"))
                    val motion = context.drainMotionDiagnostics(context.world).single()
                    require(motion.stepIndex == q.toLong() - 1 && motion.unitId == "u1")
                    require(motion.finalPosition == observation.units.single().position)
                    motionInputs += motion.diagnosticJson()
                    record(q, observation, "advance")
                }
                for ((i, c) in commands.withIndex().filter { ceil(it.value.number("q")).toInt() == q }) {
                    inputGuard()
                    attempt?.stage="submit:$i"
                    require(i == sequence + 1)
                    val before = context.observe().json()
                    val action = when (c.text("action")) {
                        "move" -> MoveAction("diag.$i", "p1", "u1", position(c.objectAt("target")), context.world)
                        "stop" -> StopAction("diag.$i", "p1", "u1", context.world)
                        else -> throw InputError("Unsupported diagnostic action")
                    }
                    val submitted = if(attempt==null) context.submit(action).also { require(it is ActionResult.Accepted) }
                        else submitAndRecord(context,action,c,i,q,attempt.inputAttempts)
                    sequence = i
                    inputs += obj("sequence" to n(i), "q" to n(q), "action" to c.required("action"), "target" to c.required("target"),
                        "raw" to obj("before" to before, "after" to context.observe().json(), "submitResult" to submitted.json(), "originalInput" to c))
                    record(q, context.observe(), "input-post")
                }
            }
            require(inputs.size == commands.size)
            require(identities.all { sha256(Path.of(it.text("path"))) == it.text("sha256") }) { "Diagnostic input drift" }
        } catch (error: Exception) { failure = error;attempt?.let { it.failureStage=it.stage } } finally {
            try { context.close() } catch(closeError:Exception) {
                val first=failure
                if(first!=null) first.addSuppressed(closeError) else {
                    failure=closeError;attempt?.failureStage="close"
                }
            }
            inputIdentityErrors+=loaded?.verifyStable().orEmpty()
            runtimeIdentityErrors+=runtime.verifyStable()
            if(failure==null && (inputIdentityErrors.isNotEmpty() || runtimeIdentityErrors.isNotEmpty())) {
                failure=InputError("Capture source identity changed; observations retained")
                attempt?.failureStage="post-source-check"
            }
        }
        val artifactRows=runtime.artifacts
        var provenance=obj("request" to requestIdentity,
            "inputs" to array(identities),"runtimeArtifacts" to array(artifactRows),"javaVersion" to s(System.getProperty("java.version")),"pid" to n(ProcessHandle.current().pid()))
        if(attempt!=null) provenance=JsonObject(provenance+mapOf("inputObservation" to checkNotNull(loaded).evidence,
            "inputIdentityErrors" to array(inputIdentityErrors.distinct().map(::s)),"runtimeIdentityErrors" to array(runtimeIdentityErrors.distinct().map(::s)),
            "validationObserverUsed" to b(attempt.observer!=null),"initialization" to checkNotNull(attempt.initialization),
            "inputAttempts" to array(attempt.inputAttempts)))
        val document = obj("kind" to s("motion-diagnostic-evidence"), "schemaVersion" to n(1), "formalCompatibility" to b(false), "role" to s("core"),
            "caseId" to s(caseId), "runId" to s(runId), "sourceKind" to s(if(attempt==null) "core-diagnostic-runtime" else "core-input-bound-diagnostic-runtime"), "complete" to b(failure == null), "exitCode" to n(if (failure == null) 0 else 4),
            "captureError" to failure?.let { obj("type" to s(it.javaClass.name), "message" to it.message?.let(::s)) },
            "samples" to array(samples), "inputs" to array(inputs), "recordCount" to n(samples.size),
            "motionInputSchemaVersion" to n(1), "motionInputs" to array(motionInputs),
            "provenance" to provenance)
        attempt?.stage="write-raw"
        attempt?.partialRaw=document
        val serialized=document.toString()+"\n"
        try {
            Files.writeString(output, serialized, StandardOpenOption.CREATE_NEW)
            if(attempt!=null) {
                val ref=MotionV2CoreInputs.reference(output)
                val bytes=serialized.toByteArray(Charsets.UTF_8)
                require(ref.integer("bytes")==bytes.size.toLong() && ref.text("sha256")==digest(bytes)) { "Written raw identity differs from captured document" }
                attempt.rawRef=ref
                attempt.rawWriteSucceeded=true
            }
        }
        catch(writeError:Exception) {
            failure?.let { if(attempt!=null) { it.addSuppressed(writeError);throw it } }
            throw writeError
        }
        failure?.let { if(attempt!=null) throw it else throw CoreFailure("Diagnostic failed; partial evidence retained: $output") }
        return document
    }

    private class RuntimeArtifacts(val artifacts:List<JsonObject>,val errors:List<String>) {
        fun verifyStable():List<String> = errors+artifacts.mapNotNull { r ->
            try { val p=Path.of(r.text("path"));require(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS) && sha256(p)==r.text("sha256"));null }
            catch(e:Exception) { "${r["class"]}: ${e.message}" }
        }
    }
    private fun runtimeArtifacts():RuntimeArtifacts {
        val rows=mutableListOf<JsonObject>();val errors=mutableListOf<String>()
        for(type in listOf(MotionDiagnosticCapture::class.java,SimulationContext::class.java,Json::class.java)) try {
            val p=Path.of(type.protectionDomain.codeSource.location.toURI())
            require(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)) { "Run the diagnostic from built, identifiable JARs: $p" }
            rows+=obj("class" to s(type.name),"path" to s(p.toString()),"sha256" to s(sha256(p)))
        } catch(e:Exception) { errors+="${type.name}: ${e.message}" }
        return RuntimeArtifacts(rows,errors)
    }
    private class InputAttempt(val request:Path,val manifest:Path,val observer:((Int)->Unit)?) {
        var requestRef:JsonObject?=null;var manifestRef:JsonObject?=null
        var loaded:MotionV2CoreInputs.Loaded?=null;var runtime:RuntimeArtifacts?=null
        var initialization:JsonObject?=null;var stage="input-load";var failureStage:String?=null
        var partialRaw:JsonObject?=null
        var rawWriteSucceeded=false;var rawRef:JsonObject?=null
        val inputAttempts=mutableListOf<JsonElement>()
        fun saveFailure(output:Path,error:Exception) {
            // A sidecar is a failed attempt record, never substitute normal raw.
            val artifact=runtime ?: runtimeArtifacts()
            val rawWritten=rawWriteSucceeded && rawRef!=null
            val row=record("motion-core-input-capture-failure","formalCompatibility" to b(false),"stage" to s(failureStage ?: stage),
                "requestPath" to s(request.toAbsolutePath().toString()),"manifestPath" to s(manifest.toAbsolutePath().toString()),
                "request" to requestRef,"manifest" to manifestRef,"inputObservation" to loaded?.evidence,
                "caseId" to loaded?.evidence?.get("caseId"),"runId" to loaded?.evidence?.get("runId"),
                "runtimeArtifacts" to array(artifact.artifacts),"runtimeIdentityErrors" to array(artifact.verifyStable().map(::s)),
                "inputIdentityErrors" to array(loaded?.verifyStable().orEmpty().map(::s)),"initialization" to initialization,
                "inputAttempts" to array(inputAttempts),"rawPath" to s(output.toAbsolutePath().toString()),"raw" to rawRef,
                "validationObserverUsed" to b(observer!=null),"rawWritten" to b(rawWritten),"partialRaw" to if(rawWritten) JsonNull else partialRaw,
                "error" to obj("type" to s(error.javaClass.name),"message" to error.message?.let(::s)),
                "suppressed" to array(error.suppressed.map { obj("type" to s(it.javaClass.name),"message" to it.message?.let(::s)) }))
            try { Files.writeString(output.resolveSibling(output.fileName.toString()+".input-failure.json"),row.toString()+"\n",StandardOpenOption.CREATE_NEW) }
            catch(secondary:Exception) { error.addSuppressed(secondary) }
        }
    }
}

private fun MotionStepSample.diagnosticJson(): JsonObject = obj(
    "worldId" to s(worldId), "stepIndex" to n(stepIndex), "unitId" to s(unitId),
    "ownerPlayerId" to s(ownerPlayerId), "typeId" to s(typeId), "timeBefore" to n(timeBefore), "delta" to n(delta),
    "originMoveId" to originMoveId?.let(::s), "positionBefore" to positionBefore.json(),
    "headingBefore" to n(headingBefore), "speedBefore" to n(speedBefore), "angularVelocityBefore" to n(angularVelocityBefore),
    "commandTargetBefore" to commandTargetBefore?.json(), "normalCompletion" to b(normalCompletion),
    "steeringInvoked" to b(steeringInvoked), "steeringAim" to steeringAim?.json(),
    "finalLocalPoint" to finalLocalPoint?.let(::b), "steeringDifference" to steeringDifference?.let(::n),
    "targetFraction" to n(targetFraction), "integratedHeading" to n(integratedHeading), "integratedSpeed" to n(integratedSpeed),
    "candidate" to candidate.json(), "terrainResolved" to terrainResolved?.json(), "terrainDisposition" to s(terrainDisposition.name),
    "finalPosition" to finalPosition.json(), "finalHeading" to n(finalHeading), "finalSpeed" to n(finalSpeed),
    "finalAngularVelocity" to n(finalAngularVelocity), "localProgressBefore" to n(localProgressBefore),
    "localProgressAfter" to n(localProgressAfter), "correctionTimeBefore" to n(correctionTimeBefore), "correctionTimeAfter" to n(correctionTimeAfter))

fun main(args: Array<String>) {
    require(args.size in setOf(4,6) && args[0] == "--request" && args[2] == "--output")
    val result = if(args.size==4) MotionDiagnosticCapture.capture(Path.of(args[1]), Path.of(args[3])) else {
        require(args[4]=="--source-manifest")
        MotionDiagnosticCapture.captureWithInputs(Path.of(args[1]),Path.of(args[3]),Path.of(args[5]))
    }
    println("Captured ${result["recordCount"]} diagnostic rows; no compatibility judgment")
}
