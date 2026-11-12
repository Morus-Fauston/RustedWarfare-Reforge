package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.GroundProjectileProfile
import com.rustedwarfare.reforge.core.content.NativeMissileProfile
import com.rustedwarfare.reforge.core.content.CustomBallisticProfile
import com.rustedwarfare.reforge.core.content.DeathExplosionProfile
import com.rustedwarfare.reforge.core.content.ScoutPayloadStage
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
    val maintenanceContract: MaintenanceContract? = null,
    val nukesEnabled: Boolean = true,
)

data class EconomyContract(val initialBalances: Map<String, Long>)

/** Explicit finite maintenance capability; caller-owned maps cannot change a live world's prices. */
class MaintenanceContract(basePrices: Map<String, Long>) {
    val basePrices: Map<String, Long> = Collections.unmodifiableMap(LinkedHashMap(basePrices))
}

/** Trusted host queue state. Nonces and payment details never enter player queue DTOs. */
data class QueuedProductionObservation(val typeId: String, val unitNonce: String,
    val progress: Float, val cost: Long, val rate: Float)

class EconomyObservation(balances: Map<String, Long>,
    queuedProduction: Map<String, List<QueuedProductionObservation>> = emptyMap(),
    preciseBalances: Map<String, Double>? = null) {
    val preciseBalances: Map<String, Double>? = preciseBalances?.let {
        Collections.unmodifiableMap(LinkedHashMap(it)) }
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

enum class UnitTaskKind { MOVE, ATTACK, PATROL, BUILD, CONSTRUCT, RECLAIM, LOAD_INTO, LOAD_UP }

/** Owner control intent; temporary combat targets never become stored patrol points. */
data class UnitTask(val kind: UnitTaskKind, val actionId: String, val target: Position? = null,
    val targetUnitId: String? = null, val atStep: Long? = null,
    val buildingTypeId: String? = null, val buildingNonce: String? = null,
    val automaticRepair: Boolean = false, val extraWorkRange: Float = 0f)

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
    val constructionProgress: Float = 1f,
    val constructionTargetId: String? = null,
    val mechPose: MechPose? = null,
    val flightPose: FlightPose? = null,
    val waterPose: WaterPose? = null,
    val hoverPose: HoverPose? = null,
    val shield: ShieldObservation? = null,
    val submarine: SubmarineObservation? = null,
    val amphibiousJet: AmphibiousJetObservation? = null,
    /** Trusted-host relation. Carried bodies are excluded from player world observations. */
    val carriedBy: String? = null,
    val transport: TransportObservation? = null,
    val transportExit: TransportExitObservation? = null,
    val energy: EnergyObservation? = null,
    val unitActions: UnitActionsObservation? = null,
    val nuclearDoors: Float? = null,
)

data class EnergyObservation(val current: Float, val maximum: Float, val recharging: Boolean)
data class UnitActionObservation(val name: String, val queueIndex: Int?, val progress: Float?,
    val cooldownRemaining: Int, val available: Boolean)
data class QueuedUnitActionObservation(val name: String, val progress: Float, val target: Position?)
data class UnitActionsObservation(val actions: List<UnitActionObservation>, val automaticCooldown: Float,
    val stockpile: Int? = null, val queue: List<QueuedUnitActionObservation> = emptyList())

data class TransportObservation(val passengerIds: List<String>?, val passengerCount: Int,
    val occupiedSlots: Int, val capacity: Int, val unloading: Boolean, val unloadRemaining: Float?,
    val canUnload: Boolean = false)

data class TransportExitObservation(val previousCarrierId: String, val remaining: Float)

/** Actual visible form; future flight intent and the integrator are owner-only. */
data class AmphibiousJetObservation(val flyingIntent: Boolean?, val verticalSpeed: Float?,
    val underwater: Boolean, val surfacePresentation: Boolean,
    val movementType: com.rustedwarfare.reforge.core.content.MovementType,
    val maximumSpeed: Double, val attackRange: Double, val wingFold: Float,
    val phase: Float, val sinkSpeed: Float, val settled: Boolean, val canDive: Boolean? = null)

/** Actual public depth state; control intent and vertical integrator are owner-only. */
data class SubmarineObservation(val surfaceIntent: Boolean?, val verticalSpeed: Float?, val underwater: Boolean,
    val surfacePresentation: Boolean, val maximumSpeed: Double, val attackRange: Double)

/** Visible durability state; no source identity or hidden combat scheduling. */
data class ShieldObservation(val current: Float, val maximum: Float, val displayDamage: Float = 0f)

/** Current visible hover state; its height does not grant the AIR target category. */
data class HoverPose(val velocity: Position, val heightPhase: Double)

