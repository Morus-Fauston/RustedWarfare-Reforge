package com.rustedwarfare.reforge.testing.perf

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import com.rustedwarfare.reforge.core.knowledge.VisibleTerrainCell
import com.rustedwarfare.reforge.core.path.PathSearchOutcome
import com.rustedwarfare.reforge.core.path.PathSearchSample
import com.rustedwarfare.reforge.core.sim.ActionResult
import com.rustedwarfare.reforge.core.sim.CoordinateModel
import com.rustedwarfare.reforge.core.sim.InitResult
import com.rustedwarfare.reforge.core.sim.MoveAction
import com.rustedwarfare.reforge.core.sim.MovementContract
import com.rustedwarfare.reforge.core.sim.Player
import com.rustedwarfare.reforge.core.sim.PlayerControl
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.Scenario
import com.rustedwarfare.reforge.core.sim.SimulationContext
import com.rustedwarfare.reforge.core.sim.Step
import com.rustedwarfare.reforge.core.sim.TimeModel
import com.rustedwarfare.reforge.core.sim.UnitSpawn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/** Public top-level configuration per PR-v0.1. */
data class PathPerfConfig(
    val repoRoot: Path,
    val unitIni: Path,
    val expectedUnitSha256: String,
    val outputRoot: Path,
)

/** Public top-level run result per PR-v0.1. */
data class PathPerfRunResult(val output: Path, val exitCode: Int)

/** Correctness failure (counts/IDs/geometry) -> exit 2. */
class PathPerfCorrectnessException(message: String) : IllegalStateException(message)

/** Required diagnostics missing/incomplete -> exit 3. */
class PathPerfDiagnosticsException(message: String) : IllegalStateException(message)

/**
 * Path-performance executor (PR-v0.1 vertical slice): pure helpers, real measureBatch over the
 * frozen task144 diagnostics API, a per-batch validator, and a minimal run(config) writing
 * CREATE_NEW raw/summary/environment files. No fake context, no preset counts, no perf claims.
 */
object PathPerfBatch {
    const val WIDTH_TILES = 32
    const val HEIGHT_TILES = 24
    const val TILE_WIDTH = 20
    const val TILE_HEIGHT = 20
    const val UNIT_RADIUS = 11.0
    const val PATH_POINT_RADIUS = 16.0
    const val TARGET_RADIUS = 4.0

    val SCENARIOS = listOf("OPEN_NOFOG", "UNKNOWN_WALL", "MEMORY_GAP", "MEMORY_UNREACHABLE", "HARDCORE_FORGET")
    val COUNTS = listOf(1, 10, 50)
    val ROUNDS = 0..7
    private val MEASURE_ROUNDS = 3..7

    data class UnitPathOutcome(
        val unitId: String,
        val ownerPlayerId: String,
        val typeId: String,
        val position: Position,
        val heading: Double,
        val speed: Double,
        val angularVelocity: Double,
        val pathPoints: List<Position>,
        val moveTarget: Position?,
        val movementState: String,
    )

    data class BatchEvent(val kind: String, val unitId: String?)

    data class BatchActionResult(val actionId: String, val accepted: Boolean, val errorCode: String?)

    data class PathPerfBatchResult(
        val scenarioKey: String,
        val n: Int,
        val round: Int,
        val samples: List<PathSearchSample>,
        val units: List<UnitPathOutcome>,
        val acceptedMoves: Int,
        val submitBatchNanos: Long,
        val advanceNanos: Long,
        val diagnosticsEnabled: Boolean,
        val stepIndex: Long,
        val simulationTime: Double,
        val events: List<BatchEvent>,
        val actionResults: List<BatchActionResult>,
        val requestCount: Int,
        val actualSearchCalls: Long,
        val reuseCount: Int,
        val replanCount: Int,
        val gcAvailable: Boolean,
        val gcCollectionCountDelta: Long,
        val gcCollectionTimeMillisDelta: Long,
        val heapUsedBeforeBytes: Long,
        val heapUsedAfterBytes: Long,
    )

    fun nearestRank(values: List<Long>, q: Double): Long {
        require(values.isNotEmpty()) { "nearestRank requires a non-empty sample" }
        require(q.isFinite() && q > 0.0 && q <= 1.0) { "q must be finite and in (0, 1]" }
        val sorted = values.sorted()
        val index = ceil(q * sorted.size).toInt() - 1
        return sorted[index.coerceIn(0, sorted.lastIndex)]
    }

