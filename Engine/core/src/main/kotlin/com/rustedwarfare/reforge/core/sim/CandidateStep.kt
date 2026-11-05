package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import com.rustedwarfare.reforge.core.path.PathSearchSample
import com.rustedwarfare.reforge.core.knowledge.*
import java.util.IdentityHashMap

internal data class CachedPlanningTerrain(val world: WorldHandle, val map: MapDefinition,
    val knowledge: KnowledgeSnapshot?, val terrain: PlanningTerrain, val buildings: Set<Pair<Int, Int>>? = null,
    val escapingBuildings: Boolean = false,
    val movementType: com.rustedwarfare.reforge.core.content.MovementType = com.rustedwarfare.reforge.core.content.MovementType.LAND)

internal data class AutomaticKnowledgeCommit(
    val stores: Map<String, PlayerTerrainKnowledge>,
    val views: Map<String, VisionSnapshot>,
    val discoveries: Map<String, MutableMap<Pair<Int, Int>, Long>>,
    val visionState: AutomaticVisionState,
)

internal data class BuildingCommit(val truth: BuildingTerrain,
    val owners: Map<String, BuildingKnowledge>, val discoveries: Map<String, Map<Pair<Int, Int>, Long>>)

/** Mutable ownership for one working step; immutable gameplay values are shared until replaced.
 * Add candidate state here, and isolate mutable containers in [fork]. Projection caches that
 * only observePlayer mutates belong to that layer, outside this single-writer transaction.
 */