/** Visible ordinary water-body state; it has neither legs nor host identities. */
data class WaterPose(val reversing: Boolean, val sinkSpeed: Float, val settled: Boolean)

/** Current visible physical flight state, without host identities or future commands. */
data class FlightPose(val velocity: Position, val height: Double, val heightPhase: Double,
    val rotorPhase: Double, val rotorHeading: Double, val rotorHeightOffset: Double,
    val fall: FlightFallPose? = null)

data class FlightFallPose(val speed: Float, val landed: Boolean, val landedInWater: Boolean, val settled: Boolean)

/** Host-only identity/target and arithmetic state. Never copied into player DTOs. */
data class ProjectileObservation(val objectId: Long, val sourceUnitId: String, val targetUnitId: String?,
    val position: Position, val heading: Double, val remainingLife: Double, val offset: Position, val scatter: Float,
    val height: Double = 0.0, val age: Float = 0f, val deflectionHealth: Float = 1f,
    val intercepted: Boolean = false, val ground: GroundProjectileObservation? = null,
    val missile: MissileObservation? = null, val areaExpansion: AreaExpansionObservation? = null,
    val sourceWeaponName: String? = null, val ballistic: BallisticObservation? = null,
    val appearance: ProjectileAppearance = ProjectileAppearance.STANDARD, val lightning: LightningSegment? = null,
    val beam: BeamSegment? = null, val alwaysVisibleInFog: Boolean = false)

/** Captured endpoint of a visible instant discharge; no host object or hidden future target. */
data class LightningSegment(val end: Position, val endHeight: Double)

/** Current visible beam endpoint, captured without a host target identity. */
data class BeamSegment(val end: Position, val endHeight: Double, val textureOffset: Float = 0f)

/** Public launch appearance, independent of hidden source identity or its subsequent depth. */
enum class ProjectileAppearance(val red: Int, val green: Int, val blue: Int, val drawSize: Float) {
    STANDARD(255, 255, 0, 2f), SUBMARINE_MISSILE(230, 230, 50, 2f), SUBMARINE_TORPEDO(30, 30, 150, 1f),
    AMPHIBIOUS_LIGHTNING(247, 212, 129, 2f), AA_BEAM(255, 255, 255, 2f), NUCLEAR(255, 238, 160, 3f),
    INTERCEPTOR(238, 238, 0, 2f), SCOUT_PAYLOAD(238, 238, 0, .9f),
    BOMBER_BOMB(0, 0, 0, 1f), BOMBER_DEPTH_CHARGE(0, 0, 0, 1f), MECH_FLAME(255, 165, 69, .3f)
}

class AreaExpansionObservation(val remaining: Float, val radius: Float, hitUnitIds: Set<String>) {
    val hitUnitIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(hitUnitIds))
}

data class MissileObservation(val speed: Float, val reachedApex: Boolean, val rawAz: Float)

data class BallisticObservation(val speed: Float, val reachedApex: Boolean, val rawAz: Float)

data class GroundProjectileObservation(val targetPosition: Position, val targetHeight: Float, val verticalSpeed: Float,
    val distanceSquared: Float? = null)

data class PointDefenseObservation(val energy: Float, val exhausted: Boolean)

/** One public appearance class. Identical newly visible objects have one token and multiplicity. */
data class VisibleProjectile(val token: String, val multiplicity: Int, val position: Position, val heading: Double,
    val height: Double = 0.0, val parentToken: String? = null, val areaRadius: Float? = null,
    val appearance: ProjectileAppearance = ProjectileAppearance.STANDARD, val lightning: LightningSegment? = null,
    val beam: BeamSegment? = null)

data class ProjectileEventObservation(val kind: String, val projectile: ProjectileObservation, val damage: Double? = null,
    val interceptorUnitId: String? = null)

data class VisibleProjectileEvent(val token: String, val kind: String, val occurrences: Int,
    val position: Position, val heading: Double, val height: Double, val sourceUnitId: String?,
    val targetUnitId: String?, val damage: Double?, val parentToken: String? = null,
    val interceptorUnitId: String? = null, val areaRadius: Float? = null,
    val sourceWeaponName: String? = null, val appearance: ProjectileAppearance = ProjectileAppearance.STANDARD,
    val lightning: LightningSegment? = null, val beam: BeamSegment? = null)

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
    val oddShot: Boolean = false, val idle: WeaponIdleObservation? = null,
    val weaponName: String? = null, val warmup: Double = 0.0, val turretHeading: Double? = null)

data class WeaponIdleObservation(val baseHeading: Double, val timer: Float, val offset: Double,
    val direction: Int, val reached: Boolean, val hold: Float)