    fun independentGeometry(
        points: List<Position>,
        blockedCells: List<IntArray>,
        widthTiles: Int,
        heightTiles: Int,
        tileWidth: Int,
        tileHeight: Int,
        radius: Double,
    ): Boolean {
        if (points.isEmpty()) return false
        if (widthTiles <= 0 || heightTiles <= 0 || tileWidth <= 0 || tileHeight <= 0) return false
        if (!radius.isFinite() || radius < 0.0) return false
        val cells = ArrayList<Pair<Int, Int>>(points.size)
        for (point in points) {
            if (!point.x.isFinite() || !point.y.isFinite()) return false
            val column = floor(point.x / tileWidth).toInt()
            val row = floor(point.y / tileHeight).toInt()
            if (column !in 0 until widthTiles || row !in 0 until heightTiles) return false
            val centerX = (column + 0.5) * tileWidth
            val centerY = (row + 0.5) * tileHeight
            if (point.x != centerX || point.y != centerY) return false
            for (blocked in blockedCells) {
                if (blocked.size < 2) return false
                val left = blocked[0].toDouble() * tileWidth
                val top = blocked[1].toDouble() * tileHeight
                if (!strictlyClear(point, radius, left, top, left + tileWidth, top + tileHeight)) return false
            }
            cells += column to row
        }
        for (index in 1 until cells.size) {
            val manhattan = kotlin.math.abs(cells[index].first - cells[index - 1].first) +
                kotlin.math.abs(cells[index].second - cells[index - 1].second)
            if (manhattan != 1) return false
        }
        return true
    }

    /**
     * One real batch. diagnosticsEnabled=false is the paired functional control run; samples are
     * then empty and correctness is inferred from the observation only.
     */
    fun measureBatch(
        scenarioKey: String,
        n: Int,
        round: Int,
        unit: UnitDefinition,
        diagnosticsEnabled: Boolean = true,
    ): PathPerfBatchResult {
        require(scenarioKey in SCENARIOS) { "Unknown scenario '$scenarioKey'" }
        require(n in COUNTS) { "n must be one of $COUNTS" }
        require(round in ROUNDS) { "round must be in $ROUNDS" }
        require(unit.radius == UNIT_RADIUS) { "unit radius must be $UNIT_RADIUS" }
        val scenario = buildScenario(scenarioKey, n, unit)
        val context = (SimulationContext.initialize(scenario) as? InitResult.Accepted)?.context
            ?: error("Scenario '$scenarioKey' failed to initialize")
        try {
            val world = context.world
            if (diagnosticsEnabled) context.enablePathDiagnostics(world)
            applyKnowledge(context, scenarioKey, scenario.map)
            val gcBefore = if (diagnosticsEnabled) gcSnapshot() else null
            val heapBefore = if (diagnosticsEnabled) heapUsedBytes() else -1L
            var accepted = 0
            val submitStart = System.nanoTime()
            for (index in 0 until n) {
                val unitId = "perf-u%03d".format(index)
                val target = Position(goalX(index), goalY(index))
                val result = context.submit(MoveAction("perf-move%03d".format(index), "p1", unitId, target, world, 0L))
                require(result is ActionResult.Accepted && result.queuedForStep == 0L) {
                    "Move for $unitId was not queued for step 0: $result"
                }
                accepted++
            }
            val submitNanos = (System.nanoTime() - submitStart).coerceAtLeast(0L)
            if (diagnosticsEnabled && context.drainPathDiagnostics(world).isNotEmpty()) {
                throw PathPerfDiagnosticsException("submit phase must not run a search")
            }
            val advanceStart = System.nanoTime()
            val observation = context.advance(Step(0L, 1.0, "sim-tick"))
            val advanceNanos = (System.nanoTime() - advanceStart).coerceAtLeast(0L)
            val samples = if (diagnosticsEnabled) context.drainPathDiagnostics(world) else emptyList()
            val outcomes = observation.units.map { observed ->
                UnitPathOutcome(
                    observed.unitId, observed.ownerPlayerId, observed.typeId, observed.position,
                    observed.heading, observed.speed, observed.angularVelocity, observed.pathPoints,
                    observed.moveTarget, observed.movementState.name,
                )
            }
            val gcAfter = if (diagnosticsEnabled) gcSnapshot() else null
            val heapAfter = if (diagnosticsEnabled) heapUsedBytes() else -1L
            val events = observation.events.map { BatchEvent(it.kind, it.unitId) }
            val actionResults = observation.actionResults.map {
                when (it) {
                    is ActionResult.Accepted -> BatchActionResult(it.actionId, true, null)
                    is ActionResult.Rejected -> BatchActionResult(it.actionId, false, it.error.code)
                }
            }
            val gcAvailable = gcBefore != null && gcAfter != null
            val batch = PathPerfBatchResult(
                scenarioKey, n, round, samples, outcomes, accepted, submitNanos, advanceNanos, diagnosticsEnabled,
                observation.stepIndex, observation.simulationTime, events, actionResults,
                accepted, samples.sumOf { it.actualSearchCalls }, 0, 0,
                gcAvailable,
                if (gcAvailable) gcAfter!!.first - gcBefore!!.first else -1L,
                if (gcAvailable) gcAfter!!.second - gcBefore!!.second else -1L,
                heapBefore, heapAfter,
            )
            if (diagnosticsEnabled) {
                validateBatch(scenarioKey, n, batch, scenario.map)
            }
            return batch
        } finally {
            context.close()
        }
    }

