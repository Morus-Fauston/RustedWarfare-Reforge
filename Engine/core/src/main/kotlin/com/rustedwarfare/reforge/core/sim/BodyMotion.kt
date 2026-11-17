package com.rustedwarfare.reforge.core.sim

import kotlin.math.abs

internal data class BodyTurn(val difference: Double, val applied: Double, val reversing: Boolean)

/** Shared ordinary body steering; mech presentation and feet remain in MechMotion. */
internal object BodyMotion {
    fun turn(unit: MutableUnit, target: Position, delta: Double, steering: SteeringPolicy,
             coordinates: CoordinatePolicy, wasReversing: Boolean, allowReverse: Boolean = true,
             alignHeading: Boolean = false): BodyTurn {
        val desired = coordinates.desired(unit.position, target)
        // Native jet inherits bc=.6: its ordinary single-unit Move is a group
        // leader of size one. The source additionally requires 1..2 remaining
        // nodes and a local aim strictly closer than 100 world units.
        val shortJetMove = (unit.amphibiousState != null ||
            unit.definition.transport == com.rustedwarfare.reforge.core.content.TransportProfile.NATIVE_DROPSHIP) && unit.birthRemaining == 0f &&
            unit.tasks.singleOrNull()?.kind == UnitTaskKind.MOVE && unit.pathPoints.size in 1..2 &&
            coordinates.distanceSquared(unit.position, target) < 10000.0
        val reverse = allowReverse && (unit.definition.movement.reverseSpeedPercentage > .95 || shortJetMove) &&
            abs(coordinates.difference(desired, unit.heading)) > 100.0
        if (reverse != wasReversing) unit.combat?.let { it.idleHold = maxOf(25f, it.idleHold) }
        val aim = if (reverse) Position(2.0 * unit.position.x - target.x, 2.0 * unit.position.y - target.y) else target
        val headingBefore = unit.heading
        val difference = steering.turn(unit, aim, delta, coordinates)
        if (alignHeading && abs(coordinates.difference(desired, unit.heading)) < 0.01) {
            unit.heading = desired
            unit.angularVelocity = 0.0
        }
        val applied = coordinates.difference(unit.heading, headingBefore)
        // The native jet holds its independent aim only while it has a combat target.
        val idleJet = unit.amphibiousState != null && unit.primaryCombatTargetId == null
        if (unit.definition.combat?.rotateWithBody == true || idleJet) {
            for (state in unit.namedWeapons?.values ?: listOfNotNull(unit.combat)) {
                state.turretHeading = coordinates.normalize(state.turretHeading + applied)
            }
        }
        return BodyTurn(difference, applied, reverse)
    }

    fun turnOrdinary(unit: MutableUnit, target: Position, delta: Double, steering: SteeringPolicy,
                     coordinates: CoordinatePolicy, allowReverse: Boolean = true, alignHeading: Boolean = false): Double =
        turn(unit, target, delta, steering, coordinates, unit.bodyReversing, allowReverse, alignHeading).also {
            unit.bodyReversing = it.reversing
        }.difference
}
