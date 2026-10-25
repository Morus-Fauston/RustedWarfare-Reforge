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
)

enum class MovementType { LAND, NONE }

/** Trusted original target class; a building or an unarmed life profile does not imply native membership. */
enum class DirectHitTargetKind { ORDINARY, NATIVE_BUILDING }

data class DurabilityProfile(val maxHp: Double, val deadImagePath: Path)
data class BuildingFootprint(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class FootprintAnchor(val offsetX: Float, val offsetY: Float)
data class BuildingProfile(val footprint: BuildingFootprint, val backImagePath: Path? = null,
    val anchor: FootprintAnchor? = null)
data class ProductionProfile(val unitTypeId: String)
data class BuildProfile(val price: Long, val rate: Float)

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
    val turretImagePath: Path,
    val deadImagePath: Path,
    val turretImageScale: Double,
    val mount: TurretMount = TurretMount(),
    val recoil: WeaponRecoil = WeaponRecoil(),
    val idleSweep: IdleSweepProfile? = null,
    val barrelX: Double = 0.0,
    val oddShotOffset: Double = 0.0,
)

data class TurretMount(val localX: Double = 0.0, val localY: Double = 0.0)
data class WeaponRecoil(val offset: Double = -2.0, val outTime: Double = 4.0, val returnTime: Double = 7.0)
data class IdleSweepProfile(val angle: Double, val delay: Float, val speedFactor: Float,
    val randomDelayExclusive: Int)

data class ProjectileProfile(val directDamage: Double, val life: Double, val speed: Double,
    val instant: Boolean = false, val nonDeflectable: Boolean = false, val deflectionHealth: Float = 1f,
    val ground: GroundProjectileProfile? = null)

/** Finite first artillery branch; these values describe a fixed ground snapshot shot. */
data class GroundProjectileProfile(val areaDamage: Float, val areaRadius: Float, val spread: Float,
    val initialVerticalSpeed: Float, val gravity: Float, val trueGravity: Float = 0f)
