package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import com.rustedwarfare.reforge.core.path.PlanningSegmentClearance
import com.rustedwarfare.reforge.core.path.PathDiagnosticsException
import com.rustedwarfare.reforge.core.path.PathSearchSample
import com.rustedwarfare.reforge.core.path.PathSearchCounters
import com.rustedwarfare.reforge.core.path.PathSearchOutcome
import com.rustedwarfare.reforge.core.path.KnowledgePathImpact
import com.rustedwarfare.reforge.core.path.PathSearchReason
import com.rustedwarfare.reforge.core.knowledge.*
import java.util.Collections
import java.util.IdentityHashMap

class SimulationContext private constructor(private val policies: ExperimentalPolicies = ExperimentalPolicies()) {
    private var activeWorld: WorldHandle? = null
    val world: WorldHandle get() = checkNotNull(activeWorld) { "Simulation is closed" }
    private var scenario: Scenario? = null
    private var units: MutableMap<String, MutableUnit> = linkedMapOf()
    private var worldGeneration: Long = 0
    private var closed: Boolean = true
    private var stepIndex: Long = 0
    private var simulationTime: Double = 0.0
    private var hasAdvanced = false
    private var revision = 0L
    private var pendingEvents: MutableList<Event> = mutableListOf()
    private var pendingActionResults: MutableList<ActionResult> = mutableListOf()
    private var latestObservation: Observation? = null
    private var committedObservation: Observation? = null
    private val scheduledActions = mutableListOf<Action>()
    private val actionIds = mutableSetOf<String>()
    private var actionResultOwners = IdentityHashMap<ActionResult, String>()
    private val playerActionRevisions = mutableMapOf<String, Long>()
    private val playerObservations = mutableMapOf<String, CachedPlayerObservation>()
    private data class CachedPlanningTerrain(val world: WorldHandle, val map: MapDefinition,
        val knowledge: KnowledgeSnapshot?, val terrain: PlanningTerrain)
    private var noFogPlanning: CachedPlanningTerrain? = null
    private val playerPlanning = mutableMapOf<String, CachedPlanningTerrain>()
    private var knowledgeStores: Map<String, PlayerTerrainKnowledge>? = null
    /** Immutable rules captured on the same successful commit boundary as [knowledgeStores]. */
    private var knowledgeRules: KnowledgeRules? = null
    private var automaticVisionSnapshots: Map<String, VisionSnapshot>? = null
    private var pathDiagnostics: MutableList<PathSearchSample>? = null
    private var motionDiagnostics: MutableList<MotionStepSample>? = null
    private val pendingKnowledgeDiscoveries = mutableMapOf<String, MutableMap<Pair<Int, Int>, Long>>()

    private data class PlayerObservationKey(val step: Long, val time: Double, val epoch: Long?,
        val knowledgeRevision: Long?, val actionRevision: Long, val units: List<UnitObservation>,
        val events: List<Event>, val results: List<ActionResult>)

    private data class CachedPlayerObservation(val key: PlayerObservationKey,
        val observation: PlayerObservation, val revision: Long, val source: Observation)

    // initialize inserts unit IDs in sorted order; transactional copies preserve that order.
    // Any future spawn/removal path must maintain this invariant before publishing units.
    private fun orderedUnits(): Collection<MutableUnit> = units.values

    private data class StepCheckpoint(
        val units: MutableMap<String, MutableUnit>, val events: MutableList<Event>,
        val results: MutableList<ActionResult>, val revision: Long, val observation: Observation?,
        val owners: IdentityHashMap<ActionResult, String>, val playerRevisions: Map<String, Long>,
    )

    private fun checkpoint() = StepCheckpoint(units, pendingEvents, pendingActionResults, revision,
        latestObservation, IdentityHashMap(actionResultOwners), HashMap(playerActionRevisions))

    private fun restore(checkpoint: StepCheckpoint) {
        units = checkpoint.units
        pendingEvents = checkpoint.events
        pendingActionResults = checkpoint.results
        revision = checkpoint.revision
        latestObservation = checkpoint.observation
        actionResultOwners = checkpoint.owners
        playerActionRevisions.clear()
        playerActionRevisions.putAll(checkpoint.playerRevisions)
    }

    private data class AutomaticKnowledgeCommit(
        val stores: Map<String, PlayerTerrainKnowledge>,
        val views: Map<String, VisionSnapshot>,
        val discoveries: Map<String, MutableMap<Pair<Int, Int>, Long>>,
    )

    private fun prepareAutomaticKnowledge(): AutomaticKnowledgeCommit? {
        val input = checkNotNull(scenario)
        if (input.automaticVision == null) return null
        val rules = checkNotNull(knowledgeRules)
        val oldStores = checkNotNull(knowledgeStores)
        val groundGids = input.map.groundGids
        val views = AutomaticVision.compute(world.id, (automaticVisionSnapshots?.values?.firstOrNull()?.epoch ?: -1L) + 1,
            input.map.widthTiles, input.map.heightTiles, input.map.tileWidth, input.map.tileHeight, input.players,
            units.values.map { VisionSource(it.ownerPlayerId, it.position, it.definition.fogOfWarSightRange) }, rules.shareAlliedVision)
        val nextStores = linkedMapOf<String, PlayerTerrainKnowledge>()
        val discoveries = linkedMapOf<String, MutableMap<Pair<Int, Int>, Long>>()
        try {
            for ((player, view) in views) {
                val old = oldStores.getValue(player)
                val before = old.snapshot(world)
                val next = old.projected(world, view.effectiveCells.map {
                    VisibleTerrainCell(it.column, it.row, input.map.blocking.isBlocked(it.column, it.row),
                        groundGids[it.row * input.map.widthTiles + it.column])
                })
                nextStores[player] = next
                val after = next.snapshot(world)
                for (cell in view.effectiveCells) {
                    if (!before.planningBlocked(cell.column, cell.row) && after.planningBlocked(cell.column, cell.row)) {
                        discoveries.getOrPut(player) { linkedMapOf() }[cell.column to cell.row] = after.revision
                    }
                }
            }
            return AutomaticKnowledgeCommit(nextStores, views, discoveries)
        } catch (failure: Exception) {
            nextStores.values.forEach { it.close() }
            throw failure
        }
    }

