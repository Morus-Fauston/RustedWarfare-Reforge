package com.rustedwarfare.reforge.core.sim

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Wiring for experimental rules; these mappings do not assert original behavior compatibility. */
internal class ExperimentalPolicies(
    val coordinates: CoordinatePolicy = ScreenPlane,
    val terrain: TerrainPolicy = CircleTerrain,
    val steering: SteeringPolicy = AcceleratedSteering,
    val propulsion: PropulsionPolicy = ReferencePropulsion,
    val capture: CapturePolicy = StrictCapture,
    val time: TimePolicy = FixedSimTick,
    val path: PathPolicy = ClearanceAStarPolicy(CenterClearance(terrain), TileCenters, terrain),
)

internal interface CoordinatePolicy {
    fun supports(model: CoordinateModel): Boolean
    fun normalize(heading: Double): Double
    fun difference(desired: Double, heading: Double): Double
    fun desired(position: Position, target: Position): Double
    fun distanceSquared(position: Position, target: Position): Double
    fun displacement(heading: Double, distance: Double): Position
}

internal object ScreenPlane : CoordinatePolicy {
    override fun supports(model: CoordinateModel): Boolean = model.space == "2d" && model.xAxis == "x-right" &&
        model.yAxis == "y-down" && model.origin == "top-left" && model.unit == "world-unit" &&
        model.valueType == "double" && model.precision == "ieee754-binary64"

    override fun normalize(heading: Double): Double = ((heading % 360.0) + 360.0) % 360.0
    override fun difference(desired: Double, heading: Double): Double =
        ((desired - heading + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    override fun desired(position: Position, target: Position): Double =
        normalize(Math.toDegrees(atan2(target.x - position.x, -(target.y - position.y))))
    override fun distanceSquared(position: Position, target: Position): Double =
        (target.x - position.x) * (target.x - position.x) + (target.y - position.y) * (target.y - position.y)
    override fun displacement(heading: Double, distance: Double): Position =
        Position(sin(Math.toRadians(heading)) * distance, -cos(Math.toRadians(heading)) * distance)
}

internal fun interface SteeringPolicy {
    fun turn(unit: MutableUnit, target: Position, delta: Double, coordinates: CoordinatePolicy): Double
}

internal object AcceleratedSteering : SteeringPolicy {
    override fun turn(unit: MutableUnit, target: Position, delta: Double, coordinates: CoordinatePolicy): Double {
        val movement = unit.definition.movement
        val diff = coordinates.difference(coordinates.desired(unit.position, target), unit.heading)
        if (abs(diff) < 0.01) return 0.0
        val direction = if (diff < 0.0) -1.0 else 1.0
        val acceleration = movement.turnAcceleration
        val turn = if (acceleration == 0.0) {
            direction * minOf(movement.maxTurnSpeed * delta, abs(diff))
        } else {
            // Ordinary body-turn branch from the static reference, not its reverse/locked modes.
            val rate = if (abs(diff) < abs(unit.angularVelocity) / acceleration) acceleration else movement.maxTurnSpeed
            unit.angularVelocity = approach(unit.angularVelocity, direction * rate, acceleration * delta)
            val candidate = unit.angularVelocity * delta
            if (abs(candidate) > abs(diff)) {
                unit.angularVelocity = 0.0
                diff
            } else candidate
        }
        unit.heading = coordinates.normalize(unit.heading + turn)
        return diff
    }
}

internal fun interface CapturePolicy {
    fun inside(distanceSquared: Double, radius: Double): Boolean
}

internal object StrictCapture : CapturePolicy {
    override fun inside(distanceSquared: Double, radius: Double): Boolean = distanceSquared < radius * radius
}

internal interface TimePolicy {
    fun supports(model: TimeModel): Boolean
    fun validate(step: Step, model: TimeModel)
}

internal object FixedSimTick : TimePolicy {
    override fun supports(model: TimeModel): Boolean = model.deltaUnit == "sim-tick" &&
        model.precision == "ieee754-binary64" && model.stepRule == "fixed-explicit-delta"
    override fun validate(step: Step, model: TimeModel) {
        require(step.delta == 1.0) { "First-slice delta must be exactly 1.0" }
        require(step.deltaUnit == model.deltaUnit) { "deltaUnit must match the declared time model" }
    }
}

internal fun approach(current: Double, target: Double, amount: Double): Double = when {
    current < target -> (current + amount).coerceAtMost(target)
    current > target -> (current - amount).coerceAtLeast(target)
    else -> current
}
