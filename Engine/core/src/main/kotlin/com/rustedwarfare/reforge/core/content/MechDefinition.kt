package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.util.Collections

/** Two-legged LAND gait and body metadata; weapon capability remains in CombatProfile. */
class MechDefinition(val body: MechBodyDefinition, legs: List<MechLegDefinition>) {
    val legs: List<MechLegDefinition> = Collections.unmodifiableList(ArrayList(legs))

    internal fun isValid(): Boolean = legs.size == 2 && body.isValid() && legs.all { it.isValid() } &&
        legs[0].legImage == legs[1].legImage && legs[0].footImage == legs[1].footImage
}

data class MechBodyDefinition(
    val turnSpeed: Double,
    val turnAcceleration: Double,
    val idleDirection: Double,
    val reverseIdleDirection: Double,
    val sweepAngle: Double,
    val sweepTimeScale: Double,
    val sweepDelay: Double,
    val sweepRandomDelay: Int,
) {
    internal fun isValid(): Boolean =
        listOf(turnSpeed, turnAcceleration, idleDirection, reverseIdleDirection, sweepAngle,
            sweepTimeScale, sweepDelay).all { it.isFinite() } &&
            turnSpeed >= 0.0 && turnAcceleration > 0.0 && sweepAngle in 0.0..180.0 &&
            sweepTimeScale >= 0.0 && sweepDelay >= 0.0 && sweepRandomDelay >= 0
}

data class MechLegDefinition(
    val x: Double,
    val y: Double,
    val attachX: Double,
    val attachY: Double,
    val moveSpeed: Double,
    val warmUp: Double,
    val rotateSpeed: Double,
    val heightSpeed: Double,
    val holdMinimum: Double,
    val holdWithMovingNeighbour: Double,
    val predictionMultiplier: Double,
    val legImage: Path,
    val footImage: Path,
    /** Parsed reference metadata; not used by the ordinary leg update. */
    val resetAngle: Double,
    val liftOffset: Double = 2.0,
    val targetHeight: Double = 0.0,
    val hardLimit: Double = 50.0,
) {
    internal fun isValid(): Boolean =
        listOf(x, y, attachX, attachY, moveSpeed, warmUp, rotateSpeed, heightSpeed,
            holdMinimum, holdWithMovingNeighbour, predictionMultiplier, resetAngle,
            liftOffset, targetHeight, hardLimit).all { it.isFinite() } &&
            moveSpeed > 0.0 && warmUp >= 0.0 && rotateSpeed >= 0.0 && heightSpeed > 0.0 &&
            holdMinimum >= 0.0 && holdWithMovingNeighbour >= holdMinimum && predictionMultiplier >= 0.0 &&
            liftOffset >= 0.0 && targetHeight == 0.0 && hardLimit > 0.0
}