    private fun commitAutomaticKnowledge(next: AutomaticKnowledgeCommit) {
        knowledgeStores?.values?.forEach { it.close() }
        knowledgeStores = next.stores
        automaticVisionSnapshots = next.views
        pendingKnowledgeDiscoveries.clear()
        pendingKnowledgeDiscoveries.putAll(next.discoveries)
    }

    fun enablePathDiagnostics(world: WorldHandle) {
        checkPathDiagnosticsWorld(world)
        if (pathDiagnostics != null) throw PathDiagnosticsException("already-enabled", "Path diagnostics are already enabled")
        pathDiagnostics = mutableListOf()
    }

    fun drainPathDiagnostics(world: WorldHandle): List<PathSearchSample> {
        checkPathDiagnosticsWorld(world)
        val samples = pathDiagnostics ?: throw PathDiagnosticsException("not-enabled", "Path diagnostics are not enabled")
        val drained = Collections.unmodifiableList(ArrayList(samples))
        samples.clear()
        return drained
    }

    private fun checkPathDiagnosticsWorld(world: WorldHandle) {
        if (closed) throw PathDiagnosticsException("closed", "Simulation is closed")
        if (world !== activeWorld) throw PathDiagnosticsException("stale-world", "Path diagnostics belong to a different world")
    }

    internal fun localMotionSnapshots(world: WorldHandle): Map<String, LocalMotionSnapshot> {
        checkPathDiagnosticsWorld(world)
        val snapshots = linkedMapOf<String, LocalMotionSnapshot>()
        for (unit in units.values) unit.localMotion?.let {
            snapshots[unit.unitId] = it.snapshot(unit.originMoveId, unit.localProgressTime, unit.terrainCorrectionTime)
        }
        return Collections.unmodifiableMap(snapshots)
    }

    /** Explicit trusted-host opt-in, separate from both host and player observation DTOs. */
    fun enableMotionDiagnostics(world: WorldHandle) {
        checkMotionDiagnosticsWorld(world)
        if (motionDiagnostics != null) throw MotionDiagnosticsException("already-enabled", "Motion diagnostics are already enabled")
        motionDiagnostics = mutableListOf()
    }

    fun drainMotionDiagnostics(world: WorldHandle): List<MotionStepSample> {
        checkMotionDiagnosticsWorld(world)
        val samples = motionDiagnostics ?: throw MotionDiagnosticsException("not-enabled", "Motion diagnostics are not enabled")
        val result = immutable(samples)
        samples.clear()
        return result
    }

    private fun checkMotionDiagnosticsWorld(world: WorldHandle) {
        if (closed) throw MotionDiagnosticsException("closed", "Simulation is closed")
        if (world !== activeWorld) throw MotionDiagnosticsException("stale-world", "Motion diagnostics belong to a different world")
    }

    companion object {
        fun initialize(scenario: Scenario): InitResult = SimulationContext().initializeInternal(scenario)
    }

    fun initialize(scenario: Scenario): InitResult = initializeInternal(scenario)

    fun reopen(scenario: Scenario): InitResult = initializeInternal(scenario)

    /** Explicit trusted-host opt-in only; no vision production or simulation scheduling occurs here. */
    fun enableKnowledge(world: WorldHandle, rules: KnowledgeRules) {
        checkKnowledgeWorld(world)
        if (knowledgeStores != null) throw KnowledgeException("already-enabled", "Knowledge is already enabled for this world")
        val currentScenario = checkNotNull(scenario)
        val pending = linkedMapOf<String, PlayerTerrainKnowledge>()
        var committed = false
        try {
            for (player in currentScenario.players) {
                pending[player.playerId] = PlayerTerrainKnowledge.create(world, player.playerId,
                    currentScenario.map.widthTiles, currentScenario.map.heightTiles, rules)
            }
            knowledgeStores = pending
            knowledgeRules = rules
            committed = true
        } finally {
            if (!committed) pending.values.forEach { it.close() }
        }
    }

    fun acceptVisibleTerrain(world: WorldHandle, playerId: String, visibleTerrain: List<VisibleTerrainCell>) {
        checkKnowledgeWorld(world)
        val stores = knowledgeStores ?: throw KnowledgeException("not-enabled", "Knowledge is not enabled")
        val store = stores[playerId] ?: throw KnowledgeException("unknown-player", "Player is not declared in the current scenario")
        if (automaticVisionSnapshots != null) throw KnowledgeException("automatic-vision", "Automatic vision owns terrain projection")
        if (knowledgeRules?.pathSource != PathInformationSource.PLAYER_KNOWLEDGE) {
            store.accept(world, visibleTerrain)
            return
        }
        val before = store.snapshot(world)
        store.accept(world, visibleTerrain)
        val after = store.snapshot(world)
        for (row in 0 until after.height) for (column in 0 until after.width) {
            if (!before.planningBlocked(column, row) && after.planningBlocked(column, row)) {
                pendingKnowledgeDiscoveries.getOrPut(playerId) { linkedMapOf() }[column to row] = after.revision
            }
        }
    }

