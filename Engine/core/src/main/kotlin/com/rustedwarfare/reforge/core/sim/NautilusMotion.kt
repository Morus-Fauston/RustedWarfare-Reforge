package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.navigation.VerticalPoseController
import kotlin.math.abs

/**
 * Data-driven non-air vertical pose controller (ENG-P017-006):
 * consumes [UnitDefinition.effectiveVerticalPose] without hardcoding unit type IDs.
 */
internal object NautilusMotion : VerticalPoseController {
    fun slowDeath(unit: MutableUnit): Boolean = unit.definition.effectiveVerticalPose?.slowDeathFall == true

    /** slowDeathFall preserves motion and defers cargo cleanup to the first update starting on the ground. */
    fun advanceCorpse(unit: MutableUnit, units: Map<String, MutableUnit>,
                      map: MapDefinition, delta: Double) {
        if (!slowDeath(unit) || unit.waterSettled) return
        val elapsed = delta.toFloat()
        if (unit.height > 0.0) {
            val moving = unit.speed != 0.0 || unit.flightVelocity.x != 0.0 || unit.flightVelocity.y != 0.0
            unit.sinkSpeed += (if (moving) .017f else .06f) * elapsed
            unit.height = (unit.height.toFloat() - unit.sinkSpeed * elapsed).toDouble()
            if (moving) {
                val displacement = if (unit.definition.movement.slidingMode) Position(
                    unit.flightVelocity.x * delta, unit.flightVelocity.y * delta)
                    else ScreenPlane.displacement(unit.heading, unit.speed * delta)
                unit.position = Position(unit.position.x + displacement.x, unit.position.y + displacement.y)
            }
        } else if (!unit.flightLanded) {
            unit.flightLanded = true
            unit.flightLandedInWater = map.isLiquid(MapBounds.cell(unit.position.x, map.tileWidth, map.widthTiles),
                MapBounds.cell(unit.position.y, map.tileHeight, map.heightTiles))
            unit.sinkSpeed = 0f
            Transport.onDeath(unit, units, map)
        } else if (!unit.flightLandedInWater) {
            unit.height = 0.0
            unit.waterSettled = true
        } else if (unit.height > -10.0) {
            unit.sinkSpeed += .0008f * elapsed
            unit.height = (unit.height.toFloat() - unit.sinkSpeed * elapsed).toDouble()
        } else unit.waterSettled = true
        require(unit.sinkSpeed.isFinite() && unit.height.isFinite() && unit.position.x.isFinite() && unit.position.y.isFinite()) {
            "Custom vertical pose corpse must remain finite"
        }
    }

    override fun advancePose(
        unit: MutableUnit,
        map: MapDefinition,
        delta: Double,
        acceptsHeight: (Double) -> Boolean,
    ): String? {
        advance(unit, delta, acceptsHeight)
        return null
    }

    fun advance(unit: MutableUnit, delta: Double, acceptsHeight: (Double) -> Boolean) {
        val pose = unit.definition.effectiveVerticalPose ?: return
        val targetHeight = pose.targetHeight.toFloat()
        val drift = pose.heightDrift.toFloat()
        val elapsed = delta.toFloat()
        var height = unit.height.toFloat()
        val lower = targetHeight - drift
        if (height < lower) height = minOf(lower, height + .2f * elapsed)
        var phase = unit.heightPhase.toFloat()
        if (drift != 0f) {
            phase += 2f * elapsed
            if (phase > 360f) phase -= 360f
        }
        val target = targetHeight + GroundMath.sin(phase) * drift
        val change = minOf(.005f, abs(height - target) * .05f * .3f + .1f) * elapsed
        height = if (height < target) minOf(target, height + change) else maxOf(target, height - change)
        val fallingThreshold = targetHeight + drift + 1f
        var sinkSpeed = unit.sinkSpeed
        if (height > fallingThreshold) {
            sinkSpeed += .03f * elapsed
            if (height < 0f) sinkSpeed = minOf(sinkSpeed, .2f)
            height -= sinkSpeed * elapsed
            if (height <= fallingThreshold) {
                if (height < targetHeight + drift) height = targetHeight + drift
                sinkSpeed = 0f
            }
        } else sinkSpeed = 0f
        require(height.isFinite() && phase.isFinite() && sinkSpeed.isFinite()) { "Custom vertical pose height must remain finite" }
        if (!acceptsHeight(height.toDouble())) return
        unit.sinkSpeed = sinkSpeed
        unit.height = height.toDouble()
        unit.heightPhase = phase.toDouble()
    }
}
