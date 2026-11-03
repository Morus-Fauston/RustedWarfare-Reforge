package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.NamedWeaponDefinition
import com.rustedwarfare.reforge.core.content.CombatProfile
import com.rustedwarfare.reforge.core.content.MovementType

/** Per-weapon geometry reads the real named state without switching the unit's definition. */
internal object NamedWeaponRuntime {
    // cg.ab constructor default in the fixed original turret evidence.
    private const val DEFAULT_LIMITING_RANGE = 99999.0
    fun usesNamedState(combat: CombatProfile): Boolean = combat.namedWeapons?.let { catalog ->
        catalog.ordered.size > 1 || catalog.ordered.any { weapon ->
            weapon.minimumRange > 0.0 || weapon.maximumRange?.let { it < combat.attackRange } == true ||
                !weapon.canAttackLandUnits || weapon.warmup != 0.0 || weapon.warmupNoReset ||
                weapon.warmupShootDelayTransfer != 0.0 || !weapon.canShoot || weapon.aimMaxAngle != 5.0 ||
                weapon.attachedTo != null || weapon.slave || weapon.linkDelayWithTurret != null ||
                weapon.limitingAngle?.let { it != -1.0 } == true || weapon.idleDirection != 0.0 ||
                weapon.barrelX != null || weapon.oddShotOffset != null
        }
    } == true
    fun qualifies(unit: MutableUnit, target: MutableUnit, weapon: NamedWeaponDefinition,
                  players: List<Player>, coordinates: CoordinatePolicy): Boolean {
        if (!CombatTargetQuery.isEnemy(unit, target, players)) return false
        val categoryAllowed = when (target.definition.movement.type) {
            MovementType.LAND, MovementType.NONE -> weapon.canAttackLandUnits
            MovementType.AIR -> if (CombatTargetQuery.isFlying(target)) weapon.canAttackFlyingUnits else weapon.canAttackLandUnits
        }
        if (!categoryAllowed) return false
        weapon.limitingAngle?.takeIf { it != -1.0 }?.let { limit ->
            val heading = weapon.attachedTo?.let { checkNotNull(unit.namedWeapons).getValue(it).turretHeading }
                ?: unit.heading
            val bearing = ScreenPlane.desired(unit.position, target.position)
            if (kotlin.math.abs(ScreenPlane.difference(bearing, heading + weapon.idleDirection)) > limit) return false
        }
        val globalRange = checkNotNull(unit.definition.combat).attackRange
        val maximum = minOf(globalRange, weapon.maximumRange ?: DEFAULT_LIMITING_RANGE)
        val distance = coordinates.distanceSquared(unit.position, target.position)
        return distance < globalRange * globalRange && distance <= maximum * maximum &&
            (weapon.minimumRange <= 0.0 || distance >= weapon.minimumRange * weapon.minimumRange)
    }

    fun target(unit: MutableUnit, weapon: NamedWeaponDefinition, state: MutableCombat,
               primary: MutableUnit?, candidates: Collection<MutableUnit>, players: List<Player>,
               coordinates: CoordinatePolicy): MutableUnit? {
        if (primary != null && qualifies(unit, primary, weapon, players, coordinates)) return primary
        if (unit.definition.combat?.multiTargeting != true ||
            (unit.definition.combat?.namedWeapons?.ordered?.size ?: 0) <= 1) return null
        candidates.firstOrNull { it.unitId == state.targetId && qualifies(unit, it, weapon, players, coordinates) }
            ?.let { return it }
        val globalRange = checkNotNull(unit.definition.combat).attackRange
        val maximum = minOf(globalRange, weapon.maximumRange ?: DEFAULT_LIMITING_RANGE)
        var best = maximum * maximum + 1.0
        val minimum = if (weapon.minimumRange > 0.0) weapon.minimumRange * weapon.minimumRange else -1.0
        var chosen: MutableUnit? = null
        for (candidate in candidates) {
            if (!qualifies(unit, candidate, weapon, players, coordinates)) continue
            val distance = coordinates.distanceSquared(unit.position, candidate.position)
            if (distance < best && distance > minimum) {
                chosen = candidate
                best = distance
            }
        }
        return chosen
    }

    fun profile(unit: MutableUnit, weapon: NamedWeaponDefinition) = profile(checkNotNull(unit.definition.combat), weapon)

    fun profile(base: CombatProfile, weapon: NamedWeaponDefinition): CombatProfile {
        // The legacy scalar adapter belongs to the first weapon only. Other omitted
        // local fields use their own zero default; inherited template values are explicit.
        val primary = base.namedWeapons?.ordered?.firstOrNull()?.name == weapon.name
        return base.copy(
        projectile = weapon.projectile, shootDelay = weapon.shootDelay, turretSize = weapon.turretSize,
        turretTurnSpeed = weapon.turnSpeed, turretTurnAcceleration = weapon.turnAcceleration,
        mount = weapon.mount, turretImagePath = weapon.imagePath, turretImageScale = weapon.imageScale,
        recoil = weapon.recoil, fireSound = weapon.fireSound, namedWeapons = null,
        barrelX = weapon.barrelX ?: if (primary) base.barrelX else 0.0,
        oddShotOffset = weapon.oddShotOffset ?: if (primary) base.oddShotOffset else 0.0)
    }

    fun pivot(unit: MutableUnit, weapon: NamedWeaponDefinition, state: MutableCombat): Position {
        var origin = unit.position
        var heading = unit.heading
        weapon.attachedTo?.let { name ->
            val parent = checkNotNull(unit.definition.combat?.namedWeapons).byName.getValue(name)
            require(parent.name != weapon.name && parent.attachedTo == null) { "Invalid nested weapon mount" }
            val parentState = checkNotNull(unit.namedWeapons).getValue(name)
            origin = pivot(unit, parent, parentState)
            heading = parentState.turretHeading
        }
        return OrdinaryWeapon.pivot(unit, state, profile(unit, weapon), origin, heading, effectiveDelay(weapon, state))
    }

    fun effectiveDelay(weapon: NamedWeaponDefinition, state: MutableCombat): Double = weapon.shootDelay +
        if (weapon.warmup == 0.0 || weapon.warmupShootDelayTransfer == 0.0) 0.0
        else -weapon.warmupShootDelayTransfer * (state.warmup / weapon.warmup)

    fun muzzle(unit: MutableUnit, weapon: NamedWeaponDefinition, state: MutableCombat): Position {
        return OrdinaryWeapon.muzzle(unit, state, profile(unit, weapon), pivot(unit, weapon, state))
    }

    fun observation(unit: MutableUnit, weapon: NamedWeaponDefinition, state: MutableCombat) =
        UnitWeaponObservation(unit.unitId, checkNotNull(unit.objectId), state.randomCounter,
            state.offset, state.targetId, state.angularVelocity, pivot(unit, weapon, state),
            muzzle(unit, weapon, state), state.cooldown, state.oddShot,
            WeaponIdleObservation(state.idleBaseHeading, state.idleTimer, state.idleOffset,
                state.idleDirection, state.idleReached, state.idleHold), weapon.name, state.warmup, state.turretHeading)
}