    fun knowledgeSnapshot(world: WorldHandle, playerId: String): KnowledgeSnapshot {
        checkKnowledgeWorld(world)
        val stores = knowledgeStores ?: throw KnowledgeException("not-enabled", "Knowledge is not enabled")
        val store = stores[playerId] ?: throw KnowledgeException("unknown-player", "Player is not declared in the current scenario")
        return store.snapshot(world)
    }

    fun visionSnapshot(world: WorldHandle, playerId: String): VisionSnapshot {
        checkKnowledgeWorld(world)
        val snapshots = automaticVisionSnapshots ?: throw KnowledgeException("not-enabled", "Automatic vision is not enabled")
        return snapshots[playerId] ?: throw KnowledgeException("unknown-player", "Player is not declared in the current scenario")
    }

    private fun checkKnowledgeWorld(world: WorldHandle) {
        if (closed) throw KnowledgeException("closed", "Simulation is closed")
        if (world !== activeWorld) throw KnowledgeException("stale-world", "Knowledge access belongs to a different world")
    }

    fun close(): CloseResult {
        if (closed) return CloseResult.AlreadyClosed
        closed = true
        knowledgeStores?.values?.forEach { it.close() }
        knowledgeStores = null
        knowledgeRules = null
        automaticVisionSnapshots = null
        pendingKnowledgeDiscoveries.clear()
        pathDiagnostics?.clear()
        pathDiagnostics = null
        motionDiagnostics?.clear()
        motionDiagnostics = null
        activeWorld = null
        this.scenario = null
        units = linkedMapOf()
        pendingEvents.clear()
        pendingActionResults.clear()
        latestObservation = null
        committedObservation = null
        scheduledActions.clear()
        actionIds.clear()
        actionResultOwners.clear()
        playerActionRevisions.clear()
        playerObservations.clear()
        noFogPlanning = null
        playerPlanning.clear()
        return CloseResult.Closed
    }

    fun submit(action: Action): ActionResult {
        if (closed) return ActionResult.Rejected(action.actionId, SimulationError("closed", "Simulation is closed"))
        if (action.world !== activeWorld) return reject(action, "stale-world", "Action belongs to a different world")
        if (!Regex("[A-Za-z0-9._:-]+").matches(action.actionId)) return reject(action, "invalid-id", "Invalid action ID")
        if (!actionIds.add(action.actionId)) return reject(action, "duplicate-action", "Action ID has already been submitted")
        val currentScenario = checkNotNull(scenario)
        if (currentScenario.players.none { it.playerId == action.actorPlayerId }) {
            return reject(action, "unknown-actor", "Actor is not a declared player")
        }
        val unit = units[action.unitId]
            ?: return reject(action, "unknown-unit", "Unknown unit '${action.unitId}'")
        if (action !is SelectAction && unit.ownerPlayerId != action.actorPlayerId) {
            return reject(action, "not-owner", "Player '${action.actorPlayerId}' does not own '${action.unitId}'")
        }
        if (action !is SelectAction && currentScenario.players.single { it.playerId == action.actorPlayerId }.control != PlayerControl.LOCAL) {
            return reject(action, "actor-not-controlled", "Player has no local control")
        }
        if (action is SelectAction && !isVisibleTo(action.actorPlayerId, unit.position)) {
            return reject(action, "not-visible", "Unit is outside the player's current vision")
        }
        if (action is MoveAction && (!action.target.x.isFinite() || !action.target.y.isFinite())) {
            return reject(action, "invalid-target", "Target coordinates must be finite")
        }
        val scheduledStep = action.atStep
        if (scheduledStep != null) {
            if (scheduledStep < 0 || (hasAdvanced && scheduledStep <= stepIndex)) {
                return reject(action, "past-step", "Scheduled action must belong to a future step")
            }
            scheduledActions += action
            return ActionResult.Accepted(action.actionId, queuedForStep = scheduledStep)
        }
        return applyAction(action)
    }

    /**
     * Selects the planning input for one unit's Move.
     *
     * Route is decided by the unit's owning player, never by the acting player, and only the
     * owning player's store may contribute a snapshot. Truth blocking is reached exclusively
     * through the pre-existing no-fog adapter, and only while knowledge is not enabled; an
     * enabled PLAYER_KNOWLEDGE context never falls back to truth.
     */
    private fun planningTerrainFor(unit: MutableUnit, map: MapDefinition): PlanningTerrain {
        fun noFog(): PlanningTerrain {
            val cached = noFogPlanning
            if (cached != null && cached.world === activeWorld && cached.map === map) return cached.terrain
            return PlanningTerrain.fromNoFog(map).also {
                noFogPlanning = CachedPlanningTerrain(world, map, null, it)
            }
        }
        // Not enabled at all: legacy no-fog behaviour, unchanged.
        val stores = knowledgeStores ?: return noFog()
        // Enabled: stores and rules are published on the same commit boundary, so a missing rules
        // value is a broken internal invariant, never a reason to silently fall back to truth.
        val rules = checkNotNull(knowledgeRules) { "Knowledge rules must be published with knowledge stores" }
        if (rules.pathSource != PathInformationSource.PLAYER_KNOWLEDGE) return noFog()
        val store = checkNotNull(stores[unit.ownerPlayerId]) {
            "Knowledge store is missing for owning player '${unit.ownerPlayerId}'"
        }
        val snapshot = store.snapshot(checkNotNull(activeWorld))
        val cached = playerPlanning[unit.ownerPlayerId]
        if (cached != null && cached.world === activeWorld && cached.map === map && cached.knowledge === snapshot) return cached.terrain
        return PlanningTerrain.fromKnowledge(snapshot, map.tileWidth, map.tileHeight).also {
            playerPlanning[unit.ownerPlayerId] = CachedPlanningTerrain(world, map, snapshot, it)
        }
    }

