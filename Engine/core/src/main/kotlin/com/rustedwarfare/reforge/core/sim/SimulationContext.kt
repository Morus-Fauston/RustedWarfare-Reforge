package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementType
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
    private var projectiles: MutableList<MutableProjectile> = mutableListOf()
    private var combatFrame: Int = -1
    private var nextObjectId: Long = 1L
    private var lastBirthSerial: Long = 0L
    private var productionLedger: ProductionLedger? = null
    private var buildingTerrain: BuildingTerrain? = null
    private var buildingKnowledge: Map<String, BuildingKnowledge> = emptyMap()
    private var pendingBuildingDiscoveries: Map<String, Map<Pair<Int, Int>, Long>> = emptyMap()
    private val projectileViews = mutableMapOf<String, ProjectileProjection>()
    private var worldGeneration: Long = 0
    private var closed: Boolean = true
    private var stepIndex: Long = 0
    private var simulationTime: Double = 0.0
    private var hasAdvanced = false
    private var revision = 0L
    private var pendingEvents: MutableList<Event> = mutableListOf()
    private var pendingProjectileEvents: MutableList<ProjectileEventObservation> = mutableListOf()
    private var pendingActionResults: MutableList<ActionResult> = mutableListOf()
    private var latestObservation: Observation? = null
    private var committedObservation: Observation? = null
    private val scheduledActions = mutableListOf<Action>()
    private val actionIds = mutableSetOf<String>()
    private var actionResultOwners = IdentityHashMap<ActionResult, String>()
    private val playerActionRevisions = mutableMapOf<String, Long>()
    private val playerObservations = mutableMapOf<String, CachedPlayerObservation>()
    private data class CachedPlanningTerrain(val world: WorldHandle, val map: MapDefinition,
        val knowledge: KnowledgeSnapshot?, val terrain: PlanningTerrain, val buildings: Set<Pair<Int, Int>>? = null,
        val escapingBuildings: Boolean = false)
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
        val events: List<Event>, val results: List<ActionResult>, val projectiles: List<VisibleProjectile>,
        val projectileEvents: List<VisibleProjectileEvent>, val balance: Long?, val factoryQueues: List<FactoryQueueObservation>)

    private data class CachedPlayerObservation(val key: PlayerObservationKey,
        val observation: PlayerObservation, val revision: Long, val source: Observation)

    // Initial IDs precede the reserved production keys; births append in creation order.
    // Public observation sorting is separate from deterministic gameplay traversal.
    private fun orderedUnits(): Collection<MutableUnit> = units.values

    private data class StepCheckpoint(
        val units: MutableMap<String, MutableUnit>, val events: MutableList<Event>,
        val results: MutableList<ActionResult>, val revision: Long, val observation: Observation?,
        val owners: IdentityHashMap<ActionResult, String>, val playerRevisions: Map<String, Long>,
        val projectiles: MutableList<MutableProjectile>, val frame: Int, val nextObjectId: Long,
        val diagnostics: MutableList<PathSearchSample>?, val noFog: CachedPlanningTerrain?,
        val planning: Map<String, CachedPlanningTerrain>,
        val projectileEvents: MutableList<ProjectileEventObservation>,
        val buildings: BuildingTerrain?, val buildingKnowledge: Map<String, BuildingKnowledge>,
        val buildingDiscoveries: Map<String, Map<Pair<Int, Int>, Long>>,
        val ledger: ProductionLedger?, val birthSerial: Long,
    )

    private fun checkpoint() = StepCheckpoint(units, pendingEvents, pendingActionResults, revision,
        latestObservation, IdentityHashMap(actionResultOwners), HashMap(playerActionRevisions), projectiles, combatFrame, nextObjectId,
        pathDiagnostics, noFogPlanning, HashMap(playerPlanning), pendingProjectileEvents,
        buildingTerrain, buildingKnowledge, pendingBuildingDiscoveries, productionLedger, lastBirthSerial)

    private fun restore(checkpoint: StepCheckpoint) {
        units = checkpoint.units
        pendingEvents = checkpoint.events
        pendingProjectileEvents = checkpoint.projectileEvents
        pendingActionResults = checkpoint.results
        revision = checkpoint.revision
        latestObservation = checkpoint.observation
        projectiles = checkpoint.projectiles
        combatFrame = checkpoint.frame
        nextObjectId = checkpoint.nextObjectId
        buildingTerrain = checkpoint.buildings
        buildingKnowledge = checkpoint.buildingKnowledge
        pendingBuildingDiscoveries = checkpoint.buildingDiscoveries
        productionLedger = checkpoint.ledger
        lastBirthSerial = checkpoint.birthSerial
        pathDiagnostics = checkpoint.diagnostics
        noFogPlanning = checkpoint.noFog
        playerPlanning.clear()
        playerPlanning.putAll(checkpoint.planning)
        actionResultOwners = checkpoint.owners
        playerActionRevisions.clear()
        playerActionRevisions.putAll(checkpoint.playerRevisions)
    }

    private data class AutomaticKnowledgeCommit(
        val stores: Map<String, PlayerTerrainKnowledge>,
        val views: Map<String, VisionSnapshot>,
        val discoveries: Map<String, MutableMap<Pair<Int, Int>, Long>>,
    )

    private data class BuildingCommit(val truth: BuildingTerrain,
        val owners: Map<String, BuildingKnowledge>, val discoveries: Map<String, Map<Pair<Int, Int>, Long>>)

    private fun prepareBuildings(views: Map<String, VisionSnapshot>?): BuildingCommit? {
        val old = buildingTerrain ?: return null
        val input = checkNotNull(scenario)
        val truth = BuildingTerrain.fromLive(input.map, units.values, old)
        val owners = linkedMapOf<String, BuildingKnowledge>()
        val discoveries = linkedMapOf<String, Map<Pair<Int, Int>, Long>>()
        val live = units.values.filter { it.alive && it.definition.building != null }
        for (player in input.players) {
            val view = views?.getValue(player.playerId)
            val authorized = live.filter { unit ->
                view == null || view.contains(MapBounds.cell(unit.position.x, input.map.tileWidth, input.map.widthTiles),
                    MapBounds.cell(unit.position.y, input.map.tileHeight, input.map.heightTiles))
            }.flatMapTo(linkedSetOf()) { unit ->
                val building = checkNotNull(unit.definition.building)
                BuildingTerrain.footprint(input.map, unit.position, building.footprint, building.anchor)
            }
            val previous = buildingKnowledge[player.playerId]
            val next = BuildingKnowledge.projected(previous, authorized)
            owners[player.playerId] = next
            val added = next.cells - (previous?.cells ?: emptySet())
            if (added.isNotEmpty()) discoveries[player.playerId] = Collections.unmodifiableMap(added.associateWith { next.revision })
        }
        return BuildingCommit(truth, Collections.unmodifiableMap(owners), Collections.unmodifiableMap(discoveries))
    }

    private fun commitBuildings(next: BuildingCommit) {
        buildingTerrain = next.truth
        buildingKnowledge = next.owners
        pendingBuildingDiscoveries = next.discoveries
    }

    private fun prepareAutomaticKnowledge(): AutomaticKnowledgeCommit? {
        val input = checkNotNull(scenario)
        if (input.automaticVision == null) return null
        val rules = checkNotNull(knowledgeRules)
        val oldStores = checkNotNull(knowledgeStores)
        val groundGids = input.map.groundGids
        val views = AutomaticVision.compute(world.id, (automaticVisionSnapshots?.values?.firstOrNull()?.epoch ?: -1L) + 1,
            input.map.widthTiles, input.map.heightTiles, input.map.tileWidth, input.map.tileHeight, input.players,
            units.values.filter { it.alive }.map { VisionSource(it.ownerPlayerId, it.position, it.definition.fogOfWarSightRange) }, rules.shareAlliedVision)
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
        private const val PRODUCED_UNIT_PREFIX = "produced:"
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
        productionLedger = null
        lastBirthSerial = 0L
        buildingTerrain = null
        buildingKnowledge = emptyMap()
        pendingBuildingDiscoveries = emptyMap()
        units = linkedMapOf()
        projectiles.clear()
        projectileViews.clear()
        pendingEvents.clear()
        pendingProjectileEvents.clear()
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
        val actor = currentScenario.players.firstOrNull { it.playerId == action.actorPlayerId }
            ?: return reject(action, "unknown-actor", "Actor is not a declared player")
        val unit = when (val access = resolveCommandActor(action, actor)) {
            is CommandActor.Available -> access.unit
            is CommandActor.Denied -> return reject(action, access.error.code, access.error.message)
        }
        if (!unit.alive) return reject(action, "inactive-unit", "Unit is not active")
        if (action is MoveAction && unit.definition.movement.type == MovementType.NONE) {
            return reject(action, "immobile-unit", "Unit cannot move")
        }
        if (action is MoveAction && (!action.target.x.isFinite() || !action.target.y.isFinite())) {
            return reject(action, "invalid-target", "Target coordinates must be finite")
        }
        if (action is AttackAction) validateAttack(action, unit)?.let {
            return reject(action, it.code, it.message)
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

    private sealed interface CommandActor {
        data class Available(val unit: MutableUnit) : CommandActor
        data class Denied(val error: SimulationError) : CommandActor
    }

    /** Namespace is a fixed public content capability, never a query of live factories or queues. */
    private fun productionNamespaceEnabled(input: Scenario): Boolean = input.unitDefinitions.values.any { it.production != null }

    private fun resolveCommandActor(action: Action, actor: Player): CommandActor {
        fun denied(code: String, message: String) = CommandActor.Denied(SimulationError(code, message))
        fun unavailable() = denied("unit-unavailable", "Unit is not available")
        val input = checkNotNull(scenario)
        if (productionNamespaceEnabled(input) && action.unitId.startsWith(PRODUCED_UNIT_PREFIX)) {
            // The fixed 32-hex suffix delimits owner IDs even when the owner itself contains ':'.
            val separator = action.unitId.length - 33
            if (separator <= PRODUCED_UNIT_PREFIX.length || action.unitId[separator] != ':') return unavailable()
            val nonce = action.unitId.substring(separator + 1)
            if (nonce.any { it !in '0'..'9' && it !in 'a'..'f' }) return unavailable()
            val owner = action.unitId.substring(PRODUCED_UNIT_PREFIX.length, separator)
            if (input.players.none { it.playerId == owner }) return unavailable()
            if (action is SelectAction) {
                val unit = orderedUnits().firstOrNull {
                    it.unitId == action.unitId && isVisibleTo(actor.playerId, it.position)
                } ?: return unavailable()
                return CommandActor.Available(unit)
            }
            if (owner != actor.playerId) return unavailable()
            if (actor.control != PlayerControl.LOCAL) return denied("actor-not-controlled", "Player has no local control")
            val unit = units[action.unitId]?.takeIf { it.ownerPlayerId == actor.playerId } ?: return unavailable()
            return CommandActor.Available(unit)
        }
        val unit = units[action.unitId] ?: return denied("unknown-unit", "Unknown unit '${action.unitId}'")
        if (action !is SelectAction && unit.ownerPlayerId != actor.playerId)
            return denied("not-owner", "Player '${action.actorPlayerId}' does not own '${action.unitId}'")
        if (action !is SelectAction && actor.control != PlayerControl.LOCAL)
            return denied("actor-not-controlled", "Player has no local control")
        if (action is SelectAction && !isVisibleTo(actor.playerId, unit.position))
            return denied("not-visible", "Unit is outside the player's current vision")
        return CommandActor.Available(unit)
    }

    /** Ground follows the configured source; dynamic buildings always follow owner visibility. */
    private fun planningTerrainFor(unit: MutableUnit, map: MapDefinition): PlanningTerrain {
        val ownerBuildings = buildingKnowledge[unit.ownerPlayerId]?.cells
        val escaping = unit.birthRemaining == 0f && ownerBuildings?.contains(
            MapBounds.cell(unit.position.x, map.tileWidth, map.widthTiles) to
                MapBounds.cell(unit.position.y, map.tileHeight, map.heightTiles)) == true
        val stores = knowledgeStores
        val usesKnowledge = stores != null &&
            checkNotNull(knowledgeRules).pathSource == PathInformationSource.PLAYER_KNOWLEDGE
        // Preserve the shared legacy ground-only adapter when no building subsystem is active.
        if (!usesKnowledge && ownerBuildings == null) {
            val cached = noFogPlanning
            if (cached != null && cached.world === activeWorld && cached.map === map) return cached.terrain
            return PlanningTerrain.fromNoFog(map).also {
                noFogPlanning = CachedPlanningTerrain(world, map, null, it)
            }
        }
        val snapshot = if (usesKnowledge) checkNotNull(stores?.get(unit.ownerPlayerId)).snapshot(world) else null
        val cached = playerPlanning[unit.ownerPlayerId]
        if (cached != null && cached.world === activeWorld && cached.map === map && cached.knowledge === snapshot &&
            cached.buildings === ownerBuildings && cached.escapingBuildings == escaping) return cached.terrain
        val ground = if (snapshot == null) PlanningTerrain.fromNoFog(map)
            else PlanningTerrain.fromKnowledge(snapshot, map.tileWidth, map.tileHeight)
        return (if (escaping) ground else ground.withBuildings(ownerBuildings ?: emptySet())).also {
            playerPlanning[unit.ownerPlayerId] = CachedPlanningTerrain(world, map, snapshot, it, ownerBuildings, escaping)
        }
    }

    private fun findPathWithDiagnostics(actionId: String, atStep: Long?, target: Position,
                                        unit: MutableUnit, map: MapDefinition,
                                        samples: MutableList<PathSearchSample>,
                                        reason: PathSearchReason = PathSearchReason.USER_MOVE,
                                        executionStep: Long? = null,
                                        start: Position = unit.position,
                                        preservedPrefixPointCount: Int = 0,
                                        targetRange: Double = 0.0): List<Position>? {
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
            val path = policies.path.findPath(terrain, start, target, unit.definition.radius, counters, targetRange)
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

    private fun plannedBuildingRevision(unit: MutableUnit): Long = buildingKnowledge[unit.ownerPlayerId]?.revision ?: -1L

    private fun replanForKnowledge(map: MapDefinition, executionStep: Long) {
        if (pendingKnowledgeDiscoveries.isEmpty() && pendingBuildingDiscoveries.isEmpty()) return
        for (unit in orderedUnits()) {
            if (unit.birthRemaining > 0f) continue
            val target = unit.moveTarget ?: continue
            val snapshot = knowledgeStores?.get(unit.ownerPlayerId)?.snapshot(world)
            val ground = pendingKnowledgeDiscoveries[unit.ownerPlayerId].orEmpty()
            val buildings = pendingBuildingDiscoveries[unit.ownerPlayerId].orEmpty()
            val affectedGround = ground.any { (cell, discoveredAt) ->
                knowledgeRules?.pathSource == PathInformationSource.PLAYER_KNOWLEDGE &&
                    discoveredAt > unit.lastPlannedKnowledgeRevision && checkNotNull(snapshot).planningBlocked(cell.first, cell.second) &&
                    KnowledgePathImpact.affectsCenter(unit.position, unit.pathPoints, target,
                        cell.first, cell.second, map.tileWidth, map.tileHeight)
            }
            val affected = affectedGround || buildings.any { (cell, discoveredAt) ->
                discoveredAt > unit.lastPlannedBuildingRevision && cell in checkNotNull(buildingKnowledge[unit.ownerPlayerId]).cells &&
                    KnowledgePathImpact.affectsCenter(unit.position, unit.pathPoints, target,
                        cell.first, cell.second, map.tileWidth, map.tileHeight)
            }
            if (!affected) continue
            if (unit.attackCommand != null) unit.lastAttackPathAttemptStep = executionStep
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
                unit.adoptRoute(path, plannedKnowledgeRevision(unit), plannedBuildingRevision(unit))
            }
        }
    }

    private fun repairInvalidConnections(map: MapDefinition, executionStep: Long) {
        for (unit in orderedUnits()) {
            if (unit.birthRemaining > 0f) continue
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
            if (unit.attackCommand != null) unit.lastAttackPathAttemptStep = executionStep
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
                unit.adoptRoute(path, plannedKnowledgeRevision(unit), plannedBuildingRevision(unit))
            }
        }
    }

    private fun validateAttack(action: AttackAction, attacker: MutableUnit): SimulationError? {
        if (attacker.definition.combat == null) return SimulationError("cannot-attack", "Unit cannot attack")
        val input = checkNotNull(scenario)
        val team = input.players.single { it.playerId == attacker.ownerPlayerId }.teamId
        val target = orderedUnits().asSequence().filter {
            it.alive && isVisibleTo(action.actorPlayerId, it.position)
        }.firstOrNull { it.unitId == action.targetUnitId }
        val enemy = target != null && target.ownerPlayerId != attacker.ownerPlayerId &&
            (team == null || input.players.single { it.playerId == target.ownerPlayerId }.teamId != team)
        return if (target == null || !enemy || target.health == null) {
            SimulationError("invalid-attack-target", "Attack target is not available")
        } else null
    }

    private fun applyAction(action: Action, executionStep: Long? = null): ActionResult {
        val currentScenario = checkNotNull(scenario)
        val actor = currentScenario.players.firstOrNull { it.playerId == action.actorPlayerId }
            ?: return reject(action, "unknown-actor", "Actor is not a declared player")
        val unit = when (val access = resolveCommandActor(action, actor)) {
            is CommandActor.Available -> access.unit
            is CommandActor.Denied -> return reject(action, access.error.code, access.error.message)
        }
        if (!unit.alive) return reject(action, "inactive-unit", "Unit is not active")
        if (action is MoveAction && unit.definition.movement.type == MovementType.NONE) {
            return reject(action, "immobile-unit", "Unit cannot move")
        }
        val result = when (action) {
            is ProduceAction -> when (val change = applyProduction(action, unit, action.productionTypeId, action.unitNonce)) {
                is ActionResult.Accepted -> change
                is ActionResult.Rejected -> return change
            }
            is CancelProductionAction -> when (val change = applyProduction(action, unit, action.productionTypeId, null)) {
                is ActionResult.Accepted -> change
                is ActionResult.Rejected -> return change
            }
            is AttackAction -> {
                validateAttack(action, unit)?.let { return reject(action, it.code, it.message) }
                unit.cancelRoute()
                unit.attackCommand = ManualAttackCommand(action.targetUnitId, action.actionId, action.atStep)
                unit.lastAttackPathAttemptStep = null
                ActionResult.Accepted(action.actionId, listOf(Event("attack-started", unit.unitId)))
            }
            is SelectAction -> {
                if (!isVisibleTo(action.actorPlayerId, unit.position)) return reject(action, "not-visible", "Unit is outside the player's current vision")
                ActionResult.Accepted(action.actionId)
            }
            is StopAction -> {
                unit.attackCommand = null
                unit.combat?.targetId = null
                unit.cancelRoute()
                ActionResult.Accepted(action.actionId, listOf(Event("movement-cancelled", unit.unitId)))
            }
            is MoveAction -> {
                if (!action.target.x.isFinite() || !action.target.y.isFinite()) {
                    return reject(action, "invalid-target", "Target coordinates must be finite")
                }
                val samples = pathDiagnostics
                val path = if (unit.birthRemaining > 0f) {
                    if (MapBounds.contains(currentScenario.map.widthTiles.toDouble() * currentScenario.map.tileWidth,
                            currentScenario.map.heightTiles.toDouble() * currentScenario.map.tileHeight,
                            action.target, unit.definition.radius)) listOf(action.target) else null
                } else if (samples == null) {
                    policies.path.findPath(planningTerrainFor(unit, currentScenario.map), unit.position, action.target, unit.definition.radius)
                } else {
                    findPathWithDiagnostics(action.actionId, action.atStep, action.target, unit, currentScenario.map, samples,
                        executionStep = executionStep)
                }
                unit.attackCommand = null
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
                    unit.adoptRoute(path, plannedKnowledgeRevision(unit), plannedBuildingRevision(unit))
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

    private fun applyProduction(action: Action, unit: MutableUnit, typeId: String, nonce: String?): ActionResult {
        if (unit.definition.production == null) return reject(action, "cannot-produce", "Unit cannot produce")
        val ledger = checkNotNull(productionLedger) { "A production world requires its registered ledger" }
        val change = if (nonce != null) ledger.enqueue(unit.unitId, typeId, nonce) else ledger.cancel(unit.unitId, typeId)
        return when (change) {
            is ProductionChange.Accepted -> {
                productionLedger = change.ledger
                ActionResult.Accepted(action.actionId, listOf(Event(
                    if (nonce != null) "production-queued" else "production-cancelled", unit.unitId)))
            }
            is ProductionChange.Rejected -> when (change.error) {
                ProductionError.UNKNOWN_FACTORY, ProductionError.INACTIVE_FACTORY ->
                    error("An authorized live producer must have an active ledger registration")
                ProductionError.INVALID_TYPE -> reject(action, "unsupported-production-type", "Unit cannot produce this type")
                ProductionError.INVALID_NONCE -> reject(action, "invalid-unit-nonce", "Unit nonce must be 32 lowercase hexadecimal characters")
                ProductionError.DUPLICATE_NONCE -> reject(action, "duplicate-unit-nonce", "Unit nonce has already been used")
                ProductionError.INSUFFICIENT_FUNDS -> reject(action, "insufficient-funds", "Insufficient funds")
                ProductionError.EMPTY_QUEUE -> reject(action, "empty-production-queue", "No matching production item is queued")
            }
        }
    }

    private fun advanceProduction(factory: MutableUnit, input: Scenario) {
        val tick = checkNotNull(productionLedger).advance(factory.unitId)
        tick.completed?.let { completed ->
            check(completed.ownerPlayerId == factory.ownerPlayerId)
            val definition = input.unitDefinitions.getValue(completed.typeId)
            val publicId = "produced:${factory.ownerPlayerId}:${completed.unitNonce}"
            check(publicId !in units) { "A paid production nonce must identify a new unit" }
            val position = Position(factory.position.x, factory.position.y + 5.0)
            require(MapBounds.contains(input.map.widthTiles.toDouble() * input.map.tileWidth,
                input.map.heightTiles.toDouble() * input.map.tileHeight, position, definition.radius)) {
                "Produced unit collision circle must remain within the map"
            }
            val objectId = nextObjectId
            nextObjectId = Math.addExact(nextObjectId, 1L)
            lastBirthSerial = Math.incrementExact(lastBirthSerial)
            val updateKey = "~produced/" + lastBirthSerial.toString().padStart(20, '0')
            check(orderedUnits().lastOrNull()?.updateKey?.let { it < updateKey } != false)
            val exit = Position(factory.position.x - 1.0, factory.position.y + 90.0)
            val born = MutableUnit(publicId, factory.ownerPlayerId, completed.typeId, definition, position, 180.0,
                objectId = objectId, updateKey = updateKey, producerId = factory.unitId, birthRemaining = 30f)
            if (MapBounds.contains(input.map.widthTiles.toDouble() * input.map.tileWidth,
                    input.map.heightTiles.toDouble() * input.map.tileHeight, exit, definition.radius)) {
                born.moveTarget = exit
                born.originMoveId = "production-exit:$publicId"
                born.adoptRoute(listOf(exit), -1L)
            } else {
                pendingEvents += Event("movement-cancelled", publicId)
                pendingEvents += Event("path-unreachable", publicId)
            }
            units[publicId] = born
            pendingEvents += Event("unit-produced", publicId)
        }
        productionLedger = tick.ledger
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
        units = units.mapValuesTo(linkedMapOf()) { (_, unit) ->
            unit.copy(pathPoints = unit.pathPoints.toMutableList(), combat = unit.combat?.copy()) }
        projectiles = projectiles.mapTo(mutableListOf()) { it.copy() }
        pathDiagnostics = pathDiagnostics?.toMutableList()
        pendingEvents = pendingEvents.toMutableList()
        pendingProjectileEvents = pendingProjectileEvents.toMutableList()
        pendingActionResults = pendingActionResults.toMutableList()
        val motionSamples = motionDiagnostics?.let { mutableListOf<MotionStepSample>() }
        var nextVision: AutomaticKnowledgeCommit? = null
        var nextBuildings: BuildingCommit? = null
        var nextObservation: Observation? = null
        var nextOwners: IdentityHashMap<ActionResult, String>? = null
        val nextTime = simulationTime + step.delta
        try {
            combatFrame += 1
            actions.forEach { applyAction(it, step.stepIndex) }
            replanForKnowledge(currentScenario.map, step.stepIndex)
            repairInvalidConnections(currentScenario.map, step.stepIndex)
            for (unit in orderedUnits().toList()) {
                if (!unit.alive) continue
                if (unit.birthRemaining > 0f) {
                    unit.birthRemaining = maxOf(0f, unit.birthRemaining - 1f)
                    if (unit.birthRemaining == 0f) resumeProductionRoute(unit, currentScenario.map, step.stepIndex)
                }
                if (unit.definition.production != null) advanceProduction(unit, currentScenario)
                updateAttackRoute(unit, currentScenario.map, step.stepIndex)
                updateWeapon(unit, step.delta)
                updateUnit(unit, currentScenario.map, step.delta, step.stepIndex, motionSamples)
                unit.combat?.let { it.cooldown = maxOf(0.0, it.cooldown - step.delta) }
            }
            updateProjectiles(step.delta)
            nextVision = prepareAutomaticKnowledge()
            nextBuildings = prepareBuildings(nextVision?.views ?: automaticVisionSnapshots)
            require(nextTime.isFinite()) { "Simulation time must remain finite" }
            validateCandidate()
            nextObservation = makeObservation(step.stepIndex, nextTime, true, revision + 1L)
            nextOwners = IdentityHashMap<ActionResult, String>(nextObservation.actionResults.size)
            for (result in nextObservation.actionResults) actionResultOwners[result]?.let { nextOwners[result] = it }
        } catch (error: Exception) {
            nextVision?.stores?.values?.forEach { it.close() }
            restore(previous)
            throw error
        }
        pendingKnowledgeDiscoveries.clear()
        nextVision?.let { commitAutomaticKnowledge(it) }
        nextBuildings?.let { commitBuildings(it) }
        scheduledActions.removeAll(actions.toSet())
        stepIndex = step.stepIndex
        hasAdvanced = true
        simulationTime = nextTime
        revision += 1L
        val observation = checkNotNull(nextObservation)
        latestObservation = observation
        committedObservation = observation
        // Only this committed window and subsequent immediate results can be observed.
        // Replace the map as well as its entries: a previous burst must not retain its capacity.
        actionResultOwners = checkNotNull(nextOwners)
        pendingEvents.clear()
        pendingProjectileEvents.clear()
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
        val visibleUnits = observation.units.filter { isVisibleTo(playerId, it.position) }
        val visibleIds = visibleUnits.map { it.unitId }.toSet()
        val visible = visibleUnits.map {
            if (it.ownerPlayerId != playerId) {
                it.copy(moveTarget = null, pathPoints = emptyList(), attackTargetId = null, cooldown = null)
            } else if (it.attackTargetId != null && it.attackTargetId !in visibleIds) {
                it.copy(moveTarget = null, pathPoints = emptyList(), attackTargetId = null)
            } else it
        }
        val ids = visible.map { it.unitId }.toSet()
        fun visibleEvents(events: List<Event>): List<Event> {
            val allowed = events.filter { event -> event.unitId != null && event.unitId in ids &&
                (!event.kind.startsWith("production-") || visible.any { it.unitId == event.unitId && it.ownerPlayerId == playerId }) }
            val combatKinds = setOf("projectile-fired", "unit-damaged", "unit-died",
                "production-queued", "production-cancelled", "production-refunded", "unit-produced")
            // S1 command events retain submission order. Combat order is public-value canonical,
            // never the hidden source/host projectile allocation order used by the simulator.
            return allowed.filter { it.kind !in combatKinds } + allowed.filter { it.kind in combatKinds }
                .sortedWith(compareBy<Event> { it.kind }.thenBy { it.unitId })
        }
        // Keep the committed step's events when immediate actions replace the host observation.
        // Hidden actions must neither erase these events nor advance the player's sample identity.
        val committed = committedObservation?.takeIf { it !== observation }
        val events = visibleEvents(committed?.events.orEmpty() + observation.events)
        val results = (committed?.actionResults.orEmpty() + observation.actionResults).filter { actionResultOwners[it] == playerId }.map {
            if (it is ActionResult.Accepted) ActionResult.Accepted(it.actionId, visibleEvents(it.events), it.queuedForStep) else it
        }
        val rawProjectileEvents = committed?.projectileEvents.orEmpty() + observation.projectileEvents
        val projectedEvents = rawProjectileEvents.filter { isVisibleTo(playerId, it.projectile.position) }.map { event ->
            val value = event.projectile
            val target = value.targetUnitId.takeIf { it in ids }
            ProjectedProjectileEvent(value.objectId, event.kind, value.position, value.heading, value.height,
                value.sourceUnitId.takeIf { it in ids }, target, event.damage.takeIf { target != null })
        }
        val projection = projectileViews.getOrPut(playerId) { ProjectileProjection() }.project(
            observation.projectiles.filter { isVisibleTo(playerId, it.position) }, projectedEvents,
            observation.projectiles.mapTo(mutableSetOf()) { it.objectId }.also { retained ->
                rawProjectileEvents.forEach { retained += it.projectile.objectId }
            })
        val balance = observation.economy?.balances?.getValue(playerId)
        val factoryQueues = if (input.players.first { it.playerId == playerId }.control == PlayerControl.LOCAL)
            visible.filter { it.ownerPlayerId == playerId && it.alive &&
                input.unitDefinitions.getValue(it.typeId).production != null }.sortedBy { it.unitId }.map { factory ->
                    FactoryQueueObservation(factory.unitId, observation.economy?.queuedProduction?.getValue(factory.unitId).orEmpty()
                        .map { ProductionQueueItem(it.typeId, it.progress) })
                } else emptyList()
        val key = PlayerObservationKey(stepIndex, simulationTime, vision?.epoch, knowledge?.revision,
            playerActionRevisions[playerId] ?: 0L, visible, events, results, projection.projectiles, projection.events, balance, factoryQueues)
        if (cached?.key == key) {
            playerObservations[playerId] = cached.copy(source = observation)
            return cached.observation
        }
        val playerRevision = (cached?.revision ?: -1L) + 1L
        val result = PlayerObservation("${world.id}:$playerId:${if (hasAdvanced) "step$stepIndex" else "initial"}:${vision?.epoch ?: 0L}:$playerRevision",
            world.id, input.scenarioId, playerId, stepIndex, simulationTime, vision, knowledge,
            visible, events, results, projection.projectiles, projection.events, balance, factoryQueues)
        playerObservations[playerId] = CachedPlayerObservation(key, result, playerRevision, observation)
        return result
    }

    private fun initializeInternal(nextScenario: Scenario): InitResult {
        if (!closed) return InitResult.Rejected(SimulationError("already-open", "Close the current world before initializing"))
        val error = validate(nextScenario)
        if (error != null) return InitResult.Rejected(error)
        val initialBuildings = try { BuildingTerrain.initial(nextScenario) } catch (failure: BuildingInitializationException) {
            return InitResult.Rejected(failure.error)
        }
        val initialLedger = nextScenario.economyContract?.let { economy ->
            val factories = nextScenario.units.mapNotNull { spawn ->
                val producer = nextScenario.unitDefinitions.getValue(spawn.typeId).production ?: return@mapNotNull null
                val build = checkNotNull(nextScenario.unitDefinitions.getValue(producer.unitTypeId).build)
                spawn.unitId to FactoryProductionSpec(spawn.ownerPlayerId, producer.unitTypeId, build)
            }.toMap()
            ProductionLedger.initial(economy.initialBalances, factories)
        }
        worldGeneration += 1
        activeWorld = WorldHandle("${nextScenario.scenarioId}:$worldGeneration")
        scenario = nextScenario.copy(
            unitDefinitions = Collections.unmodifiableMap(LinkedHashMap(nextScenario.unitDefinitions)),
            players = immutable(nextScenario.players),
            units = immutable(nextScenario.units),
            combatContract = nextScenario.combatContract?.let {
                it.copy(unitObjectIds = Collections.unmodifiableMap(LinkedHashMap(it.unitObjectIds))) },
            economyContract = nextScenario.economyContract?.let {
                it.copy(initialBalances = Collections.unmodifiableMap(LinkedHashMap(it.initialBalances))) },
        )
        productionLedger = initialLedger
        buildingTerrain = initialBuildings
        units = linkedMapOf()
        projectiles = mutableListOf()
        combatFrame = nextScenario.combatContract?.initialFrameCounter ?: -1
        nextObjectId = nextScenario.combatContract?.nextObjectId ?: 1L
        lastBirthSerial = 0L
        nextScenario.units.sortedBy { it.unitId }.forEach { spawn ->
            units[spawn.unitId] = MutableUnit(
                unitId = spawn.unitId,
                ownerPlayerId = spawn.ownerPlayerId,
                typeId = spawn.typeId,
                definition = checkNotNull(nextScenario.unitDefinitions[spawn.typeId]),
                position = spawn.position,
                heading = policies.coordinates.normalize(spawn.heading),
                height = spawn.height,
                objectId = nextScenario.combatContract?.unitObjectIds?.getValue(spawn.unitId),
            )
        }
        closed = false
        stepIndex = 0
        simulationTime = 0.0
        hasAdvanced = false
        revision = 0
        pendingEvents = mutableListOf()
        pendingProjectileEvents = mutableListOf()
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
        try {
            prepareBuildings(automaticVisionSnapshots)?.let { commitBuildings(it) }
        } catch (failure: Exception) {
            close()
            throw failure
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
        if (productionNamespaceEnabled(input) && input.units.any { it.unitId.startsWith(PRODUCED_UNIT_PREFIX) })
            return SimulationError("reserved-unit-id", "Production scenes reserve the produced unit namespace")
        val players = input.players.map { it.playerId }.toSet()
        input.economyContract?.let { economy ->
            if (economy.initialBalances.keys != players || economy.initialBalances.values.any { it < 0L }) {
                return SimulationError("invalid-economy-contract", "Expected an explicit nonnegative balance for every declared player")
            }
        }
        if (input.unitDefinitions.values.any { it.production != null } && input.economyContract == null) {
            return SimulationError("missing-economy-contract", "Production definitions require an explicit economy contract")
        }
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
            definition.production?.let { production ->
                val build = input.unitDefinitions[production.unitTypeId]?.build
                if (definition.building == null || build == null || build.price < 0L || !build.rate.isFinite() || build.rate <= 0f)
                    return SimulationError("unsupported-production", "Producer requires a fixed building and valid target build metadata")
            }
            definition.building?.let { building ->
                val shape = building.footprint
                if (m.type != MovementType.NONE || definition.maximumHealth == null || definition.radius <= 0.0 ||
                    shape.left > 0 || shape.top > 0 || shape.right < 0 || shape.bottom < 0 ||
                    shape.left > shape.right || shape.top > shape.bottom ||
                    building.anchor?.let { !it.offsetX.isFinite() || !it.offsetY.isFinite() } == true)
                    return SimulationError("unsupported-building", "Expected an immobile live building with an ordered footprint containing its center")
            }
            val numbers = listOf(definition.radius, m.moveSpeed, m.moveAccelerationSpeed, m.moveDecelerationSpeed,
                m.maxTurnSpeed, m.turnAcceleration)
            if (numbers.any { !it.isFinite() || it < 0.0 } ||
                (definition.building == null && definition.radius >= input.map.tileWidth) || m.slidingMode || m.ignoringBody) {
                return SimulationError("unsupported-unit", "Expected finite nonnegative, non-sliding movement and radius below tile size")
            }
            if (m.type == MovementType.NONE && numbers.drop(1).any { it != 0.0 })
                return SimulationError("unsupported-unit", "Immobile definitions require zero movement parameters")
            definition.durability?.let { durability ->
                if (!durability.maxHp.isFinite() || durability.maxHp <= 0.0 ||
                    (definition.combat != null && durability.maxHp != definition.combat.maxHp)) {
                    return SimulationError("unsupported-durability", "Expected finite positive health consistent with the combat definition")
                }
            }
            definition.combat?.let { combat ->
                val positive = listOf(combat.maxHp, combat.attackRange, combat.shootDelay, combat.projectile.life,
                    combat.projectile.speed, combat.recoil.outTime, combat.recoil.returnTime)
                val nonnegative = listOf(combat.turretSize, combat.turretTurnSpeed, combat.turretTurnAcceleration, combat.projectile.directDamage)
                val signed = listOf(combat.mount.localX, combat.mount.localY, combat.recoil.offset,
                    combat.recoil.outTime + combat.recoil.returnTime)
                val invalidIdle = combat.idleSweep?.let { !it.angle.isFinite() || it.angle <= 0.0 ||
                    !it.delay.isFinite() || it.delay <= 0f || !it.speedFactor.isFinite() || it.speedFactor <= 0f ||
                    it.randomDelayExclusive <= 0 } == true
                if (positive.any { !it.isFinite() || it <= 0.0 } || nonnegative.any { !it.isFinite() || it < 0.0 } ||
                    signed.any { !it.isFinite() } || invalidIdle)
                    return SimulationError("unsupported-combat", "Ordinary combat numbers must be finite and in range")
            }
        }
        if (input.unitDefinitions.values.any { it.combat != null } && input.combatContract == null)
            return SimulationError("missing-combat-contract", "Combat scenes require an explicit identity and seed contract")
        input.combatContract?.let { combat ->
            val values = combat.unitObjectIds.values
            if (combat.unitObjectIds.keys != input.units.map { it.unitId }.toSet() || values.any { it <= 0L } ||
                values.toSet().size != values.size || combat.nextObjectId <= (values.maxOrNull() ?: 0L) ||
                combat.nextObjectId == Long.MAX_VALUE)
                return SimulationError("invalid-combat-contract", "Expected complete unique positive IDs and a usable next object ID")
        }
        for (spawn in input.units) {
            val p = spawn.position
            val radius = checkNotNull(input.unitDefinitions[spawn.typeId]).radius
            if (!MapBounds.contains(input.map.widthTiles.toDouble() * input.map.tileWidth,
                    input.map.heightTiles.toDouble() * input.map.tileHeight, p, radius)) {
                return SimulationError("invalid-spawn", "Unit '${spawn.unitId}' collision circle must be finite and entirely within the map")
            }
            if (!spawn.heading.isFinite() || !spawn.height.isFinite() || policies.terrain.intersects(input.map, p, radius)) {
                return SimulationError("invalid-spawn", "Unit '${spawn.unitId}' heading must be finite and spawn outside blocked terrain")
            }
            val definition = input.unitDefinitions.getValue(spawn.typeId)
            if (definition.combat != null && !OrdinaryWeapon.hasFiniteGeometry(MutableUnit(
                    unitId = spawn.unitId, ownerPlayerId = spawn.ownerPlayerId, typeId = spawn.typeId,
                    definition = definition, position = p,
                    heading = policies.coordinates.normalize(spawn.heading), height = spawn.height,
                    objectId = input.combatContract?.unitObjectIds?.getValue(spawn.unitId)))) {
                return SimulationError("unsupported-combat", "Initial weapon geometry must remain finite")
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

    private fun resumeProductionRoute(unit: MutableUnit, map: MapDefinition, executionStep: Long) {
        val target = unit.moveTarget ?: return
        val samples = pathDiagnostics
        val path = if (samples == null)
            policies.path.findPath(planningTerrainFor(unit, map), unit.position, target, unit.definition.radius)
        else findPathWithDiagnostics(checkNotNull(unit.originMoveId), unit.originAtStep, target, unit, map,
            samples, PathSearchReason.ROUTE_REPAIR, executionStep)
        if (unit.attackCommand != null) unit.lastAttackPathAttemptStep = executionStep
        if (path == null) {
            unit.cancelRoute()
            pendingEvents += Event("movement-cancelled", unit.unitId)
            pendingEvents += Event("path-unreachable", unit.unitId)
        } else unit.adoptRoute(path, plannedKnowledgeRevision(unit), plannedBuildingRevision(unit))
    }

    private fun updateAttackRoute(unit: MutableUnit, map: MapDefinition, executionStep: Long) {
        var command = unit.attackCommand ?: return
        val weapon = unit.definition.combat ?: return
        val input = checkNotNull(scenario)
        val team = input.players.single { it.playerId == unit.ownerPlayerId }.teamId
        fun eligible(target: MutableUnit): Boolean = target.alive && target.health != null &&
            target.ownerPlayerId != unit.ownerPlayerId &&
            (team == null || input.players.single { it.playerId == target.ownerPlayerId }.teamId != team)
        var target = units[command.targetUnitId]?.takeIf(::eligible)
        if (target == null) {
            val nearby = orderedUnits().filter { eligible(it) &&
                policies.coordinates.distanceSquared(unit.position, it.position) < weapon.attackRange * weapon.attackRange }
            val armed = nearby.filter { it.definition.combat != null }
            target = (if (armed.isNotEmpty()) armed else nearby).minByOrNull {
                policies.coordinates.distanceSquared(unit.position, it.position)
            }
            unit.cancelRoute()
            if (target == null) {
                unit.attackCommand = null
                pendingEvents += Event("attack-ended", unit.unitId)
                return
            }
            command = command.copy(targetUnitId = target.unitId)
            unit.attackCommand = command
            unit.lastAttackPathAttemptStep = null
        }
        if (unit.definition.movement.type == MovementType.NONE) {
            unit.cancelRoute()
            return
        }
        if (policies.coordinates.distanceSquared(unit.position, target.position) < weapon.attackRange * weapon.attackRange) {
            if (unit.moveTarget != null) unit.cancelRoute()
            return
        }
        val targetRange = if (target.definition.building == null) 0.0 else weapon.attackRange
        val routeEnd = unit.moveTarget
        val reusable = routeEnd != null && unit.pathPoints.isNotEmpty() &&
            if (targetRange == 0.0 || unit.birthRemaining > 0f) routeEnd == target.position
            else policies.coordinates.distanceSquared(routeEnd, target.position) < targetRange * targetRange &&
                !PlanningCenterTerrain.intersects(planningTerrainFor(unit, map), routeEnd, unit.definition.radius)
        if (unit.lastAttackPathAttemptStep == executionStep || reusable) return
        unit.lastAttackPathAttemptStep = executionStep
        val samples = pathDiagnostics
        val path = if (unit.birthRemaining > 0f) {
            if (MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth,
                    map.heightTiles.toDouble() * map.tileHeight, target.position, unit.definition.radius))
                listOf(target.position) else null
        } else if (samples == null) {
            policies.path.findPath(planningTerrainFor(unit, map), unit.position, target.position, unit.definition.radius,
                targetRange = targetRange)
        } else findPathWithDiagnostics(command.actionId, command.atStep, target.position, unit, map, samples,
            reason = PathSearchReason.ATTACK_PURSUIT, executionStep = executionStep, targetRange = targetRange)
        if (path == null) {
            unit.cancelRoute()
            pendingEvents += Event("path-unreachable", unit.unitId)
        } else {
            unit.moveTarget = path.last()
            unit.originMoveId = command.actionId
            unit.originAtStep = command.atStep
            unit.nearTargetTime = 0.0
            unit.adoptRoute(path, plannedKnowledgeRevision(unit), plannedBuildingRevision(unit))
        }
    }

    private fun updateWeapon(unit: MutableUnit, delta: Double) {
        val state = unit.combat ?: return
        val weapon = checkNotNull(unit.definition.combat)
        val input = checkNotNull(scenario)
        val contract = checkNotNull(input.combatContract)
        val actorId = checkNotNull(unit.objectId)
        OrdinaryWeapon.idle(contract.worldSeed, combatFrame, actorId, unit, delta)
        val team = input.players.single { it.playerId == unit.ownerPlayerId }.teamId
        fun eligible(target: MutableUnit) = target.alive && target.health != null &&
            target.ownerPlayerId != unit.ownerPlayerId &&
            (team == null || input.players.single { it.playerId == target.ownerPlayerId }.teamId != team)
        fun inRange(target: MutableUnit) = policies.coordinates.distanceSquared(unit.position, target.position) <
            weapon.attackRange * weapon.attackRange
        val manual = unit.attackCommand
        val manualTarget = manual?.let { units[it.targetUnitId]?.takeIf { candidate -> eligible(candidate) && inRange(candidate) } }
        val target = manualTarget ?: units[state.targetId]?.takeIf { eligible(it) && inRange(it) } ?: orderedUnits().filter {
                eligible(it) && inRange(it)
            }.sortedWith(compareBy<MutableUnit> { it.definition.combat == null }.thenBy {
                policies.coordinates.distanceSquared(unit.position, it.position)
            }).firstOrNull()
        if (target == null) {
            state.targetId = null
            return
        }
        if (state.targetId != target.unitId) {
            state.targetId = target.unitId
            if (target === manualTarget) OrdinaryWeapon.refreshOffset(contract.worldSeed, combatFrame, actorId, unit, target)
        }
        if (weapon.idleSweep != null) {
            state.idleHold = maxOf(state.idleHold, 70f)
            state.idleBaseHeading = state.turretHeading
        }
        val residual = OrdinaryWeapon.aim(unit, target, delta)
        if (state.cooldown > 0.0 || kotlin.math.abs(residual) >= 5.0) return
        state.cooldown = weapon.shootDelay
        val capturedOffset = state.offset
        val muzzle = OrdinaryWeapon.muzzle(unit)
        require(muzzle.x.isFinite() && muzzle.y.isFinite()) { "Projectile muzzle must remain finite" }
        state.randomCounter = (state.randomCounter.toLong() + 1L + actorId).toInt()
        val id = nextObjectId
        nextObjectId = Math.addExact(nextObjectId, 1L)
        val scatter = OrdinaryWeapon.sample(contract.worldSeed, combatFrame, actorId, unit, state.randomCounter, 0, 1000) * .001f
        state.randomCounter += 1
        val projectile = MutableProjectile(id, unit.unitId, target.unitId, muzzle, state.turretHeading,
            weapon.projectile.life, capturedOffset, scatter, weapon.projectile.speed, weapon.projectile.directDamage, unit.height)
        projectiles += projectile
        pendingProjectileEvents += ProjectileEventObservation("born", projectileObservation(projectile))
        OrdinaryWeapon.refreshOffset(contract.worldSeed, combatFrame, actorId, unit, target)
        pendingEvents += Event("projectile-fired", unit.unitId)
    }

    private fun updateProjectiles(delta: Double) {
        for (projectile in projectiles.sortedBy { it.objectId }) {
            val target = units[projectile.targetUnitId]
            if (target == null) {
                pendingProjectileEvents += ProjectileEventObservation("target-ended", projectileObservation(projectile))
                projectiles.remove(projectile)
                continue
            }
            val hit = OrdinaryWeapon.move(projectile, target, delta)
            if (hit) {
                var appliedDamage = 0.0
                if (target.alive && target.health != null) {
                    val before = checkNotNull(target.health)
                    target.health = maxOf(0.0, checkNotNull(target.health) - projectile.damage)
                    appliedDamage = before - checkNotNull(target.health)
                    pendingEvents += Event("unit-damaged", target.unitId)
                    if (!target.alive) {
                        if (target.definition.production != null) {
                            if (checkNotNull(productionLedger).queue(target.unitId).isNotEmpty())
                                pendingEvents += Event("production-refunded", target.unitId)
                            productionLedger = checkNotNull(productionLedger).destroyFactory(target.unitId)
                        }
                        target.attackCommand = null
                        target.cancelRoute()
                        target.speed = 0.0
                        target.angularVelocity = 0.0
                        target.combat?.targetId = null
                        pendingEvents += Event("unit-died", target.unitId)
                    }
                }
                pendingProjectileEvents += ProjectileEventObservation("impact", projectileObservation(projectile), appliedDamage)
                projectiles.remove(projectile)
            } else if (projectile.remainingLife == 0.0) {
                pendingProjectileEvents += ProjectileEventObservation("expired", projectileObservation(projectile))
                projectiles.remove(projectile)
            }
        }
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
        val attackTarget = unit.attackCommand?.targetUnitId?.let { units[it] }
        val approachingBuilding = attackTarget?.alive == true && attackTarget.definition.building != null &&
            unit.definition.combat?.let { weapon ->
                policies.coordinates.distanceSquared(unit.position, attackTarget.position) >= weapon.attackRange * weapon.attackRange
            } == true
        if (target != null) {
            val commandDistanceSquared = policies.coordinates.distanceSquared(unit.position, target)
            if (commandDistanceSquared < 1681.0) unit.nearTargetTime += delta
            val completionRadius = when {
                unit.nearTargetTime > 340.0 -> 36.0
                unit.nearTargetTime > 240.0 -> 16.0
                else -> 7.0
            }
            if (!approachingBuilding && policies.capture.inside(commandDistanceSquared, completionRadius)) {
                unit.cancelRoute()
                pendingEvents += Event("movement-arrived", unit.unitId)
                target = null
                normalCompleted = true
            }
        }
        if (target != null) {
            val temporaryBirth = unit.birthRemaining > 0f
            val local = if (temporaryBirth) null else unit.ensureLocalMotion()
            val steeringTarget = local?.aim ?: target
            val finalPoint = temporaryBirth || (unit.pathPoints.size == 1 && checkNotNull(local).isLast)
            val d2 = policies.coordinates.distanceSquared(unit.position, steeringTarget)
            val diff = policies.steering.turn(unit, steeringTarget, delta, policies.coordinates)
            val propulsionDistance = if (approachingBuilding && finalPoint) maxOf(d2, 16.0) else d2
            val fraction = policies.propulsion.targetFraction(movement, unit.speed, propulsionDistance, diff, finalPoint, contract.targetRadius)
            aimForSample = steeringTarget
            finalForSample = finalPoint
            differenceForSample = diff
            requestedFraction = fraction
            speedTarget = movement.moveSpeed * fraction
            if (temporaryBirth) {
                // cn supplies the command aim directly, without normal route-node lookahead.
            } else if (unit.pathPoints.size > 1 && policies.capture.inside(
                    policies.coordinates.distanceSquared(unit.position, unit.pathPoints.first()), contract.pathPointRadius) &&
                PlanningSegmentClearance.allows(planningTerrainFor(unit, map), unit.position,
                    unit.pathPoints[1], unit.definition.radius)) {
                unit.pathPoints.removeAt(0)
                unit.localMotion = null
                unit.localProgressTime = 0.0
            } else {
                val terrain = planningTerrainFor(unit, map)
                val progress = LocalMotionMaintenance.advance(checkNotNull(local), unit.localProgressTime, delta, fraction > 0.0,
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
        val resolved = if (unit.birthRemaining > 0f) TerrainMotion.resolve(map, unit.position, candidate)
            else TerrainMotion.resolve(buildingTerrain?.collisionMap ?: map, unit.position, candidate,
                map.takeIf { buildingTerrain != null })
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

    private fun validateCandidate() {
        val input = checkNotNull(scenario)
        val objectIds = mutableSetOf<Long>()
        var previousKey: String? = null
        for (unit in orderedUnits()) {
            require(listOf(unit.position.x, unit.position.y, unit.heading, unit.speed, unit.angularVelocity, unit.height)
                .all { it.isFinite() } && (unit.health?.isFinite() != false)) { "Unit candidate must remain finite" }
            require(unit.birthRemaining.isFinite() && unit.birthRemaining in 0f..30f) { "Invalid birth timer" }
            require(previousKey?.let { it < unit.updateKey } != false) { "Unit update keys must remain strictly ordered" }
            previousKey = unit.updateKey
            if (input.combatContract != null) {
                val id = checkNotNull(unit.objectId)
                require(id > 0L && id < nextObjectId && objectIds.add(id)) { "Invalid or duplicate unit object ID" }
            }
            unit.producerId?.let { producerId ->
                val producer = units.getValue(producerId)
                val nonce = unit.unitId.removePrefix("produced:${unit.ownerPlayerId}:")
                require(producer.ownerPlayerId == unit.ownerPlayerId &&
                    producer.definition.production?.unitTypeId == unit.typeId &&
                    Regex("[0-9a-f]{32}").matches(nonce) &&
                    unit.updateKey.startsWith("~produced/") &&
                    unit.updateKey.removePrefix("~produced/").toLong() in 1L..lastBirthSerial) {
                    "Invalid produced unit identity"
                }
            }
            require(MapBounds.contains(input.map.widthTiles.toDouble() * input.map.tileWidth,
                input.map.heightTiles.toDouble() * input.map.tileHeight, unit.position, unit.definition.radius)) {
                "Unit collision circle must remain within the map"
            }
            unit.health?.let { require(it >= 0.0 && it <= checkNotNull(unit.definition.maximumHealth) &&
                unit.alive == (it > 0.0)) { "Invalid unit life state" } }
            unit.combat?.let { state ->
                require(listOf(state.turretHeading, state.angularVelocity, state.cooldown, state.offset.x, state.offset.y,
                    state.idleBaseHeading, state.idleOffset)
                    .all { it.isFinite() }) { "Weapon candidate must remain finite" }
                require(state.idleTimer.isFinite() && state.idleHold.isFinite() && state.idleHold >= 0f &&
                    state.idleDirection in setOf(-1, 1)) { "Invalid idle candidate" }
                require(OrdinaryWeapon.hasFiniteGeometry(unit)) { "Weapon geometry must remain finite" }
            }
        }
        for (projectile in projectiles) {
            require(listOf(projectile.position.x, projectile.position.y,
                projectile.height, projectile.heading, projectile.remainingLife).all { it.isFinite() }) {
                "Projectile candidate must remain finite"
            }
            require(projectile.objectId > 0L && projectile.objectId < nextObjectId && objectIds.add(projectile.objectId)) {
                "Invalid or duplicate projectile object ID"
            }
        }
        productionLedger?.validateCandidate()
        // Ended/event-only objects also enter the public window; removing them from the active
        // container must not let an invalid numeric candidate bypass the S4 publication gate.
        for (event in pendingProjectileEvents) {
            val projectile = event.projectile
            require(listOf(projectile.position.x, projectile.position.y, projectile.height,
                projectile.heading, projectile.remainingLife).all { it.isFinite() } && event.damage?.isFinite() != false) {
                "Projectile event candidate must remain finite"
            }
        }
    }

    private fun recordObservation(): Observation = makeObservation(stepIndex, simulationTime, hasAdvanced, revision).also {
        latestObservation = it
    }

    private fun projectileObservation(projectile: MutableProjectile) = ProjectileObservation(projectile.objectId,
        projectile.sourceUnitId, projectile.targetUnitId, projectile.position, projectile.heading,
        projectile.remainingLife, projectile.offset, projectile.scatter, projectile.height)

    private fun makeObservation(observedStep: Long, observedTime: Double, advanced: Boolean, observedRevision: Long): Observation {
        val currentScenario = checkNotNull(scenario)
        val observation = Observation(
            sampleId = "${currentScenario.scenarioId}:$worldGeneration:${if (advanced) "step$observedStep" else "initial"}:$observedRevision",
            worldId = "${currentScenario.scenarioId}:$worldGeneration",
            scenarioId = currentScenario.scenarioId,
            stepIndex = observedStep,
            simulationTime = observedTime,
            units = orderedUnits().sortedBy { it.unitId }.map { unit ->
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
                    health = unit.health,
                    maxHealth = unit.definition.maximumHealth,
                    alive = unit.alive,
                    attackTargetId = unit.attackCommand?.targetUnitId,
                    turretHeading = unit.combat?.turretHeading ?: unit.heading,
                    cooldown = unit.combat?.cooldown,
                    height = unit.height,
                )
            },
            events = pendingEvents,
            actionResults = pendingActionResults,
            projectiles = projectiles.map(::projectileObservation),
            projectileEvents = pendingProjectileEvents,
            economy = productionLedger?.observe(),
            production = productionLedger?.let {
                ProductionObservation(lastBirthSerial, orderedUnits().mapNotNull { unit ->
                    unit.producerId?.let { producer -> ProducedUnitObservation(unit.unitId,
                        checkNotNull(unit.objectId), unit.updateKey, producer, unit.birthRemaining) }
                })
            },
            combat = currentScenario.combatContract?.let { contract ->
                CombatObservation(combatFrame, nextObjectId, orderedUnits().mapNotNull { unit ->
                    unit.combat?.let { UnitWeaponObservation(unit.unitId, checkNotNull(unit.objectId),
                        it.randomCounter, it.offset, it.targetId, it.angularVelocity,
                        OrdinaryWeapon.pivot(unit), OrdinaryWeapon.muzzle(unit)) }
                })
            },
        )
        return observation
    }

}

