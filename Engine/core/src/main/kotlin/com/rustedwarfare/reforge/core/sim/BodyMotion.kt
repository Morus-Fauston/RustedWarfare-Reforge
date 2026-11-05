package com.rustedwarfare.reforge.core.sim

import kotlin.math.abs

internal data class BodyTurn(val difference: Double, val applied: Double, val reversing: Boolean)

/** Shared ordinary body steering; mech presentation and feet remain in MechMotion. */
internal object BodyMotion {
    fun turn(unit: MutableUnit, target: Position, delta: Double, steering: SteeringPolicy,
             coordinates: CoordinatePolicy, wasReversing: Boolean): BodyTurn {
        val desired = coordinates.desired(unit.position, target)
        val reverse = unit.definition.movement.reverseSpeedPercentage > .95 &&
            abs(coordinates.difference(desired, unit.heading)) > 100.0
        if (reverse != wasReversing) unit.combat?.let { it.idleHold = maxOf(25f, it.idleHold) }
        val aim = if (reverse) Position(2.0 * unit.position.x - target.x, 2.0 * unit.position.y - target.y) else target
        val headingBefore = unit.heading
        val difference = steering.turn(unit, aim, delta, coordinates)
        val applied = coordinates.difference(unit.heading, headingBefore)
        if (unit.definition.combat?.rotateWithBody == true) {
            for (state in unit.namedWeapons?.values ?: listOfNotNull(unit.combat)) {
                state.turretHeading = coordinates.normalize(state.turretHeading + applied)
            }
        }
        return BodyTurn(difference, applied, reverse)
    }

    fun turnOrdinary(unit: MutableUnit, target: Position, delta: Double, steering: SteeringPolicy,
                     coordinates: CoordinatePolicy): Double =
        turn(unit, target, delta, steering, coordinates, unit.bodyReversing).also {
            unit.bodyReversing = it.reversing
        }.difference
}
