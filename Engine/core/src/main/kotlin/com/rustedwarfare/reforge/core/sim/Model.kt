package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.GroundProjectileProfile
import java.util.Collections
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.KnowledgeSnapshot
import com.rustedwarfare.reforge.core.knowledge.VisionSnapshot

data class Position(val x: Double, val y: Double)

data class CoordinateModel(
    val id: String,
    val space: String,
    val xAxis: String,
    val yAxis: String,
    val origin: String,
    val unit: String,
    val valueType: String,
    val precision: String,
)

data class TimeModel(val id: String, val deltaUnit: String, val precision: String, val stepRule: String)

enum class PlayerControl { LOCAL, NONE }

data class Player(val playerId: String, val control: PlayerControl, val teamId: String? = null)

/** Opaque local capability. Display IDs may repeat across independent runs; identity may not. */
class WorldHandle internal constructor(val id: String)

/**
 * [pathPointRadius] controls independent planner waypoint consumption.
 * [targetRadius] is retained as a validated legacy input for old scenario/DTO identities.
 * Ordinary Move completion now uses the near-target time rule (7/16/36); the final
 * propulsion waypoint's zero-target gate is independently fixed at squared distance 16.
 */
data class MovementContract(val pathPointRadius: Double, val targetRadius: Double)

data class UnitSpawn(
    val unitId: String,
    val ownerPlayerId: String,
    val typeId: String,
    val position: Position,
    val heading: Double,
    val height: Double = 0.0,
)

data class Scenario(
    val scenarioId: String,
    val map: MapDefinition,
    val unitDefinitions: Map<String, UnitDefinition>,
    val players: List<Player>,
    val units: List<UnitSpawn>,
    val coordinateModel: CoordinateModel,
    val timeModel: TimeModel,
    val movementContract: MovementContract?,
    val automaticVision: KnowledgeRules? = null,
    val combatContract: CombatContract? = null,
    val economyContract: EconomyContract? = null,
)

data class EconomyContract(val initialBalances: Map<String, Long>)

/** Trusted host queue state. Nonces and payment details never enter player queue DTOs. */
data class QueuedProductionObservation(val typeId: String, val unitNonce: String,
    val progress: Float, val cost: Long, val rate: Float)

class EconomyObservation(balances: Map<String, Long>,
    queuedProduction: Map<String, List<QueuedProductionObservation>> = emptyMap()) {
    val balances: Map<String, Long> = Collections.unmodifiableMap(LinkedHashMap(balances))
    val queuedProduction: Map<String, List<QueuedProductionObservation>> =
        Collections.unmodifiableMap(queuedProduction.mapValuesTo(linkedMapOf()) { immutable(it.value) })
}

data class ProductionQueueItem(val typeId: String, val progress: Float)

data class UpgradeQueueItem(val actionKey: String, val targetTypeId: String, val progress: Float)

data class PendingUpgradeObservation(val actionKey: String, val targetTypeId: String, val progress: Float,
    val cost: Long, val rate: Float)

class UpgradeObservation(queues: Map<String, List<PendingUpgradeObservation>>) {
    val queues: Map<String, List<PendingUpgradeObservation>> = Collections.unmodifiableMap(
        queues.mapValuesTo(linkedMapOf()) { immutable(it.value) })
}

class UpgradeQueueObservation(val unitId: String, items: List<UpgradeQueueItem>) {
    val items: List<UpgradeQueueItem> = immutable(items)
    override fun equals(other: Any?): Boolean = other is UpgradeQueueObservation && unitId == other.unitId && items == other.items
    override fun hashCode(): Int = 31 * unitId.hashCode() + items.hashCode()
}

class FactoryQueueObservation(val factoryId: String, items: List<ProductionQueueItem>) {
    val items: List<ProductionQueueItem> = immutable(items)
    override fun equals(other: Any?): Boolean = other is FactoryQueueObservation &&
        factoryId == other.factoryId && items == other.items
    override fun hashCode(): Int = 31 * factoryId.hashCode() + items.hashCode()
}

