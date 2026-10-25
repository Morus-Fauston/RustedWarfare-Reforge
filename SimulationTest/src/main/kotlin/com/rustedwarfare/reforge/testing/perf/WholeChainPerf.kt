package com.rustedwarfare.reforge.testing.perf

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.path.*
import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.testing.json
import com.rustedwarfare.reforge.testing.MotionCenterGeometry
import kotlinx.serialization.json.*
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import kotlin.math.hypot

data class WholeChainFixture(val kind: String, val count: Int, val retention: TerrainRetention,
    val scenario: Scenario, val goals: Map<String, Position>)
data class WholeChainStep(val step: Int, val advanceNanos: Long, val sampleNanos: Long,
    val verifyNanos: Long, val traceNanos: Long, val heapBytes: Long,
    val observation: Observation, val known: List<Boolean?>, val visible: List<Boolean>, val samples: List<PathSearchSample>)
data class WholeChainRun(val fixture: WholeChainFixture, val requests: Int,
    val steps: List<WholeChainStep>, val initial: Observation, val initialKnown: List<Boolean?>,
    val arrived: Set<String>, val cancelled: Set<String>, val moved: Set<String>,
    val reuseEntitySteps: Long, val initNanos: Long, val submitNanos: Long, val firstResponseNanos: Long, val wallNanos: Long,
    val memory: JsonObject, val fullTrace: Boolean) {
    val searches get() = steps.flatMap { it.samples }
}

/** Existing automatic-vision fixture, real diagnostics and independent geometry, with bounded load. */
object WholeChainPerf {
    val KINDS = listOf("OPEN", "DETOUR", "DISPERSED", "SAME_BOUNDARY", "HIDDEN", "UNREACHABLE")
    const val MAX_STEPS = 2500
    const val TAIL = 8
    const val WIDTH = 50
    const val HEIGHT = 40
    const val OFFSET = 200.0

    /** Fixed independent truth geometry, prepared once per online or offline qualification. */
    private class StepOracle(val fixture: WholeChainFixture) {
        private val map = fixture.scenario.map
        val truth = (0 until map.widthTiles * map.heightTiles)
            .filter { map.blocking.isBlocked(it % map.widthTiles, it / map.widthTiles) }
            .map { intArrayOf(it % map.widthTiles, it / map.widthTiles) }
    }

    /** Per-step memoization of this tool's own exact predicate, never production geometry. */
    private class StepGeometry(private val map: MapDefinition, private val truth: List<IntArray>,
                               private val known: List<IntArray>) {
        private val truthResults = HashMap<List<Position>, Boolean>()
        private val knownResults = HashMap<List<Position>, Boolean>()
        fun allows(points: List<Position>, walls: List<IntArray>): Boolean {
            check(walls === truth || walls === known)
            val results = if (walls === truth) truthResults else knownResults
            return results.getOrPut(points.toList()) {
                MotionCenterGeometry.centerPath(points, walls, map.widthTiles, map.heightTiles, 20, 20, 11.0)
            }
        }
    }

    /** Same List contracts and values, without retaining two reference arrays for every step. */
    private class TerrainColumns(cells: List<CellKnowledge>) {
        private val values = ByteArray(cells.size) { index ->
            val cell = cells[index]
            ((when (cell.lastKnownBlocked) { null -> 0; false -> 1; true -> 2 }) or
                if (cell.visible) 4 else 0).toByte()
        }
        val known: List<Boolean?> = object : AbstractList<Boolean?>(), java.util.RandomAccess {
            override val size: Int get() = values.size
            override fun get(index: Int): Boolean? = when (values[index].toInt() and 3) {
                0 -> null; 1 -> false; else -> true
            }
        }
        val visible: List<Boolean> = object : AbstractList<Boolean>(), java.util.RandomAccess {
            override val size: Int get() = values.size
            override fun get(index: Int): Boolean = values[index].toInt() and 4 != 0
        }
    }

    private fun unitIndex(units: List<UnitObservation>): Map<String, UnitObservation> {
        val index = HashMap<String, UnitObservation>(units.size)
        for (unit in units) check(index.put(unit.unitId, unit) == null) { "Duplicate unit ID ${unit.unitId}" }
        return index
    }