    private fun findPathWithDiagnostics(actionId: String, atStep: Long?, target: Position,
                                        unit: MutableUnit, map: MapDefinition,
                                        samples: MutableList<PathSearchSample>,
                                        reason: PathSearchReason = PathSearchReason.USER_MOVE,
                                        executionStep: Long? = null,
                                        start: Position = unit.position,
                                        preservedPrefixPointCount: Int = 0): List<Position>? {
        val counters = PathSearchCounters()
        val usedKnowledge = knowledgeStores != null && knowledgeRules?.pathSource == PathInformationSource.PLAYER_KNOWLEDGE
        var outcome = PathSearchOutcome.FAILED
        var pathPointCount = 0
        var preparationNanos = 0L
        var searchNanos = 0L
        val preparationStart = System.nanoTime()
        var preparationFinished = false
        var searchStart = 0L
        var searchEntered = false
        try {
            val terrain = planningTerrainFor(unit, map)
            preparationNanos = (System.nanoTime() - preparationStart).coerceAtLeast(0L)
            preparationFinished = true
            searchStart = System.nanoTime()
            searchEntered = true
            val path = policies.path.findPath(terrain, start, target, unit.definition.radius, counters)
            outcome = if (path == null) PathSearchOutcome.UNREACHABLE else PathSearchOutcome.FOUND
            pathPointCount = path?.size ?: 0
            return path
        } catch (error: Throwable) {
            outcome = PathSearchOutcome.FAILED
            throw error
        } finally {
            if (!preparationFinished) preparationNanos = (System.nanoTime() - preparationStart).coerceAtLeast(0L)
            if (searchEntered) searchNanos = (System.nanoTime() - searchStart).coerceAtLeast(0L)
            samples += PathSearchSample(actionId, unit.unitId, atStep, usedKnowledge, outcome,
                preparationNanos, searchNanos, counters.actualSearchCalls, counters.candidatesChecked, counters.popped,
                counters.expanded, counters.enqueued, counters.neighborsChecked, counters.heuristicCalls, pathPointCount, reason, executionStep,
                searchTarget = target, searchStart = start,
                preservedPrefixPointCount = if (outcome == PathSearchOutcome.FOUND) preservedPrefixPointCount else 0)
        }
    }

    private fun plannedKnowledgeRevision(unit: MutableUnit): Long =
        if (knowledgeRules?.pathSource == PathInformationSource.PLAYER_KNOWLEDGE)
            checkNotNull(knowledgeStores?.get(unit.ownerPlayerId)).snapshot(checkNotNull(activeWorld)).revision else -1L

    private fun replanForKnowledge(map: MapDefinition, executionStep: Long) {
        if (pendingKnowledgeDiscoveries.isEmpty()) return
        for (unit in orderedUnits()) {
            val target = unit.moveTarget ?: continue
            val discoveries = pendingKnowledgeDiscoveries[unit.ownerPlayerId] ?: continue
            val snapshot = checkNotNull(knowledgeStores?.get(unit.ownerPlayerId)).snapshot(checkNotNull(activeWorld))
            val affected = discoveries.any { (cell, discoveredAt) ->
                discoveredAt > unit.lastPlannedKnowledgeRevision && snapshot.planningBlocked(cell.first, cell.second) &&
                    KnowledgePathImpact.affectsCenter(unit.position, unit.pathPoints, target,
                        cell.first, cell.second, map.tileWidth, map.tileHeight)
            }
            if (!affected) continue
            val samples = pathDiagnostics
            val terrain = planningTerrainFor(unit, map)
            val oldPath = unit.pathPoints.toList()
            val prefix = mutableListOf<Position>()
            var from = unit.position
            for (point in oldPath) {
                if (!PlanningSegmentClearance.allows(terrain, from, point, unit.definition.radius)) break
                prefix += point
                from = point
            }
            fun search(start: Position, preserved: Int = 0): List<Position>? =
                if (samples == null) policies.path.findPath(terrain, start, target, unit.definition.radius)
                else findPathWithDiagnostics(checkNotNull(unit.originMoveId), unit.originAtStep, target, unit, map, samples,
                    PathSearchReason.KNOWLEDGE_REPLAN, executionStep, start, preserved)
            val path = if (prefix.isNotEmpty() && prefix.size < oldPath.size) {
                val suffix = search(prefix.last(), prefix.size)
                if (suffix == null) search(unit.position)
                else prefix + if (suffix.firstOrNull() == prefix.last()) suffix.drop(1) else suffix
            } else search(unit.position)
            if (path == null) {
                unit.cancelRoute()
                pendingEvents += Event("movement-cancelled", unit.unitId)
                pendingEvents += Event("path-unreachable", unit.unitId)
            } else {
                unit.adoptRoute(path, snapshot.revision)
            }
        }
    }

