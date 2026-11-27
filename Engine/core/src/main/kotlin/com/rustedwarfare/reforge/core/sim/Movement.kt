package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementProfile
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Concrete 2D screen-plane geometry, differential steering, time-tick validation, and chassis
 * propulsion kinematics (ENG-P017-004).
 *
 * Replaces the 9 single-implementation `ExperimentalPolicies` fake interfaces and removes the
 * hidden non-final `< 9.0` dead-zone in [ReferencePropulsion] that previously forced callers to
 * pass fabricated `maxOf(d2, ...)` distances.
 */
internal object ScreenPlane {
    fun supports(model: CoordinateModel): Boolean = model.space == "2d" && model.xAxis == "x-right" &&
        model.yAxis == "y-down" && model.origin == "top-left" && model.unit == "world-unit" &&
        model.valueType == "double" && model.precision == "ieee754-binary64"

    fun normalize(heading: Double): Double = ((heading % 360.0) + 360.0) % 360.0

    fun difference(desired: Double, heading: Double): Double =
        ((desired - heading + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    fun desired(position: Position, target: Position): Double =
        normalize(Math.toDegrees(atan2(target.x - position.x, -(target.y - position.y))))

    fun distanceSquared(position: Position, target: Position): Double =
        (target.x - position.x) * (target.x - position.x) + (target.y - position.y) * (target.y - position.y)

    fun displacement(heading: Double, distance: Double): Position =
        Position(sin(Math.toRadians(heading)) * distance, -cos(Math.toRadians(heading)) * distance)
}

internal object AcceleratedSteering {
    fun turn(
        unit: MutableUnit,
        target: Position,
        delta: Double,
        faultHook: ((MutableUnit) -> Unit)? = null,
    ): Double {
        faultHook?.invoke(unit)
        val movement = UnitLocomotion.movement(unit)
        val diff = ScreenPlane.difference(ScreenPlane.desired(unit.position, target), unit.heading)
        if (abs(diff) < 0.01) return 0.0
        val direction = if (diff < 0.0) -1.0 else 1.0
        val acceleration = movement.turnAcceleration
        val turn = if (acceleration == 0.0) {
            direction * minOf(movement.maxTurnSpeed * delta, abs(diff))
        } else {
            val rate = if (abs(diff) < abs(unit.angularVelocity) / acceleration) acceleration else movement.maxTurnSpeed
            unit.angularVelocity = approach(unit.angularVelocity, direction * rate, acceleration * delta)
            val candidate = unit.angularVelocity * delta
            if (abs(candidate) > abs(diff)) {
                unit.angularVelocity = 0.0
                diff
            } else candidate
        }
        unit.heading = ScreenPlane.normalize(unit.heading + turn)
        return diff
    }
}

internal object StrictCapture {
    fun inside(distanceSquared: Double, radius: Double): Boolean = distanceSquared < radius * radius
}

internal object FixedSimTick {
    fun supports(model: TimeModel): Boolean = model.deltaUnit == "sim-tick" &&
        model.precision == "ieee754-binary64" && model.stepRule == "fixed-explicit-delta"

    fun validate(step: Step, model: TimeModel) {
        require(step.delta == 1.0) { "First-slice delta must be exactly 1.0" }
        require(step.deltaUnit == model.deltaUnit) { "deltaUnit must match the declared time model" }
    }
}

internal fun approach(current: Double, target: Double, amount: Double): Double = when {
    current < target -> (current + amount).coerceAtMost(target)
    current > target -> (current - amount).coerceAtLeast(target)
    else -> current
}

/**
 * Concrete chassis propulsion model explicitly distinguishing cruising (`finalStop = false`) from
 * final-stop braking (`finalStop = true`, gated by `stopRadius`).
 *
 * Never zeroes propulsion at `< 9.0` or `< 16.0` during intermediate waypoints, reentry alignment,
 * flow-field lookahead, or flank sliding, eliminating all fabricated `maxOf(d2, ...)` caller hacks.
 */
internal object ReferencePropulsion {
    fun targetSpeed(
        profile: MovementProfile,
        speed: Double,
        distanceSquared: Double,
        diff: Double,
        finalStop: Boolean,
        stopRadius: Double = 4.0,
    ): Double = profile.moveSpeed * targetFraction(profile, speed, distanceSquared, diff, finalStop, stopRadius)

    fun targetFraction(
        profile: MovementProfile,
        speed: Double,
        distanceSquared: Double,
        diff: Double,
        finalStop: Boolean,
        stopRadius: Double = 4.0,
    ): Double {
        val v = profile.moveSpeed
        val turn = profile.maxTurnSpeed
        val ratio = if (v == 0.0) 0.0 else speed / v
        var limit = 20.0
        if (distanceSquared > 361.0) limit = 46.0
        if (distanceSquared > 3600.0) limit = 89.0
        if (turn < 1.4) limit = if (distanceSquared > 6400.0) limit * 0.5 else 17.0
        if (v > 5.0 && ratio > -0.01 && ratio < 0.01) limit = 1.0
        if (turn < 1.1) limit *= 0.7
        if (ratio > 0.4 && distanceSquared > 16900.0) limit = 180.0

        val stopRadiusSq = if (finalStop && stopRadius > 0.0) stopRadius * stopRadius else 0.0
        var fraction = if (abs(diff) <= limit && !(finalStop && distanceSquared < stopRadiusSq)) 1.0 else 0.0
        if (finalStop && fraction != 0.0) {
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
