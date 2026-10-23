package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.*
import kotlin.math.ceil
import kotlin.math.hypot

/** Rule wiring on identified historical diagnostics; never a formal batch verdict. */
object MotionV2DiagnosticAnalysis {
    private fun interval(v:MotionV2Math.Interval)=array(listOf(n(v.low),n(v.high)))
    private fun event(e:MotionV2Events.Event?)=e?.let { obj("intervalQ" to interval(it.interval),"callIndex" to n(it.index),"confirmedCallIndex" to n(it.confirmedIndex)) }
    private fun metric(m:MotionV2Sampling.Metric?)=m?.let { obj("expectedCount" to n(it.expectedCount),"status" to s(it.status.name),
        "rmse" to it.value?.rmse?.let(::n),"maximum" to it.value?.maximum?.let(::n),"count" to it.value?.count?.let(::n),
        "fullRmseLowerBound" to it.value?.fullRmseLowerBound?.let(::n)) }
    private fun side(f:MotionV2DiagnosticAdapter.Facts):JsonObject {
        val inputs=f.inputs.indices.map { i->val d=MotionV2Events.deceleration(f.calls,i.toLong())
            obj("inputSequence" to n(i),"consumedQ" to n(f.inputs[i]),
                "quantizationEntryQ" to n(f.consumptions[i].quantizationEntryQ),"consumptionEvent" to event(f.consumptions[i].event),
                "firstMove" to event(MotionV2Events.firstMove(f.calls,i.toLong())),
                "deceleration" to event(d.event),"firstDecelerationCandidate" to event(d.firstCandidate),"decelerationUnavailable" to d.unavailable?.let(::s)) }
        fun lengthThrough(index:Int)=f.calls.take(index+1).sumOf { hypot(it.x-it.beforeX,it.y-it.beforeY) }
        val clears=f.clears.map { c->val tail=MotionV2Events.stableTail(f.calls,c.input,c.event)
            obj("cause" to s(c.cause.name),"inputSequence" to n(c.input),"clear" to event(c.event),"tail" to tail?.let {
                obj("settle" to event(it.event),"fixedPosition" to array(listOf(n(it.fixedX),n(it.fixedY))),
                    "tailEndQ" to it.endIndex?.let { index->n(f.calls[index].exitQ) },"eighteenQWithinBudget" to b(it.eighteenQWithinBudget),
                    "eighteenQStable" to it.eighteenQStable?.let(::b),"fullRawFutureStable" to b(it.fullRawFutureStable),
                    "clearLifecycleIntact" to b(it.clearLifecycleIntact)) },
                "endpointDiagnosticsOnly" to obj("normalClearLength" to n(lengthThrough(c.event.index)),
                    "settleStartLength" to tail?.let { n(lengthThrough(it.event.index)) },
                    "stableTailEndLength" to tail?.endIndex?.let { n(lengthThrough(it)) },
                    "rawCapturedLength" to n(lengthThrough(f.calls.lastIndex)),
                    "clearDurationIntervalQ" to interval(MotionV2Math.Interval(c.event.interval.low-f.inputs[c.input.toInt()],c.event.interval.high-f.inputs[c.input.toInt()])),
                    "settleDurationIntervalQ" to tail?.let { interval(MotionV2Math.Interval(it.event.interval.low-f.inputs[c.input.toInt()],it.event.interval.high-f.inputs[c.input.toInt()])) })) }
        val first=f.points.first()
        val maxDrift=f.points.maxOf { hypot(it.x-first.x,it.y-first.y) }
        return obj("rawPointCount" to n(f.points.size),"callCount" to n(f.calls.size),"lastQ" to n(f.points.last().q),
            "inputs" to array(inputs),"clears" to array(clears),"maximumDisplacementFromInitial" to n(maxDrift))
    }
    fun analyze(planPath: Path,output: Path):JsonObject {
        val plan=OriginalMotionComparison.read(planPath)
        plan.header("motion-v2-technical-analysis-plan")
        require(plan.text("purpose")=="rule-wiring-over-qualified-diagnostics-only" && plan.required("formalCompatibility")==b(false) &&
            plan.required("approvedStandard")==JsonNull && plan.required("wholeVersionFrozen")==b(false))
        val identities=mutableListOf<JsonObject>()
        fun bound(r:JsonObject):Path {
            val p=Path.of(r.text("path"));require(p.isAbsolute && Files.isRegularFile(p) && Files.size(p)==r.integer("bytes") && sha256(p)==r.text("sha256"))
            identities+=r;return p
        }
        fun read(r:JsonObject)=OriginalMotionComparison.read(bound(r))
        read(plan.objectAt("catalog"));val candidate=read(plan.objectAt("candidateContract"))
        val pairs=plan.arrayAt("pairs").map { it.jsonObject }
        val caseIds=pairs.map { it.text("caseId") }
        require(caseIds==listOf("OM00","OM01E","OM04","OM05","OM06","OM07","OM08") ||
            caseIds==listOf("OM01W","OM02S","OM02N","OM03SE","OM03NW") || caseIds==listOf("OM08"))
        val results=pairs.map { pair->
            val id=pair.text("caseId");val coreDoc=read(pair.objectAt("core"));val originalDoc=read(pair.objectAt("original"))
            val request=read(pair.objectAt("request"));val receipt=read(pair.objectAt("receipt"));val archive=read(pair.objectAt("runtimeArchive"))
            require(coreDoc.text("caseId")==id && originalDoc.text("caseId")==id && request.text("caseId")==id)
            val run=receipt.arrayAt("runs").map { it.jsonObject }.single { it.text("caseId")==id }
            require(receipt.required("formalCompatibility")==b(false) && run.integer("exitCode")==0L && run.required("error")==JsonNull &&
                run.objectAt("request").text("sha256")==pair.objectAt("request").text("sha256") &&
                run.objectAt("output").text("sha256")==pair.objectAt("core").text("sha256"))
            val archivedRun=archive.arrayAt("runs").map { it.jsonObject }.single { it.text("caseId")==id }
            require(archive.objectAt("receipt").text("sha256")==pair.objectAt("receipt").text("sha256") &&
                archivedRun.objectAt("output").text("sha256")==pair.objectAt("core").text("sha256") &&
                archivedRun.objectAt("request").text("sha256")==pair.objectAt("request").text("sha256"))
            require(coreDoc.objectAt("provenance").objectAt("request").text("sha256")==pair.objectAt("request").text("sha256") &&
                request.objectAt("original").text("sha256")==pair.objectAt("original").text("sha256"))
            val artifacts=archive.arrayAt("artifacts").map { it.jsonObject }
            for(r in coreDoc.objectAt("provenance").arrayAt("runtimeArtifacts").map { it.jsonObject }) {
                val a=artifacts.single { it.objectAt("source").text("sha256")==r.text("sha256") }
                bound(a.objectAt("preserved")) // Never substitute today's rebuilt JAR for a captured runtime.
            }
            val core=MotionV2DiagnosticAdapter.core(coreDoc);val original=MotionV2DiagnosticAdapter.original(originalDoc)
            require(core.inputs.size==original.inputs.size && core.inputs.indices.all { core.inputs[it]==ceil(original.inputs[it]) })
            val inputComparisons=core.consumptions.zip(original.consumptions).map { (c,o)->
                require(c.sequence==o.sequence && c.action==o.action)
                require(coreDoc.arrayAt("inputs")[c.sequence.toInt()].jsonObject.objectAt("raw").required("originalInput")==
                    originalDoc.arrayAt("inputs")[o.sequence.toInt()])
                val comparison=MotionV2Math.interval(c.event.interval,o.event.interval,3.0)
                obj("inputSequence" to n(c.sequence),"action" to s(c.action),"status" to s(comparison.status.name),
                    "minimumDifferenceQ" to n(comparison.minimumDifference),"maximumDifferenceQ" to n(comparison.maximumDifference))
            }
            val horizon=minOf(600.0,core.points.last().q,original.points.last().q)
            val numerical=if(id !in setOf("OM07","OM08"))MotionV2Sampling.evaluate(core.points,original.points,original.inputs,horizon,id=="OM00")else null
            obj("caseId" to s(id),"formalCompatibility" to b(false),"core" to side(core),"original" to side(original),
                "inputConsumptionComparisons" to array(inputComparisons),
                "diagnosticCommonHorizonQ" to n(horizon),"numeric" to numerical?.let { result->obj("position" to metric(result.position),
                    "heading" to metric(result.heading),"speed" to metric(result.speed),
                    "unavailablePoints" to array(result.points.filter { it.unavailable.isNotEmpty() }.map { obj("requestedQ" to n(it.requestedQ),"reasons" to array(it.unavailable.map(::s))) }),
                    "notApplicableSpeedWindows" to array(result.notApplicableWindows.map(::n))) })
        }
        // This document deliberately has no aggregate compatibility status, approved
        // wrapper, OM07 endpoint choice, or claim that seven old pairs are 36 fresh runs.
        require(candidate.isNotEmpty())
        require(identities.all { sha256(Path.of(it.text("path")))==it.text("sha256") })
        val result=record("motion-v2-diagnostic-rule-analysis","formalCompatibility" to b(false),"approvedStandard" to JsonNull,
            "wholeVersionFrozen" to b(false),"purpose" to s("rule-wiring-over-qualified-diagnostics-only"),
            "plan" to obj("path" to s(planPath.toAbsolutePath().toString()),"sha256" to s(sha256(planPath))),
            "identities" to array(identities.distinct()),"cases" to array(results))
        Files.writeString(output,result.toString()+"\n",StandardOpenOption.CREATE_NEW)
        return result
    }
}

fun main(args:Array<String>) {
    require(args.size==4 && args[0]=="--plan" && args[2]=="--output")
    val result=MotionV2DiagnosticAnalysis.analyze(Path.of(args[1]),Path.of(args[3]))
    println("Analyzed ${result.arrayAt("cases").size} historical diagnostic pairs; no formal compatibility verdict")
}