data class Event(val kind: String, val unitId: String? = null)

/** Trusted-host birth state; never part of the player projection. */
data class ProducedUnitObservation(val unitId: String, val objectId: Long, val updateKey: String,
    val producerId: String, val birthRemaining: Float)

class ProductionObservation(val lastBirthSerial: Long, units: List<ProducedUnitObservation>) {
    val units: List<ProducedUnitObservation> = immutable(units)
}

data class ConstructedBuildingObservation(val unitId: String, val objectId: Long, val updateKey: String, val progress: Float)

/** Trusted host income phase; never included in player observations. */
data class IncomeStateObservation(val unitId: String, val elapsed: Float)
data class ConstructionWorkerObservation(val unitId: String, val elapsed: Float, val retryTime: Int,
    val preparation: Float, val working: Boolean, val previousTargetActive: Boolean,
    val singleNodePathCount: Byte = 0, val logicalRequestX: Float? = null, val logicalRequestY: Float? = null,
    val movementRequested: Boolean = false, val pendingPathNodeCounts: List<Int> = emptyList(),
    val consumedPathNodeCounts: List<Int> = emptyList(), val qBeforeDispatch: Byte = 0)
/** Trusted host diagnostics, excluded from player observations. */
class ConstructionObservation(val clock: Int, buildings: List<ConstructedBuildingObservation>,
                              workers: List<ConstructionWorkerObservation>, nonces: Map<String, Set<String>>) {
    val buildings: List<ConstructedBuildingObservation> = immutable(buildings)
    val workers: List<ConstructionWorkerObservation> = immutable(workers)
    val nonces: Map<String, Set<String>> = Collections.unmodifiableMap(nonces.mapValues {
        Collections.unmodifiableSet(LinkedHashSet(it.value)) })
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
    val construction: ConstructionObservation? = null,
    incomeStates: List<IncomeStateObservation> = emptyList(),
    reclaimedUnitIds: List<String>? = null,
    soundTriggers: List<SoundTriggerObservation> = emptyList(),
) {
    /** Trusted host only; player audio receives authorized parameters, not this list. */
    val soundTriggers: List<SoundTriggerObservation> = immutable(soundTriggers)
    val reclaimedUnitIds: List<String>? = reclaimedUnitIds?.let { immutable(it) }
    val incomeStates: List<IncomeStateObservation> = immutable(incomeStates)
    val units: List<UnitObservation> = immutable(units)
    val events: List<Event> = immutable(events)
    val actionResults: List<ActionResult> = immutable(actionResults)
    val projectiles: List<ProjectileObservation> = immutable(projectiles)
    val projectileEvents: List<ProjectileEventObservation> = immutable(projectileEvents)
}

