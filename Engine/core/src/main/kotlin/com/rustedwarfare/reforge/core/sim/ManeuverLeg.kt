package com.rustedwarfare.reforge.core.sim

import kotlin.math.abs
import kotlin.math.sqrt

/** Turn at a validated waypoint, then translate along the tested line to the next waypoint. */
internal class ManeuverLeg(private val from: Position, val target: Position, private val coordinates: CoordinatePolicy) {
    private val heading = coordinates.desired(from, target)
    private val distance = sqrt(coordinates.distanceSquared(from, target))

    fun aligned(heading: Double): Boolean = abs(coordinates.difference(this.heading, heading)) < 1e-8

    fun approachSpeed(maximum: Double, brakingRate: Double, isLast: Boolean): Double =
        if (isLast || brakingRate == 0.0) maximum else minOf(maximum, sqrt(2.0 * brakingRate * distance))

    fun position(heading: Double, distanceThisStep: Double): Position {
        if (!aligned(heading) || distanceThisStep <= 0.0) return from
        if (distanceThisStep >= distance) return target
        val fraction = distanceThisStep / distance
        return Position(from.x + (target.x - from.x) * fraction, from.y + (target.y - from.y) * fraction)
    }
}
