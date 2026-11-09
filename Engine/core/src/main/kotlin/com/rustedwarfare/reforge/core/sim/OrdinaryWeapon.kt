package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.DirectHitTargetKind
import com.rustedwarfare.reforge.core.content.CombatProfile
import com.rustedwarfare.reforge.core.content.MechKind
import kotlin.math.abs
import kotlin.math.sqrt

/** Ordinary ground weapon only; geometry uses this core's Double/ScreenPlane contract. */
internal object OrdinaryWeapon {
    fun sample(seed: Int, frame: Int, objectId: Long, unit: MutableUnit, salt: Int, min: Int, max: Int,
               state: MutableCombat = checkNotNull(unit.combat)): Int {
        require(max > min)
        val t = frame + 1
        var h = (seed.toLong() + objectId * 1313L).toInt()
        val x = unit.position.x.toFloat()
        val y = unit.position.y.toFloat()
        h = (h.toFloat() + x * 13f).toInt()
        h = (h.toFloat() + y * 13f).toInt()
        h = (h.toFloat() + x * 130f).toInt()
        h = (h.toFloat() + y * 130f).toInt()
        h += state.randomCounter * 13131
        h += state.randomCounter * t
        h += salt * 133 * max
        h = (h.toLong() + salt.toLong() * objectId + salt).toInt()
        h += t * 1313 * salt
        h += t * 13 + t % 10
        var remainder = h % (max - min)
        if (remainder < 0) remainder = -remainder
        return min + remainder
    }

    fun refreshOffset(seed: Int, frame: Int, objectId: Long, unit: MutableUnit, target: MutableUnit,
                      state: MutableCombat = checkNotNull(unit.combat)) {
        if (unit.definition.combat?.projectile?.beam != null) {
            state.offset = Position(0.0, 0.0)
            return
        }
        val bound = (target.definition.radius.toFloat() * .6f).toInt()
        state.offset = if (bound == 0) Position(0.0, 0.0) else Position(
            sample(seed, frame, objectId, unit, 1, -bound, bound, state).toDouble(),
            sample(seed, frame, objectId, unit, 2, -bound, bound, state).toDouble())
    }

    fun pivot(unit: MutableUnit, state: MutableCombat = checkNotNull(unit.combat),
              weapon: CombatProfile = checkNotNull(unit.definition.combat),
              origin: Position = unit.position, baseHeading: Double = unit.heading,
              recoilDelay: Double = weapon.shootDelay): Position {
        val profile = weapon.recoil
        val elapsed = recoilDelay - state.cooldown
        val recoil = if (state.cooldown <= 0.0) 0.0 else when {
            elapsed < profile.outTime -> elapsed / profile.outTime * profile.offset
            elapsed < profile.outTime + profile.returnTime ->
                profile.offset - (elapsed - profile.outTime) / profile.returnTime * profile.offset
            else -> 0.0
        }
        val d = ScreenPlane.displacement(state.turretHeading, recoil)
        val forward = ScreenPlane.displacement(baseHeading, weapon.mount.localY)
        val side = ScreenPlane.displacement(baseHeading + 90.0, weapon.mount.localX)
        return Position(origin.x + d.x + forward.x + side.x,
            origin.y + d.y + forward.y + side.y)
    }

    fun muzzle(unit: MutableUnit, state: MutableCombat = checkNotNull(unit.combat),
               weapon: CombatProfile = checkNotNull(unit.definition.combat),
               base: Position = pivot(unit, state, weapon)): Position {
        val forward = ScreenPlane.displacement(state.turretHeading, weapon.turretSize)
        val side = ScreenPlane.displacement(state.turretHeading + 90.0,
            weapon.barrelX + if (state.oddShot) weapon.oddShotOffset else 0.0)
        return Position(base.x + forward.x + side.x, base.y + forward.y + side.y)
    }

    fun hasFiniteGeometry(unit: MutableUnit): Boolean {
        unit.definition.combat?.takeIf { NamedWeaponRuntime.usesNamedState(it) }?.namedWeapons?.let { catalog ->
            val states = unit.namedWeapons ?: return false
            if (states.keys.toList() != catalog.ordered.map { it.name }) return false
            return catalog.ordered.all { definition ->
                val state = states.getValue(definition.name)
                val pivot = NamedWeaponRuntime.pivot(unit, definition, state)
                val muzzle = NamedWeaponRuntime.muzzle(unit, definition, state)
                pivot.x.isFinite() && pivot.y.isFinite() && muzzle.x.isFinite() && muzzle.y.isFinite()
            }
        }
        val pivot = pivot(unit)
        val muzzle = muzzle(unit)
        return pivot.x.isFinite() && pivot.y.isFinite() && muzzle.x.isFinite() && muzzle.y.isFinite()
    }

    fun aim(unit: MutableUnit, target: MutableUnit, delta: Double, state: MutableCombat = checkNotNull(unit.combat),
            weapon: CombatProfile = checkNotNull(unit.definition.combat),
            pivotForAim: (() -> Position)? = null): Double {
        val aim = Position(target.position.x + state.offset.x, target.position.y + state.offset.y)
        fun origin() = pivotForAim?.invoke() ?: pivot(unit, state, weapon)
        turnTo(unit, ScreenPlane.desired(origin(), aim), delta, state, weapon)
        return ScreenPlane.difference(ScreenPlane.desired(origin(), aim), state.turretHeading)
    }

