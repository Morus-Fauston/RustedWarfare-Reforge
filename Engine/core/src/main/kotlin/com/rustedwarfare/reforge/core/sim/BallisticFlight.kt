package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.DirectHitTargetKind
import kotlin.math.abs

/** Shared ordered binary32 flight; native and custom profiles retain separate state and acceleration gates. */
internal object BallisticFlight {
    private fun towards(value: Float, target: Float, amount: Float): Float = when {
        value > target + amount -> value - amount
        value < target - amount -> value + amount
        else -> target
    }

    fun move(shot: MutableProjectile, target: MutableUnit, delta: Double): Boolean {
        val native = shot.missile
        val custom = shot.ballistic
        require((native != null) != (custom != null)) { "Expected one ballistic flight profile" }
        val threshold = native?.climbThreshold ?: checkNotNull(custom).delayMoveHeight
        val apex = native?.apex ?: checkNotNull(custom).apex
        val configuredVertical = native?.verticalSpeed ?: checkNotNull(custom).verticalSpeed
        val usesCurrentSpeed = custom != null && configuredVertical == -1f
        val vertical = if (usesCurrentSpeed) shot.speed.toFloat() else configuredVertical
        val targetSpeed = native?.targetSpeed ?: shot.acceleration?.targetSpeed?.toFloat() ?: 0f
        val acceleration = native?.acceleration ?: shot.acceleration?.acceleration?.toFloat() ?: 0f
        var reached = if (custom != null) shot.ballisticReachedApex else shot.missileReachedApex
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
        val angle = GroundMath.angle(x, y, destinationX, destinationY)
        shot.heading = angle.toDouble() + 90.0
        val planeMoved = height > threshold || reached
        if (planeMoved) {
            var travel = shot.speed.toFloat() * elapsed
            require(travel.isFinite() && (travel * travel).isFinite()) { "Missile travel must remain finite" }
            if (distanceSquared < travel * travel) { travel = GroundMath.sqrt(distanceSquared); distanceSquared = 0f }
            x += GroundMath.cos(angle) * travel
            y += GroundMath.sin(angle) * travel
        }
        if (!reached) {
            height = towards(height, apex, vertical * elapsed)
            if (height >= apex) reached = true
        } else if (distanceSquared < 400f) height = towards(height, target.height.toFloat(), vertical * elapsed)
        // Original custom aL<0 marks local13 even on a vertical-only first step.
        if ((planeMoved || usesCurrentSpeed) && targetSpeed > 0f)
            shot.speed = towards(shot.speed.toFloat(), targetSpeed, acceleration * elapsed).toDouble()
        require(listOf(x, y, height, angle, shot.speed.toFloat()).all { it.isFinite() }) {
            "Missile candidate must remain finite"
        }
        shot.position = Position(x.toDouble(), y.toDouble())
        shot.height = height.toDouble()
        if (custom != null) {
            shot.ballisticReachedApex = reached
            shot.ballisticRawAz = angle
        } else {
            shot.missileReachedApex = reached
            shot.missileRawAz = angle
        }
        val radius = if (target.definition.directHitTargetKind == DirectHitTargetKind.NATIVE_BUILDING)
            maxOf(6f, target.definition.radius.toFloat() * .8f) else 6f
        return distanceSquared < radius * radius && abs(heightDifference) < 3f
    }
}
