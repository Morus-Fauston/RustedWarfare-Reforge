package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.IncomeProfile
import com.rustedwarfare.reforge.core.content.BomberRules
import com.rustedwarfare.reforge.core.content.AttackMovementProfile
import com.rustedwarfare.reforge.core.content.MechFlameRules
import com.rustedwarfare.reforge.core.content.ModularSpiderRules
import com.rustedwarfare.reforge.core.content.ModularSpiderUtilityForms.ANTI_NUKE_AMMO_COST
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.NativeSeaFactoryProducts
import com.rustedwarfare.reforge.core.content.RoleCompositionRules
import com.rustedwarfare.reforge.core.content.TransportProfile
import com.rustedwarfare.reforge.core.content.foundationMovementType
import com.rustedwarfare.reforge.core.content.canConstruct
import com.rustedwarfare.reforge.core.content.DirectHitTargetKind
import com.rustedwarfare.reforge.core.content.ScoutBotRules
import com.rustedwarfare.reforge.core.content.ScoutPayloadStage
import com.rustedwarfare.reforge.core.content.NuclearSiloKind
import com.rustedwarfare.reforge.core.content.isTerminalExpiryPayload
import com.rustedwarfare.reforge.core.content.supportsExpirySteering
import com.rustedwarfare.reforge.core.path.AuthorizedNavigationTerrain
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import com.rustedwarfare.reforge.core.path.PlanningSegmentClearance
import com.rustedwarfare.reforge.core.path.PathDiagnosticsException
import com.rustedwarfare.reforge.core.path.PathSearchSample
import com.rustedwarfare.reforge.core.path.PathSearchCounters
import com.rustedwarfare.reforge.core.path.PathSearchOutcome
import com.rustedwarfare.reforge.core.path.KnowledgePathImpact
import com.rustedwarfare.reforge.core.path.movementBlocked
import com.rustedwarfare.reforge.core.path.PathSearchReason
import com.rustedwarfare.reforge.core.path.PlanningWorkSlice
import com.rustedwarfare.reforge.core.path.PlanningWorkSample
import com.rustedwarfare.reforge.core.sim.TacticalRouteRequest
import com.rustedwarfare.reforge.core.sim.TacticalRouteResolution
import com.rustedwarfare.reforge.core.knowledge.*
import java.util.Collections
import java.util.IdentityHashMap

