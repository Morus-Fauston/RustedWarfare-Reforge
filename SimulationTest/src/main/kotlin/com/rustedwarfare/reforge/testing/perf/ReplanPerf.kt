package com.rustedwarfare.reforge.testing.perf

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.path.*
import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.testing.json
import com.rustedwarfare.reforge.testing.MotionCenterGeometry
import kotlinx.serialization.json.*
import java.lang.management.ManagementFactory

data class ReplanStep(
    val step: Int,
    val projectionNanos: Long,
    val submitNanos: Long,
    val advanceNanos: Long,
    val drainNanos: Long,
    val samples: List<PathSearchSample>,
    val gameplay: JsonObject,
)

data class ReplanBatch(
    val count: Int,
    val retention: TerrainRetention,
    val diagnostics: Boolean,
    val steps: List<ReplanStep>,
    val wallNanos: Long,
    val heapBefore: Long,
    val heapAfter: Long,
    val gcCount: Long,
    val gcMillis: Long,
    val typeId: String,
    val geometryVersion: String = PathPerfBatch.DEVELOPMENT_GEOMETRY,
)

object ReplanPerf {
    const val DEVELOPMENT_CONTRACT_SHA256 = "92d67ebdbec9937e4322d69e2f8c916157ef7f18abab09d727371c2e2d3d1a90"
    internal fun identityFields(version: String = PathPerfBatch.DEVELOPMENT_GEOMETRY): JsonObject {
        check(version == PathPerfBatch.DEVELOPMENT_GEOMETRY) { "Unsupported development replanning geometry version" }
        return buildJsonObject {
            put("geometryVersion",version);put("developmentOnly",true)
            put("qualificationScope","development-replanning-only");put("fullTerrainWritebackQualified",false)
            put("validatorContractSha256",DEVELOPMENT_CONTRACT_SHA256)
        }
    }
    fun measure(unit: UnitDefinition, count: Int, retention: TerrainRetention, diagnostics: Boolean): ReplanBatch {
        require(count in listOf(1, 10, 50))
        require(unit.radius == 11.0)
        val blocked = BooleanArray(32 * 24) { it % 32 == 15 || it == 33 }
        val input = Scenario("rp-v1", MapDefinition(32, 24, 20, 20, emptyList(), IntArray(768), BlockingGrid(32, 24, blocked)),
            mapOf(unit.id to unit), listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
            (0 until count).map { UnitSpawn("rp-$it", "p1", unit.id, Position(50.0 + 20 * (it % 3), 150.0 + 20 * (it % 8)), 90.0) },
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0))
        val context = (SimulationContext.initialize(input) as? InitResult.Accepted)?.context ?: error("RP initialization rejected")
        val steps = mutableListOf<ReplanStep>()
        try {
            context.enableKnowledge(context.world, KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, false))
            if (diagnostics) context.enablePathDiagnostics(context.world)
            val gcBefore = gc()
            val heapBefore = heap()
            val begin = System.nanoTime()
            val full = (0 until 24).map { VisibleTerrainCell(15, it, true) }
            val partial = (5..18).map { VisibleTerrainCell(15, it, true) }
            for (step in 0..7) {
                val projectionStart = System.nanoTime()
                when (step) {
                    1 -> {
                        context.acceptVisibleTerrain(context.world, "p2", full)
                        context.acceptVisibleTerrain(context.world, "p1", listOf(VisibleTerrainCell(1, 1, true)))
                    }
                    2, 3 -> context.acceptVisibleTerrain(context.world, "p1", partial)
                    4 -> context.acceptVisibleTerrain(context.world, "p1", emptyList())
                    6, 7 -> context.acceptVisibleTerrain(context.world, "p1", full)
                }
                val projectionNanos = System.nanoTime() - projectionStart
                val submitStart = System.nanoTime()
                if (step == 0 || step == 5) for (index in 0 until count) {
                    val accepted = context.submit(MoveAction("rp-$step-$index", "p1", "rp-$index",
                        Position(550.0, 150.0 + 20 * (index % 8)), context.world, step.toLong()))
                    check(accepted is ActionResult.Accepted && accepted.queuedForStep == step.toLong()) { "Move rejected: $accepted" }
                }
                val submitNanos = System.nanoTime() - submitStart
                val advanceStart = System.nanoTime()
                val observation = context.advance(Step(step.toLong(), 1.0, "sim-tick"))
                val advanceNanos = System.nanoTime() - advanceStart
                val drainStart = System.nanoTime()
                val samples = if (diagnostics) context.drainPathDiagnostics(context.world) else emptyList()
                val drainNanos = System.nanoTime() - drainStart
                val gameplay = buildJsonObject {
                    put("observation", JsonObject(observation.json().filterKeys { it !in setOf("worldId", "sampleId") }))
                    putJsonArray("knowledge") {
                        for (player in listOf("p1", "p2")) {
                            val snapshot = context.knowledgeSnapshot(context.world, player)
                            add(buildJsonObject {
                                put("player", player); put("revision", snapshot.revision)
                                putJsonArray("cells") { snapshot.cells.forEach { cell -> add(buildJsonObject {
                                    put("visible", cell.visible)
                                    put("blocked", cell.lastKnownBlocked?.let(::JsonPrimitive) ?: JsonNull)
                                }) } }
                            })
                        }
                    }
                }
                steps += ReplanStep(step, projectionNanos, submitNanos, advanceNanos, drainNanos, samples, gameplay)
            }
            val elapsed = System.nanoTime() - begin
            val heapAfter = heap()
            val gcAfter = gc()
            return ReplanBatch(count, retention, diagnostics, steps, elapsed, heapBefore, heapAfter,
                if (gcBefore.first < 0 || gcAfter.first < 0) -1 else gcAfter.first - gcBefore.first,
                if (gcBefore.second < 0 || gcAfter.second < 0) -1 else gcAfter.second - gcBefore.second, unit.id)
        } finally { context.close() }
    }

    fun validate(batch: ReplanBatch) {
        identityFields(batch.geometryVersion)
        check(batch.count in listOf(1, 10, 50))
        val expectedIds = (0 until batch.count).map { "rp-$it" }.toSet()
        check(batch.steps.map { it.step } == (0..7).toList()) { "Missing RP steps" }
        val expected = listOf(batch.count, 0, batch.count, 0, 0, batch.count, batch.count, 0)
        for ((index, step) in batch.steps.withIndex()) {
            if (batch.diagnostics) {
                check(step.samples.size == expected[index]) { "step $index sample count ${step.samples.size} != ${expected[index]}" }
                check(step.samples.map { it.unitId }.distinct().size == expected[index]) { "Duplicate searched units" }
                if (expected[index] > 0) check(step.samples.map { it.unitId }.toSet() == expectedIds) { "Wrong searched units" }
                val reason = if (index in listOf(0, 5)) PathSearchReason.USER_MOVE else PathSearchReason.KNOWLEDGE_REPLAN
                check(step.samples.all { it.actualSearchCalls == 1L && it.reason == reason && it.executionStep == index.toLong() && it.usedPlayerKnowledge }) { "Wrong actual search provenance" }
                check(step.samples.all { it.outcome == if (index == 6) PathSearchOutcome.UNREACHABLE else PathSearchOutcome.FOUND }) { "Wrong path outcome" }
                for(sample in step.samples) {
                    val unitIndex=sample.unitId.removePrefix("rp-").toInt()
                    val originStep=if(index<5)0 else 5
                    val start=if(index==0)Position(50.0+20*(unitIndex%3),150.0+20*(unitIndex%8)) else {
                        val prior=batch.steps[index-1].gameplay.getValue("observation").jsonObject.getValue("units").jsonArray
                            .single { it.jsonObject.getValue("unitId").jsonPrimitive.content==sample.unitId }.jsonObject.getValue("position").jsonObject
                        Position(prior.getValue("x").jsonPrimitive.double,prior.getValue("y").jsonPrimitive.double)
                    }
                    check(sample.actionId=="rp-$originStep-$unitIndex" && sample.scheduledStep==originStep.toLong() &&
                        sample.preservedPrefixPointCount==0 && sample.searchStart==start &&
                        sample.searchTarget==Position(550.0,150.0+20*(unitIndex%8))) { "Wrong actual search input identity" }
                }
            } else check(step.samples.isEmpty())
            val observation = step.gameplay.getValue("observation").jsonObject
            val units = observation.getValue("units").jsonArray
            check(units.size == batch.count && units.map { it.jsonObject.getValue("unitId").jsonPrimitive.content }.toSet() == expectedIds)
            check(observation.getValue("stepIndex").jsonPrimitive.int == index)
            check(observation.getValue("simulationTime").jsonPrimitive.double == index + 1.0)
            val ownerCells=step.gameplay.getValue("knowledge").jsonArray.single {
                it.jsonObject.getValue("player").jsonPrimitive.content=="p1"
            }.jsonObject.getValue("cells").jsonArray
            check(ownerCells.size==768) { "Incomplete owner knowledge" }
            val knownWalls=ownerCells.mapIndexedNotNull { cell,value ->
                if(value.jsonObject.getValue("blocked").jsonPrimitive.booleanOrNull==true)intArrayOf(cell%32,cell/32)else null
            }
            for (value in units) {
                val u = value.jsonObject
                check(u.getValue("ownerPlayerId").jsonPrimitive.content == "p1" &&
                    u.getValue("typeId").jsonPrimitive.content == batch.typeId) { "Wrong unit ownership/type" }
                val position = u.getValue("position").jsonObject
                val x = position.getValue("x").jsonPrimitive.double
                val y = position.getValue("y").jsonPrimitive.double
                check(x.isFinite() && y.isFinite() && x >= 11.0 && x <= 629.0 && y >= 11.0 && y <= 469.0) { "Actual position outside map" }
                val truthWalls=(0 until 24).map { intArrayOf(15,it) }+listOf(intArrayOf(1,1))
                check(MotionCenterGeometry.centerPath(listOf(Position(x,y)),truthWalls,32,24,20,20,11.0)) { "Hidden truth collision violated" }
                check(x < 300.0) { "Actual position crossed the full wall" }
                val points = u.getValue("pathPoints").jsonArray.map { point -> point.jsonObject.let {
                    Position(it.getValue("x").jsonPrimitive.double, it.getValue("y").jsonPrimitive.double)
                } }
                step.samples.singleOrNull { it.unitId==u.getValue("unitId").jsonPrimitive.content }?.let {
                    check(it.pathPointCount==points.size) { "Wrong diagnostic path count" }
                }
                if (index >= 6) {
                    check(u.getValue("moveTarget") == JsonNull && points.isEmpty()) { "Unreachable target survived" }
                } else {
                    check(u.getValue("moveTarget") != JsonNull && points.isNotEmpty()) { "Active request lost" }
                    val unitIndex=u.getValue("unitId").jsonPrimitive.content.removePrefix("rp-").toInt()
                    val goal=Position(550.0,150.0+20*(unitIndex%8))
                    val target=u.getValue("moveTarget").jsonObject
                    check(Position(target.getValue("x").jsonPrimitive.double,target.getValue("y").jsonPrimitive.double)==goal && points.last()==goal) { "Wrong active request goal or route tail" }
                    check(MotionCenterGeometry.centerPath(listOf(Position(x,y))+points,knownWalls,32,24,20,20,11.0)) { "Illegal known-terrain path at step $index" }
                }
            }
            fun event(kind:String,id:String)=buildJsonObject { put("kind",kind);put("unitId",id) }
            val expectedEvents=when(index) {
                0,5 -> (0 until batch.count).map { event("movement-started","rp-$it") }
                6 -> expectedIds.sorted().flatMap { listOf(event("movement-cancelled",it),event("path-unreachable",it)) }
                else -> emptyList()
            }
            check(observation.getValue("events").jsonArray==JsonArray(expectedEvents)) { "Wrong per-unit request lifecycle events" }
            val expectedResults=if(index==0 || index==5)(0 until batch.count).map { i->buildJsonObject {
                put("actionId","rp-$index-$i");put("status","accepted");put("queuedForStep",JsonNull)
                put("events",JsonArray(listOf(event("movement-started","rp-$i"))))
            } }else emptyList()
            check(observation.getValue("actionResults").jsonArray==JsonArray(expectedResults)) { "Wrong original request outcomes" }
            if (index in 4..5) {
                val p1 = step.gameplay.getValue("knowledge").jsonArray[0].jsonObject.getValue("cells").jsonArray
                check(p1[10 * 32 + 15].jsonObject.getValue("blocked") ==
                    if (batch.retention == TerrainRetention.MEMORY) JsonPrimitive(true) else JsonNull)
            }
        }
    }

    fun sameGameplay(a: ReplanBatch, b: ReplanBatch): Boolean {
        identityFields(a.geometryVersion);identityFields(b.geometryVersion)
        return a.count == b.count && a.retention == b.retention && a.steps.map { it.gameplay } == b.steps.map { it.gameplay }
    }

    fun sampleJson(sample: PathSearchSample) = buildJsonObject {
        put("actionId", sample.actionId); put("unitId", sample.unitId)
        put("scheduledStep", sample.scheduledStep?.let(::JsonPrimitive) ?: JsonNull)
        put("executionStep", sample.executionStep?.let(::JsonPrimitive) ?: JsonNull)
        put("reason", sample.reason.name); put("outcome", sample.outcome.name)
        put("usedPlayerKnowledge", sample.usedPlayerKnowledge); put("actualSearchCalls", sample.actualSearchCalls)
        put("inputPreparationNanos", sample.inputPreparationNanos); put("searchNanos", sample.searchNanos)
        put("candidatesChecked", sample.candidatesChecked); put("popped", sample.popped); put("expanded", sample.expanded)
        put("enqueued", sample.enqueued); put("neighborsChecked", sample.neighborsChecked); put("heuristicCalls", sample.heuristicCalls)
        put("pathPointCount", sample.pathPointCount)
        put("preservedPrefixPointCount", sample.preservedPrefixPointCount)
        put("searchStart", sample.searchStart?.let { start -> buildJsonObject {
            put("x", start.x); put("y", start.y)
        } } ?: JsonNull)
        put("searchTarget", sample.searchTarget?.let { target -> buildJsonObject {
            put("x", target.x); put("y", target.y)
        } } ?: JsonNull)
    }

    fun batchJson(batch: ReplanBatch) = buildJsonObject {
        identityFields(batch.geometryVersion).forEach { (key,value)->put(key,value) }
        put("count", batch.count); put("retention", batch.retention.name); put("diagnostics", batch.diagnostics)
        put("typeId", batch.typeId)
        put("wallNanos", batch.wallNanos); put("heapBefore", batch.heapBefore); put("heapAfter", batch.heapAfter)
        put("gcCount", batch.gcCount); put("gcMillis", batch.gcMillis)
        put("requestCount", 2 * batch.count)
        put("actualSearchCalls", batch.steps.flatMap { it.samples }.sumOf { it.actualSearchCalls })
        put("replanCount", batch.steps.flatMap { it.samples }.count { it.reason == PathSearchReason.KNOWLEDGE_REPLAN })
        put("stepsWithoutSearch", batch.steps.count { it.samples.isEmpty() })
        putJsonArray("steps") { batch.steps.forEach { step -> add(buildJsonObject {
            put("step", step.step); put("projectionNanos", step.projectionNanos); put("submitNanos", step.submitNanos)
            put("advanceNanos", step.advanceNanos); put("drainNanos", step.drainNanos)
            put("samples", JsonArray(step.samples.map(::sampleJson))); put("gameplay", step.gameplay)
        }) } }
    }

    private fun heap() = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    private fun gc(): Pair<Long, Long> = ManagementFactory.getGarbageCollectorMXBeans().let { beans ->
        if (beans.isEmpty() || beans.any { it.collectionCount < 0 || it.collectionTime < 0 }) -1L to -1L
        else beans.sumOf { it.collectionCount } to beans.sumOf { it.collectionTime }
    }
}