    private fun repairInvalidConnections(map: MapDefinition, executionStep: Long) {
        for (unit in orderedUnits()) {
            val target = unit.moveTarget ?: continue
            val first = unit.pathPoints.firstOrNull() ?: continue
            val terrain = planningTerrainFor(unit, map)
            val local = unit.ensureLocalMotion()
            val firstConnectionClear = PlanningSegmentClearance.allows(terrain, unit.position, first, unit.definition.radius)
            val localConnectionClear = PlanningSegmentClearance.allows(terrain, unit.position, local.aim, unit.definition.radius)
            val nextLocal = local.next()
            val localCornerStall = nextLocal != null &&
                policies.coordinates.distanceSquared(unit.position, local.aim) < 9.0 &&
                !PlanningSegmentClearance.allows(terrain, unit.position, nextLocal.aim, unit.definition.radius)
            val nearCornerStall = unit.pathPoints.size > 1 &&
                policies.coordinates.distanceSquared(unit.position, first) < 9.0 &&
                !PlanningSegmentClearance.allows(terrain, unit.position, unit.pathPoints[1], unit.definition.radius)
            if (firstConnectionClear && localConnectionClear && !nearCornerStall && !localCornerStall) continue
            val samples = pathDiagnostics
            fun search(destination: Position): List<Position>? =
                if (samples == null) policies.path.findPath(terrain, unit.position, destination, unit.definition.radius)
                else findPathWithDiagnostics(checkNotNull(unit.originMoveId), unit.originAtStep, destination, unit, map, samples,
                    PathSearchReason.ROUTE_REPAIR, executionStep)
            val oldPath = unit.pathPoints.toList()
            val tailIsClear = oldPath.size > 1 && oldPath.zipWithNext().all { (a, b) ->
                PlanningSegmentClearance.allows(terrain, a, b, unit.definition.radius)
            }
            val path = if (nearCornerStall || localCornerStall || (firstConnectionClear && !localConnectionClear)) {
                // The local propulsion gate cannot close the last <3 units,
                // and a safe capture cannot skip this corner. Plan from the
                // actual position rather than reconnecting the same dead end.
                search(target)
            } else {
                val connector = if (tailIsClear) search(first) else null
                if (connector != null) connector + oldPath.drop(1) else search(target)
            }
            if (path == null) {
                unit.cancelRoute()
                pendingEvents += Event("movement-cancelled", unit.unitId)
                pendingEvents += Event("path-unreachable", unit.unitId)
            } else {
                unit.adoptRoute(path, plannedKnowledgeRevision(unit))
            }
        }
    }

    private fun applyAction(action: Action, executionStep: Long? = null): ActionResult {
        val currentScenario = checkNotNull(scenario)
        val unit = units.getValue(action.unitId)
        val result = when (action) {
            is SelectAction -> {
                if (!isVisibleTo(action.actorPlayerId, unit.position)) return reject(action, "not-visible", "Unit is outside the player's current vision")
                ActionResult.Accepted(action.actionId)
            }
            is StopAction -> {
                unit.cancelRoute()
                ActionResult.Accepted(action.actionId, listOf(Event("movement-cancelled", unit.unitId)))
            }
            is MoveAction -> {
                if (!action.target.x.isFinite() || !action.target.y.isFinite()) {
                    return reject(action, "invalid-target", "Target coordinates must be finite")
                }
                val samples = pathDiagnostics
                val path = if (samples == null) {
                    policies.path.findPath(planningTerrainFor(unit, currentScenario.map), unit.position, action.target, unit.definition.radius)
                } else {
                    findPathWithDiagnostics(action.actionId, action.atStep, action.target, unit, currentScenario.map, samples,
                        executionStep = executionStep)
                }
                if (path == null) {
                    unit.cancelRoute()
                    ActionResult.Accepted(
                        action.actionId,
                        listOf(Event("movement-cancelled", unit.unitId), Event("path-unreachable", unit.unitId)),
                    )
                } else {
                    unit.moveTarget = action.target
                    unit.nearTargetTime = 0.0
                    unit.originMoveId = action.actionId
                    unit.originAtStep = action.atStep
                    unit.adoptRoute(path, plannedKnowledgeRevision(unit))
                    ActionResult.Accepted(action.actionId, listOf(Event("movement-started", unit.unitId)))
                }
            }
        }
        pendingActionResults += result
        recordActionOwner(action, result)
        pendingEvents += result.events
        invalidateObservation()
        return result
    }

    fun advance(step: Step): Observation {
        check(!closed) { "Simulation is closed" }
        val currentScenario = checkNotNull(scenario)
        require(step.stepIndex >= 0 && (!hasAdvanced || step.stepIndex > stepIndex)) {
            "stepIndex must strictly increase from $stepIndex: ${step.stepIndex}"
        }
        policies.time.validate(step, currentScenario.timeModel)
        require(scheduledActions.none { checkNotNull(it.atStep) < step.stepIndex }) {
            "advance cannot skip a scheduled action step"
        }
        val actions = scheduledActions.filter { it.atStep == step.stepIndex }
        val previous = checkpoint()
        // Calculate on copies so a numerical failure cannot commit half of a multi-unit step.
        units = units.mapValuesTo(linkedMapOf()) { (_, unit) -> unit.copy(pathPoints = unit.pathPoints.toMutableList()) }
        pendingEvents = pendingEvents.toMutableList()
        pendingActionResults = pendingActionResults.toMutableList()
        val motionSamples = motionDiagnostics?.let { mutableListOf<MotionStepSample>() }
        var nextVision: AutomaticKnowledgeCommit? = null
        try {
            actions.forEach { applyAction(it, step.stepIndex) }
            replanForKnowledge(currentScenario.map, step.stepIndex)
            repairInvalidConnections(currentScenario.map, step.stepIndex)
            for (unit in orderedUnits()) updateUnit(unit, currentScenario.map, step.delta, step.stepIndex, motionSamples)
            nextVision = prepareAutomaticKnowledge()
        } catch (error: Exception) {
            restore(previous)
            throw error
        }
        pendingKnowledgeDiscoveries.clear()
        nextVision?.let { commitAutomaticKnowledge(it) }
        scheduledActions.removeAll(actions.toSet())
        stepIndex = step.stepIndex
        hasAdvanced = true
        simulationTime += step.delta
        invalidateObservation()
        val observation = recordObservation()
        committedObservation = observation
        // Only this committed window and subsequent immediate results can be observed.
        // Replace the map as well as its entries: a previous burst must not retain its capacity.
        val currentOwners = IdentityHashMap<ActionResult, String>(observation.actionResults.size)
        for (result in observation.actionResults) actionResultOwners[result]?.let { currentOwners[result] = it }
        actionResultOwners = currentOwners
        pendingEvents.clear()
        pendingActionResults.clear()
        motionSamples?.let { checkNotNull(motionDiagnostics).addAll(it) }
        return observation
    }

