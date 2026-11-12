package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.util.Collections

class UnitDefinition(
    val id: String,
    val className: String,
    val radius: Double,
    val displayRadius: Double,
    val totalFrames: Int,
    val imagePath: Path,
    val imageScale: Double,
    val movement: MovementProfile,
    retainedUnconsumed: Set<String>,
    val fogOfWarSightRange: Int = 15,
    val combat: CombatProfile? = null,
    val durability: DurabilityProfile? = null,
    val building: BuildingProfile? = null,
    val production: ProductionProfile? = null,
    val build: BuildProfile? = null,
    val directHitTargetKind: DirectHitTargetKind = DirectHitTargetKind.ORDINARY,
    val pointDefense: PointDefenseProfile? = null,
    val upgrades: UpgradeProfile? = null,
    val income: IncomeProfile? = null,
    val builder: BuilderProfile? = null,
    val construction: ConstructionProfile? = null,
    val deathExplosion: DeathExplosionProfile? = null,
    val mech: MechDefinition? = null,
    val rotor: RotorDefinition? = null,
    val fixedUnderwaterTarget: FixedUnderwaterTargetProfile? = null,
    /** Explicit author surface capability; native hull qualification remains separate. */
    val authoredWaterCombat: Boolean = false,
    val shadowImagePath: Path? = null,
    val shield: ShieldProfile? = null,
    val shieldImagePath: Path? = null,
    val submarine: SubmarineProfile? = null,
    val amphibiousJet: AmphibiousJetProfile? = null,
    val transport: TransportProfile? = null,
    val transportSlots: Int = 1,
    val energy: EnergyProfile? = null,
    val customActions: CustomActionsProfile? = null,
    val afterburnTrailImagePath: Path? = null,
    val groundHeight: GroundHeightProfile? = null,
    val maintenance: MaintenanceProfile? = null,
    /** Null uses the ordinary terminal fraction; an explicit zero remains a zero price. */
    val reclaimPrice: Long? = null,
    /** Positive custom HP regeneration per simulation delta; separate from helping a target. */
    val selfRegenRate: Float = 0f,
    val techLevel: Int = 1,
    val nuclearSilo: NuclearSiloProfile? = null,
    val attachmentLayout: AttachmentLayout? = null,
    val attachmentModule: AttachmentModuleProfile? = null,
) {
    val retainedUnconsumed: Set<String> = Collections.unmodifiableSet(LinkedHashSet(retainedUnconsumed))
    val maximumHealth: Double? get() = durability?.maxHp ?: combat?.maxHp
}

enum class TransportProfile { NATIVE_HOVERCRAFT, NATIVE_DROPSHIP, NAUTILUS_SUBMERGED }

/** Native work capability, independent of the ordinary builder's construction menu. */
enum class MaintenanceProfile { NATIVE_REPAIR_BAY, MECH_ENGINEER, SCOUT_BOT }

/** Finite effective custom silos share their content and presentation slots. */
data class NuclearSiloProfile(
    val arm1ImagePath: Path,
    val arm2ImagePath: Path,
    val kind: NuclearSiloKind = NuclearSiloKind.LAUNCHER,
)

enum class NuclearSiloKind(
    val unitId: String,
    val maxHp: Double,
    val constructionPrice: Long,
    val placementFootprint: BuildingFootprint,
) {
    LAUNCHER("nukeLauncherC", 1500.0, 45000L, BuildingFootprint(-2, -1, 2, 2)),
    INTERCEPTOR("antiNukeLauncherC", 2800.0, 15000L, BuildingFootprint(-1, -1, 1, 1)),
}

data class MovementProfile(
    val type: MovementType,
    val moveSpeed: Double,
    val moveAccelerationSpeed: Double,
    val moveDecelerationSpeed: Double,
    val maxTurnSpeed: Double,
    val turnAcceleration: Double,
    val slidingMode: Boolean,
    val ignoringBody: Boolean,
    val reverseSpeedPercentage: Double = 0.0,
    val slidingDirection: Int = -1,
    val flight: FlightProfile? = null,
    val hover: HoverProfile? = null,
)

enum class MovementType { LAND, NONE, AIR, WATER, HOVER, OVER_CLIFF, OVER_CLIFF_WATER }

data class HoverProfile(val targetHeight: Double = 4.0, val heightDrift: Double = 1.5,
    val phaseSpeed: Double = 3.0, val heightChangeRate: Double = .1) {
    fun isValid(): Boolean = listOf(targetHeight, heightDrift, phaseSpeed, heightChangeRate).all { it.isFinite() && it >= 0.0 } &&
        targetHeight >= heightDrift && (targetHeight + heightDrift).isFinite()
}