    /** Per-batch correctness validator (IDs/outcome/candidates/step/target/geometry). */
    fun validateBatch(scenarioKey: String, n: Int, batch: PathPerfBatchResult, map: MapDefinition) {
        if (batch.samples.size != n) {
            throw PathPerfDiagnosticsException("expected $n real search samples, got ${batch.samples.size}")
        }
        val expectedActions = (0 until n).map { "perf-move%03d".format(it) }.toSet()
        val expectedUnits = (0 until n).map { "perf-u%03d".format(it) }.toSet()
        if (batch.samples.map { it.actionId }.toSet() != expectedActions) {
            throw PathPerfCorrectnessException("sample action IDs do not cover perf-move000..${n - 1} exactly")
        }
        if (batch.samples.size != batch.samples.map { it.actionId }.toSet().size) {
            throw PathPerfCorrectnessException("duplicate action samples are not allowed")
        }
        if (batch.samples.map { it.unitId }.toSet() != expectedUnits) {
            throw PathPerfCorrectnessException("sample unit IDs do not cover perf-u000..${n - 1} exactly")
        }
        if (batch.units.size != n) {
            throw PathPerfCorrectnessException("expected $n observed units, got ${batch.units.size}")
        }
        if (batch.units.map { it.unitId }.toSet() != expectedUnits) {
            throw PathPerfCorrectnessException("observed unit IDs do not cover perf-u000..${n - 1} exactly")
        }
        if (batch.units.size != batch.units.map { it.unitId }.toSet().size) {
            throw PathPerfCorrectnessException("duplicate observed unit IDs are not allowed")
        }
        for (sample in batch.samples) {
            val actionIndex = sample.actionId.removePrefix("perf-move").toIntOrNull()
            val unitIndex = parseIndex(sample.unitId)
            if (actionIndex == null || actionIndex != unitIndex) {
                throw PathPerfCorrectnessException("sample ${sample.actionId} must pair with ${sample.unitId}")
            }
        }
        val expectedKnowledge = scenarioKey != "OPEN_NOFOG"
        val unreachable = scenarioKey == "MEMORY_UNREACHABLE"
        if (batch.stepIndex != 0L || batch.simulationTime != 1.0) {
            throw PathPerfCorrectnessException("batch must observe step 0 at simulationTime 1.0")
        }
        if (batch.actionResults.size != n || batch.actionResults.any { !it.accepted }) {
            throw PathPerfCorrectnessException("every submitted move must have an accepted action result")
        }
        if (batch.actionResults.map { it.actionId }.toSet() != expectedActions) {
            throw PathPerfCorrectnessException("action results must cover every perf-move id")
        }
        if (unreachable) {
            for (index in 0 until n) {
                val unitId = "perf-u%03d".format(index)
                if (batch.events.none { it.kind == "path-unreachable" && it.unitId == unitId }) {
                    throw PathPerfCorrectnessException("unreachable unit $unitId must emit path-unreachable")
                }
            }
        }
        if (batch.requestCount != n || batch.actualSearchCalls != n.toLong() || batch.reuseCount != 0 || batch.replanCount != 0) {
            throw PathPerfCorrectnessException("request/actual/reuse/replan counts do not match the fixed batch")
        }
        if (batch.gcAvailable && (batch.gcCollectionCountDelta < 0L || batch.gcCollectionTimeMillisDelta < 0L)) {
            throw PathPerfCorrectnessException("GC delta must be non-negative when available")
        }
        if (!batch.gcAvailable && (batch.gcCollectionCountDelta != -1L || batch.gcCollectionTimeMillisDelta != -1L)) {
            throw PathPerfCorrectnessException("unavailable GC must be reported as -1, not zero")
        }
        if (batch.heapUsedBeforeBytes < 0L || batch.heapUsedAfterBytes < 0L) {
            throw PathPerfCorrectnessException("heap snapshot must be available for the batch")
        }
        for (sample in batch.samples) {
            if (sample.actualSearchCalls != 1L) {
                throw PathPerfDiagnosticsException("sample ${sample.actionId} must record exactly one actual search")
            }
            if (sample.outcome == PathSearchOutcome.FAILED) {
                throw PathPerfDiagnosticsException("sample ${sample.actionId} failed internally")
            }
            if (sample.usedPlayerKnowledge != expectedKnowledge) {
                throw PathPerfCorrectnessException("sample ${sample.actionId} knowledge flag mismatch")
            }
            if (sample.scheduledStep != 0L) {
                throw PathPerfCorrectnessException("sample ${sample.actionId} must be scheduled for step 0")
            }
            val expectedOutcome = if (unreachable) PathSearchOutcome.UNREACHABLE else PathSearchOutcome.FOUND
            if (sample.outcome != expectedOutcome) {
                throw PathPerfCorrectnessException("sample ${sample.actionId} expected $expectedOutcome got ${sample.outcome}")
            }
            if (sample.candidatesChecked != (WIDTH_TILES * HEIGHT_TILES).toLong()) {
                throw PathPerfCorrectnessException("sample ${sample.actionId} candidates must be ${WIDTH_TILES * HEIGHT_TILES}")
            }
            if (unreachable && sample.expanded < 1L) {
                throw PathPerfCorrectnessException("unreachable sample ${sample.actionId} must expand at least one node")
            }
        }
        val authorized = authorizedBlocked(scenarioKey, map)
        for (outcome in batch.units) {
            val index = parseIndex(outcome.unitId)
            val goal = Position(goalX(index), goalY(index))
            if (unreachable) {
                if (outcome.pathPoints.isNotEmpty()) {
                    throw PathPerfCorrectnessException("unit ${outcome.unitId} must have no path when unreachable")
                }
                continue
            }
            if (outcome.pathPoints.isEmpty()) {
                throw PathPerfCorrectnessException("unit ${outcome.unitId} must have a path")
            }
            val start = Position(startX(index), startY(index))
            if (outcome.pathPoints.any { it == start }) {
                throw PathPerfCorrectnessException("unit ${outcome.unitId} path must not repeat the start cell")
            }
            val first = outcome.pathPoints.first()
            val startCell = floor(start.x / TILE_WIDTH).toInt() to floor(start.y / TILE_HEIGHT).toInt()
            val firstCell = floor(first.x / TILE_WIDTH).toInt() to floor(first.y / TILE_HEIGHT).toInt()
            val startDistance = kotlin.math.abs(startCell.first - firstCell.first) +
                kotlin.math.abs(startCell.second - firstCell.second)
            if (startDistance != 1) {
                throw PathPerfCorrectnessException("unit ${outcome.unitId} first path point must be a 4-neighbour of the fixed start")
            }
            if (outcome.pathPoints.last() != goal) {
                throw PathPerfCorrectnessException("unit ${outcome.unitId} path must end exactly at the goal")
            }
            if (!independentGeometry(outcome.pathPoints, authorized, WIDTH_TILES, HEIGHT_TILES, TILE_WIDTH, TILE_HEIGHT, UNIT_RADIUS)) {
                throw PathPerfCorrectnessException("unit ${outcome.unitId} path fails independent geometry on the authorized grid")
            }
        }
    }

