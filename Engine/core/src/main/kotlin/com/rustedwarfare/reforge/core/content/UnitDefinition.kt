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
) {
    val retainedUnconsumed: Set<String> = Collections.unmodifiableSet(LinkedHashSet(retainedUnconsumed))
    val maximumHealth: Double? get() = durability?.maxHp ?: combat?.maxHp
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
)

enum class MovementType { LAND, NONE }

/** Trusted original target class; a building or an unarmed life profile does not imply native membership. */
enum class DirectHitTargetKind { ORDINARY, NATIVE_BUILDING }

data class DurabilityProfile(val maxHp: Double, val deadImagePath: Path?, val maxDamagePerHit: Double? = null)
data class BuildingFootprint(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class FootprintAnchor(val offsetX: Float, val offsetY: Float)
data class BuildingProfile(val footprint: BuildingFootprint, val backImagePath: Path? = null,
    val anchor: FootprintAnchor? = null, val placementFootprint: BuildingFootprint = footprint,
    val requiresResourcePool: Boolean = false)
class BuilderProfile(buildingTypeIds: List<String>) {
    val buildingTypeIds: List<String> = Collections.unmodifiableList(ArrayList(buildingTypeIds))
    val range: Float = 85f
    val preparation: Float = 30f
    val decay: Float = 1.3f
    val repairRate: Float = .2f
}
data class ConstructionProfile(val price: Long, val rate: Float)
enum class FactoryExitProfile { NATIVE_LAND, CUSTOM_MECH }

class ProductionProfile(unitTypeIds: List<String>, val exitProfile: FactoryExitProfile = FactoryExitProfile.NATIVE_LAND) {
    constructor(unitTypeId: String) : this(listOf(unitTypeId))
    val unitTypeIds: List<String> = Collections.unmodifiableList(ArrayList(unitTypeIds))
    val unitTypeId: String get() = unitTypeIds.first()
    override fun equals(other: Any?): Boolean = other is ProductionProfile && unitTypeIds == other.unitTypeIds && exitProfile == other.exitProfile
    override fun hashCode(): Int = 31 * unitTypeIds.hashCode() + exitProfile.hashCode()
    override fun toString(): String = "ProductionProfile(unitTypeIds=$unitTypeIds, exitProfile=$exitProfile)"
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
)

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
    val barrelX: Double? = null, val oddShotOffset: Double? = null)

/** Gameplay trigger identity, never an audio filename or resource-pack decision. */
enum class FireSoundProfile(val eventId: String, val volume: Float) {
    TANK("reforge:events/tank-fire", .3f),
}

data class TurretMount(val localX: Double = 0.0, val localY: Double = 0.0)
data class WeaponRecoil(val offset: Double = -2.0, val outTime: Double = 4.0, val returnTime: Double = 7.0)
data class IdleSweepProfile(val angle: Double, val delay: Float, val speedFactor: Float,
    val randomDelayExclusive: Int)

data class ProjectileProfile(val directDamage: Double, val life: Double, val speed: Double,
    val instant: Boolean = false, val nonDeflectable: Boolean = false, val deflectionHealth: Float = 1f,
    val ground: GroundProjectileProfile? = null, val missile: NativeMissileProfile? = null,
    val acceleration: LinearProjectileAcceleration? = null)

data class LinearProjectileAcceleration(val targetSpeed: Double, val acceleration: Double)

data class NativeMissileProfile(val targetSpeed: Float = 5f, val acceleration: Float = .1f,
    val climbThreshold: Float = 40f, val apex: Float = 60f, val verticalSpeed: Float = 2f,
    val retargetRadius: Float = 120f, val retargetForward: Float = 15f)

/** Finite first artillery branch; these values describe a fixed ground snapshot shot. */
data class GroundProjectileProfile(val areaDamage: Float, val areaRadius: Float, val spread: Float,
    val initialVerticalSpeed: Float, val gravity: Float, val trueGravity: Float = 0f)
