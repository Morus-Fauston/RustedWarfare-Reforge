package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.util.Collections
import kotlin.math.sqrt

enum class MechKind { TWO_LEGGED, FOUR_LEGGED }

/** Finite LAND gait metadata; the kind distinguishes the two qualified representatives. */
class MechDefinition(val body: MechBodyDefinition, legs: List<MechLegDefinition>,
    val kind: MechKind = MechKind.TWO_LEGGED) {
    val legs: List<MechLegDefinition> = Collections.unmodifiableList(ArrayList(legs))
    internal val neighbours: List<List<Int>> = this.legs.mapIndexed { index, leg ->
        val distances = this.legs.map { other ->
            val dx = (other.x - leg.x) * if (leg.favourOppositeSideNeighbours) .1 else 1.0
            val dy = other.y - leg.y
            dx * dx + dy * dy
        }
        val nearest = distances.indices.filter { it != index }.minOfOrNull { distances[it] }
        if (nearest == null) emptyList() else {
            val limit = sqrt(nearest) + 2.0
            distances.indices.filter { it != index && distances[it] <= limit * limit }
        }
    }

    internal fun isValid(): Boolean = legs.size == (if (kind == MechKind.TWO_LEGGED) 2 else 4) &&
        body.isValid() && legs.all { it.isValid() } &&
        legs.all { it.legImage == legs[0].legImage && it.footImage == legs[0].footImage }
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
    val favourOppositeSideNeighbours: Boolean = false,
) {
    internal fun isValid(): Boolean =
        listOf(x, y, attachX, attachY, moveSpeed, warmUp, rotateSpeed, heightSpeed,
            holdMinimum, holdWithMovingNeighbour, predictionMultiplier, resetAngle,
            liftOffset, targetHeight, hardLimit).all { it.isFinite() } &&
            moveSpeed > 0.0 && warmUp >= 0.0 && rotateSpeed >= 0.0 && heightSpeed > 0.0 &&
            holdMinimum >= 0.0 && holdWithMovingNeighbour >= holdMinimum && predictionMultiplier >= 0.0 &&
            liftOffset >= 0.0 && targetHeight == 0.0 && hardLimit > 0.0
}
