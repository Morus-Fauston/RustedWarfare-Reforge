package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.DirectHitTargetKind
import kotlin.math.abs
import kotlin.math.sqrt

/** Ordinary ground weapon only; geometry uses this core's Double/ScreenPlane contract. */
internal object OrdinaryWeapon {
    fun sample(seed: Int, frame: Int, objectId: Long, unit: MutableUnit, salt: Int, min: Int, max: Int): Int {
        require(max > min)
        val state = checkNotNull(unit.combat)
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

    fun refreshOffset(seed: Int, frame: Int, objectId: Long, unit: MutableUnit, target: MutableUnit) {
        val bound = (target.definition.radius.toFloat() * .6f).toInt()
        val state = checkNotNull(unit.combat)
        state.offset = if (bound == 0) Position(0.0, 0.0) else Position(
            sample(seed, frame, objectId, unit, 1, -bound, bound).toDouble(),
            sample(seed, frame, objectId, unit, 2, -bound, bound).toDouble())
    }

    fun pivot(unit: MutableUnit): Position {
        val state = checkNotNull(unit.combat)
        val weapon = checkNotNull(unit.definition.combat)
        val delay = weapon.shootDelay
        val profile = weapon.recoil
        val elapsed = delay - state.cooldown
        val recoil = if (state.cooldown <= 0.0) 0.0 else when {
            elapsed < profile.outTime -> elapsed / profile.outTime * profile.offset
            elapsed < profile.outTime + profile.returnTime -> profile.offset - (elapsed - profile.outTime) / profile.returnTime * profile.offset
            else -> 0.0
        }
        val d = ScreenPlane.displacement(state.turretHeading, recoil)
        val base = Position(unit.position.x + d.x, unit.position.y + d.y)
        val mount = weapon.mount
        if (mount.localX == 0.0 && mount.localY == 0.0) return base
        val forward = ScreenPlane.displacement(unit.heading, mount.localY)
        val side = ScreenPlane.displacement(unit.heading + 90.0, mount.localX)
        return Position(base.x + forward.x + side.x, base.y + forward.y + side.y)
    }

    fun muzzle(unit: MutableUnit): Position {
        val state = checkNotNull(unit.combat)
        val base = pivot(unit)
        val forward = ScreenPlane.displacement(state.turretHeading, checkNotNull(unit.definition.combat).turretSize)
        return Position(base.x + forward.x, base.y + forward.y)
    }

    fun aim(unit: MutableUnit, target: MutableUnit, delta: Double): Double {
        val state = checkNotNull(unit.combat)
        val aim = Position(target.position.x + state.offset.x, target.position.y + state.offset.y)
        turnTo(unit, ScreenPlane.desired(pivot(unit), aim), delta)
        // Recoil pivot depends on the updated angle, so re-evaluate the actual firing residual.
        return ScreenPlane.difference(ScreenPlane.desired(pivot(unit), aim), state.turretHeading)
    }

    private fun turnTo(unit: MutableUnit, desired: Double, delta: Double, exact: Boolean = false): Double {
        val state = checkNotNull(unit.combat)
        val weapon = checkNotNull(unit.definition.combat)
        val diff = ScreenPlane.difference(desired, state.turretHeading)
        if (if (exact) diff != 0.0 else abs(diff) >= .01) {
            val acceleration = weapon.turretTurnAcceleration
            val sign = if (diff < 0) -1.0 else 1.0
            val turn = if (acceleration == 0.0) {
                // The original r<=0 branch rotates directly; it does not write the acceleration state.
                sign * minOf(weapon.turretTurnSpeed * delta, abs(diff))
            } else {
                val rate = if (abs(diff) < abs(state.angularVelocity) / acceleration) acceleration else weapon.turretTurnSpeed
                state.angularVelocity = approach(state.angularVelocity, sign * rate, acceleration * delta)
                val candidate = state.angularVelocity * delta
                if (abs(candidate) > abs(diff)) { state.angularVelocity = 0.0; diff } else candidate
            }
            state.turretHeading = ScreenPlane.normalize(state.turretHeading + turn)
        }
        return ScreenPlane.difference(desired, state.turretHeading)
    }

    fun advanceIdle(unit: MutableUnit, seed: Int, frame: Int, objectId: Long, delta: Double) {
        val profile = checkNotNull(unit.definition.combat).idleSweep ?: return
        val state = checkNotNull(unit.combat)
        if (state.idleHold != 0f) {
            state.idleHold = maxOf(0f, state.idleHold - delta.toFloat())
            return // Reaching zero does not add an idle callback in the same update.
        }
        state.idleTimer += delta.toFloat()
        if (!state.idleReached) {
            val residual = turnTo(unit, ScreenPlane.normalize(state.idleBaseHeading + state.idleOffset),
                (profile.speedFactor * delta.toFloat()).toDouble(), exact = true)
            if (residual == 0.0) state.idleReached = true
        }
        if (state.idleTimer > profile.delay) {
            state.idleTimer = -sample(seed, frame, objectId, unit, 0, 0, profile.randomDelayExclusive).toFloat()
            state.idleDirection = -state.idleDirection
            state.idleOffset = state.idleDirection * profile.angle
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
        val contactRadius = when (target.definition.directHitTargetKind) {
            DirectHitTargetKind.ORDINARY -> 6f
            DirectHitTargetKind.NATIVE_BUILDING -> maxOf(6f, target.definition.radius.toFloat() * .8f)
        }
        // Preserve the source's binary32 threshold and square before comparing this core's Double cache.
        val contactSquared = (contactRadius * contactRadius).toDouble()
        return distanceSquared < contactSquared && abs(target.height - projectile.height) < 3.0
    }
}