    fun turnTo(unit: MutableUnit, heading: Double, delta: Double, state: MutableCombat,
                       weapon: CombatProfile, difference: Double = ScreenPlane.difference(heading, state.turretHeading)): Double {
        val diff = difference
        if (abs(diff) >= .01) {
            val acceleration = weapon.turretTurnAcceleration
            val sign = if (diff < 0) -1.0 else 1.0
            val braking = weapon.turretTurnDeceleration
            if (braking != null && acceleration > 0.0) {
                val slowing = abs(diff) < abs(state.angularVelocity) / braking &&
                    (diff > 0.0) == (state.angularVelocity > 0.0)
                state.angularVelocity = approach(state.angularVelocity,
                    sign * if (slowing) braking else weapon.turretTurnSpeed,
                    (if (slowing) braking else acceleration) * delta)
            } else {
                val rate = if (acceleration > 0.0 && abs(diff) < abs(state.angularVelocity) / acceleration)
                    acceleration else weapon.turretTurnSpeed
                state.angularVelocity = if (acceleration == 0.0) sign * rate else
                    approach(state.angularVelocity, sign * rate, acceleration * delta)
            }
            val turn = state.angularVelocity * delta
            val applied = if (abs(turn) > abs(diff)) {
                state.angularVelocity = 0.0
                diff
            } else turn
            state.turretHeading = ScreenPlane.normalize(state.turretHeading + applied)
            if (state === unit.combat && unit.definition.mech?.kind == MechKind.TWO_LEGGED)
                unit.mechState?.let { unit.mechState = it.copy(bodyAngle = it.bodyAngle + applied) }
        }
        return ScreenPlane.difference(heading, state.turretHeading)
    }

    fun idle(seed: Int, frame: Int, objectId: Long, unit: MutableUnit, delta: Double,
             state: MutableCombat = checkNotNull(unit.combat),
             weapon: CombatProfile = checkNotNull(unit.definition.combat)) {
        val profile = weapon.idleSweep ?: return
        if (profile.angle == 0.0) return
        val elapsed = delta.toFloat()
        if (state.idleHold != 0f) {
            state.idleHold = maxOf(0f, state.idleHold - elapsed)
            return
        }
        unit.mechState?.takeIf { it.hadMotionAim }?.let { motion ->
            val body = checkNotNull(unit.definition.mech).body
            val base = motion.motionAngle + if (motion.reversing && unit.definition.movement.reverseSpeedPercentage > .95)
                body.reverseIdleDirection else body.idleDirection
            state.idleBaseHeading = ScreenPlane.normalize(base + 90.0)
            val difference = MechMotion.angleDifference(base, motion.bodyAngle)
            when {
                motion.bodyAngle == base -> Unit
                abs(difference) < .5 -> { state.idleHold = 20f; state.angularVelocity = 0.0 }
                else -> turnTo(unit, state.idleBaseHeading, delta, state, weapon, difference)
            }
            return
        }
        state.idleTimer += elapsed
        if (!state.idleReached) {
            val residual = turnTo(unit, ScreenPlane.normalize(state.idleBaseHeading + state.idleOffset),
                (profile.speedFactor * elapsed).toDouble(), state, weapon)
            if (abs(residual) < .01) state.idleReached = true
        }
        if (state.idleTimer > profile.delay) {
            state.idleTimer = -sample(seed, frame, objectId, unit, 0, 0, profile.randomDelayExclusive, state).toFloat()
            state.idleDirection = -state.idleDirection
            state.idleOffset = profile.angle * state.idleDirection
            state.idleReached = false
        }
    }

    /** Cached pre-plane distance is retained unless the ordinary plane step overshoots. */
    fun move(projectile: MutableProjectile, target: MutableUnit, delta: Double): Boolean {
        projectile.remainingLife = maxOf(0.0, projectile.remainingLife - delta)
        val destination = Position(target.position.x + projectile.offset.x, target.position.y + projectile.offset.y)
        var distanceSquared = ScreenPlane.distanceSquared(projectile.position, destination)
        require(distanceSquared.isFinite()) { "Projectile geometry must remain finite" }
        projectile.heading = ScreenPlane.desired(projectile.position, destination)
        val step = projectile.speed * delta
        require(step.isFinite()) { "Projectile step must remain finite" }
        if (distanceSquared == 0.0) {
            // A positive subnormal speed can have a squared step of zero. The ordinary hit
            // cache is already zero here; never normalize the zero vector by dividing by zero.
            projectile.position = destination
        } else if (distanceSquared < step * step) {
            projectile.position = destination
            distanceSquared = 0.0
        } else {
            val distance = sqrt(distanceSquared)
            projectile.position = Position(projectile.position.x + (destination.x - projectile.position.x) / distance * step,
                projectile.position.y + (destination.y - projectile.position.y) / distance * step)
        }
        val heightDifference = target.height - projectile.height
        require(heightDifference.isFinite()) { "Projectile height geometry must remain finite" }
        val heightStep = if (heightDifference != 0.0 && distanceSquared > .1)
            minOf(abs(heightDifference) / sqrt(distanceSquared) * step, step) else step
        projectile.height = approach(projectile.height, target.height, heightStep)
        projectile.acceleration?.let { profile ->
            val amount = profile.acceleration * delta
            require(amount.isFinite()) { "Projectile acceleration step must remain finite" }
            projectile.speed = approach(projectile.speed, profile.targetSpeed, amount)
        }
        val contactRadius = when (target.definition.directHitTargetKind) {
            DirectHitTargetKind.ORDINARY -> 6f
            DirectHitTargetKind.NATIVE_BUILDING -> maxOf(6f, target.definition.radius.toFloat() * .8f)
        }
        // Preserve the source's binary32 threshold and square before comparing this core's Double cache.
        val contactSquared = (contactRadius * contactRadius).toDouble()
        return distanceSquared < contactSquared && abs(target.height - projectile.height) < 3.0
    }
}