internal data class StepState(
    var units: MutableMap<String, MutableUnit> = linkedMapOf(),
    var projectiles: MutableList<MutableProjectile> = mutableListOf(),
    var combatFrame: Int = -1,
    var nextObjectId: Long = 1L,
    var lastBirthSerial: Long = 0L,
    var buildingWork: BuildingWork = BuildingWork.initial(),
    var productionLedger: ProductionLedger? = null,
    var ordinaryUpgrades: OrdinaryUpgrades? = null,
    var missileTargets: MissileTargetIndex? = null,
    var buildingTerrain: BuildingTerrain? = null,
    var buildingKnowledge: Map<String, BuildingKnowledge> = emptyMap(),
    var pendingBuildingDiscoveries: Map<String, Map<Pair<Int, Int>, Long>> = emptyMap(),
    var stepIndex: Long = 0,
    var simulationTime: Double = 0.0,
    var hasAdvanced: Boolean = false,
    var revision: Long = 0L,
    var pendingEvents: MutableList<Event> = mutableListOf(),
    var pendingProjectileEvents: MutableList<ProjectileEventObservation> = mutableListOf(),
    var pendingSoundTriggers: MutableList<SoundTriggerObservation> = mutableListOf(),
    var pendingActionResults: MutableList<ActionResult> = mutableListOf(),
    var latestObservation: Observation? = null,
    var committedObservation: Observation? = null,
    var scheduledActions: MutableList<Action> = mutableListOf(),
    var actionResultOwners: IdentityHashMap<ActionResult, String> = IdentityHashMap(),
    var playerActionRevisions: MutableMap<String, Long> = mutableMapOf(),
    var noFogPlanning: CachedPlanningTerrain? = null,
    var playerPlanning: MutableMap<String, CachedPlanningTerrain> = mutableMapOf(),
    var knowledgeStores: Map<String, PlayerTerrainKnowledge>? = null,
    var automaticVisionSnapshots: Map<String, VisionSnapshot>? = null,
    var automaticVisionState: AutomaticVisionState? = null,
    var pathDiagnostics: MutableList<PathSearchSample>? = null,
    var motionDiagnostics: MutableList<MotionStepSample>? = null,
    var pendingKnowledgeDiscoveries: MutableMap<String, MutableMap<Pair<Int, Int>, Long>> = mutableMapOf(),
) {
    fun fork(): StepState = copy(
        units = units.mapValuesTo(linkedMapOf()) { (_, unit) ->
            val combat = unit.combat?.copy()
            val named = unit.namedWeapons?.mapValuesTo(linkedMapOf()) { (_, state) ->
                if (state === unit.combat) checkNotNull(combat) else state.copy()
            }
            unit.copy(pathPoints = unit.pathPoints.toMutableList(), combat = combat, namedWeapons = named,
                pointDefense = unit.pointDefense?.copy()) },
        projectiles = projectiles.mapTo(mutableListOf()) {
            it.copy(expansion = it.expansion?.let { area -> area.copy(hitUnitIds = LinkedHashSet(area.hitUnitIds)) }) },
        pendingEvents = pendingEvents.toMutableList(),
        pendingProjectileEvents = pendingProjectileEvents.toMutableList(),
        pendingSoundTriggers = pendingSoundTriggers.toMutableList(),
        pendingActionResults = pendingActionResults.toMutableList(),
        scheduledActions = scheduledActions.toMutableList(),
        actionResultOwners = IdentityHashMap(actionResultOwners),
        playerActionRevisions = HashMap(playerActionRevisions),
        playerPlanning = HashMap(playerPlanning),
        pathDiagnostics = pathDiagnostics?.toMutableList(),
        pendingKnowledgeDiscoveries = pendingKnowledgeDiscoveries.mapValuesTo(linkedMapOf()) {
            (_, cells) -> LinkedHashMap(cells) },
        // Motion samples are staged separately and appended only on successful publication.
        // Do not recopy the already committed, potentially long diagnostic history per step.
        // Vision source counts and knowledge blocks are immutable; sharing them is a fork.
    )

    fun validate(input: Scenario) {
        val objectIds = mutableSetOf<Long>()
        var previousKey: String? = null
        for (unit in units.values) {
            require(input.unitDefinitions[unit.typeId] === unit.definition) { "Unit definition must remain registered" }
            require(listOf(unit.position.x, unit.position.y, unit.heading, unit.speed, unit.angularVelocity, unit.height)
                .all { it.isFinite() } && (unit.health?.isFinite() != false)) { "Unit candidate must remain finite" }
            MechMotion.requireFinitePose(unit)
            require(unit.sinkSpeed.isFinite() && unit.sinkSpeed >= 0f) { "Water-body sink state must remain finite" }
            val birthLimit = unit.producerId?.let { units[it]?.definition?.production?.exitProfile }
                .let { when (it) {
                    com.rustedwarfare.reforge.core.content.FactoryExitProfile.CUSTOM_MECH -> 45f
                    com.rustedwarfare.reforge.core.content.FactoryExitProfile.NATIVE_SEA -> 80f
                    else -> 30f
                } }
            require(unit.birthRemaining.isFinite() && unit.birthRemaining in 0f..birthLimit) { "Invalid birth timer" }
            unit.taskQueue.validateCandidate()
            require((unit.logicalRequestX == null) == (unit.logicalRequestY == null) &&
                unit.logicalRequestX?.isFinite() != false && unit.logicalRequestY?.isFinite() != false) {
                "Logical movement coordinates must be a finite pair"
            }
            require(unit.pendingPathNodeCounts.all { it >= 0 } && unit.consumedPathNodeCounts.all { it >= 0 }) {
                "Accepted path node counts must remain nonnegative"
            }
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
                    producer.definition.production?.unitTypeIds?.contains(unit.typeId) == true &&
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
            for (state in unit.namedWeapons?.values ?: listOfNotNull(unit.combat)) {
                require(listOf(state.turretHeading, state.angularVelocity, state.cooldown, state.offset.x, state.offset.y,
                    state.idleBaseHeading, state.idleOffset, state.warmup)
                    .all { it.isFinite() }) { "Weapon candidate must remain finite" }
                require(state.warmup >= 0.0) { "Warmup must remain nonnegative" }
                require(state.idleTimer.isFinite() && state.idleHold.isFinite() && state.idleHold >= 0f &&
                    state.idleDirection in setOf(-1, 1)) { "Invalid idle candidate" }
                require(state.patrolSearchTimer.isFinite() && state.patrolSearchTimer >= 0f &&
                    state.patrolDispatchTimer.isFinite() && state.patrolDispatchTimer >= 0f) {
                    "Invalid patrol combat timers"
                }
            }
            if (unit.combat != null) require(OrdinaryWeapon.hasFiniteGeometry(unit)) { "Weapon geometry must remain finite" }
            unit.pointDefense?.let { state ->
                require(state.energy.isFinite() && state.energy in 0f..1f) { "Defense energy candidate must remain finite and bounded" }
                val muzzle = PointDefense.muzzle(unit)
                require(muzzle.x.isFinite() && muzzle.y.isFinite()) { "Defense muzzle must remain finite" }
            }
        }
        for (projectile in projectiles) {
            require(listOf(projectile.position.x, projectile.position.y,
                projectile.height, projectile.heading, projectile.remainingLife, projectile.speed).all { it.isFinite() } &&
                projectile.speed >= 0.0 &&
                projectile.age.isFinite() && projectile.age >= 0f && projectile.deflectionHealth.isFinite() &&
                projectile.verticalSpeed.isFinite() && projectile.groundDistanceSquared?.isFinite() != false &&
                projectile.ballisticRawAz.isFinite() && !(projectile.ballistic != null && projectile.missile != null) &&
                projectile.ground?.let { it.x.isFinite() && it.y.isFinite() && it.height.isFinite() } != false &&
                (projectile.missile == null || projectile.speed.toFloat().let { it.isFinite() && it >= 0f } && projectile.missileRawAz.isFinite())) {
                "Projectile candidate must remain finite"
            }
            require(projectile.objectId > 0L && projectile.objectId < nextObjectId && objectIds.add(projectile.objectId)) {
                "Invalid or duplicate projectile object ID"
            }
        }
        productionLedger?.validateCandidate()
        productionLedger?.validateUpgrades(units)
        missileTargets?.validate(units)
        ordinaryUpgrades?.validateCandidate(units)
        // Ended/event-only objects also enter the public window; removing them from the active
        // container must not let an invalid numeric candidate bypass the S4 publication gate.
        for (event in pendingProjectileEvents) {
            val projectile = event.projectile
            require(listOf(projectile.position.x, projectile.position.y, projectile.height,
                projectile.heading, projectile.remainingLife).all { it.isFinite() } && event.damage?.isFinite() != false &&
                projectile.age.isFinite() && projectile.age >= 0f && projectile.deflectionHealth.isFinite() &&
                projectile.ground?.let { it.targetPosition.x.isFinite() && it.targetPosition.y.isFinite() &&
                    it.targetHeight.isFinite() && it.verticalSpeed.isFinite() && it.distanceSquared?.isFinite() != false } != false &&
                projectile.missile?.let { it.speed.isFinite() && it.speed >= 0f && it.rawAz.isFinite() } != false) {
                "Projectile event candidate must remain finite"
            }
        }
        for (sound in pendingSoundTriggers) require(sound.position.x.isFinite() && sound.position.y.isFinite() &&
            sound.volume.isFinite() && sound.volume > 0f && sound.volume <= 1f && sound.eventId.isNotBlank()) {
            "Sound trigger candidate must remain finite and bounded"
        }
    }
}