data class VisibleUnitWeapon(val unitId: String, val weaponName: String, val turretHeading: Double,
    val pivot: Position, val muzzle: Position, val cooldown: Double?,
    val warmup: Double? = null, val targetUnitId: String? = null)

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
    val credits: Double? = null,
    weapons: List<VisibleUnitWeapon> = emptyList(),
) {
    val weapons: List<VisibleUnitWeapon> = immutable(weapons)
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

/** One immutable origin shared by candidate copies; never a player DTO. */
internal data class ProducedOrigin(val producerId: String, val factoryTypeId: String, val unitTypeId: String)
internal data class ScoutOrigin(val sourceUnitId: String, val projectileId: Long)

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
    var shield: Float = definition.shield?.takeUnless { it.startAtZero }?.maximum ?: 0f,
    var shieldDisplayDamage: Float = 0f,
    var attackCommand: ManualAttackCommand? = null,
    var taskQueue: UnitTaskQueue = UnitTaskQueue(),
    /** Last activity's logical request, independent of propulsion and path success. */
    var movementRequested: Boolean = false,
    var lastAttackPathAttemptStep: Long? = null,
    var combat: MutableCombat? = definition.combat?.let { MutableCombat(heading) },
    var height: Double = definition.movement.flight?.startingHeightOffset ?: 0.0,
    var submarineSurfaceIntent: Boolean = false,
    var submarineVerticalSpeed: Float = 0f,
    var submarineSurfacePresentation: Boolean = true,
    var amphibiousState: AmphibiousJetState? = if (definition.amphibiousJet != null) AmphibiousJetState() else null,
    var heightPhase: Double = 0.0,
    var flightIdleTime: Float = 0f,
    var bomberApproach: BomberApproach? = null,
    var flightVelocity: Position = Position(0.0, 0.0),
    var rotorPhase: Double = 0.0,
    var rotorHeightOffset: Double = if (definition.rotor == null) 0.0 else .1,
    /** Host identity and deterministic traversal order, never player DTO fields. */
    val objectId: Long? = null,
    val updateKey: String = unitId,
    val productionOrigin: ProducedOrigin? = null,
    val scoutOrigin: ScoutOrigin? = null,
    var birthRemaining: Float = 0f,
    var customActionState: CustomActionState = CustomActionState(),
    var ordnance: OrdnanceState = OrdnanceState(),
    var incomeElapsed: Float = 0f,
    var pointDefense: MutablePointDefense? = definition.pointDefense?.let { MutablePointDefense(it.initialEnergy) },
    var constructionProgress: Float = 1f,
    var constructionElapsed: Float = 0f,
    var constructionRetryTime: Int = -9999,
    var constructionPreparation: Float = 0f,
    var constructionWorking: Boolean = false,
    var previousWorkTargetActive: Boolean = false,
    /** Native r.d is independent of the work preparation and Stop fields. */
    var repairScanElapsed: Float = 0f,
    var reclaimed: Boolean = false,
    var singleNodePathCount: Byte = 0,
    var logicalRequestX: Float? = null,
    var logicalRequestY: Float? = null,
    var pendingPathNodeCounts: List<Int> = emptyList(),
    var consumedPathNodeCounts: List<Int> = emptyList(),
    var qBeforeDispatch: Byte = 0,
    var namedWeapons: MutableMap<String, MutableCombat>? = NamedWeaponRuntime.initialStates(definition.combat, heading, combat),
    var sharedWeaponTargetId: String? = null,
    var mechState: MechState? = MechMotion.initial(definition, position, heading),
    var bodyReversing: Boolean = false,
    var sinkSpeed: Float = 0f,
    var waterSettled: Boolean = false,
    var flightLanded: Boolean = false,
    var flightLandedInWater: Boolean = false,
    var productionSide: Boolean = false,
    /** Custom instance j; retained when the normal locomotion-aim gate is closed. */
    var terrainSpeedMultiplier: Double = 1.0,
    var carriedBy: String? = null,
    var transportState: TransportState? = if (definition.transport != null) TransportState() else null,
    var transportExit: TransportExitObservation? = null,
    /** Native placement tests a terrain cell; retain its legal center until the body reenters the ordinary map inset. */
    var nativeTransportPlacement: Boolean = false,
    /** Native cj writes health first; the subsequent base health update dispatches death. */
    var deathPending: Boolean = false,
    var energy: Float = definition.energy?.maximum ?: 0f,
    var energyRecharging: Boolean = false,
) {
    val producerId: String? get() = productionOrigin?.producerId
    val reversing: Boolean get() = mechState?.reversing ?: bodyReversing
    val alive: Boolean get() = deathPending || health == null || checkNotNull(health) > 0.0
    val tasks: List<UnitTask> get() = taskQueue.tasks
    var primaryCombatTargetId: String?
        get() = if (namedWeapons != null) sharedWeaponTargetId else combat?.targetId
        set(value) {
            if (namedWeapons != null) sharedWeaponTargetId = value else combat?.targetId = value
        }
    fun clearWeaponTargets() {
        sharedWeaponTargetId = null
        for (state in namedWeapons?.values ?: listOfNotNull(combat)) {
            state.targetId = null
            state.aimReady = false
        }
    }
    fun ensureLocalMotion(): LocalMotionSegment {
        val first = pathPoints.first()
        return localMotion?.takeIf { it.anchor == first }
            ?: LocalMotionSegment.create(position, first, UnitLocomotion.movement(this)).also { localMotion = it; localProgressTime = 0.0 }
    }

    /** End the command while preserving linear/angular speed for the existing coasting rule. */
    fun cancelRoute() {
        pauseRoute()
        singleNodePathCount = 0
        logicalRequestX = null
        logicalRequestY = null
        pendingPathNodeCounts = emptyList()
        consumedPathNodeCounts = emptyList()
    }

    /** No usable physical route; a still-active logical approach may remain. */
    fun pauseRoute() {
        moveTarget = null
        pathPoints.clear()
        clearMoveOrigin()
        movementState = if (speed != 0.0) MovementState.DECELERATING else MovementState.IDLE
    }

    /** Replanning and repair preserve command identity, completion time and terrain history. */
    fun adoptRoute(points: List<Position>, logicalTarget: Position, knowledgeRevision: Long, buildingRevision: Long = -1L) {
        recordLogicalTarget(logicalTarget)
        pendingPathNodeCounts = immutable(pendingPathNodeCounts + points.size)
        pathPoints = points.toMutableList()
        localMotion = null
        localProgressTime = 0.0
        lastPlannedKnowledgeRevision = knowledgeRevision
        lastPlannedBuildingRevision = buildingRevision
        movementState = MovementState.MOVING
    }

    fun requestMovement(target: Position) {
        movementRequested = true
        recordLogicalTarget(target)
    }

    private fun recordLogicalTarget(target: Position) {
        val x = target.x.toFloat(); val y = target.y.toFloat()
        require(x.isFinite() && y.isFinite()) { "Logical movement target must remain finite" }
        val oldX = logicalRequestX; val oldY = logicalRequestY
        if (oldX == null || oldY == null || oldX.toFloat() != x || oldY.toFloat() != y) {
            singleNodePathCount = 0
            pendingPathNodeCounts = emptyList()
        }
        logicalRequestX = x; logicalRequestY = y
    }

    /** Count each real acceptance only after this worker's activity dispatch. */
    fun finishMovementRequest() {
        if (!movementRequested) {
            singleNodePathCount = 0
            logicalRequestX = null; logicalRequestY = null
            pendingPathNodeCounts = emptyList(); consumedPathNodeCounts = emptyList()
            return
        }
        val consumed = pendingPathNodeCounts
        for (count in consumed) if (count == 1) singleNodePathCount = (singleNodePathCount + 1).toByte()
        pendingPathNodeCounts = emptyList()
        consumedPathNodeCounts = consumed
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

internal data class BomberApproach(val angle: Float, val breakingAway: Boolean)

internal data class MutableCombat(var turretHeading: Double, var angularVelocity: Double = 0.0,
    var cooldown: Double = 0.0, var targetId: String? = null, var offset: Position = Position(0.0, 0.0),
    var randomCounter: Int = 0, var idleBaseHeading: Double = turretHeading,
    var idleTimer: Float = 0f, var idleOffset: Double = 0.0, var idleDirection: Int = -1,
    var idleReached: Boolean = false, var idleHold: Float = 0f, var oddShot: Boolean = false,
    var patrolSearchTimer: Float = 0f, var patrolDispatchTimer: Float = 0f,
    var warmup: Double = 0.0, var aimReady: Boolean = false)

internal data class MutableProjectile(val objectId: Long, val sourceUnitId: String, var targetUnitId: String?,
    var position: Position, var heading: Double, var remainingLife: Double, var offset: Position,
    val scatter: Float, var speed: Double, val damage: Double, var height: Double = 0.0,
    var age: Float = 0f, var deflectionHealth: Float = 1f, var intercepted: Boolean = false,
    val instant: Boolean = false, val nonDeflectable: Boolean = false,
    val ground: GroundShot? = null, var verticalSpeed: Float = 0f, var groundDistanceSquared: Float? = null,
    val missile: NativeMissileProfile? = null, var missileReachedApex: Boolean = false, var missileRawAz: Float = 0f,
    var expansion: AreaExpansion? = null, val sourceWeaponName: String? = null,
    val nuclearTarget: Position? = null,
    val acceleration: com.rustedwarfare.reforge.core.content.LinearProjectileAcceleration? = null,
    val splash: com.rustedwarfare.reforge.core.content.OrdinarySplashProfile? = null,
    val retargetOnDeath: Boolean = false,
    val ballistic: CustomBallisticProfile? = null, var ballisticReachedApex: Boolean = false, var ballisticRawAz: Float = 0f,
    val appearance: ProjectileAppearance = ProjectileAppearance.STANDARD,
    val lightning: Boolean = false, var hitApplied: Boolean = false, val lightningSegment: LightningSegment? = null,
    val damageModifiers: com.rustedwarfare.reforge.core.content.ProjectileDamageModifiers =
        com.rustedwarfare.reforge.core.content.ProjectileDamageModifiers(),
    val beam: Boolean = false, var beamSegment: BeamSegment? = null,
    val alwaysVisibleInFog: Boolean = false, var revealFogPending: Boolean = nuclearTarget != null,
    val nukeTagged: Boolean = false, var interception: InterceptionCourse? = null,
    val scoutPayload: ScoutPayload? = null)

internal data class ScoutPayload(val target: Position, val stage: ScoutPayloadStage)

internal data class AreaExpansion(val profile: DeathExplosionProfile, var remaining: Float,
    var radius: Float = 0f, val hitUnitIds: MutableSet<String> = linkedSetOf())

internal data class GroundShot(val x: Float, val y: Float, val height: Float, val profile: GroundProjectileProfile)

internal data class MutablePointDefense(var energy: Float, var exhausted: Boolean = false)

internal fun <T> immutable(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
