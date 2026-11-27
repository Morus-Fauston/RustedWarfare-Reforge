package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.navigation.MotionGoalGeometry
import com.rustedwarfare.reforge.core.navigation.UnitLocomotion
import kotlin.math.abs

internal data class BodyTurn(val difference: Double, val applied: Double, val reversing: Boolean)

/** Shared ordinary body steering; mech presentation and feet remain in MechMotion. */
internal object BodyMotion {
    fun turn(
        unit: MutableUnit,
        target: Position,
        delta: Double,
        wasReversing: Boolean,
        allowReverse: Boolean = true,
        alignHeading: Boolean = false,
        faultHook: ((MutableUnit) -> Unit)? = null,
    ): BodyTurn {
        val desired = ScreenPlane.desired(unit.position, target)
        // Short-distance point moves permit reversing when turning > 100 degrees, governed orthogonally
        // by MotionContract and pure data-driven effectiveShortReverseDistance (zero unit-ID checks).
        val pointGoal = unit.motionContract?.goal as? MotionGoalGeometry.Point
        val shortReverseDistance = UnitLocomotion.shortReverseDistance(unit.definition)
        val shortReverseMove = shortReverseDistance != null && unit.birthRemaining == 0f &&
            pointGoal?.allowShortReverse == true && unit.pathPoints.size in 1..2 &&
            ScreenPlane.distanceSquared(unit.position, target) < shortReverseDistance * shortReverseDistance
        val reverse = allowReverse && (unit.definition.movement.reverseSpeedPercentage > .95 || shortReverseMove) &&
            abs(ScreenPlane.difference(desired, unit.heading)) > 100.0
        if (reverse != wasReversing) unit.combat?.let { it.idleHold = maxOf(25f, it.idleHold) }
        val aim = if (reverse) Position(2.0 * unit.position.x - target.x, 2.0 * unit.position.y - target.y) else target
        val headingBefore = unit.heading
        val difference = AcceleratedSteering.turn(unit, aim, delta, faultHook)
        if (alignHeading && abs(ScreenPlane.difference(desired, unit.heading)) < 0.01) {
            unit.heading = desired
            unit.angularVelocity = 0.0
        }
        val applied = ScreenPlane.difference(unit.heading, headingBefore)
        // Independent-aim amphibious bodies hold their turret aim only while tracking a combat target.
        val idleAmphibious = unit.amphibiousState != null && unit.primaryCombatTargetId == null
        if (unit.definition.combat?.rotateWithBody == true || idleAmphibious) {
            for (state in unit.namedWeapons?.values ?: listOfNotNull(unit.combat)) {
                state.turretHeading = ScreenPlane.normalize(state.turretHeading + applied)
            }
        }
        return BodyTurn(difference, applied, reverse)
    }

    fun turnOrdinary(
        unit: MutableUnit,
        target: Position,
        delta: Double,
        allowReverse: Boolean = true,
        alignHeading: Boolean = false,
        faultHook: ((MutableUnit) -> Unit)? = null,
    ): Double =
        turn(unit, target, delta, unit.bodyReversing, allowReverse, alignHeading, faultHook).also {
            unit.bodyReversing = it.reversing
        }.difference
}