/** The former per-field rollback list is now a complete state identity. */
private data class StepCheckpoint(val state: StepState)

/** One entry for all step commit/rollback. Gameplay callbacks preserve their existing order;
 * neither the callback nor future gameplay modules have to coordinate a second rollback list.
 */
internal class CandidateSteps {
    var current: StepState = StepState()
        private set

    fun advance(input: Scenario, step: Step, actions: List<Action>,
                observe: (Long, Double, Long) -> Observation, update: (CandidateStep) -> Unit): Observation {
        val previous = StepCheckpoint(current)
        val candidate = CandidateStep(previous.state.fork(), step)
        current = candidate.state
        try {
            update(candidate)
            val observation = candidate.publish(input, actions, observe)
            candidate.releasePreviousKnowledge(previous.state)
            return observation
        } catch (failure: Exception) {
            current = previous.state
            candidate.abort()
            throw failure
        }
    }
}

/** Owns projected resources and the publication window, as well as the working state. */
internal class CandidateStep(val state: StepState, private val step: Step) {
    val existingProjectileIds = state.projectiles.mapTo(hashSetOf()) { it.objectId }
    val motionSamples = state.motionDiagnostics?.let { mutableListOf<MotionStepSample>() }
    private var knowledge: AutomaticKnowledgeCommit? = null
    private var buildings: BuildingCommit? = null
    val vision: Map<String, VisionSnapshot>? get() = knowledge?.views ?: state.automaticVisionSnapshots

    fun stageKnowledge(next: AutomaticKnowledgeCommit?) { knowledge = next }
    fun stageBuildings(next: BuildingCommit?) { buildings = next }

    fun publish(input: Scenario, actions: List<Action>, observe: (Long, Double, Long) -> Observation): Observation {
        val nextTime = state.simulationTime + step.delta
        require(nextTime.isFinite()) { "Simulation time must remain finite" }
        state.validate(input)
        val observation = observe(step.stepIndex, nextTime, state.revision + 1L)
        val owners = IdentityHashMap<ActionResult, String>(observation.actionResults.size)
        for (result in observation.actionResults) state.actionResultOwners[result]?.let { owners[result] = it }
        val committedMotion = motionSamples?.let { checkNotNull(state.motionDiagnostics) }

        state.pendingKnowledgeDiscoveries.clear()
        knowledge?.let {
            state.knowledgeStores = it.stores
            state.automaticVisionSnapshots = it.views
            state.automaticVisionState = it.visionState
            state.pendingKnowledgeDiscoveries.putAll(it.discoveries)
        }
        buildings?.let {
            state.buildingTerrain = it.truth
            state.buildingKnowledge = it.owners
            state.pendingBuildingDiscoveries = it.discoveries
        }
        state.scheduledActions.removeAll(actions.toSet())
        state.stepIndex = step.stepIndex
        state.hasAdvanced = true
        state.simulationTime = nextTime
        state.revision += 1L
        state.latestObservation = observation
        state.committedObservation = observation
        // Retain only the committed result window and subsequent immediate results.
        state.actionResultOwners = owners
        state.pendingEvents.clear()
        state.pendingProjectileEvents.clear()
        state.pendingSoundTriggers.clear()
        state.pendingActionResults.clear()
        motionSamples?.let { checkNotNull(committedMotion).addAll(it) }
        return observation
    }

    fun releasePreviousKnowledge(previous: StepState) {
        if (knowledge != null) previous.knowledgeStores?.values?.forEach { it.close() }
    }

    fun abort() { knowledge?.stores?.values?.forEach { it.close() } }
}
