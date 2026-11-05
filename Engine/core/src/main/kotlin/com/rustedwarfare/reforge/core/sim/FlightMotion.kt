package com.rustedwarfare.reforge.core.sim

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.hypot
import com.rustedwarfare.reforge.core.content.MovementProfile

/** Adapted from the qualified movement-only flight slice, using the shared physical height. */
internal object FlightMotion {
    fun velocity(profile: MovementProfile, current: Position, target: Position, fraction: Double, delta: Double): Position {
        val oldDistance = hypot(target.x - current.x, target.y - current.y)
        var vx = current.x
        var vy = current.y
        if (oldDistance > profile.moveSpeed) {
            vx *= 1.0 - .05 * delta
            vy *= 1.0 - .05 * delta
        }
        val limit = (if (fraction != 0.0) profile.moveAccelerationSpeed else profile.moveDecelerationSpeed) * 1.41 * delta
        check(limit.isFinite()) { "Non-finite flight acceleration" }
        if (oldDistance < limit) return target
        val remaining = hypot(target.x - vx, target.y - vy)
        if (remaining == 0.0 || limit == 0.0) return Position(vx, vy)
        return Position(vx + (target.x - vx) / remaining * limit, vy + (target.y - vy) / remaining * limit)
    }

    fun advancePose(unit: MutableUnit, delta: Double) {
        val profile = unit.definition.movement.flight ?: return
        val rotor = checkNotNull(unit.definition.rotor)
        unit.rotorPhase = (unit.rotorPhase + rotor.spinRate * delta) % 360.0
        unit.rotorHeightOffset = (unit.rotorHeightOffset - .3 * delta).coerceAtLeast(0.0)
        unit.heightPhase += 2.0 * delta
        if (unit.heightPhase > 360.0) unit.heightPhase -= 360.0
        val desired = profile.targetHeight + sin(Math.toRadians(unit.heightPhase)) * profile.heightDrift
        val difference = unit.height - desired
        check(desired.isFinite() && difference.isFinite()) { "Non-finite derived flight height for ${unit.unitId}" }
        val rate = if (profile.heightChangeRate < 0.0) abs(unit.height - 2.0) * .02 + .2
            else profile.heightChangeRate
        val cappedRate = minOf(rate, abs(difference) * .015 + .1)
        unit.height = approach(unit.height, desired, cappedRate * delta)
        check(listOf(unit.height, unit.heightPhase, unit.rotorPhase, unit.rotorHeightOffset,
                desired, rate, cappedRate).all { it.isFinite() }) {
            "Non-finite flight height for ${unit.unitId}"
        }
    }
}
