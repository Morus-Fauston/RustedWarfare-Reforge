package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.json.*

/**
 * Second-level movement micro-benchmark & 2-second stall-highlighted contact sheet probe (ENG-P016-010B).
 *
 * Outputs per-scene JSON metrics and optional PNG contact sheets / peak zooms:
 * - Arrived / active / stalled-2s / hard-contact counts & first/last arrival steps
 * - Gate/corridor exit crossing counts and steady throughput (units/sec)
 * - Simulation `advance()` step latency distribution (mean, P50, P95, P99, max in ms)
 * - Simulation-only JVM thread heap allocation (total bytes, steady-state bytes/step, P50/P99 bytes/step) & GC delta
 *   strictly isolated from post-run projection and PNG rendering.
 */
object MovementMicroProbe {
    data class GateLine(
        val id: String,
        val axisX: Boolean,
        val worldThreshold: Double,
        val minOrtho: Double,
        val maxOrtho: Double,
        val positiveDirection: Boolean = true,
    ) {
        fun crossed(prev: Position, curr: Position): Boolean {
            val pMain = if (axisX) prev.x else prev.y
            val cMain = if (axisX) curr.x else curr.y
            val cOrtho = if (axisX) curr.y else curr.x
            if (cOrtho < minOrtho || cOrtho > maxOrtho) return false
            return if (positiveDirection) {
                pMain <= worldThreshold && cMain > worldThreshold
            } else {
                pMain >= worldThreshold && cMain < worldThreshold
            }
        }
    }

    data class ProbeSceneSpec(
        val id: String,
        val title: String,
        val defaultSteps: Int,
        val gates: List<GateLine>,
        val build: (Map<String, UnitDefinition>) -> Scenario,
        val issueOrders: (SimulationContext) -> Unit,
    )

    val ALL_SCENE_IDS: List<String> = listOf(
        "rocks-small-100",
        "rocks-mixed-100",
        "opposed-open-25v25",
        "opposed-corridor-8v8",
        "mixed-overtake-wide",
        "narrow-bridge-yield",
        "air-flock-rally",
        "open-50-217-166",
        "culdesac-overflow-100",
    )

    fun loadStandardDefinitions(repoRoot: Path): Map<String, UnitDefinition> {
        val packPath = repoRoot.resolve("SimulationTest/public/inputs/phase-six-a-sandbox/json/package")
        val loaded = ContentLoader.loadContentPack(packPath)
        check(loaded is ContentResult.Success) { "Failed to load phase-six-a-sandbox: $loaded" }
        val pack = loaded.value
        val loadedTank = pack.units.getValue("c_tank")
        val largeMech = pack.units.getValue("mechGun")
        val smallTank = UnitDefinition(
            id = "c_tank", className = loadedTank.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = loadedTank.totalFrames, imagePath = loadedTank.imagePath, imageScale = loadedTank.imageScale,
            movement = loadedTank.movement.copy(maxTurnSpeed = 4.1, turnAcceleration = 0.25),
            retainedUnconsumed = emptySet(), fogOfWarSightRange = 18,
            combat = loadedTank.combat, durability = loadedTank.durability, build = loadedTank.build,
            movementCollision = MovementCollisionShape.Circle(6.0), mass = 3000.0,
        )
        val fastScout = UnitDefinition(
            id = "fast_scout", className = loadedTank.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = loadedTank.totalFrames, imagePath = loadedTank.imagePath, imageScale = loadedTank.imageScale,
            movement = loadedTank.movement.copy(moveSpeed = 1.65, maxTurnSpeed = 8.0, turnAcceleration = 1.2),
            retainedUnconsumed = emptySet(), fogOfWarSightRange = 18,
            combat = loadedTank.combat, durability = loadedTank.durability, build = loadedTank.build,
            movementCollision = MovementCollisionShape.Circle(6.0), mass = 2200.0,
        )
        val slowHeavy = UnitDefinition(
            id = "slow_heavy", className = loadedTank.className, radius = 14.0, displayRadius = 14.0,
            totalFrames = loadedTank.totalFrames, imagePath = loadedTank.imagePath, imageScale = loadedTank.imageScale,
            movement = loadedTank.movement.copy(moveSpeed = 0.55, maxTurnSpeed = 2.0, turnAcceleration = 0.2),
            retainedUnconsumed = emptySet(), fogOfWarSightRange = 18,
            combat = loadedTank.combat, durability = loadedTank.durability, build = loadedTank.build,
            movementCollision = MovementCollisionShape.Circle(14.0), mass = 36000.0,
        )
        val airChopper = pack.units.getValue("helicopter")
        return mapOf(
            "c_tank" to smallTank,
            "mechGun" to largeMech,
            "fast_scout" to fastScout,
            "slow_heavy" to slowHeavy,
            "helicopter" to airChopper,
        )
    }

