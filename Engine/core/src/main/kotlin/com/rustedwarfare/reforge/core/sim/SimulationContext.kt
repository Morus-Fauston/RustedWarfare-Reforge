package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.BomberRules
import com.rustedwarfare.reforge.core.content.MechFlameRules
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.foundationMovementType
import com.rustedwarfare.reforge.core.content.DirectHitTargetKind
import com.rustedwarfare.reforge.core.content.ScoutBotRules
import com.rustedwarfare.reforge.core.content.ScoutPayloadStage
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import com.rustedwarfare.reforge.core.path.PlanningSegmentClearance
import com.rustedwarfare.reforge.core.path.PathDiagnosticsException
import com.rustedwarfare.reforge.core.path.PathSearchSample
import com.rustedwarfare.reforge.core.path.PathSearchCounters
import com.rustedwarfare.reforge.core.path.PathSearchOutcome
import com.rustedwarfare.reforge.core.path.KnowledgePathImpact
import com.rustedwarfare.reforge.core.path.movementBlocked
import com.rustedwarfare.reforge.core.path.PathSearchReason
import com.rustedwarfare.reforge.core.knowledge.*
import java.util.Collections
import java.util.IdentityHashMap

class SimulationContext private constructor(private val policies: ExperimentalPolicies = ExperimentalPolicies()) {
    private val steps = CandidateSteps()
    private val soundAuthority = SoundAuthority()
    private var activeWorld: WorldHandle? = null
    val world: WorldHandle get() = checkNotNull(activeWorld) { "Simulation is closed" }
    private var scenario: Scenario? = null
    private var units: MutableMap<String, MutableUnit>
        get() = steps.current.units
        set(value) { steps.current.units = value }
    private var projectiles: MutableList<MutableProjectile>
        get() = steps.current.projectiles
        set(value) { steps.current.projectiles = value }
    private var combatFrame: Int
        get() = steps.current.combatFrame
        set(value) { steps.current.combatFrame = value }
    private var nextObjectId: Long
        get() = steps.current.nextObjectId
        set(value) { steps.current.nextObjectId = value }
    private var lastBirthSerial: Long
        get() = steps.current.lastBirthSerial
        set(value) { steps.current.lastBirthSerial = value }
    private var buildingWork: BuildingWork
        get() = steps.current.buildingWork
        set(value) { steps.current.buildingWork = value }
    private var productionLedger: ProductionLedger?
        get() = steps.current.productionLedger
        set(value) { steps.current.productionLedger = value }
    private var ordinaryUpgrades: OrdinaryUpgrades?
        get() = steps.current.ordinaryUpgrades
        set(value) { steps.current.ordinaryUpgrades = value }
    private var missileTargets: MissileTargetIndex?
        get() = steps.current.missileTargets
        set(value) { steps.current.missileTargets = value }
    private var buildingTerrain: BuildingTerrain?
        get() = steps.current.buildingTerrain
        set(value) { steps.current.buildingTerrain = value }
    private var buildingKnowledge: Map<String, BuildingKnowledge>
        get() = steps.current.buildingKnowledge
        set(value) { steps.current.buildingKnowledge = value }
    private var pendingBuildingDiscoveries: Map<String, Map<Pair<Int, Int>, Long>>
        get() = steps.current.pendingBuildingDiscoveries
        set(value) { steps.current.pendingBuildingDiscoveries = value }
    private var worldGeneration: Long = 0
    private var closed: Boolean = true
    private var stepIndex: Long
        get() = steps.current.stepIndex
        set(value) { steps.current.stepIndex = value }
    private var simulationTime: Double
        get() = steps.current.simulationTime
        set(value) { steps.current.simulationTime = value }
    private var hasAdvanced: Boolean
        get() = steps.current.hasAdvanced
        set(value) { steps.current.hasAdvanced = value }
    private var revision: Long
        get() = steps.current.revision
        set(value) { steps.current.revision = value }
    private var pendingEvents: MutableList<Event>
        get() = steps.current.pendingEvents
        set(value) { steps.current.pendingEvents = value }
    private var pendingProjectileEvents: MutableList<ProjectileEventObservation>
        get() = steps.current.pendingProjectileEvents
        set(value) { steps.current.pendingProjectileEvents = value }
    private var pendingActionResults: MutableList<ActionResult>
        get() = steps.current.pendingActionResults
        set(value) { steps.current.pendingActionResults = value }
    private var latestObservation: Observation?
        get() = steps.current.latestObservation
        set(value) { steps.current.latestObservation = value }
    private var committedObservation: Observation?
        get() = steps.current.committedObservation
        set(value) { steps.current.committedObservation = value }
    private val scheduledActions: MutableList<Action>
        get() = steps.current.scheduledActions
    private val actionIds = mutableSetOf<String>()
    private var actionResultOwners: IdentityHashMap<ActionResult, String>
        get() = steps.current.actionResultOwners
        set(value) { steps.current.actionResultOwners = value }
    private val playerActionRevisions: MutableMap<String, Long>
        get() = steps.current.playerActionRevisions
    private val playerObservationProjectors = mutableMapOf<String, PlayerObservationProjector>()
    private var noFogPlanning: CachedPlanningTerrain?
        get() = steps.current.noFogPlanning
        set(value) { steps.current.noFogPlanning = value }
    private val playerPlanning: MutableMap<String, CachedPlanningTerrain>
        get() = steps.current.playerPlanning
    private var knowledgeStores: Map<String, PlayerTerrainKnowledge>?
        get() = steps.current.knowledgeStores
        set(value) { steps.current.knowledgeStores = value }
    /** Immutable rules captured on the same successful commit boundary as [knowledgeStores]. */
    private var knowledgeRules: KnowledgeRules? = null
    private var automaticVisionSnapshots: Map<String, VisionSnapshot>?
        get() = steps.current.automaticVisionSnapshots
        set(value) { steps.current.automaticVisionSnapshots = value }
    private var pathDiagnostics: MutableList<PathSearchSample>?
        get() = steps.current.pathDiagnostics
        set(value) { steps.current.pathDiagnostics = value }
    private var motionDiagnostics: MutableList<MotionStepSample>?
        get() = steps.current.motionDiagnostics
        set(value) { steps.current.motionDiagnostics = value }
    private val pendingKnowledgeDiscoveries: MutableMap<String, MutableMap<Pair<Int, Int>, Long>>
        get() = steps.current.pendingKnowledgeDiscoveries