    fun fixture(kind: String, count: Int, retention: TerrainRetention): WholeChainFixture {
        require(kind in KINDS && count in listOf(1, 10, 50))
        val base = AutomaticVisionPerf.hiddenDiscoveryFixture(retention).scenario
        val blocked = BooleanArray(WIDTH * HEIGHT) { kind in listOf("DETOUR", "HIDDEN", "UNREACHABLE") &&
            it % WIDTH == 24 && (kind == "UNREACHABLE" || it / WIDTH in 11..25) }
        val original = base.unitDefinitions.getValue("tank")
        val unit = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement, original.retainedUnconsumed,
            if (kind in listOf("DETOUR", "UNREACHABLE")) 60 else 3)
        val spawns = (0 until count).map { i -> UnitSpawn("u$i", "p1", "tank",
            if (kind == "DISPERSED") Position(OFFSET + 50.0 + 35 * (i % 5), OFFSET + 50.0 + 30 * (i / 5)) else Position(OFFSET + 50.0, OFFSET + 70.0), 90.0) }
        val goals = spawns.associate { spawn ->
            val i = spawn.unitId.drop(1).toInt()
            spawn.unitId to if (kind == "DISPERSED") Position(OFFSET + 410.0 + 30 * (i % 5), OFFSET + 50.0 + 30 * (i / 5)) else Position(OFFSET + 430.0, OFFSET + 70.0)
        }
        return WholeChainFixture(kind, count, retention, base.copy(scenarioId = "s08-v2-$kind-$count-${retention.name}",
            map = MapDefinition(WIDTH, HEIGHT, 20, 20, emptyList(), IntArray(WIDTH * HEIGHT) { 1 }, BlockingGrid(WIDTH, HEIGHT, blocked)),
            unitDefinitions = mapOf("tank" to unit), units = spawns), goals)
    }

    /** Append-only raw callback runs before the oracle, so a failed step is retained. */
    fun capture(f: WholeChainFixture, fullTrace: Boolean = false, raw: (JsonObject) -> Unit = {}): WholeChainRun {
        val started = System.nanoTime()
        val context = (SimulationContext.initialize(f.scenario) as? InitResult.Accepted)?.context ?: error("Initialization rejected")
        val initNanos = System.nanoTime() - started
        try {
            context.enablePathDiagnostics(context.world)
            val initial = context.observe()
            val initialKnown = context.knowledgeSnapshot(context.world, "p1").cells.map { it.lastKnownBlocked }
            raw(buildJsonObject { put("kind", "initial"); put("observation", initial.json()); put("case", f.kind)
                put("count", f.count); put("retention", f.retention.name); put("fullTrace", fullTrace) })
            val gcBefore = gc()
            val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == java.lang.management.MemoryType.HEAP }
            pools.forEach { it.resetPeakUsage() }
            val allocationBean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
            val allocationAvailable = allocationBean?.isThreadAllocatedMemorySupported == true && allocationBean.isThreadAllocatedMemoryEnabled
            val thread = Thread.currentThread().threadId()
            val allocatedBefore = if (allocationAvailable) allocationBean!!.getThreadAllocatedBytes(thread) else -1L
            val steps = mutableListOf<WholeChainStep>()
            val arrived = mutableSetOf<String>(); val cancelled = mutableSetOf<String>(); val moved = mutableSetOf<String>()
            var reuse = 0L; var terminalAt: Int? = null
            val begin = System.nanoTime()
            val submitStart = System.nanoTime()
            for ((id, goal) in f.goals) {
                val result = context.submit(MoveAction("s08-$id", "p1", id, goal, context.world, 0))
                check(result is ActionResult.Accepted && result.queuedForStep == 0L) { "Request rejected $id: $result" }
            }
            val submitNanos = System.nanoTime() - submitStart
            check(context.drainPathDiagnostics(context.world).isEmpty()) { "Search ran at submit" }
            var previous = initial; var knownBefore = initialKnown
            val oracle = StepOracle(f)
            val nearTimes = mutableMapOf<String,Double>()
            var firstResponseNanos = 0L
            for (step in 0 until MAX_STEPS) {
                check(System.nanoTime() - begin < 120_000_000_000L) { "120s batch deadline" }
                val t = System.nanoTime()
                val observed = context.advance(Step(step.toLong(), 1.0, "sim-tick"))
                val advanceEnd = System.nanoTime()
                val advanceNanos = advanceEnd - t
                if (step == 0) firstResponseNanos = advanceEnd - submitStart
                val sampleStart = System.nanoTime()
                val samples = context.drainPathDiagnostics(context.world)
                val knowledge = context.knowledgeSnapshot(context.world, "p1").cells
                val columns = TerrainColumns(knowledge)
                val known = columns.known; val visible = columns.visible
                val heap = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
                val sampleNanos = System.nanoTime() - sampleStart
                val rowStart = System.nanoTime()
                raw(buildJsonObject {
                    put("kind", "step"); put("step", step); put("advanceNanos", advanceNanos); put("sampleNanos", sampleNanos); put("heapBytes", heap)
                    put("observation", observed.json()); put("searches", JsonArray(samples.map(ReplanPerf::sampleJson)))
                    if (fullTrace) {
                        put("known", JsonArray(known.map { it?.let(::JsonPrimitive) ?: JsonNull }))
                        put("visible", JsonArray(visible.map(::JsonPrimitive)))
                    }
                })
                val traceNanos = System.nanoTime() - rowStart
                val verifyStart = System.nanoTime()
                verifyStep(oracle, previous, knownBefore, observed, known, visible, samples, step, nearTimes)
                val previousById = unitIndex(previous.units)
                val searchedIds = samples.mapTo(HashSet()) { it.unitId }
                for (unit in observed.units) {
                    val before = previousById.getValue(unit.unitId)
                    if (unit.position != before.position) moved += unit.unitId
                    if (before.moveTarget != null && unit.unitId !in searchedIds && unit.moveTarget != null) reuse++
                }
                observed.events.filter { it.kind == "movement-arrived" }.mapNotNullTo(arrived) { it.unitId }
                observed.events.filter { it.kind == "path-unreachable" }.mapNotNullTo(cancelled) { it.unitId }
                val verifyNanos = System.nanoTime() - verifyStart
                steps += WholeChainStep(step, advanceNanos, sampleNanos, verifyNanos, traceNanos, heap, observed, known, visible, samples)
                if (observed.units.all { it.moveTarget == null && it.speed == 0.0 && it.movementState == MovementState.IDLE }) {
                    if (terminalAt == null) terminalAt = step
                    if (step - terminalAt >= TAIL) break
                } else check(terminalAt == null) { "Terminal state resurrected" }
                previous = observed; knownBefore = known
            }
            val wall = System.nanoTime() - begin
            val gcAfter = gc()
            val allocatedAfter = if (allocationAvailable) allocationBean!!.getThreadAllocatedBytes(thread) else -1L
            val memory = buildJsonObject {
                put("heapStepSampledPeakBytes", steps.maxOf { it.heapBytes }); put("heapPoolPeakBytes", pools.sumOf { it.peakUsage.used })
                put("heapPeakScope", "step samples; sum of per-pool MXBean peaks since reset (not simultaneous); excludes native/GPU/process")
                put("gcCount", delta(gcBefore.first, gcAfter.first)); put("gcMillis", delta(gcBefore.second, gcAfter.second))
                put("threadAllocatedBytes", delta(allocatedBefore, allocatedAfter))
                put("allocationScope", if (allocatedBefore >= 0 && allocatedAfter >= allocatedBefore) "current runner thread including probes/oracle/trace; excludes other threads/native/GPU" else "unavailable: unsupported/disabled/negative ThreadMXBean allocation")
            }
            val run = WholeChainRun(f, f.count, steps, initial, initialKnown, arrived, cancelled, moved, reuse,
                initNanos, submitNanos, firstResponseNanos, wall, memory, fullTrace)
            qualify(run)
            raw(buildJsonObject { put("kind", "completion"); put("summary", summary(run)) })
            return run
        } finally { context.close() }
    }

    fun qualify(run: WholeChainRun) {
        val f = run.fixture
        check(run.requests == f.count && run.steps.isNotEmpty() && run.steps.size <= MAX_STEPS)
        check(run.steps.map { it.step } == run.steps.indices.toList()) { "Missing steps" }
        check(run.searches.filter { it.reason == PathSearchReason.USER_MOVE }.map { it.unitId }.toSet() == f.goals.keys)
        check(run.searches.count { it.reason == PathSearchReason.USER_MOVE } == f.count) { "Idle entities counted as load" }
        var previous = run.initial; var known = run.initialKnown
        val oracle = StepOracle(f)
        val nearTimes = mutableMapOf<String,Double>()
        for (step in run.steps) {
            verifyStep(oracle, previous, known, step.observation, step.known, step.visible, step.samples, step.step, nearTimes)
            previous = step.observation; known = step.known
        }
        val terminal = run.steps.takeLast(TAIL + 1)
        check(terminal.size == TAIL + 1 && terminal.all { s -> s.observation.units.all { it.moveTarget == null && it.pathPoints.isEmpty() && it.speed == 0.0 && it.movementState == MovementState.IDLE && it.angularVelocity.isFinite() } }) { "Missing terminal tail within 2500" }
        for ((a, b) in terminal.zipWithNext()) check(a.observation.units == b.observation.units) { "Stopped terminal drift" }
        val arrivalEvents = run.steps.flatMap { it.observation.events }.filter { it.kind == "movement-arrived" }
        check(arrivalEvents.mapNotNull { it.unitId }.toSet() == run.arrived && arrivalEvents.size == run.arrived.size) { "Arrival not exactly once" }
        if (f.kind == "UNREACHABLE") check(run.cancelled == f.goals.keys && run.arrived.isEmpty()) { "Expected unreachable" }
        else check(run.arrived == f.goals.keys && run.cancelled.isEmpty() && run.moved == f.goals.keys) { "Missing actual motion/arrival" }
        if (f.kind == "HIDDEN") {
            check(run.initialKnown.none { it == true }) { "Hidden wall initially known" }
            check(run.searches.any { it.reason == PathSearchReason.KNOWLEDGE_REPLAN }) { "No discovery replan" }
            check(run.steps.any { s -> s.observation.units.any { u -> u.pathPoints.any { it.y != f.goals.getValue(u.unitId).y } } }) { "No actual detour" }
        }
        if (f.kind in listOf("OPEN", "HIDDEN", "DISPERSED", "SAME_BOUNDARY")) {
            check(run.steps.zipWithNext().any { (a, b) -> a.visible.indices.any { a.visible[it] && !b.visible[it] } }) { "No actual loss-of-sight knowledge transition" }
        }
    }

    private fun verifyStep(oracle: StepOracle, previous: Observation, before: List<Boolean?>,
        observed: Observation, known: List<Boolean?>, visible: List<Boolean>, samples: List<PathSearchSample>, step: Int,
        nearTimes: MutableMap<String,Double>) {
        val f = oracle.fixture
        check(observed.worldId == previous.worldId && observed.scenarioId == f.scenario.scenarioId && observed.stepIndex == step.toLong() && observed.simulationTime == step + 1.0) { "Observation identity/time" }
        check(observed.units.size == f.count && observed.units.map { it.unitId }.toSet() == f.goals.keys)
        val previousById = unitIndex(previous.units)
        check(previousById.keys == f.goals.keys) { "Previous unit IDs differ" }
        val samplesByUnit = samples.groupBy { it.unitId }
        val map = f.scenario.map; val width = map.widthTiles; val height = map.heightTiles
        check(known.size == width * height && visible.size == width * height)
        if (step == 0) {
            // queuedForStep belongs to the submit acknowledgement; execution emits fresh results.
            check(observed.actionResults.size == f.count && observed.actionResults.all { it is ActionResult.Accepted }) { "Actual accepted request count differs" }
            check(observed.actionResults.map { it.actionId }.toSet() == f.goals.keys.map { "s08-$it" }.toSet()) { "Wrong submitted action identity" }
        } else check(observed.actionResults.isEmpty()) { "Unexpected later user request" }
        val truth = oracle.truth
        val knownWalls = before.indices.filter { before[it] == true }.map { intArrayOf(it % width, it / width) }
        val stepGeometry = StepGeometry(map, truth, knownWalls)
        fun geometry(points: List<Position>, walls: List<IntArray>) = stepGeometry.allows(points, walls)
        val radius = f.scenario.unitDefinitions.getValue("tank").fogOfWarSightRange
        val visionPositions = observed.units.map { it.position }.distinct()
        for (cell in 0 until width * height) {
            val expectedVisible = visionPositions.any { position ->
                val dx = cell % width + .5 - position.x / 20; val dy = cell / width + .5 - position.y / 20
                dx * dx + dy * dy <= radius * radius
            }
            check(visible[cell] == expectedVisible) { "Wrong automatic vision cell $cell" }
            val expected = if (expectedVisible) map.blocking.isBlocked(cell % width, cell / width)
                else if (f.retention == TerrainRetention.MEMORY) before[cell] else null
            check(known[cell] == expected) { "Wrong retention cell $cell" }
        }
        for (u in observed.units) {
            val old = previousById.getValue(u.unitId); val goal = f.goals.getValue(u.unitId)
            check(u.ownerPlayerId == "p1" && u.typeId == "tank")
            if (step > 0 && old.moveTarget == null) check(u.moveTarget == null) { "Ended move resurrected" }
            check(u.heading.isFinite() && u.heading in 0.0..<360.0 && u.speed.isFinite() && u.speed in 0.0..1.1 && u.angularVelocity.isFinite())
            // This fixed low-speed fixture checks final occupancy and D-083 only.
            // It does not substitute a center sweep for actual C candidate/writeback validation.
            check(geometry(listOf(u.position), truth)) { "Actual final center/map boundary illegal step$step ${u.unitId}" }
            val delta=observed.simulationTime-previous.simulationTime
            if(step==0)nearTimes[u.unitId]=0.0
            val dx=old.position.x-goal.x;val dy=old.position.y-goal.y
            if((old.moveTarget==goal || step==0) && dx*dx+dy*dy<1681.0)
                nearTimes[u.unitId]=(nearTimes[u.unitId]?:0.0)+delta
            if(observed.events.any { it.kind=="movement-arrived" && it.unitId==u.unitId })
                check(u.moveTarget==null && u.pathPoints.isEmpty()) { "Normal clear retained target/path" }
            if (u.moveTarget == null) {
                check(u.pathPoints.isEmpty())
                if (old.moveTarget != null) check(observed.events.any { it.unitId == u.unitId && it.kind in setOf("movement-arrived", "path-unreachable") }) { "Move vanished" }
                if (observed.events.any { it.kind == "movement-arrived" && it.unitId == u.unitId }) {
                    val time=nearTimes.getValue(u.unitId);val capture=if(time>340.0)36.0 else if(time>240.0)16.0 else 7.0
                    check(old.moveTarget == goal && old.pathPoints.lastOrNull()==goal &&
                        dx*dx+dy*dy<capture*capture) { "Arrival outside strict normal pre-step capture radius $capture" }
                }
                run {
                    check(u.heading == old.heading) { "Coasting changed heading" }
                    val movement = f.scenario.unitDefinitions.getValue("tank").movement
                    val expectedSpeed = maxOf(0.0, old.speed - movement.moveDecelerationSpeed*movement.moveSpeed*delta)
                    val expectedOmega = old.angularVelocity
                    check(u.speed == expectedSpeed && u.angularVelocity == expectedOmega) { "Wrong post-command deceleration" }
                    val angle = Math.toRadians(old.heading)
                    val expectedPosition = Position(old.position.x + kotlin.math.sin(angle) * expectedSpeed*delta,
                        old.position.y - kotlin.math.cos(angle) * expectedSpeed*delta)
                    if(geometry(listOf(expectedPosition),truth))
                        check(hypot(u.position.x - expectedPosition.x, u.position.y - expectedPosition.y) < 1e-9) { "Wrong DIRECT coasting displacement" }
                }
            } else {
                check(u.moveTarget == goal && u.pathPoints.isNotEmpty() && u.pathPoints.last() == goal)
                check(geometry(u.pathPoints, knownWalls)) { "Remaining path illegal step$step" }
                if (old.pathPoints.isNotEmpty() && !geometry(listOf(old.position, old.pathPoints.first()), knownWalls))
                    check(samples.any { it.unitId == u.unitId && it.reason in setOf(PathSearchReason.KNOWLEDGE_REPLAN, PathSearchReason.ROUTE_REPAIR) }) { "Missing pre-move repair" }
            }
        }
        for (s in samples) {
            check(s.unitId in f.goals && s.actionId == "s08-${s.unitId}" && s.scheduledStep == 0L && s.executionStep == step.toLong()) { "Search provenance" }
            check(s.actualSearchCalls == 1L && s.usedPlayerKnowledge && s.outcome != PathSearchOutcome.FAILED)
            check(s.searchStart != null && s.searchTarget != null && geometry(listOfNotNull(s.searchStart), knownWalls)) { "Search source illegal" }
            check(s.searchTarget == f.goals[s.unitId] || (s.reason == PathSearchReason.ROUTE_REPAIR && s.searchTarget in previousById.getValue(s.unitId).pathPoints)) { "Search target differs" }
            check((s.outcome == PathSearchOutcome.FOUND) == (s.pathPointCount > 0))
            if (s.reason == PathSearchReason.USER_MOVE) check(step == 0 && s.searchStart == f.scenario.units.single { it.unitId == s.unitId }.position)
            if (s.reason == PathSearchReason.KNOWLEDGE_REPLAN) check(step > 0)
        }
        for (u in observed.units) qualifyAnchors(previousById.getValue(u.unitId), u,
            f.goals.getValue(u.unitId), samplesByUnit[u.unitId].orEmpty(), step) { geometry(it, knownWalls) }
    }

    /** Same fixed-fixture pre-movement prefix/fallback contract as AutomaticVisionPerf. */
    private fun qualifyAnchors(old: UnitObservation, current: UnitObservation, goal: Position,
        samples: List<PathSearchSample>, step: Int, geometry: (List<Position>) -> Boolean) {
        fun safe(a: Position, b: Position) = geometry(listOf(a, b))
        val prefix = mutableListOf<Position>(); var from = old.position
        for (point in old.pathPoints) { if (!safe(from, point)) break; prefix += point; from = point }
        val needsRepair = old.pathPoints.isNotEmpty() && !safe(old.position, old.pathPoints.first())
        val nearCornerStall=old.pathPoints.size>1 &&
            (old.position.x-old.pathPoints.first().x).let { dx->
                val dy=old.position.y-old.pathPoints.first().y;dx*dx+dy*dy<9.0 } && !safe(old.position,old.pathPoints[1])
        check(samples.map { it.reason }.distinct().size <= 1 && samples.size <= 2) { "Mixed/repeated phases require intermediate route snapshot" }
        if (needsRepair || nearCornerStall) check(samples.any { it.reason in setOf(PathSearchReason.KNOWLEDGE_REPLAN, PathSearchReason.ROUTE_REPAIR) }) { "Missing publicly required pre-move repair search" }
        for ((index, sample) in samples.withIndex()) {
            if (index > 0) check(samples[index - 1].outcome == PathSearchOutcome.UNREACHABLE) { "Duplicate search after success" }
            when (sample.reason) {
                PathSearchReason.ATTACK_PURSUIT -> error("Movement-only whole-chain profile does not support attack pursuit")
                PathSearchReason.USER_MOVE -> check(step == 0 && samples.size == 1 && sample.searchStart == old.position && sample.searchTarget == goal && sample.preservedPrefixPointCount == 0)
                PathSearchReason.KNOWLEDGE_REPLAN -> {
                    val hasPrefix = prefix.isNotEmpty() && prefix.size < old.pathPoints.size
                    check(sample.searchTarget == goal && sample.searchStart == if (index == 0 && hasPrefix) prefix.last() else old.position) { "Invalid retained-prefix anchor" }
                    check(sample.preservedPrefixPointCount == if (index == 0 && hasPrefix && sample.outcome == PathSearchOutcome.FOUND) prefix.size else 0) { "Invalid retained-prefix count" }
                    if (index > 0) check(hasPrefix) { "Fallback without failed prefix" }
                }
                PathSearchReason.ROUTE_REPAIR -> {
                    val tailSafe = old.pathPoints.size > 1 && old.pathPoints.zipWithNext().all { (a, b) -> safe(a, b) }
                    check(sample.searchStart == old.position && sample.preservedPrefixPointCount == 0) { "Invalid repair anchor/prefix" }
                    val directGoal=index==0 && samples.size==1 && sample.searchTarget==goal
                    // An extra local repair's precise private trigger is not observed by this tool.
                    val connector=index==0 && needsRepair && !nearCornerStall && tailSafe && sample.searchTarget==old.pathPoints.first()
                    val fallback=index>0 && needsRepair && tailSafe && sample.searchTarget==goal
                    check(directGoal || connector || fallback) { "Invalid repair target" }
                }
            }
            if (sample.outcome == PathSearchOutcome.UNREACHABLE && index == samples.lastIndex)
                check(current.moveTarget == null && current.pathPoints.isEmpty()) { "Unreachable search did not cancel move" }
        }
    }

    fun stats(values: List<Long>) = buildJsonObject {
        put("n", values.size)
        for ((name, q) in listOf("p50" to .5, "p95" to .95)) put(name, if (values.isEmpty()) JsonNull else JsonPrimitive(DeterministicStatistics.nearestRank(values, q)))
        put("max", values.maxOrNull()?.let(::JsonPrimitive) ?: JsonNull)
    }
    fun summary(r: WholeChainRun) = buildJsonObject {
        val searches = r.searches
        put("case", r.fixture.kind); put("count", r.fixture.count); put("retention", r.fixture.retention.name); put("fullTrace", r.fullTrace)
        put("geometryScope","fixed low-speed final center/D-083; owner half-open planning/anchors; no full C writeback claim")
        put("fullTerrainWritebackQualified",false)
        put("privateLocalRepairTriggerScope","local-trigger-not-observed; actual source/action/goal/outcome remain checked")
        put("requests", r.requests); put("actualSearchCalls", searches.sumOf { it.actualSearchCalls })
        put("userSearches", searches.count { it.reason == PathSearchReason.USER_MOVE })
        put("knowledgeReplans", searches.count { it.reason == PathSearchReason.KNOWLEDGE_REPLAN }); put("routeRepairs", searches.count { it.reason == PathSearchReason.ROUTE_REPAIR })
        put("preservedPrefixSearches", searches.count { it.preservedPrefixPointCount > 0 }); put("preservedPrefixPoints", searches.sumOf { it.preservedPrefixPointCount }); put("reuseEntitySteps", r.reuseEntitySteps)
        put("arrived", r.arrived.size); put("unreachable", r.cancelled.size); put("moved", r.moved.size)
        val lost = r.steps.zipWithNext().flatMap { (a, b) -> a.visible.indices.filter { a.visible[it] && !b.visible[it] }.map { b.known[it] } }
        put("lostSightCellTransitions", lost.size); put("retainedLostCellTransitions", lost.count { it != null }); put("forgottenCellTransitions", lost.count { it == null })
        put("gameplaySha256", gameplaySha256(r))
        put("initNanos", r.initNanos); put("submitNanos", r.submitNanos); put("batchNanos", r.wallNanos)
        put("advanceNanos", stats(r.steps.map { it.advanceNanos })); put("singleSearchNanos", stats(searches.map { it.searchNanos }))
        put("inputPreparationNanos", stats(searches.map { it.inputPreparationNanos })); put("lightSampleNanos", stats(r.steps.map { it.sampleNanos }))
        put("traceNanos", stats(r.steps.map { it.traceNanos })); put("verifyNanos", stats(r.steps.map { it.verifyNanos }))
        put("boundarySearchCallsPeak", r.steps.maxOf { s -> s.samples.sumOf { it.actualSearchCalls } }); put("boundarySearchNanosPeak", r.steps.maxOf { s -> s.samples.sumOf { it.searchNanos } })
        put("submitToFirstAdvanceReturnNanosUpperBound", r.firstResponseNanos)
        put("scheduledWaitSteps", 0); put("queueWaitNanos", JsonNull); put("queueWaitReason", "synchronous same-boundary execution; no worker queue")
        put("counters", buildJsonObject {
            put("candidatesChecked", searches.sumOf { it.candidatesChecked }); put("popped", searches.sumOf { it.popped }); put("expanded", searches.sumOf { it.expanded })
            put("enqueued", searches.sumOf { it.enqueued }); put("neighborsChecked", searches.sumOf { it.neighborsChecked }); put("heuristicCalls", searches.sumOf { it.heuristicCalls })
        }); put("memory", r.memory)
    }
    private fun gc(): Pair<Long, Long> = ManagementFactory.getGarbageCollectorMXBeans().let { beans ->
        if (beans.isEmpty() || beans.any { it.collectionCount < 0 || it.collectionTime < 0 }) -1L to -1L
        else beans.sumOf { it.collectionCount } to beans.sumOf { it.collectionTime }
    }
    private fun delta(a: Long, b: Long) = if (a < 0 || b < a) -1L else b - a
    fun gameplaySha256(run: WholeChainRun): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(run.initial.json().toString().toByteArray(Charsets.UTF_8))
        for (step in run.steps) {
            digest.update(step.observation.json().toString().toByteArray(Charsets.UTF_8))
            digest.update(step.known.toString().toByteArray(Charsets.UTF_8)); digest.update(step.visible.toString().toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