    fun sceneSpecs(): Map<String, ProbeSceneSpec> {
        val rockGates = listOf(
            GateLine("wall1-narrow-y12", axisX = true, worldThreshold = 26.0 * 20.0, minOrtho = 11.5 * 20.0, maxOrtho = 13.5 * 20.0),
            GateLine("wall1-wide-y23..28", axisX = true, worldThreshold = 26.0 * 20.0, minOrtho = 22.5 * 20.0, maxOrtho = 29.5 * 20.0),
            GateLine("wall2-narrow-y15", axisX = true, worldThreshold = 36.0 * 20.0, minOrtho = 14.5 * 20.0, maxOrtho = 16.5 * 20.0),
            GateLine("wall2-wide-y20..25", axisX = true, worldThreshold = 36.0 * 20.0, minOrtho = 19.5 * 20.0, maxOrtho = 26.5 * 20.0),
            GateLine("wall3-narrow-y11", axisX = true, worldThreshold = 46.0 * 20.0, minOrtho = 10.5 * 20.0, maxOrtho = 12.5 * 20.0),
            GateLine("wall3-wide-y25..30", axisX = true, worldThreshold = 46.0 * 20.0, minOrtho = 24.5 * 20.0, maxOrtho = 31.5 * 20.0),
        )
        return linkedMapOf(
            "rocks-small-100" to ProbeSceneSpec(
                id = "rocks-small-100",
                title = "Probe: rocks-small-100 (100x c_tank through staggered narrow+wide rock gates)",
                defaultSteps = 2900,
                gates = rockGates,
                build = { defs -> buildRocksScenario("rocks-small-100", defs, mixed = false) },
                issueOrders = { ctx ->
                    val ids = (0 until 100).map { String.format("tank-%02d", it) }
                    val click = Position(1260.0, 400.0)
                    val group = checkNotNull(ctx.requestGroupMove("p1", ids, click))
                    for (id in ids) {
                        check(ctx.submitGroupMove(MoveAction("rocks100-$id", "p1", id, click, ctx.world), group) is ActionResult.Accepted)
                    }
                },
            ),
            "rocks-mixed-100" to ProbeSceneSpec(
                id = "rocks-mixed-100",
                title = "Probe: rocks-mixed-100 (75 small + 25 slow_heavy through staggered narrow+wide rock gates)",
                defaultSteps = 4200,
                gates = rockGates,
                build = { defs -> buildRocksScenario("rocks-mixed-100", defs, mixed = true) },
                issueOrders = { ctx ->
                    val ids = (0 until 100).map { String.format("tank-%02d", it) }
                    val click = Position(1260.0, 400.0)
                    val group = checkNotNull(ctx.requestGroupMove("p1", ids, click))
                    for (id in ids) {
                        check(ctx.submitGroupMove(MoveAction("rocksmix-$id", "p1", id, click, ctx.world), group) is ActionResult.Accepted)
                    }
                },
            ),
            "opposed-open-25v25" to ProbeSceneSpec(
                id = "opposed-open-25v25",
                title = "Probe: opposed-open-25v25 (25 Eastbound vs 25 Westbound head-on)",
                defaultSteps = 2400,
                gates = listOf(
                    GateLine("midline-eastbound", axisX = true, worldThreshold = 500.0, minOrtho = 0.0, maxOrtho = 600.0, positiveDirection = true),
                    GateLine("midline-westbound", axisX = true, worldThreshold = 500.0, minOrtho = 0.0, maxOrtho = 600.0, positiveDirection = false),
                ),
                build = { defs -> buildOpposedOpenScenario("opposed-open-25v25", defs) },
                issueOrders = { ctx ->
                    val eastIds = (0 until 25).map { String.format("east-%02d", it) }
                    val westIds = (0 until 25).map { String.format("west-%02d", it) }
                    val gEast = checkNotNull(ctx.requestGroupMove("p1", eastIds, Position(780.0, 300.0)))
                    for (id in eastIds) check(ctx.submitGroupMove(MoveAction("east-$id", "p1", id, Position(780.0, 300.0), ctx.world), gEast) is ActionResult.Accepted)
                    val gWest = checkNotNull(ctx.requestGroupMove("p2", westIds, Position(220.0, 300.0)))
                    for (id in westIds) check(ctx.submitGroupMove(MoveAction("west-$id", "p2", id, Position(220.0, 300.0), ctx.world), gWest) is ActionResult.Accepted)
                },
            ),
            "opposed-corridor-8v8" to ProbeSceneSpec(
                id = "opposed-corridor-8v8",
                title = "Probe: opposed-corridor-8v8 (3-tile corridor bidirectional 8v8)",
                defaultSteps = 1800,
                gates = listOf(
                    GateLine("corridor-exit-east", axisX = true, worldThreshold = 620.0, minOrtho = 175.0, maxOrtho = 245.0, positiveDirection = true),
                    GateLine("corridor-exit-west", axisX = true, worldThreshold = 280.0, minOrtho = 175.0, maxOrtho = 245.0, positiveDirection = false),
                ),
                build = { defs -> buildOpposedCorridorScenario("opposed-corridor-8v8", defs) },
                issueOrders = { ctx ->
                    val eastIds = (0 until 8).map { String.format("ceast-%02d", it) }
                    val westIds = (0 until 8).map { String.format("cwest-%02d", it) }
                    val gEast = checkNotNull(ctx.requestGroupMove("p1", eastIds, Position(760.0, 200.0)))
                    for (id in eastIds) check(ctx.submitGroupMove(MoveAction("ceast-$id", "p1", id, Position(760.0, 200.0), ctx.world), gEast) is ActionResult.Accepted)
                    val gWest = checkNotNull(ctx.requestGroupMove("p2", westIds, Position(140.0, 200.0)))
                    for (id in westIds) check(ctx.submitGroupMove(MoveAction("cwest-$id", "p2", id, Position(140.0, 200.0), ctx.world), gWest) is ActionResult.Accepted)
                },
            ),
            "mixed-overtake-wide" to ProbeSceneSpec(
                id = "mixed-overtake-wide",
                title = "Probe: mixed-overtake-wide (8 fast scouts overtaking 3 slow heavies)",
                defaultSteps = 1300,
                gates = listOf(
                    GateLine("overtake-line-x540", axisX = true, worldThreshold = 540.0, minOrtho = 110.0, maxOrtho = 330.0, positiveDirection = true),
                ),
                build = { defs -> buildMixedOvertakeScenario("mixed-overtake-wide", defs) },
                issueOrders = { ctx ->
                    val ids = listOf("heavy-0", "heavy-1", "heavy-2") + (0 until 8).map { "scout-$it" }
                    val click = Position(780.0, 220.0)
                    val group = checkNotNull(ctx.requestGroupMove("p1", ids, click))
                    for (id in ids) check(ctx.submitGroupMove(MoveAction("ov-$id", "p1", id, click, ctx.world), group) is ActionResult.Accepted)
                },
            ),
            "narrow-bridge-yield" to ProbeSceneSpec(
                id = "narrow-bridge-yield",
                title = "Probe: narrow-bridge-yield (1-tile bridge outside-mouth yield)",
                defaultSteps = 900,
                gates = listOf(
                    GateLine("bridge-east-exit", axisX = true, worldThreshold = 420.0, minOrtho = 195.0, maxOrtho = 225.0, positiveDirection = true),
                    GateLine("bridge-west-exit", axisX = true, worldThreshold = 300.0, minOrtho = 195.0, maxOrtho = 225.0, positiveDirection = false),
                ),
                build = { defs -> buildNarrowBridgeYieldScenario("narrow-bridge-yield", defs) },
                issueOrders = { ctx ->
                    check(ctx.submit(MoveAction("nb-east", "p1", "east-0", Position(580.0, 210.0), ctx.world)) is ActionResult.Accepted)
                    check(ctx.submit(MoveAction("nb-west", "p2", "west-0", Position(140.0, 210.0), ctx.world)) is ActionResult.Accepted)
                },
            ),
            "air-flock-rally" to ProbeSceneSpec(
                id = "air-flock-rally",
                title = "Probe: air-flock-rally (16 airborne choppers soft-separating at shared rally)",
                defaultSteps = 600,
                gates = emptyList(),
                build = { defs -> buildAirFlockScenario("air-flock-rally", defs) },
                issueOrders = { ctx ->
                    val click = Position(540.0, 240.0)
                    for (i in 0 until 16) check(ctx.submit(MoveAction("air-$i", "p1", "chopper-$i", click, ctx.world)) is ActionResult.Accepted)
                },
            ),
            "open-50-217-166" to ProbeSceneSpec(
                id = "open-50-217-166",
                title = "Probe: open-50-217-166 (50 tanks converging on (217, 166) open ground)",
                defaultSteps = 1400,
                gates = emptyList(),
                build = { defs -> buildOpen50Scenario("open-50-217-166", defs) },
                issueOrders = { ctx ->
                    val ids = (0 until 50).map { String.format("u-%02d", it) }
                    val click = Position(217.0, 166.0)
                    val group = checkNotNull(ctx.requestGroupMove("p1", ids, click))
                    for (id in ids) check(ctx.submitGroupMove(MoveAction("open50-$id", "p1", id, click, ctx.world), group) is ActionResult.Accepted)
                },
            ),
            "culdesac-overflow-100" to ProbeSceneSpec(
                id = "culdesac-overflow-100",
                title = "Probe: culdesac-overflow-100 (known limitation monitor)",
                defaultSteps = 2200,
                gates = listOf(
                    GateLine("pocket-mouth-x560", axisX = true, worldThreshold = 560.0, minOrtho = 215.0, maxOrtho = 305.0, positiveDirection = true),
                ),
                build = { defs -> buildCulDeSacScenario("culdesac-overflow-100", defs) },
                issueOrders = { ctx ->
                    val ids = (0 until 100).map { String.format("cds-%02d", it) }
                    val click = Position(710.0, 260.0)
                    val group = checkNotNull(ctx.requestGroupMove("p1", ids, click))
                    for (id in ids) check(ctx.submitGroupMove(MoveAction("cds100-$id", "p1", id, click, ctx.world), group) is ActionResult.Accepted)
                },
            ),
        )
    }

