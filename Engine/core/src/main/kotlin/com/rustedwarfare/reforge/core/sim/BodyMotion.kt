package com.rustedwarfare.reforge.core.sim

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
        // Native jet inherits bc=.6: short-distance point moves permit reversing when turning > 100 degrees,
        // governed orthogonally by MotionContract (independent of UnitTaskKind and task-queue length >= 2).
        val pointGoal = unit.motionContract?.goal as? MotionGoalGeometry.Point
        val shortJetMove = (unit.amphibiousState != null ||
            unit.definition.transport == com.rustedwarfare.reforge.core.content.TransportProfile.NATIVE_DROPSHIP) && unit.birthRemaining == 0f &&
            pointGoal?.allowShortReverse == true && unit.pathPoints.size in 1..2 &&
            ScreenPlane.distanceSquared(unit.position, target) < 10000.0
        val reverse = allowReverse && (unit.definition.movement.reverseSpeedPercentage > .95 || shortJetMove) &&
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
        // The native jet holds its independent aim only while it has a combat target.
        val idleJet = unit.amphibiousState != null && unit.primaryCombatTargetId == null
        if (unit.definition.combat?.rotateWithBody == true || idleJet) {
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