    // Initial IDs precede the reserved production keys; births append in creation order.
    // Public observation sorting is separate from deterministic gameplay traversal.
    private fun orderedUnits(): Collection<MutableUnit> = units.values

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
        val groundTypes = input.unitDefinitions.values.flatMap { if (it.amphibiousJet != null) listOf(it.movement.type, MovementType.WATER) else listOf(it.movement.type) }
            .filter { it != MovementType.LAND && it != MovementType.AIR }.distinct()
        val vision = AutomaticVision.project(steps.current.automaticVisionState, world.id, (automaticVisionSnapshots?.values?.firstOrNull()?.epoch ?: -1L) + 1,
            input.map.widthTiles, input.map.heightTiles, input.map.tileWidth, input.map.tileHeight, input.players,
            units.values.filter { it.alive && it.carriedBy == null }.map { VisionSource(it.ownerPlayerId, it.position, it.definition.fogOfWarSightRange) } +
                steps.current.temporaryVision.map { VisionSource(it.owner, it.position, 15) }, rules.shareAlliedVision)
        val views = vision.views
        val nextStores = linkedMapOf<String, PlayerTerrainKnowledge>()
        val discoveries = linkedMapOf<String, MutableMap<Pair<Int, Int>, Long>>()
        try {
            for ((player, view) in views) {
                val old = oldStores.getValue(player)
                val next = old.projected(world, view.effectiveCells.map {
                    VisibleTerrainCell(it.column, it.row, input.map.blocking.isBlocked(it.column, it.row),
                        groundGids[it.row * input.map.widthTiles + it.column])
                })
                nextStores[player] = next.store
                val after = next.store.snapshot(world)
                for (change in next.changes) {
                    if (change.after.visible && ((change.before.lastKnownBlocked != true && change.after.lastKnownBlocked == true) ||
                            groundTypes.any { type -> !change.before.movementBlocked(type, input.map.tileSets) &&
                                change.after.movementBlocked(type, input.map.tileSets) })) {
                        discoveries.getOrPut(player) { linkedMapOf() }[change.index % input.map.widthTiles to change.index / input.map.widthTiles] = after.revision
                    }
                }
            }
            return AutomaticKnowledgeCommit(nextStores, views, discoveries, vision.state)
        } catch (failure: Exception) {
            nextStores.values.forEach { it.close() }
            throw failure
        }
    }

    private fun commitAutomaticKnowledge(next: AutomaticKnowledgeCommit) {
        knowledgeStores?.values?.forEach { it.close() }
        knowledgeStores = next.stores
        automaticVisionSnapshots = next.views
        steps.current.automaticVisionState = next.visionState
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
        private const val CONSTRUCTED_UNIT_PREFIX = "constructed:"
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
        val input = checkNotNull(scenario)
        val groundTypes = input.unitDefinitions.values.flatMap { if (it.amphibiousJet != null) listOf(it.movement.type, MovementType.WATER) else listOf(it.movement.type) }
            .filter { it != MovementType.LAND && it != MovementType.AIR }.distinct()
        for (row in 0 until after.height) for (column in 0 until after.width) {
            if ((!before.planningBlocked(column, row) && after.planningBlocked(column, row)) ||
                groundTypes.any { type -> !before.movementBlocked(column, row, type, input.map.tileSets) &&
                    after.movementBlocked(column, row, type, input.map.tileSets) }) {
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
        steps.current.automaticVisionState = null
        pendingKnowledgeDiscoveries.clear()
        pathDiagnostics?.clear()
        pathDiagnostics = null
        motionDiagnostics?.clear()
        motionDiagnostics = null
        activeWorld = null
        this.scenario = null
        productionLedger = null
        missileTargets = null
        ordinaryUpgrades = null
        lastBirthSerial = 0L
        buildingWork = BuildingWork.initial()
        buildingTerrain = null
        buildingKnowledge = emptyMap()
        pendingBuildingDiscoveries = emptyMap()
        units = linkedMapOf()
        projectiles.clear()
        steps.current.temporaryVision = emptyList()
        pendingEvents.clear()
        pendingProjectileEvents.clear()
        steps.current.pendingSoundTriggers.clear()
        pendingActionResults.clear()
        latestObservation = null
        committedObservation = null
        scheduledActions.clear()
        actionIds.clear()
        actionResultOwners.clear()
        playerActionRevisions.clear()
        playerObservationProjectors.clear()
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
        if (unit.carriedBy != null) return reject(action, "unit-unavailable", "Unit is not available")
        if ((action is MoveAction || action is PatrolAction) && unit.definition.movement.type == MovementType.NONE) {
            return reject(action, "immobile-unit", "Unit cannot move")
        }
        if (action is MoveAction && (!action.target.x.isFinite() || !action.target.y.isFinite())) {
            return reject(action, "invalid-target", "Target coordinates must be finite")
        }
        if (action is PatrolAction && (!action.target.x.isFinite() || !action.target.y.isFinite())) {
            return reject(action, "invalid-target", "Target coordinates must be finite")
        }
        if (action is AttackAction) validateAttack(action, unit)?.let {
            return reject(action, it.code, it.message)
        }
        BuildingWork.validateEnvelope(action)?.let { return reject(action, it.code, it.message) }
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
    private fun constructionNamespaceEnabled(input: Scenario): Boolean = input.unitDefinitions.values.any {
        it.builder != null || it.maintenance != null }

    private fun resolveCommandActor(action: Action, actor: Player): CommandActor {
        fun denied(code: String, message: String) = CommandActor.Denied(SimulationError(code, message))
        fun unavailable() = denied("unit-unavailable", "Unit is not available")
        val input = checkNotNull(scenario)
        val prefix = when {
            productionNamespaceEnabled(input) && action.unitId.startsWith(PRODUCED_UNIT_PREFIX) -> PRODUCED_UNIT_PREFIX
            constructionNamespaceEnabled(input) && action.unitId.startsWith(CONSTRUCTED_UNIT_PREFIX) -> CONSTRUCTED_UNIT_PREFIX
            else -> null
        }
        if (prefix != null) {
            // The fixed 32-hex suffix delimits owner IDs even when the owner itself contains ':'.
            val separator = action.unitId.length - 33
            if (separator <= prefix.length || action.unitId[separator] != ':') return unavailable()
            val nonce = action.unitId.substring(separator + 1)
            if (nonce.any { it !in '0'..'9' && it !in 'a'..'f' }) return unavailable()
            val owner = action.unitId.substring(prefix.length, separator)
            if (input.players.none { it.playerId == owner }) return unavailable()
            if (action is SelectAction) {
                val unit = orderedUnits().firstOrNull {
                    it.unitId == action.unitId && isVisibleTo(actor.playerId, it.position, it.nativeTransportPlacement)
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
        if (action is SelectAction && !isVisibleTo(actor.playerId, unit.position, unit.nativeTransportPlacement))
            return denied("not-visible", "Unit is outside the player's current vision")
        return CommandActor.Available(unit)
    }

    /** Ground follows the configured source; dynamic buildings always follow owner visibility. */
    private fun planningTerrainFor(unit: MutableUnit, map: MapDefinition): PlanningTerrain {
        val movementType = UnitLocomotion.movement(unit).type
        if (UnitLocomotion.movement(unit).type == MovementType.AIR)
            return PlanningTerrain(map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight,
                BooleanArray(map.widthTiles * map.heightTiles))
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
            if (cached != null && cached.world === activeWorld && cached.map === map && cached.movementType == movementType) return cached.terrain
            return PlanningTerrain.fromNoFog(map, movementType).also {
                noFogPlanning = CachedPlanningTerrain(world, map, null, it, movementType = movementType)
            }
        }
        val snapshot = if (usesKnowledge) checkNotNull(stores?.get(unit.ownerPlayerId)).snapshot(world) else null
        val cached = playerPlanning[unit.ownerPlayerId]
        if (cached != null && cached.world === activeWorld && cached.map === map && cached.knowledge === snapshot &&
            cached.buildings === ownerBuildings && cached.escapingBuildings == escaping && cached.movementType == movementType) return cached.terrain
        val ground = if (snapshot == null) PlanningTerrain.fromNoFog(map, movementType)
            else PlanningTerrain.fromKnowledge(snapshot, map.tileWidth, map.tileHeight, movementType, map.tileSets)
        return (if (escaping) ground else ground.withBuildings(ownerBuildings ?: emptySet())).also {
            playerPlanning[unit.ownerPlayerId] = CachedPlanningTerrain(world, map, snapshot, it, ownerBuildings, escaping, movementType)
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
            val path = policies.path.findPath(terrain, Transport.queryPosition(unit, map, start), target, Transport.boundaryRadius(unit), counters, targetRange)
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
            if (unit.carriedBy != null) continue
            if (UnitLocomotion.movement(unit).type == MovementType.AIR) continue
            if (unit.birthRemaining > 0f) continue
            val target = unit.moveTarget ?: continue
            val snapshot = knowledgeStores?.get(unit.ownerPlayerId)?.snapshot(world)
            val ground = pendingKnowledgeDiscoveries[unit.ownerPlayerId].orEmpty()
            val buildings = pendingBuildingDiscoveries[unit.ownerPlayerId].orEmpty()
            val affectedGround = ground.any { (cell, discoveredAt) ->
                knowledgeRules?.pathSource == PathInformationSource.PLAYER_KNOWLEDGE &&
                    discoveredAt > unit.lastPlannedKnowledgeRevision && checkNotNull(snapshot)
                        .movementBlocked(cell.first, cell.second, UnitLocomotion.movement(unit).type, map.tileSets) &&
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
                if (!PlanningSegmentClearance.allows(terrain, Transport.queryPosition(unit, map, from), point, Transport.boundaryRadius(unit))) break
                prefix += point
                from = point
            }
            fun search(start: Position, preserved: Int = 0): List<Position>? =
                if (samples == null) policies.path.findPath(terrain, Transport.queryPosition(unit, map, start), target, Transport.boundaryRadius(unit))
                else findPathWithDiagnostics(checkNotNull(unit.originMoveId), unit.originAtStep, target, unit, map, samples,
                    PathSearchReason.KNOWLEDGE_REPLAN, executionStep, start, preserved)
            val path = if (prefix.isNotEmpty() && prefix.size < oldPath.size) {
                val suffix = search(prefix.last(), prefix.size)
                if (suffix == null) search(unit.position)
                else prefix + if (suffix.firstOrNull() == prefix.last()) suffix.drop(1) else suffix
            } else search(unit.position)
            if (path == null) {
                if (unit.tasks.firstOrNull()?.kind == UnitTaskKind.BUILD) unit.pauseRoute()
                else unit.cancelRoute()
                pendingEvents += Event("movement-cancelled", unit.unitId)
                pendingEvents += Event("path-unreachable", unit.unitId)
            } else {
                unit.adoptRoute(path, logicalRouteTarget(unit, checkNotNull(unit.moveTarget)), plannedKnowledgeRevision(unit), plannedBuildingRevision(unit))
            }
        }
    }

    private fun repairInvalidConnections(map: MapDefinition, executionStep: Long) {
        for (unit in orderedUnits()) {
            if (unit.carriedBy != null) continue
            if (unit.amphibiousState?.terrainPausedMove == true && UnitLocomotion.movement(unit).type == MovementType.AIR) {
                val task = unit.tasks.firstOrNull()?.takeIf { it.kind == UnitTaskKind.MOVE }
                if (unit.alive && unit.moveTarget == null && task?.target != null) {
                    unit.moveTarget = task.target
                    unit.originMoveId = task.actionId
                    unit.originAtStep = task.atStep
                    resumeProductionRoute(unit, map, executionStep)
                }
                unit.amphibiousState = checkNotNull(unit.amphibiousState).copy(terrainPausedMove = false)
            }
            if (UnitLocomotion.movement(unit).type == MovementType.AIR) continue
            if (unit.birthRemaining > 0f) continue
            val target = unit.moveTarget ?: continue
            val first = unit.pathPoints.firstOrNull() ?: continue
            val terrain = planningTerrainFor(unit, map)
            val local = unit.ensureLocalMotion()
            val firstConnectionClear = PlanningSegmentClearance.allows(terrain, Transport.queryPosition(unit, map), first, Transport.boundaryRadius(unit))
            val localConnectionClear = PlanningSegmentClearance.allows(terrain, Transport.queryPosition(unit, map), local.aim, Transport.boundaryRadius(unit))
            val nextLocal = local.next()
            val localCornerStall = nextLocal != null &&
                policies.coordinates.distanceSquared(unit.position, local.aim) < 9.0 &&
                !PlanningSegmentClearance.allows(terrain, Transport.queryPosition(unit, map), nextLocal.aim, Transport.boundaryRadius(unit))
            val nearCornerStall = unit.pathPoints.size > 1 &&
                policies.coordinates.distanceSquared(unit.position, first) < 9.0 &&
                !PlanningSegmentClearance.allows(terrain, Transport.queryPosition(unit, map), unit.pathPoints[1], Transport.boundaryRadius(unit))
            if (firstConnectionClear && localConnectionClear && !nearCornerStall && !localCornerStall) continue
            if (unit.attackCommand != null) unit.lastAttackPathAttemptStep = executionStep
            val samples = pathDiagnostics
            fun search(destination: Position): List<Position>? =
                if (samples == null) policies.path.findPath(terrain, Transport.queryPosition(unit, map), destination, Transport.boundaryRadius(unit))
                else findPathWithDiagnostics(checkNotNull(unit.originMoveId), unit.originAtStep, destination, unit, map, samples,
                    PathSearchReason.ROUTE_REPAIR, executionStep)
            val oldPath = unit.pathPoints.toList()
            val tailIsClear = oldPath.size > 1 && oldPath.zipWithNext().all { (a, b) ->
                PlanningSegmentClearance.allows(terrain, a, b, Transport.boundaryRadius(unit))
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
                if (unit.tasks.firstOrNull()?.kind == UnitTaskKind.BUILD) unit.pauseRoute()
                else unit.cancelRoute()
                unit.amphibiousState = unit.amphibiousState?.copy(terrainPausedMove =
                    unit.tasks.firstOrNull()?.kind == UnitTaskKind.MOVE)
                pendingEvents += Event("movement-cancelled", unit.unitId)
                pendingEvents += Event("path-unreachable", unit.unitId)
            } else {
                unit.adoptRoute(path, logicalRouteTarget(unit, checkNotNull(unit.moveTarget)), plannedKnowledgeRevision(unit), plannedBuildingRevision(unit))
            }
        }
    }

    private fun validateAttack(action: AttackAction, attacker: MutableUnit): SimulationError? {
        if (attacker.definition.combat == null) return SimulationError("cannot-attack", "Unit cannot attack")
        val input = checkNotNull(scenario)
        val team = input.players.single { it.playerId == attacker.ownerPlayerId }.teamId
        val target = orderedUnits().asSequence().filter {
            it.alive && isVisibleTo(action.actorPlayerId, it.position, it.nativeTransportPlacement)
        }.firstOrNull { it.unitId == action.targetUnitId }
        val enemy = target != null && target.ownerPlayerId != attacker.ownerPlayerId &&
            (team == null || input.players.single { it.playerId == target.ownerPlayerId }.teamId != team)
        return if (target == null || !enemy || target.health == null || !CombatTargetQuery.canAttackAirCategory(attacker, target, input.map)) {
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
        if (unit.carriedBy != null) return reject(action, "unit-unavailable", "Unit is not available")
        BuildingWork.validateEnvelope(action)?.let { return reject(action, it.code, it.message) }
        val constructionQueueFull = constructionAppend(action) == true && unit.taskQueue.size == 29
        if (constructionAppend(action) == false) {
            // A safe, authorized ordinary order clears first, even when its later capability check rejects it.
            // Scheduled orders arrive here only at execution; reservation never clears the current activity.
            unit.taskQueue = UnitTaskQueue()
            unit.attackCommand = null
            unit.bomberApproach = null
            unit.cancelRoute()
            unit.movementRequested = false
            unit.constructionElapsed = 0f
            unit.constructionWorking = false
        }
        if ((action is MoveAction || action is PatrolAction) && unit.definition.movement.type == MovementType.NONE) {
            return reject(action, "immobile-unit", "Unit cannot move")
        }
        val result = when (action) {
            is UnitAction -> {
                val error = if (unit.definition.nuclearSilo != null) {
                    val change = NuclearOrdnance.request(unit, action.actionName, action.cancelQueued, checkNotNull(productionLedger), action.target)
                    productionLedger = change.ledger
                    change.error
                } else withUnitActionQueue(unit) {
                    val change = CustomUnitActions.request(unit, action, steps.current.actionClock, currentScenario,
                        buildingTerrain?.collisionMap ?: currentScenario.map, productionLedger)
                    productionLedger = change.ledger
                    change.error
                }
                error?.let {
                    return reject(action, it.code, it.message)
                }
                ActionResult.Accepted(action.actionId, listOf(Event(if (action.cancelQueued) "unit-action-cancelled" else "unit-action-queued", unit.unitId)))
            }
            is UnloadAction -> {
                if (!BuildingWork.isReady(unit)) return reject(action, "inactive-unit", "Unfinished units cannot unload")
                Transport.requestUnload(unit, action.unloading, currentScenario.map)?.let { return reject(action, it.code, it.message) }
                ActionResult.Accepted(action.actionId, listOf(Event(if (action.unloading) "unload-started" else "unload-cancelled", unit.unitId)))
            }
            is LoadAction -> {
                val target = units[action.targetUnitId]?.takeIf { it.carriedBy == null && !it.reclaimed &&
                    isVisibleTo(action.actorPlayerId, it.position, it.nativeTransportPlacement) }
                    ?: return reject(action, "target-unavailable", "Loading target is not available")
                val change = Transport.request(action, unit, target, units)
                change.error?.let { return reject(action, it.code, it.message) }
                ActionResult.Accepted(action.actionId, listOfNotNull(change.event))
            }
            is AmphibiousFlightAction -> {
                val state = unit.amphibiousState
                    ?: return reject(action, "unsupported-amphibious-action", "Unit has no native amphibious flight capability")
                if (!BuildingWork.isReady(unit)) return reject(action, "inactive-unit", "Unfinished units cannot change flight intent")
                if (state.flyingIntent == action.flying)
                    return reject(action, "amphibious-state-unchanged", "Requested flight intent is already active")
                if (!action.flying && !AmphibiousJetMotion.overWater(unit, currentScenario.map))
                    return reject(action, "dive-requires-water", "Diving requires the current tile to be water")
                unit.amphibiousState = state.copy(flyingIntent = action.flying)
                ActionResult.Accepted(action.actionId, listOf(Event(
                    if (action.flying) "amphibious-fly-requested" else "amphibious-dive-requested", unit.unitId)))
            }
            is SubmarineDepthAction -> {
                if (unit.definition.submarine == null)
                    return reject(action, "unsupported-submarine-action", "Unit has no submarine depth capability")
                if (!BuildingWork.isReady(unit))
                    return reject(action, "inactive-unit", "Unfinished units cannot change depth intent")
                if (unit.submarineSurfaceIntent == action.surface)
                    return reject(action, "submarine-state-unchanged", "Requested depth intent is already active")
                unit.submarineSurfaceIntent = action.surface
                ActionResult.Accepted(action.actionId, listOf(Event(
                    if (action.surface) "submarine-surface-requested" else "submarine-dive-requested", unit.unitId)))
            }
            is RepairUnitAction, is ReclaimUnitAction, is ResumeConstructionAction -> {
                buildingWork.requestTarget(action, unit, currentScenario, units,
                    automaticVisionSnapshots?.getValue(unit.ownerPlayerId))?.let { return reject(action, it.code, it.message) }
                ActionResult.Accepted(action.actionId)
            }
            is BuildAction -> {
                buildingWork.request(action, unit, currentScenario)?.let { return reject(action, it.code, it.message) }
                ActionResult.Accepted(action.actionId)
            }
            is PatrolAction -> {
                if (!action.target.x.isFinite() || !action.target.y.isFinite())
                    return reject(action, "invalid-target", "Target coordinates must be finite")
                val task = UnitTask(UnitTaskKind.PATROL, action.actionId, action.target, atStep = action.atStep)
                if (!action.append || unit.tasks.isEmpty()) {
                    unit.attackCommand = null
                    unit.bomberApproach = null
                    unit.cancelRoute()
                }
                unit.taskQueue = (if (action.append) unit.taskQueue else UnitTaskQueue()).append(task)
                ActionResult.Accepted(action.actionId)
            }
            is UpgradeAction -> when (val change = applyUpgrade(action, unit, action.upgradeId, false)) {
                is ActionResult.Accepted -> change
                is ActionResult.Rejected -> return change
            }
            is CancelUpgradeAction -> when (val change = applyUpgrade(action, unit, action.upgradeId, true)) {
                is ActionResult.Accepted -> change
                is ActionResult.Rejected -> return change
            }
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
                unit.taskQueue = UnitTaskQueue().append(UnitTask(UnitTaskKind.ATTACK, action.actionId,
                    targetUnitId = action.targetUnitId, atStep = action.atStep))
                unit.attackCommand = ManualAttackCommand(action.targetUnitId, action.actionId, action.atStep)
                unit.bomberApproach = null
                unit.lastAttackPathAttemptStep = null
                ActionResult.Accepted(action.actionId, listOf(Event("attack-started", unit.unitId)))
            }
            is SelectAction -> {
                if (!isVisibleTo(action.actorPlayerId, unit.position, unit.nativeTransportPlacement)) return reject(action, "not-visible", "Unit is outside the player's current vision")
                ActionResult.Accepted(action.actionId)
            }
            is StopAction -> {
                unit.attackCommand = null
                unit.bomberApproach = null
                unit.taskQueue = UnitTaskQueue()
                unit.movementRequested = false
                unit.clearWeaponTargets()
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
                            action.target, Transport.boundaryRadius(unit))) listOf(action.target) else null
                } else if (samples == null) {
                    policies.path.findPath(planningTerrainFor(unit, currentScenario.map), Transport.queryPosition(unit, currentScenario.map), action.target, Transport.boundaryRadius(unit))
                } else {
                    findPathWithDiagnostics(action.actionId, action.atStep, action.target, unit, currentScenario.map, samples,
                        executionStep = executionStep)
                }
                unit.attackCommand = null
                unit.bomberApproach = null
                if (path == null) {
                    unit.taskQueue = UnitTaskQueue()
                    unit.cancelRoute()
                    ActionResult.Accepted(
                        action.actionId,
                        listOf(Event("movement-cancelled", unit.unitId), Event("path-unreachable", unit.unitId)),
                    )
                } else {
                    unit.taskQueue = UnitTaskQueue().append(UnitTask(UnitTaskKind.MOVE, action.actionId, action.target,
                        atStep = action.atStep))
                    unit.moveTarget = action.target
                    unit.nearTargetTime = 0.0
                    unit.originMoveId = action.actionId
                    unit.originAtStep = action.atStep
                    unit.adoptRoute(path, logicalRouteTarget(unit, checkNotNull(unit.moveTarget)), plannedKnowledgeRevision(unit), plannedBuildingRevision(unit))
                    ActionResult.Accepted(action.actionId, listOf(Event("movement-started", unit.unitId)))
                }
            }
        }
        if (action is StopAction || action is MoveAction || action is AttackAction ||
            action is PatrolAction && !action.append) {
            unit.constructionElapsed = 0f
            unit.constructionWorking = false
        }
        val publishedResult = if (constructionQueueFull && result is ActionResult.Accepted)
            ActionResult.Accepted(result.actionId, result.events + Event("construction-queue-full", unit.unitId), result.queuedForStep) else result
        pendingActionResults += publishedResult
        recordActionOwner(action, publishedResult)
        pendingEvents += publishedResult.events
        invalidateObservation()
        return publishedResult
    }

    private fun constructionAppend(action: Action): Boolean? = when (action) {
        is BuildAction -> action.append
        is RepairUnitAction -> action.append
        is ReclaimUnitAction -> action.append
        is ResumeConstructionAction -> action.append
        else -> null
    }

    private val buildingWorkAccess = object : BuildingWorkAccess {
        override fun path(worker: MutableUnit, target: Position, range: Double, task: UnitTask, step: Long): BuildingRoute? {
            val map = checkNotNull(scenario).map
            val samples = pathDiagnostics
            val path = if (worker.birthRemaining > 0f) listOf(target)
                else if (samples == null) policies.path.findPath(planningTerrainFor(worker, map), Transport.queryPosition(worker, map), target,
                    Transport.boundaryRadius(worker), targetRange = range)
                else findPathWithDiagnostics(task.actionId, task.atStep, target, worker, map, samples,
                    reason = PathSearchReason.CONSTRUCTION_APPROACH, executionStep = step, targetRange = range)
            return path?.let { BuildingRoute(it, plannedKnowledgeRevision(worker), plannedBuildingRevision(worker)) }
        }
        override fun construct(ownerPlayerId: String, typeId: String, position: Position, nonce: String,
                               paidLedger: ProductionLedger): ConstructionRegistration =
            UnitRegistration.construction(steps.current, checkNotNull(scenario), ownerPlayerId, typeId, position, nonce, paidLedger)
    }

    private fun updateConstruction(worker: MutableUnit, input: Scenario, step: Step) {
        val frame = BuildingWorkFrame(input, units, step, automaticVisionSnapshots?.getValue(worker.ownerPlayerId),
            policies.coordinates, policies.steering, buildingWorkAccess)
        val change = buildingWork.advanceActivity(worker, frame, checkNotNull(productionLedger), ordinaryUpgrades)
        buildingWork = change.work
        productionLedger = change.ledger
        ordinaryUpgrades = change.upgrades
        change.reclaimedUnitId?.let { missileTargets = missileTargets?.remove(it) }
        if (change.reclaimedUnitId != null)
            buildingTerrain = BuildingTerrain.fromLive(input.map, units.values, checkNotNull(buildingTerrain))
        pendingEvents += change.events
    }

    private fun logicalRouteTarget(unit: MutableUnit, fallback: Position): Position {
        val task = unit.tasks.firstOrNull() ?: return fallback
        return when (task.kind) {
            UnitTaskKind.BUILD -> task.target ?: fallback
            UnitTaskKind.CONSTRUCT, UnitTaskKind.RECLAIM -> units[task.targetUnitId]?.position ?: fallback
            UnitTaskKind.ATTACK -> unit.attackCommand?.targetUnitId?.let { units[it]?.position } ?: fallback
            UnitTaskKind.PATROL -> unit.primaryCombatTargetId?.let { units[it]?.position } ?: task.target ?: fallback
            else -> fallback
        }
    }

    private fun applyUpgrade(action: Action, unit: MutableUnit, upgradeId: String, cancel: Boolean): ActionResult {
        if (!cancel && unit.definition.production != null && !BuildingWork.isReady(unit))
            return reject(action, "factory-under-construction", "The factory must finish construction before accepting an upgrade")
        val upgrades = ordinaryUpgrades ?: return reject(action, "cannot-upgrade", "Unit cannot upgrade")
        return when (val result = upgrades.request(unit, upgradeId, cancel, checkNotNull(productionLedger))) {
            is OrdinaryUpgradeRequest.Rejected -> reject(action, result.error.code, result.error.message)
            is OrdinaryUpgradeRequest.Accepted -> ActionResult.Accepted(action.actionId, applyUpgradeChange(result.change))
        }
    }

    private fun applyUpgradeChange(change: OrdinaryUpgradeChange): List<Event> {
        ordinaryUpgrades = change.upgrades
        productionLedger = change.ledger
        return change.events
    }

    private fun advanceUpgrade(unit: MutableUnit) {
        val upgrades = ordinaryUpgrades ?: return
        pendingEvents += applyUpgradeChange(upgrades.advance(unit, checkNotNull(productionLedger)))
    }

    private fun refundUpgrades(unit: MutableUnit) {
        val upgrades = ordinaryUpgrades ?: return
        pendingEvents += applyUpgradeChange(upgrades.discard(unit, checkNotNull(productionLedger)))
    }

    private fun applyProduction(action: Action, unit: MutableUnit, typeId: String, nonce: String?): ActionResult {
        if (unit.definition.production == null) return reject(action, "cannot-produce", "Unit cannot produce")
        val exitProfile = unit.definition.production?.exitProfile
        if ((unit.definition.className == "AuthoredBuilding" || exitProfile in setOf(com.rustedwarfare.reforge.core.content.FactoryExitProfile.CUSTOM_MECH,
                com.rustedwarfare.reforge.core.content.FactoryExitProfile.NATIVE_AIR,
                com.rustedwarfare.reforge.core.content.FactoryExitProfile.NATIVE_SEA)) && !BuildingWork.isReady(unit))
            return reject(action, "factory-under-construction", if (exitProfile == com.rustedwarfare.reforge.core.content.FactoryExitProfile.NATIVE_AIR)
                "The air factory must finish construction before accepting production"
                else "The factory must finish construction before accepting production")
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
        val tick = checkNotNull(productionLedger).advance(factory.unitId,
            checkNotNull(factory.definition.production).speedMultiplier, Transport.allowsProductionCompletion(factory, units))
        productionLedger = tick.ledger
        tick.completed?.let { completed ->
            UnitRegistration.produced(steps.current, input, factory, completed)
        }
    }

    private fun unitActionQueue(unit: MutableUnit): List<PendingUnitAction> =
        if (unit.definition.production != null) checkNotNull(productionLedger).actionQueue(unit.unitId)
        else unit.customActionState.queue

    private fun unitActionHead(unit: MutableUnit): PendingUnitAction? =
        if (unit.definition.production != null) checkNotNull(productionLedger).actionAtHead(unit.unitId)
        else unit.customActionState.queue.firstOrNull()

    /** Only the factory ledger persists work order; the action executor borrows its projection inside this candidate. */
    private inline fun <T> withUnitActionQueue(unit: MutableUnit, operation: () -> T): T {
        if (unit.definition.production == null) return operation()
        check(unit.customActionState.queue.isEmpty())
        unit.customActionState = unit.customActionState.copy(queue = unitActionQueue(unit))
        val result = operation()
        productionLedger = checkNotNull(productionLedger).replaceActions(unit.unitId, unit.customActionState.queue)
        unit.customActionState = unit.customActionState.copy(queue = emptyList())
        return result
    }

    internal fun isCurrentSandboxWorld(handle: WorldHandle): Boolean = !closed && activeWorld === handle

    internal fun validateSandboxSpawn(command: SandboxCommand.Spawn, generation: Long, sequence: Long): SimulationError? {
        val input = checkNotNull(scenario)
        fun rejected(code: String) = SimulationError(code, "Sandbox spawn rejected: $code")
        val definition = input.unitDefinitions[command.typeId] ?: return rejected("unknown-type")
        if (UnitRegistration.sandboxId(command.ownerPlayerId, generation, sequence) in units)
            return rejected("duplicate-unit-id")
        if (input.players.none { it.playerId == command.ownerPlayerId }) return rejected("unknown-owner")
        if (command.state == SandboxInitialState.DAMAGED && definition.maximumHealth == null)
            return rejected("unsupported-state")
        if (definition.production != null && productionLedger == null) return rejected("unsupported-type")
        if (!command.heading.isFinite() || !MapBounds.contains(input.map.widthTiles.toDouble() * input.map.tileWidth,
                input.map.heightTiles.toDouble() * input.map.tileHeight, command.position, definition.radius))
            return rejected("invalid-placement")
        if (definition.building?.requiresResourcePool != true && definition.movement.type != MovementType.AIR &&
            policies.terrain.intersects(buildingTerrain?.collisionMap ?: input.map, command.position,
                definition.radius, definition.foundationMovementType)) return rejected("invalid-placement")
        if (definition.building != null) {
            val cells = try {
                BuildingTerrain.placementCells(input.map, definition, command.position, buildingTerrain?.cells.orEmpty())
            } catch (failure: BuildingInitializationException) { return rejected("invalid-placement") }
            if (orderedUnits().any { unit ->
                    unit.alive && unit.definition.building == null && UnitLocomotion.movement(unit).type != MovementType.AIR &&
                        (MapBounds.cell(unit.position.x, input.map.tileWidth, input.map.widthTiles) to
                            MapBounds.cell(unit.position.y, input.map.tileHeight, input.map.heightTiles)) in cells
                }) return rejected("invalid-placement")
        }
        return null
    }

    internal fun advanceSandbox(step: Step, commands: List<Pair<SandboxCommandRequest, SandboxCommand.Spawn>>):
            Pair<Observation, List<SandboxCommandReceipt>> {
        val results = mutableListOf<SandboxCommandReceipt>()
        val observation = advanceInternal(step) {
            for ((request, command) in commands) {
                val error = validateSandboxSpawn(command, request.generation, request.sequence)
                if (error != null) {
                    results += SandboxCommandReceipt(request.sequence, request.generation, world.id,
                        SandboxCommandStatus.REJECTED, command, step.stepIndex, code = error.code)
                    continue
                }
                val born = UnitRegistration.sandbox(steps.current, checkNotNull(scenario), command,
                    request.generation, request.sequence, policies.coordinates)
                pendingEvents += Event("unit-spawned", born.unitId)
                results += SandboxCommandReceipt(request.sequence, request.generation, world.id,
                    SandboxCommandStatus.APPLIED, command, step.stepIndex, born.unitId)
            }
        }
        return observation to results
    }

    fun advance(step: Step): Observation = advanceInternal(step) {}

    private fun advanceInternal(step: Step, deploy: () -> Unit): Observation {
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
        return steps.advance(currentScenario, step, actions,
            { index, time, nextRevision -> makeObservation(index, time, true, nextRevision) }) { candidate ->
            combatFrame += 1
            steps.current.actionClock = (steps.current.actionClock.toFloat() + step.delta.toFloat() * 16.666666f).toInt()
            deploy()
            buildingWork = buildingWork.advanceClock(step.delta)
            actions.forEach { applyAction(it, step.stepIndex) }
            for (unit in orderedUnits()) if (unit.alive && unit.carriedBy == null && unit.tasks.firstOrNull()?.kind == UnitTaskKind.PATROL)
                retainedPatrolTarget(unit)
            replanForKnowledge(currentScenario.map, step.stepIndex)
            repairInvalidConnections(currentScenario.map, step.stepIndex)
            for (unit in orderedUnits().toList()) {
                if (unit.deathPending) {
                    unit.deathPending = false
                    processDeath(unit)
                }
                if (!unit.alive) {
                    AmphibiousJetMotion.advanceCorpse(unit, currentScenario.map, step.delta)
                    WaterBody.advanceCorpse(unit, currentScenario.map, step.delta)
                    HoverMotion.advanceCorpse(unit, currentScenario.map, step.delta)
                    Transport.advanceCorpse(unit, currentScenario.map, step.delta)
                    NautilusMotion.advanceCorpse(unit, units, currentScenario.map, step.delta)
                    AaBeamGunship.advanceCorpse(unit, currentScenario.map, step.delta)
                    continue
                }
                if (unit.carriedBy != null) continue
                if (unit.definition.energy?.dieOnZero == true && BuildingWork.isReady(unit)) {
                    WeaponEnergy.advance(unit, step.delta)
                    if (unit.energy <= 0f) {
                        unit.health = 0.0
                        processDeath(unit)
                        continue
                    }
                }
                unit.definition.shield?.let { profile ->
                    val restored = unit.shield + profile.regeneration * step.delta.toFloat()
                    require(restored.isFinite()) { "Shield regeneration must remain finite" }
                    unit.shield = restored.coerceIn(0f, profile.maximum)
                    val faded = unit.shieldDisplayDamage - unit.shieldDisplayDamage * .02f * step.delta.toFloat()
                    unit.shieldDisplayDamage = maxOf(0f, faded - 4f * step.delta.toFloat()).coerceAtMost(50f)
                }
                Bunker.advanceHeight(unit, step.delta)
                if (unit.combat == null) MechBody.advance(unit, step.delta)
                val previousRequest = unit.movementRequested
                unit.movementRequested = false
                val constructionActivity = (unit.definition.builder != null || unit.definition.maintenance != null) &&
                    unit.tasks.firstOrNull()?.kind in setOf(UnitTaskKind.BUILD, UnitTaskKind.CONSTRUCT, UnitTaskKind.RECLAIM)
                if (unit.birthRemaining > 0f) {
                    unit.birthRemaining = maxOf(0f, unit.birthRemaining - 1f)
                    if (unit.birthRemaining == 0f) {
                        if (unit.tasks.firstOrNull()?.kind in setOf(UnitTaskKind.PATROL, UnitTaskKind.BUILD, UnitTaskKind.CONSTRUCT, UnitTaskKind.RECLAIM)) unit.cancelRoute()
                        else resumeProductionRoute(unit, currentScenario.map, step.stepIndex)
                    }
                }
                val loading = Transport.advanceLoading(unit, units)
                loading?.event?.let { pendingEvents += it }
                loading?.approach?.let { target ->
                    unit.requestMovement(target)
                    val endpoint = unit.moveTarget
                    if (endpoint == null || ScreenPlane.distanceSquared(endpoint, target) >= 441.0 || unit.pathPoints.isEmpty()) {
                        val path = policies.path.findPath(planningTerrainFor(unit, currentScenario.map), Transport.queryPosition(unit, currentScenario.map), target,
                            Transport.boundaryRadius(unit), targetRange = 21.0)
                        if (path == null) unit.cancelRoute() else {
                            unit.moveTarget = path.last()
                            unit.originMoveId = unit.tasks.first().actionId
                            unit.originAtStep = unit.tasks.first().atStep
                            unit.adoptRoute(path, path.last(), plannedKnowledgeRevision(unit), plannedBuildingRevision(unit))
                        }
                    }
                }
                if (unit.carriedBy != null) continue
                unit.qBeforeDispatch = unit.singleNodePathCount
                unit.consumedPathNodeCounts = emptyList()
                if (unit.definition.builder != null || unit.definition.maintenance != null && BuildingWork.isReady(unit))
                    updateConstruction(unit, currentScenario, step)
                buildingWork.advanceAutomatic(unit, currentScenario, step.delta) {
                    checkNotNull(missileTargets).repairCandidates(unit.position.x.toFloat(), unit.position.y.toFloat(),
                        BuildingWork.automaticRange(unit), units)
                }
                val factoryWork = unit.definition.production != null
                val factoryActionAtHead = factoryWork && unitActionHead(unit) != null
                if (factoryWork && !factoryActionAtHead && BuildingWork.isReady(unit)) {
                    if (checkNotNull(productionLedger).upgradeAtHead(unit.unitId)) advanceUpgrade(unit)
                    else advanceProduction(unit, currentScenario)
                }
                val economicBuilding = unit.definition.income != null && unit.definition.construction != null
                if (economicBuilding && BuildingWork.isReady(unit)) advanceUpgrade(unit)
                unit.definition.income?.takeIf { BuildingWork.isReady(unit) }?.let { income ->
                    unit.incomeElapsed += step.delta.toFloat()
                    require(unit.incomeElapsed.isFinite()) { "Income elapsed time must remain finite" }
                    if (unit.incomeElapsed > income.period - .1f) {
                        unit.incomeElapsed -= income.period
                        productionLedger = checkNotNull(productionLedger).creditIncome(unit.ownerPlayerId, income.credits)
                    }
                }
                // A construction pop releases the next activity for the next update only.
                if (!constructionActivity) {
                    if (unit.tasks.firstOrNull()?.kind == UnitTaskKind.PATROL)
                        updatePatrolRoute(unit, currentScenario.map, step.stepIndex, previousRequest)
                    else updateAttackRoute(unit, currentScenario.map, step.stepIndex)
                }
                val fourLegged = unit.definition.mech?.kind == com.rustedwarfare.reforge.core.content.MechKind.FOUR_LEGGED
                if ((unit.definition.groundHeight != null || com.rustedwarfare.reforge.core.content.NautilusRules.supports(unit.definition)) &&
                    unit.definition.movement.type != MovementType.NONE && unit.moveTarget == null) {
                    unit.tasks.firstOrNull()?.takeIf { it.kind == UnitTaskKind.MOVE }?.let { task ->
                        unit.moveTarget = task.target
                        unit.originMoveId = task.actionId
                        unit.originAtStep = task.atStep
                        resumeProductionRoute(unit, currentScenario.map, step.stepIndex)
                    }
                }
                if (fourLegged) updateUnit(unit, currentScenario.map, step.delta, step.stepIndex, candidate.motionSamples)
                if (BuildingWork.isReady(unit)) {
                    updateWeapon(unit, step.delta)
                    if (unit.definition.nuclearSilo != null) NuclearOrdnance.advance(unit, step.delta)?.let { work ->
                        pendingEvents += Event("unit-action-completed", unit.unitId)
                        if (work.action == OrdnanceAction.LAUNCH) {
                            val id = nextObjectId
                            nextObjectId = Math.addExact(id, 1L)
                            val shot = NuclearOrdnance.projectile(unit, work, id)
                            projectiles += shot
                            pendingProjectileEvents += ProjectileEventObservation("born", projectileObservation(shot))
                            pendingEvents += Event("projectile-fired", unit.unitId)
                            steps.current.pendingSoundTriggers += SoundTriggerObservation("reforge:events/nuke-launch", shot.position, .27f)
                        }
                    }
                    updatePointDefense(unit, step.delta)
                    ProjectileInterception.target(unit, projectiles, units, currentScenario.players)?.let { target ->
                        val id = nextObjectId
                        nextObjectId = Math.addExact(id, 1L)
                        val shot = ProjectileInterception.launch(unit, target, id)
                        projectiles += shot
                        pendingProjectileEvents += ProjectileEventObservation("born", projectileObservation(shot))
                        pendingEvents += Event("projectile-fired", unit.unitId)
                        steps.current.pendingSoundTriggers += SoundTriggerObservation("reforge:events/nuke-launch", shot.position, .15f)
                    }
                    if (unit.definition.energy?.dieOnZero != true) WeaponEnergy.advance(unit, step.delta)
                    if ((!factoryWork || factoryActionAtHead) && withUnitActionQueue(unit) {
                            CustomUnitActions.advanceQueue(unit, currentScenario.unitDefinitions, step.delta, steps.current.actionClock) { launch, target ->
                                launchScoutPayload(unit, launch.turretName, target)
                            }
                        })
                        pendingEvents += Event("unit-action-completed", unit.unitId)
                    CustomUnitActions.constrain(unit, step.delta, policies.steering, policies.coordinates, unitActionHead(unit))
                }
                if (!fourLegged) updateUnit(unit, currentScenario.map, step.delta, step.stepIndex, candidate.motionSamples)
                FlightMotion.advancePose(unit, currentScenario.map, step.delta)
                NautilusMotion.advance(unit, step.delta)
                Transport.refreshTerrain(unit, currentScenario.map)
                pendingEvents += Transport.advance(unit, units, currentScenario.map, step.delta)
                if (unit.transportState == null) HoverMotion.advancePose(unit, step.delta)
                if (SubmarineMotion.advance(unit, step.delta)) pendingEvents += Event("submarine-threshold-crossed", unit.unitId)
                if (AmphibiousJetMotion.advance(unit, currentScenario.map, step.delta))
                    pendingEvents += Event("amphibious-threshold-crossed", unit.unitId)
                MechGait.advance(unit, step.delta)
                MechMotion.requireFinitePose(unit)
                unit.finishMovementRequest()
                if (BuildingWork.isReady(unit)) {
                    unit.namedWeapons?.values?.forEach {
                        it.cooldown = if (it.cooldown >= 0.0) maxOf(0.0, it.cooldown - step.delta)
                            else minOf(0.0, it.cooldown + step.delta)
                    }
                        ?: unit.combat?.let { it.cooldown = maxOf(0.0, it.cooldown - step.delta) }
                    if (!economicBuilding && !factoryWork) advanceUpgrade(unit)
                    if (withUnitActionQueue(unit) {
                            CustomUnitActions.advanceAutomatic(unit, currentScenario.unitDefinitions, currentScenario.map, step.delta)
                        })
                        pendingEvents += Event("unit-action-automatic", unit.unitId)
                    val regeneration = unit.definition.selfRegenRate
                    if (regeneration != 0f && checkNotNull(unit.health) < checkNotNull(unit.definition.maximumHealth)) {
                        val restored = checkNotNull(unit.health).toFloat() + regeneration * step.delta.toFloat()
                        require(restored.isFinite()) { "HP regeneration must remain finite" }
                        unit.health = minOf(checkNotNull(unit.definition.maximumHealth).toFloat(), restored).toDouble()
                    }
                }
            }
            updateProjectiles(step.delta, candidate.existingProjectileIds)
            missileTargets = missileTargets?.refresh(orderedUnits())
            candidate.stageKnowledge(prepareAutomaticKnowledge())
            candidate.stageBuildings(prepareBuildings(candidate.vision))
        }
    }

    fun observe(): Observation {
        check(!closed) { "Simulation is closed" }
        return latestObservation ?: recordObservation()
    }

    private fun isVisibleTo(playerId: String, storedPosition: Position, nativeTransportPlacement: Boolean = false): Boolean {
        val view = automaticVisionSnapshots?.getValue(playerId) ?: return true
        val map = checkNotNull(scenario).map
        val position = Transport.queryPosition(storedPosition, nativeTransportPlacement, map)
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
        val owners = IdentityHashMap<ActionResult, String>()
        (committedObservation?.actionResults.orEmpty() + observation.actionResults).forEach { result ->
            actionResultOwners[result]?.let { owners[result] = it }
        }
        val frame = PlayerObservationFrame(observation, committedObservation,
            automaticVisionSnapshots?.getValue(playerId), knowledgeStores?.getValue(playerId)?.snapshot(world),
            playerActionRevisions[playerId] ?: 0L, hasAdvanced, owners,
            units.values.filter { it.reclaimed }.associate { it.unitId to it.ownerPlayerId },
            units.values.filter { it.nativeTransportPlacement }.mapTo(mutableSetOf()) { it.unitId })
        return playerObservationProjectors.getOrPut(playerId) {
            PlayerObservationProjector(world, input, playerId)
        }.observe(frame)
    }

    /** Current-step audio authorization; no historical or source-coordinate query is exposed. */
    fun consumeSounds(world: WorldHandle, playerId: String, listener: SoundListener): AuthorizedSoundBatch {
        checkKnowledgeWorld(world)
        val input = checkNotNull(scenario)
        require(input.players.any { it.playerId == playerId }) { "SOUND_AUDIENCE_INVALID: Unknown match player" }
        listener.validate(input.map.widthTiles.toDouble() * input.map.tileWidth,
            input.map.heightTiles.toDouble() * input.map.tileHeight)
        return soundAuthority.consume(world, playerId, committedObservation, listener)
    }

    private fun initializeInternal(nextScenario: Scenario): InitResult {
        if (!closed) return InitResult.Rejected(SimulationError("already-open", "Close the current world before initializing"))
        val error = validate(nextScenario)
        if (error != null) return InitResult.Rejected(error)
        val initialBuildings = try { BuildingTerrain.initial(nextScenario) } catch (failure: BuildingInitializationException) {
            return InitResult.Rejected(failure.error)
        }
        val initialLedger = nextScenario.economyContract?.let { economy ->
            ProductionLedger.initial(economy.initialBalances, emptyMap(),
                nextScenario.unitDefinitions.values.any { it.income != null || it.maintenance != null } || nextScenario.maintenanceContract != null)
        }
        val initialUpgrades = OrdinaryUpgrades.prepare(nextScenario)
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
        ordinaryUpgrades = initialUpgrades
        buildingTerrain = initialBuildings
        units = linkedMapOf()
        projectiles = mutableListOf()
        steps.current.temporaryVision = emptyList()
        combatFrame = nextScenario.combatContract?.initialFrameCounter ?: -1
        steps.current.actionClock = 0
        nextObjectId = nextScenario.combatContract?.nextObjectId ?: 1L
        lastBirthSerial = 0L
        buildingWork = BuildingWork.initial()
        nextScenario.units.sortedBy { it.unitId }.forEach { spawn ->
            UnitRegistration.initial(steps.current, nextScenario, spawn, policies.coordinates)
        }
        missileTargets = if (nextScenario.unitDefinitions.values.any { it.combat?.projectile?.missile != null || it.maintenance != null })
            MissileTargetIndex.initial(nextScenario.map, orderedUnits()) else null
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
        if (constructionNamespaceEnabled(input) && input.units.any { it.unitId.startsWith(CONSTRUCTED_UNIT_PREFIX) })
            return SimulationError("reserved-unit-id", "Construction scenes reserve the constructed unit namespace")
        val players = input.players.map { it.playerId }.toSet()
        val incomes = input.unitDefinitions.values.mapNotNull { it.income }
        if (incomes.any { !it.period.isFinite() || it.period < 1f || !it.credits.isFinite() || it.credits < 0f })
            return SimulationError("unsupported-income", "Income requires a finite period of at least one tick and finite nonnegative credits")
        input.economyContract?.let { economy ->
            if (economy.initialBalances.keys != players || economy.initialBalances.values.any {
                    it < 0L || (incomes.isNotEmpty() && it > 999999999L) }) {
                return SimulationError("invalid-economy-contract", "Expected an explicit nonnegative balance for every declared player")
            }
        }
        if (input.unitDefinitions.values.any { it.production != null || it.income != null || it.builder != null || it.construction != null } && input.economyContract == null) {
            return SimulationError("missing-economy-contract", "Production, income and construction require an explicit economy contract")
        }
        if (input.units.any { it.ownerPlayerId !in players }) return SimulationError("bad-reference", "Unit owner is not a declared player")
        if (input.units.any { it.typeId !in input.unitDefinitions }) return SimulationError("bad-reference", "Unit type is not declared")
        MaintenanceRules.validate(input)?.let { return it }
        OrdinaryUpgrades.validate(input)?.let { return it }
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
            if (definition.nuclearSilo != null && !NuclearOrdnance.supports(definition))
                return SimulationError("unsupported-nuclear-silo", "Expected the complete finite effective nuclear silo")
            if (definition.techLevel !in 1..3)
                return SimulationError("unsupported-tech-level", "Expected a supported construction tech level from 1 to 3")
            val maximumHealth = definition.maximumHealth
            if (!definition.selfRegenRate.isFinite() || definition.selfRegenRate < 0f ||
                definition.selfRegenRate != 0f && (maximumHealth == null ||
                    !maximumHealth.toFloat().isFinite() || maximumHealth <= 0.0))
                return SimulationError("unsupported-self-regeneration", "Expected nonnegative finite HP regeneration on a durable unit")
            if (definition.transport != null && !Transport.supports(definition))
                return SimulationError("unsupported-transport", "Expected a finite native transport capability")
            if (definition.transportSlots < 0) return SimulationError("invalid-transport-slots", "Transport slots must be a nonnegative integer")
            if (definition.shield?.let { !it.isValid() || definition.maximumHealth == null } == true)
                return SimulationError("unsupported-shield", "Expected finite positive shield capacity and nonnegative regeneration on a durable unit")
            val m = definition.movement
            val submarine = definition.submarine != null
            val amphibious = definition.amphibiousJet != null
            val dropship = definition.transport == com.rustedwarfare.reforge.core.content.TransportProfile.NATIVE_DROPSHIP
            val bunker = Bunker.supports(definition)
            val engineer = MechEngineer.supports(definition)
            val nautilus = com.rustedwarfare.reforge.core.content.NautilusRules.supports(definition)
            val scout = ScoutBotRules.supports(definition)
            val flameMech = definition.id == MechFlameRules.ID
            if (flameMech && !MechFlameRules.supports(definition))
                return SimulationError("unsupported-flame-mech", "Expected the qualified flame mech")
            val bomber = definition.id == BomberRules.ID
            if (bomber && !BomberRules.supports(definition))
                return SimulationError("unsupported-bomber", "Expected the qualified two-weapon bomber")
            if (!nautilus && definition.combat?.namedWeapons?.ordered?.any { it.interception != null } == true)
                return SimulationError("unsupported-naval-interception", "Expected the qualified Nautilus interception weapon")
            if (definition.groundHeight != null && !bunker && !scout)
                return SimulationError("unsupported-unit-actions", "Ground height requires a qualified custom form")
            val beamGunship = !nautilus && !scout && !bomber && (definition.energy != null || definition.combat?.projectile?.beam != null ||
                definition.combat?.namedWeapons?.ordered?.any { it.energyUsage != 0f || it.projectile.beam != null } == true)
            if (beamGunship && !AaBeamGunship.supports(definition))
                return SimulationError("unsupported-aa-beam", "Expected the qualified AA energy and beam representative")
            if (definition.customActions != null) {
                if (!CustomUnitActions.supports(definition))
                    return SimulationError("unsupported-unit-actions", "Expected qualified custom unit actions")
                if (definition.customActions.actions.any { action ->
                    action.targetTypeId?.let { input.unitDefinitions[it]?.let(CustomUnitActions::supports) != true } == true ||
                    action.groundLaunch?.let { launch ->
                        input.unitDefinitions[launch.payloadUnitTypeId]?.let(ScoutBotRules::supports) != true
                    } == true })
                    return SimulationError("invalid-unit-action-target", "Custom action target must be registered and qualified")
            }
            if (amphibious && !AmphibiousJetMotion.supports(definition))
                return SimulationError("unsupported-amphibious-jet", "Expected the finite native amphibious jet capability")
            if (submarine && !SubmarineMotion.supports(definition))
                return SimulationError("unsupported-submarine", "Expected the finite native submarine capability")
            val air = m.type == MovementType.AIR
            val hovering = m.type == MovementType.HOVER && m.hover != null
            if (m.hover != null && (!hovering || !m.hover.isValid() || m.flight != null ||
                    (definition.transport == null && (!m.slidingMode || !m.ignoringBody)) || m.reverseSpeedPercentage != 0.0 || definition.mech != null))
                return SimulationError("unsupported-hover", "Expected a finite sliding HOVER height profile")
            definition.fixedUnderwaterTarget?.let { fixed ->
                if (fixed.height != -8.0 || m.type != MovementType.WATER ||
                    listOf(m.moveSpeed, m.moveAccelerationSpeed, m.moveDecelerationSpeed, m.maxTurnSpeed,
                        m.turnAcceleration, m.reverseSpeedPercentage).any { it != 0.0 } || m.slidingMode || m.ignoringBody ||
                    m.flight != null || definition.combat != null || definition.build != null || definition.building != null ||
                    definition.builder != null || definition.construction != null || definition.production != null ||
                    definition.upgrades != null || definition.income != null || definition.mech != null || definition.rotor != null ||
                    definition.deathExplosion != null || definition.pointDefense != null || definition.maximumHealth == null)
                    return SimulationError("unsupported-underwater-target", "Expected the finite fixed-depth unarmed WATER target")
            }
            if (air && !amphibious && !dropship && !beamGunship && !bomber && (m.flight?.isValid() != true || definition.rotor?.spinRate?.isFinite() != true ||
                    !m.slidingMode || !m.ignoringBody || m.reverseSpeedPercentage != .1 || definition.mech != null) ||
                !air && (m.flight != null || definition.rotor != null))
                return SimulationError("unsupported-flight", "Expected a finite AIR profile and a single finite rotor")
            if (!m.reverseSpeedPercentage.isFinite() || !air && !submarine && !engineer && !nautilus && (m.reverseSpeedPercentage !in setOf(0.0, 1.0) ||
                m.reverseSpeedPercentage != 0.0 && definition.mech == null && m.type != MovementType.WATER) ||
                definition.mech?.let { !it.isValid() || !bunker && !nautilus && !scout && !flameMech && m.type != (if (it.kind == com.rustedwarfare.reforge.core.content.MechKind.FOUR_LEGGED) MovementType.OVER_CLIFF else MovementType.LAND) } == true)
                return SimulationError("unsupported-mech", "Expected the finite supported mech locomotion category")
            if (definition.maintenance != null && !RepairBay.supports(definition) && !engineer && !scout)
                return SimulationError("unsupported-repair-bay", "Expected the complete finite native repair bay capability")
            if (definition.reclaimPrice != null && definition.reclaimPrice < 0L)
                return SimulationError("invalid-reclaim-price", "Explicit reclaim price must be nonnegative")
            definition.builder?.takeUnless { scout }?.let { builder ->
                if (definition.fogOfWarSightRange < 15 || !engineer && (m.type != MovementType.LAND || m.moveSpeed > .8 ||
                    builder.range != 85f || builder.preparation != 30f || builder.decay != 1.3f ||
                    builder.repairRate != .2f || builder.buildSpeedMultiplier != 1f || builder.mainNanoTurret != null) ||
                    builder.buildingTypeIds.isEmpty() || builder.buildingTypeIds.distinct().size != builder.buildingTypeIds.size ||
                    builder.buildingTypeIds.any { input.unitDefinitions[it]?.construction == null })
                    return SimulationError("unsupported-construction", "Finite construction requires supported sight, movement and building targets")
            }
            definition.construction?.let { construction ->
                val building = definition.building
                val mechFactory = FactoryExit.supportsMechFactory(definition)
                val airFactory = FactoryExit.supportsAirFactory(definition)
                val seaFactory = FactoryExit.supportsSeaFactory(definition)
                val experimentalFactory = com.rustedwarfare.reforge.core.content.ExperimentalFactoryRules.supports(definition)
                val factory = building?.placementFootprint == com.rustedwarfare.reforge.core.content.BuildingFootprint(-1, -1, 1, 3) &&
                    building.footprint == com.rustedwarfare.reforge.core.content.BuildingFootprint(-1, -1, 1, 1) &&
                    building.anchor == null && !building.requiresResourcePool
                val extractor = building?.requiresResourcePool == true &&
                    building.footprint == com.rustedwarfare.reforge.core.content.BuildingFootprint(0, 0, 0, 0) &&
                    building.placementFootprint == building.footprint && building.anchor == com.rustedwarfare.reforge.core.content.FootprintAnchor(10f, 10f)
                val fabricator = building?.requiresResourcePool == false &&
                    building.footprint == com.rustedwarfare.reforge.core.content.BuildingFootprint(-1, -1, 1, 1) &&
                    building.placementFootprint == building.footprint && building.anchor == com.rustedwarfare.reforge.core.content.FootprintAnchor(10f, 10f)
                val defenseShape = building?.requiresResourcePool == false &&
                    building.footprint == com.rustedwarfare.reforge.core.content.BuildingFootprint(0, 0, 1, 1) &&
                    building.placementFootprint == building.footprint &&
                    building.anchor == com.rustedwarfare.reforge.core.content.FootprintAnchor(20f, 20f)
                val defense = defenseShape && definition.production == null && definition.builder == null && definition.income == null &&
                    when (definition.id) {
                        "c_turret_t1" -> construction == com.rustedwarfare.reforge.core.content.ConstructionProfile(500L, .0008f) &&
                            definition.radius == 16.0 && definition.maximumHealth == 700.0 && definition.combat != null && definition.pointDefense == null
                        "laserDefence" -> construction == com.rustedwarfare.reforge.core.content.ConstructionProfile(1200L, .001f) &&
                            definition.radius == 19.0 && definition.maximumHealth == 500.0 && definition.combat == null &&
                            definition.pointDefense == com.rustedwarfare.reforge.core.content.PointDefenseProfile()
                        else -> false
                    }
                val repairBay = RepairBay.supports(definition)
                val supportedPlacement = if (definition.id in setOf("c_turret_t1", "c_turret_t2_gun", "c_turret_t1_artillery", "laserDefence"))
                    defense else factory || airFactory || seaFactory || experimentalFactory || extractor || fabricator || repairBay || NuclearOrdnance.supports(definition)
                val mobileNano = definition.id == "mechGun" && definition.building == null &&
                    definition.mech?.kind == com.rustedwarfare.reforge.core.content.MechKind.TWO_LEGGED &&
                    definition.build == com.rustedwarfare.reforge.core.content.BuildProfile(600L, .0012f) &&
                    construction == com.rustedwarfare.reforge.core.content.ConstructionProfile(600L, .0012f) &&
                    m.type == MovementType.LAND
                if ((!mobileNano && building == null) || construction.price < 0L || !construction.rate.isFinite() || construction.rate <= 0f ||
                    !supportedPlacement && !mobileNano || definition.radius > 30.0 && !(factory && mechFactory) && !seaFactory && !experimentalFactory && !NuclearOrdnance.supports(definition))
                    return SimulationError("unsupported-construction", "Expected finite construction metadata within the supported placement envelope")
            }
            definition.pointDefense?.let { profile ->
                if (definition.building == null || definition.combat != null || m.type != MovementType.NONE ||
                    !profile.range.isFinite() || profile.range <= 0.0 || !(profile.range * profile.range).isFinite() ||
                    !profile.muzzleOffsetX.isFinite() || !profile.muzzleOffsetY.isFinite() ||
                    !profile.recharge.isFinite() || profile.recharge < 0f || !profile.cost.isFinite() || profile.cost < 0f ||
                    !profile.initialEnergy.isFinite() || profile.initialEnergy !in 0f..1f)
                    return SimulationError("unsupported-point-defense", "Expected a finite immobile defense building and valid energy profile")
            }
            definition.deathExplosion?.let { profile ->
                if (definition.building == null || definition.durability == null || m.type != MovementType.NONE ||
                    !profile.damage.isFinite() || profile.damage < 0f || !profile.radius.isFinite() || profile.radius <= 0f ||
                    !(profile.radius * profile.radius).isFinite() || !profile.expandTime.isFinite() || profile.expandTime <= 0f)
                    return SimulationError("unsupported-death-explosion", "Expected a durable immobile building and finite death area")
            }
            definition.production?.let { production ->
                if (nautilus && production.unitTypeIds.any { id ->
                        input.unitDefinitions[id]?.let(com.rustedwarfare.reforge.core.content.NautilusRules::acceptsProduct) != true })
                    return SimulationError("unsupported-production", "Nautilus requires the qualified product price, rate and capability")
                if (production.speedMultiplier != (if (nautilus) 2f else 1f) ||
                    production.exitProfile == com.rustedwarfare.reforge.core.content.FactoryExitProfile.CUSTOM_NAUTILUS && !nautilus)
                    return SimulationError("unsupported-production", "Expected qualified current factory speed and exit")
                val authoredFactory = com.rustedwarfare.reforge.core.content.AuthorFactoryRules.supports(definition, input.unitDefinitions)
                if (production.exitProfile == com.rustedwarfare.reforge.core.content.FactoryExitProfile.NATIVE_EXPERIMENTAL &&
                    (!com.rustedwarfare.reforge.core.content.ExperimentalFactoryRules.supports(definition) ||
                        input.unitDefinitions["nautilusSubmarineLand"]?.let(com.rustedwarfare.reforge.core.content.NautilusRules::supports) != true))
                    return SimulationError("unsupported-production", "Experimental factory requires the qualified native body and land Nautilus product")
                if (!authoredFactory && production.exitProfile == com.rustedwarfare.reforge.core.content.FactoryExitProfile.NATIVE_SEA &&
                    (!FactoryExit.supportsSeaFactory(definition) || input.unitDefinitions["heavyBattleship"]?.movement?.type != MovementType.WATER ||
                        input.unitDefinitions["heavyBattleship"]?.combat == null))
                    return SimulationError("unsupported-production", "Native sea exits require the finite factory tier and armed WATER product")
                if (!authoredFactory && production.exitProfile == com.rustedwarfare.reforge.core.content.FactoryExitProfile.NATIVE_AIR &&
                    (!FactoryExit.supportsAirFactory(definition) || production.unitTypeIds.any { id ->
                        val product = input.unitDefinitions[id]
                        product?.movement?.type != MovementType.AIR ||
                            product.combat == null && product.transport != com.rustedwarfare.reforge.core.content.TransportProfile.NATIVE_DROPSHIP
                    }))
                    return SimulationError("unsupported-production", "Native air exits require the finite factory and its declared AIR product capabilities")
                if (!authoredFactory && production.exitProfile == com.rustedwarfare.reforge.core.content.FactoryExitProfile.CUSTOM_MECH &&
                    (!FactoryExit.supportsMechFactory(definition) || input.unitDefinitions["builder"]?.builder == null ||
                        input.unitDefinitions["mechGun"]?.mech == null))
                    return SimulationError("unsupported-production", "Custom mech exits require the complete finite L1 factory and product capabilities")
                if (definition.building == null && !nautilus || production.unitTypeIds.isEmpty() &&
                    !(FactoryExit.supportsSeaFactory(definition) && definition.id == "seaFactory") ||
                    production.unitTypeIds.distinct().size != production.unitTypeIds.size || production.unitTypeIds.any {
                        val build = input.unitDefinitions[it]?.build
                        build == null || build.price < 0L || !build.rate.isFinite() || build.rate <= 0f })
                    return SimulationError("unsupported-production", "Producer requires a fixed building and valid target build metadata")
            }
            definition.building?.let { building ->
                val shape = building.footprint
                val authored = definition.className == "AuthoredBuilding"
                if (building.foundationMovementType != null && (!authored ||
                        building.foundationMovementType !in setOf(MovementType.LAND, MovementType.WATER)) ||
                    authored && (building.foundationMovementType == null || definition.construction == null ||
                        definition.production != null && !com.rustedwarfare.reforge.core.content.AuthorFactoryRules.supports(definition, input.unitDefinitions) ||
                        definition.builder != null || definition.combat != null ||
                        definition.upgrades != null && !com.rustedwarfare.reforge.core.content.AuthorFactoryRules.supports(definition, input.unitDefinitions) ||
                        definition.income != null || definition.pointDefense != null))
                    return SimulationError("unsupported-building", "Explicit author foundations require a finite plain construction body")
                if (m.type != MovementType.NONE || definition.maximumHealth == null || definition.radius <= 0.0 ||
                    shape.left > 0 || shape.top > 0 || shape.right < 0 || shape.bottom < 0 ||
                    shape.left > shape.right || shape.top > shape.bottom ||
                    building.anchor?.let { !it.offsetX.isFinite() || !it.offsetY.isFinite() } == true)
                    return SimulationError("unsupported-building", "Expected an immobile live building with an ordered footprint containing its center")
            }
            val numbers = listOf(definition.radius, m.moveSpeed, m.moveAccelerationSpeed, m.moveDecelerationSpeed,
                m.maxTurnSpeed, m.turnAcceleration)
            val fourLegged = definition.mech?.kind == com.rustedwarfare.reforge.core.content.MechKind.FOUR_LEGGED
            val navalHull = definition.id == "heavyBattleship" && m.type == MovementType.WATER && definition.radius == 40.0
            val authoredSurface = definition.authoredWaterCombat
            if (authoredSurface && (m.type != MovementType.WATER || definition.combat == null ||
                    definition.className != "CustomUnitMetadata" || !definition.id.matches(Regex("[a-z][a-z0-9_.-]*")) ||
                    definition.radius <= 0.0 || !definition.displayRadius.isFinite() || definition.displayRadius <= 0.0 ||
                    !definition.imageScale.isFinite() || definition.imageScale <= 0.0 || definition.totalFrames <= 0 ||
                    definition.fixedUnderwaterTarget != null || definition.building != null || definition.production != null ||
                    definition.builder != null || definition.construction != null || definition.income != null ||
                    definition.upgrades != null || definition.deathExplosion != null || definition.pointDefense != null ||
                    definition.mech != null || definition.rotor != null || m.flight != null ||
                    definition.directHitTargetKind != DirectHitTargetKind.ORDINARY))
                return SimulationError("unsupported-unit", "Expected finite authored surface combat without unsupported capability combinations")
            if (numbers.any { !it.isFinite() || it < 0.0 } ||
                (definition.building == null && definition.radius >= input.map.tileWidth && !(fourLegged && definition.radius == 22.0) && !navalHull && !authoredSurface && !dropship && !beamGunship && !bunker && !nautilus && !flameMech) ||
                m.type != MovementType.AIR && !fourLegged && !hovering && (m.slidingMode || m.ignoringBody) ||
                fourLegged && !bunker && (m.type != MovementType.OVER_CLIFF || !m.slidingMode || !m.ignoringBody)) {
                return SimulationError("unsupported-unit", "Expected finite movement and the supported representative's radius and locomotion")
            }
            if (m.type == MovementType.NONE && !bunker && !NuclearOrdnance.supports(definition) && numbers.drop(1).any { it != 0.0 })
                return SimulationError("unsupported-unit", "Immobile definitions require zero movement parameters")
            definition.durability?.let { durability ->
                if (!durability.maxHp.isFinite() || durability.maxHp <= 0.0 ||
                    durability.maxDamagePerHit?.let { !it.isFinite() || it <= 0.0 } == true ||
                    durability.armor?.isValid() == false ||
                    (definition.combat != null && durability.maxHp != definition.combat.maxHp)) {
                    return SimulationError("unsupported-durability", "Expected finite positive health consistent with the combat definition")
                }
            }
            val combatProfiles = definition.combat?.let { base ->
                base.namedWeapons?.let { catalog ->
                    if (catalog.ordered.any { named ->
                        named.attachedTo?.let { name ->
                            name == named.name || catalog.byName[name] == null || catalog.byName[name]?.attachedTo != null
                        } == true || named.slave && named.attachedTo == null
                    }) return SimulationError("unsupported-combat", "Invalid named weapon attachment or slave reference")
                    for (weapon in catalog.ordered) {
                        val visited = mutableSetOf(weapon.name)
                        var next = weapon.linkDelayWithTurret
                        while (next != null) {
                            if (!visited.add(next)) return SimulationError("unsupported-combat", "Cyclic named weapon links are unsupported")
                            val target = catalog.byName[next]
                                ?: return SimulationError("unsupported-combat", "Unknown named weapon link")
                            next = target.linkDelayWithTurret
                        }
                    }
                }
                if (base.namedWeapons?.ordered?.any { named ->
                        !named.minimumRange.isFinite() || named.minimumRange < 0.0 ||
                        !(named.minimumRange * named.minimumRange).isFinite() ||
                        named.maximumRange?.let { !it.isFinite() || it < 0.0 || !(it * it).isFinite() } == true ||
                        named.limitingAngle?.let { !it.isFinite() || it < 0.0 && it != -1.0 } == true ||
                        !named.idleDirection.isFinite() || named.idleDirectionReversing?.isFinite() == false ||
                        listOf(named.warmup, named.warmupCallDownRate, named.warmupShootDelayTransfer, named.aimMaxAngle)
                            .any { !it.isFinite() || it < 0.0 }
                    } == true) return SimulationError("unsupported-combat", "Named weapon ranges and clocks must be finite and nonnegative")
                listOf(base) + base.namedWeapons?.ordered.orEmpty().filter { it.canShoot }.map { NamedWeaponRuntime.profile(base, it) }
            }.orEmpty()
            for (combat in combatProfiles) {
                if ((combat.projectile.lightning || combat.projectile.damageModifiers != com.rustedwarfare.reforge.core.content.ProjectileDamageModifiers()) && !amphibious && !beamGunship)
                    return SimulationError("unsupported-combat", "Lightning requires the qualified native amphibious capability")
                combat.projectile.missile?.let { missile ->
                    if (listOf(missile.targetSpeed, missile.acceleration, missile.climbThreshold, missile.apex,
                            missile.verticalSpeed, missile.retargetForward).any { !it.isFinite() || it < 0f } ||
                        !missile.retargetRadius.isFinite() || missile.retargetRadius <= 0f ||
                        !(missile.retargetRadius * missile.retargetRadius).isFinite() || missile.apex < missile.climbThreshold ||
                        combat.projectile.ground != null || combat.projectile.instant ||
                        input.map.widthTiles.toLong() * input.map.tileWidth < 32L ||
                        input.map.heightTiles.toLong() * input.map.tileHeight < 32L ||
                        listOf(combat.attackRange, combat.projectile.life, combat.projectile.speed).any { !it.toFloat().isFinite() } ||
                        !(combat.projectile.speed.toFloat() * combat.projectile.speed.toFloat()).isFinite())
                        return SimulationError("unsupported-missile", "Expected finite native missile geometry and supported world dimensions")
                }
                val zeroRecoil = m.type == MovementType.AIR && combat.recoil.offset == 0.0
                val positive = listOf(combat.maxHp, combat.attackRange, combat.shootDelay, combat.projectile.life) +
                    (if (zeroRecoil) emptyList() else listOf(combat.recoil.outTime, combat.recoil.returnTime)) +
                    if (combat.projectile.acceleration == null) listOf(combat.projectile.speed) else emptyList()
                val nonnegative = listOf(combat.turretSize, combat.turretTurnSpeed, combat.turretTurnAcceleration,
                    combat.projectile.directDamage, combat.projectile.speed) +
                    (if (zeroRecoil) listOf(combat.recoil.outTime, combat.recoil.returnTime) else emptyList())
                val invalidAcceleration = combat.projectile.acceleration?.let {
                    !it.targetSpeed.isFinite() || it.targetSpeed <= 0.0 || !it.acceleration.isFinite() || it.acceleration <= 0.0 ||
                        combat.projectile.instant || combat.projectile.ground != null && !bomber && !flameMech || combat.projectile.missile != null
                } == true
                val invalidBallistic = combat.projectile.ballistic?.let {
                    !it.delayMoveHeight.isFinite() || it.delayMoveHeight < 0f || !it.apex.isFinite() ||
                        it.apex < it.delayMoveHeight && !nautilus || (it.verticalSpeed != -1f &&
                            !(submarine && it == com.rustedwarfare.reforge.core.content.CustomBallisticProfile(40f, 60f, 2f))) || combat.projectile.instant ||
                        combat.projectile.ground != null || combat.projectile.missile != null ||
                        listOf(combat.attackRange, combat.projectile.life, combat.projectile.speed).any { !it.toFloat().isFinite() } ||
                        !(combat.projectile.speed.toFloat() * combat.projectile.speed.toFloat()).isFinite() ||
                        combat.projectile.acceleration?.let { acceleration ->
                            !acceleration.targetSpeed.toFloat().isFinite() || !acceleration.acceleration.toFloat().isFinite()
                        } == true
                } == true
                val signed = listOf(combat.mount.localX, combat.mount.localY, combat.recoil.offset,
                    combat.recoil.outTime + combat.recoil.returnTime, combat.barrelX, combat.oddShotOffset)
                val invalidGround = combat.projectile.ground?.let { ground ->
                    !ground.areaDamage.isFinite() || ground.areaDamage < 0f ||
                    !ground.areaRadius.isFinite() || ground.areaRadius <= 0f || !(ground.areaRadius * ground.areaRadius).isFinite() ||
                    !ground.spread.isFinite() || ground.spread < 0f || !ground.initialVerticalSpeed.isFinite() ||
                    !ground.gravity.isFinite() || ground.gravity < 0f || !ground.trueGravity.isFinite() || ground.trueGravity < 0f ||
                    !flameMech && (ground.speedSpread != 0f || ground.resolveAtSnapshot) ||
                    listOf(combat.attackRange, combat.projectile.life, combat.projectile.speed).any { !it.toFloat().isFinite() }
                } == true
                val invalidSplash = combat.projectile.splash?.let { splash ->
                    !splash.damage.isFinite() || splash.damage < 0f || !splash.radius.isFinite() || splash.radius <= 0f ||
                        !(splash.radius * splash.radius).isFinite() || combat.projectile.ground != null ||
                        combat.projectile.missile != null || combat.projectile.instant
                } == true
                val invalidIdle = combat.idleSweep?.let { !it.angle.isFinite() || it.angle < 0.0 ||
                    it.angle == 0.0 && !(beamGunship && AaBeamGunship.supports(definition)) ||
                    !it.delay.isFinite() || it.delay <= 0f || !it.speedFactor.isFinite() || it.speedFactor <= 0f ||
                    it.randomDelayExclusive <= 0 } == true
                if (positive.any { !it.isFinite() || it <= 0.0 } || nonnegative.any { !it.isFinite() || it < 0.0 } ||
                    signed.any { !it.isFinite() } || invalidIdle || invalidGround || invalidSplash || invalidAcceleration || invalidBallistic ||
                    (combat.turretTurnDeceleration?.let { !it.isFinite() || it <= 0.0 } == true) ||
                    !combat.projectile.deflectionHealth.isFinite() || combat.projectile.deflectionHealth <= 0f)
                    return SimulationError("unsupported-combat", "Ordinary combat numbers must be finite and in range")
            }
        }
        if (input.unitDefinitions.values.any { it.combat != null || it.deathExplosion != null || it.nuclearSilo != null } && input.combatContract == null)
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
            val resourceBuilding = input.unitDefinitions.getValue(spawn.typeId).building?.requiresResourcePool == true
            val spawnDefinition = input.unitDefinitions.getValue(spawn.typeId)
            if (spawnDefinition.authoredWaterCombat && spawn.height != 0.0)
                return SimulationError("invalid-spawn", "Authored surface combat must start at height zero")
            if (spawnDefinition.id == "heavyBattleship" && spawnDefinition.movement.type == MovementType.WATER &&
                spawnDefinition.radius == 40.0 && spawn.height != 0.0)
                return SimulationError("invalid-spawn", "The finite surface battleship must start at height zero")
            if (input.unitDefinitions.getValue(spawn.typeId).movement.type == MovementType.AIR && spawn.height != 0.0)
                return SimulationError("invalid-spawn", "AIR initial height must use FlightProfile.startingHeightOffset; explicit spawn height must be zero")
            input.unitDefinitions.getValue(spawn.typeId).fixedUnderwaterTarget?.let { fixed ->
                if (spawn.height != fixed.height) return SimulationError("invalid-spawn", "Fixed underwater target height must match its explicit profile")
            }
            if (!spawn.heading.isFinite() || !spawn.height.isFinite() ||
                (!resourceBuilding && input.unitDefinitions.getValue(spawn.typeId).movement.type != MovementType.AIR &&
                    policies.terrain.intersects(input.map, p, radius, input.unitDefinitions.getValue(spawn.typeId).foundationMovementType))) {
                return SimulationError("invalid-spawn", "Unit '${spawn.unitId}' heading must be finite and spawn outside blocked terrain")
            }
            val definition = input.unitDefinitions.getValue(spawn.typeId)
            if (definition.mech != null) try {
                MechMotion.initial(definition, p, policies.coordinates.normalize(spawn.heading))
            } catch (_: ArithmeticException) {
                return SimulationError("unsupported-mech", "Initial mech pose must remain finite")
            }
            definition.pointDefense?.let { profile ->
                if (!(p.x + profile.muzzleOffsetX).isFinite() || !(p.y + profile.muzzleOffsetY).isFinite())
                    return SimulationError("unsupported-point-defense", "Initial defense muzzle must remain finite")
            }
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
            policies.path.findPath(planningTerrainFor(unit, map), Transport.queryPosition(unit, map), target, Transport.boundaryRadius(unit))
        else findPathWithDiagnostics(checkNotNull(unit.originMoveId), unit.originAtStep, target, unit, map,
            samples, PathSearchReason.ROUTE_REPAIR, executionStep)
        if (unit.attackCommand != null) unit.lastAttackPathAttemptStep = executionStep
        if (path == null) {
            unit.cancelRoute()
            pendingEvents += Event("movement-cancelled", unit.unitId)
            pendingEvents += Event("path-unreachable", unit.unitId)
        } else unit.adoptRoute(path, logicalRouteTarget(unit, checkNotNull(unit.moveTarget)), plannedKnowledgeRevision(unit), plannedBuildingRevision(unit))
    }

    private fun updatePatrolRoute(unit: MutableUnit, map: MapDefinition, executionStep: Long, previousRequest: Boolean) {
        val enemy = retainedPatrolTarget(unit)
        val task = unit.tasks.first()
        var target = checkNotNull(task.target)
        // No unit-overlap separation exists here: the source ac counter remains its initial zero.
        val multiple = unit.tasks.size > 1
        val capture = if (!previousRequest || combatFrame == 0 || combatFrame == 1)
            (if (multiple) 70.0 else 80.0) else 30.0
        val captureX = unit.position.x.toFloat() - target.x.toFloat()
        val captureY = unit.position.y.toFloat() - target.y.toFloat()
        if (captureX * captureX + captureY * captureY < capture.toFloat() * capture.toFloat()) {
            if (multiple) unit.taskQueue = unit.taskQueue.append(task).pop()
            unit.cancelRoute()
            if (multiple || enemy == null) return
        }
        if (enemy != null) {
            val stopRange = UnitCombat.range(unit).toFloat() - 1f
            if (CombatTargetQuery.patrolDistanceSquared(unit, enemy) < stopRange * stopRange) {
                unit.cancelRoute()
                return
            }
            target = enemy.position
        }
        unit.requestMovement(target)
        if (unit.moveTarget == target && unit.pathPoints.isNotEmpty()) return
        val samples = pathDiagnostics
        val path = if (unit.birthRemaining > 0f) {
            if (MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth,
                    map.heightTiles.toDouble() * map.tileHeight, target, Transport.boundaryRadius(unit))) listOf(target) else null
        } else if (samples == null)
            policies.path.findPath(planningTerrainFor(unit, map), Transport.queryPosition(unit, map), target, Transport.boundaryRadius(unit))
        else findPathWithDiagnostics(task.actionId, task.atStep, target, unit, map, samples,
            reason = if (enemy == null) PathSearchReason.USER_MOVE else PathSearchReason.ATTACK_PURSUIT,
            executionStep = executionStep)
        if (path == null) unit.cancelRoute()
        else {
            unit.moveTarget = target
            unit.originMoveId = task.actionId
            unit.originAtStep = task.atStep
            unit.adoptRoute(path, logicalRouteTarget(unit, checkNotNull(unit.moveTarget)), plannedKnowledgeRevision(unit), plannedBuildingRevision(unit))
        }
    }

    private fun updateAttackRoute(unit: MutableUnit, map: MapDefinition, executionStep: Long) {
        var command = unit.attackCommand ?: return
        val weapon = UnitCombat.profile(unit) ?: return
        val input = checkNotNull(scenario)
        fun eligible(target: MutableUnit) = CombatTargetQuery.isEnemy(unit, target, input.players) &&
            CombatTargetQuery.canAttackAirCategory(unit, target, input.map)
        var target = units[command.targetUnitId]?.takeIf(::eligible)
        if (target == null) {
            target = CombatTargetQuery.nearestEnemy(unit, orderedUnits(), input.players, policies.coordinates, input.map)
            unit.cancelRoute()
            unit.bomberApproach = null
            if (target == null) {
                unit.attackCommand = null
                if (unit.tasks.firstOrNull()?.kind == UnitTaskKind.ATTACK) unit.taskQueue = unit.taskQueue.pop()
                pendingEvents += Event("attack-ended", unit.unitId)
                return
            }
            command = command.copy(targetUnitId = target.unitId)
            unit.attackCommand = command
            unit.tasks.firstOrNull()?.takeIf { it.kind == UnitTaskKind.ATTACK }?.let {
                unit.taskQueue = unit.taskQueue.replaceHead(it.copy(targetUnitId = target.unitId))
            }
            unit.lastAttackPathAttemptStep = null
        }
        if (unit.definition.movement.type == MovementType.NONE) {
            unit.cancelRoute()
            return
        }
        val compositeRange = unit.definition.mech?.kind == com.rustedwarfare.reforge.core.content.MechKind.FOUR_LEGGED
        val bomber = unit.typeId == BomberRules.ID
        if (!bomber && policies.coordinates.distanceSquared(unit.position, target.position) < weapon.attackRange * weapon.attackRange &&
            (!compositeRange || NamedWeaponRuntime.inRange(unit, target, input.players, policies.coordinates, input.map))) {
            if (unit.moveTarget != null) unit.cancelRoute()
            return
        }
        val destination = if (bomber) FlightMotion.bomberAttackDestination(unit, target.position, map) else target.position
        unit.requestMovement(destination)
        val targetRange = if (bomber || target.definition.building == null) 0.0 else weapon.attackRange
        val routeEnd = unit.moveTarget
        val reusable = routeEnd != null && unit.pathPoints.isNotEmpty() &&
            if (targetRange == 0.0 || unit.birthRemaining > 0f) routeEnd == destination
            else policies.coordinates.distanceSquared(routeEnd, destination) < targetRange * targetRange &&
                !PlanningCenterTerrain.intersects(planningTerrainFor(unit, map), routeEnd, Transport.boundaryRadius(unit))
        if (unit.lastAttackPathAttemptStep == executionStep || reusable) return
        unit.lastAttackPathAttemptStep = executionStep
        val samples = pathDiagnostics
        val path = if (unit.birthRemaining > 0f) {
            if (MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth,
                    map.heightTiles.toDouble() * map.tileHeight, destination, Transport.boundaryRadius(unit)))
                listOf(destination) else null
        } else if (samples == null) {
            policies.path.findPath(planningTerrainFor(unit, map), Transport.queryPosition(unit, map), destination, Transport.boundaryRadius(unit),
                targetRange = targetRange)
        } else findPathWithDiagnostics(command.actionId, command.atStep, destination, unit, map, samples,
            reason = PathSearchReason.ATTACK_PURSUIT, executionStep = executionStep, targetRange = targetRange)
        if (path == null) {
            unit.cancelRoute()
            pendingEvents += Event("path-unreachable", unit.unitId)
        } else {
            unit.moveTarget = path.last()
            unit.originMoveId = command.actionId
            unit.originAtStep = command.atStep
            unit.nearTargetTime = 0.0
            unit.adoptRoute(path, logicalRouteTarget(unit, checkNotNull(unit.moveTarget)), plannedKnowledgeRevision(unit), plannedBuildingRevision(unit))
        }
    }

    private fun retainedPatrolTarget(unit: MutableUnit): MutableUnit? {
        val oldId = unit.primaryCombatTargetId ?: return null
        val input = checkNotNull(scenario)
        val target = units[oldId]?.takeIf {
            CombatTargetQuery.isEnemy(unit, it, input.players) && CombatTargetQuery.canAttackAirCategory(unit, it, input.map) &&
                isVisibleTo(unit.ownerPlayerId, it.position, it.nativeTransportPlacement) }
        val range = maxOf(UnitCombat.range(unit).toFloat() + 110f, 190f)
        if (target != null && CombatTargetQuery.patrolDistanceSquared(unit, target) < range * range) return target
        unit.primaryCombatTargetId = null
        unit.cancelRoute()
        unit.lastAttackPathAttemptStep = null
        return null
    }

    private fun acquirePatrolTarget(unit: MutableUnit): MutableUnit? =
        CombatTargetQuery.patrolEnemy(unit, orderedUnits(), checkNotNull(scenario).players, ::isVisibleTo, checkNotNull(scenario).map)

    private fun updateWeapon(unit: MutableUnit, delta: Double) {
        if (unit.namedWeapons != null) {
            updateNamedWeapons(unit, delta)
            return
        }
        val state = unit.combat ?: return
        val weapon = checkNotNull(UnitCombat.profile(unit))
        val input = checkNotNull(scenario)
        val contract = checkNotNull(input.combatContract)
        val actorId = checkNotNull(unit.objectId)
        OrdinaryWeapon.idle(contract.worldSeed, combatFrame, actorId, unit, delta)
        fun eligible(target: MutableUnit) = CombatTargetQuery.isEnemy(unit, target, input.players) &&
            CombatTargetQuery.canAttackAirCategory(unit, target, input.map)
        fun inRange(target: MutableUnit) = policies.coordinates.distanceSquared(unit.position, target.position) <
            weapon.attackRange * weapon.attackRange
        val manual = unit.attackCommand
        val manualTarget = manual?.let { units[it.targetUnitId]?.takeIf { candidate -> eligible(candidate) && inRange(candidate) } }
        val patrol = unit.tasks.firstOrNull()?.kind == UnitTaskKind.PATROL
        val target = if (patrol) {
            state.patrolSearchTimer = maxOf(0f, state.patrolSearchTimer - delta.toFloat())
            state.patrolDispatchTimer = maxOf(0f, state.patrolDispatchTimer - delta.toFloat())
            var retained = retainedPatrolTarget(unit)
            val range = weapon.attackRange.toFloat()
            val interval = 20f + unit.position.x.toFloat() % 5f + unit.position.y.toFloat() % 5f
            if ((retained == null || CombatTargetQuery.patrolDistanceSquared(unit, retained) >= range * range) && state.patrolSearchTimer == 0f) {
                state.patrolSearchTimer = interval
                retained = acquirePatrolTarget(unit) ?: retained
                state.targetId = retained?.unitId
                if (retained != null) state.patrolDispatchTimer = 0f
            }
            if (retained != null && state.patrolDispatchTimer == 0f) state.patrolDispatchTimer = interval
            retained?.takeIf(::inRange)
        } else manualTarget ?: units[state.targetId]?.takeIf { eligible(it) && inRange(it) } ?: CombatTargetQuery.nearestEnemy(unit, orderedUnits(), input.players, policies.coordinates, input.map)
        if (target == null) {
            if (!patrol) state.targetId = null
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
        fireWeapon(unit, target, state, weapon)
    }

    private fun updateNamedWeapons(unit: MutableUnit, delta: Double) {
        val input = checkNotNull(scenario)
        val contract = checkNotNull(input.combatContract)
        val candidates = orderedUnits()
        val globalRange = UnitCombat.range(unit)
        fun globalEligible(target: MutableUnit) = CombatTargetQuery.isEnemy(unit, target, input.players) &&
            CombatTargetQuery.canAttackAirCategory(unit, target, input.map) &&
            policies.coordinates.distanceSquared(unit.position, target.position) < globalRange * globalRange
        val manual = unit.attackCommand?.let { units[it.targetUnitId]?.takeIf(::globalEligible) }
        val patrol = unit.tasks.firstOrNull()?.kind == UnitTaskKind.PATROL
        val primary = if (patrol) {
            val clock = checkNotNull(unit.combat)
            clock.patrolSearchTimer = maxOf(0f, clock.patrolSearchTimer - delta.toFloat())
            clock.patrolDispatchTimer = maxOf(0f, clock.patrolDispatchTimer - delta.toFloat())
            var retained = retainedPatrolTarget(unit)
            val range = globalRange.toFloat()
            val interval = 20f + unit.position.x.toFloat() % 5f + unit.position.y.toFloat() % 5f
            if ((retained == null || CombatTargetQuery.patrolDistanceSquared(unit, retained) >= range * range) && clock.patrolSearchTimer == 0f) {
                clock.patrolSearchTimer = interval
                retained = acquirePatrolTarget(unit) ?: retained
                unit.primaryCombatTargetId = retained?.unitId
                if (retained != null) clock.patrolDispatchTimer = 0f
            }
            if (retained != null && clock.patrolDispatchTimer == 0f) clock.patrolDispatchTimer = interval
            retained?.takeIf(::globalEligible)
        } else {
            (manual ?: units[unit.sharedWeaponTargetId]?.takeIf(::globalEligible) ?:
                CombatTargetQuery.nearestEnemy(unit, candidates, input.players, policies.coordinates, input.map)).also {
                    unit.sharedWeaponTargetId = it?.unitId
                }
        }
        val weaponCandidates = if (!patrol) candidates else if (unit.primaryCombatTargetId == null) emptyList()
            else candidates.filter { isVisibleTo(unit.ownerPlayerId, it.position, it.nativeTransportPlacement) }
        NamedWeaponRuntime.advance(unit, NamedWeaponFrame(contract.worldSeed, combatFrame, delta,
            input.players, policies.coordinates, units, weaponCandidates, primary, manual, input.map,
            projectiles.filter { it.beam && it.sourceUnitId == unit.unitId }.associate { checkNotNull(it.sourceWeaponName) to it.offset })) { target, state, weapon, delay, definition ->
            fireWeapon(unit, target, state, weapon, delay, definition)
        }
    }

    private fun fireWeapon(unit: MutableUnit, target: MutableUnit, state: MutableCombat,
                           weapon: com.rustedwarfare.reforge.core.content.CombatProfile,
                           effectiveDelay: Double = weapon.shootDelay,
                           definition: com.rustedwarfare.reforge.core.content.NamedWeaponDefinition? = null) {
        val contract = checkNotNull(scenario?.combatContract)
        val actorId = checkNotNull(unit.objectId)
        definition?.let { WeaponEnergy.consume(unit, it.energyUsage) }
        state.cooldown = effectiveDelay
        definition?.let { NamedWeaponRuntime.fired(unit, it) }
        val capturedOffset = if (unit.typeId == BomberRules.ID) Position(0.0, 0.0) else state.offset
        weapon.projectile.missile?.let { missile ->
            val id = nextObjectId
            nextObjectId = Math.addExact(nextObjectId, 1L)
            val projectile = MutableProjectile(id, unit.unitId, target.unitId,
                Position(unit.position.x.toFloat().toDouble(), unit.position.y.toFloat().toDouble()), 90.0,
                weapon.projectile.life, capturedOffset, 0f, weapon.projectile.speed, weapon.projectile.directDamage,
                deflectionHealth = weapon.projectile.deflectionHealth, nonDeflectable = weapon.projectile.nonDeflectable,
                missile = missile, sourceWeaponName = definition?.name)
            projectiles += projectile
            pendingProjectileEvents += ProjectileEventObservation("born", projectileObservation(projectile))
            weapon.fireSound?.let { steps.current.pendingSoundTriggers += SoundTriggerObservation(it.eventId, projectile.position, it.volume, it.pitch) }
            OrdinaryWeapon.refreshOffset(contract.worldSeed, combatFrame, actorId, unit, target, state)
            pendingEvents += Event("projectile-fired", unit.unitId)
            return
        }
        val muzzle = if (definition == null) OrdinaryWeapon.muzzle(unit, state, weapon)
            else NamedWeaponRuntime.muzzle(unit, definition, state)
        require(muzzle.x.isFinite() && muzzle.y.isFinite()) { "Projectile muzzle must remain finite" }
        if (weapon.projectile.beam != null) {
            val previous = projectiles.firstOrNull { it.beam && it.sourceUnitId == unit.unitId && it.sourceWeaponName == definition?.name }
            state.randomCounter = (state.randomCounter.toLong() + 1L + actorId).toInt()
            val beamPhase = previous?.scatter ?: (OrdinaryWeapon.sample(contract.worldSeed, combatFrame, actorId, unit,
                state.randomCounter, 0, 1000, state) * .001f).also { state.randomCounter++ }
            val beam = previous ?: MutableProjectile(nextObjectId, unit.unitId, target.unitId, muzzle, state.turretHeading,
                weapon.projectile.life, Position(0.0, 0.0), beamPhase, weapon.projectile.speed, weapon.projectile.directDamage,
                unit.height, instant = true, sourceWeaponName = definition?.name,
                damageModifiers = weapon.projectile.damageModifiers, beam = true,
                appearance = ProjectileAppearance.AA_BEAM).also {
                    nextObjectId = Math.addExact(nextObjectId, 1L)
                    projectiles += it
                }
            beam.targetUnitId = target.unitId
            beam.position = muzzle
            beam.heading = state.turretHeading
            beam.height = unit.height
            beam.remainingLife = weapon.projectile.life
            beam.hitApplied = false
            beam.beamSegment = BeamSegment(target.position, target.height)
            pendingProjectileEvents += ProjectileEventObservation(if (previous == null) "born" else "rearmed", projectileObservation(beam))
            pendingEvents += Event("projectile-fired", unit.unitId)
            return
        }
        val flameMech = unit.typeId == MechFlameRules.ID
        // Both flame nozzles advance the source's counter before release.
        val randomState = if (flameMech) checkNotNull(unit.combat) else state
        randomState.randomCounter = (randomState.randomCounter.toLong() + 1L + actorId).toInt()
        val id = nextObjectId
        nextObjectId = Math.addExact(nextObjectId, 1L)
        val scatter = if (flameMech) 0f else OrdinaryWeapon.sample(contract.worldSeed, combatFrame, actorId, unit,
            state.randomCounter, 0, 1000, state).also { state.randomCounter++ } * .001f
        val ground = weapon.projectile.ground?.let {
            GroundWeapon.snapshot(unit, target, muzzle, contract.worldSeed, combatFrame, actorId, randomState, weapon)
        }
        val speed = GroundWeapon.initialSpeed(weapon.projectile, unit, contract.worldSeed, combatFrame, actorId, randomState)
        val position = if (ground == null && weapon.projectile.ballistic == null) muzzle
            else Position(muzzle.x.toFloat().toDouble(), muzzle.y.toFloat().toDouble())
        val projectile = MutableProjectile(id, unit.unitId, if (ground == null) target.unitId else null, position, state.turretHeading,
            weapon.projectile.life, capturedOffset, scatter, speed, weapon.projectile.directDamage,
            if (ground == null) unit.height else unit.height.toFloat().toDouble(),
            deflectionHealth = weapon.projectile.deflectionHealth, instant = weapon.projectile.instant,
            nonDeflectable = weapon.projectile.nonDeflectable, ground = ground, verticalSpeed = ground?.profile?.initialVerticalSpeed ?: 0f,
            sourceWeaponName = definition?.name, acceleration = weapon.projectile.acceleration,
            splash = weapon.projectile.splash, retargetOnDeath = weapon.projectile.retargetOnDeath,
            ballistic = weapon.projectile.ballistic, lightning = weapon.projectile.lightning,
            damageModifiers = weapon.projectile.damageModifiers,
            lightningSegment = if (weapon.projectile.lightning) LightningSegment(target.position, target.height) else null,
            appearance = if (flameMech) ProjectileAppearance.MECH_FLAME
            else if (unit.typeId == BomberRules.ID) {
                if (ground == null) ProjectileAppearance.BOMBER_DEPTH_CHARGE else ProjectileAppearance.BOMBER_BOMB
            } else if (weapon.projectile.lightning) ProjectileAppearance.AMPHIBIOUS_LIGHTNING
                else if (unit.definition.submarine == null) ProjectileAppearance.STANDARD
                else if (SubmarineMotion.submerged(unit)) ProjectileAppearance.SUBMARINE_TORPEDO
                else ProjectileAppearance.SUBMARINE_MISSILE)
        projectiles += projectile
        pendingProjectileEvents += ProjectileEventObservation("born", projectileObservation(projectile))
        weapon.fireSound?.let { steps.current.pendingSoundTriggers += SoundTriggerObservation(it.eventId, position, it.volume, it.pitch) }
        OrdinaryWeapon.refreshOffset(contract.worldSeed, combatFrame, actorId, unit, target, state)
        state.oddShot = !state.oddShot
        pendingEvents += Event("projectile-fired", unit.unitId)
    }

    private fun launchScoutPayload(unit: MutableUnit, turretName: String, target: Position) {
        val turret = checkNotNull(unit.definition.combat?.namedWeapons).byName.getValue(turretName)
        val weapon = checkNotNull(unit.namedWeapons).getValue(turretName)
        val muzzle = NamedWeaponRuntime.muzzle(unit, turret, weapon).let { Position(it.x.toFloat().toDouble(), it.y.toFloat().toDouble()) }
        addScoutPayload(unit, turretName, muzzle, unit.height.toFloat().toDouble(), weapon.turretHeading,
            Position(target.x.toFloat().toDouble(), target.y.toFloat().toDouble()), ScoutPayloadStage.MOTHER)
        pendingEvents += Event("projectile-fired", unit.unitId)
        val sound = com.rustedwarfare.reforge.core.content.FireSoundProfile.NAVAL_MISSILE
        steps.current.pendingSoundTriggers += SoundTriggerObservation(sound.eventId, muzzle, 1f, sound.pitch)
    }

    private fun addScoutPayload(source: MutableUnit, weaponName: String, position: Position, height: Double,
                                heading: Double, target: Position, stage: ScoutPayloadStage) {
        val state = checkNotNull(source.namedWeapons).getValue(weaponName)
        val contract = checkNotNull(scenario?.combatContract)
        val objectId = checkNotNull(source.objectId)
        val phase = OrdinaryWeapon.sample(contract.worldSeed, combatFrame, objectId, source, state.randomCounter, 0, 1000, state) * .001f
        state.randomCounter++
        fun spread(axis: Double, salt: Int): Double {
            val value = axis.toFloat() + OrdinaryWeapon.sample(contract.worldSeed, combatFrame, objectId, source,
                salt, -500, 500, state) / 100f
            state.randomCounter = (state.randomCounter.toFloat() + value).toInt()
            return value.toDouble()
        }
        val guided = stage.guided
        val destination = if (guided) Position(spread(target.x, 2), spread(target.y, 3)) else target
        val id = nextObjectId
        nextObjectId = Math.addExact(id, 1L)
        val shot = MutableProjectile(id, source.unitId, null, position, ScreenPlane.normalize(heading), stage.life,
            Position(0.0, 0.0), phase, stage.speed, if (guided) 123.0 else 0.0, height, nonDeflectable = guided,
            sourceWeaponName = weaponName,
            acceleration = if (guided) com.rustedwarfare.reforge.core.content.LinearProjectileAcceleration(6.0, .05) else null,
            ballistic = if (guided) com.rustedwarfare.reforge.core.content.CustomBallisticProfile(20f, 50f) else null,
            appearance = ProjectileAppearance.SCOUT_PAYLOAD, scoutPayload = ScoutPayload(destination, stage))
        projectiles += shot
        pendingProjectileEvents += ProjectileEventObservation("born", projectileObservation(shot))
    }

    private fun updateScoutPayload(shot: MutableProjectile, payload: ScoutPayload, delta: Double) {
        if (shot.intercepted) {
            pendingProjectileEvents += ProjectileEventObservation("intercepted", projectileObservation(shot))
            projectiles.remove(shot)
            return
        }
        val hit = payload.stage.guided && BallisticFlight.move(shot, payload.target, 0f, delta)
        shot.age += delta.toFloat()
        if (hit) {
            pendingProjectileEvents += ProjectileEventObservation("impact", projectileObservation(shot))
            units[shot.sourceUnitId]?.let { UnitRegistration.scout(steps.current, checkNotNull(scenario), it, shot) }
        }
        val expired = shot.remainingLife == 0.0
        if (expired) {
            pendingProjectileEvents += ProjectileEventObservation("expired", projectileObservation(shot))
            units[shot.sourceUnitId]?.let { source ->
                val weapon = checkNotNull(shot.sourceWeaponName)
                when (payload.stage) {
                    ScoutPayloadStage.MOTHER -> for (offset in listOf(15.0, -180.0)) {
                        addScoutPayload(source, weapon, shot.position, shot.height, shot.heading + offset, payload.target, ScoutPayloadStage.SPLIT)
                    }
                    ScoutPayloadStage.SPLIT ->
                        addScoutPayload(source, weapon, shot.position, shot.height, shot.heading, payload.target, ScoutPayloadStage.FAST_TURN)
                    ScoutPayloadStage.FAST_TURN -> Unit
                }
            }
        }
        if (hit || expired) projectiles.remove(shot)
    }

    private fun updatePointDefense(unit: MutableUnit, delta: Double) {
        if (unit.pointDefense == null) return
        val teams = checkNotNull(scenario).players.associate { it.playerId to it.teamId }
        val shot = PointDefense.advance(unit, delta, projectiles, units, teams) ?: return
        pendingProjectileEvents += ProjectileEventObservation("deflected", projectileObservation(shot),
            interceptorUnitId = unit.unitId)
    }

    private fun updateProjectiles(delta: Double, existingIds: Set<Long>) {
        steps.current.temporaryVision = steps.current.temporaryVision.map { it.copy(remaining = it.remaining - delta.toFloat()) }
            .filter { it.remaining >= 0f }
        // Retain targets through this step so that a removed target's final pose remains available.
        val trackedTargets = if (projectiles.any { it.interception != null }) projectiles.associateBy { it.objectId } else emptyMap()
        val projectileUpdateLimit = nextObjectId
        updateProjectileBatch(delta, projectiles.filter { it.objectId in existingIds }.sortedBy { it.objectId }, trackedTargets)
        updateProjectileBatch(delta, projectiles.filter {
            it.objectId !in existingIds && (it.scoutPayload == null || it.objectId < projectileUpdateLimit)
        }.sortedBy { it.objectId }, trackedTargets)
    }

    private fun updateProjectileBatch(delta: Double, batch: List<MutableProjectile>, trackedTargets: Map<Long, MutableProjectile>) {
        for (projectile in batch) {
            val scout = projectile.scoutPayload
            if (scout != null) {
                updateScoutPayload(projectile, scout, delta)
                continue
            }
            val course = projectile.interception
            if (course != null) {
                updateInterceptor(projectile, course, trackedTargets[course.targetId], delta)
                continue
            }
            if (projectile.beam) {
                units[projectile.sourceUnitId]?.takeIf { it.alive }?.let { source ->
                    val name = checkNotNull(projectile.sourceWeaponName)
                    val weapon = checkNotNull(source.definition.combat?.namedWeapons).byName.getValue(name)
                    projectile.position = NamedWeaponRuntime.muzzle(source, weapon, checkNotNull(source.namedWeapons).getValue(name))
                    projectile.height = source.height
                }
                units[projectile.targetUnitId]?.let { target ->
                    val radius = target.definition.radius.toFloat() * .4f
                    val x = GroundMath.sin((360f * projectile.scatter + projectile.age) % 360f) * radius
                    val y = GroundMath.sin((360f * projectile.scatter + projectile.age * 1.5f) % 360f) * radius
                    projectile.offset = Position(x.toDouble(), y.toDouble())
                    projectile.beamSegment = BeamSegment(Position(target.position.x + x, target.position.y + y), target.height)
                }
            }
            if ((projectile.lightning || projectile.beam) && projectile.hitApplied) {
                projectile.age += delta.toFloat()
                projectile.remainingLife = maxOf(0.0, projectile.remainingLife - delta)
                if (projectile.remainingLife == 0.0) {
                    pendingProjectileEvents += ProjectileEventObservation("expired", projectileObservation(projectile))
                    projectiles.remove(projectile)
                }
                continue
            }
            if (projectile.expansion != null) {
                updateDeathExplosion(projectile, delta)
                continue
            }
            if (projectile.missile != null && units[projectile.targetUnitId]?.alive == false) retargetMissile(projectile)
            if (projectile.retargetOnDeath && units[projectile.targetUnitId]?.alive == false) retargetOrdinaryMissile(projectile)
            val target = units[projectile.targetUnitId]
            if (target == null && projectile.ground == null && projectile.nuclearTarget == null) {
                projectile.age += delta.toFloat()
                pendingProjectileEvents += ProjectileEventObservation("target-ended", projectileObservation(projectile))
                projectiles.remove(projectile)
                continue
            }
            val hit = if (projectile.nuclearTarget != null) BallisticFlight.move(projectile, projectile.nuclearTarget, 0f, delta)
            else if (projectile.instant && projectile.ground == null) {
                projectile.remainingLife = maxOf(0.0, projectile.remainingLife - delta)
                true
            } else if (projectile.ground != null) GroundWeapon.move(projectile, delta)
                else if (projectile.missile != null) NativeMissile.move(projectile, checkNotNull(target), delta)
                else if (projectile.ballistic != null) BallisticFlight.move(projectile, checkNotNull(target), delta)
                else OrdinaryWeapon.move(projectile, checkNotNull(target), delta)
            projectile.age += delta.toFloat()
            if (projectile.revealFogPending && (hit || projectile.ballisticReachedApex && projectile.height < 30.0)) {
                val source = checkNotNull(units[projectile.sourceUnitId])
                projectile.revealFogPending = false
                steps.current.temporaryVision = steps.current.temporaryVision + TemporaryVision(source.ownerPlayerId, projectile.position)
            }
            if (hit) {
                if (projectile.nuclearTarget != null) {
                    projectile.position = projectile.nuclearTarget
                    projectile.height = 0.0
                    projectile.expansion = AreaExpansion(com.rustedwarfare.reforge.core.content.DeathExplosionProfile(5400f, 250f, 75f), 75f)
                    projectile.hitApplied = true
                    pendingProjectileEvents += ProjectileEventObservation("impact", projectileObservation(projectile))
                    steps.current.pendingSoundTriggers += SoundTriggerObservation("reforge:events/nuke-explode", projectile.position, 1f)
                    updateDeathExplosion(projectile, delta)
                    continue
                }
                if (projectile.ground != null) applyAreaDamage(projectile)
                val appliedDamage = if (projectile.ground == null) applyDamage(checkNotNull(target), projectile.damage, projectile.damageModifiers) else null
                if (projectile.splash != null) applyOrdinarySplash(projectile)
                if (projectile.appearance == ProjectileAppearance.BOMBER_BOMB) {
                    steps.current.pendingSoundTriggers += SoundTriggerObservation("reforge:events/bomber-impact", projectile.position, .5f)
                }
                pendingProjectileEvents += ProjectileEventObservation("impact", projectileObservation(projectile), appliedDamage)
                projectile.hitApplied = true
                if (!projectile.lightning && !projectile.beam) projectiles.remove(projectile)
            } else if (projectile.remainingLife == 0.0) {
                pendingProjectileEvents += ProjectileEventObservation("expired", projectileObservation(projectile))
                projectiles.remove(projectile)
            } else if (projectile.intercepted) {
                pendingProjectileEvents += ProjectileEventObservation("intercepted", projectileObservation(projectile))
                projectiles.remove(projectile)
            }
        }
    }

    private fun updateInterceptor(shot: MutableProjectile, course: InterceptionCourse, target: MutableProjectile?, delta: Double) {
        val track = target?.let { course.copy(position = it.position, height = it.height.toFloat()) } ?: course
        shot.interception = track
        val hit = BallisticFlight.move(shot, track.position, track.height, delta)
        shot.age += delta.toFloat()
        if (hit) {
            if (target != null && ProjectileInterception.isInFlight(target) && projectiles.remove(target)) {
                target.intercepted = true
                pendingProjectileEvents += ProjectileEventObservation("intercepted", projectileObservation(target),
                    interceptorUnitId = shot.sourceUnitId)
                steps.current.pendingSoundTriggers += SoundTriggerObservation("reforge:events/nuke-intercept", target.position, .8f)
            }
            // The impact call can apply its own expanding area before removal. It has no later area ticks.
            val remaining = maxOf(0f, 75f - delta.toFloat())
            applyGroundArea(shot.sourceUnitId, track.launchTarget, 0f, 100f, 60f, 60f * (1f - remaining / 75f))
            pendingProjectileEvents += ProjectileEventObservation("impact", projectileObservation(shot))
            projectiles.remove(shot)
        } else if (shot.remainingLife == 0.0) {
            pendingProjectileEvents += ProjectileEventObservation("expired", projectileObservation(shot))
            projectiles.remove(shot)
        }
    }

    private fun updateDeathExplosion(projectile: MutableProjectile, delta: Double) {
        val area = checkNotNull(projectile.expansion)
        area.remaining = maxOf(0f, area.remaining - delta.toFloat())
        area.radius = area.profile.radius * (1f - area.remaining / area.profile.expandTime)
        projectile.age += delta.toFloat()
        projectile.remainingLife = maxOf(0.0, projectile.remainingLife - delta)
        val source = checkNotNull(units[projectile.sourceUnitId])
        val players = checkNotNull(scenario).players
        val team = players.single { it.playerId == source.ownerPlayerId }.teamId
        for (target in orderedUnits()) {
            if (!target.alive || target.carriedBy != null || target.health == null || target.unitId in area.hitUnitIds ||
                target.height.toFloat() < -5f && projectile.height.toFloat() >= -2f ||
                projectile.nuclearTarget == null && target.ownerPlayerId != source.ownerPlayerId && team != null &&
                    players.single { it.playerId == target.ownerPlayerId }.teamId == team) continue
            val squared = GroundMath.square(projectile.position.x.toFloat(), projectile.position.y.toFloat(),
                target.position.x.toFloat(), target.position.y.toFloat())
            require(squared.isFinite()) { "Death area geometry must remain finite" }
            if (squared > area.radius * area.radius) continue
            val distance = GroundMath.sqrt(squared)
            if (distance > area.radius) continue
            area.hitUnitIds += target.unitId
            val falloff = minOf(1f, ((1f - distance / area.profile.radius).toDouble() + .1).toFloat())
            applyDamage(target, (area.profile.damage * falloff).toDouble())
        }
        if (area.remaining == 0f) {
            pendingProjectileEvents += ProjectileEventObservation(if (projectile.nuclearTarget == null) "impact" else "area-ended", projectileObservation(projectile))
            projectiles.remove(projectile)
        }
    }

    private fun retargetMissile(shot: MutableProjectile) {
        val profile = checkNotNull(shot.missile)
        val source = units[shot.sourceUnitId] ?: return
        if (source.combat == null) return
        val teams = checkNotNull(scenario).players.associate { it.playerId to it.teamId }
        val preferred = units[source.combat?.targetId]?.takeIf { it.alive }
            ?: units[source.attackCommand?.targetUnitId]?.takeIf { it.alive }
        val x = shot.position.x.toFloat() + GroundMath.cos(shot.missileRawAz) * profile.retargetForward
        val y = shot.position.y.toFloat() + GroundMath.sin(shot.missileRawAz) * profile.retargetForward
        var nearest = profile.retargetRadius * profile.retargetRadius
        var chosen: MutableUnit? = null
        for (candidate in checkNotNull(missileTargets).candidates(x, y, profile.retargetRadius, units)) {
            if (!candidate.alive || candidate.carriedBy != null || candidate.health == null || candidate.ownerPlayerId == source.ownerPlayerId ||
                teams[source.ownerPlayerId]?.let { it == teams[candidate.ownerPlayerId] } == true) continue
            val distance = GroundMath.square(x, y, candidate.position.x.toFloat(), candidate.position.y.toFloat())
            require(distance.isFinite()) { "Missile candidate distance must remain finite" }
            if (distance >= profile.retargetRadius * profile.retargetRadius) continue
            if (chosen == null || distance < nearest || candidate === preferred) {
                chosen = candidate; nearest = distance
            }
        }
        chosen?.let { shot.targetUnitId = it.unitId }
    }

    private fun retargetOrdinaryMissile(shot: MutableProjectile) {
        val source = units[shot.sourceUnitId]?.takeIf { it.alive } ?: return
        val catalog = source.definition.combat?.namedWeapons ?: return
        val weapon = catalog.byName[shot.sourceWeaponName] ?: return
        val players = checkNotNull(scenario).players
        val forward = policies.coordinates.displacement(shot.heading, 15.0)
        val center = Position(shot.position.x + forward.x, shot.position.y + forward.y)
        require(center.x.isFinite() && center.y.isFinite()) { "Ordinary missile search center must remain finite" }
        val preferred = units[source.primaryCombatTargetId]
        var nearest = 120.0 * 120.0
        var chosen: MutableUnit? = null
        for (candidate in orderedUnits()) {
            if (!CombatTargetQuery.canAttackAirCategory(source, candidate, checkNotNull(scenario).map) ||
                !NamedWeaponRuntime.qualifies(source, candidate, weapon, players, policies.coordinates, checkNotNull(scenario).map, checkRange = false) ||
                weapon.attachedTo?.let { parent -> !NamedWeaponRuntime.qualifies(source, candidate,
                    catalog.byName.getValue(parent), players, policies.coordinates, checkNotNull(scenario).map, checkRange = false) } == true) continue
            val distance = policies.coordinates.distanceSquared(center, candidate.position)
            require(distance.isFinite()) { "Ordinary missile search distance must remain finite" }
            if (distance >= 120.0 * 120.0) continue
            if (distance < nearest || candidate === preferred) {
                chosen = candidate
                nearest = distance
            }
        }
        chosen?.let { shot.targetUnitId = it.unitId }
    }

    private fun applyOrdinarySplash(projectile: MutableProjectile) {
        val profile = checkNotNull(projectile.splash)
        val source = checkNotNull(units[projectile.sourceUnitId])
        val players = checkNotNull(scenario).players
        val team = players.single { it.playerId == source.ownerPlayerId }.teamId
        val x = projectile.position.x.toFloat()
        val y = projectile.position.y.toFloat()
        val height = projectile.height.toFloat()
        for (target in orderedUnits()) {
            if (!target.alive || target.carriedBy != null || target.health == null || target.ownerPlayerId == source.ownerPlayerId ||
                team != null && players.single { it.playerId == target.ownerPlayerId }.teamId == team) continue
            if (CombatTargetQuery.isFlying(target) != (height >= 5f) ||
                target.height.toFloat() < -5f && height >= -2f) continue
            val squared = GroundMath.square(x, y, target.position.x.toFloat(), target.position.y.toFloat())
            require(squared.isFinite()) { "Ordinary splash geometry must remain finite" }
            if (squared > profile.radius * profile.radius) continue
            val distance = GroundMath.sqrt(squared)
            if (distance > profile.radius) continue
            val falloff = minOf(1f, ((1f - distance / profile.radius).toDouble() + .1).toFloat())
            applyDamage(target, (profile.damage * falloff).toDouble())
        }
    }

    private fun applyAreaDamage(projectile: MutableProjectile) {
        val ground = checkNotNull(projectile.ground)
        val profile = ground.profile
        val center = if (profile.resolveAtSnapshot) Position(ground.x.toDouble(), ground.y.toDouble()) else projectile.position
        val height = if (profile.resolveAtSnapshot) ground.height else projectile.height.toFloat()
        applyGroundArea(projectile.sourceUnitId, center, height, profile.areaDamage, profile.areaRadius, profile.areaRadius)
    }

    private fun applyGroundArea(sourceId: String, center: Position, height: Float, damage: Float, radius: Float, currentRadius: Float) {
        val source = checkNotNull(units[sourceId])
        val players = checkNotNull(scenario).players
        val team = players.single { it.playerId == source.ownerPlayerId }.teamId
        val x = center.x.toFloat()
        val y = center.y.toFloat()
        for (target in orderedUnits()) {
            if (!target.alive || target.carriedBy != null || target.health == null || target.ownerPlayerId == source.ownerPlayerId ||
                team != null && players.single { it.playerId == target.ownerPlayerId }.teamId == team) continue
            if (CombatTargetQuery.isFlying(target) != (height >= 5f) ||
                target.height.toFloat() < -5f && height >= -2f) continue
            val distanceSquared = GroundMath.square(x, y, target.position.x.toFloat(), target.position.y.toFloat())
            require(distanceSquared.isFinite()) { "Area geometry must remain finite" }
            if (distanceSquared > currentRadius * currentRadius) continue
            val distance = GroundMath.sqrt(distanceSquared)
            if (distance > currentRadius) continue
            val falloff = minOf(1f, ((1f - distance / radius).toDouble() + .1).toFloat())
            applyDamage(target, (damage * falloff).toDouble())
        }
    }

    /** Direct and area hits share damage, death, queue refunds and later building-layer removal. */
    private fun applyDamage(target: MutableUnit, damage: Double,
                            modifiers: com.rustedwarfare.reforge.core.content.ProjectileDamageModifiers =
                                com.rustedwarfare.reforge.core.content.ProjectileDamageModifiers()): Double {
        if (!target.alive || target.deathPending || target.health == null) return 0.0
        require(damage.isFinite() && damage >= 0.0) { "Damage must remain finite and nonnegative" }
        val before = checkNotNull(target.health)
        val targetDamage = damage * if (target.definition.building != null ||
            target.definition.directHitTargetKind == DirectHitTargetKind.NATIVE_BUILDING) modifiers.building else 1.0
        val armor = target.definition.durability?.armor
        val armoredDamage = if (armor != null && armor.reduction > 0.0 && targetDamage > armor.minimumDamage)
            maxOf(armor.minimumDamage, targetDamage - armor.reduction) else targetDamage
        val constructionDamage = if (target.constructionProgress < 1f) armoredDamage * 1.75 else armoredDamage
        require(constructionDamage.isFinite()) { "Construction damage must remain finite" }
        val applied = target.definition.durability?.maxDamagePerHit?.let { minOf(constructionDamage, it) } ?: constructionDamage
        val shieldBefore = target.shield.toDouble()
        val shieldCost = applied * modifiers.shieldDamage
        val absorbed = minOf(shieldBefore, shieldCost)
        val deflected = if (shieldBefore <= 0.0) 0.0 else
            (if (shieldBefore < shieldCost) shieldBefore else applied) * modifiers.shieldDeflection
        target.shield = (target.shield - absorbed).toFloat()
        if (absorbed > 0.0) {
            target.shieldDisplayDamage += absorbed.toFloat()
            pendingEvents += Event("shield-damaged", target.unitId)
        }
        target.health = maxOf(0.0, before - maxOf(0.0, applied - deflected))
        pendingEvents += Event("unit-damaged", target.unitId)
        if (!target.alive) processDeath(target)
        return before - checkNotNull(target.health)
    }

    private fun processDeath(target: MutableUnit) {
            if (target.definition.nuclearSilo != null) {
                if (target.ordnance.queue.isNotEmpty()) pendingEvents += Event("unit-action-refunded", target.unitId)
                productionLedger = NuclearOrdnance.discard(target, checkNotNull(productionLedger))
            }
            Transport.onDeath(target, units, checkNotNull(scenario).map, deferCargo = NautilusMotion.slowDeath(target))
            missileTargets = missileTargets?.remove(target.unitId)
            if (target.definition.production != null) {
                if (checkNotNull(productionLedger).upgradeQueue(target.unitId).isNotEmpty())
                    pendingEvents += Event("upgrade-refunded", target.unitId)
                if (checkNotNull(productionLedger).queue(target.unitId).isNotEmpty())
                    pendingEvents += Event("production-refunded", target.unitId)
                if (checkNotNull(productionLedger).actionQueue(target.unitId).any { it.cost > 0 })
                    pendingEvents += Event("unit-action-refunded", target.unitId)
                productionLedger = checkNotNull(productionLedger).destroyFactory(target.unitId)
            }
            refundUpgrades(target)
            target.attackCommand = null
            target.bomberApproach = null
            target.taskQueue = UnitTaskQueue()
            target.movementRequested = false
            target.cancelRoute()
            if (!NautilusMotion.slowDeath(target) &&
                (target.definition.energy == null || target.definition.movement.type != MovementType.AIR)) {
                target.speed = 0.0
                target.flightVelocity = Position(0.0, 0.0)
            }
            if (target.definition.movement.type == MovementType.AIR && target.amphibiousState == null &&
                target.definition.transport != com.rustedwarfare.reforge.core.content.TransportProfile.NATIVE_DROPSHIP &&
                target.definition.energy == null) target.height = 0.0
            target.angularVelocity = 0.0
            target.clearWeaponTargets()
            pendingEvents += Event("unit-died", target.unitId)
            target.definition.deathExplosion?.takeIf { target.constructionProgress >= 1f }?.let { profile ->
                val id = nextObjectId
                nextObjectId = Math.addExact(nextObjectId, 1L)
                val explosion = MutableProjectile(id, target.unitId, null, target.position, 0.0, 99.0,
                    Position(0.0, 0.0), 0f, 0.0, 0.0, instant = true, nonDeflectable = true,
                    expansion = AreaExpansion(profile, profile.expandTime))
                projectiles += explosion
                pendingProjectileEvents += ProjectileEventObservation("born", projectileObservation(explosion))
            }
    }

    private fun updateUnit(unit: MutableUnit, map: MapDefinition, delta: Double, executionStep: Long,
                           motionSamples: MutableList<MotionStepSample>?) {
        if (!BuildingWork.isReady(unit)) return
        val movement = UnitLocomotion.movement(unit)
        if (CustomUnitActions.movementLocked(unit, unitActionHead(unit)) || movement.type == MovementType.NONE) {
            unit.speed = 0.0
            unit.flightVelocity = Position(0.0, 0.0)
            unit.movementState = MovementState.IDLE
            return
        }
        val maximumSpeed = movement.moveSpeed * unit.terrainSpeedMultiplier
        val air = movement.type == MovementType.AIR
        val sliding = movement.slidingMode
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
            UnitCombat.profile(unit)?.let { weapon ->
                policies.coordinates.distanceSquared(unit.position, attackTarget.position) >= weapon.attackRange * weapon.attackRange
            } == true
        val workTask = unit.tasks.firstOrNull()?.takeIf {
            it.kind == UnitTaskKind.BUILD || it.kind == UnitTaskKind.CONSTRUCT || it.kind == UnitTaskKind.RECLAIM }
        val workTarget = if (workTask?.kind == UnitTaskKind.BUILD) workTask.target
            else workTask?.targetUnitId?.let { units[it]?.takeIf { targetUnit -> targetUnit.alive }?.position }
        val approachingWork = workTarget != null && unit.definition.builder?.let { builder ->
            policies.coordinates.distanceSquared(unit.position, workTarget) > builder.range.toDouble() * builder.range
        } == true
        val approachingLoad = unit.tasks.firstOrNull()?.kind in setOf(UnitTaskKind.LOAD_INTO, UnitTaskKind.LOAD_UP)
        if (target != null && !approachingLoad && unit.tasks.firstOrNull()?.kind != UnitTaskKind.PATROL) {
            val commandDistanceSquared = policies.coordinates.distanceSquared(unit.position, target)
            if (commandDistanceSquared < 1681.0) unit.nearTargetTime += delta
            val completionRadius = when {
                unit.nearTargetTime > 340.0 -> 36.0
                unit.nearTargetTime > 240.0 -> 16.0
                else -> 7.0
            }
            if (!approachingBuilding && policies.capture.inside(commandDistanceSquared, completionRadius)) {
                unit.cancelRoute()
                if (unit.tasks.firstOrNull()?.kind == UnitTaskKind.MOVE) unit.taskQueue = unit.taskQueue.pop()
                pendingEvents += Event("movement-arrived", unit.unitId)
                target = null
                normalCompleted = true
            }
        }
        if (target != null && air) {
            unit.requestMovement(logicalRouteTarget(unit, target))
            val d2 = policies.coordinates.distanceSquared(unit.position, target)
            val ordinaryBody = unit.typeId == BomberRules.ID || unit.amphibiousState != null ||
                unit.definition.transport == com.rustedwarfare.reforge.core.content.TransportProfile.NATIVE_DROPSHIP
            val difference = if (ordinaryBody)
                BodyMotion.turnOrdinary(unit, target, delta, policies.steering, policies.coordinates)
                else policies.steering.turn(unit, target, delta, policies.coordinates)
            val fraction = policies.propulsion.targetFraction(movement, unit.speed, d2,
                if (ordinaryBody) difference else 0.0, true, contract.targetRadius)
            requestedFraction = if (ordinaryBody && unit.reversing)
                -fraction * movement.reverseSpeedPercentage else fraction
            speedTarget = maximumSpeed * requestedFraction
            aimForSample = target
            finalForSample = true
            differenceForSample = difference
        }
        if (target != null && !air) {
            unit.requestMovement(logicalRouteTarget(unit, target))
            val temporaryBirth = unit.birthRemaining > 0f
            val local = if (temporaryBirth) null else unit.ensureLocalMotion()
            val steeringTarget = local?.aim ?: target
            val finalPoint = temporaryBirth || (unit.pathPoints.size == 1 && checkNotNull(local).isLast)
            val d2 = policies.coordinates.distanceSquared(unit.position, steeringTarget)
            val diff = when {
                unit.mechState != null -> MechMotion.turn(unit, steeringTarget, delta, policies.steering, policies.coordinates)
                movement.type == MovementType.WATER -> BodyMotion.turnOrdinary(unit, steeringTarget, delta, policies.steering, policies.coordinates)
                else -> policies.steering.turn(unit, steeringTarget, delta, policies.coordinates)
            }
            val propulsionDistance = if ((approachingBuilding || approachingWork || approachingLoad) && finalPoint) maxOf(d2, 16.0) else d2
            val fraction = policies.propulsion.targetFraction(movement, unit.speed, propulsionDistance,
                if (movement.ignoringBody) 0.0 else diff, finalPoint, contract.targetRadius)
            aimForSample = steeringTarget
            finalForSample = finalPoint
            differenceForSample = diff
            requestedFraction = if (unit.reversing) -fraction * movement.reverseSpeedPercentage else fraction
            speedTarget = maximumSpeed * requestedFraction
            if (temporaryBirth) {
                // cn supplies the command aim directly, without normal route-node lookahead.
            } else if (unit.pathPoints.size > 1 && policies.capture.inside(
                    policies.coordinates.distanceSquared(unit.position, unit.pathPoints.first()), contract.pathPointRadius) &&
                PlanningSegmentClearance.allows(planningTerrainFor(unit, map), Transport.queryPosition(unit, map),
                    unit.pathPoints[1], Transport.boundaryRadius(unit))) {
                unit.pathPoints.removeAt(0)
                unit.localMotion = null
                unit.localProgressTime = 0.0
            } else {
                val terrain = planningTerrainFor(unit, map)
                val progress = LocalMotionMaintenance.advance(checkNotNull(local), unit.localProgressTime, delta, fraction > 0.0,
                    d2, !finalPoint && policies.capture.inside(d2, 16.0), unit.terrainCorrectionTime,
                    { policies.coordinates.distanceSquared(unit.position, it) },
                    { PlanningSegmentClearance.allows(terrain, Transport.queryPosition(unit, map), it, Transport.boundaryRadius(unit)) })
                unit.localMotion = progress.segment
                unit.localProgressTime = progress.activeTime
            }
        }

        unit.mechState = unit.mechState?.copy(hadMotionAim = aimForSample != null)
        Transport.recordMotionGate(unit, aimForSample != null)
        val displacement = if (sliding) {
            val desiredVelocity = aimForSample?.let {
                // Sliding already aims at the route; reversing changes body orientation,
                // while a signed speed is needed only for body-directed propulsion below.
                policies.coordinates.displacement(policies.coordinates.desired(unit.position, it), kotlin.math.abs(speedTarget))
            } ?: Position(0.0, 0.0)
            unit.flightVelocity = FlightMotion.velocity(movement, unit.flightVelocity, desiredVelocity, requestedFraction, delta, maximumSpeed)
            unit.speed = kotlin.math.hypot(unit.flightVelocity.x, unit.flightVelocity.y)
            Position(unit.flightVelocity.x * delta, unit.flightVelocity.y * delta)
        } else {
            val rate = if (unit.speed < speedTarget) movement.moveAccelerationSpeed else movement.moveDecelerationSpeed
            // Non-sliding LAND B/C advance the propulsion proportion.
            unit.speed = approach(unit.speed, speedTarget, rate * maximumSpeed * delta)
            if (unit.typeId == BomberRules.ID) {
                unit.flightVelocity = policies.coordinates.displacement(unit.heading, unit.speed)
                Position(unit.flightVelocity.x * delta, unit.flightVelocity.y * delta)
            } else policies.coordinates.displacement(unit.heading, unit.speed * delta)
        }
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
        if (!Transport.containsPosition(unit, map, candidate)) {
            blockAtMapBoundary(unit)
            recordMotion(null, MotionTerrainDisposition.CANDIDATE_OUTSIDE_MAP)
            return
        }
        val resolved = if (air) candidate else if (unit.birthRemaining > 0f) TerrainMotion.resolve(map, unit.position, candidate, movementType = movement.type)
            else TerrainMotion.resolve(buildingTerrain?.collisionMap ?: map, unit.position, candidate,
                map.takeIf { buildingTerrain != null }, movement.type, unit.nativeTransportPlacement)
        if (!Transport.containsPosition(unit, map, resolved)) {
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
        // The normal custom I() gate is open for this alive, uncarried, nonbuilding unit.
        // ad.b is a usable locomotion aim, even when collision prevented displacement.
        // Match the proven base-update -> custom multiplier order; Stop does not reset j.
        if (movement.type == MovementType.OVER_CLIFF && aimForSample != null) {
            val column = MapBounds.cell(unit.position.x, map.tileWidth, map.widthTiles)
            val row = MapBounds.cell(unit.position.y, map.tileHeight, map.heightTiles)
            unit.terrainSpeedMultiplier = if (map.isCliff(column, row)) .7 else 1.0
        }
        unit.movementState = when {
            unit.moveTarget != null -> MovementState.MOVING
            unit.speed != 0.0 -> MovementState.DECELERATING
            else -> MovementState.IDLE
        }
        recordMotion(resolved, if (resolved == candidate) MotionTerrainDisposition.DIRECT else MotionTerrainDisposition.CORRECTED)
    }

    private fun blockAtMapBoundary(unit: MutableUnit) {
        unit.speed = 0.0
        unit.flightVelocity = Position(0.0, 0.0)
        unit.angularVelocity = 0.0
        unit.cancelRoute()
        unit.movementState = MovementState.BLOCKED
        pendingEvents += Event("movement-boundary-blocked", unit.unitId)
    }

    private fun recordObservation(): Observation = makeObservation(stepIndex, simulationTime, hasAdvanced, revision).also {
        latestObservation = it
    }

    private fun projectileObservation(projectile: MutableProjectile) = ProjectileObservation(projectile.objectId,
        projectile.sourceUnitId, projectile.targetUnitId, projectile.position, projectile.heading,
        projectile.remainingLife, projectile.offset, projectile.scatter, projectile.height, projectile.age,
        projectile.deflectionHealth, projectile.intercepted, projectile.ground?.let {
            GroundProjectileObservation(Position(it.x.toDouble(), it.y.toDouble()), it.height, projectile.verticalSpeed,
                projectile.groundDistanceSquared) } ?: projectile.interception?.let {
            GroundProjectileObservation(it.launchTarget, 0f, projectile.speed.toFloat()) } ?: projectile.nuclearTarget?.let {
            GroundProjectileObservation(it, 0f, projectile.speed.toFloat()) } ?: projectile.scoutPayload?.let {
            GroundProjectileObservation(it.target, 0f, projectile.speed.toFloat()) }, projectile.missile?.let {
            MissileObservation(projectile.speed.toFloat(), projectile.missileReachedApex, projectile.missileRawAz) },
        projectile.expansion?.let { AreaExpansionObservation(it.remaining, it.radius, it.hitUnitIds) }, projectile.sourceWeaponName,
        projectile.ballistic?.let { BallisticObservation(projectile.speed.toFloat(), projectile.ballisticReachedApex, projectile.ballisticRawAz) },
        projectile.appearance, projectile.lightningSegment, projectile.beamSegment?.copy(textureOffset = projectile.age * 2f), projectile.alwaysVisibleInFog)

    private fun makeObservation(observedStep: Long, observedTime: Double, advanced: Boolean, observedRevision: Long): Observation {
        val currentScenario = checkNotNull(scenario)
        val observation = Observation(
            sampleId = "${currentScenario.scenarioId}:$worldGeneration:${if (advanced) "step$observedStep" else "initial"}:$observedRevision",
            worldId = "${currentScenario.scenarioId}:$worldGeneration",
            scenarioId = currentScenario.scenarioId,
            stepIndex = observedStep,
            simulationTime = observedTime,
            units = orderedUnits().filter { !it.reclaimed }.sortedBy { it.unitId }.map { unit ->
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
                    shield = unit.definition.shield?.let { ShieldObservation(unit.shield, it.maximum, unit.shieldDisplayDamage) },
                    energy = unit.definition.energy?.let { EnergyObservation(unit.energy, it.maximum, unit.energyRecharging) },
                    unitActions = NuclearOrdnance.observe(unit) ?: CustomUnitActions.observe(unit, steps.current.actionClock, currentScenario, unitActionQueue(unit)),
                    nuclearDoors = unit.definition.nuclearSilo?.let { unit.ordnance.doors },
                    submarine = unit.definition.submarine?.let { SubmarineObservation(unit.submarineSurfaceIntent,
                        unit.submarineVerticalSpeed, SubmarineMotion.submerged(unit), unit.submarineSurfacePresentation,
                        SubmarineMotion.movement(unit).moveSpeed, UnitCombat.range(unit)) },
                    amphibiousJet = AmphibiousJetMotion.observation(unit, currentScenario.map),
                    carriedBy = unit.carriedBy, transport = Transport.observe(unit, units), transportExit = unit.transportExit,
                    alive = unit.alive,
                    attackTargetId = if (unit.tasks.firstOrNull()?.kind == UnitTaskKind.PATROL) unit.primaryCombatTargetId
                        else unit.attackCommand?.targetUnitId,
                    turretHeading = unit.combat?.turretHeading ?: unit.heading,
                    cooldown = unit.combat?.cooldown,
                    height = unit.height,
                    pointDefense = unit.pointDefense?.let { PointDefenseObservation(it.energy, it.exhausted) },
                    tasks = unit.tasks,
                    constructionProgress = unit.constructionProgress,
                    mechPose = MechMotion.pose(unit),
                    waterPose = if (unit.definition.movement.type == MovementType.WATER && unit.definition.fixedUnderwaterTarget == null)
                        WaterPose(unit.bodyReversing, unit.sinkSpeed, unit.waterSettled) else null,
                    hoverPose = if (unit.definition.movement.hover != null) HoverPose(unit.flightVelocity, unit.heightPhase) else null,
                    flightPose = if (unit.definition.movement.type == MovementType.AIR && unit.amphibiousState == null) FlightPose(
                        unit.flightVelocity, unit.height, unit.heightPhase, unit.rotorPhase,
                        policies.coordinates.normalize(unit.heading + unit.rotorPhase), unit.rotorHeightOffset,
                        if (unit.definition.energy != null) FlightFallPose(unit.sinkSpeed, unit.flightLanded,
                            unit.flightLandedInWater, unit.waterSettled) else null) else null,
                    constructionTargetId = unit.tasks.firstOrNull()?.takeIf {
                        unit.alive && unit.constructionWorking && (it.kind == UnitTaskKind.CONSTRUCT || it.kind == UnitTaskKind.RECLAIM) &&
                            units[it.targetUnitId]?.alive == true
                    }?.targetUnitId,
                )
            },
            events = pendingEvents,
            reclaimedUnitIds = if (currentScenario.maintenanceContract != null ||
                currentScenario.unitDefinitions.values.any { it.maintenance != null })
                orderedUnits().filter { unit -> unit.reclaimed }.map { unit -> unit.unitId }.sorted() else null,
            actionResults = pendingActionResults,
            projectiles = projectiles.map(::projectileObservation),
            projectileEvents = pendingProjectileEvents,
            soundTriggers = steps.current.pendingSoundTriggers,
            economy = productionLedger?.observe(),
            incomeStates = orderedUnits().filter { it.definition.income != null }.map {
                IncomeStateObservation(it.unitId, it.incomeElapsed) },
            upgrades = ordinaryUpgrades?.observe(checkNotNull(productionLedger)),
            construction = if (constructionNamespaceEnabled(currentScenario)) ConstructionObservation(buildingWork.clock,
                orderedUnits().filter { it.unitId.startsWith(CONSTRUCTED_UNIT_PREFIX) }.map {
                    ConstructedBuildingObservation(it.unitId, checkNotNull(it.objectId), it.updateKey, it.constructionProgress) },
                orderedUnits().filter { !it.reclaimed && (it.definition.builder != null || it.definition.maintenance != null) }.map {
                    ConstructionWorkerObservation(it.unitId, it.constructionElapsed, it.constructionRetryTime,
                        it.constructionPreparation, it.constructionWorking, it.previousWorkTargetActive,
                        it.singleNodePathCount, it.logicalRequestX, it.logicalRequestY, it.movementRequested,
                        Collections.unmodifiableList(it.pendingPathNodeCounts.toList()),
                        Collections.unmodifiableList(it.consumedPathNodeCounts.toList()), it.qBeforeDispatch) }, buildingWork.nonces) else null,
            production = productionLedger?.let {
                ProductionObservation(lastBirthSerial, orderedUnits().mapNotNull { unit ->
                    unit.producerId?.let { producer -> ProducedUnitObservation(unit.unitId,
                        checkNotNull(unit.objectId), unit.updateKey, producer, unit.birthRemaining) }
                })
            },
            combat = currentScenario.combatContract?.let { contract ->
                CombatObservation(combatFrame, nextObjectId, orderedUnits().flatMap { unit ->
                    unit.namedWeapons?.map { (name, state) -> NamedWeaponRuntime.observation(unit,
                        checkNotNull(unit.definition.combat?.namedWeapons).byName.getValue(name), state) }
                        ?: listOfNotNull(unit.combat?.let { UnitWeaponObservation(unit.unitId, checkNotNull(unit.objectId),
                        it.randomCounter, it.offset, it.targetId, it.angularVelocity,
                        OrdinaryWeapon.pivot(unit), OrdinaryWeapon.muzzle(unit), it.cooldown, it.oddShot,
                        idle = WeaponIdleObservation(it.idleBaseHeading, it.idleTimer, it.idleOffset,
                            it.idleDirection, it.idleReached, it.idleHold)) })
                })
            },
        )
        return observation
    }

}