    fun runSceneProbe(
        spec: ProbeSceneSpec,
        definitions: Map<String, UnitDefinition>,
        outputDir: Path,
        stepsOverride: Int? = null,
        renderSheets: Boolean = true,
    ): JsonObject {
        val steps = stepsOverride ?: spec.defaultSteps
        check(steps > 0) { "steps must be positive: $steps" }
        Files.createDirectories(outputDir)

        val scenario = spec.build(definitions)
        val init = SimulationContext.initialize(scenario)
        check(init is InitResult.Accepted) { "Init rejected for ${spec.id}: $init" }
        val ctx = init.context

        val observations = ArrayList<Observation>(steps + 1)
        val stepNanos = LongArray(steps)
        val stepAllocBytes = LongArray(steps)

        val sunThreadBean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        sunThreadBean?.let {
            if (it.isThreadAllocatedMemorySupported && !it.isThreadAllocatedMemoryEnabled) {
                it.isThreadAllocatedMemoryEnabled = true
            }
        }
        val threadId = Thread.currentThread().threadId()
        val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
        val gcCountBefore = gcBeans.sumOf { max(0L, it.collectionCount) }
        val gcTimeMsBefore = gcBeans.sumOf { max(0L, it.collectionTime) }
        val wallSimStart = System.nanoTime()

        try {
            spec.issueOrders(ctx)
            observations += ctx.observe()
            for (i in 0 until steps) {
                val tickStep = Step(i.toLong(), 1.0, "sim-tick")
                val allocBefore = sunThreadBean?.getThreadAllocatedBytes(threadId) ?: 0L
                val t0 = System.nanoTime()
                val obs = ctx.advance(tickStep)
                val dt = (System.nanoTime() - t0).coerceAtLeast(0L)
                val allocAfter = sunThreadBean?.getThreadAllocatedBytes(threadId) ?: 0L
                stepNanos[i] = dt
                stepAllocBytes[i] = (allocAfter - allocBefore).coerceAtLeast(0L)
                observations += obs
            }
        } finally {
            ctx.close()
        }

        val simWallSeconds = (System.nanoTime() - wallSimStart).coerceAtLeast(0L) / 1_000_000_000.0
        val gcCountDelta = gcBeans.sumOf { max(0L, it.collectionCount) } - gcCountBefore
        val gcTimeMsDelta = gcBeans.sumOf { max(0L, it.collectionTime) } - gcTimeMsBefore

        // Gate crossing throughput analysis on authoritative observations
        val gateStats = spec.gates.map { gate ->
            val crossedUnits = HashSet<String>()
            var firstStep: Long? = null
            var lastStep: Long? = null
            var prevByUnit = observations.first().units.associate { it.unitId to it.position }
            for (idx in 1 until observations.size) {
                val obs = observations[idx]
                val stepIdx = idx.toLong() - 1L
                for (u in obs.units) {
                    if (u.unitId in crossedUnits) continue
                    val prev = prevByUnit[u.unitId] ?: continue
                    if (gate.crossed(prev, u.position)) {
                        crossedUnits += u.unitId
                        if (firstStep == null) firstStep = stepIdx
                        lastStep = stepIdx
                    }
                }
                prevByUnit = obs.units.associate { it.unitId to it.position }
            }
            val activeWindowSeconds = if (firstStep != null && lastStep != null && lastStep >= firstStep) {
                max(1L, lastStep - firstStep + 1L) / 60.0
            } else 0.0
            val throughputPerSec = if (activeWindowSeconds > 0.0) crossedUnits.size / activeWindowSeconds else 0.0
            buildJsonObject {
                put("gateId", gate.id)
                put("crossedUnits", crossedUnits.size)
                put("firstCrossStep", firstStep?.let(::JsonPrimitive) ?: JsonNull)
                put("lastCrossStep", lastStep?.let(::JsonPrimitive) ?: JsonNull)
                put("throughputUnitsPerSec", round3(throughputPerSec))
            }
        }

        val diagnosticGates = spec.gates.map { gate ->
            val halfDepth = 20.0
            val entry = if (gate.positiveDirection) gate.worldThreshold - halfDepth * 2.0 else gate.worldThreshold + halfDepth * 2.0
            DiagnosticGateLine(
                id = gate.id,
                axisX = gate.axisX,
                entryThreshold = entry,
                exitThreshold = gate.worldThreshold,
                minOrtho = gate.minOrtho,
                maxOrtho = gate.maxOrtho,
                positiveDirection = gate.positiveDirection,
            )
        }
        val sequence = DiagnosticSceneProjector.projectSequence(observations, scenario.unitDefinitions, gates = diagnosticGates)
        val gateSummaryById = sequence.gateSummaries.associateBy { it.gateId }
        val enrichedGateStats = gateStats.map { obj ->
            val gid = obj["gateId"]!!.jsonPrimitive.content
            val gs = gateSummaryById[gid]
            buildJsonObject {
                for ((k, v) in obj) put(k, v)
                if (gs != null) {
                    put("enteredUnits", gs.enteredCount)
                    put("sternExitedUnits", gs.sternExitedCount)
                    put("targetSideSettledUnits", gs.targetSideSettledCount)
                    put("firstEntryStep", gs.firstEntryStep?.let(::JsonPrimitive) ?: JsonNull)
                    put("maxExitGapSteps", gs.maxExitGapSteps)
                }
            }
        }
        var contactSheetPathStr: String? = null
        var peakZoomPathStr: String? = null
        if (renderSheets) {
            val contactSheet = outputDir.resolve("${spec.id}-contact-sheet.png")
            val peakZoom = outputDir.resolve("${spec.id}-peak-zoom.png")
            val rendered = DiagnosticContactSheetRenderer.writeArtifacts(
                sequence = sequence,
                map = scenario.map,
                title = spec.title,
                contactSheetPath = contactSheet,
                peakZoomPath = peakZoom,
            )
            contactSheetPathStr = rendered.contactSheetPath.toString()
            peakZoomPathStr = rendered.peakZoomPath?.toString()
        }

        val sortedNanos = stepNanos.copyOf().apply { sort() }
        val sortedAlloc = stepAllocBytes.copyOf().apply { sort() }
        val steadyStart = min(30, steps - 1)
        val steadySlice = stepAllocBytes.copyOfRange(steadyStart, steps)
        val steadyMeanAlloc = if (steadySlice.isNotEmpty()) steadySlice.average() else 0.0

        val finalFrame = sequence.frames.last()
        val maxStalled = sequence.frames.maxOfOrNull { it.stalledCount } ?: 0
        val maxContacts = sequence.frames.maxOfOrNull { it.hardContactCount } ?: 0

        val report = buildJsonObject {
            put("sceneId", spec.id)
            put("title", spec.title)
            put("steps", steps)
            put("simWallSeconds", round3(simWallSeconds))
            put("totalMobileUnits", finalFrame.units.size)
            put("finalArrivedCount", finalFrame.arrivedCount)
            put("finalActiveMoveCount", finalFrame.activeMoveCount)
            put("firstAutonomousMoveStep", sequence.firstAutonomousMoveStep?.let(::JsonPrimitive) ?: JsonNull)
            put("firstHardContactStep", sequence.firstHardContactStep?.let(::JsonPrimitive) ?: JsonNull)
            put("peakCongestionStep", sequence.peakCongestionStep?.let(::JsonPrimitive) ?: JsonNull)
            put("lastArrivalStep", sequence.lastArrivalStep?.let(::JsonPrimitive) ?: JsonNull)
            put("maxStalled2sUnits", maxStalled)
            put("maxHardContactPairs", maxContacts)
            put("stepLatencyMs", buildJsonObject {
                put("mean", round4(stepNanos.average() / 1_000_000.0))
                put("p50", round4(percentileLong(sortedNanos, 0.50) / 1_000_000.0))
                put("p95", round4(percentileLong(sortedNanos, 0.95) / 1_000_000.0))
                put("p99", round4(percentileLong(sortedNanos, 0.99) / 1_000_000.0))
                put("max", round4(sortedNanos.last() / 1_000_000.0))
            })
            put("heapAllocation", buildJsonObject {
                put("supported", sunThreadBean?.isThreadAllocatedMemorySupported == true)
                put("totalSimBytes", stepAllocBytes.sum())
                put("steadyStateMeanBytesPerStep", kotlin.math.round(steadyMeanAlloc).toLong())
                put("p50BytesPerStep", percentileLong(sortedAlloc, 0.50))
                put("p99BytesPerStep", percentileLong(sortedAlloc, 0.99))
                put("gcCountDelta", gcCountDelta)
                put("gcTimeMsDelta", gcTimeMsDelta)
            })
            put("gateThroughput", JsonArray(enrichedGateStats))
            put("contactSheet", contactSheetPathStr?.let(::JsonPrimitive) ?: JsonNull)
            put("peakZoom", peakZoomPathStr?.let(::JsonPrimitive) ?: JsonNull)
        }
        Files.writeString(
            outputDir.resolve("${spec.id}-probe.json"),
            Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n",
        )
        return report
    }

