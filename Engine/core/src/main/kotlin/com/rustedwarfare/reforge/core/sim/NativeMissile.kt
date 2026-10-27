package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.DirectHitTargetKind
import kotlin.math.abs

/** Native center flight uses binary32 caches; public positions only carry the resulting values. */
internal object NativeMissile {
    private fun towards(value: Float, target: Float, amount: Float): Float =
        when {
            value > target + amount -> value - amount
            value < target - amount -> value + amount
            else -> target
        }

    fun move(shot: MutableProjectile, target: MutableUnit, delta: Double): Boolean {
        val profile = checkNotNull(shot.missile)
        val elapsed = delta.toFloat()
        shot.remainingLife = maxOf(0f, shot.remainingLife.toFloat() - elapsed).toDouble()
        var x = shot.position.x.toFloat()
        var y = shot.position.y.toFloat()
        var height = shot.height.toFloat()
        val destinationX = target.position.x.toFloat() + shot.offset.x.toFloat()
        val destinationY = target.position.y.toFloat() + shot.offset.y.toFloat()
        val heightDifference = target.height.toFloat() - height
        var distanceSquared = GroundMath.square(x, y, destinationX, destinationY)
        require(listOf(x, y, height, destinationX, destinationY, heightDifference, distanceSquared).all { it.isFinite() }) {
            "Missile geometry must remain finite"
        }
        shot.missileRawAz = GroundMath.angle(x, y, destinationX, destinationY)
        shot.heading = shot.missileRawAz.toDouble() + 90.0
        val planeMoved = height > profile.climbThreshold || shot.missileReachedApex
        if (planeMoved) {
            var travel = shot.speed.toFloat() * elapsed
            require(travel.isFinite() && (travel * travel).isFinite()) { "Missile travel must remain finite" }
            if (distanceSquared < travel * travel) { travel = GroundMath.sqrt(distanceSquared); distanceSquared = 0f }
            x += GroundMath.cos(shot.missileRawAz) * travel
            y += GroundMath.sin(shot.missileRawAz) * travel
        }
        if (!shot.missileReachedApex) {
            height = towards(height, profile.apex, profile.verticalSpeed * elapsed)
            if (height >= profile.apex) shot.missileReachedApex = true
        } else if (distanceSquared < 400f) height = towards(height, target.height.toFloat(), profile.verticalSpeed * elapsed)
        if (planeMoved && profile.targetSpeed > 0f)
            shot.speed = towards(shot.speed.toFloat(), profile.targetSpeed, profile.acceleration * elapsed).toDouble()
        require(listOf(x, y, height, shot.missileRawAz, shot.speed.toFloat()).all { it.isFinite() }) {
            "Missile candidate must remain finite"
        }
        shot.position = Position(x.toDouble(), y.toDouble())
        shot.height = height.toDouble()
        val radius = if (target.definition.directHitTargetKind == DirectHitTargetKind.NATIVE_BUILDING)
            maxOf(6f, target.definition.radius.toFloat() * .8f) else 6f
        return distanceSquared < radius * radius && abs(heightDifference) < 3f
    }
}