    /**
     * Minimal real run: validates inputs, loads the approved unit INI with rehashed bytes,
     * creates one CREATE_NEW output directory and writes raw samples, units, summary array,
     * environment and manifest. No performance claims; exit 0/2/3/4/64 per PR.
     */
    fun run(config: PathPerfConfig): PathPerfRunResult {
        val repo = config.repoRoot.toAbsolutePath().normalize()
        val unit = config.unitIni.toAbsolutePath().normalize()
        val outputRoot = config.outputRoot.toAbsolutePath().normalize()
        if (!config.repoRoot.isAbsolute || !config.unitIni.isAbsolute || !config.outputRoot.isAbsolute) {
            return PathPerfRunResult(outputRoot, 64)
        }
        if (config.expectedUnitSha256.length != 64 || !config.expectedUnitSha256.all { it in "0123456789abcdefABCDEF" }) {
            return PathPerfRunResult(outputRoot, 64)
        }
        if (!isPlainDirectory(repo) || !isPlainFile(unit) || !isPlainDirectory(outputRoot)) {
            return PathPerfRunResult(outputRoot, 64)
        }
        if (!insideForbidden(repo, outputRoot)) return PathPerfRunResult(outputRoot, 64)
        val before = sha256(unit)
        if (!before.equals(config.expectedUnitSha256, ignoreCase = true)) return PathPerfRunResult(outputRoot, 64)
        val loaded = ContentLoader.loadUnitVariant(unit)
        if (loaded !is ContentResult.Success) return PathPerfRunResult(outputRoot, 64)
        val definition = loaded.value
        if (definition.radius != UNIT_RADIUS) return PathPerfRunResult(outputRoot, 64)
        if (sha256(unit) != before) return PathPerfRunResult(outputRoot, 64)
        val stamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now(ZoneOffset.UTC))
        val output = outputRoot.resolve("path-perf-$stamp-${UUID.randomUUID()}")
        return try {
            Files.createDirectory(output)
            Files.createDirectory(output.resolve("raw"))
            // Correctness phase (not timed): exactly one on/off pair per 15 groups; control output separate.
            var controlPairs = 0
            for (scenario in SCENARIOS) {
                for (count in COUNTS) {
                    runControlCheck(output, scenario, count, definition)
                    controlPairs++
                }
            }
            // Performance phase: 120 enabled batches (timed; measured rounds 3..7).
            val batches = ArrayList<PathPerfBatchResult>()
            for (scenario in SCENARIOS) {
                for (count in COUNTS) {
                    for (round in ROUNDS) {
                        val batch = measureBatch(scenario, count, round, definition, diagnosticsEnabled = true)
                        batches += batch
                        writeRaw(output, batch)
                    }
                }
            }
            val totalSamples = batches.sumOf { it.samples.size }
            val measuredSamples = batches.filter { it.round in MEASURE_ROUNDS }.sumOf { it.samples.size }
            val summaryRows = summarize(batches)
            verifyRunCounts(controlPairs, batches.size, totalSamples, measuredSamples, summaryRows.size)
            val summary = buildSummary(batches)
            Files.writeString(output.resolve("summary.json"), summary.toString(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            Files.writeString(output.resolve("environment.json"), buildEnvironment(before, definition, repo), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            if (sha256(unit) != before) {
                throw PathPerfCorrectnessException("unit INI bytes changed during the run")
            }
            Files.writeString(
                output.resolve("manifest.json"),
                buildJsonObject {
                    put("completed", true)
                    put("unitSha256", before)
                    put("output", output.fileName.toString())
                    put("round0Note", "round0 is the group's first batch in this JVM, not an independent fresh-JVM cold start")
                }.toString(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            )
            PathPerfRunResult(output, 0)
        } catch (error: PathPerfDiagnosticsException) {
            return failedRun(output, 3, error)
        } catch (error: PathPerfCorrectnessException) {
            return failedRun(output, 2, error)
        } catch (error: Throwable) {
            return failedRun(output, 4, error)
        }
    }

    private fun failedRun(output: Path, exitCode: Int, error: Throwable): PathPerfRunResult {
        try {
            Files.writeString(
                output.resolve("manifest.json"),
                buildJsonObject {
                    put("completed", false)
                    put("exitCode", exitCode)
                    put("errorClass", error.javaClass.name)
                    put("errorMessage", error.message ?: "no message")
                }.toString(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            )
            Files.writeString(
                output.resolve("error.log"),
                error.stackTraceToString(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            )
        } catch (ignored: Throwable) {
            // Best-effort: the output directory and already written files stay for inspection.
        }
        return PathPerfRunResult(output, exitCode)
    }

    /**
     * Public gameplay-state equivalence for the diagnostics on/off pair.
     *
     * Compares only gameplay state (units incl. full observation values, step/time/events/
     * action results/request count); diagnostics counters and timing/GC are intentionally excluded
     * because the off run has no samples and must not be filled with N.
     */
    fun sameGameplayState(a: PathPerfBatchResult, b: PathPerfBatchResult): Boolean =
        a.units == b.units &&
            a.stepIndex == b.stepIndex &&
            a.simulationTime == b.simulationTime &&
            a.events == b.events &&
            a.actionResults == b.actionResults &&
            a.acceptedMoves == b.acceptedMoves &&
            a.requestCount == b.requestCount

    /**
     * PR hard guard for a successful run. Counts must be the fixed plan:
     * 15 control pairs, 120 timed batches, 2440 total samples, 1525 measured samples,
     * 45 summary rows (15 groups x 3 phases). Missing/duplicate counts fail with exit 3.
     */
    fun verifyRunCounts(
        controlPairs: Int,
        batches: Int,
        samples: Int,
        measuredSamples: Int,
        summaryRows: Int,
    ) {
        if (controlPairs != 15) throw PathPerfDiagnosticsException("expected 15 control pairs, got $controlPairs")
        if (batches != 120) throw PathPerfDiagnosticsException("expected 120 timed batches, got $batches")
        if (samples != 2440) throw PathPerfDiagnosticsException("expected 2440 total samples, got $samples")
        if (measuredSamples != 1525) throw PathPerfDiagnosticsException("expected 1525 measured samples, got $measuredSamples")
        if (summaryRows != 45) throw PathPerfDiagnosticsException("expected 45 summary rows, got $summaryRows")
    }

    /**
     * Public raw serializer: one JSONL line per batch metadata/sample/unit, so summary batch P95
     * and correctness can be recomputed from raw files. Callers use this in run(); tests parse it.
     */
    fun serializeBatch(batch: PathPerfBatchResult, correctnessPass: Boolean = true): List<String> {
        val lines = ArrayList<String>(2 + batch.samples.size + batch.units.size)
        lines += buildJsonObject {
            put("kind", "batch")
            put("scenarioKey", batch.scenarioKey)
            put("n", batch.n)
            put("round", batch.round)
            put("samples", batch.samples.size)
            put("actualSearchCalls", batch.actualSearchCalls)
            put("submitBatchNanos", batch.submitBatchNanos)
            put("advanceNanos", batch.advanceNanos)
            put("gcAvailable", batch.gcAvailable)
            put("gcCollectionCountDelta", batch.gcCollectionCountDelta)
            put("gcCollectionTimeMillisDelta", batch.gcCollectionTimeMillisDelta)
            put("heapUsedBeforeBytes", batch.heapUsedBeforeBytes)
            put("heapUsedAfterBytes", batch.heapUsedAfterBytes)
            put("requestCount", batch.requestCount)
            put("acceptedMoves", batch.acceptedMoves)
            put("reuseCount", batch.reuseCount)
            put("replanCount", batch.replanCount)
            put("stepIndex", batch.stepIndex)
            put("simulationTime", batch.simulationTime)
            put("correctnessPass", correctnessPass)
            put("diagnosticsEnabled", batch.diagnosticsEnabled)
            putJsonArray("events") {
                batch.events.forEach { event ->
                    add(buildJsonObject {
                        put("kind", event.kind)
                        if (event.unitId != null) put("unitId", event.unitId) else put("unitId", JsonNull)
                    })
                }
            }
            putJsonArray("actionResults") {
                batch.actionResults.forEach { result ->
                    add(buildJsonObject {
                        put("actionId", result.actionId)
                        put("accepted", result.accepted)
                        if (result.errorCode != null) put("errorCode", result.errorCode) else put("errorCode", JsonNull)
                    })
                }
            }
        }.toString()
        for (sample in batch.samples) {
            lines += buildJsonObject {
                put("kind", "sample")
                put("scenarioKey", batch.scenarioKey)
                put("n", batch.n)
                put("round", batch.round)
                sampleJson(sample).forEach { (key, value) -> put(key, value) }
            }.toString()
        }
        for (unit in batch.units) {
            lines += unitJson(unit, batch, null).toString()
        }
        return lines
    }

    private fun unitJson(unit: UnitPathOutcome, batch: PathPerfBatchResult, side: String?): JsonObject =
        buildJsonObject {
            put("kind", "unit")
            if (side != null) put("side", side)
            put("scenarioKey", batch.scenarioKey)
            put("n", batch.n)
            put("round", batch.round)
            put("unitId", unit.unitId)
            put("ownerPlayerId", unit.ownerPlayerId)
            put("typeId", unit.typeId)
            put("heading", unit.heading)
            put("speed", unit.speed)
            put("angularVelocity", unit.angularVelocity)
            put("movementState", unit.movementState)
            put("position", buildJsonObject {
                put("x", unit.position.x)
                put("y", unit.position.y)
            })
            if (unit.moveTarget != null) {
                put("moveTarget", buildJsonObject {
                    put("x", unit.moveTarget.x)
                    put("y", unit.moveTarget.y)
                })
            } else {
                put("moveTarget", JsonNull)
            }
            putJsonArray("pathPoints") {
                unit.pathPoints.forEach { point ->
                    add(buildJsonObject {
                        put("x", point.x)
                        put("y", point.y)
                    })
                }
            }
        }

    /**
     * Bounded public control check used by run(): one real on/off pair, gameplay-state
     * equivalence, full control record written CREATE_NEW. Tests call this with a temp output.
     */
    fun runControlCheck(output: Path, scenarioKey: String, n: Int, unit: UnitDefinition): Boolean {
        val enabled = measureBatch(scenarioKey, n, 0, unit, diagnosticsEnabled = true)
        val control = measureBatch(scenarioKey, n, 0, unit, diagnosticsEnabled = false)
        if (!sameGameplayState(enabled, control)) {
            throw PathPerfCorrectnessException("diagnostics-on/off functional mismatch for $scenarioKey n=$n (group control)")
        }
        writeRawControl(output, enabled, control, scenarioKey, n)
        return true
    }

    /** Correctness-phase control record (full on/off state + sameGameplayState); never mixed into measured samples. */
    private fun writeRawControl(output: Path, enabled: PathPerfBatchResult, control: PathPerfBatchResult,
                                scenarioKey: String, count: Int) {
        val dir = output.resolve("control")
        if (!Files.exists(dir)) Files.createDirectory(dir)
        val file = dir.resolve("$scenarioKey-n$count.jsonl")
        val lines = ArrayList<String>()
        lines += buildJsonObject {
            put("kind", "control")
            put("scenarioKey", scenarioKey)
            put("n", count)
            put("sameGameplayState", sameGameplayState(enabled, control))
        }.toString()
        enabled.units.forEach { unit -> lines += unitJson(unit, enabled, "enabled").toString() }
        control.units.forEach { unit -> lines += unitJson(unit, control, "off").toString() }
        Files.write(file, lines, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    private fun writeRaw(output: Path, batch: PathPerfBatchResult) {
        val file = output.resolve("raw").resolve("${batch.scenarioKey}-n${batch.n}-round${batch.round}.jsonl")
        Files.write(file, serializeBatch(batch), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    private fun sampleJson(sample: PathSearchSample): JsonObject = buildJsonObject {
        put("actionId", sample.actionId)
        put("unitId", sample.unitId)
        put("outcome", sample.outcome.name)
        put("usedPlayerKnowledge", sample.usedPlayerKnowledge)
        if (sample.scheduledStep != null) {
            put("scheduledStep", sample.scheduledStep)
        } else {
            put("scheduledStep", JsonNull)
        }
        put("inputPreparationNanos", sample.inputPreparationNanos)
        put("searchNanos", sample.searchNanos)
        put("actualSearchCalls", sample.actualSearchCalls)
        put("candidatesChecked", sample.candidatesChecked)
        put("popped", sample.popped)
        put("expanded", sample.expanded)
        put("enqueued", sample.enqueued)
        put("neighborsChecked", sample.neighborsChecked)
        put("heuristicCalls", sample.heuristicCalls)
        put("pathPointCount", sample.pathPointCount)
    }

    /** Public scenario+N+phase row; phases initial(round0)/warmup(rounds1-2)/measured(rounds3-7). */
    data class SummaryRow(
        val scenarioKey: String,
        val n: Int,
        val phase: String,
        val rounds: Int,
        val samples: Int,
        val searchSamples: Int,
        val searchP50Nanos: Long,
        val searchP95Nanos: Long,
        val searchMaxNanos: Long,
        val batchSamples: Int,
        val batchP50Nanos: Long,
        val batchP95Nanos: Long,
        val batchMaxNanos: Long,
    )

    /**
     * Public summary aggregation (fixture-testable without running 120):
     * every scenario+N gets initial/warmup/measured rows; per-search and batch percentiles per phase.
     */
    fun summarize(batches: List<PathPerfBatchResult>): List<SummaryRow> {
        val rows = ArrayList<SummaryRow>()
        for (scenario in SCENARIOS) {
            for (count in COUNTS) {
                for (phase in listOf("initial", "warmup", "measured")) {
                    val selected = batches.filter {
                        it.scenarioKey == scenario && it.n == count && phaseMatches(phase, it.round)
                    }
                    if (selected.isEmpty()) continue
                    val search = selected.flatMap { batch -> batch.samples.map { it.searchNanos } }
                    val batchNanos = selected.map { it.advanceNanos }
                    rows += SummaryRow(
                        scenario, count, phase, selected.size, selected.sumOf { it.samples.size },
                        search.size, nearestRank(search, 0.50), nearestRank(search, 0.95), search.maxOrNull() ?: 0L,
                        batchNanos.size, nearestRank(batchNanos, 0.50), nearestRank(batchNanos, 0.95), batchNanos.maxOrNull() ?: 0L,
                    )
                }
            }
        }
        return rows
    }

    private fun phaseMatches(phase: String, round: Int): Boolean = when (phase) {
        "initial" -> round == 0
        "warmup" -> round in 1..2
        "measured" -> round in MEASURE_ROUNDS
        else -> false
    }

    private fun buildSummary(batches: List<PathPerfBatchResult>): JsonArray =
        buildJsonArray {
            summarize(batches).forEach { row ->
                add(buildJsonObject {
                    put("scenarioKey", row.scenarioKey)
                    put("n", row.n)
                    put("phase", row.phase)
                    put("rounds", row.rounds)
                    put("samples", row.samples)
                    put("searchSamples", row.searchSamples)
                    put("searchP50Nanos", row.searchP50Nanos)
                    put("searchP95Nanos", row.searchP95Nanos)
                    put("searchMaxNanos", row.searchMaxNanos)
                    put("batchSamples", row.batchSamples)
                    put("batchP50Nanos", row.batchP50Nanos)
                    put("batchP95Nanos", row.batchP95Nanos)
                    put("batchMaxNanos", row.batchMaxNanos)
                })
            }
        }

    private fun buildEnvironment(unitSha256: String, definition: UnitDefinition, repo: Path): String {
        val gcs = runCatching { ManagementFactory.getGarbageCollectorMXBeans() }.getOrNull()
        val runtime = Runtime.getRuntime()
        val heapUsed = runtime.totalMemory() - runtime.freeMemory()
        return buildJsonObject {
            put("javaVersion", System.getProperty("java.version"))
            put("javaVendor", System.getProperty("java.vendor"))
            put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"))
            put("processors", runtime.availableProcessors())
            put("heapUsedBytes", heapUsed)
            put("heapTotalBytes", runtime.totalMemory())
            put("heapSource", "Runtime heapUsed snapshot, not allocated/RSS peak")
            put("gcAvailable", gcs != null)
            if (gcs == null) {
                put("gc", "unavailable")
            } else {
                putJsonArray("gc") {
                    gcs.forEach { gc ->
                        add(buildJsonObject {
                            put("name", gc.name)
                            put("collectionCount", gc.collectionCount)
                            put("collectionTimeMillis", gc.collectionTime)
                        })
                    }
                }
            }
            put("unitSha256", unitSha256)
            put("unitRadius", definition.radius)
            put("unitIni", definition.id)
            put("repoRoot", repo.toString())
            put("runnerClass", PathPerfBatch::class.java.name)
            put("round0Note", "round0 is the group's first batch in this JVM, not an independent fresh-JVM cold start")
            put("measurementRounds", "3..7")
            put("warmupRounds", "1..2")
        }.toString()
    }

    private fun authorizedBlocked(scenarioKey: String, map: MapDefinition): List<IntArray> {
        if (scenarioKey in setOf("UNKNOWN_WALL", "HARDCORE_FORGET")) return emptyList()
        val cells = ArrayList<IntArray>()
        for (row in 0 until HEIGHT_TILES) {
            for (column in 0 until WIDTH_TILES) {
                if (map.blocking.isBlocked(column, row)) cells += intArrayOf(column, row)
            }
        }
        return cells
    }

    private fun parseIndex(unitId: String): Int =
        unitId.removePrefix("perf-u").toIntOrNull() ?: throw PathPerfCorrectnessException("bad unit id '$unitId'")

    private fun isPlainFile(path: Path): Boolean =
        Files.isRegularFile(path) && path.toRealPath().normalize() == path.normalize()

    private fun isPlainDirectory(path: Path): Boolean =
        Files.isDirectory(path) && path.toRealPath().normalize() == path.normalize()

    private fun insideForbidden(repo: Path, outputRoot: Path): Boolean {
        val normalized = outputRoot.normalize()
        val forbidden = listOf("Rusted Warfare", "TestLab", "Docs", "Docs/Research-DEC", "Docs/归档", ".git")
        return forbidden.none { normalized.startsWith(repo.resolve(it).normalize()) }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun buildScenario(scenarioKey: String, n: Int, unit: UnitDefinition): Scenario {
        val blocked = BooleanArray(WIDTH_TILES * HEIGHT_TILES)
        when (scenarioKey) {
            "OPEN_NOFOG" -> Unit
            "UNKNOWN_WALL", "MEMORY_GAP", "HARDCORE_FORGET" -> {
                for (index in blocked.indices) blocked[index] = wallWithGap(index)
            }
            "MEMORY_UNREACHABLE" -> {
                for (index in blocked.indices) blocked[index] = fullWall(index)
            }
            else -> error("Unknown scenario '$scenarioKey'")
        }
        val map = MapDefinition(
            WIDTH_TILES, HEIGHT_TILES, TILE_WIDTH, TILE_HEIGHT,
            emptyList(), IntArray(WIDTH_TILES * HEIGHT_TILES), BlockingGrid(WIDTH_TILES, HEIGHT_TILES, blocked),
        )
        val units = (0 until n).map { index ->
            UnitSpawn("perf-u%03d".format(index), "p1", unit.id, Position(startX(index), startY(index)), 90.0)
        }
        return Scenario(
            scenarioId = "path-perf-$scenarioKey",
            map = map,
            unitDefinitions = mapOf(unit.id to unit),
            players = listOf(Player("p1", PlayerControl.LOCAL)),
            units = units,
            coordinateModel = CoordinateModel(
                "p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64",
            ),
            timeModel = TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"),
            movementContract = MovementContract(PATH_POINT_RADIUS, TARGET_RADIUS),
        )
    }

    private fun applyKnowledge(context: SimulationContext, scenarioKey: String, map: MapDefinition) {
        val world = context.world
        when (scenarioKey) {
            "OPEN_NOFOG" -> Unit
            "UNKNOWN_WALL" -> context.enableKnowledge(
                world, KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false),
            )
            "MEMORY_GAP", "MEMORY_UNREACHABLE" -> {
                context.enableKnowledge(
                    world, KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false),
                )
                acceptAll(context, map)
            }
            "HARDCORE_FORGET" -> {
                context.enableKnowledge(
                    world, KnowledgeRules(TerrainRetention.HARDCORE_FORGET, PathInformationSource.PLAYER_KNOWLEDGE, false),
                )
                acceptAll(context, map)
                context.acceptVisibleTerrain(world, "p1", emptyList())
            }
            else -> error("Unknown scenario '$scenarioKey'")
        }
    }

    private fun acceptAll(context: SimulationContext, map: MapDefinition) {
        val cells = ArrayList<VisibleTerrainCell>(WIDTH_TILES * HEIGHT_TILES)
        for (row in 0 until HEIGHT_TILES) {
            for (column in 0 until WIDTH_TILES) {
                cells += VisibleTerrainCell(column, row, map.blocking.isBlocked(column, row))
            }
        }
        context.acceptVisibleTerrain(context.world, "p1", cells)
    }

    private fun wallWithGap(index: Int): Boolean {
        val column = index % WIDTH_TILES
        val row = index / WIDTH_TILES
        return column == 16 && (row < 10 || row > 14)
    }

    private fun fullWall(index: Int): Boolean = index % WIDTH_TILES == 16

    private fun startX(index: Int): Double = (2 + index % 10 + 0.5) * TILE_WIDTH

    private fun startY(index: Int): Double = (2 + index / 10 + 0.5) * TILE_HEIGHT

    private fun goalX(index: Int): Double = (20 + index % 10 + 0.5) * TILE_WIDTH

    private fun goalY(index: Int): Double = (16 + index / 10 + 0.5) * TILE_HEIGHT

    private fun gcSnapshot(): Pair<Long, Long>? = runCatching {
        val beans = ManagementFactory.getGarbageCollectorMXBeans()
        if (beans.isEmpty()) return@runCatching null
        var count = 0L
        var time = 0L
        for (bean in beans) {
            val beanCount = bean.collectionCount
            val beanTime = bean.collectionTime
            if (beanCount < 0L || beanTime < 0L) return@runCatching null
            count += beanCount
            time += beanTime
        }
        count to time
    }.getOrNull()

    private fun heapUsedBytes(): Long = runCatching {
        val runtime = Runtime.getRuntime()
        runtime.totalMemory() - runtime.freeMemory()
    }.getOrDefault(-1L)

    private fun strictlyClear(position: Position, radius: Double, left: Double, top: Double, right: Double, bottom: Double): Boolean {
        val closestX = position.x.coerceIn(left, right)
        val closestY = position.y.coerceIn(top, bottom)
        return hypot(position.x - closestX, position.y - closestY) > radius
    }
}