data class FlightProfile(val targetHeight: Double, val heightDrift: Double,
    val heightChangeRate: Double, val startingHeightOffset: Double) {
    fun isValid(): Boolean = listOf(targetHeight, heightDrift, heightChangeRate, startingHeightOffset).all { it.isFinite() } &&
        targetHeight >= 0.0 && heightDrift >= 0.0 && startingHeightOffset >= 0.0 &&
        (targetHeight + heightDrift).isFinite() && (heightChangeRate == -1.0 || heightChangeRate >= 0.0)
}

/** The finite helicopter has one rotor at the body origin, drawn above its body. */
data class RotorDefinition(val imagePath: Path, val spinRate: Double)

/** Explicit finite evidence target; this grants neither submarine movement nor production. */
data class FixedUnderwaterTargetProfile(val height: Double = -8.0)

/** Explicit native capability; depth alone does not make an ordinary unit a submarine. */
enum class SubmarineProfile { NATIVE }

/** Native capability and the two folding side images; independent from rotor flight. */
data class AmphibiousJetProfile(val leftWingImagePath: Path, val rightWingImagePath: Path)

/** Trusted original target class; a building or an unarmed life profile does not imply native membership. */
enum class DirectHitTargetKind { ORDINARY, NATIVE_BUILDING }

data class DurabilityProfile(val maxHp: Double, val deadImagePath: Path?, val maxDamagePerHit: Double? = null,
    val armor: ArmorProfile? = null)

data class ArmorProfile(val reduction: Double, val minimumDamage: Double = 1.0) {
    internal fun isValid(): Boolean = reduction.isFinite() && reduction >= 0.0 &&
        minimumDamage.isFinite() && minimumDamage >= 0.0
}

/** Actual shield capacity and per-simulation-time regeneration, separate from hit display. */
data class ShieldProfile(val maximum: Float, val regeneration: Float, val startAtZero: Boolean = false) {
    fun isValid(): Boolean = maximum.isFinite() && maximum > 0f && regeneration.isFinite() && regeneration >= 0f
}

/** Weapon energy is separate from the point-defense module's different exhaustion rule. */
data class EnergyProfile(val maximum: Float, val regeneration: Float, val rechargingRegeneration: Float,
    val needsFullRecharge: Boolean, val dieOnZero: Boolean = false)

/** Finite custom ground body's physical height; it does not grant flight movement. */
data class GroundHeightProfile(val target: Float)

/** The qualified conversion/action subset; no executable text or arbitrary expression interpreter. */
enum class AutomaticActionCondition { ENERGY_EMPTY, OVER_WATER, NOT_OVER_WATER }
data class GroundLaunchProfile(val turretName: String, val price: Long, val range: Double,
    val movementType: MovementType, val payloadUnitTypeId: String)
data class AmmoProductionProfile(val price: Long, val queueLimit: Int)
data class CustomActionDefinition(val name: String, val actionId: String, val targetTypeId: String?,
    val rate: Float, val energySet: Float?, val cooldown: Int = 0,
    val canPlayerCancel: Boolean = false, val allowMultipleInQueue: Boolean = false,
    val automatic: AutomaticActionCondition? = null, val visible: Boolean = true,
    val cannotMove: Boolean = false, val rotateTo: Double? = null, val rotateToOrBackwards: Boolean = false,
    val highPriority: Boolean = false, val requiresOverWater: Boolean = false, val groundLaunch: GroundLaunchProfile? = null,
    val ammoProduction: AmmoProductionProfile? = null) {
    init {
        require(listOfNotNull(targetTypeId, groundLaunch, ammoProduction).size == 1)
        require(automatic == null || targetTypeId != null)
    }
}
class CustomActionsProfile(actions: List<CustomActionDefinition>, val automaticCooldown: Float = 60f) {
    val actions: List<CustomActionDefinition> = Collections.unmodifiableList(ArrayList(actions))
}