    private fun percentileLong(sorted: LongArray, p: Double): Long {
        if (sorted.isEmpty()) return 0L
        val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun round3(v: Double): Double = kotlin.math.round(v * 1000.0) / 1000.0
    private fun round4(v: Double): Double = kotlin.math.round(v * 10000.0) / 10000.0

    private fun buildRocksScenario(id: String, definitions: Map<String, UnitDefinition>, mixed: Boolean): Scenario {
        val w = 74
        val h = 40
        val gates = listOf(Triple(24, 12, 23), Triple(34, 15, 20), Triple(44, 11, 25))
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else gates.any { (start, narrow, wide) ->
                x in start until (start + 2) && y != narrow && y !in wide until (wide + 6)
            }
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val units = (0 until 100).map { index ->
            val x = 420.0 - 36.0 * (index / 10)
            val y = 180.0 + 36.0 * (index % 10)
            val type = if (mixed && index % 4 == 3) "slow_heavy" else "c_tank"
            UnitSpawn(String.format("tank-%02d", index), "p1", type, Position(x, y), 90.0)
        }
        return baseScenario(id, map, definitions, units)
    }

    private fun buildOpposedOpenScenario(id: String, definitions: Map<String, UnitDefinition>): Scenario {
        val w = 50
        val h = 30
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            x == 0 || x == w - 1 || y == 0 || y == h - 1
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val units = buildList {
            for (i in 0 until 25) {
                add(UnitSpawn(String.format("east-%02d", i), "p1", "c_tank",
                    Position(240.0 - (i / 5) * 28.0, 244.0 + (i % 5) * 28.0), 90.0))
            }
            for (i in 0 until 25) {
                add(UnitSpawn(String.format("west-%02d", i), "p2", "c_tank",
                    Position(760.0 + (i / 5) * 28.0, 244.0 + (i % 5) * 28.0), 270.0))
            }
        }
        return baseScenario(id, map, definitions, units)
    }

    private fun buildOpposedCorridorScenario(id: String, definitions: Map<String, UnitDefinition>): Scenario {
        val w = 45
        val h = 20
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else (x in 14..30) && (y !in 9..11)
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val units = buildList {
            for (i in 0 until 8) {
                add(UnitSpawn(String.format("ceast-%02d", i), "p1", "c_tank",
                    Position(220.0 - (i / 2) * 26.0, 192.0 + (i % 2) * 16.0), 90.0))
            }
            for (i in 0 until 8) {
                add(UnitSpawn(String.format("cwest-%02d", i), "p2", "c_tank",
                    Position(680.0 + (i / 2) * 26.0, 192.0 + (i % 2) * 16.0), 270.0))
            }
        }
        return baseScenario(id, map, definitions, units)
    }

    private fun buildMixedOvertakeScenario(id: String, definitions: Map<String, UnitDefinition>): Scenario {
        val w = 44
        val h = 22
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else (y < 6 || y > 15)
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val units = buildList {
            add(UnitSpawn("heavy-0", "p1", "slow_heavy", Position(340.0, 220.0), 90.0))
            add(UnitSpawn("heavy-1", "p1", "slow_heavy", Position(280.0, 190.0), 90.0))
            add(UnitSpawn("heavy-2", "p1", "slow_heavy", Position(280.0, 250.0), 90.0))
            for (i in 0 until 8) {
                add(UnitSpawn("scout-$i", "p1", "fast_scout", Position(190.0 - (i / 4) * 24.0, 185.0 + (i % 4) * 24.0), 90.0))
            }
        }
        return baseScenario(id, map, definitions, units)
    }

    private fun buildNarrowBridgeYieldScenario(id: String, definitions: Map<String, UnitDefinition>): Scenario {
        val w = 36
        val h = 21
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else (x in 15..20) && (y != 10)
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val units = listOf(
            UnitSpawn("east-0", "p1", "c_tank", Position(330.0, 210.0), 90.0),
            UnitSpawn("west-0", "p2", "c_tank", Position(440.0, 210.0), 270.0),
        )
        return baseScenario(id, map, definitions, units)
    }

    private fun buildAirFlockScenario(id: String, definitions: Map<String, UnitDefinition>): Scenario {
        val w = 36
        val h = 24
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            x == 0 || x == w - 1 || y == 0 || y == h - 1
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val units = (0 until 16).map { i ->
            UnitSpawn("chopper-$i", "p1", "helicopter", Position(100.0 + (i / 4) * 34.0, 160.0 + (i % 4) * 34.0), 90.0)
        }
        return baseScenario(id, map, definitions, units)
    }

    private fun buildOpen50Scenario(id: String, definitions: Map<String, UnitDefinition>): Scenario {
        val w = 40
        val h = 30
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            x == 0 || x == w - 1 || y == 0 || y == h - 1
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val units = (0 until 50).map { i ->
            val col = i % 10
            val row = i / 10
            UnitSpawn(String.format("u-%02d", i), "p1", "c_tank", Position(420.0 + col * 24.0, 260.0 + row * 24.0), 270.0)
        }
        return baseScenario(id, map, definitions, units)
    }

    private fun buildCulDeSacScenario(id: String, definitions: Map<String, UnitDefinition>): Scenario {
        val w = 44
        val h = 26
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else x >= 28 && (y < 11 || y > 14 || x > 37)
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val units = (0 until 100).map { i ->
            UnitSpawn(String.format("cds-%02d", i), "p1", "c_tank", Position(420.0 - (i / 10) * 24.0, 160.0 + (i % 10) * 22.0), 90.0)
        }
        return baseScenario(id, map, definitions, units)
    }

    private fun baseScenario(
        id: String,
        map: MapDefinition,
        definitions: Map<String, UnitDefinition>,
        units: List<UnitSpawn>,
    ): Scenario = Scenario(
        scenarioId = id,
        map = map,
        unitDefinitions = definitions,
        players = listOf(Player("p1", PlayerControl.LOCAL, "allies"), Player("p2", PlayerControl.LOCAL, "allies")),
        units = units,
        coordinateModel = CoordinateModel("public.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
        timeModel = TimeModel("public.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"),
        movementContract = MovementContract(16.0, 4.0),
        economyContract = EconomyContract(mapOf("p1" to 30000L, "p2" to 30000L)),
        combatContract = CombatContract(
            worldSeed = 12345,
            initialFrameCounter = -1,
            unitObjectIds = units.mapIndexed { idx, u -> u.unitId to (8L + idx) }.toMap(),
            nextObjectId = 8L + units.size,
        ),
    )
}

fun main(args: Array<String>) {
    var repoRoot: Path? = null
    var outputDir: Path? = null
    var sceneFilter = "rocks-small-100"
    var stepsOverride: Int? = null
    var renderSheets = true

    var idx = 0
    while (idx < args.size) {
        when (val arg = args[idx++]) {
            "--repo-root" -> repoRoot = Path.of(args.getOrElse(idx++) { error("Missing value for --repo-root") })
            "--output" -> outputDir = Path.of(args.getOrElse(idx++) { error("Missing value for --output") })
            "--scene" -> sceneFilter = args.getOrElse(idx++) { error("Missing value for --scene") }
            "--steps" -> stepsOverride = args.getOrElse(idx++) { error("Missing value for --steps") }.toInt()
            "--sheets" -> renderSheets = args.getOrElse(idx++) { error("Missing value for --sheets") }.toBooleanStrict()
            "--list" -> {
                println(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(MovementMicroProbe.ALL_SCENE_IDS.map(::JsonPrimitive))))
                return
            }
            else -> error("Unknown argument: $arg")
        }
    }

    val resolvedRepo = (repoRoot ?: generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
        .firstOrNull { Files.isRegularFile(it.resolve("AGENTS.md")) || Files.isDirectory(it.resolve("SimulationTest")) }
        ?: error("Cannot locate repository root; pass --repo-root")).toAbsolutePath().normalize()
    val resolvedOut = (outputDir ?: Files.createTempDirectory("reforge-movement-probe-")).toAbsolutePath().normalize()
    Files.createDirectories(resolvedOut)

    val specs = MovementMicroProbe.sceneSpecs()
    val selectedIds = if (sceneFilter == "all") MovementMicroProbe.ALL_SCENE_IDS
    else sceneFilter.split(',').map(String::trim).filter(String::isNotEmpty)
    for (id in selectedIds) {
        require(id in specs) { "Unknown probe scene '$id'; available=${specs.keys}" }
    }

    val definitions = MovementMicroProbe.loadStandardDefinitions(resolvedRepo)
    val reports = selectedIds.map { id ->
        val spec = specs.getValue(id)
        MovementMicroProbe.runSceneProbe(
            spec = spec,
            definitions = definitions,
            outputDir = resolvedOut.resolve(id),
            stepsOverride = stepsOverride,
            renderSheets = renderSheets,
        )
    }
    val summary = buildJsonObject {
        put("schemaVersion", 1)
        put("outputDir", resolvedOut.toString())
        put("scenes", JsonArray(reports))
    }
    val text = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), summary)
    Files.writeString(resolvedOut.resolve("probe-summary.json"), text + "\n")
    println(text)
}
