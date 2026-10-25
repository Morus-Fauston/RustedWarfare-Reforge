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
        val delay = checkNotNull(unit.definition.combat).shootDelay
        val elapsed = delay - state.cooldown
        val recoil = if (state.cooldown <= 0.0) 0.0 else when {
            elapsed < 4.0 -> elapsed / 4.0 * -2.0
            elapsed < 11.0 -> -2.0 - (elapsed - 4.0) / 7.0 * -2.0
            else -> 0.0
        }
        val d = ScreenPlane.displacement(state.turretHeading, recoil)
        return Position(unit.position.x + d.x, unit.position.y + d.y)
    }

    fun muzzle(unit: MutableUnit): Position {
        val state = checkNotNull(unit.combat)
        val base = pivot(unit)
        val forward = ScreenPlane.displacement(state.turretHeading, checkNotNull(unit.definition.combat).turretSize)
        return Position(base.x + forward.x, base.y + forward.y)
    }

    fun aim(unit: MutableUnit, target: MutableUnit, delta: Double): Double {
        val state = checkNotNull(unit.combat)
        val weapon = checkNotNull(unit.definition.combat)
        val aim = Position(target.position.x + state.offset.x, target.position.y + state.offset.y)
        val diff = ScreenPlane.difference(ScreenPlane.desired(pivot(unit), aim), state.turretHeading)
        if (abs(diff) >= .01) {
            val acceleration = weapon.turretTurnAcceleration
            val sign = if (diff < 0) -1.0 else 1.0
            val rate = if (acceleration > 0.0 && abs(diff) < abs(state.angularVelocity) / acceleration)
                acceleration else weapon.turretTurnSpeed
            state.angularVelocity = if (acceleration == 0.0) sign * rate else
                approach(state.angularVelocity, sign * rate, acceleration * delta)
            val turn = state.angularVelocity * delta
            state.turretHeading = ScreenPlane.normalize(state.turretHeading + if (abs(turn) > abs(diff)) {
                state.angularVelocity = 0.0
                diff
            } else turn)
        }
        return ScreenPlane.difference(ScreenPlane.desired(pivot(unit), aim), state.turretHeading)
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
