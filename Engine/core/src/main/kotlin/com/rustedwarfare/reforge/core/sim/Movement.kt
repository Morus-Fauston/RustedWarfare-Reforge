package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementProfile
import kotlin.math.abs

internal fun interface PropulsionPolicy {
    fun targetSpeed(profile: MovementProfile, speed: Double, distanceSquared: Double, diff: Double,
                    finalPoint: Boolean, targetRadius: Double): Double
    fun targetFraction(profile: MovementProfile, speed: Double, distanceSquared: Double, diff: Double,
                       finalPoint: Boolean, targetRadius: Double): Double =
        if (profile.moveSpeed == 0.0) 0.0 else targetSpeed(profile, speed, distanceSquared, diff, finalPoint, targetRadius) / profile.moveSpeed
}

/** S reference structure; world-speed mapping is experimental, not original numeric compatibility. */
internal object ReferencePropulsion : PropulsionPolicy {
    override fun targetSpeed(profile: MovementProfile, speed: Double, distanceSquared: Double, diff: Double,
                             finalPoint: Boolean, targetRadius: Double): Double =
        profile.moveSpeed * targetFraction(profile, speed, distanceSquared, diff, finalPoint, targetRadius)

    override fun targetFraction(profile: MovementProfile, speed: Double, distanceSquared: Double, diff: Double,
                                finalPoint: Boolean, targetRadius: Double): Double {
        val v = profile.moveSpeed
        val turn = profile.maxTurnSpeed
        val ratio = if (v == 0.0) 0.0 else speed / v
        var limit = 20.0
        if (distanceSquared > 361.0) limit = 46.0
        if (distanceSquared > 3600.0) limit = 89.0
        if (turn < 1.4) limit = if (distanceSquared > 6400.0) limit * 0.5 else 17.0
        if (v > 5.0 && ratio < 0.01) limit = 1.0
        if (turn < 1.1) limit *= 0.7
        if (ratio > 0.4 && distanceSquared > 16900.0) limit = 180.0
        // The local final-waypoint propulsion gate is separate from command completion.
        var fraction = if (abs(diff) <= limit && !(finalPoint && distanceSquared < 16.0)) 1.0 else 0.0
        if (distanceSquared < 9.0) fraction = 0.0
        if (finalPoint && fraction != 0.0) {
            if (distanceSquared < 324.0 && profile.moveDecelerationSpeed < 0.13 && v > 1.0) fraction *= 0.5
            if (distanceSquared < 169.0 && profile.moveDecelerationSpeed < 0.15 && v > 0.9) fraction *= 0.5
            if (v > 5.0) {
                if (distanceSquared < 324.0 && fraction > 0.5) fraction = 0.5
                if (distanceSquared < 81.0 && fraction > 0.25) fraction = 0.25
            }
        }
        return fraction
    }
}