/** Trusted deterministic scene input, unrelated to command/display string IDs. */
data class CombatContract(val worldSeed: Int, val initialFrameCounter: Int,
    val unitObjectIds: Map<String, Long>, val nextObjectId: Long)

data class Step(val stepIndex: Long, val delta: Double, val deltaUnit: String)

enum class MovementState { IDLE, MOVING, DECELERATING, BLOCKED }

enum class UnitTaskKind { MOVE, ATTACK, PATROL }

/** Owner control intent; temporary combat targets never become stored patrol points. */
data class UnitTask(val kind: UnitTaskKind, val actionId: String, val target: Position? = null,
    val targetUnitId: String? = null, val atStep: Long? = null)

data class UnitObservation(
    val unitId: String,
    val ownerPlayerId: String,
    val typeId: String,
    val position: Position,
    val heading: Double,
    val speed: Double,
    /** Steering-rate state (reference cg), applied only when body steering is invoked. */
    val angularVelocity: Double,
    val moveTarget: Position?,
    val pathPoints: List<Position>,
    val movementState: MovementState,
    val health: Double? = null,
    val maxHealth: Double? = null,
    val alive: Boolean = true,
    val attackTargetId: String? = null,
    val turretHeading: Double = heading,
    val cooldown: Double? = null,
    val height: Double = 0.0,
    val pointDefense: PointDefenseObservation? = null,
    val tasks: List<UnitTask> = emptyList(),
)

/** Host-only identity/target and arithmetic state. Never copied into player DTOs. */
data class ProjectileObservation(val objectId: Long, val sourceUnitId: String, val targetUnitId: String?,
    val position: Position, val heading: Double, val remainingLife: Double, val offset: Position, val scatter: Float,
    val height: Double = 0.0, val age: Float = 0f, val deflectionHealth: Float = 1f,
    val intercepted: Boolean = false, val ground: GroundProjectileObservation? = null)

data class GroundProjectileObservation(val targetPosition: Position, val targetHeight: Float, val verticalSpeed: Float,
    val distanceSquared: Float? = null)

data class PointDefenseObservation(val energy: Float, val exhausted: Boolean)

/** One public appearance class. Identical newly visible objects have one token and multiplicity. */
data class VisibleProjectile(val token: String, val multiplicity: Int, val position: Position, val heading: Double,
    val height: Double = 0.0, val parentToken: String? = null)

data class ProjectileEventObservation(val kind: String, val projectile: ProjectileObservation, val damage: Double? = null,
    val interceptorUnitId: String? = null)

data class VisibleProjectileEvent(val token: String, val kind: String, val occurrences: Int,
    val position: Position, val heading: Double, val height: Double, val sourceUnitId: String?,
    val targetUnitId: String?, val damage: Double?, val parentToken: String? = null,
    val interceptorUnitId: String? = null)

/** Trusted-host state for deterministic replay/rollback verification, absent from player observations. */
class CombatObservation(val frameCounter: Int, val nextObjectId: Long, weapons: List<UnitWeaponObservation>) {
    val objectIdsExhausted: Boolean get() = nextObjectId == Long.MAX_VALUE
    val weapons: List<UnitWeaponObservation> = immutable(weapons)
    override fun equals(other: Any?): Boolean = other is CombatObservation && frameCounter == other.frameCounter &&
        nextObjectId == other.nextObjectId && weapons == other.weapons
    override fun hashCode(): Int = 31 * (31 * frameCounter + nextObjectId.hashCode()) + weapons.hashCode()
}

data class UnitWeaponObservation(val unitId: String, val objectId: Long, val randomCounter: Int,
    val offset: Position, val targetUnitId: String?, val angularVelocity: Double,
    val pivot: Position? = null, val muzzle: Position? = null, val cooldown: Double = 0.0,
    val oddShot: Boolean = false, val idle: WeaponIdleObservation? = null)

data class WeaponIdleObservation(val baseHeading: Double, val timer: Float, val offset: Double,
    val direction: Int, val reached: Boolean, val hold: Float)

