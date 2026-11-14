package com.rustedwarfare.reforge.core.sim

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.hypot
import com.rustedwarfare.reforge.core.content.AttackMovementProfile
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementProfile

/** Adapted from the qualified movement-only flight slice, using the shared physical height. */
internal object FlightMotion {
    fun velocity(profile: MovementProfile, current: Position, target: Position, fraction: Double, delta: Double, maximumSpeed: Double = profile.moveSpeed): Position {
        val oldDistance = hypot(target.x - current.x, target.y - current.y)
        var vx = current.x
        var vy = current.y
        if (oldDistance > maximumSpeed) {
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

    fun advancePose(unit: MutableUnit, map: MapDefinition, delta: Double) {
        val profile = unit.definition.movement.flight ?: return
        unit.definition.rotor?.let { rotor ->
            unit.rotorPhase = (unit.rotorPhase + rotor.spinRate * delta) % 360.0
        }
        unit.rotorHeightOffset = (unit.rotorHeightOffset - .3 * delta).coerceAtLeast(0.0)
        unit.heightPhase += 2.0 * delta
        if (unit.heightPhase > 360.0) unit.heightPhase -= 360.0
        val bomber = unit.definition.attackMovement == AttackMovementProfile.BOMBER
        if ((bomber || profile.landWhenStationary) && advanceIdleLanding(unit, map, delta, bomber)) return
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

    fun bomberAttackDestination(unit: MutableUnit, target: Position, map: MapDefinition): Position {
        val x = target.x.toFloat()
        val y = target.y.toFloat()
        val sourceX = unit.position.x.toFloat()
        val sourceY = unit.position.y.toFloat()
        val distanceSquared = GroundMath.square(sourceX, sourceY, x, y)
        val approach = unit.bomberApproach ?: BomberApproach(GroundMath.angle(sourceX, sourceY, x, y), false)
        val breakingAway = approach.breakingAway || distanceSquared < 10_000f && unit.energyRecharging
        if (!breakingAway) {
            unit.bomberApproach = approach
            return target
        }
        val maximum = checkNotNull(unit.definition.energy).maximum
        if (unit.energy.toDouble() >= maximum * .6 && (distanceSquared >= 40_000f || unit.energy >= maximum)) {
            unit.bomberApproach = null
            return target
        }
        unit.bomberApproach = approach.copy(breakingAway = true)
        val away = approach.angle + 180f
        val radius = Transport.boundaryRadius(unit)
        // The route planner needs an in-bounds endpoint even if the offset is past the map edge.
        return Position((x + GroundMath.cos(away) * 600f).toDouble().coerceIn(radius, map.widthTiles.toDouble() * map.tileWidth - radius),
            (y + GroundMath.sin(away) * 600f).toDouble().coerceIn(radius, map.heightTiles.toDouble() * map.tileHeight - radius))
    }

    private fun advanceIdleLanding(unit: MutableUnit, map: MapDefinition, delta: Double, requiresEmptyQueue: Boolean): Boolean {
        unit.flightIdleTime = if (unit.movementRequested || OrdinaryWeapon.moving(unit)) 0f
            else minOf(4f, unit.flightIdleTime + delta.toFloat())
        if (unit.flightIdleTime <= 3f || requiresEmptyQueue && unit.tasks.isNotEmpty() || map.isLiquid(
                MapBounds.cell(unit.position.x, map.tileWidth, map.widthTiles),
                MapBounds.cell(unit.position.y, map.tileHeight, map.heightTiles))) return false
        val height = unit.height.toFloat()
        val speed = abs(height - 2f) * .05f * .4f + .2f
        unit.height = approach(height.toDouble(), 2.0, (speed * delta.toFloat()).toDouble())
        check(unit.height.isFinite()) { "Non-finite idle flight height for ${unit.unitId}" }
        return true
    }
}