/** Qualified custom AA beam art and reuse; ordinary instant lightning keeps its own lifetime. */
data class BeamProfile(val image: Path, val startImage: Path, val endImage: Path)
data class BuildingFootprint(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class FootprintAnchor(val offsetX: Float, val offsetY: Float)
data class BuildingProfile(val footprint: BuildingFootprint, val backImagePath: Path? = null,
    val anchor: FootprintAnchor? = null, val placementFootprint: BuildingFootprint = footprint,
    val requiresResourcePool: Boolean = false,
    val foundationMovementType: MovementType? = null)
class BuilderProfile(buildingTypeIds: List<String>, val range: Float = 85f,
    val preparation: Float = 30f, val decay: Float = 1.3f, val repairRate: Float = .2f,
    val buildSpeedMultiplier: Float = 1f, val mainNanoTurret: String? = null) {
    val buildingTypeIds: List<String> = Collections.unmodifiableList(ArrayList(buildingTypeIds))
}
data class ConstructionProfile(val price: Long, val rate: Float)
enum class FactoryExitProfile { NATIVE_LAND, CUSTOM_MECH, NATIVE_AIR, NATIVE_SEA, CUSTOM_NAUTILUS, NATIVE_EXPERIMENTAL }

/** Author buildings declare their terrain class; native sea factories retain their existing water fallback. */
val UnitDefinition.foundationMovementType: MovementType
    get() = building?.foundationMovementType
        ?: if (production?.exitProfile == FactoryExitProfile.NATIVE_SEA) MovementType.WATER else movement.type

class ProductionProfile(unitTypeIds: List<String>, val exitProfile: FactoryExitProfile = FactoryExitProfile.NATIVE_LAND,
    val speedMultiplier: Float = 1f) {
    constructor(unitTypeId: String) : this(listOf(unitTypeId))
    val unitTypeIds: List<String> = Collections.unmodifiableList(ArrayList(unitTypeIds))
    val unitTypeId: String get() = unitTypeIds.first()
    override fun equals(other: Any?): Boolean = other is ProductionProfile && unitTypeIds == other.unitTypeIds && exitProfile == other.exitProfile && speedMultiplier == other.speedMultiplier
    override fun hashCode(): Int = 31 * (31 * unitTypeIds.hashCode() + exitProfile.hashCode()) + speedMultiplier.hashCode()
    override fun toString(): String = "ProductionProfile(unitTypeIds=$unitTypeIds, exitProfile=$exitProfile, speedMultiplier=$speedMultiplier)"
}
data class BuildProfile(val price: Long, val rate: Float)
data class IncomeProfile(val period: Float, val credits: Float)

data class DeathExplosionProfile(val damage: Float, val radius: Float, val expandTime: Float)

data class UpgradeActionProfile(val actionKey: String, val targetTypeId: String, val price: Long, val rate: Float)

class UpgradeProfile(actions: List<UpgradeActionProfile>) {
    val actions: List<UpgradeActionProfile> = Collections.unmodifiableList(ArrayList(actions))
}

/** Finite native level-one defense fixture; no ordinary offensive weapon. */
data class PointDefenseProfile(val range: Double = 160.0, val muzzleOffsetX: Double = 0.0,
    val muzzleOffsetY: Double = -13.0, val recharge: Float = .0004f, val cost: Float = .11f,
    val initialEnergy: Float = 1f)

/** Explicit ordinary combat content; absent on legacy movement-only variants. */
data class CombatProfile(
    val maxHp: Double,
    val attackRange: Double,
    val shootDelay: Double,
    val turretSize: Double,
    val turretTurnSpeed: Double,
    val turretTurnAcceleration: Double,
    val projectile: ProjectileProfile,
    val turretImagePath: Path?,
    val deadImagePath: Path?,
    val turretImageScale: Double,
    val mount: TurretMount = TurretMount(),
    val recoil: WeaponRecoil = WeaponRecoil(),
    val idleSweep: IdleSweepProfile? = null,
    val barrelX: Double = 0.0,
    val oddShotOffset: Double = 0.0,
    val fireSound: FireSoundProfile? = null,
    val namedWeapons: WeaponCatalog? = null,
    val multiTargeting: Boolean = false,
    val rotateWithBody: Boolean = false,
    val turretTurnDeceleration: Double? = null,
) {
    /** Bound to this actual immutable definition; replacements never reuse an old unit ID's layout. */
    val weaponAssembly: WeaponAssembly by lazy(LazyThreadSafetyMode.PUBLICATION) { WeaponAssembly.compile(this) }
}

/** Stable content references and declaration order are separate from runtime storage indices. */
class WeaponCatalog(weapons: List<NamedWeaponDefinition>) {
    val ordered: List<NamedWeaponDefinition> = Collections.unmodifiableList(ArrayList(weapons))
    val byName: Map<String, NamedWeaponDefinition> = Collections.unmodifiableMap(
        ordered.associateByTo(linkedMapOf()) { it.name })
    init {
        require(ordered.isNotEmpty()) { "A weapon catalog must not be empty" }
        require(byName.size == ordered.size) { "Weapon names must be unique" }
    }
    override fun equals(other: Any?): Boolean = other is WeaponCatalog && ordered == other.ordered
    override fun hashCode(): Int = ordered.hashCode()
}

data class NamedWeaponDefinition(val name: String, val projectileName: String,
    val projectile: ProjectileProfile, val mount: TurretMount,
    val shootDelay: Double, val turretSize: Double, val turnSpeed: Double, val turnAcceleration: Double,
    val imagePath: Path?, val imageScale: Double,
    val recoil: WeaponRecoil = WeaponRecoil(), val fireSound: FireSoundProfile? = null,
    val minimumRange: Double = 0.0, val maximumRange: Double? = null,
    val canAttackLandUnits: Boolean = true, val canAttackFlyingUnits: Boolean = false,
    val canAttackUnderwaterUnits: Boolean = false,
    val warmup: Double = 0.0, val warmupCallDownRate: Double = 4.0,
    val warmupNoReset: Boolean = false, val warmupShootDelayTransfer: Double = 0.0,
    val canShoot: Boolean = true, val aimMaxAngle: Double = 5.0,
    val attachedTo: String? = null, val slave: Boolean = false,
    val linkDelayWithTurret: String? = null, val limitingAngle: Double? = null,
    val idleDirection: Double = 0.0,
    val barrelX: Double? = null, val oddShotOffset: Double? = null,
    val invisible: Boolean = false, val imageOffsetX: Double = 0.0, val imageOffsetY: Double = 0.0,
    val idleDirectionReversing: Double? = null, val turnDeceleration: Double? = null,
    val energyUsage: Float = 0f, val idleSweep: IdleSweepProfile? = null, val resetTurret: Boolean = true,
    val interception: ProjectileInterceptionProfile? = null)

data class ProjectileInterceptionProfile(val landingRange: Float, val trackingRange: Float, val minimumHeight: Float)

/** Gameplay trigger identity, never an audio filename or resource-pack decision. */
enum class FireSoundProfile(val eventId: String, val volume: Float, val pitch: Float = 1f) {
    TANK("reforge:events/tank-fire", .3f),
    HOVER("reforge:events/hover-fire", .3f, 1.3f),
    NAVAL_MAIN("reforge:events/naval-main-fire", .3f),
    NAVAL_MISSILE("reforge:events/naval-missile-fire", .1f),
    SCOUT_PLASMA("reforge:events/scout-plasma-fire", .05f),
    SPIDER_GUN("reforge:events/spider-gun-fire", .3f),
    SPIDER_GUN_T2("reforge:events/spider-gun-fire", .15f),
    SPIDER_AA_MISSILE("reforge:events/naval-missile-fire", .3f),
    SUBMARINE_MISSILE("reforge:events/naval-missile-fire", .8f),
    ENGINEER_MISSILE("reforge:events/naval-missile-fire", .2f),
    AMPHIBIOUS_LIGHTNING("reforge:events/amphibious-lightning-fire", .2f),
    SPIDER_LIGHTNING("reforge:events/spider-lightning-fire", .3f),
}

data class TurretMount(val localX: Double = 0.0, val localY: Double = 0.0)
data class WeaponRecoil(val offset: Double = -2.0, val outTime: Double = 4.0, val returnTime: Double = 7.0)
data class IdleSweepProfile(val angle: Double, val delay: Float, val speedFactor: Float,
    val randomDelayExclusive: Int, val onlyWhenStationary: Boolean = false)

data class ProjectileProfile(val directDamage: Double, val life: Double, val speed: Double,
    val instant: Boolean = false, val nonDeflectable: Boolean = false, val deflectionHealth: Float = 1f,
    val ground: GroundProjectileProfile? = null, val missile: NativeMissileProfile? = null,
    val acceleration: LinearProjectileAcceleration? = null,
    val splash: OrdinarySplashProfile? = null, val retargetOnDeath: Boolean = false,
    val ballistic: CustomBallisticProfile? = null, val lightning: Boolean = false,
    val damageModifiers: ProjectileDamageModifiers = ProjectileDamageModifiers(),
    val beam: BeamProfile? = null)

/** Shield expenditure and damage deflection are separate native projectile properties. */
data class ProjectileDamageModifiers(val shieldDamage: Double = 1.0, val shieldDeflection: Double = 1.0,
    val building: Double = 1.0)

/** A verticalSpeed of -1 uses the current custom projectile speed. */
data class CustomBallisticProfile(val delayMoveHeight: Float, val apex: Float, val verticalSpeed: Float = -1f)

/** Ordinary homing impact area; separate from a fixed ground-snapshot artillery shot. */
data class OrdinarySplashProfile(val damage: Float, val radius: Float)

data class LinearProjectileAcceleration(val targetSpeed: Double, val acceleration: Double)

data class NativeMissileProfile(val targetSpeed: Float = 5f, val acceleration: Float = .1f,
    val climbThreshold: Float = 40f, val apex: Float = 60f, val verticalSpeed: Float = 2f,
    val retargetRadius: Float = 120f, val retargetForward: Float = 15f)

/** Finite first artillery branch; these values describe a fixed ground snapshot shot. */
data class GroundProjectileProfile(val areaDamage: Float, val areaRadius: Float, val spread: Float,
    val initialVerticalSpeed: Float, val gravity: Float, val trueGravity: Float = 0f,
    val speedSpread: Float = 0f, val resolveAtSnapshot: Boolean = false)