class SimulationContext internal constructor(
    internal val movePlanningBudgetPerStep: Int = MOVE_PLANNING_WORK_PER_STEP,
    internal val routePreparationBudget: (Long) -> Int = { 8 },
    internal val pathPolicy: ClearanceAStarPolicy = ClearanceAStarPolicy.DEFAULT,
    internal val steeringFaultHook: ((MutableUnit) -> Unit)? = null,
) {
    private val steps = CandidateSteps()
    internal var trafficSeparationEnabled = true
    internal val trafficSeparationInterventions: Long get() = steps.current.separationInterventions
    var materializedObservationCount: Long = 0L
        private set
    internal var planningTerrainCacheHits: Long = 0L
        private set
    internal var planningTerrainCacheMisses: Long = 0L
        private set

    internal fun resetPlanningTerrainCacheStats() {
        planningTerrainCacheHits = 0L
        planningTerrainCacheMisses = 0L
    }
    private val soundAuthority = SoundAuthority()
    private var activeWorld: WorldHandle? = null
    val world: WorldHandle get() = checkNotNull(activeWorld) { "Simulation is closed" }
    private var scenario: Scenario? = null
    private var passageAdmission: PassageAdmission? = null
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
    private var spatialIndex: WorldSpatialIndex
        get() = steps.current.spatialIndex
        set(value) { steps.current.spatialIndex = value }
    internal val flowFields: ChunkedFlowFieldAdapter
        get() = steps.current.flowFields
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
    private val noFogPlanning get() = steps.current.noFogPlanning
    private val playerPlanning get() = steps.current.playerPlanning
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
    private var activeStepPlanningBudget: PathWorkBudget? = null
    private var activeStepPlanningSlices: MutableList<PlanningWorkSlice>? = null
    var hotspotCollector: SimHotspotCollector? = null
    val liveUnitCountForProfiler: Int get() = units.values.count { it.alive }
    val activeFlowFieldCountForProfiler: Int get() = steps.current.flowFields.activeSubscriberFieldCount()
    val cachedFlowFieldCountForProfiler: Int get() = steps.current.flowFields.activeFieldCount()

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
        val groundTypes = input.unitDefinitions.values.flatMap(UnitLocomotion::routeTypes)
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

    /** Separate opt-in avoids retaining per-step history for existing search-only consumers. */
    fun enablePlanningWorkDiagnostics(world: WorldHandle) {
        checkPathDiagnosticsWorld(world)
        if (steps.current.planningWorkDiagnostics != null)
            throw PathDiagnosticsException("already-enabled", "Planning work diagnostics are already enabled")
        steps.current.planningWorkDiagnostics = mutableListOf()
    }

    /** Trusted-host work accounting; never projected to player observations. */
    fun drainPlanningWorkDiagnostics(world: WorldHandle): List<PlanningWorkSample> {
        checkPathDiagnosticsWorld(world)
        val samples = steps.current.planningWorkDiagnostics
            ?: throw PathDiagnosticsException("not-enabled", "Planning work diagnostics are not enabled")
        val drained = Collections.unmodifiableList(ArrayList(samples))
        samples.clear()
        return drained
    }

    private fun checkPathDiagnosticsWorld(world: WorldHandle) {
        if (closed) throw PathDiagnosticsException("closed", "Simulation is closed")
        if (world !== activeWorld) throw PathDiagnosticsException("stale-world", "Path diagnostics belong to a different world")
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

    fun enableRouteContinuationDiagnostics(world: WorldHandle) {
        checkMotionDiagnosticsWorld(world)
        check(steps.current.routeContinuationDiagnostics == null) { "Route continuation diagnostics are already enabled" }
        steps.current.routeContinuationDiagnostics = mutableListOf()
    }

    fun drainRouteContinuationDiagnostics(world: WorldHandle): List<RouteContinuationSample> {
        checkMotionDiagnosticsWorld(world)
        val samples = checkNotNull(steps.current.routeContinuationDiagnostics) { "Route continuation diagnostics are not enabled" }
        val result = immutable(samples)
        samples.clear()
        return result
    }

    private fun checkMotionDiagnosticsWorld(world: WorldHandle) {
        if (closed) throw MotionDiagnosticsException("closed", "Simulation is closed")
        if (world !== activeWorld) throw MotionDiagnosticsException("stale-world", "Motion diagnostics belong to a different world")
    }

    companion object {
        private const val MOVE_PLANNING_WORK_PER_STEP = 512
        private const val FAST_STALL_TICKS = 12L
        private const val PRODUCED_UNIT_PREFIX = "produced:"
        private const val CONSTRUCTED_UNIT_PREFIX = "constructed:"
        private const val ROUTE_FAILURE_TICKS = 420L
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
        val groundTypes = input.unitDefinitions.values.flatMap(UnitLocomotion::routeTypes)
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
        steps.current.planningWorkDiagnostics?.clear()
        steps.current.planningWorkDiagnostics = null
        motionDiagnostics?.clear()
        motionDiagnostics = null
        steps.current.routeContinuationDiagnostics?.clear()
        steps.current.routeContinuationDiagnostics = null
        activeWorld = null
        this.scenario = null
        passageAdmission = null
        productionLedger = null
        missileTargets = null
        ordinaryUpgrades = null
        lastBirthSerial = 0L
        buildingWork = BuildingWork.initial()
        buildingTerrain = null
        buildingKnowledge = emptyMap()
        pendingBuildingDiscoveries = emptyMap()
        units = linkedMapOf()
        spatialIndex = WorldSpatialIndex()
        projectiles.clear()
        steps.current.temporaryVision = emptyList()
        pendingEvents.clear()
        pendingProjectileEvents.clear()
        steps.current.pendingSoundTriggers.clear()
        pendingActionResults.clear()
        steps.current.deferredWindowEvents.clear()
        steps.current.deferredWindowProjectileEvents.clear()
        steps.current.deferredWindowActionResults.clear()
        latestObservation = null
        committedObservation = null
        scheduledActions.clear()
        steps.current.movePlanning = MovePlanning()
        steps.current.flowFields = ChunkedFlowFieldAdapter()
        steps.current.pendingFlowStart.clear()
        actionIds.clear()
        actionResultOwners.clear()
        playerActionRevisions.clear()
        playerObservationProjectors.clear()
        noFogPlanning.clear()
        playerPlanning.clear()
        return CloseResult.Closed
    }

    fun requestGroupMove(actor: String, unitIds: Collection<String>, click: Position): GroupMoveRequest? {
        val input = scenario ?: return null
        if (!click.x.isFinite() || !click.y.isFinite() ||
            input.players.none { it.playerId == actor && it.control == PlayerControl.LOCAL }) return null
        val requested = unitIds.toHashSet()
        val members = orderedUnits().filter { it.unitId in requested && it.ownerPlayerId == actor && it.alive &&
            it.carriedBy == null && it.attachment == null && it.definition.movement.type != MovementType.NONE }
        if (members.size < 2) return null
        val allLandShared = members.all {
            (it.definition.movement.type == MovementType.LAND || it.definition.movement.type == MovementType.OVER_CLIFF) &&
                it.birthRemaining <= 0f
        }
        val allSameWater = members.all { it.definition.movement.type == MovementType.WATER }
        val hasRectWater = allSameWater && members.any {
            it.definition.movementCollision is MovementCollisionShape.Rectangle
        }
        val mixedThreshold = if (hasRectWater) 8 else ChunkedFlowFieldAdapter.MIN_GROUP_SIZE
        val largeMixedGroup = members.size >= mixedThreshold && members.count {
            val mt = it.definition.movement.type
            (mt == MovementType.LAND || mt == MovementType.OVER_CLIFF || mt == MovementType.HOVER || mt == MovementType.WATER) &&
                it.birthRemaining <= 0f
        } >= 2
        val shared = allLandShared || largeMixedGroup
        val mapForGroup = scenario?.map
        val gTw = mapForGroup?.tileWidth ?: 20
        val gTh = mapForGroup?.tileHeight ?: 20
        val maxRadiusByMovement = LinkedHashMap<MovementType, Double>()
        for (m in members) {
            val mt = UnitLocomotion.movement(m).type
            val r = ChunkedFlowFieldAdapter.flowPassabilityRadius(m, gTw, gTh)
            val prev = maxRadiusByMovement[mt]
            if (prev == null || r > prev) maxRadiusByMovement[mt] = r
        }
        return GroupMoveRequest(
            world, stepIndex.takeIf { hasAdvanced }, actor, click,
            members.mapTo(hashSetOf()) { it.unitId }, shared, maxRadiusByMovement,
        )
    }

    private fun movePlanningInput(map: MapDefinition, actions: List<MoveAction>, group: GroupMoveRequest?,
                                  repair: ManeuverRepair?, delta: Double): MovePlanningInput {
        val single = actions.singleOrNull()
        val byId = if (single == null) actions.associateBy { it.unitId } else emptyMap()
        val collectDiagnostics = pathDiagnostics != null
        val bodies = if (single != null) listOfNotNull(units[single.unitId]) else orderedUnits()
        val members = bodies.mapNotNull { unit ->
            val action = single ?: byId[unit.unitId] ?: return@mapNotNull null
            if (!unit.alive || unit.carriedBy != null || unit.attachment != null || unit.ownerPlayerId != action.actorPlayerId ||
                unit.definition.movement.type == MovementType.NONE || unit.tasks.firstOrNull()?.actionId != action.actionId)
                return@mapNotNull null
            if (repair != null && ((!unit.followsManeuverRoute &&
                    repair != ManeuverRepair.CONTACT &&
                    repair != ManeuverRepair.GROUP_SLOT &&
                    repair != ManeuverRepair.CONNECTION &&
                    repair != ManeuverRepair.KNOWLEDGE) ||
                    unit.moveTarget != action.target)) return@mapNotNull null
            val started = if (collectDiagnostics) System.nanoTime() else 0
            val terrain = planningTerrainFor(unit, map)
            val start = Transport.queryPosition(unit, map)
            val hull = HullRoute.forUnit(unit, map, maneuver = true)
            val preparation = if (collectDiagnostics) (System.nanoTime() - started).coerceAtLeast(0) else 0
            val body = unit.copy()
            if (repair == ManeuverRepair.SHORE_REJOIN) body.updateNavigation { it.withPathPoints(unit.pathPoints.take(2)) }
            MovePlanningMember(action, body, movePlanningPose(unit), start, routeRadius(unit), terrain,
                hull, unit.birthRemaining > 0f, preparation)
        }
        val segmentPeers = mutableListOf<MovePlanningOccupant>()
        val occupants = if (repair == ManeuverRepair.SHORE_REJOIN && members.isNotEmpty()) {
            val observer = checkNotNull(members.single().body)
            val next = observer.pathPoints.getOrNull(1)
            val contactLayer = UnitContact.layer(observer)
            if (next == null || contactLayer == null) emptyList() else {
                val swept = spatialIndex.querySwept(
                    SpatialLayer.fromContact(contactLayer),
                    observer.position,
                    next,
                    observer.definition.movementCollision.boundingRadius,
                )
                val out = ArrayList<MovePlanningOccupant>()
                for (i in 0 until swept.size) {
                    val other = swept[i]
                    val reach = observer.definition.movementCollision.boundingRadius + other.definition.movementCollision.boundingRadius
                    if (other.unitId == observer.unitId || !UnitContact.shareLayer(observer, other) ||
                        other.position.x !in minOf(observer.position.x, next.x) - reach..maxOf(observer.position.x, next.x) + reach ||
                        other.position.y !in minOf(observer.position.y, next.y) - reach..maxOf(observer.position.y, next.y) + reach ||
                        !knowsRouteOccupant(observer, other)
                    ) continue
                    out += MovePlanningOccupant(other.copy(), movePlanningPose(other), null, idle = other.moveTarget == null, obstructsRoute = true)
                }
                out
            }
        } else if (repair == ManeuverRepair.CONTACT && members.isNotEmpty()) {
            val observer = checkNotNull(members.single().body)
            if (observer.landingMember?.claimed != null) {
                val layerUnits = UnitContact.layer(observer)?.let { spatialIndex.allInLayer(SpatialLayer.fromContact(it)) } ?: emptyList()
                val out = ArrayList<MovePlanningOccupant>()
                val target = members.single().action.target
                for (i in 0 until layerUnits.size) {
                    val other = layerUnits[i]
                    if (other.unitId == observer.unitId || !UnitContact.shareLayer(observer, other) || !knowsRouteOccupant(observer, other)) continue
                    val peer = other.landingMember?.group === observer.landingMember?.group
                    val parkedPeer = isParkedPeer(observer, other)
                    val copiedOther = if (!peer || parkedPeer || UnitContact.inContact(observer, other)) other.copy() else null
                    if (copiedOther != null && (!peer || parkedPeer)) {
                        segmentPeers += MovePlanningOccupant(copiedOther, movePlanningPose(other), null,
                            idle = other.moveTarget == null, obstructsRoute = true)
                    }
                    if (copiedOther != null && (parkedPeer || UnitContact.inContact(observer, other) ||
                            (!peer && other.moveTarget == null))
                    ) {
                        out += MovePlanningOccupant(copiedOther, movePlanningPose(other), null, idle = false, obstructsRoute = true,
                            fixed = parkedPeer || !canDisplaceInContact(other) || !UnitContact.canPushMass(observer, other))
                    }
                }
                out
            } else {
                val contacts = routeContacts(observer)
                val out = ArrayList<MovePlanningOccupant>(contacts.size)
                for (i in 0 until contacts.size) {
                    val it = contacts[i]
                    out += MovePlanningOccupant(it.copy(), movePlanningPose(it), null, idle = false, obstructsRoute = true,
                        fixed = isParkedPeer(observer, it) || !canDisplaceInContact(it) || !UnitContact.canPushMass(observer, it))
                }
                out
            }
        } else if (group == null && members.isNotEmpty()) {
            val observer = checkNotNull(members.single().body)
            val landing = observer.landingMember
            val targetPos = members.single().action.target
            val candidates = if (repair == null && landing != null) {
                UnitContact.layer(observer)?.let { spatialIndex.allInLayer(SpatialLayer.fromContact(it)) } ?: emptyList()
            } else {
                val contactLayer = UnitContact.layer(observer)
                if (contactLayer == null) emptyList() else {
                    val ownR = observer.definition.movementCollision.boundingRadius
                    spatialIndex.queryBounds(
                        SpatialLayer.fromContact(contactLayer),
                        targetPos.x - ownR, targetPos.x + ownR,
                        targetPos.y - ownR, targetPos.y + ownR,
                    )
                }
            }
            val out = ArrayList<MovePlanningOccupant>()
            for (i in 0 until candidates.size) {
                val other = candidates[i]
                if (other.unitId == observer.unitId || !UnitContact.shareLayer(observer, other) ||
                    !knowsRouteOccupant(observer, other)
                ) continue
                val addSegmentPeer = repair == null && landing != null &&
                    (other.landingMember?.group !== landing.group || isParkedPeer(observer, other))
                val reach = observer.definition.movementCollision.boundingRadius + other.definition.movementCollision.boundingRadius
                val blocksTarget = !(canDisplaceInContact(other) && UnitContact.canPushMass(observer, other)) &&
                    ScreenPlane.distanceSquared(targetPos, other.position) <= reach * reach
                if (!addSegmentPeer && !blocksTarget) continue
                val copiedOther = other.copy()
                if (addSegmentPeer) {
                    val otherPendingMove = other.moveTarget == null &&
                        (steps.current.movePlanning.pending(other.unitId) || other.unitId in steps.current.pendingFlowStart)
                    segmentPeers += MovePlanningOccupant(copiedOther, movePlanningPose(other), null,
                        idle = other.moveTarget == null && !otherPendingMove, obstructsRoute = true)
                }
                if (blocksTarget) {
                    out += MovePlanningOccupant(copiedOther, movePlanningPose(other), null, idle = true, obstructsRoute = true, fixed = true)
                }
            }
            out
        } else if (members.isEmpty()) emptyList() else {
            val leaving = members.mapTo(hashSetOf()) { it.action.unitId }
            val observer = checkNotNull(members.first().body)
            val replacing = observer.groupMoveReservation?.slot.takeIf { repair == ManeuverRepair.GROUP_SLOT }
            val groupCandidates = if (replacing != null) {
                UnitContact.layer(observer)?.let { spatialIndex.allInLayer(SpatialLayer.fromContact(it)) } ?: emptyList()
            } else {
                (spatialIndex.allInLayer(SpatialLayer.SURFACE) + spatialIndex.allInLayer(SpatialLayer.SUBMERGED)).sortedBy { it.updateKey }
            }
            val out = ArrayList<MovePlanningOccupant>()
            for (i in 0 until groupCandidates.size) {
                val other = groupCandidates[i]
                if (other.unitId in leaving || UnitContact.layer(other) == null) continue
                if (replacing != null && !UnitContact.shareLayer(observer, other)) continue
                val known = knowsRouteOccupant(observer, other)
                val reserved = if (known) ownGroupReservation(observer, other)?.slot else null
                val idle = (other.moveTarget == null || replacing != null) && known
                if (!idle && reserved == null) continue
                val pose = movePlanningPose(other)
                val occupiedPose = if (idle || reserved == null) pose else pose.copy(position = reserved.position, heading = reserved.heading)
                val obstructsRoute = idle && replacing != null && GroupMoveDestinations.overlaps(observer, replacing, other)
                out += MovePlanningOccupant(other.copy(), occupiedPose, reserved, idle, obstructsRoute)
            }
            out
        }
        return MovePlanningInput(map, members, occupants, collectDiagnostics, repair, segmentPeers)
    }

    private fun movePlanningPose(unit: MutableUnit): MovePlanningPose {
        val shape = unit.definition.movementCollision
        return MovePlanningPose(unit.position, if (shape is MovementCollisionShape.Rectangle) unit.heading else 0.0,
            UnitContact.layer(unit), shape)
    }

    private fun publishMove(member: MovePlanningMember, group: GroupMoveRequest?, repair: ManeuverRepair?,
                            occupants: List<String>, planned: PlannedMove, executionStep: Long) {
        val action = member.action
        val unit = units.getValue(action.unitId)
        val task = checkNotNull(unit.tasks.firstOrNull())
        val slot = planned.slot
        if (repair == ManeuverRepair.SHORE_REJOIN) {
            if (slot != null && unit.pathPoints.size > 1 && unit.pathPoints[1] == slot.position &&
                unit.pathPoints.first() == member.body?.pathPoints?.firstOrNull()) {
                unit.nav = unit.nav.popFrontWaypoint(maneuverTurnWaypoint = unit.position)
            }
            return
        }
        planned.counters?.let { counts ->
            pathDiagnostics?.add(PathSearchSample(action.actionId, unit.unitId, action.atStep,
                knowledgeStores != null && knowledgeRules?.pathSource == PathInformationSource.PLAYER_KNOWLEDGE,
                if (slot == null) PathSearchOutcome.UNREACHABLE else PathSearchOutcome.FOUND,
                member.inputPreparationNanos, planned.searchNanos, counts.actualSearchCalls, counts.candidatesChecked, counts.popped, counts.expanded,
                counts.enqueued, counts.neighborsChecked, counts.heuristicCalls, slot?.path?.size ?: 0,
                reason = repair?.reason ?: PathSearchReason.USER_MOVE, executionStep = executionStep,
                searchTarget = slot?.position ?: action.target, searchStart = member.start))
        }
        if (repair != null) {
            val landing = unit.landingMember
            val lostRoute = landing?.claimed != null && (slot == null || planned.nearest)
            if (landing != null && lostRoute && (repair == ManeuverRepair.KNOWLEDGE ||
                    (repair == ManeuverRepair.CONTACT && unit.stalledRouteTicks >= ROUTE_FAILURE_TICKS))) {
                val click = landing.group.click
                val releasedContract = task.motionContract ?: MotionContract.sharedCluster(click)
                unit.updateNavigation {
                    it.withLandingMember(LandingMember(landing.group)).bindCommandTarget(
                        target = click,
                        contract = releasedContract,
                    )
                }
                unit.taskQueue = unit.taskQueue.replaceHead(task.copy(target = click))
                unit.adoptRoute(emptyList(), click, plannedKnowledgeRevision(unit), plannedBuildingRevision(unit))
                steps.current.landingOccupancy.update(unit)
                steps.current.movePlanning.enqueue(
                    MoveAction(task.actionId, unit.ownerPlayerId, unit.unitId, click, world, task.atStep),
                    null,
                    selectionSize = landing.group.members.size,
                )
                pendingEvents += Event("group-destination-released", unit.unitId)
                return
            }
            if (repair == ManeuverRepair.CONTACT) {
                unit.updateNavigation { it.withLastManeuverContactRepair(executionStep) }
            }
            if (slot != null) {
                if (planned.nearest && unit.landingMember != null) return
                if (repair == ManeuverRepair.CONTACT && slot.path == unit.pathPoints) return
                if (planned.nearest) {
                    val nextNearestTarget = unit.nearestMoveTarget ?: unit.groupMoveReservation?.plan?.click ?: action.target
                    val nextNearestGroup = unit.nearestMoveIsGroup || unit.groupMoveReservation != null
                    val nextRes = if (repair == ManeuverRepair.CONTACT && slot.position == member.start) null
                        else unit.groupMoveReservation?.copy(slot = slot)
                    val nextContract = task.motionContract ?: MotionContract.pointMove(
                        target = slot.position,
                        allowShortReverse = !task.factoryExit,
                        gatheringTarget = if (task.factoryExit) null else slot.position,
                    )
                    unit.taskQueue = unit.taskQueue.replaceHead(task.copy(target = slot.position))
                    unit.updateNavigation {
                        it.withGroupMoveReservation(nextRes).bindCommandTarget(
                            target = slot.position,
                            contract = nextContract,
                            followsManeuverRoute = true,
                            nearestMoveTarget = nextNearestTarget,
                            nearestMoveIsGroup = nextNearestGroup,
                        )
                    }
                }
                if (repair == ManeuverRepair.GROUP_SLOT) {
                    val nextContract = task.motionContract ?: MotionContract.pointMove(
                        target = slot.position,
                        allowShortReverse = !task.factoryExit,
                        gatheringTarget = if (task.factoryExit) null else slot.position,
                    )
                    unit.taskQueue = unit.taskQueue.replaceHead(task.copy(target = slot.position))
                    unit.updateNavigation {
                        it.withGroupMoveReservation(GroupMoveReservation(checkNotNull(group), slot)).bindCommandTarget(
                            target = slot.position,
                            contract = nextContract,
                            nearTargetTime = 0.0,
                        )
                    }
                    pendingEvents += Event("group-destination-reassigned", unit.unitId)
                }
                unit.adoptRoute(
                    slot.path,
                    logicalRouteTarget(unit, slot.position),
                    plannedKnowledgeRevision(unit),
                    plannedBuildingRevision(unit),
                    occupants,
                    repairReset = true,
                )
                return
            }
            if (repair == ManeuverRepair.CONTACT) return
            if (repair != ManeuverRepair.GROUP_SLOT) {
                val current = unit.groupMoveReservation
                if (current != null && unit.moveTarget == current.slot.position) {
                    // Slot replacement enters the queue after the current planning pass.
                    unit.updateNavigation { it.withGroupMoveReservation(current.copy(needsReplacement = true)) }
                    return
                }
            }
        }
        if (slot == null) {
            if (repair == ManeuverRepair.GROUP_SLOT && planned.nearest) {
                val currentRes = unit.groupMoveReservation
                if (currentRes != null && ScreenPlane.distanceSquared(unit.position, currentRes.slot.position) <= 4.0) {
                    val originalTarget = unit.nearestMoveTarget ?: currentRes.plan.click
                    val arrival = MoveArrival(
                        originalTarget, unit.position,
                        MoveArrival.distanceToHull(unit.definition.movementCollision, unit.position, unit.heading, originalTarget),
                        executionStep, group = true,
                    )
                    unit.cancelRoute()
                    unit.nav = unit.nav.markArrived(null, currentRes.plan, arrival)
                    if (unit.tasks.firstOrNull()?.kind == UnitTaskKind.MOVE) unit.taskQueue = unit.taskQueue.pop()
                    unit.speed = 0.0
                    unit.angularVelocity = 0.0
                    unit.flightVelocity = Position(0.0, 0.0)
                    unit.movementState = MovementState.IDLE
                    pendingEvents += Event("movement-arrived", unit.unitId)
                    pendingEvents += Event("movement-nearest-reached", unit.unitId)
                    return
                }
            }
            val amphibiousPaused = repair == ManeuverRepair.CONNECTION && unit.amphibiousState != null && task.kind == UnitTaskKind.MOVE
            if (!amphibiousPaused) {
                unit.taskQueue = unit.taskQueue.pop()
            }
            unit.cancelRoute()
            if (amphibiousPaused) {
                unit.amphibiousState = unit.amphibiousState?.copy(terrainPausedMove = true)
            }
            if (planned.nearest) {
                reject(action, "immobile-unit", "No legal movement from the current pose")
                return
            }
            if (group != null) pendingEvents += Event("group-destination-unavailable", unit.unitId)
            pendingEvents += Event("movement-cancelled", unit.unitId)
            pendingEvents += Event("path-unreachable", unit.unitId)
            return
        }
        if (task.target != slot.position) unit.taskQueue = unit.taskQueue.replaceHead(task.copy(target = slot.position))
        val priorGroup = unit.landingMember?.group
        val nearSharedArea = planned.nearest && priorGroup != null && priorGroup.members.any { id ->
            units[id]?.definition?.movementCollision?.let { it != unit.definition.movementCollision } == true
        } && ScreenPlane.distanceSquared(slot.position, priorGroup.click) <= unit.definition.movementCollision.boundingRadius.let { it * it }
        val keepSharedLandingOnNearest = nearSharedArea || (planned.nearest && priorGroup != null && priorGroup.sharedLanding)
        val nextNearestTarget = if (planned.nearest) action.target else unit.nearestMoveTarget
        val nextNearestIsGroup = when {
            planned.nearest && !keepSharedLandingOnNearest -> group != null || unit.landingMember != null
            keepSharedLandingOnNearest -> true
            else -> unit.nearestMoveIsGroup
        }
        val nextLanding = if (planned.nearest && !keepSharedLandingOnNearest) null else unit.landingMember
        val nextReservation = group?.let { GroupMoveReservation(it, slot) }
        val nextManeuver = (planned.nearest || (member.hull as? RectangularDeparture)?.maneuver == true) && !member.hasBirthDelay
        val publishedContract = task.motionContract ?: if (nextLanding != null) {
            MotionContract.sharedCluster(nextLanding.group.click)
        } else {
            MotionContract.pointMove(
                target = slot.position,
                allowShortReverse = !task.factoryExit,
                gatheringTarget = if (task.factoryExit) null else slot.position,
            )
        }
        unit.updateNavigation {
            it.bindCommandTarget(
                target = slot.position,
                originMoveId = action.actionId,
                originAtStep = action.atStep,
                contract = publishedContract,
                followsManeuverRoute = nextManeuver,
                nearestMoveTarget = nextNearestTarget,
                nearestMoveIsGroup = nextNearestIsGroup,
                nearTargetTime = 0.0,
                groupReservation = GroupReservation.of(
                    slot = nextReservation,
                    parkedOwner = it.parkedReservationOwner,
                    landing = nextLanding,
                ),
            )
        }
        unit.adoptRoute(slot.path, logicalRouteTarget(unit, slot.position), plannedKnowledgeRevision(unit), plannedBuildingRevision(unit))
        pendingEvents += Event("movement-started", unit.unitId)
    }

    private fun ownGroupReservation(observer: MutableUnit, other: MutableUnit): GroupMoveReservation? {
        if (other.ownerPlayerId != observer.ownerPlayerId) return null
        return other.groupMoveReservation?.takeIf { other.moveTarget == it.slot.position }
    }

    private fun groupSlotFits(unit: MutableUnit, slot: GroupMoveSlot, map: MapDefinition): Boolean {
        val grid = HullTerrainGrid.authorized(planningTerrainFor(unit, map))
        return UnitContact.poseFits(unit.definition.movementCollision, slot.position, slot.heading, grid)
    }

    private fun groupStopClearsSlots(unit: MutableUnit): Boolean {
        val reserved = unit.groupMoveReservation ?: return true
        val layer = UnitContact.layer(unit) ?: return true
        val stopped = reserved.slot.copy(position = unit.position, heading = unit.heading)
        val ownBuf = unit.definition.contactBufferCollision.boundingRadius
        // Check live nearby parked members via spatialIndex and group peers (which have slot positions separate from current position).
        val nearby = spatialIndex.queryBounds(
            SpatialLayer.fromContact(layer),
            stopped.position.x - ownBuf, stopped.position.x + ownBuf,
            stopped.position.y - ownBuf, stopped.position.y + ownBuf,
            useBufferRadius = true,
        )
        for (other in nearby) {
            if (other === unit || other.ownerPlayerId != unit.ownerPlayerId) continue
            val peer = ownGroupReservation(unit, other)
            if (peer?.plan !== reserved.plan && reserved.plan.hasParkedMember(other) &&
                GroupMoveDestinations.overlaps(unit, stopped, other)
            ) return false
        }
        for (memberId in reserved.plan.members) {
            if (memberId == unit.unitId) continue
            val other = units[memberId] ?: continue
            if (other.ownerPlayerId != unit.ownerPlayerId) continue
            val peer = ownGroupReservation(unit, other)
            if (peer?.plan === reserved.plan && GroupMoveDestinations.overlaps(unit, stopped, other, peer.slot)) return false
        }
        return true
    }

    private fun advanceSharedLandings(map: MapDefinition, budget: PathWorkBudget, slices: MutableList<PlanningWorkSlice>?) {
        val pools = steps.current.landingSites
        if (budget.exhausted) {
            steps.current.landingsShortOfBudget = true
            return
        }
        val world = orderedUnits().toList()
        val groups = world.filter { it.alive && it.landingMember != null }.groupBy { it.landingMember!!.group }
        pools.keys.retainAll(groups.keys)
        for ((group, members) in groups) {
            val membersDriving = members.filter { it.moveTarget != null && it.landingMember!!.claimed == null && !it.landingMember!!.parked }
            if (membersDriving.isEmpty()) continue
            val pool = pools.getOrPut(group) { SharedLandingSites.forGroup(map, group, members) }
            val survey = pool.mixedBays ?: continue
            if (!membersDriving.any { pool.near(it.position) }) continue
            if (budget.limit - budget.used < 32) steps.current.landingsShortOfBudget = true
            if (budget.exhausted) continue
            val before = budget.used
            val leader = membersDriving.first()
            val index = steps.current.landingOccupancy
            fun occupied(candidateCar: MutableUnit, site: Position, arrivalHeading: Double): Boolean {
                if (index.at(site).any { id ->
                        val other = units[id] ?: return@any false
                        val state = other.landingMember ?: return@any false
                        other !== candidateCar && other.alive && UnitContact.shareLayer(candidateCar, other) &&
                            if (other.ownerPlayerId == candidateCar.ownerPlayerId) state.claimed == site || (state.parked && other.position == site)
                            else state.parked && other.position == site && knowsRouteOccupant(candidateCar, other)
                    }) return true
                return index.overlapsAnyActiveClaim(candidateCar, site, arrivalHeading, units, ::knowsRouteOccupant)
            }
            val available = budget.limit - budget.used
            val slice = PathWorkBudget(minOf(available, maxOf(1, available / 2)))
            val bays = survey.sites(planningTerrainFor(leader, map), slice)
            val decision = when {
                bays == null -> MixedLandingBays.Decision.Pending
                bays.isEmpty() -> MixedLandingBays.Decision.Ordinary
                else -> survey.choose(
                    members, bays, ::occupied, pool::near, { planningTerrainFor(it, map) },
                    pathPolicy, slice, pool::advanceExact,
                ) { car ->
                    world.filter { other ->
                        other !== car && other.alive && UnitContact.shareLayer(car, other) &&
                            (other.landingMember?.group !== group || isParkedPeer(car, other)) &&
                            knowsRouteOccupant(car, other)
                    }
                }
            }
            check(budget.take(slice.used))
            if (decision is MixedLandingBays.Decision.Claim) {
                val car = decision.car
                val site = decision.path.last()
                val prev = if (decision.path.size >= 2) decision.path[decision.path.size - 2] else car.position
                val claimHeading = if (prev != site) ScreenPlane.desired(prev, site) else car.heading
                steps.current.movePlanning.cancel(car.unitId)
                steps.current.pendingFlowStart.remove(car.unitId)
                val headTask = car.tasks.first()
                val claimContract = headTask.motionContract ?: MotionContract.sharedCluster(site)
                car.updateNavigation {
                    it.withLandingMember(LandingMember(group, claimed = site, claimedHeading = claimHeading))
                        .bindCommandTarget(
                            target = site,
                            contract = claimContract,
                            followsManeuverRoute = true,
                        )
                }
                car.taskQueue = car.taskQueue.replaceHead(headTask.copy(target = site))
                car.adoptRoute(decision.path, site, plannedKnowledgeRevision(car), plannedBuildingRevision(car))
                index.update(car)
            }
            if (budget.used > before) {
                slices?.add(
                    PlanningWorkSlice(
                        membersDriving.map { it.unitId }, PathSearchReason.USER_MOVE,
                        budget.used - before, membersDriving.all { it.landingMember?.claimed != null }, false,
                    )
                )
            }
        }
    }

    private fun refreshGroupDestinations(map: MapDefinition, delta: Double) {
        for (unit in orderedUnits()) {
            val reservation = unit.groupMoveReservation ?: continue
            if (unit.landingMember != null) continue
            if (!unit.alive || unit.carriedBy != null || unit.moveTarget != reservation.slot.position) continue
            val distance = ScreenPlane.distanceSquared(unit.position, reservation.slot.position)
            val radius = UnitContact.boundingRadius(unit.definition.movementCollision)
            fun occupies(other: MutableUnit): Boolean {
                val approachRange = maxOf(41.0, radius + UnitContact.boundingRadius(other.definition.movementCollision) + 7.0)
                return other !== unit && (other.moveTarget == null || distance <= approachRange * approachRange) &&
                    GroupMoveDestinations.overlaps(unit, reservation.slot, other) && knowsRouteOccupant(unit, other)
            }
            val contactLayer = UnitContact.layer(unit)
            val holder = reservation.occupiedBy?.let(units::get)?.takeIf(::occupies)
                ?: if (contactLayer == null) null else {
                    val bufR = unit.definition.contactBufferCollision.boundingRadius
                    val sp = reservation.slot.position
                    spatialIndex.queryBounds(
                        SpatialLayer.fromContact(contactLayer),
                        sp.x - bufR, sp.x + bufR, sp.y - bufR, sp.y + bufR,
                        useBufferRadius = true,
                    ).firstOrNull(::occupies)
                }
            val occupied = holder != null
            val sameHolder = holder?.unitId == reservation.occupiedBy
            val ticks = when {
                !occupied -> 0L
                delta > 0.0 -> if (sameHolder) reservation.occupiedTicks + 1 else 1L
                sameHolder -> reservation.occupiedTicks
                else -> 0L
            }
            if (!reservation.needsReplacement && ticks < ROUTE_FAILURE_TICKS && groupSlotFits(unit, reservation.slot, map)) {
                unit.updateNavigation { nav ->
                    val withTime = if (occupied) nav.withNearTargetTime(0.0) else nav
                    if (ticks != reservation.occupiedTicks || !sameHolder) {
                        withTime.withGroupMoveReservation(reservation.copy(occupiedTicks = ticks, occupiedBy = holder?.unitId))
                    } else withTime
                }
                continue
            }
            replaceInvalidGroupDestination(unit)
        }
    }

    private fun replaceInvalidGroupDestination(unit: MutableUnit): Boolean {
        if (unit.landingMember != null) return false
        val reservation = unit.groupMoveReservation?.takeIf { it.slot.position == unit.moveTarget } ?: return false
        if (unit.tasks.firstOrNull()?.let {
                it.kind == UnitTaskKind.MOVE && it.actionId == unit.originMoveId
            } != true) return false
        scheduleManeuverRepair(unit, reservation.slot.position, ManeuverRepair.GROUP_SLOT, reservation.plan)
        return true
    }

    fun submit(action: Action): ActionResult = submit(action, null)

    fun submitGroupMove(action: MoveAction, plan: GroupMoveRequest): ActionResult = submit(action, plan)

    private fun submit(action: Action, group: GroupMoveRequest?): ActionResult {
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
        if (unit.attachment != null) return reject(action, "attached-unit", "Use the chassis to control its slot")
        if ((action is MoveAction || action is PatrolAction) && unit.definition.movement.type == MovementType.NONE) {
            return reject(action, "immobile-unit", "Unit cannot move")
        }
        if (action is MoveAction && (!action.target.x.isFinite() || !action.target.y.isFinite())) {
            return reject(action, "invalid-target", "Target coordinates must be finite")
        }
        if (action is MoveAction && !MapBounds.contains(currentScenario.map.widthTiles.toDouble() * currentScenario.map.tileWidth,
                currentScenario.map.heightTiles.toDouble() * currentScenario.map.tileHeight, action.target, 0.0))
            return reject(action, "invalid-target", "Target is outside the map")
        if (action is MoveAction && UnitLocomotion.movement(unit).moveSpeed <= 0.0)
            return reject(action, "immobile-unit", "Unit cannot move")
        if (group != null && (group.world !== world || group.completedStep != stepIndex.takeIf { hasAdvanced } || group.actor != action.actorPlayerId ||
                group.click != (action as MoveAction).target || action.unitId !in group.members || action.atStep != null)) {
            return reject(action, "stale-group-move", "Selection move no longer belongs to this action")
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
        return applyAction(action, group = group)
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
            action.unitId.startsWith(PRODUCED_UNIT_PREFIX) && productionNamespaceEnabled(input) -> PRODUCED_UNIT_PREFIX
            action.unitId.startsWith(CONSTRUCTED_UNIT_PREFIX) && constructionNamespaceEnabled(input) -> CONSTRUCTED_UNIT_PREFIX
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
                val unit = units[action.unitId]?.takeIf {
                    isVisibleTo(actor.playerId, it.position, it.nativeTransportPlacement)
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
    private fun routeRadius(unit: MutableUnit): Double {
        val map = scenario?.map
        val tw = map?.tileWidth ?: 20
        val th = map?.tileHeight ?: 20
        return when (val shape = UnitLocomotion.terrainHull(unit, tw, th)) {
            is MovementCollisionShape.Rectangle -> minOf(shape.length, shape.width) / 2.0
            is MovementCollisionShape.Circle -> maxOf(shape.radius, Transport.boundaryRadius(unit))
            null -> Transport.boundaryRadius(unit)
        }
    }

    /**
     * Read-only player-knowledge-isolated navigation terrain for `(playerId, movementType)`
     * (ADR-016 / ENG-P017-004).
     */
    fun authorizedNavigationTerrain(
        world: WorldHandle,
        playerId: String,
        movementType: MovementType = MovementType.LAND,
    ): AuthorizedNavigationTerrain {
        checkKnowledgeWorld(world)
        val input = checkNotNull(scenario)
        if (input.players.none { it.playerId == playerId }) {
            throw KnowledgeException("unknown-player", "Player is not declared in the current scenario")
        }
        return resolveAuthorizedTerrain(
            ownerPlayerId = playerId,
            map = input.map,
            movementType = movementType,
            escaping = false,
            overlappedBuildingCells = emptySet(),
        )
    }

    private fun planningTerrainFor(
        unit: MutableUnit,
        map: MapDefinition,
        movementType: MovementType = UnitLocomotion.movement(unit).type,
    ): PlanningTerrain {
        val ownerBuildings = buildingKnowledge[unit.ownerPlayerId]?.cells
        val escaping = unit.birthRemaining == 0f && ownerBuildings?.contains(
            MapBounds.cell(unit.position.x, map.tileWidth, map.widthTiles) to
                MapBounds.cell(unit.position.y, map.tileHeight, map.heightTiles),
        ) == true
        val overlappedBuildingCells = if (unit.birthRemaining == 0f && !escaping && ownerBuildings != null) {
            BuildingOverlapEscape.overlappedAuthorizedBuildingCells(
                unit, map, buildingTerrain, spatialIndex, units, ownerBuildings,
            )
        } else emptySet()
        return resolveAuthorizedTerrain(unit.ownerPlayerId, map, movementType, escaping, overlappedBuildingCells)
    }

    private fun resolveAuthorizedTerrain(
        ownerPlayerId: String,
        map: MapDefinition,
        movementType: MovementType,
        escaping: Boolean,
        overlappedBuildingCells: Set<Pair<Int, Int>>,
    ): PlanningTerrain {
        if (movementType == MovementType.AIR) {
            val cached = noFogPlanning[MovementType.AIR]
            if (cached != null && cached.world === activeWorld && cached.map === map) {
                planningTerrainCacheHits++
                return cached.terrain
            }
            planningTerrainCacheMisses++
            return PlanningTerrain(
                map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight,
                BooleanArray(map.widthTiles * map.heightTiles),
            ).also {
                noFogPlanning[MovementType.AIR] = CachedPlanningTerrain(world, map, null, it, movementType = movementType)
            }
        }
        val ownerBuildings = buildingKnowledge[ownerPlayerId]?.cells
        val stores = knowledgeStores
        val usesKnowledge = stores != null &&
            checkNotNull(knowledgeRules).pathSource == PathInformationSource.PLAYER_KNOWLEDGE
        // Preserve the shared legacy ground-only adapter when no building subsystem is active.
        if (!usesKnowledge && ownerBuildings == null) {
            val cached = noFogPlanning[movementType]
            if (cached != null && cached.world === activeWorld && cached.map === map && cached.movementType == movementType) {
                planningTerrainCacheHits++
                return cached.terrain
            }
            planningTerrainCacheMisses++
            return PlanningTerrain.fromNoFog(map, movementType).also {
                noFogPlanning[movementType] = CachedPlanningTerrain(world, map, null, it, movementType = movementType)
            }
        }
        val snapshot = if (usesKnowledge) checkNotNull(stores?.get(ownerPlayerId)).snapshot(world) else null
        val cacheKey = PlayerPlanningKey(ownerPlayerId, movementType, escaping)
        val cached = playerPlanning[cacheKey]?.takeIf { it.world === activeWorld && it.map === map }
        val sameTerrainKnowledge = if (snapshot == null && cached?.knowledge == null) {
            true
        } else if (snapshot != null && cached?.knowledge != null) {
            snapshot === cached.knowledge || snapshot.terrainRevision == cached.knowledge.terrainRevision
        } else {
            false
        }
        val sameBuildings = cached != null && (cached.buildings === ownerBuildings || cached.buildings == ownerBuildings)
        if (cached != null && sameTerrainKnowledge && sameBuildings) {
            if (cached.knowledge !== snapshot) {
                playerPlanning[cacheKey] = cached.copy(knowledge = snapshot)
            }
            planningTerrainCacheHits++
            return if (overlappedBuildingCells.isEmpty()) {
                cached.terrain
            } else {
                cached.ground.withBuildingsCached((ownerBuildings ?: emptySet()) - overlappedBuildingCells)
            }
        }
        planningTerrainCacheMisses++
        val previous = cached?.knowledge
        val ground = when {
            sameTerrainKnowledge && cached != null -> cached.ground
            snapshot != null && previous != null -> cached.ground.withUpdatedKnowledge(previous, snapshot, movementType, map.tileSets)
            snapshot != null -> PlanningTerrain.fromKnowledge(snapshot, map.tileWidth, map.tileHeight, movementType, map.tileSets)
            else -> PlanningTerrain.fromNoFog(map, movementType)
        }
        val terrain = when {
            escaping -> ground
            cached != null && ground === cached.ground && sameBuildings -> cached.terrain
            else -> ground.withBuildings(ownerBuildings ?: emptySet())
        }
        playerPlanning[cacheKey] = CachedPlanningTerrain(world, map, snapshot, terrain, ownerBuildings, escaping, movementType, ground)
        return if (overlappedBuildingCells.isEmpty()) {
            terrain
        } else {
            ground.withBuildingsCached((ownerBuildings ?: emptySet()) - overlappedBuildingCells)
        }
    }

    private fun resolveBudgetedTacticalRoute(
        unit: MutableUnit,
        map: MapDefinition,
        actionId: String,
        atStep: Long?,
        target: Position,
        reason: PathSearchReason,
        executionStep: Long,
        start: Position = unit.position,
        targetRange: Double = 0.0,
        occupancy: RouteOccupancy? = null,
        occupantIds: List<String> = emptyList(),
        isRepair: Boolean = false,
        preservedPrefix: List<Position> = emptyList(),
        preservedSuffix: List<Position> = emptyList(),
        fallbackFullTarget: Position? = null,
        budgetOverride: PathWorkBudget? = null,
        mustCompleteInStep: Boolean = false,
        unreachablePolicy: TacticalUnreachablePolicy = TacticalUnreachablePolicy.CANCEL_ROUTE,
    ): TacticalRouteResolution {
        val samples = pathDiagnostics
        val collectDiagnostics = samples != null
        val prepStart = if (collectDiagnostics) System.nanoTime() else 0L
        val terrain = planningTerrainFor(unit, map)
        val queriedStart = Transport.queryPosition(unit, map, start)
        val radius = routeRadius(unit)
        val hull = HullRoute.forUnit(unit, map)
        val prepNanos = if (collectDiagnostics) (System.nanoTime() - prepStart).coerceAtLeast(0L) else 0L

        val stepBudget = activeStepPlanningBudget
        val budget = when {
            mustCompleteInStep -> PathWorkBudget(Int.MAX_VALUE)
            budgetOverride != null -> budgetOverride
            stepBudget != null -> stepBudget
            else -> PathWorkBudget(movePlanningBudgetPerStep)
        }
        val usedBefore = budget.used
        val slices = if (mustCompleteInStep) null else activeStepPlanningSlices
        val request = TacticalRouteRequest(
            unitId = unit.unitId,
            actionId = actionId,
            atStep = atStep,
            start = queriedStart,
            target = target,
            radius = radius,
            targetRange = targetRange,
            terrain = terrain,
            hull = hull,
            occupancy = occupancy,
            occupantIds = occupantIds,
            reason = reason,
            isRepair = isRepair,
            collectDiagnostics = collectDiagnostics,
            inputPreparationNanos = prepNanos,
            preservedPrefix = preservedPrefix,
            preservedSuffix = preservedSuffix,
            fallbackFullTarget = fallbackFullTarget,
            isCircleShape = unit.definition.movementCollision is MovementCollisionShape.Circle,
            unreachablePolicy = unreachablePolicy,
        )
        val resolution = steps.current.movePlanning.resolveTactical(request, pathPolicy, budget, slices)
        if (mustCompleteInStep && stepBudget != null) {
            val deltaUsed = (budget.used - usedBefore).coerceAtLeast(0)
            if (deltaUsed > 0 && stepBudget.remaining > 0) {
                stepBudget.take(minOf(deltaUsed, stepBudget.remaining))
            }
        }
        if (samples != null && resolution !is TacticalRouteResolution.Pending) {
            val usedKnowledge = knowledgeStores != null && knowledgeRules?.pathSource == PathInformationSource.PLAYER_KNOWLEDGE
            when (resolution) {
                is TacticalRouteResolution.Ready -> {
                    val c = resolution.counters ?: PathSearchCounters()
                    val suffixPointCount = (resolution.points.size - resolution.preservedPrefixCount).coerceAtLeast(0)
                    samples += PathSearchSample(
                        actionId, unit.unitId, atStep, usedKnowledge, PathSearchOutcome.FOUND,
                        resolution.preparationNanos, resolution.searchNanos,
                        c.actualSearchCalls, c.candidatesChecked, c.popped, c.expanded, c.enqueued,
                        c.neighborsChecked, c.heuristicCalls, suffixPointCount, reason, executionStep,
                        searchTarget = target, searchStart = start,
                        preservedPrefixPointCount = resolution.preservedPrefixCount,
                    )
                }
                is TacticalRouteResolution.Unreachable -> {
                    val c = resolution.counters ?: PathSearchCounters()
                    samples += PathSearchSample(
                        actionId, unit.unitId, atStep, usedKnowledge, PathSearchOutcome.UNREACHABLE,
                        resolution.preparationNanos, resolution.searchNanos,
                        c.actualSearchCalls, c.candidatesChecked, c.popped, c.expanded, c.enqueued,
                        c.neighborsChecked, c.heuristicCalls, 0, reason, executionStep,
                        searchTarget = target, searchStart = start,
                        preservedPrefixPointCount = 0,
                    )
                }
                TacticalRouteResolution.Pending -> Unit
            }
        }
        return resolution
    }

    private fun plannedKnowledgeRevision(unit: MutableUnit): Long =
        if (knowledgeRules?.pathSource == PathInformationSource.PLAYER_KNOWLEDGE)
            checkNotNull(knowledgeStores?.get(unit.ownerPlayerId)).snapshot(checkNotNull(activeWorld)).revision else -1L

    private fun plannedBuildingRevision(unit: MutableUnit): Long = buildingKnowledge[unit.ownerPlayerId]?.revision ?: -1L

    private fun drainCompletedRepairTacticalRoutes(map: MapDefinition, executionStep: Long) {
        val completed = steps.current.movePlanning.completedRepairTacticalRequests()
        if (completed.isEmpty()) return
        for (req in completed) {
            val unit = units[req.unitId]
            if (unit == null || !unit.alive || unit.carriedBy != null) {
                steps.current.movePlanning.cancel(req.unitId)
                continue
            }
            val currentTarget = unit.moveTarget
            if (currentTarget == null ||
                (unit.originMoveId != null && req.actionId != "repair:${unit.unitId}" && unit.originMoveId != req.actionId)
            ) {
                steps.current.movePlanning.cancel(req.unitId)
                continue
            }
            val primaryRes = resolveBudgetedTacticalRoute(
                unit = unit,
                map = map,
                actionId = req.actionId,
                atStep = req.atStep,
                target = req.target,
                reason = req.reason,
                executionStep = executionStep,
                start = if (req.preservedPrefix.isNotEmpty()) req.start else unit.position,
                targetRange = req.targetRange,
                occupancy = req.occupancy,
                occupantIds = req.occupantIds,
                isRepair = true,
                preservedPrefix = req.preservedPrefix,
                preservedSuffix = req.preservedSuffix,
                fallbackFullTarget = req.fallbackFullTarget,
                unreachablePolicy = req.unreachablePolicy,
            )
            val res = if (primaryRes is TacticalRouteResolution.Unreachable && req.fallbackFullTarget != null) {
                val fallbackRange = (unit.motionContract?.goal as? MotionGoalGeometry.RangeBand)?.range ?: 0.0
                resolveBudgetedTacticalRoute(
                    unit = unit,
                    map = map,
                    actionId = req.actionId,
                    atStep = req.atStep,
                    target = req.fallbackFullTarget,
                    reason = req.reason,
                    executionStep = executionStep,
                    targetRange = fallbackRange,
                    isRepair = true,
                    unreachablePolicy = req.unreachablePolicy,
                )
            } else primaryRes
            when (res) {
                TacticalRouteResolution.Pending -> Unit
                is TacticalRouteResolution.Unreachable -> {
                    if (FactoryExit.isNativeSeaDeparture(unit)) continue
                    when (req.unreachablePolicy) {
                        TacticalUnreachablePolicy.SILENT_IGNORE -> continue
                        TacticalUnreachablePolicy.STALL_MOVE_GUARDED -> {
                            if (unit.stalledRouteTicks < ROUTE_FAILURE_TICKS) continue
                            if ((unit.groupMoveReservation?.occupiedTicks ?: 0) > 0) continue
                            if (replaceInvalidGroupDestination(unit)) continue
                            if (unit.tasks.firstOrNull()?.kind == UnitTaskKind.MOVE) {
                                unit.taskQueue = unit.taskQueue.pop()
                            }
                            unit.cancelRoute()
                            pendingEvents += Event("movement-cancelled", unit.unitId)
                            pendingEvents += Event("path-unreachable", unit.unitId)
                        }
                        TacticalUnreachablePolicy.CANCEL_ROUTE -> {
                            if (replaceInvalidGroupDestination(unit)) continue
                            if (unit.tasks.firstOrNull()?.kind == UnitTaskKind.BUILD) unit.pauseRoute()
                            else unit.cancelRoute()
                            unit.amphibiousState = unit.amphibiousState?.copy(
                                terrainPausedMove = unit.tasks.firstOrNull()?.kind == UnitTaskKind.MOVE,
                            )
                            pendingEvents += Event("movement-cancelled", unit.unitId)
                            pendingEvents += Event("path-unreachable", unit.unitId)
                        }
                    }
                }
                is TacticalRouteResolution.Ready -> {
                    unit.adoptRoute(
                        res.points,
                        logicalRouteTarget(unit, currentTarget),
                        plannedKnowledgeRevision(unit),
                        plannedBuildingRevision(unit),
                        req.occupantIds,
                    )
                }
            }
        }
    }

    private fun replanForKnowledge(map: MapDefinition, executionStep: Long) {
        if (pendingKnowledgeDiscoveries.isEmpty() && pendingBuildingDiscoveries.isEmpty()) return
        val mapWidth = map.widthTiles.toDouble() * map.tileWidth
        val mapHeight = map.heightTiles.toDouble() * map.tileHeight
        for (unit in orderedUnits()) {
            if (unit.carriedBy != null) continue
            if (UnitLocomotion.movement(unit).type == MovementType.AIR) continue
            if (unit.birthRemaining > 0f) continue
            val target = unit.moveTarget ?: continue
            val lmFlow = unit.landingMember
            if (trafficSeparationEnabled && lmFlow != null && lmFlow.claimed == null &&
                lmFlow.group.members.size >= ChunkedFlowFieldAdapter.MIN_GROUP_SIZE &&
                steps.current.flowFields.activeFieldCount() > 0 &&
                steps.current.flowFields.hasValidGuidanceFor(unit, planningTerrainFor(unit, map))
            ) continue
            val snapshot = knowledgeStores?.get(unit.ownerPlayerId)?.snapshot(world)
            val ground = pendingKnowledgeDiscoveries[unit.ownerPlayerId].orEmpty()
            val buildings = pendingBuildingDiscoveries[unit.ownerPlayerId].orEmpty()
            val clearanceRadius = UnitLocomotion.terrainPassabilityRadius(unit, map.tileWidth, map.tileHeight)
            val affectedGround = ground.any { (cell, discoveredAt) ->
                knowledgeRules?.pathSource == PathInformationSource.PLAYER_KNOWLEDGE &&
                    discoveredAt > unit.lastPlannedKnowledgeRevision && checkNotNull(snapshot)
                        .movementBlocked(cell.first, cell.second, UnitLocomotion.movement(unit).type, map.tileSets) &&
                    KnowledgePathImpact.affectsSegment(
                        unit.position, unit.pathPoints, target, clearanceRadius,
                        cell.first, cell.second, map.tileWidth, map.tileHeight, mapWidth, mapHeight,
                    )
            }
            val affected = affectedGround || buildings.any { (cell, discoveredAt) ->
                discoveredAt > unit.lastPlannedBuildingRevision && cell in checkNotNull(buildingKnowledge[unit.ownerPlayerId]).cells &&
                    KnowledgePathImpact.affectsSegment(
                        unit.position, unit.pathPoints, target, clearanceRadius,
                        cell.first, cell.second, map.tileWidth, map.tileHeight, mapWidth, mapHeight,
                    )
            }
            if (!affected) continue
            if (unit.followsManeuverRoute) {
                scheduleManeuverRepair(unit, target, ManeuverRepair.KNOWLEDGE)
                continue
            }
            if (unit.attackCommand != null) unit.lastAttackPathAttemptStep = executionStep
            val terrain = planningTerrainFor(unit, map)
            val oldPath = unit.pathPoints.toList()
            val prefix = mutableListOf<Position>()
            var from = unit.position
            val boundaryR = routeRadius(unit)
            for (point in oldPath) {
                if (!PlanningSegmentClearance.allows(terrain, Transport.queryPosition(unit, map, from), point, boundaryR, clearanceRadius)) break
                prefix += point
                from = point
            }
            val rangeForSearch = (unit.motionContract?.goal as? MotionGoalGeometry.RangeBand)?.range ?: 0.0
            val primaryRes = if (prefix.isNotEmpty() && prefix.size < oldPath.size) {
                resolveBudgetedTacticalRoute(
                    unit = unit,
                    map = map,
                    actionId = checkNotNull(unit.originMoveId),
                    atStep = unit.originAtStep,
                    target = target,
                    reason = PathSearchReason.KNOWLEDGE_REPLAN,
                    executionStep = executionStep,
                    start = prefix.last(),
                    targetRange = rangeForSearch,
                    isRepair = true,
                    preservedPrefix = prefix,
                    mustCompleteInStep = true,
                )
            } else null
            val finalRes = when (primaryRes) {
                is TacticalRouteResolution.Ready, TacticalRouteResolution.Pending -> primaryRes
                is TacticalRouteResolution.Unreachable, null -> resolveBudgetedTacticalRoute(
                    unit = unit,
                    map = map,
                    actionId = checkNotNull(unit.originMoveId),
                    atStep = unit.originAtStep,
                    target = target,
                    reason = PathSearchReason.KNOWLEDGE_REPLAN,
                    executionStep = executionStep,
                    start = unit.position,
                    targetRange = rangeForSearch,
                    isRepair = true,
                    mustCompleteInStep = true,
                )
            }
            when (finalRes) {
                TacticalRouteResolution.Pending -> Unit
                is TacticalRouteResolution.Unreachable -> {
                    if (FactoryExit.isNativeSeaDeparture(unit)) continue
                    if (replaceInvalidGroupDestination(unit)) continue
                    if (unit.tasks.firstOrNull()?.kind == UnitTaskKind.BUILD) unit.pauseRoute()
                    else unit.cancelRoute()
                    pendingEvents += Event("movement-cancelled", unit.unitId)
                    pendingEvents += Event("path-unreachable", unit.unitId)
                }
                is TacticalRouteResolution.Ready -> {
                    unit.adoptRoute(
                        finalRes.points,
                        logicalRouteTarget(unit, checkNotNull(unit.moveTarget)),
                        plannedKnowledgeRevision(unit),
                        plannedBuildingRevision(unit),
                    )
                }
            }
        }
    }

    private fun repairInvalidConnections(map: MapDefinition, executionStep: Long) {
        for (unit in orderedUnits()) {
            if (unit.carriedBy != null) continue
            if (unit.amphibiousState?.terrainPausedMove == true && UnitLocomotion.movement(unit).type == MovementType.AIR) {
                val task = unit.tasks.firstOrNull()?.takeIf { it.kind == UnitTaskKind.MOVE }
                if (unit.alive && unit.moveTarget == null && task?.target != null) {
                    val resumeContract = task.motionContract ?: MotionContract.pointMove(
                        target = task.target,
                        allowShortReverse = !task.factoryExit,
                        gatheringTarget = if (task.factoryExit) null else task.target,
                    )
                    unit.updateNavigation {
                        it.bindCommandTarget(
                            target = task.target,
                            originMoveId = task.actionId,
                            originAtStep = task.atStep,
                            contract = resumeContract,
                        )
                    }
                    resumeProductionRoute(unit, map, executionStep)
                }
                unit.amphibiousState = checkNotNull(unit.amphibiousState).copy(terrainPausedMove = false)
            }
            if (UnitLocomotion.movement(unit).type == MovementType.AIR) continue
            if (unit.birthRemaining > 0f) continue
            if (FactoryExit.isNativeSeaDeparture(unit)) continue
            if (steps.current.movePlanning.pending(unit.unitId)) continue
            val target = unit.moveTarget ?: continue
            val lmFlow = unit.landingMember
            if (trafficSeparationEnabled && lmFlow != null && lmFlow.claimed == null &&
                lmFlow.group.members.size >= ChunkedFlowFieldAdapter.MIN_GROUP_SIZE &&
                steps.current.flowFields.activeFieldCount() > 0 &&
                steps.current.flowFields.hasValidGuidanceFor(unit, planningTerrainFor(unit, map))
            ) continue
            val first = unit.pathPoints.firstOrNull() ?: continue
            val terrain = planningTerrainFor(unit, map)
            val start = Transport.queryPosition(unit, map)
            val radius = routeRadius(unit)
            val firstConnectionClear = PlanningSegmentClearance.allows(terrain, start, first, radius)
            if (unit.followsManeuverRoute) {
                if (!firstConnectionClear) scheduleManeuverRepair(unit, target, ManeuverRepair.CONNECTION)
                continue
            }
            val nearCornerStall = unit.pathPoints.size > 1 &&
                ScreenPlane.distanceSquared(unit.position, first) < 9.0 &&
                !PlanningSegmentClearance.allows(terrain, start, unit.pathPoints[1], radius)
            if (firstConnectionClear && !nearCornerStall) continue
            val headTaskKind = unit.tasks.firstOrNull()?.kind
            if (headTaskKind == UnitTaskKind.MOVE && unit.originMoveId != null) {
                scheduleManeuverRepair(unit, target, ManeuverRepair.CONNECTION)
                continue
            }
            if (unit.attackCommand != null) unit.lastAttackPathAttemptStep = executionStep
            val rangeForSearch = (unit.motionContract?.goal as? MotionGoalGeometry.RangeBand)?.range ?: 0.0
            val oldPath = unit.pathPoints.toList()
            val tailIsClear = !nearCornerStall && rangeForSearch == 0.0 && oldPath.size > 1 && oldPath.zipWithNext().all { (a, b) ->
                PlanningSegmentClearance.allows(terrain, a, b, radius)
            }
            val repairActionId = if (pathDiagnostics != null) checkNotNull(unit.originMoveId) else (unit.originMoveId ?: "repair:${unit.unitId}")
            val connectorRes = if (tailIsClear) {
                resolveBudgetedTacticalRoute(
                    unit = unit,
                    map = map,
                    actionId = repairActionId,
                    atStep = unit.originAtStep,
                    target = first,
                    reason = PathSearchReason.ROUTE_REPAIR,
                    executionStep = executionStep,
                    isRepair = true,
                    preservedSuffix = oldPath.drop(1),
                    fallbackFullTarget = target,
                    mustCompleteInStep = true,
                )
            } else null
            val finalRes = when (connectorRes) {
                is TacticalRouteResolution.Ready -> connectorRes
                TacticalRouteResolution.Pending -> TacticalRouteResolution.Pending
                is TacticalRouteResolution.Unreachable, null -> resolveBudgetedTacticalRoute(
                    unit = unit,
                    map = map,
                    actionId = repairActionId,
                    atStep = unit.originAtStep,
                    target = target,
                    reason = PathSearchReason.ROUTE_REPAIR,
                    executionStep = executionStep,
                    targetRange = rangeForSearch,
                    isRepair = true,
                    mustCompleteInStep = true,
                )
            }
            when (finalRes) {
                TacticalRouteResolution.Pending -> Unit
                is TacticalRouteResolution.Unreachable -> {
                    if (replaceInvalidGroupDestination(unit)) continue
                    if (unit.tasks.firstOrNull()?.kind == UnitTaskKind.BUILD) unit.pauseRoute()
                    else unit.cancelRoute()
                    unit.amphibiousState = unit.amphibiousState?.copy(
                        terrainPausedMove = unit.tasks.firstOrNull()?.kind == UnitTaskKind.MOVE,
                    )
                    pendingEvents += Event("movement-cancelled", unit.unitId)
                    pendingEvents += Event("path-unreachable", unit.unitId)
                }
                is TacticalRouteResolution.Ready -> {
                    unit.adoptRoute(
                        finalRes.points,
                        logicalRouteTarget(unit, checkNotNull(unit.moveTarget)),
                        plannedKnowledgeRevision(unit),
                        plannedBuildingRevision(unit),
                    )
                }
            }
        }
    }

    private fun validateAttack(action: AttackAction, attacker: MutableUnit): SimulationError? {
        if (attacker.definition.combat == null) return SimulationError("cannot-attack", "Unit cannot attack")
        val input = checkNotNull(scenario)
        val team = input.players.single { it.playerId == attacker.ownerPlayerId }.teamId
        val target = units[action.targetUnitId]?.takeIf {
            it.alive && isVisibleTo(action.actorPlayerId, it.position, it.nativeTransportPlacement)
        }
        val enemy = target != null && target.ownerPlayerId != attacker.ownerPlayerId &&
            (team == null || input.players.single { it.playerId == target.ownerPlayerId }.teamId != team)
        return if (target == null || !enemy || target.health == null || !CombatTargetQuery.canAttackAirCategory(attacker, target, input.map)) {
            SimulationError("invalid-attack-target", "Attack target is not available")
        } else null
    }

    private fun applyAction(action: Action, executionStep: Long? = null, group: GroupMoveRequest? = null): ActionResult {
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
            steps.current.movePlanning.cancel(unit.unitId)
            steps.current.pendingFlowStart.remove(unit.unitId)
            unit.taskQueue = UnitTaskQueue()
            unit.attackCommand = null
            unit.bomberApproach = null
            unit.cancelRoute(clearRequested = true)
            unit.constructionElapsed = 0f
            unit.constructionWorking = false
        }
        if ((action is MoveAction || action is PatrolAction) && unit.definition.movement.type == MovementType.NONE) {
            return reject(action, "immobile-unit", "Unit cannot move")
        }
        val priorLanding = unit.landingMember
        val result = when (action) {
            is UnitAction -> {
                val offset = unit.definition.attachmentLayout?.offsetActions?.get(action.actionName)
                if (offset != null) {
                    UnitAttachments.adjustOffset(steps.current, unit, action, offset, currentScenario.map)?.let {
                        return reject(action, it.code, it.message)
                    }
                    ActionResult.Accepted(action.actionId, listOf(Event("unit-action-attachment-offset-changed", unit.unitId)))
                } else if (action.actionName == "setRally" && unit.definition.production?.rallyEnabled == true) {
                    if (!BuildingWork.isReady(unit)) return reject(action, "factory-under-construction", "Factory is under construction")
                    val point = action.target
                    if (action.cancelQueued == (point != null)) return reject(action, "invalid-rally-target", "Supply a rally point or clear it")
                    val map = currentScenario.map
                    if (point != null && !MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth,
                            map.heightTiles.toDouble() * map.tileHeight, point, 0.0))
                        return reject(action, "invalid-rally-target", "Rally point is outside the map")
                    unit.factoryRally = point
                    ActionResult.Accepted(action.actionId, listOf(Event(if (point == null) "rally-cleared" else "rally-set", unit.unitId)))
                } else {
                    val error = if (unit.definition.attachmentLayout != null) {
                        UnitAttachments.request(steps.current, unit, action,
                            buildingTerrain?.collisionMap ?: currentScenario.map)
                    } else if (unit.definition.nuclearSilo != null) {
                        val change = NuclearOrdnance.request(unit, action.actionName, action.cancelQueued, checkNotNull(productionLedger), action.target)
                        productionLedger = change.ledger
                        change.error
                    } else withUnitActionQueue(unit) {
                        val change = CustomUnitActions.request(unit, action, steps.current.actionClock, currentScenario,
                            buildingTerrain?.collisionMap ?: currentScenario.map, productionLedger, simulationTime)
                        productionLedger = change.ledger
                        change.error
                    }
                    error?.let {
                        return reject(action, it.code, it.message)
                    }
                    ActionResult.Accepted(action.actionId, listOf(Event(if (action.cancelQueued) "unit-action-cancelled" else "unit-action-queued", unit.unitId)))
                }
            }
            is UnloadAction -> {
                if (!BuildingWork.isReady(unit)) return reject(action, "inactive-unit", "Unfinished units cannot unload")
                Transport.requestUnload(unit, action.unloading, currentScenario.map)?.let { return reject(action, it.code, it.message) }
                ActionResult.Accepted(action.actionId, listOf(Event(if (action.unloading) "unload-started" else "unload-cancelled", unit.unitId)))
            }
            is LoadAction -> {
                val target = units[action.targetUnitId]?.takeIf { it.carriedBy == null && it.attachment == null && !it.reclaimed &&
                    isVisibleTo(action.actorPlayerId, it.position, it.nativeTransportPlacement) }
                    ?: return reject(action, "target-unavailable", "Loading target is not available")
                val change = Transport.request(action, unit, target, units)
                change.error?.let { return reject(action, it.code, it.message) }
                steps.current.movePlanning.cancel(unit.unitId)
                steps.current.pendingFlowStart.remove(unit.unitId)
                change.boardedUnitId?.let { id ->
                    cancelBoardedMove(id)
                    units[id]?.let(spatialIndex::relocate)
                }
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
                if (units[when (action) {
                        is RepairUnitAction -> action.targetUnitId
                        is ReclaimUnitAction -> action.targetUnitId
                        is ResumeConstructionAction -> action.targetUnitId
                        else -> error("Expected a construction target action")
                    }]?.attachment != null)
                    return reject(action, "attached-unit", "Use the chassis slot actions")
                buildingWork.requestTarget(action, unit, currentScenario, units,
                    automaticVisionSnapshots?.getValue(unit.ownerPlayerId))?.let { return reject(action, it.code, it.message) }
                ActionResult.Accepted(action.actionId)
            }
            is BuildAction -> {
                buildingWork.request(action, unit, currentScenario)?.let { return reject(action, it.code, it.message) }
                ActionResult.Accepted(action.actionId)
            }
            is CancelConstructionAction -> {
                if (unit.definition.building == null)
                    return reject(action, "cannot-cancel-construction", "Unit is not a building")
                if (BuildingWork.isReady(unit))
                    return reject(action, "construction-already-complete", "Completed buildings cannot cancel construction")
                val change = buildingWork.cancelSite(unit, units, currentScenario, productionLedger, ordinaryUpgrades)
                    ?: return reject(action, "cannot-cancel-construction", "Building construction cannot be cancelled")
                productionLedger = change.ledger
                ordinaryUpgrades = change.upgrades
                val cargoIds = unit.transportState?.passengerIds
                Transport.onRemoved(unit, units, currentScenario.map)
                spatialIndex.remove(unit.unitId)
                if (cargoIds != null) {
                    for (cid in cargoIds) units[cid]?.let(spatialIndex::relocate)
                }
                missileTargets = missileTargets?.remove(unit.unitId)
                prepareBuildings(automaticVisionSnapshots)?.let(::commitBuildings)
                    ?: buildingTerrain?.let { existing ->
                        buildingTerrain = BuildingTerrain.fromLive(currentScenario.map, units.values, existing)
                    }
                ActionResult.Accepted(action.actionId, change.events)
            }
            is PatrolAction -> {
                if (!action.target.x.isFinite() || !action.target.y.isFinite())
                    return reject(action, "invalid-target", "Target coordinates must be finite")
                val task = UnitTask(UnitTaskKind.PATROL, action.actionId, action.target, atStep = action.atStep)
                if (!action.append || unit.tasks.isEmpty()) {
                    steps.current.movePlanning.cancel(unit.unitId)
                    steps.current.pendingFlowStart.remove(unit.unitId)
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
                steps.current.movePlanning.cancel(unit.unitId)
                steps.current.pendingFlowStart.remove(unit.unitId)
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
                steps.current.movePlanning.cancel(unit.unitId)
                steps.current.pendingFlowStart.remove(unit.unitId)
                unit.attackCommand = null
                unit.bomberApproach = null
                unit.taskQueue = UnitTaskQueue()
                unit.clearWeaponTargets()
                unit.cancelRoute(clearRequested = true)
                ActionResult.Accepted(action.actionId, listOf(Event("movement-cancelled", unit.unitId)))
            }
            is MoveAction -> {
                if (!action.target.x.isFinite() || !action.target.y.isFinite()) {
                    return reject(action, "invalid-target", "Target coordinates must be finite")
                }
                if (!MapBounds.contains(currentScenario.map.widthTiles.toDouble() * currentScenario.map.tileWidth,
                        currentScenario.map.heightTiles.toDouble() * currentScenario.map.tileHeight, action.target, 0.0))
                    return reject(action, "invalid-target", "Target is outside the map")
                if (UnitLocomotion.movement(unit).moveSpeed <= 0.0)
                    return reject(action, "immobile-unit", "Unit cannot move")
                unit.attackCommand = null
                unit.bomberApproach = null
                unit.amphibiousState?.takeIf { it.terrainPausedMove }?.let {
                    unit.amphibiousState = it.copy(terrainPausedMove = false)
                }
                unit.pauseRoute()
                unit.updateNavigation { it.withMoveArrival(null) }
                unit.taskQueue = UnitTaskQueue().append(UnitTask(UnitTaskKind.MOVE, action.actionId, action.target,
                    atStep = action.atStep))
                val optMovement = UnitLocomotion.movement(unit)
                val optShape = unit.definition.movementCollision
                if (optMovement.type != MovementType.NONE && unit.birthRemaining <= 0f &&
                    optShape is MovementCollisionShape.Circle &&
                    (group == null || group.sharedLanding)) {
                    val optTerrain = planningTerrainFor(unit, currentScenario.map)
                    val dx = action.target.x - unit.position.x
                    val dy = action.target.y - unit.position.y
                    val dist = kotlin.math.hypot(dx, dy)
                    val optMap = currentScenario.map
                    val startCol = MapBounds.cell(unit.position.x, optMap.tileWidth, optMap.widthTiles)
                    val startRow = MapBounds.cell(unit.position.y, optMap.tileHeight, optMap.heightTiles)
                    val goalCol = MapBounds.cell(action.target.x, optMap.tileWidth, optMap.widthTiles)
                    val goalRow = MapBounds.cell(action.target.y, optMap.tileHeight, optMap.heightTiles)
                    val targetReachable = optMovement.type == MovementType.AIR ||
                        optTerrain.isCellConnected(startCol, startRow, goalCol, goalRow)
                    if (dist > 1e-4 && targetReachable) {
                        val probeDist = minOf(dist, minOf(currentScenario.map.tileWidth, currentScenario.map.tileHeight).toDouble())
                        val probe = Position(unit.position.x + dx / dist * probeDist, unit.position.y + dy / dist * probeDist)
                        if (optMovement.type == MovementType.AIR ||
                            PlanningSegmentClearance.allows(optTerrain, unit.position, probe, routeRadius(unit))) {
                            unit.updateNavigation { it.withOptimisticSteerTarget(action.target) }
                        }
                    }
                }
                steps.current.pendingFlowStart.remove(unit.unitId)
                val flowDirectEligible = trafficSeparationEnabled &&
                    group != null &&
                    group.sharedLanding &&
                    group.members.size >= ChunkedFlowFieldAdapter.MIN_GROUP_SIZE &&
                    unit.birthRemaining <= 0f &&
                    optMovement.type != MovementType.AIR &&
                    optMovement.type != MovementType.NONE
                if (flowDirectEligible) {
                    val mType = optMovement.type
                    val terrainR = maxOf(1.0, ChunkedFlowFieldAdapter.flowPassabilityRadius(unit, currentScenario.map.tileWidth, currentScenario.map.tileHeight))
                    val maxGroupR = maxOf(
                        terrainR,
                        group.maxRadiusByMovement[mType] ?: terrainR,
                    )
                    val terrain = planningTerrainFor(unit, currentScenario.map, mType)
                    val uCol = MapBounds.cell(unit.position.x, currentScenario.map.tileWidth, currentScenario.map.widthTiles)
                    val uRow = MapBounds.cell(unit.position.y, currentScenario.map.tileHeight, currentScenario.map.heightTiles)
                    val gCol = MapBounds.cell(group.click.x, currentScenario.map.tileWidth, currentScenario.map.widthTiles)
                    val gRow = MapBounds.cell(group.click.y, currentScenario.map.tileHeight, currentScenario.map.heightTiles)
                    val isRectUnit = unit.definition.movementCollision is MovementCollisionShape.Rectangle
                    val rectInOpenWater = !isRectUnit ||
                        terrain.clearanceField().clearanceRings(uCol, uRow) >= ChunkedFlowFieldAdapter.clearanceBandFor(terrain, terrainR)
                    val srcComp = steps.current.flowFields.resolveSourceComponent(terrain, terrainR, gCol, gRow, uCol, uRow)
                    val flowField = if (rectInOpenWater) steps.current.flowFields.ensureFieldForGroup(
                        ownerId = unit.ownerPlayerId,
                        movementType = mType,
                        unitRadius = terrainR,
                        maxGroupRadius = maxGroupR,
                        terrain = terrain,
                        goalWorld = group.click,
                        stepIndex = stepIndex,
                        sourceComponentId = srcComp,
                    ) else null
                    if (flowField != null && pathDiagnostics != null && steps.current.flowFields.consumeInitialDiagnostic(flowField)) {
                        pathDiagnostics?.add(
                            PathSearchSample(
                                action.actionId, unit.unitId, action.atStep,
                                knowledgeStores != null && knowledgeRules?.pathSource == PathInformationSource.PLAYER_KNOWLEDGE,
                                PathSearchOutcome.FOUND, 0L, 0L, 1L, 0L, 0L, 0L, 0L, 0L, 0L, 1,
                                reason = PathSearchReason.USER_MOVE, executionStep = stepIndex,
                                searchTarget = flowField.effectiveGoalWorld, searchStart = unit.position,
                            ),
                        )
                    }
                    unit.updateNavigation {
                        it.withLandingMember(LandingMember(group))
                            .withMotionContract(MotionContract.sharedCluster(group.click))
                    }
                    val goalBlocked = terrain.isBlocked(gCol, gRow)
                    val immobilePocket = srcComp != 0 &&
                        steps.current.flowFields.isSingleCellImmobilePocket(terrain, terrainR, uCol, uRow)
                    if (flowField == null || (goalBlocked && srcComp == 0) || immobilePocket) {
                        steps.current.movePlanning.enqueue(action, null, selectionSize = group.members.size)
                    } else {
                        steps.current.movePlanning.cancel(unit.unitId)
                        steps.current.pendingFlowStart.add(unit.unitId)
                    }
                } else if (group?.sharedLanding == true) {
                    if (optMovement.type == MovementType.AIR) {
                        unit.updateNavigation { it.withMotionContract(MotionContract.pointMove(action.target)) }
                        steps.current.movePlanning.enqueue(action, null, selectionSize = 1)
                    } else {
                        unit.updateNavigation {
                            it.withLandingMember(LandingMember(group))
                                .withMotionContract(MotionContract.sharedCluster(group.click))
                        }
                        steps.current.movePlanning.enqueue(action, null, selectionSize = group.members.size)
                    }
                } else {
                    unit.updateNavigation { it.withMotionContract(MotionContract.pointMove(action.target)) }
                    steps.current.movePlanning.enqueue(action, group, selectionSize = group?.members?.size ?: 1)
                }
                ActionResult.Accepted(action.actionId)
            }
        }
        if (action is StopAction || action is MoveAction || action is AttackAction ||
            action is PatrolAction && !action.append) {
            unit.constructionElapsed = 0f
            unit.constructionWorking = false
        }
        steps.current.landingOccupancy.update(unit)
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
        override fun path(worker: MutableUnit, target: Position, range: Double, task: UnitTask, step: Long): BuildingRouteResolution {
            val map = checkNotNull(scenario).map
            if (worker.birthRemaining > 0f) {
                return BuildingRouteResolution.Ready(
                    BuildingRoute(listOf(target), plannedKnowledgeRevision(worker), plannedBuildingRevision(worker)),
                )
            }
            return when (val res = resolveBudgetedTacticalRoute(
                unit = worker,
                map = map,
                actionId = task.actionId,
                atStep = task.atStep,
                target = target,
                reason = PathSearchReason.CONSTRUCTION_APPROACH,
                executionStep = step,
                targetRange = range,
                isRepair = false,
            )) {
                TacticalRouteResolution.Pending -> BuildingRouteResolution.Pending
                is TacticalRouteResolution.Unreachable -> BuildingRouteResolution.Unreachable
                is TacticalRouteResolution.Ready -> BuildingRouteResolution.Ready(
                    BuildingRoute(res.points, plannedKnowledgeRevision(worker), plannedBuildingRevision(worker)),
                )
            }
        }
        override fun construct(ownerPlayerId: String, typeId: String, position: Position, nonce: String,
                               paidLedger: ProductionLedger): ConstructionRegistration =
            UnitRegistration.construction(steps.current, checkNotNull(scenario), ownerPlayerId, typeId, position, nonce, paidLedger)
    }

    private fun updateConstruction(worker: MutableUnit, input: Scenario, step: Step) {
        val frame = BuildingWorkFrame(input, units, step, automaticVisionSnapshots?.getValue(worker.ownerPlayerId),
            buildingWorkAccess, steeringFaultHook)
        val change = buildingWork.advanceActivity(worker, frame, checkNotNull(productionLedger), ordinaryUpgrades)
        buildingWork = change.work
        productionLedger = change.ledger
        ordinaryUpgrades = change.upgrades
        change.reclaimedUnitId?.let { id ->
            val removed = units.getValue(id)
            val cargoIds = removed.transportState?.passengerIds
            Transport.onRemoved(removed, units, input.map)
            spatialIndex.remove(id)
            if (cargoIds != null) {
                for (cid in cargoIds) units[cid]?.let(spatialIndex::relocate)
            }
            missileTargets = missileTargets?.remove(id)
            buildingTerrain = BuildingTerrain.fromLive(input.map, units.values, checkNotNull(buildingTerrain))
        }
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

    private fun advanceIncome(unit: MutableUnit, delta: Float) {
        val income = unit.definition.income ?: return
        unit.incomeElapsed += delta
        require(unit.incomeElapsed.isFinite()) { "Income elapsed time must remain finite" }
        if (unit.incomeElapsed > income.period - .1f) {
            unit.incomeElapsed -= income.period
            productionLedger = checkNotNull(productionLedger).creditIncome(unit.ownerPlayerId, income.credits)
        }
    }

    private fun advanceUpgrade(unit: MutableUnit) {
        val upgrades = ordinaryUpgrades ?: return
        pendingEvents += applyUpgradeChange(upgrades.advance(unit, checkNotNull(productionLedger)))
        spatialIndex.relocate(unit)
    }

    private fun refundUpgrades(unit: MutableUnit) {
        val upgrades = ordinaryUpgrades ?: return
        pendingEvents += applyUpgradeChange(upgrades.discard(unit, checkNotNull(productionLedger)))
    }

    private fun applyProduction(action: Action, unit: MutableUnit, typeId: String, nonce: String?): ActionResult {
        if (unit.definition.production == null) return reject(action, "cannot-produce", "Unit cannot produce")
        val exitProfile = unit.definition.production?.exitProfile
        if (unit.definition.productionRequiresCompletedConstruction && !BuildingWork.isReady(unit))
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
        val ledger = checkNotNull(productionLedger)
        val production = checkNotNull(factory.definition.production)
        val mobile = production.exitProfile == com.rustedwarfare.reforge.core.content.FactoryExitProfile.AUTHORED_MOBILE
        val map = if (mobile) buildingTerrain?.collisionMap ?: input.map else input.map
        val tick = ledger.advance(factory.unitId, production.speedMultiplier, Transport.allowsProductionCompletion(factory, units)) { type ->
            !mobile || FactoryExit.mobileBirth(factory, input.unitDefinitions.getValue(type), map) != null
        }
        productionLedger = tick.ledger
        tick.completed?.let { completed ->
            UnitRegistration.produced(steps.current, input, factory, completed, map)
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
        val id = UnitRegistration.sandboxId(command.ownerPlayerId, generation, sequence)
        return validateSandboxSpawn(command, id, steps.current)
    }

    private fun validateSandboxSpawn(command: SandboxCommand.Spawn, id: String, state: StepState): SimulationError? {
        val input = checkNotNull(scenario)
        fun rejected(code: String) = SimulationError(code, "Sandbox spawn rejected: $code")
        val definition = input.unitDefinitions[command.typeId] ?: return rejected("unknown-type")
        if (definition.attachmentLayout != null || definition.attachmentModule != null)
            return rejected("opening-only-type")
        if (id in state.units)
            return rejected("duplicate-unit-id")
        if (input.players.none { it.playerId == command.ownerPlayerId }) return rejected("unknown-owner")
        if (command.state == SandboxInitialState.DAMAGED && definition.maximumHealth == null)
            return rejected("unsupported-state")
        if (definition.production != null && state.productionLedger == null) return rejected("unsupported-type")
        if (!command.heading.isFinite() || !MapBounds.containsHull(input.map, command.position,
                command.heading, definition.movementCollision))
            return rejected("invalid-placement")
        if (definition.building?.requiresResourcePool != true && definition.movement.type != MovementType.AIR &&
            CenterTerrain.intersects(state.buildingTerrain?.collisionMap ?: input.map, command.position,
                definition.foundationMovementType)) return rejected("invalid-placement")
        if (definition.building != null) {
            val cells = try {
                BuildingTerrain.placementCells(input.map, definition, command.position, state.buildingTerrain?.cells.orEmpty())
            } catch (failure: BuildingInitializationException) { return rejected("invalid-placement") }
            val minX = cells.minOf { it.first } * input.map.tileWidth.toDouble()
            val maxX = (cells.maxOf { it.first } + 1) * input.map.tileWidth.toDouble()
            val minY = cells.minOf { it.second } * input.map.tileHeight.toDouble()
            val maxY = (cells.maxOf { it.second } + 1) * input.map.tileHeight.toDouble()
            if (state.spatialIndex.queryAllLayersBounds(minX, maxX, minY, maxY).any { unit ->
                    unit.alive && unit.definition.building == null && UnitLocomotion.movement(unit).type != MovementType.AIR &&
                        (MapBounds.cell(unit.position.x, input.map.tileWidth, input.map.widthTiles) to
                            MapBounds.cell(unit.position.y, input.map.tileHeight, input.map.heightTiles)) in cells
                }) return rejected("invalid-placement")
        }
        return null
    }

    internal fun advanceSandbox(step: Step, commands: List<Pair<SandboxCommandRequest, SandboxCommand>>,
                                materializeObservation: Boolean = true):
            Pair<Observation, List<SandboxCommandReceipt>> {
        val results = mutableListOf<SandboxCommandReceipt>()
        val observation = advanceInternal(step, materializeObservation = materializeObservation) {
            for ((request, command) in commands) {
                when (command) {
                    SandboxCommand.Help -> error("Help cannot be queued")
                    is SandboxCommand.Credits -> results += applySandboxCredits(request, command, step.stepIndex)
                    is SandboxCommand.Destroy -> results += applySandboxUnitChange(request, command, command.unitId, null, step.stepIndex)
                    is SandboxCommand.Health -> results += applySandboxUnitChange(request, command, command.unitId, command.amount, step.stepIndex)
                    is SandboxCommand.SpawnBatch -> results += applySandboxBatch(request, command, step.stepIndex)
                    is SandboxCommand.ProjectileBatch -> results += applySandboxProjectiles(request, command, step.stepIndex)
                    is SandboxCommand.Spawn -> {
                        val id = UnitRegistration.sandboxId(command.ownerPlayerId, request.generation, request.sequence)
                        val error = validateSandboxSpawn(command, id, steps.current)
                        if (error != null) {
                            results += SandboxCommandReceipt(request.sequence, request.generation, world.id,
                                SandboxCommandStatus.REJECTED, command, step.stepIndex, code = error.code)
                        } else {
                            val born = UnitRegistration.sandbox(steps.current, checkNotNull(scenario), command, id)
                            pendingEvents += Event("unit-spawned", born.unitId)
                            results += SandboxCommandReceipt(request.sequence, request.generation, world.id,
                                SandboxCommandStatus.APPLIED, command, step.stepIndex, born.unitId)
                        }
                    }
                }
            }
        }
        return observation to results
    }

    private fun applySandboxCredits(request: SandboxCommandRequest, command: SandboxCommand.Credits,
                                    stepIndex: Long): SandboxCommandReceipt {
        fun rejected(code: String) = SandboxCommandReceipt(request.sequence, request.generation, world.id,
            SandboxCommandStatus.REJECTED, command, stepIndex, code = code)
        val player = command.playerId
        if (checkNotNull(scenario).players.none { it.playerId == player }) return rejected("unknown-owner")
        val ledger = productionLedger ?: return rejected("economy-unavailable")
        return when (val adjusted = ledger.adjustSandboxCredits(player, command.amount) { sandboxRefunds(player) }) {
            is SandboxCreditAdjustment.Rejected -> rejected(adjusted.code)
            is SandboxCreditAdjustment.Accepted -> {
                productionLedger = adjusted.ledger
                SandboxCommandReceipt(request.sequence, request.generation, world.id,
                    SandboxCommandStatus.APPLIED, command, stepIndex, credits = adjusted.change)
            }
        }
    }

    private fun sandboxRefunds(player: String): Sequence<Long> = sequence {
        for (unit in units.values) {
            if (!unit.alive || unit.ownerPlayerId != player) continue
            scenario?.let { input ->
                val siteRefund = buildingWork.siteCancellationRefund(unit, input)
                if (siteRefund > 0L) yield(siteRefund)
            }
            ordinaryUpgrades?.pendingRefunds(unit.unitId)?.let { yieldAll(it) }
            for (item in unit.customActionState.queue) yield(item.cost)
            for (item in unit.ordnance.queue) yield(item.action.cost)
            yield(unit.attachmentInterceptor.rounds.size * ANTI_NUKE_AMMO_COST)
            when (val order = unit.attachmentOrder) {
                is AttachmentOrder.Paid -> yield(order.cost)
                is AttachmentOrder.Cancel -> {
                    yield(order.action.refund)
                    yield(order.displacedUpgrade?.cost ?: 0L)
                }
                is AttachmentOrder.Reclaim -> yield(order.action.refund)
                null -> Unit
            }
        }
    }

    private fun applySandboxUnitChange(request: SandboxCommandRequest, command: SandboxCommand, unitId: String,
                                       amount: Double?, stepIndex: Long): SandboxCommandReceipt {
        fun rejected(code: String) = SandboxCommandReceipt(request.sequence, request.generation, world.id,
            SandboxCommandStatus.REJECTED, command, stepIndex, code = code)
        val unit = units[unitId] ?: return rejected("unknown-unit")
        if (!unit.alive || unit.deathPending || unit.reclaimed) return rejected("inactive-unit")
        if (unit.definition.attachmentModule?.empty == true) return rejected("unit-unavailable")
        val binding = unit.attachment
        if (binding != null) {
            val parent = units[binding.parentId]
            if (parent == null || !parent.alive || parent.deathPending || parent.reclaimed)
                return rejected("inactive-unit")
        }
        val before = unit.health
        val after = if (amount == null) before?.let { 0.0 } else {
            if (before == null) return rejected("health-unavailable")
            (before + amount).coerceIn(0.0, checkNotNull(unit.definition.maximumHealth))
        }
        unit.health = after
        if (after == null) unit.destroyedWithoutHealth = true
        val destroyed = amount == null || after == 0.0
        unit.sandboxPreservesPositiveAttachmentHealth = binding != null && after != null && after > 0.0 && after < 1.0
        if (binding == null && destroyed) processDeath(unit)
        return SandboxCommandReceipt(request.sequence, request.generation, world.id, SandboxCommandStatus.APPLIED,
            command, stepIndex, unitId, unitChange = SandboxUnitChange(unitId, before, after, destroyed))
    }

    private fun applySandboxBatch(request: SandboxCommandRequest, batch: SandboxCommand.SpawnBatch,
                                  atStep: Long): SandboxCommandReceipt {
        val current = steps.current
        // Registration updates only the new unit and replaces the registries and terrain it touches.
        val stagedUnits = LinkedHashMap(current.units)
        val staged = current.copy(units = stagedUnits, spatialIndex = current.spatialIndex.fork(stagedUnits))
        val generated = linkedMapOf<String, String>()
        val input = checkNotNull(scenario)
        for ((index, entry) in batch.entries.withIndex()) {
            val spawn = entry.spawn
            val id = UnitRegistration.sandboxBatchId(spawn.ownerPlayerId, request.generation, request.sequence, index)
            val reason = validateSandboxSpawn(spawn, id, staged)?.code
                ?: "deployment-identities-exhausted".takeIf {
                    staged.nextObjectId == Long.MAX_VALUE || staged.lastBirthSerial == Long.MAX_VALUE
                }
            if (reason != null) return SandboxCommandReceipt(request.sequence, request.generation, world.id,
                SandboxCommandStatus.REJECTED, batch, atStep, code = reason, failedEntry = index)
            UnitRegistration.sandbox(staged, input, spawn, id)
            generated[entry.alias] = id
        }
        current.units = staged.units
        current.spatialIndex = staged.spatialIndex
        current.productionLedger = staged.productionLedger
        current.ordinaryUpgrades = staged.ordinaryUpgrades
        current.buildingTerrain = staged.buildingTerrain
        current.nextObjectId = staged.nextObjectId
        current.lastBirthSerial = staged.lastBirthSerial
        for (id in generated.values) pendingEvents += Event("unit-spawned", id)
        return SandboxCommandReceipt(request.sequence, request.generation, world.id, SandboxCommandStatus.APPLIED,
            batch, atStep, generated = Collections.unmodifiableMap(generated))
    }

    private fun applySandboxProjectiles(request: SandboxCommandRequest, batch: SandboxCommand.ProjectileBatch,
                                        atStep: Long): SandboxCommandReceipt {
        val input = checkNotNull(scenario)
        val staged = linkedMapOf<String, MutableProjectile>()
        var id = nextObjectId
        for ((index, entry) in batch.entries.withIndex()) {
            fun reject(reason: String) = SandboxCommandReceipt(request.sequence, request.generation, world.id,
                SandboxCommandStatus.REJECTED, batch, atStep, code = reason, failedEntry = index)
            if (id == Long.MAX_VALUE) return reject("deployment-identities-exhausted")
            if (input.combatContract == null) return reject("deployment-combat-unavailable")
            val source = units[entry.sourceUnitId]?.takeIf { it.alive && it.carriedBy == null && BuildingWork.isReady(it) }
                ?: return reject("unknown-projectile-source")
            val target = units[entry.targetUnitId]?.takeIf { CombatTargetQuery.isEnemy(source, it, input.players) }
                ?: return reject("unknown-projectile-target")
            val shot = if (source.definition.nuclearSilo?.kind == NuclearSiloKind.LAUNCHER) {
                if (entry.weapon != null && entry.weapon != "siloTop") return reject("unknown-projectile-weapon")
                if (!input.nukesEnabled) return reject("nukes-disabled")
                NuclearOrdnance.projectile(source, PendingOrdnance(OrdnanceAction.LAUNCH, target = target.position), id)
            } else {
                // Depth and flight conversions use specialized projectile factories outside this finite setup command.
                if (source.definition.submarine != null || source.amphibiousState != null ||
                    source.definition.transport == TransportProfile.NAUTILUS_SUBMERGED)
                    return reject("unsupported-projectile-source-form")
                val combat = UnitCombat.profile(source) ?: return reject("unknown-projectile-weapon")
                val catalog = combat.weaponAssembly
                val named = if (entry.weapon == null) catalog.slots.singleOrNull() else catalog.byName[entry.weapon]
                if (named == null && (entry.weapon != null || catalog.slots.isNotEmpty()))
                    return reject("unknown-projectile-weapon")
                val definition = named?.definition
                if (!CombatTargetQuery.canAttackAirCategory(source, target, input.map) ||
                    definition?.let { !it.canShoot || !CombatTargetQuery.weaponAllows(target, it) ||
                        !CombatTargetQuery.conditionAllows(source, target, it, input.map) } == true)
                    return reject("projectile-target-ineligible")
                val effective = if (catalog.usesNamedState) named?.profile ?: combat else combat
                val profile = effective.projectile
                if (profile.ground != null || profile.missile != null || profile.beam != null || profile.flame || profile.lightning ||
                    profile.retargetOnDeath)
                    return reject("unsupported-deployment-projectile")
                val heading = ScreenPlane.desired(source.position, target.position)
                val pose = definition?.let { source.namedWeapons?.get(it.name) } ?: source.combat
                    ?: return reject("unknown-projectile-weapon")
                val aimed = pose.copy(turretHeading = heading)
                val muzzle = if (definition == null || !catalog.usesNamedState) OrdinaryWeapon.muzzle(source, aimed, effective)
                    else NamedWeaponRuntime.muzzle(source, definition, aimed)
                MutableProjectile(id, source.unitId, target.unitId, muzzle, heading, profile.life, Position(0.0, 0.0),
                    0f, profile.speed, profile.directDamage, source.height, deflectionHealth = profile.deflectionHealth,
                    instant = profile.instant, nonDeflectable = profile.nonDeflectable, sourceWeaponName = definition?.name,
                    acceleration = profile.acceleration, splash = profile.splash, ballistic = profile.ballistic,
                    damageModifiers = profile.damageModifiers, sourceOwnerId = source.ownerPlayerId,
                    steering = profile.steering, endOfLife = profile.endOfLife)
            }
            staged[entry.alias] = shot
            id++
        }
        nextObjectId = id
        for (shot in staged.values) {
            projectiles += shot
            pendingProjectileEvents += ProjectileEventObservation("born", projectileObservation(shot))
        }
        return SandboxCommandReceipt(request.sequence, request.generation, world.id, SandboxCommandStatus.APPLIED,
            batch, atStep, projectileIds = Collections.unmodifiableMap(staged.mapValues { it.value.objectId }))
    }

    internal fun sandboxProjectileEvidence(ids: Set<Long>): List<SandboxProjectileEvidence> {
        val observation = observe()
        val live = observation.projectiles.filter { it.objectId in ids }.map {
            SandboxProjectileEvidence(it.objectId, "live", it.position)
        }
        val events = observation.projectileEvents.filter { it.projectile.objectId in ids }.map {
            SandboxProjectileEvidence(it.projectile.objectId, it.kind, it.projectile.position, it.interceptorUnitId)
        }
        return events + live
    }

    fun advance(step: Step): Observation = advanceInternal(step) {}

    internal fun advanceWithInjectedFailureForTest(step: Step): Observation =
        advanceInternal(step, materializeObservation = true, injectFailureAfterUpdate = true) {}

    private fun advanceInternal(step: Step, materializeObservation: Boolean = true,
                                injectFailureAfterUpdate: Boolean = false,
                                deploy: () -> Unit): Observation {
        check(!closed) { "Simulation is closed" }
        val currentScenario = checkNotNull(scenario)
        require(step.stepIndex >= 0 && (!hasAdvanced || step.stepIndex > stepIndex)) {
            "stepIndex must strictly increase from $stepIndex: ${step.stepIndex}"
        }
        FixedSimTick.validate(step, currentScenario.timeModel)
        require(scheduledActions.none { checkNotNull(it.atStep) < step.stepIndex }) {
            "advance cannot skip a scheduled action step"
        }
        val actions = scheduledActions.filter { it.atStep == step.stepIndex }
        val prof = hotspotCollector
        return steps.advance(currentScenario, step, actions,
            { index, time, nextRevision -> makeObservation(index, time, true, nextRevision) },
            materializeObservation = materializeObservation,
            worldId = "${currentScenario.scenarioId}:$worldGeneration",
            profiler = prof) { candidate ->
            var phaseMark = if (prof != null) System.nanoTime() else 0L
            combatFrame += 1
            steps.current.actionClock = (steps.current.actionClock.toFloat() + step.delta.toFloat() * 16.666666f).toInt()
            deploy()
            buildingWork = buildingWork.advanceClock(step.delta)
            actions.forEach { applyAction(it, step.stepIndex) }
            if (prof != null) {
                val now = System.nanoTime()
                prof.addNanos(SimHotspotStage.SIM_COMBAT, now - phaseMark)
                phaseMark = now
            }
            val wallDue = candidate.state.sharedWallRepairDue(step.stepIndex)
            val needsGateWork = candidate.state.gateWorkDeferred
            val stepBudgetLimit = movePlanningBudgetPerStep
            val reserve = if (needsGateWork || wallDue) stepBudgetLimit / 4 else 0
            val planningBudget = PathWorkBudget(stepBudgetLimit - reserve)
            val planningSlices = candidate.state.planningWorkDiagnostics?.let { mutableListOf<PlanningWorkSlice>() }
            val stepTacticalBudget = PathWorkBudget(stepBudgetLimit)
            activeStepPlanningBudget = stepTacticalBudget
            activeStepPlanningSlices = planningSlices
            try {
            val serveLandingsFirst = candidate.state.landingsShortOfBudget &&
                !candidate.state.movePlanning.hasPendingFastLaneOrders()
            candidate.state.landingsShortOfBudget = false
            if (serveLandingsFirst) {
                val landingBudget = PathWorkBudget(planningBudget.limit / 4)
                advanceSharedLandings(currentScenario.map, landingBudget, planningSlices)
                check(planningBudget.take(landingBudget.used))
            }
            val p2UsedFirstPass = candidate.state.movePlanning.advance(pathPolicy, planningBudget,
                { requests, group, repair -> movePlanningInput(currentScenario.map, requests, group, repair, step.delta) },
                { member, group, repair, occupants, plan ->
                    publishMove(member, group, repair, occupants, plan, step.stepIndex)
                    units[member.action.unitId]?.let(candidate.state.landingOccupancy::update)
                },
                planningSlices, liveUnitCount = { units.size }, executionStep = step.stepIndex)
            if (!serveLandingsFirst) advanceSharedLandings(currentScenario.map, planningBudget, planningSlices)
            // Charge stepTacticalBudget with initial MovePlanning + SharedLandings work so replan/repair shares remaining step budget
            if (planningBudget.used > 0) stepTacticalBudget.take(planningBudget.used)
            drainCompletedRepairTacticalRoutes(currentScenario.map, step.stepIndex)
            refreshGroupDestinations(currentScenario.map, step.delta)
            for (unit in orderedUnits()) if (unit.alive && unit.carriedBy == null && unit.tasks.firstOrNull()?.kind == UnitTaskKind.PATROL)
                retainedPatrolTarget(unit)
            replanForKnowledge(currentScenario.map, step.stepIndex)
            repairInvalidConnections(currentScenario.map, step.stepIndex)
            // Sync any tactical work consumed inside replanForKnowledge / repairInvalidConnections back into planningBudget
            val extraSync = (stepTacticalBudget.used - planningBudget.used).coerceAtLeast(0)
            if (extraSync > 0) planningBudget.take(minOf(extraSync, planningBudget.remaining))
            // 0-tick static obstruction response (ENG-P016-005): immediately spend remaining step budget
            // on newly queued CONNECTION/KNOWLEDGE repairs in this same step without rotating queues a
            // second time and while honoring the step-wide P2 budget cap.
            if (!planningBudget.exhausted && candidate.state.movePlanning.hasPendingRepairs()) {
                val beforeSecondPass = planningBudget.used
                candidate.state.movePlanning.advance(pathPolicy, planningBudget,
                    { requests, group, repair -> movePlanningInput(currentScenario.map, requests, group, repair, step.delta) },
                    { member, group, repair, occupants, plan ->
                        publishMove(member, group, repair, occupants, plan, step.stepIndex)
                        units[member.action.unitId]?.let(candidate.state.landingOccupancy::update)
                    },
                    planningSlices, liveUnitCount = { units.size }, executionStep = step.stepIndex,
                    rotateQueues = false, p2BudgetAlreadyUsed = p2UsedFirstPass)
                val secondDelta = planningBudget.used - beforeSecondPass
                if (secondDelta > 0) stepTacticalBudget.take(minOf(secondDelta, stepTacticalBudget.remaining))
                drainCompletedRepairTacticalRoutes(currentScenario.map, step.stepIndex)
            }
            val remaining = (stepBudgetLimit - stepTacticalBudget.used).coerceAtLeast(0)
            val orderedForStep = orderedUnits()
            val hasUnitLoopTacticalDemand = orderedForStep.any { u ->
                u.alive && u.carriedBy == null && (
                    u.attackCommand != null ||
                    (u.birthRemaining > 0f && u.birthRemaining <= 1f && u.moveTarget != null) ||
                    (u.tasks.firstOrNull()?.kind.let { k ->
                        k == UnitTaskKind.BUILD || k == UnitTaskKind.CONSTRUCT || k == UnitTaskKind.RECLAIM ||
                            k == UnitTaskKind.PATROL || k == UnitTaskKind.LOAD_INTO || k == UnitTaskKind.LOAD_UP
                    } && (u.moveTarget == null || u.pathPoints.isEmpty()))
                )
            }
            val backgroundSlack = if (hasUnitLoopTacticalDemand) {
                val p0Reserve = minOf(remaining, maxOf(8, stepBudgetLimit * 3 / 4))
                (remaining - p0Reserve).coerceAtLeast(0)
            } else remaining
            val gateBudget = PathWorkBudget(if (wallDue) backgroundSlack / 2 else backgroundSlack)
            val survey = candidate.state.driverPassages
            val passageWaiting = checkNotNull(passageAdmission).waitingUnits(orderedForStep, spatialIndex, step,
                { unit, type -> planningTerrainFor(unit, currentScenario.map, type) }, ::knowsRouteOccupant, survey, gateBudget,
                entranceTracker = candidate.state.passageTracker)
            candidate.state.gateWorkDeferred = survey.shortOfBudget
            val repairBudget = PathWorkBudget((backgroundSlack - gateBudget.used).coerceAtLeast(0))
            val seaDepartureWaiting = FactoryExit.waitingNativeSeaDepartures(currentScenario.map, units)
            val repairSlicesStart = planningSlices?.size ?: 0
            recoverStalledMoves(currentScenario.map, step.stepIndex, passageWaiting, seaDepartureWaiting, repairBudget)
            val localWork = gateBudget.used + repairBudget.used
            if (localWork > 0) {
                stepTacticalBudget.take(minOf(localWork, stepTacticalBudget.remaining))
                val tacticalRepairWork = if (planningSlices != null) {
                    (repairSlicesStart until planningSlices.size).sumOf { planningSlices[it].workUnits }
                } else 0
                val summaryLocalWork = (localWork - tacticalRepairWork).coerceAtLeast(0)
                if (summaryLocalWork > 0) {
                    planningSlices?.add(
                        PlanningWorkSlice(
                            emptyList(), PathSearchReason.ROUTE_REPAIR,
                            summaryLocalWork, !survey.shortOfBudget && !repairBudget.exhausted, false,
                        ),
                    )
                }
            }
            prepareRouteSuccessors(currentScenario.map, step.stepIndex, candidate.routeContinuationSamples)
            if (prof != null) {
                val now = System.nanoTime()
                prof.addNanos(SimHotspotStage.NAV_PLAN, now - phaseMark)
                phaseMark = now
            }
            spatialIndex.beginStep(canDisplace = ::canDisplaceInContact)
            if (prof != null) {
                val now = System.nanoTime()
                prof.addNanos(SimHotspotStage.SIM_CONTACT, now - phaseMark)
                phaseMark = now
            }
            if (trafficSeparationEnabled && candidate.state.flowFields.activeFieldCount() > 0) {
                val p1Budget = maxOf(2048, stepBudgetLimit)
                candidate.state.flowFields.advanceP1Budget(
                    units = orderedUnits(),
                    spatialIndex = spatialIndex,
                    terrainLookup = { u, mt -> planningTerrainFor(u, currentScenario.map, mt) },
                    stepIndex = step.stepIndex,
                    maxExpansions = p1Budget,
                )
                val diagList = candidate.state.pathDiagnostics
                if (diagList != null) {
                    val rebuilt = candidate.state.flowFields.drainTerrainRebuildDiagnostics()
                    for ((ownerId, goalPos) in rebuilt) {
                        val rep = orderedUnits().firstOrNull { it.ownerPlayerId == ownerId && it.moveTarget != null }
                        diagList += PathSearchSample(
                            rep?.originMoveId ?: "flow-replan:${step.stepIndex}",
                            rep?.unitId ?: ownerId,
                            rep?.originAtStep,
                            knowledgeStores != null && knowledgeRules?.pathSource == PathInformationSource.PLAYER_KNOWLEDGE,
                            PathSearchOutcome.FOUND, 0L, 0L, 1L, 0L, 0L, 0L, 0L, 0L, 0L, 1,
                            reason = PathSearchReason.KNOWLEDGE_REPLAN, executionStep = step.stepIndex,
                            searchTarget = goalPos, searchStart = rep?.position ?: goalPos,
                        )
                    }
                }
            }
            // True O(1) shared flow-field direct drive (ENG-P016-009D):
            // Drain pendingFlowStart units even if activeFieldCount() dropped to 0 (e.g. rapid re-click
            // cancellation or LRU eviction), falling back to MovePlanning immediately if no field remains.
            if (trafficSeparationEnabled && candidate.state.pendingFlowStart.isNotEmpty()) {
                var fastStartedAny = false
                val pendingIds = candidate.state.pendingFlowStart.toList()
                for (uid in pendingIds) {
                    val u = units[uid]
                    if (u == null || !u.alive || u.carriedBy != null || u.attachment != null || u.moveTarget != null) {
                        candidate.state.pendingFlowStart.remove(uid)
                        continue
                    }
                    val lm = u.landingMember
                    val headTask = u.tasks.firstOrNull()
                    if (lm == null || lm.parked || lm.claimed != null ||
                        headTask == null ||
                        (headTask.kind != UnitTaskKind.MOVE && headTask.motionContract?.isSharedCluster != true) ||
                        headTask.target != lm.group.click
                    ) {
                        candidate.state.pendingFlowStart.remove(uid)
                        continue
                    }
                    val uTerrain = planningTerrainFor(u, currentScenario.map)
                    val field = candidate.state.flowFields.fieldForUnit(u, uTerrain)
                    if (field == null) {
                        candidate.state.pendingFlowStart.remove(uid)
                        candidate.state.movePlanning.enqueue(
                            MoveAction(headTask.actionId, u.ownerPlayerId, u.unitId, lm.group.click, world, headTask.atStep),
                            null,
                            selectionSize = lm.group.members.size,
                        )
                    } else if (candidate.state.flowFields.hasValidGuidanceFor(u, uTerrain)) {
                        candidate.state.pendingFlowStart.remove(uid)
                        val effectiveTarget = if (field.goalWasNearestFallback) field.effectiveGoalWorld else lm.group.click
                        if (field.goalWasNearestFallback && headTask.target != effectiveTarget) {
                            u.taskQueue = u.taskQueue.replaceHead(headTask.copy(target = effectiveTarget))
                        }
                        val flowContract = headTask.motionContract ?: MotionContract.sharedCluster(lm.group.click)
                        u.updateNavigation {
                            it.bindCommandTarget(
                                target = effectiveTarget,
                                originMoveId = headTask.actionId,
                                originAtStep = headTask.atStep,
                                contract = flowContract,
                                followsManeuverRoute = false,
                                nearestMoveTarget = if (field.goalWasNearestFallback) lm.group.click else it.nearestMoveTarget,
                                nearestMoveIsGroup = if (field.goalWasNearestFallback) true else it.nearestMoveIsGroup,
                                nearTargetTime = 0.0,
                            )
                        }
                        u.adoptRoute(
                            listOf(effectiveTarget),
                            logicalRouteTarget(u, effectiveTarget),
                            plannedKnowledgeRevision(u),
                            plannedBuildingRevision(u),
                        )
                        candidate.state.landingOccupancy.update(u)
                        pendingEvents += Event("movement-started", u.unitId)
                        fastStartedAny = true
                    } else if (candidate.state.flowFields.isUnreachableInCompletedField(u, uTerrain)) {
                        candidate.state.pendingFlowStart.remove(uid)
                        candidate.state.movePlanning.enqueue(
                            MoveAction(headTask.actionId, u.ownerPlayerId, u.unitId, lm.group.click, world, headTask.atStep),
                            null,
                            selectionSize = lm.group.members.size,
                        )
                    }
                }
                if (fastStartedAny) {
                    if (prof != null) {
                        val now = System.nanoTime()
                        prof.addNanos(SimHotspotStage.NAV_FLOW, now - phaseMark)
                        phaseMark = now
                    }
                    spatialIndex.beginStep(canDisplace = ::canDisplaceInContact)
                    if (prof != null) {
                        val now = System.nanoTime()
                        prof.addNanos(SimHotspotStage.SIM_CONTACT, now - phaseMark)
                        phaseMark = now
                    }
                }
            }
            if (prof != null) {
                val now = System.nanoTime()
                prof.addNanos(SimHotspotStage.NAV_FLOW, now - phaseMark)
                phaseMark = now
            }
            var unitPlanNanos = 0L
            var unitAvoidNanos = 0L
            for (unit in orderedUnits().toList()) {
                val attachment = unit.attachment
                if (attachment != null) {
                    if (!unit.alive) continue
                    val parent = units[attachment.parentId]?.takeIf { it.alive && it.carriedBy == null && !it.reclaimed } ?: continue
                    if (parent.deathPending && attachment.slot.number in parent.attachmentOffsets) continue
                    UnitAttachments.synchronize(parent, unit, attachment.slot)
                    spatialIndex.relocate(unit)
                    if (BuildingWork.isReady(unit)) {
                        updatePointDefense(unit, step.delta)
                        updateProjectileInterception(unit, currentScenario)
                    }
                    UnitAttachments.advance(steps.current, currentScenario, parent, unit, step.delta.toFloat())
                    if (BuildingWork.isReady(unit)) {
                        AttachmentBlink.finishSlot(steps.current, step, parent, unit)
                        if (unit.definition.attachmentModule?.interception != null)
                            AttachmentInterceptor.advance(steps.current, unit, step.delta.toFloat())
                        advanceIncome(unit, step.delta.toFloat())
                        UnitAttachments.advanceAutomatic(steps.current, currentScenario, parent, unit, step.delta.toFloat())
                        val preferredTarget = units[parent.attackCommand?.targetUnitId ?: parent.primaryCombatTargetId]
                        updateWeapon(unit, step.delta, preferredTarget)
                        advanceWeaponCooldown(unit, step.delta)
                        regenerateHealth(unit, step.delta.toFloat())
                    }
                    continue
                }
                if (unit.deathPending) {
                    unit.deathPending = false
                    processDeath(unit)
                }
                if (!unit.alive) {
                    AmphibiousJetMotion.advanceCorpse(unit, currentScenario.map, step.delta)
                    WaterBody.advanceCorpse(unit, currentScenario.map, step.delta)
                    HoverMotion.advanceCorpse(unit, currentScenario.map, step.delta)
                    Transport.advanceCorpse(unit, currentScenario.map, step.delta)
                    val corpseCargo = unit.transportState?.passengerIds
                    NautilusMotion.advanceCorpse(unit, units, currentScenario.map, step.delta)
                    if (corpseCargo != null && unit.transportState?.passengerIds.isNullOrEmpty()) {
                        for (cid in corpseCargo) units[cid]?.let(spatialIndex::relocate)
                    }
                    AaBeamGunship.advanceCorpse(unit, currentScenario.map, step.delta)
                    continue
                }
                if (unit.carriedBy != null) continue
                UnitAttachments.advanceOpeningFall(unit, step.delta.toFloat())
                spatialIndex.relocate(unit)
                if (unit.definition.energy?.dieOnZero == true && BuildingWork.isReady(unit)) {
                    WeaponEnergy.advance(unit, step.delta)
                    if (unit.energy <= 0f) {
                        unit.health = 0.0
                        processDeath(unit)
                        continue
                    }
                }
                UnitShield.advance(unit, step.delta.toFloat())
                Bunker.advanceHeight(unit, step.delta)
                spatialIndex.relocate(unit)
                if (unit.combat == null) MechBody.advance(unit, step.delta)
                val previousRequest = unit.beginMovementStep()
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
                loading?.boardedUnitId?.let { id ->
                    cancelBoardedMove(id)
                    units[id]?.let(spatialIndex::relocate)
                }
                loading?.approach?.let { target ->
                    unit.requestMovement(target)
                    val endpoint = unit.moveTarget
                    if (endpoint == null || ScreenPlane.distanceSquared(endpoint, target) >= 441.0 || unit.pathPoints.isEmpty()) {
                        val planT0 = if (prof != null) System.nanoTime() else 0L
                        val loadTask = unit.tasks.first()
                        val res = resolveBudgetedTacticalRoute(
                            unit = unit,
                            map = currentScenario.map,
                            actionId = loadTask.actionId,
                            atStep = loadTask.atStep,
                            target = target,
                            reason = PathSearchReason.USER_MOVE,
                            executionStep = step.stepIndex,
                            targetRange = 21.0,
                            isRepair = false,
                        )
                        if (prof != null) unitPlanNanos += (System.nanoTime() - planT0).coerceAtLeast(0L)
                        when (res) {
                            TacticalRouteResolution.Pending -> Unit
                            is TacticalRouteResolution.Unreachable -> unit.cancelRoute()
                            is TacticalRouteResolution.Ready -> {
                                val loadEnd = res.points.last()
                                val loadContract = loadTask.motionContract ?: MotionContract.rangeApproach(
                                    center = target,
                                    range = 21.0,
                                    tracksRouteStall = false,
                                    enableLocalAvoidance = false,
                                    isBoardingApproach = true,
                                )
                                unit.updateNavigation {
                                    it.bindCommandTarget(
                                        target = loadEnd,
                                        originMoveId = loadTask.actionId,
                                        originAtStep = loadTask.atStep,
                                        contract = loadContract,
                                    )
                                }
                                unit.adoptRoute(res.points, loadEnd, plannedKnowledgeRevision(unit), plannedBuildingRevision(unit))
                            }
                        }
                    }
                }
                if (unit.carriedBy != null) continue
                unit.snapshotDispatchWindow()
                if ((unit.definition.builder != null || unit.definition.maintenance != null) && BuildingWork.isReady(unit)) {
                    val planT0 = if (prof != null && constructionActivity) System.nanoTime() else 0L
                    updateConstruction(unit, currentScenario, step)
                    if (planT0 != 0L) unitPlanNanos += (System.nanoTime() - planT0).coerceAtLeast(0L)
                }
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
                if (BuildingWork.isReady(unit)) advanceIncome(unit, step.delta.toFloat())
                // A construction pop releases the next activity for the next update only.
                if (!constructionActivity) {
                    val isPatrol = unit.tasks.firstOrNull()?.kind == UnitTaskKind.PATROL
                    val measureRoute = prof != null && (isPatrol || unit.attackCommand != null)
                    val planT0 = if (measureRoute) System.nanoTime() else 0L
                    if (isPatrol)
                        updatePatrolRoute(unit, currentScenario.map, step.stepIndex, previousRequest)
                    else updateAttackRoute(unit, currentScenario.map, step.stepIndex)
                    if (planT0 != 0L) unitPlanNanos += (System.nanoTime() - planT0).coerceAtLeast(0L)
                }
                val fourLegged = unit.definition.mech?.kind == com.rustedwarfare.reforge.core.content.MechKind.FOUR_LEGGED
                val head = unit.tasks.firstOrNull()
                if (unit.moveTarget == null && head?.factoryExit == true && unit.birthRemaining == 0f)
                    unit.taskQueue = unit.taskQueue.pop()
                val queuedMove = unit.tasks.firstOrNull()?.takeIf { it.kind == UnitTaskKind.MOVE }
                if (queuedMove != null && (queuedMove.startsAfterFactoryExit && unit.birthRemaining == 0f ||
                        unit.definition.groundHeight != null || com.rustedwarfare.reforge.core.content.NautilusRules.supports(unit.definition)) &&
                    unit.definition.movement.type != MovementType.NONE && unit.moveTarget == null &&
                    !candidate.state.movePlanning.pending(unit.unitId) &&
                    unit.unitId !in candidate.state.pendingFlowStart) {
                    val qTarget = checkNotNull(queuedMove.target)
                    val qContract = queuedMove.motionContract ?: MotionContract.pointMove(
                        target = qTarget,
                        allowShortReverse = !queuedMove.factoryExit,
                        gatheringTarget = if (queuedMove.factoryExit) null else qTarget,
                    )
                    unit.updateNavigation {
                        it.bindCommandTarget(
                            target = qTarget,
                            originMoveId = queuedMove.actionId,
                            originAtStep = queuedMove.atStep,
                            contract = qContract,
                        )
                    }
                    val planT0 = if (prof != null) System.nanoTime() else 0L
                    resumeProductionRoute(unit, currentScenario.map, step.stepIndex)
                    if (prof != null) unitPlanNanos += (System.nanoTime() - planT0).coerceAtLeast(0L)
                    if (unit.moveTarget == null && !candidate.state.movePlanning.hasPendingTactical(unit.unitId) && queuedMove.startsAfterFactoryExit) {
                        unit.taskQueue = unit.taskQueue.pop()
                    }
                }
                val trackAvoid = prof != null && unit.definition.movement.type != MovementType.NONE &&
                    (unit.moveTarget != null || unit.speed != 0.0 || unit.angularVelocity != 0.0 ||
                        unit.definition.movement.type == MovementType.AIR || unit.definition.movement.hover != null ||
                        unit.definition.groundHeight != null || unit.definition.amphibiousJet != null ||
                        unit.transportState != null)
                if (fourLegged) {
                    val avoidT0 = if (trackAvoid) System.nanoTime() else 0L
                    updateUnit(unit, currentScenario.map, step.delta, step.stepIndex, candidate,
                        passageWaiting = unit.unitId in passageWaiting, departureWaiting = unit.unitId in seaDepartureWaiting)
                    if (avoidT0 != 0L) unitAvoidNanos += (System.nanoTime() - avoidT0).coerceAtLeast(0L)
                }
                val blinkCooldown = unit.blinkCooldownUntil
                val actionForm = unit.definition
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
                    updateProjectileInterception(unit, currentScenario)
                    if (unit.definition.energy?.dieOnZero != true) WeaponEnergy.advance(unit, step.delta)
                    if ((!factoryWork || factoryActionAtHead) && withUnitActionQueue(unit) {
                            CustomUnitActions.advanceQueue(unit, currentScenario.unitDefinitions, step.delta, steps.current.actionClock,
                                steps.current) { launch, target ->
                                launchScoutPayload(unit, launch.turretName, target)
                            }
                        })
                        pendingEvents += Event("unit-action-completed", unit.unitId)
                    if (actionForm !== unit.definition) spatialIndex.relocate(unit)
                    if (actionForm !== unit.definition && (actionForm.blink != null || unit.definition.blink != null))
                        productionLedger = CustomUnitActions.reconcileBlinkConversion(unit, productionLedger)
                    CustomUnitActions.constrain(unit, step.delta, unitActionHead(unit))
                }
                val avoidPoseT0 = if (trackAvoid) System.nanoTime() else 0L
                if (unit.definition.blink != null && unit.blinkCooldownUntil > blinkCooldown) {
                    // The destination write preserves the logical order. Rebase the physical route at the new position.
                    resumeProductionRoute(unit, currentScenario.map, step.stepIndex)
                } else if (!fourLegged) {
                    updateUnit(unit, currentScenario.map, step.delta, step.stepIndex, candidate,
                        passageWaiting = unit.unitId in passageWaiting, departureWaiting = unit.unitId in seaDepartureWaiting)
                }
                val acceptsHeight = { height: Double ->
                    val allowed = UnitContact.canChangeHeight(unit, height, spatialIndex,
                        currentScenario.map, buildingTerrain)
                    if (!allowed) {
                        val blocked = Event("movement-blocked", unit.unitId)
                        if (blocked !in pendingEvents) pendingEvents += blocked
                    }
                    allowed
                }
                val passengersBeforeUnload = unit.transportState?.passengerIds
                FlightMotion.advancePose(unit, currentScenario.map, step.delta, acceptsHeight)
                NautilusMotion.advance(unit, step.delta, acceptsHeight)
                Transport.refreshTerrain(unit, currentScenario.map)
                pendingEvents += Transport.advance(unit, units, currentScenario.map, step.delta, acceptsHeight)
                if (passengersBeforeUnload != null) {
                    val remaining = unit.transportState?.passengerIds.orEmpty()
                    if (remaining.size < passengersBeforeUnload.size) {
                        for (pid in passengersBeforeUnload) {
                            if (pid !in remaining) {
                                val unloaded = units[pid]
                                if (unloaded != null) {
                                    spatialIndex.relocate(unloaded)
                                    for (cid in unloaded.attachmentIds.values) units[cid]?.let(spatialIndex::relocate)
                                }
                            }
                        }
                    }
                }
                if (unit.transportState == null || unit.definition.transport == TransportProfile.AUTHORED)
                    HoverMotion.advancePose(unit, step.delta)
                if (SubmarineMotion.advance(unit, step.delta, acceptsHeight)) pendingEvents += Event("submarine-threshold-crossed", unit.unitId)
                if (AmphibiousJetMotion.advance(unit, currentScenario.map, step.delta, acceptsHeight))
                    pendingEvents += Event("amphibious-threshold-crossed", unit.unitId)
                spatialIndex.relocate(unit)
                MechGait.advance(unit, step.delta)
                MechMotion.requireFinitePose(unit)
                unit.finishMovementRequest()
                if (avoidPoseT0 != 0L) unitAvoidNanos += (System.nanoTime() - avoidPoseT0).coerceAtLeast(0L)
                if (BuildingWork.isReady(unit)) {
                    advanceWeaponCooldown(unit, step.delta)
                    if (!economicBuilding && !factoryWork) advanceUpgrade(unit)
                    val beforeAutomatic = unit.definition
                    if (withUnitActionQueue(unit) {
                            CustomUnitActions.advanceAutomatic(unit, currentScenario.unitDefinitions, currentScenario.map, step.delta)
                        }) {
                        if (beforeAutomatic !== unit.definition) spatialIndex.relocate(unit)
                        if (beforeAutomatic.blink != null || unit.definition.blink != null)
                            productionLedger = CustomUnitActions.reconcileBlinkConversion(unit, productionLedger)
                        pendingEvents += Event("unit-action-automatic", unit.unitId)
                    }
                    regenerateHealth(unit, step.delta.toFloat())
                }
            }
            updateProjectiles(step.delta, candidate.existingProjectileIds)
            for (parent in orderedUnits()) {
                val pending = parent.spiderBlink ?: continue
                if (parent.alive && !parent.reclaimed && parent.carriedBy == null && pending.queuedAt < step.stepIndex)
                    AttachmentBlink.advanceParent(steps.current, parent, pending, currentScenario.map)
            }
            UnitAttachments.settleLifecycle(steps.current, currentScenario)
            missileTargets = missileTargets?.refresh(orderedUnits())
            if (planningSlices != null) {
                candidate.planningWorkSample = PlanningWorkSample(
                    step.stepIndex,
                    stepBudgetLimit,
                    stepTacticalBudget.used,
                    Collections.unmodifiableList(planningSlices),
                )
            }
            if (prof != null) {
                val now = System.nanoTime()
                val loopTotal = (now - phaseMark).coerceAtLeast(0L)
                prof.addNanos(SimHotspotStage.NAV_PLAN, unitPlanNanos)
                prof.addNanos(SimHotspotStage.NAV_AVOID, unitAvoidNanos)
                prof.addNanos(SimHotspotStage.SIM_COMBAT, (loopTotal - unitPlanNanos - unitAvoidNanos).coerceAtLeast(0L))
                phaseMark = now
            }
            candidate.stageKnowledge(prepareAutomaticKnowledge())
            candidate.stageBuildings(prepareBuildings(candidate.vision))
            if (prof != null) {
                prof.addNanos(SimHotspotStage.SIM_VISION, System.nanoTime() - phaseMark)
            }
            if (injectFailureAfterUpdate) throw IllegalStateException("Injected candidate step failure")
            } finally {
                activeStepPlanningBudget = null
                activeStepPlanningSlices = null
            }
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
                incomeEnabled = nextScenario.unitDefinitions.values.any { it.income != null },
                maintenanceEnabled = nextScenario.maintenanceContract != null ||
                    nextScenario.unitDefinitions.values.any { it.maintenance != null })
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
        passageAdmission = PassageAdmission(nextScenario.unitDefinitions.values)
        productionLedger = initialLedger
        ordinaryUpgrades = initialUpgrades
        buildingTerrain = initialBuildings
        units = linkedMapOf()
        spatialIndex = WorldSpatialIndex()
        projectiles = mutableListOf()
        steps.current.temporaryVision = emptyList()
        combatFrame = nextScenario.combatContract?.initialFrameCounter ?: -1
        steps.current.actionClock = 0
        nextObjectId = nextScenario.combatContract?.nextObjectId ?: 1L
        lastBirthSerial = 0L
        buildingWork = BuildingWork.initial()
        nextScenario.units.sortedBy { it.unitId }.forEach { spawn ->
            UnitRegistration.initial(steps.current, nextScenario, spawn)
        }
        units.values.toList().forEach { UnitAttachments.initialize(steps.current, nextScenario, it) }
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
        steps.current.deferredWindowEvents = mutableListOf()
        steps.current.deferredWindowProjectileEvents = mutableListOf()
        steps.current.deferredWindowActionResults = mutableListOf()
        latestObservation = null
        committedObservation = null
        scheduledActions.clear()
        steps.current.movePlanning = MovePlanning()
        steps.current.flowFields = ChunkedFlowFieldAdapter()
        steps.current.pendingFlowStart.clear()
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
        if (incomes.any { it.period !in 1f..IncomeProfile.MAX_PERIOD || !it.credits.isFinite() || it.credits < 0f })
            return SimulationError("unsupported-income", "Income requires a finite period of at least one tick and finite nonnegative credits")
        input.economyContract?.let { economy ->
            if (economy.initialBalances.keys != players || economy.initialBalances.values.any {
                    it < 0L || (incomes.isNotEmpty() && it > ProductionLedger.INCOME_LIMIT.toLong()) }) {
                return SimulationError("invalid-economy-contract", "Expected an explicit nonnegative balance for every declared player")
            }
        }
        if (input.unitDefinitions.values.any { it.production != null || it.income != null || it.builder != null ||
                it.construction != null || (it.blink?.unlockCredits ?: 0L) > 0L } && input.economyContract == null) {
            return SimulationError("missing-economy-contract", "Production, income and construction require an explicit economy contract")
        }
        if (input.units.any { it.ownerPlayerId !in players }) return SimulationError("bad-reference", "Unit owner is not a declared player")
        if (input.units.any { it.typeId !in input.unitDefinitions }) return SimulationError("bad-reference", "Unit type is not declared")
        if (input.unitDefinitions.values.any { it.attachmentLayout != null } &&
            input.units.any { it.unitId.startsWith(UnitAttachments.UNIT_PREFIX) })
            return SimulationError("reserved-unit-id", "Attached units reserve their own unit namespace")
        UnitAttachments.validateDefinitions(input)?.let { return it }
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
        if (!ScreenPlane.supports(input.coordinateModel)) {
            return SimulationError("unsupported-coordinate-model", "Coordinate model is not supported by this contract")
        }
        if (!FixedSimTick.supports(input.timeModel)) return SimulationError("unsupported-time-model", "Unsupported time model")
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
            val attachedProfile = definition.attachmentLayout != null || definition.attachmentModule != null
            val spiderWork = ModularSpiderRules.supportsWork(definition)
            val roles = RoleCompositionRules.usesRoles(definition)
            if (roles && !RoleCompositionRules.supports(definition, input.unitDefinitions))
                return SimulationError("unsupported-unit-roles", "Expected complete compatible authored roles and valid mobile construction")
            val spiderLightning = attachedProfile && definition.id == ModularSpiderRules.LIGHTNING
            val submarine = definition.submarine != null
            val amphibious = definition.amphibiousJet != null
            val dropship = definition.transport == com.rustedwarfare.reforge.core.content.TransportProfile.NATIVE_DROPSHIP
            val bunker = Bunker.supports(definition)
            val engineer = MechEngineer.supports(definition)
            val nautilus = com.rustedwarfare.reforge.core.content.NautilusRules.supports(definition)
            val scout = ScoutBotRules.supports(definition)
            val flameMech = MechFlameRules.supports(definition)
            if (definition.id == MechFlameRules.ID && !flameMech)
                return SimulationError("unsupported-flame-mech", "Expected the qualified flame mech")
            val bomber = definition.attackMovement == AttackMovementProfile.BOMBER
            if ((bomber || definition.id == BomberRules.ID) && !BomberRules.supports(definition))
                return SimulationError("unsupported-bomber", "Expected the qualified two-weapon bomber")
            if (!nautilus && definition.combat?.namedWeapons?.ordered?.any { it.interception != null } == true &&
                !com.rustedwarfare.reforge.core.content.NavalInterceptionRules.supports(definition))
                return SimulationError("unsupported-naval-interception", "Expected one qualified naval interception mount and stock action")
            if (definition.groundHeight != null && !bunker && !scout)
                return SimulationError("unsupported-unit-actions", "Ground height requires a qualified custom form")
            val beamGunship = !nautilus && !scout && !bomber && (definition.energy != null ||
                definition.combat?.namedWeapons?.ordered?.any { it.energyUsage != 0f } == true)
            if (definition.afterburn != null || beamGunship) {
                if (!AaBeamGunship.supports(definition))
                    return SimulationError("unsupported-aa-beam", "Expected a qualified afterburn profile with an energy beam")
                val profile = checkNotNull(definition.afterburn)
                if (listOf(profile.normalForm, profile.boostedForm).any { input.unitDefinitions[it]?.afterburn != profile })
                    return SimulationError("invalid-unit-action-target", "Afterburn conversion forms must share the same profile")
            }
            definition.blink?.let { profile ->
                if (!profile.isValid() || definition.production?.let { it.speedMultiplier != profile.queueSpeed } == true ||
                    definition.attachmentLayout != null || definition.attachmentModule != null ||
                    definition.nuclearSilo != null || (!profile.locked && m.type != MovementType.HOVER) ||
                    definition.customActions?.actions?.filter { it.blinkWork != null } != profile.actions().actions)
                    return SimulationError("unsupported-blink", "Expected valid Blink work and a mobile or locked form")
            }
            if (definition.customActions != null) {
                if (!CustomUnitActions.supports(definition))
                    return SimulationError("unsupported-unit-actions", "Expected qualified custom unit actions")
                if (definition.customActions.actions.any { action ->
                    action.blinkWork != null && definition.blink == null ||
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
            val authoredAir = definition.authoredAirCombat
            if (authoredAir && (!air || m.flight?.isValid() != true || definition.rotor != null ||
                    !m.slidingMode || !m.ignoringBody || m.reverseSpeedPercentage !in 0.0..1.0 ||
                    definition.className != "CustomUnitMetadata" || definition.mech != null ||
                    definition.building != null || definition.amphibiousJet != null || definition.combat == null))
                return SimulationError("unsupported-flight", "Expected finite rotorless AIR combat movement")
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
            if (air && !amphibious && !dropship && !beamGunship && !bomber && !authoredAir && !roles && (m.flight?.isValid() != true || definition.rotor?.spinRate?.isFinite() != true ||
                    !m.slidingMode || !m.ignoringBody || m.reverseSpeedPercentage != .1 || definition.mech != null) ||
                !air && (m.flight != null || definition.rotor != null))
                return SimulationError("unsupported-flight", "Expected a finite AIR profile and a single finite rotor")
            if (!m.reverseSpeedPercentage.isFinite() || !air && !submarine && !engineer && !nautilus && (m.reverseSpeedPercentage !in setOf(0.0, 1.0) ||
                m.reverseSpeedPercentage != 0.0 && definition.mech == null && m.type != MovementType.WATER) ||
                definition.mech?.let { !it.isValid() || !bunker && !nautilus && !scout && !flameMech && !attachedProfile && m.type != (if (it.kind == com.rustedwarfare.reforge.core.content.MechKind.FOUR_LEGGED) MovementType.OVER_CLIFF else MovementType.LAND) } == true)
                return SimulationError("unsupported-mech", "Expected the finite supported mech locomotion category")
            val authoredMaintenance = definition.maintenance == com.rustedwarfare.reforge.core.content.MaintenanceProfile.AUTHORED
            if (authoredMaintenance != (definition.authoredMaintenance != null) || authoredMaintenance &&
                    (definition.authoredMaintenance?.isValid() != true || definition.className != "AuthoredConstructor" && !roles ||
                        definition.builder == null || definition.building != null || definition.combat != null && !roles))
                return SimulationError("unsupported-maintenance", "Expected a finite authored constructor maintenance profile")
            if (definition.maintenance != null && !authoredMaintenance && !RepairBay.supports(definition) && !engineer && !scout && !spiderWork)
                return SimulationError("unsupported-repair-bay", "Expected the complete finite native repair bay capability")
            if (definition.reclaimPrice != null && definition.reclaimPrice < 0L)
                return SimulationError("invalid-reclaim-price", "Explicit reclaim price must be nonnegative")
            definition.builder?.takeUnless { scout }?.let { builder ->
                if (definition.fogOfWarSightRange < 15 || !engineer && !spiderWork && !roles && (m.type != MovementType.LAND || m.moveSpeed > .8 ||
                    (if (authoredMaintenance) !(builder.range > 0f && builder.range <= 500f &&
                        builder.repairRate > 0f && builder.repairRate <= 10f)
                        else builder.range != 85f || builder.repairRate != .2f) ||
                    builder.preparation != 30f || builder.decay != 1.3f ||
                    builder.buildSpeedMultiplier != 1f || builder.mainNanoTurret != null) ||
                    builder.buildingTypeIds.isEmpty() || builder.buildingTypeIds.distinct().size != builder.buildingTypeIds.size ||
                    !builder.buildingTypeIds.containsAll(builder.nanoConstructionTypes) ||
                    builder.buildingTypeIds.any { id -> input.unitDefinitions[id]?.let(definition::canConstruct) != true })
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
                        "c_turret_t1_artillery" -> construction == com.rustedwarfare.reforge.core.content.ConstructionProfile(2100L, .0008f) &&
                            definition.radius == 16.0 && definition.maximumHealth == 1000.0 && definition.techLevel == 2 &&
                            definition.combat?.projectile?.ground != null && definition.pointDefense == null
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
                val attachedDefense = definition.id == ModularSpiderRules.LASER_DEFENSE &&
                    ModularSpiderRules.supportsModule(definition)
                val hoverDefense = profile.authored && (m.type == MovementType.HOVER || definition.blink?.locked == true) &&
                    definition.building == null && definition.combat != null && profile.attachedBarrel == null
                if (!attachedDefense && !hoverDefense && (definition.building == null || definition.combat != null ||
                        m.type != MovementType.NONE || profile.attachedBarrel != null || profile.authored) ||
                    !profile.range.isFinite() || profile.range <= 0.0 || !(profile.range * profile.range).isFinite() ||
                    !profile.muzzleOffsetX.isFinite() || !profile.muzzleOffsetY.isFinite() ||
                    !profile.recharge.isFinite() || profile.recharge < 0f || !profile.cost.isFinite() || profile.cost < 0f ||
                    !profile.initialEnergy.isFinite() || profile.initialEnergy !in 0f..1f)
                    return SimulationError("unsupported-point-defense", "Expected a qualified defense and valid energy profile")
            }
            definition.deathExplosion?.let { profile ->
                val qualified = when {
                    profile.authored -> m.type == MovementType.HOVER && definition.combat != null && !attachedProfile
                    profile.nuclear -> spiderWork && definition.durability != null
                    else -> definition.building != null && m.type == MovementType.NONE && definition.durability != null
                }
                if (!qualified || definition.maximumHealth == null ||
                    !profile.damage.isFinite() || profile.damage < 0f || !profile.radius.isFinite() || profile.radius <= 0f ||
                    !(profile.radius * profile.radius).isFinite() || !profile.expandTime.isFinite() || profile.expandTime <= 0f)
                    return SimulationError("unsupported-death-explosion", "Expected a qualified unit and a finite death area")
            }
            definition.production?.let { production ->
                if (nautilus && production.unitTypeIds.any { id ->
                        input.unitDefinitions[id]?.let(com.rustedwarfare.reforge.core.content.NautilusRules::acceptsProduct) != true })
                    return SimulationError("unsupported-production", "Nautilus requires the qualified product price, rate and capability")
                if (production.exitProfile == com.rustedwarfare.reforge.core.content.FactoryExitProfile.AUTHORED_MOBILE && !roles ||
                    !roles && production.speedMultiplier != (if (nautilus) 2f else 1f) ||
                    production.exitProfile == com.rustedwarfare.reforge.core.content.FactoryExitProfile.CUSTOM_NAUTILUS && !nautilus)
                    return SimulationError("unsupported-production", "Expected qualified current factory speed and exit")
                val authoredFactory = com.rustedwarfare.reforge.core.content.AuthorFactoryRules.supports(definition, input.unitDefinitions)
                if (production.rallyEnabled && !authoredFactory)
                    return SimulationError("unsupported-production", "Rally points require a qualified authored factory")
                if (production.exitProfile == com.rustedwarfare.reforge.core.content.FactoryExitProfile.NATIVE_EXPERIMENTAL &&
                    (!com.rustedwarfare.reforge.core.content.ExperimentalFactoryRules.supports(definition) ||
                        input.unitDefinitions["nautilusSubmarineLand"]?.let(com.rustedwarfare.reforge.core.content.NautilusRules::supports) != true))
                    return SimulationError("unsupported-production", "Experimental factory requires the qualified native body and land Nautilus product")
                if (!authoredFactory && production.exitProfile == com.rustedwarfare.reforge.core.content.FactoryExitProfile.NATIVE_SEA &&
                    (!FactoryExit.supportsSeaFactory(definition) || production.unitTypeIds.any { id ->
                        input.unitDefinitions[id]?.let {
                            NativeSeaFactoryProducts.accepts(definition.techLevel, it)
                        } != true
                    }))
                    return SimulationError("unsupported-production", "Native sea exits require a supported factory tier and declared water-capable products")
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
                if (definition.building == null && !nautilus && !roles || production.unitTypeIds.isEmpty() ||
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
                        definition.income != null && definition.production != null || definition.pointDefense != null))
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
                    definition.className != "CustomUnitMetadata" && !roles || !definition.id.matches(Regex("[a-z][a-z0-9_.-]*")) ||
                    definition.radius <= 0.0 || !definition.displayRadius.isFinite() || definition.displayRadius <= 0.0 ||
                    !definition.imageScale.isFinite() || definition.imageScale <= 0.0 || definition.totalFrames <= 0 ||
                    definition.fixedUnderwaterTarget != null || definition.building != null || definition.production != null && !roles ||
                    definition.builder != null && !roles || definition.construction != null || definition.income != null ||
                    definition.upgrades != null || definition.deathExplosion != null || definition.pointDefense != null ||
                    definition.mech != null || definition.rotor != null || m.flight != null ||
                    definition.directHitTargetKind != DirectHitTargetKind.ORDINARY))
                return SimulationError("unsupported-unit", "Expected finite authored surface combat without unsupported capability combinations")
            val validShape = when (val shape = definition.movementCollision) {
                is MovementCollisionShape.Rectangle -> shape.length.isFinite() && shape.width.isFinite() &&
                    shape.length / 2.0 > 0.0 && shape.width / 2.0 > 0.0
                is MovementCollisionShape.Circle -> shape.radius.isFinite() && shape.radius >= 0.0
            }
            if (!validShape || numbers.any { !it.isFinite() || it < 0.0 } ||
                (definition.building == null && definition.radius >= input.map.tileWidth && !(fourLegged && definition.radius == 22.0) && !navalHull && !authoredSurface && !dropship && !beamGunship && !bunker && !nautilus && !flameMech && !authoredAir && !attachedProfile) ||
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
                val shot = combat.projectile
                if ((shot.steering != null || shot.endOfLife != null) &&
                    (!(authoredSurface || navalHull) || !shot.supportsExpirySteering() ||
                        shot.endOfLife?.isTerminalExpiryPayload() == false))
                    return SimulationError("unsupported-combat", "Expected a finite direct naval expiry projectile and a terminal payload")
                val flame = combat.projectile.flame
                if (flame && !flameMech && ((!authoredAir && (m.type != MovementType.NONE || definition.building == null)) ||
                        combat.projectile.ground?.let { it.resolveAtSnapshot && it.initialVerticalSpeed == 0f &&
                            it.gravity == 0f && it.trueGravity == 0f && it.speedSpread >= 0f && it.speedSpread <= 100f &&
                            combat.projectile.speed.toFloat() > it.speedSpread &&
                            (combat.projectile.speed.toFloat() * combat.projectile.speed.toFloat()).isFinite() &&
                            it.spread <= 10_000f } != true ||
                        combat.projectile.acceleration?.let {
                            !it.targetSpeed.toFloat().isFinite() || !it.acceleration.toFloat().isFinite()
                        } == true ||
                        combat.projectile.directDamage != 0.0 || combat.projectile.instant || combat.projectile.retargetOnDeath ||
                        combat.projectile.splash != null || combat.projectile.ballistic != null || combat.projectile.missile != null))
                    return SimulationError("unsupported-combat", "Expected a finite ground snapshot flame projectile")
                val specialInstant = combat.projectile.instant && combat.projectile.missile == null &&
                    combat.projectile.ground == null && combat.projectile.splash == null && combat.projectile.ballistic == null &&
                    combat.projectile.acceleration == null &&
                    (combat.projectile.beam == null || !combat.projectile.lightning) &&
                    combat.projectile.damageModifiers.let { listOf(it.shieldDamage, it.shieldDeflection, it.building).all { n -> n in 0.0..10.0 } }
                if ((combat.projectile.lightning || combat.projectile.beam != null ||
                        combat.projectile.damageModifiers != com.rustedwarfare.reforge.core.content.ProjectileDamageModifiers()) &&
                    !amphibious && !beamGunship && !spiderLightning && !specialInstant)
                    return SimulationError("unsupported-combat", "Special projectile damage requires a qualified capability")
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
                    if (combat.projectile.acceleration == null && !spiderLightning) listOf(combat.projectile.speed) else emptyList()
                val nonnegative = listOf(combat.turretSize, combat.turretTurnSpeed, combat.turretTurnAcceleration,
                    combat.projectile.directDamage, combat.projectile.speed) +
                    (if (zeroRecoil) listOf(combat.recoil.outTime, combat.recoil.returnTime) else emptyList())
                val invalidAcceleration = combat.projectile.acceleration?.let {
                    !it.targetSpeed.isFinite() || it.targetSpeed <= 0.0 || !it.acceleration.isFinite() || it.acceleration <= 0.0 ||
                        combat.projectile.instant || combat.projectile.ground != null && !bomber && !flame || combat.projectile.missile != null
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
                    !flame && (ground.speedSpread != 0f || ground.resolveAtSnapshot) ||
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
            val spawnDefinition = input.unitDefinitions.getValue(spawn.typeId)
            val radius = spawnDefinition.radius
            val spawnHeading = if (spawnDefinition.attachmentLayout == null) spawn.heading else 90.0
            if (!MapBounds.containsHull(input.map, p, spawnHeading, spawnDefinition.movementCollision)) {
                return SimulationError("invalid-spawn", "Unit '${spawn.unitId}' movement hull must be finite and entirely within the map")
            }
            val resourceBuilding = spawnDefinition.building?.requiresResourcePool == true
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
                    CenterTerrain.intersects(input.map, p, input.unitDefinitions.getValue(spawn.typeId).foundationMovementType))) {
                return SimulationError("invalid-spawn", "Unit '${spawn.unitId}' heading must be finite and spawn outside blocked terrain")
            }
            val definition = input.unitDefinitions.getValue(spawn.typeId)
            if (definition.mech != null) try {
                MechMotion.initial(definition, p, ScreenPlane.normalize(spawn.heading))
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
                    heading = ScreenPlane.normalize(spawn.heading), height = spawn.height,
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
        val res = resolveBudgetedTacticalRoute(
            unit = unit,
            map = map,
            actionId = checkNotNull(unit.originMoveId),
            atStep = unit.originAtStep,
            target = target,
            reason = PathSearchReason.ROUTE_REPAIR,
            executionStep = executionStep,
            isRepair = true,
            mustCompleteInStep = true,
        )
        if (unit.attackCommand != null) unit.lastAttackPathAttemptStep = executionStep
        when (res) {
            TacticalRouteResolution.Pending -> {
                if (!FactoryExit.isNativeSeaDeparture(unit)) {
                    // Clear pathPoints while suspended so updateUnit stays stationary until the route finishes,
                    // while preserving moveTarget/originMoveId/motionContract.
                    unit.updateNavigation { it.copy(pathPoints = emptyList()) }
                }
            }
            is TacticalRouteResolution.Unreachable -> {
                if (FactoryExit.isNativeSeaDeparture(unit)) return
                unit.cancelRoute()
                pendingEvents += Event("movement-cancelled", unit.unitId)
                pendingEvents += Event("path-unreachable", unit.unitId)
            }
            is TacticalRouteResolution.Ready -> {
                unit.adoptRoute(
                    res.points,
                    logicalRouteTarget(unit, checkNotNull(unit.moveTarget)),
                    plannedKnowledgeRevision(unit),
                    plannedBuildingRevision(unit),
                )
            }
        }
    }

    private fun updatePatrolRoute(unit: MutableUnit, map: MapDefinition, executionStep: Long, previousRequest: Boolean) {
        val enemy = retainedPatrolTarget(unit)
        val task = unit.tasks.first()
        var target = checkNotNull(task.target)
        val multiple = unit.tasks.size > 1
        val capture = if (!previousRequest || combatFrame == 0 || combatFrame == 1)
            (if (multiple) 70.0 else 80.0) else 30.0
        val captureX = unit.position.x.toFloat() - target.x.toFloat()
        val captureY = unit.position.y.toFloat() - target.y.toFloat()
        if (captureX * captureX + captureY * captureY < capture.toFloat() * capture.toFloat()) {
            if (multiple) unit.taskQueue = unit.taskQueue.append(task).pop()
            steps.current.movePlanning.cancel(unit.unitId)
            unit.cancelRoute()
            if (multiple || enemy == null) return
        }
        if (enemy != null) {
            val stopRange = UnitCombat.range(unit).toFloat() - 1f
            if (CombatTargetQuery.patrolDistanceSquared(unit, enemy) < stopRange * stopRange) {
                steps.current.movePlanning.cancel(unit.unitId)
                unit.cancelRoute()
                return
            }
            target = enemy.position
        }
        unit.requestMovement(target)
        if (unit.moveTarget == target && unit.pathPoints.isNotEmpty()) return
        if (enemy != null && trySpliceMovingTargetTail(unit, map, target)) return
        if (unit.birthRemaining > 0f) {
            val fits = MapBounds.contains(
                map.widthTiles.toDouble() * map.tileWidth,
                map.heightTiles.toDouble() * map.tileHeight,
                target,
                routeRadius(unit),
            )
            if (!fits) {
                unit.cancelRoute()
            } else {
                val contract = task.motionContract ?: if (enemy == null) MotionContract.patrolLeg(target) else MotionContract.pursuitPoint(target)
                unit.updateNavigation {
                    it.bindCommandTarget(target = target, originMoveId = task.actionId, originAtStep = task.atStep, contract = contract)
                }
                unit.adoptRoute(listOf(target), logicalRouteTarget(unit, target), plannedKnowledgeRevision(unit), plannedBuildingRevision(unit))
            }
            return
        }
        val res = resolveBudgetedTacticalRoute(
            unit = unit,
            map = map,
            actionId = task.actionId,
            atStep = task.atStep,
            target = target,
            reason = if (enemy == null) PathSearchReason.USER_MOVE else PathSearchReason.ATTACK_PURSUIT,
            executionStep = executionStep,
            isRepair = false,
        )
        when (res) {
            TacticalRouteResolution.Pending -> Unit
            is TacticalRouteResolution.Unreachable -> unit.cancelRoute()
            is TacticalRouteResolution.Ready -> {
                val contract = task.motionContract ?: if (enemy == null) MotionContract.patrolLeg(target) else MotionContract.pursuitPoint(target)
                unit.updateNavigation {
                    it.bindCommandTarget(
                        target = target,
                        originMoveId = task.actionId,
                        originAtStep = task.atStep,
                        contract = contract,
                    )
                }
                unit.adoptRoute(res.points, logicalRouteTarget(unit, target), plannedKnowledgeRevision(unit), plannedBuildingRevision(unit))
            }
        }
    }

    /**
     * O(1) tail line-of-sight splice + multi-leg cell hysteresis for moving combat targets (ENG-P017-005):
     * - When the final segment from the penultimate waypoint (or current position on a 1-leg route) to
     *   [destination] is unobstructed, updates the terminal waypoint directly in O(1) with zero A* work.
     * - When routing around an obstacle (`pathPoints.size >= 2`) and the moving target remains in the same
     *   tile (or within a 2-tile hysteresis neighborhood during a recent replan cooldown while still far),
     *   keeps following the active obstacle-detour trunk instead of restarting a whole-map search every step.
     */
    private fun trySpliceMovingTargetTail(
        unit: MutableUnit,
        map: MapDefinition,
        destination: Position,
        executionStep: Long = -1L,
        allowDetourHysteresis: Boolean = false,
        attackRange: Double = 0.0,
    ): Boolean {
        val routeEnd = unit.moveTarget ?: return false
        val pts = unit.pathPoints
        if (pts.isEmpty() || unit.birthRemaining > 0f) return false
        if (routeEnd == destination) return true

        val terrain = planningTerrainFor(unit, map)
        val radius = routeRadius(unit)
        val mapW = map.widthTiles.toDouble() * map.tileWidth
        val mapH = map.heightTiles.toDouble() * map.tileHeight
        if (MapBounds.contains(mapW, mapH, destination, radius) &&
            !PlanningCenterTerrain.intersects(terrain, destination, radius)
        ) {
            val anchor = if (pts.size >= 2) pts[pts.size - 2] else Transport.queryPosition(unit, map)
            val hull = HullRoute.forUnit(unit, map)
            val refHeading = if (pts.size >= 2) ScreenPlane.desired(anchor, pts.last()) else unit.heading
            val tailClear = if (hull == null) {
                PlanningSegmentClearance.allows(terrain, anchor, destination, radius)
            } else {
                hull.arrival(HullTerrainGrid.authorized(terrain), anchor, destination, refHeading) != null
            }
            if (tailClear && routeOccupancy(unit)?.allows(anchor, destination, refHeading, refHeading) != false) {
                unit.updateNavigation { it.spliceTailTarget(destination) }
                return true
            }
        }

        if (allowDetourHysteresis && pts.size >= 2) {
            val endCol = MapBounds.cell(routeEnd.x, map.tileWidth, map.widthTiles)
            val endRow = MapBounds.cell(routeEnd.y, map.tileHeight, map.heightTiles)
            val destCol = MapBounds.cell(destination.x, map.tileWidth, map.widthTiles)
            val destRow = MapBounds.cell(destination.y, map.tileHeight, map.heightTiles)
            if (endCol == destCol && endRow == destRow) {
                return true
            }
            val cellDelta = maxOf(kotlin.math.abs(endCol - destCol), kotlin.math.abs(endRow - destRow))
            val farThreshold = maxOf(attackRange + 80.0, 160.0)
            val lastAttempt = unit.lastAttackPathAttemptStep
            if (pts.size >= 3 &&
                cellDelta <= 2 &&
                ScreenPlane.distanceSquared(unit.position, destination) > farThreshold * farThreshold &&
                lastAttempt != null &&
                executionStep >= 0L &&
                executionStep - lastAttempt < 15L
            ) {
                return true
            }
        }
        return false
    }

    private fun updateAttackRoute(unit: MutableUnit, map: MapDefinition, executionStep: Long) {
        var command = unit.attackCommand ?: return
        val weapon = UnitCombat.profile(unit) ?: return
        val input = checkNotNull(scenario)
        fun eligible(target: MutableUnit) = CombatTargetQuery.isEnemy(unit, target, input.players) &&
            CombatTargetQuery.canAttackAirCategory(unit, target, input.map)
        var target = units[command.targetUnitId]?.takeIf(::eligible)
        if (target == null) {
            target = CombatTargetQuery.nearestEnemy(unit, spatialIndex.queryAllLayersCenterBox(unit.position, UnitCombat.range(unit) + 1e-6), input.players, input.map)
            steps.current.movePlanning.cancel(unit.unitId)
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
            steps.current.movePlanning.cancel(unit.unitId)
            unit.cancelRoute()
            return
        }
        val compositeRange = unit.definition.mech?.kind == com.rustedwarfare.reforge.core.content.MechKind.FOUR_LEGGED
        val bomber = unit.definition.attackMovement == AttackMovementProfile.BOMBER
        if (!bomber && ScreenPlane.distanceSquared(unit.position, target.position) < weapon.attackRange * weapon.attackRange &&
            (!compositeRange || NamedWeaponRuntime.inRange(unit, target, input.players, input.map))) {
            steps.current.movePlanning.cancel(unit.unitId)
            if (unit.moveTarget != null) unit.cancelRoute()
            return
        }
        val destination = if (bomber) FlightMotion.bomberAttackDestination(unit, target.position, map) else target.position
        unit.requestMovement(destination)
        val targetRange = if (bomber || target.definition.building == null) 0.0 else weapon.attackRange
        val routeEnd = unit.moveTarget
        val reusable = if (routeEnd != null && unit.pathPoints.isNotEmpty()) {
            if (unit.birthRemaining > 0f || bomber) {
                routeEnd == destination
            } else if (targetRange > 0.0) {
                ScreenPlane.distanceSquared(routeEnd, destination) < targetRange * targetRange &&
                    !PlanningCenterTerrain.intersects(planningTerrainFor(unit, map), routeEnd, routeRadius(unit))
            } else {
                trySpliceMovingTargetTail(
                    unit = unit,
                    map = map,
                    destination = destination,
                    executionStep = executionStep,
                    allowDetourHysteresis = true,
                    attackRange = weapon.attackRange,
                )
            }
        } else false
        if (reusable) return
        if (unit.lastAttackPathAttemptStep == executionStep && !steps.current.movePlanning.hasPendingTactical(unit.unitId)) return

        if (unit.birthRemaining > 0f) {
            unit.lastAttackPathAttemptStep = executionStep
            val fits = MapBounds.contains(
                map.widthTiles.toDouble() * map.tileWidth,
                map.heightTiles.toDouble() * map.tileHeight,
                destination,
                routeRadius(unit),
            )
            if (!fits) {
                unit.cancelRoute()
                pendingEvents += Event("path-unreachable", unit.unitId)
            } else {
                val headContract = unit.tasks.firstOrNull()?.motionContract ?: MotionContract.pursuitPoint(destination)
                unit.updateNavigation {
                    it.bindCommandTarget(
                        target = destination,
                        originMoveId = command.actionId,
                        originAtStep = command.atStep,
                        contract = headContract,
                        nearTargetTime = 0.0,
                    )
                }
                unit.adoptRoute(listOf(destination), logicalRouteTarget(unit, destination), plannedKnowledgeRevision(unit), plannedBuildingRevision(unit))
            }
            return
        }

        val res = resolveBudgetedTacticalRoute(
            unit = unit,
            map = map,
            actionId = command.actionId,
            atStep = command.atStep,
            target = destination,
            reason = PathSearchReason.ATTACK_PURSUIT,
            executionStep = executionStep,
            targetRange = targetRange,
            isRepair = false,
        )
        when (res) {
            TacticalRouteResolution.Pending -> Unit
            is TacticalRouteResolution.Unreachable -> {
                unit.lastAttackPathAttemptStep = executionStep
                unit.cancelRoute()
                pendingEvents += Event("path-unreachable", unit.unitId)
            }
            is TacticalRouteResolution.Ready -> {
                unit.lastAttackPathAttemptStep = executionStep
                val endPos = res.points.last()
                val headContract = unit.tasks.firstOrNull()?.motionContract ?: if (targetRange > 0.0) {
                    MotionContract.rangeApproach(destination, targetRange, tracksRouteStall = false, enableLocalAvoidance = true)
                } else {
                    MotionContract.pursuitPoint(endPos)
                }
                unit.updateNavigation {
                    it.bindCommandTarget(
                        target = endPos,
                        originMoveId = command.actionId,
                        originAtStep = command.atStep,
                        contract = headContract,
                        nearTargetTime = 0.0,
                    )
                }
                unit.adoptRoute(res.points, logicalRouteTarget(unit, endPos), plannedKnowledgeRevision(unit), plannedBuildingRevision(unit))
            }
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

    private fun acquirePatrolTarget(unit: MutableUnit): MutableUnit? {
        val searchRange = maxOf(UnitCombat.range(unit).toFloat() + 110f, 190f).toDouble() + 1.0
        return CombatTargetQuery.patrolEnemy(unit, spatialIndex.queryAllLayersCenterBox(unit.position, searchRange), checkNotNull(scenario).players, ::isVisibleTo, checkNotNull(scenario).map)
    }

    private fun advanceWeaponCooldown(unit: MutableUnit, delta: Double) {
        unit.namedWeapons?.values?.forEach {
            it.cooldown = if (it.cooldown >= 0.0) maxOf(0.0, it.cooldown - delta)
                else minOf(0.0, it.cooldown + delta)
        } ?: unit.combat?.let { it.cooldown = maxOf(0.0, it.cooldown - delta) }
    }

    private fun regenerateHealth(unit: MutableUnit, delta: Float) {
        val regeneration = unit.definition.selfRegenRate
        if (regeneration == 0f) return
        val maximum = checkNotNull(unit.definition.maximumHealth)
        val health = checkNotNull(unit.health)
        if (health >= maximum) return
        val restored = health.toFloat() + regeneration * delta
        require(restored.isFinite()) { "HP regeneration must remain finite" }
        unit.health = minOf(maximum.toFloat(), restored).toDouble()
    }

    private fun updateWeapon(unit: MutableUnit, delta: Double, preferredTarget: MutableUnit? = null) {
        if (unit.namedWeapons != null) {
            updateNamedWeapons(unit, delta, preferredTarget)
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
        fun inRange(target: MutableUnit) = ScreenPlane.distanceSquared(unit.position, target.position) <
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
        } else manualTarget ?: units[state.targetId]?.takeIf { eligible(it) && inRange(it) } ?: CombatTargetQuery.nearestEnemy(unit, spatialIndex.queryAllLayersCenterBox(unit.position, UnitCombat.range(unit) + 1e-6), input.players, input.map)
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

    private fun updateNamedWeapons(unit: MutableUnit, delta: Double, preferredTarget: MutableUnit?) {
        val input = checkNotNull(scenario)
        val contract = checkNotNull(input.combatContract)
        val globalRange = UnitCombat.range(unit)
        val candidates = spatialIndex.queryAllLayersCenterBox(unit.position, globalRange + 1e-6)
        fun globalEligible(target: MutableUnit) = CombatTargetQuery.isEnemy(unit, target, input.players) &&
            CombatTargetQuery.canAttackAirCategory(unit, target, input.map) &&
            ScreenPlane.distanceSquared(unit.position, target.position) < globalRange * globalRange
        val manual = unit.attackCommand?.let { units[it.targetUnitId]?.takeIf(::globalEligible) }
            ?: preferredTarget?.takeIf(::globalEligible)
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
                CombatTargetQuery.nearestEnemy(unit, candidates, input.players, input.map)).also {
                    unit.sharedWeaponTargetId = it?.unitId
                }
        }
        val weaponCandidates = if (!patrol) candidates else if (unit.primaryCombatTargetId == null) emptyList()
            else candidates.filter { isVisibleTo(unit.ownerPlayerId, it.position, it.nativeTransportPlacement) }
        NamedWeaponRuntime.advance(unit, NamedWeaponFrame(contract.worldSeed, combatFrame, delta,
            input.players, units, weaponCandidates, primary, manual, input.map,
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
        val bomber = unit.definition.attackMovement == AttackMovementProfile.BOMBER
        val capturedOffset = if (bomber) Position(0.0, 0.0) else state.offset
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
            beam.beamSegment = BeamSegment(target.position, target.height,
                resourceUnitType = (unit.definition.afterburn?.normalForm ?: unit.typeId).takeUnless { it == "aaBeamGunship" })
            pendingProjectileEvents += ProjectileEventObservation(if (previous == null) "born" else "rearmed", projectileObservation(beam))
            pendingEvents += Event("projectile-fired", unit.unitId)
            return
        }
        val flame = weapon.projectile.flame
        // Flame emitters advance the shared source counter before release.
        val randomState = if (flame) checkNotNull(unit.combat) else state
        randomState.randomCounter = (randomState.randomCounter.toLong() + 1L + actorId).toInt()
        val id = nextObjectId
        nextObjectId = Math.addExact(nextObjectId, 1L)
        val scatter = if (flame) 0f else OrdinaryWeapon.sample(contract.worldSeed, combatFrame, actorId, unit,
            state.randomCounter, 0, 1000, state).also { state.randomCounter++ } * .001f
        val ground = weapon.projectile.ground?.let {
            GroundWeapon.snapshot(unit, target, muzzle, contract.worldSeed, combatFrame, actorId, randomState, weapon)
        }
        val speed = GroundWeapon.initialSpeed(weapon.projectile, unit, contract.worldSeed, combatFrame, actorId, randomState)
        val position = if (ground == null && weapon.projectile.ballistic == null) muzzle
            else Position(muzzle.x.toFloat().toDouble(), muzzle.y.toFloat().toDouble())
        val attachmentRetargetMount = if (definition != null && unit.attachment != null && weapon.projectile.retargetOnDeath) {
            val mounts = checkNotNull(unit.definition.combat?.namedWeapons).ordered
            AttachmentRetargetMount(mounts.indexOf(definition).also { check(it >= 0) }, definition)
        } else null
        val projectile = MutableProjectile(id, unit.unitId, if (ground == null) target.unitId else null, position, state.turretHeading,
            weapon.projectile.life, capturedOffset, scatter, speed, weapon.projectile.directDamage,
            if (ground == null) unit.height else unit.height.toFloat().toDouble(),
            deflectionHealth = weapon.projectile.deflectionHealth, instant = weapon.projectile.instant,
            nonDeflectable = weapon.projectile.nonDeflectable, ground = ground, verticalSpeed = ground?.profile?.initialVerticalSpeed ?: 0f,
            sourceWeaponName = definition?.name, acceleration = weapon.projectile.acceleration,
            splash = weapon.projectile.splash, retargetOnDeath = weapon.projectile.retargetOnDeath,
            ballistic = weapon.projectile.ballistic, lightning = weapon.projectile.lightning,
            damageModifiers = weapon.projectile.damageModifiers,
            sourceOwnerId = unit.ownerPlayerId,
            attachmentRetargetMount = attachmentRetargetMount,
            steering = weapon.projectile.steering, endOfLife = weapon.projectile.endOfLife,
            lightningSegment = if (weapon.projectile.lightning) LightningSegment(target.position, target.height) else null,
            appearance = if (flame) ProjectileAppearance.MECH_FLAME
            else if (bomber) {
                if (ground == null) ProjectileAppearance.BOMBER_DEPTH_CHARGE else ProjectileAppearance.BOMBER_BOMB
            } else if (unit.typeId == ModularSpiderRules.LIGHTNING) ProjectileAppearance.SPIDER_LIGHTNING
                else if (weapon.projectile.lightning) ProjectileAppearance.AMPHIBIOUS_LIGHTNING
                else if (unit.definition.submarine == null) ProjectileAppearance.STANDARD
                else if (UnitLocomotion.isSubmerged(unit)) ProjectileAppearance.SUBMARINE_TORPEDO
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

    private fun updateProjectileInterception(unit: MutableUnit, input: Scenario) {
        val target = ProjectileInterception.target(unit, projectiles, units, input.players) ?: return
        val id = nextObjectId
        nextObjectId = Math.addExact(id, 1L)
        val shot = ProjectileInterception.launch(unit, target, id)
        projectiles += shot
        pendingProjectileEvents += ProjectileEventObservation("born", projectileObservation(shot))
        pendingEvents += Event("projectile-fired", unit.unitId)
        steps.current.pendingSoundTriggers += SoundTriggerObservation("reforge:events/nuke-launch", shot.position, .15f)
    }

    private fun updateProjectiles(delta: Double, existingIds: Set<Long>) {
        steps.current.temporaryVision = steps.current.temporaryVision.map { it.copy(remaining = it.remaining - delta.toFloat()) }
            .filter { it.remaining >= 0f }
        // Retain targets through this step so that a removed target's final pose remains available.
        val trackedTargets = if (projectiles.any { it.interception != null }) projectiles.associateBy { it.objectId } else emptyMap()
        val projectileUpdateLimit = nextObjectId
        updateProjectileBatch(delta, projectiles.filter { it.objectId in existingIds }.sortedBy { it.objectId }, trackedTargets)
        updateProjectileBatch(delta, projectiles.filter {
            it.objectId !in existingIds &&
                (it.scoutPayload == null && !it.derivedOnExpiry || it.objectId < projectileUpdateLimit)
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
                    projectile.beamSegment = BeamSegment(Position(target.position.x + x, target.position.y + y), target.height,
                        resourceUnitType = projectile.beamSegment?.resourceUnitType)
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
                revealNuclearFog(projectile)
            }
            if (hit) {
                if (projectile.nuclearTarget != null) {
                    projectile.position = projectile.nuclearTarget
                    projectile.height = 0.0
                    projectile.expansion = AreaExpansion(com.rustedwarfare.reforge.core.content.DeathExplosionProfile(5400f, 250f, 75f, nuclear = true), 75f)
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
                if (!projectile.lightning && !projectile.beam) {
                    projectiles.remove(projectile)
                    if (projectile.remainingLife == 0.0) spawnExpiryPayload(projectile)
                }
            } else if (projectile.remainingLife == 0.0) {
                pendingProjectileEvents += ProjectileEventObservation("expired", projectileObservation(projectile))
                projectiles.remove(projectile)
                spawnExpiryPayload(projectile)
            } else if (projectile.intercepted) {
                pendingProjectileEvents += ProjectileEventObservation("intercepted", projectileObservation(projectile))
                projectiles.remove(projectile)
            }
        }
    }

    private fun spawnExpiryPayload(parent: MutableProjectile) {
        val profile = parent.endOfLife ?: return
        if (parent.intercepted || parent.sourceUnitId !in units) return
        val id = nextObjectId
        nextObjectId = Math.addExact(id, 1L)
        val child = MutableProjectile(id, parent.sourceUnitId, parent.targetUnitId, parent.position, parent.heading,
            profile.life, Position(0.0, 0.0), 0f, profile.speed, profile.directDamage, parent.height,
            deflectionHealth = profile.deflectionHealth, nonDeflectable = profile.nonDeflectable,
            sourceWeaponName = parent.sourceWeaponName, acceleration = profile.acceleration,
            splash = profile.splash, damageModifiers = profile.damageModifiers,
            appearance = parent.appearance, sourceOwnerId = parent.sourceOwnerId, steering = profile.steering,
            derivedOnExpiry = true)
        projectiles += child
        pendingProjectileEvents += ProjectileEventObservation("born", projectileObservation(child))
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
            applyGroundArea(checkNotNull(shot.sourceOwnerId), track.launchTarget, 0f, 100f, 60f,
                60f * (1f - remaining / 75f))
            pendingProjectileEvents += ProjectileEventObservation("impact", projectileObservation(shot))
            projectiles.remove(shot)
        } else if (shot.remainingLife == 0.0) {
            pendingProjectileEvents += ProjectileEventObservation("expired", projectileObservation(shot))
            projectiles.remove(shot)
        }
    }

    private fun revealNuclearFog(projectile: MutableProjectile) {
        val source = units.getValue(projectile.sourceUnitId)
        projectile.revealFogPending = false
        steps.current.temporaryVision = steps.current.temporaryVision + TemporaryVision(source.ownerPlayerId, projectile.position)
    }

    private fun updateDeathExplosion(projectile: MutableProjectile, delta: Double) {
        val area = checkNotNull(projectile.expansion)
        if (area.profile.nuclear && !projectile.hitApplied) {
            if (projectile.revealFogPending) revealNuclearFog(projectile)
            projectile.hitApplied = true
            pendingProjectileEvents += ProjectileEventObservation("impact", projectileObservation(projectile))
            steps.current.pendingSoundTriggers += SoundTriggerObservation("reforge:events/nuke-explode", projectile.position, 1f)
        }
        area.remaining = maxOf(0f, area.remaining - delta.toFloat())
        area.radius = area.profile.radius * (1f - area.remaining / area.profile.expandTime)
        projectile.age += delta.toFloat()
        projectile.remainingLife = maxOf(0.0, projectile.remainingLife - delta)
        val source = checkNotNull(units[projectile.sourceUnitId])
        val players = checkNotNull(scenario).players
        val team = players.single { it.playerId == source.ownerPlayerId }.teamId
        for (target in spatialIndex.queryAllLayersCenterBox(projectile.position, area.radius.toDouble() + 1.0)) {
            if (!target.alive || target.carriedBy != null || target.health == null || target.unitId in area.hitUnitIds ||
                target.height.toFloat() < -5f && projectile.height.toFloat() >= -2f ||
                !area.profile.nuclear && target.ownerPlayerId != source.ownerPlayerId && team != null &&
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
            pendingProjectileEvents += ProjectileEventObservation(if (area.profile.nuclear) "area-ended" else "impact", projectileObservation(projectile))
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
        val source = units[shot.sourceUnitId]?.takeIf { it.alive }
        val catalog = source?.definition?.combat?.namedWeapons
        val attachmentMount = shot.attachmentRetargetMount
        val weapon = if (attachmentMount != null) catalog?.ordered?.getOrNull(attachmentMount.index)
            else catalog?.byName?.get(shot.sourceWeaponName) ?: return
        val input = checkNotNull(scenario)
        val owner = source?.ownerPlayerId ?: checkNotNull(shot.sourceOwnerId)
        val forward = ScreenPlane.displacement(shot.heading, 15.0)
        val center = Position(shot.position.x + forward.x, shot.position.y + forward.y)
        require(center.x.isFinite() && center.y.isFinite()) { "Ordinary missile search center must remain finite" }
        val preferred = units[source?.primaryCombatTargetId]
        var nearest = 120.0 * 120.0
        var chosen: MutableUnit? = null
        for (candidate in spatialIndex.queryAllLayersCenterBox(center, 121.0)) {
            if (source == null) {
                if (!CombatTargetQuery.isEnemy(owner, candidate, input.players) ||
                    !CombatTargetQuery.weaponAllows(candidate, checkNotNull(attachmentMount).weapon)) continue
            } else {
                val eligible = if (source.definition.attachmentModule?.empty == true) {
                    candidate.definition.attachmentModule?.empty != true && !CombatTargetQuery.isUnderwater(candidate)
                } else CombatTargetQuery.canAttackAirCategory(source, candidate, input.map)
                if (!eligible) continue
                if (weapon == null) {
                    // The old mount index can fall outside the source's new form after a conversion.
                    if (!CombatTargetQuery.isEnemy(source, candidate, input.players)) continue
                } else if (!NamedWeaponRuntime.qualifies(source, candidate, weapon, input.players,
                        input.map, checkRange = false) || weapon.attachedTo?.let { parent ->
                        NamedWeaponRuntime.qualifies(source, candidate, checkNotNull(catalog).byName.getValue(parent),
                            input.players, input.map, checkRange = false)
                    } == false) continue
            }
            val distance = ScreenPlane.distanceSquared(center, candidate.position)
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
        val owner = checkNotNull(projectile.sourceOwnerId)
        val players = checkNotNull(scenario).players
        val team = players.single { it.playerId == owner }.teamId
        val x = projectile.position.x.toFloat()
        val y = projectile.position.y.toFloat()
        val height = projectile.height.toFloat()
        for (target in spatialIndex.queryAllLayersCenterBox(projectile.position, profile.radius.toDouble() + 1.0)) {
            if (!target.alive || target.carriedBy != null || target.health == null || target.ownerPlayerId == owner ||
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
        val owner = checkNotNull(units[projectile.sourceUnitId]).ownerPlayerId
        applyGroundArea(owner, center, height, profile.areaDamage, profile.areaRadius, profile.areaRadius)
    }

    private fun applyGroundArea(owner: String, center: Position, height: Float, damage: Float, radius: Float, currentRadius: Float) {
        val players = checkNotNull(scenario).players
        val team = players.single { it.playerId == owner }.teamId
        val x = center.x.toFloat()
        val y = center.y.toFloat()
        for (target in spatialIndex.queryAllLayersCenterBox(center, currentRadius.toDouble() + 1.0)) {
            if (!target.alive || target.carriedBy != null || target.health == null || target.ownerPlayerId == owner ||
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
        val attachment = target.attachment
        val incoming = if (attachment != null) {
            val parent = units[attachment.parentId]?.takeIf { it.alive && !it.reclaimed } ?: return 0.0
            val remainder = damageAfterShield(parent, damage, modifiers)
            if (target.definition.attachmentModule?.empty == true) return 0.0
            remainder
        } else damage
        val before = checkNotNull(target.health)
        val targetDamage = incoming * if (target.definition.building != null ||
            target.definition.directHitTargetKind == DirectHitTargetKind.NATIVE_BUILDING) modifiers.building else 1.0
        val armor = target.definition.durability?.armor
        val armoredDamage = if (armor != null && armor.reduction > 0.0 && targetDamage > armor.minimumDamage)
            maxOf(armor.minimumDamage, targetDamage - armor.reduction) else targetDamage
        val constructionDamage = if (target.constructionProgress < 1f) armoredDamage * 1.75 else armoredDamage
        require(constructionDamage.isFinite()) { "Construction damage must remain finite" }
        val applied = target.definition.durability?.maxDamagePerHit?.let { minOf(constructionDamage, it) } ?: constructionDamage
        val after = maxOf(0.0, before - damageAfterShield(target, applied, modifiers))
        target.health = after
        if (after < before) target.sandboxPreservesPositiveAttachmentHealth = false
        pendingEvents += Event("unit-damaged", target.unitId)
        if (!target.alive && attachment == null) processDeath(target)
        return before - after
    }

    private fun damageAfterShield(target: MutableUnit, applied: Double,
        modifiers: com.rustedwarfare.reforge.core.content.ProjectileDamageModifiers): Double =
        UnitShield.damage(target, applied, modifiers) { pendingEvents += Event("shield-damaged", target.unitId) }

    private fun processDeath(target: MutableUnit) {
            steps.current.movePlanning.cancel(target.unitId)
            steps.current.pendingFlowStart.remove(target.unitId)
            target.spiderBlink = null
            val blinkLedger = CustomUnitActions.dropBlinkOnDeath(target, productionLedger)
            if (blinkLedger !== productionLedger) pendingEvents += Event("unit-action-refunded", target.unitId)
            productionLedger = blinkLedger
            if (target.definition.nuclearSilo != null) {
                if (target.ordnance.queue.isNotEmpty()) pendingEvents += Event("unit-action-refunded", target.unitId)
                productionLedger = NuclearOrdnance.discard(target, checkNotNull(productionLedger))
            }
            val cargoIds = target.transportState?.passengerIds
            Transport.onDeath(target, units, checkNotNull(scenario).map, deferCargo = NautilusMotion.slowDeath(target))
            spatialIndex.remove(target.unitId)
            for (cid in target.attachmentIds.values) spatialIndex.remove(cid)
            if (cargoIds != null) {
                for (cid in cargoIds) units[cid]?.let(spatialIndex::relocate)
            }
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
            target.cancelRoute(clearRequested = true)
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
            target.definition.deathExplosion?.takeIf {
                target.constructionProgress >= 1f && (!it.nuclear || checkNotNull(scenario).nukesEnabled)
            }?.let { profile ->
                val id = nextObjectId
                nextObjectId = Math.addExact(nextObjectId, 1L)
                val explosion = MutableProjectile(id, target.unitId, null, target.position, 0.0, maxOf(99.0, profile.expandTime.toDouble()),
                    Position(0.0, 0.0), 0f, 0.0, 0.0, instant = true, nonDeflectable = true,
                    expansion = AreaExpansion(profile, profile.expandTime),
                    appearance = if (profile.nuclear) ProjectileAppearance.NUCLEAR else ProjectileAppearance.STANDARD,
                    alwaysVisibleInFog = profile.nuclear, revealFogPending = profile.nuclear)
                projectiles += explosion
                pendingProjectileEvents += ProjectileEventObservation("born", projectileObservation(explosion))
            }
    }

    private fun recoverStalledMoves(map: MapDefinition, step: Long, passageWaiting: Set<String>,
                                    seaDepartureWaiting: Set<String>, repairBudget: PathWorkBudget) {
        for (unit in orderedUnits()) {
            // ENG-P016-009B: early-exit idle/inactive units before touching spatialIndex.
            val target = unit.moveTarget ?: continue
            if (!unit.alive || unit.carriedBy != null || unit.unitId in passageWaiting || unit.unitId in seaDepartureWaiting) continue
            val stalled = unit.stalledRouteTicks
            val movingContact = unit.hardContactLimitedTicks
            val waitingForRoute = unit.landingMember?.claimed != null && unit.pathPoints.isEmpty()
            if (waitingForRoute && !steps.current.movePlanning.pending(unit.unitId) && unit.landingMember!!.group.repairDue(step)) {
                scheduleManeuverRepair(unit, target, ManeuverRepair.CONTACT)
            }
            var cachedContacts: List<MutableUnit>? = null
            fun lazyContacts(): List<MutableUnit> = cachedContacts ?: routeContacts(unit).also { cachedContacts = it }
            val sustainedContact = unit.followsManeuverRoute &&
                movingContact >= ROUTE_RETRY_TICKS && lazyContacts().isNotEmpty()
            val limited = if (sustainedContact) movingContact else stalled
            val fastStallCandidate = stalled >= FAST_STALL_TICKS &&
                unit.terrainCorrectionTime > 0.0 &&
                unit.terrainOnlyStall &&
                step >= unit.repairBackoffStep &&
                !steps.current.movePlanning.pending(unit.unitId)
            val fastStallEligible = fastStallCandidate && lazyContacts().none { other ->
                other.moveTarget != null && other.speed > 0.05 * UnitLocomotion.movement(other).moveSpeed
            }
            val periodicRetry = limited > 0 && limited % ROUTE_RETRY_TICKS == 0L
            val retry = (periodicRetry && (!unit.terrainOnlyStall || step >= unit.repairBackoffStep)) || fastStallEligible
            // Wall pressure can turn the hull or nudge the body without freeing its route.
            val checkWall = unit.wallRepairDue(step)
            if (!retry && !checkWall) continue
            val lmFlow = unit.landingMember
            if (trafficSeparationEnabled && lmFlow != null && lmFlow.claimed == null &&
                lmFlow.group.members.size >= ChunkedFlowFieldAdapter.MIN_GROUP_SIZE &&
                steps.current.flowFields.activeFieldCount() > 0 &&
                steps.current.flowFields.hasValidGuidanceFor(unit, planningTerrainFor(unit, map))
            ) continue
            if ((checkWall || (stalled >= ROUTE_RETRY_TICKS || fastStallEligible) && unit.terrainCorrectionTime > 0) &&
                unit.landingMember != null && unit.definition.movementCollision is MovementCollisionShape.Circle &&
                unit.pathPoints.isNotEmpty() && repairBudget.take(1 + unit.routeOccupantIds.size)) {
                // A push can leave a legal circle touching the corner beside its planned lane.
                // Try the two checked legs through its own cell before requesting a new long path.
                val pose = unit.position
                val mid = Position((kotlin.math.floor(pose.x / map.tileWidth) + 0.5) * map.tileWidth,
                    (kotlin.math.floor(pose.y / map.tileHeight) + 0.5) * map.tileHeight)
                val next = unit.pathPoints.first()
                // Budget the occupancy lookup and each swept leg before reading it.
                // If this slice runs out, leave the route available to the normal repair queue.
                val terrain = planningTerrainFor(unit, map)
                val hull = HullRoute.forUnit(unit, map)
                val occupancy = routeOccupancy(unit)
                fun fits(from: Position, to: Position): Boolean {
                    val tiles = kotlin.math.ceil(maxOf(kotlin.math.abs(to.x - from.x) / map.tileWidth,
                        kotlin.math.abs(to.y - from.y) / map.tileHeight)).toInt()
                    return repairBudget.take(1 + tiles.coerceAtMost(MOVE_PLANNING_WORK_PER_STEP) + unit.routeOccupantIds.size) &&
                        continuationFits(terrain, from, to, unit.heading, hull, routeRadius(unit), occupancy)
                }
                val bend = if (fits(pose, next)) null else {
                    // When the cell centre is still behind the corner, line up with
                    // the opening on one axis first. Both legs must clear the hull.
                    val x = next.x.coerceIn(pose.x - map.tileWidth, pose.x + map.tileWidth)
                    val y = next.y.coerceIn(pose.y - map.tileHeight, pose.y + map.tileHeight)
                    listOf(mid, Position(pose.x, y), Position(x, pose.y)).firstOrNull {
                        it != pose && it != next && fits(pose, it) && fits(it, next)
                    }
                }
                if (bend != null) {
                    unit.nav = unit.nav.prependWaypoints(listOf(bend), resetLocalSegment = true)
                    continue
                }
            }
            if (!retry) continue
            val nativeDeparture = FactoryExit.isNativeSeaDeparture(unit)
            val headTask = unit.tasks.firstOrNull()
            val move = headTask?.takeIf { it.kind == UnitTaskKind.MOVE }
            val workTask = headTask?.takeIf {
                it.kind == UnitTaskKind.BUILD || it.kind == UnitTaskKind.CONSTRUCT || it.kind == UnitTaskKind.RECLAIM
            }
            if (nativeDeparture && unit.birthRemaining > 0f || (!nativeDeparture && move == null && workTask == null)) continue
            if (workTask != null && !nativeDeparture) {
                val layer = UnitContact.layer(unit)
                val blockers = if (layer != null) {
                    val scanR = unit.definition.movementCollision.boundingRadius * 4.5 + 32.0
                    spatialIndex.queryBounds(
                        SpatialLayer.fromContact(layer),
                        unit.position.x - scanR, unit.position.x + scanR,
                        unit.position.y - scanR, unit.position.y + scanR,
                    ).filter {
                        it.unitId != unit.unitId && knowsRouteOccupant(unit, it) &&
                            (it.moveTarget == null || UnitContact.inContact(unit, it))
                    }
                } else emptyList()
                val occupancy = UnitContact.routeAround(unit, blockers)
                val blockerIds = blockers.map { it.unitId }
                val workRange = unit.definition.builder?.range?.toDouble()?.let { base ->
                    val targetUnit = workTask.targetUnitId?.let { units[it] }
                    val bonus = if (targetUnit?.definition?.production?.exitProfile ==
                        com.rustedwarfare.reforge.core.content.FactoryExitProfile.NATIVE_SEA
                    ) 110.0 else 0.0
                    base + bonus
                } ?: 0.0
                val res = resolveBudgetedTacticalRoute(
                    unit = unit,
                    map = map,
                    actionId = workTask.actionId,
                    atStep = workTask.atStep,
                    target = target,
                    reason = PathSearchReason.ROUTE_REPAIR,
                    executionStep = step,
                    targetRange = workRange,
                    occupancy = occupancy,
                    occupantIds = blockerIds,
                    isRepair = true,
                    budgetOverride = repairBudget,
                    unreachablePolicy = TacticalUnreachablePolicy.SILENT_IGNORE,
                )
                if (res is TacticalRouteResolution.Ready && res.points != unit.pathPoints) {
                    unit.adoptRoute(
                        res.points, logicalRouteTarget(unit, target),
                        plannedKnowledgeRevision(unit), plannedBuildingRevision(unit),
                        blockerIds,
                    )
                }
                continue
            }
            if (unit.followsManeuverRoute || !nativeDeparture && move != null) {
                if (unit.lastManeuverContactRepair != step) {
                    if (fastStallEligible || (periodicRetry && unit.terrainCorrectionTime > 0.0)) {
                        val failures = unit.repairConsecutiveFailures.coerceAtMost(2)
                        val backoff = FAST_STALL_TICKS shl failures // 12 -> 24 -> 48
                        unit.updateNavigation {
                            it.recordRepairBackoff(
                                backoffUntilStep = step + backoff,
                                nextConsecutiveFailures = (unit.repairConsecutiveFailures + 1).coerceAtMost(4),
                            )
                        }
                    }
                    scheduleManeuverRepair(unit, target, ManeuverRepair.CONTACT)
                }
                continue
            }
            val actionId = move?.actionId ?: checkNotNull(unit.originMoveId)
            val atStep = if (move == null) unit.originAtStep else move.atStep
            val occupants = lazyContacts()
            val occupantIds = occupants.map { it.unitId }
            val occupancy = UnitContact.routeAround(unit, occupants)
            val res = resolveBudgetedTacticalRoute(
                unit = unit,
                map = map,
                actionId = actionId,
                atStep = atStep,
                target = target,
                reason = PathSearchReason.ROUTE_REPAIR,
                executionStep = step,
                occupancy = occupancy,
                occupantIds = occupantIds,
                isRepair = true,
                budgetOverride = repairBudget,
                unreachablePolicy = TacticalUnreachablePolicy.STALL_MOVE_GUARDED,
            )
            when (res) {
                TacticalRouteResolution.Pending -> Unit
                is TacticalRouteResolution.Unreachable -> {
                    if (nativeDeparture) continue
                    if (stalled < ROUTE_FAILURE_TICKS) continue
                    if ((unit.groupMoveReservation?.occupiedTicks ?: 0) > 0) continue
                    if (replaceInvalidGroupDestination(unit)) continue
                    unit.taskQueue = unit.taskQueue.pop()
                    unit.cancelRoute()
                    pendingEvents += Event("movement-cancelled", unit.unitId)
                    pendingEvents += Event("path-unreachable", unit.unitId)
                }
                is TacticalRouteResolution.Ready -> {
                    if (res.points != unit.pathPoints) {
                        unit.adoptRoute(
                            res.points, logicalRouteTarget(unit, target), plannedKnowledgeRevision(unit),
                            plannedBuildingRevision(unit), occupantIds,
                        )
                    }
                }
            }
        }
    }

    private fun knowsRouteOccupant(unit: MutableUnit, other: MutableUnit): Boolean =
        other.ownerPlayerId == unit.ownerPlayerId || isVisibleTo(unit.ownerPlayerId, other.position, other.nativeTransportPlacement)

    private fun routeContacts(unit: MutableUnit): List<MutableUnit> {
        val layer = UnitContact.layer(unit) ?: return emptyList()
        val r = unit.definition.movementCollision.boundingRadius + 1.0
        return spatialIndex.queryBounds(
            SpatialLayer.fromContact(layer),
            unit.position.x - r, unit.position.x + r,
            unit.position.y - r, unit.position.y + r,
        ).filter { it.unitId != unit.unitId && knowsRouteOccupant(unit, it) && UnitContact.inContact(unit, it) }
    }


    private fun scheduleManeuverRepair(unit: MutableUnit, target: Position, reason: ManeuverRepair,
                                       group: GroupMoveRequest? = null) {
        val original = checkNotNull(unit.originMoveId)
        val action = MoveAction(original, unit.ownerPlayerId, unit.unitId, target, world, unit.originAtStep)
        steps.current.movePlanning.repair(action, reason, group)
    }

    private fun cancelBoardedMove(unitId: String) {
        val planning = steps.current.movePlanning
        val removedFlow = steps.current.pendingFlowStart.remove(unitId)
        if (!planning.pending(unitId) && !removedFlow) return
        planning.cancel(unitId)
        val passenger = units.getValue(unitId)
        if (passenger.tasks.firstOrNull()?.kind == UnitTaskKind.MOVE) passenger.taskQueue = passenger.taskQueue.pop()
    }

    private fun routeOccupancy(unit: MutableUnit, includeMoving: Boolean = false): RouteOccupancy? {
        if (unit.routeOccupantIds.isEmpty()) return null
        val visible = unit.routeOccupantIds.mapNotNull { id -> units[id]?.takeIf {
            knowsRouteOccupant(unit, it) &&
                (includeMoving || unit.definition.movementCollision !is MovementCollisionShape.Circle || it.moveTarget == null)
        } }
        // A circular follower may enter a long leg behind an advancing convoy.
        // Per-step hard contact governs their actual separation; a moving body
        // farther along the leg must not pin the follower at its previous node.
        return UnitContact.routeAround(unit, visible)
    }

    private fun prepareRouteSuccessors(map: MapDefinition, stepIndex: Long, samples: MutableList<RouteContinuationSample>?) {
        var budget = routePreparationBudget(stepIndex)
        val handoffDistance = checkNotNull(scenario?.movementContract).pathPointRadius
        val readyIn = 40.0
        val candidates = orderedUnits().mapNotNull { unit ->
            if (!preparesRouteSuccessor(unit) || unit.routeContinuation?.ready == true) return@mapNotNull null
            val movement = UnitLocomotion.movement(unit)
            val speed = UnitLocomotion.effectiveMaximumSpeed(unit, movement)
            if (speed <= 0.0) return@mapNotNull null
            val remaining = (kotlin.math.sqrt(ScreenPlane.distanceSquared(unit.position, unit.pathPoints.first())) -
                handoffDistance).coerceAtLeast(0.0)
            val remainingTicks = remaining / speed
            (unit to remainingTicks).takeIf { remainingTicks <= readyIn }
        }.sortedWith(compareBy<Pair<MutableUnit, Double>> { it.second }.thenBy { it.first.updateKey })
        for ((unit, remaining) in candidates) {
            val continuation = requestRouteContinuation(unit, stepIndex, remaining, samples)
            if (budget <= 0) continue
            budget--
            val terrain = planningTerrainFor(unit, map)
            val hull = HullRoute.forUnit(unit, map)
            val start = if (continuation.waiting) Transport.queryPosition(unit, map) else continuation.anchor
            val incoming = if (continuation.waiting) unit.heading
                else (hull as? RectangularDeparture)?.headingTowards(unit.position, start, unit.heading) ?: unit.heading
            if (continuationFits(terrain, start, continuation.successor, incoming, hull, routeRadius(unit),
                    routeOccupancy(unit))) {
                unit.updateNavigation { it.withRouteContinuation(continuation.copy(ready = true)) }
                recordRouteContinuation(samples, stepIndex, unit, continuation, remaining, RouteContinuationStage.READY)
            }
        }
    }

    private fun requestRouteContinuation(unit: MutableUnit, step: Long, estimate: Double,
                                         samples: MutableList<RouteContinuationSample>?): RouteContinuation {
        unit.routeContinuation?.let { return it }
        val continuation = RouteContinuation(unit.pathPoints[0], unit.pathPoints[1])
        unit.updateNavigation { it.withRouteContinuation(continuation) }
        recordRouteContinuation(samples, step, unit, continuation, estimate, RouteContinuationStage.REQUESTED)
        return continuation
    }

    private fun recordRouteContinuation(samples: MutableList<RouteContinuationSample>?, step: Long, unit: MutableUnit,
                                        continuation: RouteContinuation, remaining: Double, stage: RouteContinuationStage) {
        samples?.add(RouteContinuationSample(step, unit.unitId, checkNotNull(unit.originMoveId),
            continuation.anchor, continuation.successor, remaining, stage))
    }

    private fun continuationFits(terrain: PlanningTerrain, start: Position, target: Position, heading: Double,
                                 hull: HullRoute?, radius: Double, occupancy: RouteOccupancy? = null): Boolean {
        val arrival = if (hull == null) {
            if (!PlanningSegmentClearance.allows(terrain, start, target, radius)) return false
            heading
        } else hull.arrival(HullTerrainGrid.authorized(terrain), start, target, heading) ?: return false
        return occupancy?.allows(start, target, heading, arrival) != false
    }

    private fun preparesRouteSuccessor(unit: MutableUnit): Boolean =
        unit.alive && unit.carriedBy == null && unit.attachment == null && unit.birthRemaining == 0f &&
            !unit.followsManeuverRoute && unit.motionContract?.preparesRouteSuccessor == true && unit.pathPoints.size > 1 &&
            UnitLocomotion.movement(unit).type != MovementType.AIR

    private fun onMotionArrivalCompleted(unit: MutableUnit, completedMoveId: String?) {
        val head = unit.tasks.firstOrNull() ?: return
        if (head.kind == UnitTaskKind.MOVE ||
            (completedMoveId != null && head.actionId == completedMoveId && head.motionContract?.goal?.completesOnCapture == true)
        ) {
            unit.taskQueue = unit.taskQueue.pop()
        }
    }

    private val movementHost = object : UnitMovementHost {
        override val activeWorld: WorldHandle get() = this@SimulationContext.world
        override val simulationTime: Double get() = this@SimulationContext.simulationTime
        override val movementContract: MovementContract get() = checkNotNull(this@SimulationContext.scenario?.movementContract)
        override val trafficSeparationEnabled: Boolean get() = this@SimulationContext.trafficSeparationEnabled
        override val units: Map<String, MutableUnit> get() = this@SimulationContext.units
        override val spatialIndex: WorldSpatialIndex get() = this@SimulationContext.spatialIndex
        override val landingOccupancy: LandingOccupancy get() = this@SimulationContext.steps.current.landingOccupancy
        override val navigationGuidance: NavigationGuidance get() = this@SimulationContext.flowFields
        override val buildingTerrain: BuildingTerrain? get() = this@SimulationContext.buildingTerrain
        override val pendingEvents: MutableList<Event> get() = this@SimulationContext.pendingEvents
        override val steeringFaultHook: ((MutableUnit) -> Unit)? get() = this@SimulationContext.steeringFaultHook

        override fun unitActionHead(unit: MutableUnit): PendingUnitAction? =
            this@SimulationContext.unitActionHead(unit)

        override fun logicalRouteTarget(unit: MutableUnit, fallback: Position): Position =
            this@SimulationContext.logicalRouteTarget(unit, fallback)

        override fun planningTerrainFor(unit: MutableUnit, map: MapDefinition): PlanningTerrain =
            this@SimulationContext.planningTerrainFor(unit, map)

        override fun routeRadius(unit: MutableUnit): Double =
            this@SimulationContext.routeRadius(unit)

        override fun routeOccupancy(unit: MutableUnit, includeMoving: Boolean): RouteOccupancy? =
            this@SimulationContext.routeOccupancy(unit, includeMoving)

        override fun continuationFits(
            terrain: PlanningTerrain,
            start: Position,
            target: Position,
            heading: Double,
            hull: HullRoute?,
            radius: Double,
            occupancy: RouteOccupancy?,
        ): Boolean = this@SimulationContext.continuationFits(terrain, start, target, heading, hull, radius, occupancy)

        override fun scheduleManeuverRepair(unit: MutableUnit, target: Position, reason: ManeuverRepair) =
            this@SimulationContext.scheduleManeuverRepair(unit, target, reason)

        override fun groupStopClearsSlots(unit: MutableUnit): Boolean =
            this@SimulationContext.groupStopClearsSlots(unit)

        override fun preparesRouteSuccessor(unit: MutableUnit): Boolean =
            this@SimulationContext.preparesRouteSuccessor(unit)

        override fun onMotionArrivalCompleted(unit: MutableUnit, completedMoveId: String?) =
            this@SimulationContext.onMotionArrivalCompleted(unit, completedMoveId)

        override fun requestRouteContinuation(
            unit: MutableUnit,
            step: Long,
            estimate: Double,
            samples: MutableList<RouteContinuationSample>?,
        ): RouteContinuation = this@SimulationContext.requestRouteContinuation(unit, step, estimate, samples)

        override fun recordRouteContinuation(
            samples: MutableList<RouteContinuationSample>?,
            step: Long,
            unit: MutableUnit,
            continuation: RouteContinuation,
            remaining: Double,
            stage: RouteContinuationStage,
        ) = this@SimulationContext.recordRouteContinuation(samples, step, unit, continuation, remaining, stage)

        override fun knowsRouteOccupant(unit: MutableUnit, other: MutableUnit): Boolean =
            this@SimulationContext.knowsRouteOccupant(unit, other)

        override fun canDisplaceInContact(unit: MutableUnit): Boolean =
            this@SimulationContext.canDisplaceInContact(unit)

        override fun isVisibleTo(playerId: String, storedPosition: Position, nativeTransportPlacement: Boolean): Boolean =
            this@SimulationContext.isVisibleTo(playerId, storedPosition, nativeTransportPlacement)

        override fun isParkedPeer(mover: MutableUnit, other: MutableUnit): Boolean =
            this@SimulationContext.isParkedPeer(mover, other)

        override fun blockAtMapBoundary(unit: MutableUnit) =
            this@SimulationContext.blockAtMapBoundary(unit)

        override fun approachSpeed(current: Double, target: Double, maxDelta: Double): Double =
            approach(current, target, maxDelta)
    }

    private fun updateUnit(unit: MutableUnit, map: MapDefinition, delta: Double, executionStep: Long,
                           step: CandidateStep, passageWaiting: Boolean, departureWaiting: Boolean) {
        UnitMovementOrchestrator.advanceUnit(
            movementHost, unit, map, delta, executionStep, step, passageWaiting, departureWaiting,
        )
    }

    private fun isParkedPeer(mover: MutableUnit, other: MutableUnit): Boolean =
        other.landingMember?.let { it.parked && it.group === mover.landingMember?.group } == true

    private fun canDisplaceInContact(unit: MutableUnit): Boolean {
        val movement = UnitLocomotion.movement(unit)
        return unit.birthRemaining == 0f &&
            BuildingWork.isReady(unit) && movement.moveSpeed > 0.0 && movement.type != MovementType.NONE &&
            !unit.constructionWorking && unit.spiderBlink == null &&
            !CustomUnitActions.movementLocked(unit, unitActionHead(unit)) &&
            unit.transportState?.unloading != true && !Transport.isBoarding(unit, units)
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
        materializedObservationCount++
        val currentScenario = checkNotNull(scenario)
        val mergedEvents = if (steps.current.deferredWindowEvents.isEmpty()) pendingEvents
            else steps.current.deferredWindowEvents + pendingEvents
        val mergedActionResults = if (steps.current.deferredWindowActionResults.isEmpty()) pendingActionResults
            else steps.current.deferredWindowActionResults + pendingActionResults
        val mergedProjectileEvents = if (steps.current.deferredWindowProjectileEvents.isEmpty()) pendingProjectileEvents
            else steps.current.deferredWindowProjectileEvents + pendingProjectileEvents
        val observation = Observation(
            sampleId = "${currentScenario.scenarioId}:$worldGeneration:${if (advanced) "step$observedStep" else "initial"}:$observedRevision",
            worldId = "${currentScenario.scenarioId}:$worldGeneration",
            scenarioId = currentScenario.scenarioId,
            stepIndex = observedStep,
            simulationTime = observedTime,
            units = orderedUnits().filter { !it.reclaimed }.sortedBy { it.unitId }.map { unit ->
                val attachments = UnitAttachments.overview(steps.current, unit)
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
                    unitActions = attachments?.let { UnitAttachments.observe(steps.current, unit, it, observedTime) }
                        ?: NuclearOrdnance.observe(unit) ?: CustomUnitActions.observe(unit, steps.current.actionClock, currentScenario, unitActionQueue(unit), observedTime),
                    nuclearDoors = unit.definition.nuclearSilo?.let { unit.ordnance.doors },
                    attachment = unit.attachment?.let {
                        AttachedUnitObservation(it.parentId, it.slot.number, checkNotNull(unit.definition.attachmentModule).empty)
                    },
                    attachmentOverview = attachments,
                    factoryRally = unit.factoryRally.takeIf { unit.definition.production?.rallyEnabled == true && unit.alive },
                    submarine = unit.definition.submarine?.let { SubmarineObservation(unit.submarineSurfaceIntent,
                        unit.submarineVerticalSpeed, UnitLocomotion.isSubmerged(unit), unit.submarineSurfacePresentation,
                        UnitLocomotion.movement(unit).moveSpeed, UnitCombat.range(unit)) },
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
                    moveArrival = unit.moveArrival,
                    constructionProgress = unit.constructionProgress,
                    mechPose = MechMotion.pose(unit),
                    waterPose = if (unit.definition.movement.type == MovementType.WATER && unit.definition.fixedUnderwaterTarget == null)
                        WaterPose(unit.bodyReversing, unit.sinkSpeed, unit.waterSettled) else null,
                    hoverPose = if (unit.definition.movement.hover != null) HoverPose(unit.flightVelocity, unit.heightPhase) else null,
                    flightPose = if (unit.definition.movement.type == MovementType.AIR && unit.amphibiousState == null) FlightPose(
                        unit.flightVelocity, unit.height, unit.heightPhase, unit.rotorPhase,
                        ScreenPlane.normalize(unit.heading + unit.rotorPhase), unit.rotorHeightOffset,
                        if (unit.definition.energy != null) FlightFallPose(unit.sinkSpeed, unit.flightLanded,
                            unit.flightLandedInWater, unit.waterSettled) else null) else null,
                    constructionTargetId = unit.tasks.firstOrNull()?.takeIf {
                        unit.alive && unit.constructionWorking && (it.kind == UnitTaskKind.CONSTRUCT || it.kind == UnitTaskKind.RECLAIM) &&
                            units[it.targetUnitId]?.alive == true
                    }?.targetUnitId,
                )
            },
            events = mergedEvents,
            reclaimedUnitIds = if (currentScenario.maintenanceContract != null ||
                currentScenario.unitDefinitions.values.any { it.maintenance != null })
                orderedUnits().filter { unit -> unit.reclaimed }.map { unit -> unit.unitId }.sorted() else null,
            actionResults = mergedActionResults,
            projectiles = projectiles.map(::projectileObservation),
            projectileEvents = mergedProjectileEvents,
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