data class Event(val kind: String, val unitId: String? = null)

/** Trusted-host birth state; never part of the player projection. */
data class ProducedUnitObservation(val unitId: String, val objectId: Long, val updateKey: String,
    val producerId: String, val birthRemaining: Float)

class ProductionObservation(val lastBirthSerial: Long, units: List<ProducedUnitObservation>) {
    val units: List<ProducedUnitObservation> = immutable(units)
}

class Observation(
    val sampleId: String,
    val worldId: String,
    val scenarioId: String,
    val stepIndex: Long,
    val simulationTime: Double,
    units: List<UnitObservation>,
    events: List<Event>,
    actionResults: List<ActionResult>,
    projectiles: List<ProjectileObservation> = emptyList(),
    val combat: CombatObservation? = null,
    projectileEvents: List<ProjectileEventObservation> = emptyList(),
    val economy: EconomyObservation? = null,
    val production: ProductionObservation? = null,
    val upgrades: UpgradeObservation? = null,
) {
    val units: List<UnitObservation> = immutable(units)
    val events: List<Event> = immutable(events)
    val actionResults: List<ActionResult> = immutable(actionResults)
    val projectiles: List<ProjectileObservation> = immutable(projectiles)
    val projectileEvents: List<ProjectileEventObservation> = immutable(projectileEvents)
}

class PlayerObservation internal constructor(
    val sampleId: String,
    val worldId: String,
    val scenarioId: String,
    val playerId: String,
    val stepIndex: Long,
    val simulationTime: Double,
    val vision: VisionSnapshot?,
    val knowledge: KnowledgeSnapshot?,
    units: List<UnitObservation>,
    events: List<Event>,
    actionResults: List<ActionResult>,
    projectiles: List<VisibleProjectile> = emptyList(),
    projectileEvents: List<VisibleProjectileEvent> = emptyList(),
    val balance: Long? = null,
    factoryQueues: List<FactoryQueueObservation> = emptyList(),
    upgradeQueues: List<UpgradeQueueObservation> = emptyList(),
) {
    val upgradeQueues: List<UpgradeQueueObservation> = immutable(upgradeQueues)
    val factoryQueues: List<FactoryQueueObservation> = immutable(factoryQueues)
    val units: List<UnitObservation> = immutable(units)
    val events: List<Event> = immutable(events)
    val actionResults: List<ActionResult> = immutable(actionResults)
    val projectiles: List<VisibleProjectile> = immutable(projectiles)
    val projectileEvents: List<VisibleProjectileEvent> = immutable(projectileEvents)
}

data class SimulationError(val code: String, val message: String)

sealed interface InitResult {
    data class Accepted(val context: SimulationContext) : InitResult
    data class Rejected(val error: SimulationError) : InitResult
}

sealed interface ActionResult {
    val actionId: String

    data class Accepted(
        override val actionId: String,
        private val rawEvents: List<Event> = emptyList(),
        val queuedForStep: Long? = null,
    ) : ActionResult {
        val events: List<Event> = immutable(rawEvents)
    }

    data class Rejected(
        override val actionId: String,
        val error: SimulationError,
    ) : ActionResult
}

sealed interface CloseResult {
    data object Closed : CloseResult
    data object AlreadyClosed : CloseResult
}