    fun observe(): Observation {
        check(!closed) { "Simulation is closed" }
        return latestObservation ?: recordObservation()
    }

    private fun isVisibleTo(playerId: String, position: Position): Boolean {
        val view = automaticVisionSnapshots?.getValue(playerId) ?: return true
        val map = checkNotNull(scenario).map
        if (!MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth,
                map.heightTiles.toDouble() * map.tileHeight, position, 0.0)) return false
        val column = MapBounds.cell(position.x, map.tileWidth, map.widthTiles)
        val row = MapBounds.cell(position.y, map.tileHeight, map.heightTiles)
        return view.contains(column, row)
    }

    fun observePlayer(world: WorldHandle, playerId: String): PlayerObservation {
        checkKnowledgeWorld(world)
        val input = checkNotNull(scenario)
        if (input.players.none { it.playerId == playerId }) throw KnowledgeException("unknown-player", "Player is not declared in the current scenario")
        val observation = observe()
        val vision = automaticVisionSnapshots?.getValue(playerId)
        val knowledge = knowledgeStores?.getValue(playerId)?.snapshot(world)
        val cached = playerObservations[playerId]
        if (cached?.source === observation && cached.key.epoch == vision?.epoch &&
            cached.key.knowledgeRevision == knowledge?.revision &&
            cached.key.actionRevision == (playerActionRevisions[playerId] ?: 0L)) return cached.observation
        val visible = observation.units.filter { isVisibleTo(playerId, it.position) }.map {
            if (it.ownerPlayerId == playerId) it else it.copy(moveTarget = null, pathPoints = emptyList())
        }
        val ids = visible.map { it.unitId }.toSet()
        fun visibleEvents(events: List<Event>) = events.filter { it.unitId != null && it.unitId in ids }
        // Keep the committed step's events when immediate actions replace the host observation.
        // Hidden actions must neither erase these events nor advance the player's sample identity.
        val committed = committedObservation?.takeIf { it !== observation }
        val events = visibleEvents(committed?.events.orEmpty() + observation.events)
        val results = (committed?.actionResults.orEmpty() + observation.actionResults).filter { actionResultOwners[it] == playerId }.map {
            if (it is ActionResult.Accepted) ActionResult.Accepted(it.actionId, visibleEvents(it.events), it.queuedForStep) else it
        }
        val key = PlayerObservationKey(stepIndex, simulationTime, vision?.epoch, knowledge?.revision,
            playerActionRevisions[playerId] ?: 0L, visible, events, results)
        if (cached?.key == key) {
            playerObservations[playerId] = cached.copy(source = observation)
            return cached.observation
        }
        val playerRevision = (cached?.revision ?: -1L) + 1L
        val result = PlayerObservation("${world.id}:$playerId:${if (hasAdvanced) "step$stepIndex" else "initial"}:${vision?.epoch ?: 0L}:$playerRevision",
            world.id, input.scenarioId, playerId, stepIndex, simulationTime, vision, knowledge,
            visible, events, results)
        playerObservations[playerId] = CachedPlayerObservation(key, result, playerRevision, observation)
        return result
    }

    private fun initializeInternal(nextScenario: Scenario): InitResult {
        if (!closed) return InitResult.Rejected(SimulationError("already-open", "Close the current world before initializing"))
        val error = validate(nextScenario)
        if (error != null) return InitResult.Rejected(error)
        worldGeneration += 1
        activeWorld = WorldHandle("${nextScenario.scenarioId}:$worldGeneration")
        scenario = nextScenario.copy(
            unitDefinitions = Collections.unmodifiableMap(LinkedHashMap(nextScenario.unitDefinitions)),
            players = immutable(nextScenario.players),
            units = immutable(nextScenario.units),
        )
        units = linkedMapOf()
        nextScenario.units.sortedBy { it.unitId }.forEach { spawn ->
            units[spawn.unitId] = MutableUnit(
                unitId = spawn.unitId,
                ownerPlayerId = spawn.ownerPlayerId,
                typeId = spawn.typeId,
                definition = checkNotNull(nextScenario.unitDefinitions[spawn.typeId]),
                position = spawn.position,
                heading = policies.coordinates.normalize(spawn.heading),
            )
        }
        closed = false
        stepIndex = 0
        simulationTime = 0.0
        hasAdvanced = false
        revision = 0
        pendingEvents = mutableListOf()
        pendingActionResults = mutableListOf()
        latestObservation = null
        scheduledActions.clear()
        actionIds.clear()
        nextScenario.automaticVision?.let { rules ->
            try {
                enableKnowledge(world, rules)
                commitAutomaticKnowledge(checkNotNull(prepareAutomaticKnowledge()))
            } catch (failure: Exception) {
                close()
                throw failure
            }
        }
        return InitResult.Accepted(this)
    }

    private fun validate(input: Scenario): SimulationError? {
        val id = Regex("[A-Za-z0-9._:-]+")
        val ids = listOf(input.scenarioId, input.coordinateModel.id, input.timeModel.id) +
            input.players.map { it.playerId } + input.players.mapNotNull { it.teamId } +
            input.units.map { it.unitId } + input.unitDefinitions.keys
        if (ids.any { !id.matches(it) }) return SimulationError("invalid-id", "Identifiers must be nonempty ASCII IDs")
        if (input.players.map { it.playerId }.toSet().size != input.players.size) return SimulationError("duplicate-id", "Duplicate player id")
        if (input.units.map { it.unitId }.toSet().size != input.units.size) return SimulationError("duplicate-id", "Duplicate unit id")
        val players = input.players.map { it.playerId }.toSet()
        if (input.units.any { it.ownerPlayerId !in players }) return SimulationError("bad-reference", "Unit owner is not a declared player")
        if (input.units.any { it.typeId !in input.unitDefinitions }) return SimulationError("bad-reference", "Unit type is not declared")
        if (input.map.widthTiles <= 0 || input.map.heightTiles <= 0) return SimulationError("invalid-map", "Map dimensions must be positive")
        if (input.automaticVision != null && input.map.widthTiles.toLong() * input.map.heightTiles != input.map.groundGids.size.toLong()) {
            return SimulationError("invalid-map", "Automatic terrain knowledge requires matching ground dimensions")
        }
        if (input.automaticVision?.retention == TerrainRetention.HARDCORE_FORGET &&
            input.automaticVision.pathSource != PathInformationSource.PLAYER_KNOWLEDGE) {
            return SimulationError("incompatible-rules", "Hardcore forgetting requires player knowledge path information")
        }
        if (input.map.tileWidth != 20 || input.map.tileHeight != 20 ||
            input.map.blocking.widthTiles != input.map.widthTiles || input.map.blocking.heightTiles != input.map.heightTiles) {
            return SimulationError("unsupported-map", "Expected matching blocking dimensions and 20-unit square tiles")
        }
        if (!policies.coordinates.supports(input.coordinateModel)) {
            return SimulationError("unsupported-coordinate-model", "Coordinate model is not supported by this contract")
        }
        if (!policies.time.supports(input.timeModel)) return SimulationError("unsupported-time-model", "Unsupported time model")
        val contract = input.movementContract ?: return SimulationError("missing-contract", "Movement contract must be explicit")
        if (!contract.pathPointRadius.isFinite() || contract.pathPointRadius <= 0.0 ||
            !contract.targetRadius.isFinite() || contract.targetRadius <= 0.0) {
            return SimulationError("invalid-contract", "Capture radii must be finite and positive")
        }
        for (definition in input.unitDefinitions.values) {
            val m = definition.movement
            val numbers = listOf(definition.radius, m.moveSpeed, m.moveAccelerationSpeed, m.moveDecelerationSpeed,
                m.maxTurnSpeed, m.turnAcceleration)
            if (numbers.any { !it.isFinite() || it < 0.0 } || definition.radius >= input.map.tileWidth || m.slidingMode || m.ignoringBody) {
                return SimulationError("unsupported-unit", "Expected finite nonnegative, non-sliding movement and radius below tile size")
            }
        }
        for (spawn in input.units) {
            val p = spawn.position
            val radius = checkNotNull(input.unitDefinitions[spawn.typeId]).radius
            if (!MapBounds.contains(input.map.widthTiles.toDouble() * input.map.tileWidth,
                    input.map.heightTiles.toDouble() * input.map.tileHeight, p, radius)) {
                return SimulationError("invalid-spawn", "Unit '${spawn.unitId}' collision circle must be finite and entirely within the map")
            }
            if (!spawn.heading.isFinite() || policies.terrain.intersects(input.map, p, radius)) {
                return SimulationError("invalid-spawn", "Unit '${spawn.unitId}' heading must be finite and spawn outside blocked terrain")
            }
        }
        return null
    }

    private fun reject(action: Action, code: String, message: String): ActionResult.Rejected {
        val result = ActionResult.Rejected(action.actionId, SimulationError(code, message))
        pendingActionResults += result
        recordActionOwner(action, result)
        invalidateObservation()
        return result
    }

    private fun recordActionOwner(action: Action, result: ActionResult) {
        actionResultOwners[result] = action.actorPlayerId
        playerActionRevisions[action.actorPlayerId] = (playerActionRevisions[action.actorPlayerId] ?: 0L) + 1L
    }

    private fun invalidateObservation() {
        revision += 1
        latestObservation = null
    }

    private fun updateUnit(unit: MutableUnit, map: MapDefinition, delta: Double, executionStep: Long,
                           motionSamples: MutableList<MotionStepSample>?) {
        val movement = unit.definition.movement
        val contract = checkNotNull(scenario?.movementContract)
        val positionBefore = unit.position
        val headingBefore = unit.heading
        val speedBefore = unit.speed
        val angularVelocityBefore = unit.angularVelocity
        val commandBefore = unit.moveTarget
        val originBefore = unit.originMoveId
        val progressBefore = unit.localProgressTime
        val correctionBefore = unit.terrainCorrectionTime
        var normalCompleted = false
        var aimForSample: Position? = null
        var finalForSample = false
        var differenceForSample = 0.0
        var requestedFraction = 0.0
        var target = unit.moveTarget
        var speedTarget = 0.0
        if (target != null) {
            val commandDistanceSquared = policies.coordinates.distanceSquared(unit.position, target)
            if (commandDistanceSquared < 1681.0) unit.nearTargetTime += delta
            val completionRadius = when {
                unit.nearTargetTime > 340.0 -> 36.0
                unit.nearTargetTime > 240.0 -> 16.0
                else -> 7.0
            }
            if (policies.capture.inside(commandDistanceSquared, completionRadius)) {
                unit.cancelRoute()
                pendingEvents += Event("movement-arrived", unit.unitId)
                target = null
                normalCompleted = true
            }
        }
        if (target != null) {
            val local = unit.ensureLocalMotion()
            val steeringTarget = local.aim
            val finalPoint = unit.pathPoints.size == 1 && local.isLast
            val d2 = policies.coordinates.distanceSquared(unit.position, steeringTarget)
            val diff = policies.steering.turn(unit, steeringTarget, delta, policies.coordinates)
            val fraction = policies.propulsion.targetFraction(movement, unit.speed, d2, diff, finalPoint, contract.targetRadius)
            aimForSample = steeringTarget
            finalForSample = finalPoint
            differenceForSample = diff
            requestedFraction = fraction
            speedTarget = movement.moveSpeed * fraction
            if (unit.pathPoints.size > 1 && policies.capture.inside(
                    policies.coordinates.distanceSquared(unit.position, unit.pathPoints.first()), contract.pathPointRadius) &&
                PlanningSegmentClearance.allows(planningTerrainFor(unit, map), unit.position,
                    unit.pathPoints[1], unit.definition.radius)) {
                unit.pathPoints.removeAt(0)
                unit.localMotion = null
                unit.localProgressTime = 0.0
            } else {
                val terrain = planningTerrainFor(unit, map)
                val progress = LocalMotionMaintenance.advance(local, unit.localProgressTime, delta, fraction > 0.0,
                    d2, !finalPoint && policies.capture.inside(d2, 16.0), unit.terrainCorrectionTime,
                    { policies.coordinates.distanceSquared(unit.position, it) },
                    { PlanningSegmentClearance.allows(terrain, unit.position, it, unit.definition.radius) })
                unit.localMotion = progress.segment
                unit.localProgressTime = progress.activeTime
            }
        }

        val rate = if (unit.speed < speedTarget) movement.moveAccelerationSpeed else movement.moveDecelerationSpeed
        // B/C advance the propulsion proportion; the public speed is in world units.
        unit.speed = approach(unit.speed, speedTarget, rate * movement.moveSpeed * delta)

        val displacement = policies.coordinates.displacement(unit.heading, unit.speed * delta)
        val candidate = Position(unit.position.x + displacement.x, unit.position.y + displacement.y)
        val integratedHeading = unit.heading
        val integratedSpeed = unit.speed
        fun recordMotion(resolved: Position?, disposition: MotionTerrainDisposition) {
            motionSamples?.add(MotionStepSample(checkNotNull(activeWorld).id, unit.unitId, unit.ownerPlayerId, unit.typeId,
                executionStep, simulationTime, delta, originBefore, positionBefore, headingBefore, speedBefore, angularVelocityBefore,
                commandBefore, normalCompleted, aimForSample != null, aimForSample,
                finalForSample.takeIf { aimForSample != null }, differenceForSample.takeIf { aimForSample != null },
                requestedFraction, integratedHeading, integratedSpeed, candidate, resolved, disposition,
                unit.position, unit.heading, unit.speed, unit.angularVelocity,
                progressBefore, unit.localProgressTime, correctionBefore, unit.terrainCorrectionTime))
        }
        if (!candidate.x.isFinite() || !candidate.y.isFinite()) {
            throw ArithmeticException("Non-finite movement candidate for ${unit.unitId}")
        }
        if (!MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth,
                map.heightTiles.toDouble() * map.tileHeight, candidate, unit.definition.radius)) {
            blockAtMapBoundary(unit)
            recordMotion(null, MotionTerrainDisposition.CANDIDATE_OUTSIDE_MAP)
            return
        }
        val resolved = TerrainMotion.resolve(map, unit.position, candidate)
        if (!MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth,
                map.heightTiles.toDouble() * map.tileHeight, resolved, unit.definition.radius)) {
            blockAtMapBoundary(unit)
            recordMotion(resolved, MotionTerrainDisposition.RESOLVED_OUTSIDE_MAP)
            return
        }
        if (resolved != candidate) {
            pendingEvents += Event("movement-blocked", unit.unitId)
            unit.terrainCorrectionTime += delta
            unit.terrainClearSteps = 0
        } else if (delta > 0.0) {
            unit.terrainClearSteps = (unit.terrainClearSteps + 1).coerceAtMost(3)
            if (unit.terrainClearSteps >= 3) unit.terrainCorrectionTime = 0.0
        }
        unit.position = resolved
        unit.movementState = when {
            unit.moveTarget != null -> MovementState.MOVING
            unit.speed != 0.0 -> MovementState.DECELERATING
            else -> MovementState.IDLE
        }
        recordMotion(resolved, if (resolved == candidate) MotionTerrainDisposition.DIRECT else MotionTerrainDisposition.CORRECTED)
    }

    private fun blockAtMapBoundary(unit: MutableUnit) {
        unit.speed = 0.0
        unit.angularVelocity = 0.0
        unit.cancelRoute()
        unit.movementState = MovementState.BLOCKED
        pendingEvents += Event("movement-boundary-blocked", unit.unitId)
    }

    private fun recordObservation(): Observation {
        val currentScenario = checkNotNull(scenario)
        val observation = Observation(
            sampleId = "${currentScenario.scenarioId}:$worldGeneration:${if (hasAdvanced) "step$stepIndex" else "initial"}:$revision",
            worldId = "${currentScenario.scenarioId}:$worldGeneration",
            scenarioId = currentScenario.scenarioId,
            stepIndex = stepIndex,
            simulationTime = simulationTime,
            units = orderedUnits().map { unit ->
                UnitObservation(
                    unitId = unit.unitId,
                    ownerPlayerId = unit.ownerPlayerId,
                    typeId = unit.typeId,
                    position = unit.position,
                    heading = unit.heading,
                    speed = unit.speed,
                    angularVelocity = unit.angularVelocity,
                    moveTarget = unit.moveTarget,
                    pathPoints = immutable(unit.pathPoints),
                    movementState = unit.movementState,
                )
            },
            events = pendingEvents,
            actionResults = pendingActionResults,
        )
        latestObservation = observation
        return observation
    }

}