internal data class MutableUnit(
    val unitId: String,
    val ownerPlayerId: String,
    var typeId: String,
    var definition: UnitDefinition,
    var position: Position,
    var heading: Double,
    var speed: Double = 0.0,
    /** Retained steering-rate state; an idle unit does not integrate it into heading. */
    var angularVelocity: Double = 0.0,
    var moveTarget: Position? = null,
    var pathPoints: MutableList<Position> = mutableListOf(),
    var movementState: MovementState = MovementState.IDLE,
    var originMoveId: String? = null,
    var originAtStep: Long? = null,
    var lastPlannedKnowledgeRevision: Long = -1L,
    var lastPlannedBuildingRevision: Long = -1L,
    var nearTargetTime: Double = 0.0,
    var localMotion: LocalMotionSegment? = null,
    var localProgressTime: Double = 0.0,
    var terrainCorrectionTime: Double = 0.0,
    var terrainClearSteps: Int = 0,
    var health: Double? = definition.maximumHealth,
    var attackCommand: ManualAttackCommand? = null,
    var taskQueue: UnitTaskQueue = UnitTaskQueue(),
    /** Last activity's logical request, independent of propulsion and path success. */
    var movementRequested: Boolean = false,
    var lastAttackPathAttemptStep: Long? = null,
    var combat: MutableCombat? = definition.combat?.let { MutableCombat(heading) },
    val height: Double = 0.0,
    /** Host identity and deterministic traversal order, never player DTO fields. */
    val objectId: Long? = null,
    val updateKey: String = unitId,
    val producerId: String? = null,
    var birthRemaining: Float = 0f,
    var pointDefense: MutablePointDefense? = definition.pointDefense?.let { MutablePointDefense(it.initialEnergy) },
) {
    val alive: Boolean get() = health == null || checkNotNull(health) > 0.0
    val tasks: List<UnitTask> get() = taskQueue.tasks
    fun ensureLocalMotion(): LocalMotionSegment {
        val first = pathPoints.first()
        return localMotion?.takeIf { it.anchor == first }
            ?: LocalMotionSegment.create(position, first, definition.movement).also { localMotion = it; localProgressTime = 0.0 }
    }

    /** End the command while preserving linear/angular speed for the existing coasting rule. */
    fun cancelRoute() {
        moveTarget = null
        pathPoints.clear()
        clearMoveOrigin()
        movementState = if (speed != 0.0) MovementState.DECELERATING else MovementState.IDLE
    }

    /** Replanning and repair preserve command identity, completion time and terrain history. */
    fun adoptRoute(points: List<Position>, knowledgeRevision: Long, buildingRevision: Long = -1L) {
        pathPoints = points.toMutableList()
        localMotion = null
        localProgressTime = 0.0
        lastPlannedKnowledgeRevision = knowledgeRevision
        lastPlannedBuildingRevision = buildingRevision
        movementState = MovementState.MOVING
    }

    fun clearMoveOrigin() {
        originMoveId = null
        originAtStep = null
        lastPlannedKnowledgeRevision = -1L
        lastPlannedBuildingRevision = -1L
        nearTargetTime = 0.0
        localMotion = null
        localProgressTime = 0.0
    }
}

internal data class ManualAttackCommand(val targetUnitId: String, val actionId: String, val atStep: Long?)

internal data class MutableCombat(var turretHeading: Double, var angularVelocity: Double = 0.0,
    var cooldown: Double = 0.0, var targetId: String? = null, var offset: Position = Position(0.0, 0.0),
    var randomCounter: Int = 0, var idleBaseHeading: Double = turretHeading,
    var idleTimer: Float = 0f, var idleOffset: Double = 0.0, var idleDirection: Int = -1,
    var idleReached: Boolean = false, var idleHold: Float = 0f, var oddShot: Boolean = false,
    var patrolSearchTimer: Float = 0f, var patrolDispatchTimer: Float = 0f)

internal data class MutableProjectile(val objectId: Long, val sourceUnitId: String, val targetUnitId: String?,
    var position: Position, var heading: Double, var remainingLife: Double, val offset: Position,
    val scatter: Float, val speed: Double, val damage: Double, var height: Double = 0.0,
    var age: Float = 0f, var deflectionHealth: Float = 1f, var intercepted: Boolean = false,
    val instant: Boolean = false, val nonDeflectable: Boolean = false,
    val ground: GroundShot? = null, var verticalSpeed: Float = 0f, var groundDistanceSquared: Float? = null)

internal data class GroundShot(val x: Float, val y: Float, val height: Float, val profile: GroundProjectileProfile)

internal data class MutablePointDefense(var energy: Float, var exhausted: Boolean = false)

internal fun <T> immutable(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
