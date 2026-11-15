package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import kotlin.math.abs

/** Sweeps a centered rectangle at constant heading against the map edge and solid cells. */
internal class RectangularTerrain(shape: MovementCollisionShape.Rectangle, heading: Double) {
    private val halfLength = shape.length / 2.0
    private val halfWidth = shape.width / 2.0
    private val forward = ScreenPlane.displacement(heading, 1.0)
    private val right = Position(-forward.y, forward.x)
    private val extentX = halfLength * abs(forward.x) + halfWidth * abs(right.x)
    private val extentY = halfLength * abs(forward.y) + halfWidth * abs(right.y)

    fun resolve(map: HullTerrainGrid, start: Position, end: Position): Position {
        if (start == end) return start
        val drift = Position(end.x - start.x, end.y - start.y)
        var fraction = minOf(boundaryFraction(start.x, drift.x, extentX, map.width),
            boundaryFraction(start.y, drift.y, extentY, map.height))
        if (fraction > 0.0) {
            val far = interpolate(start, drift, fraction)
            val halfTileWidth = map.tileWidth / 2.0
            val halfTileHeight = map.tileHeight / 2.0
            val projections = listOf(
                Projection(Position(1.0, 0.0), extentX + halfTileWidth),
                Projection(Position(0.0, 1.0), extentY + halfTileHeight),
                Projection(forward, halfLength + abs(forward.x) * halfTileWidth + abs(forward.y) * halfTileHeight),
                Projection(right, halfWidth + abs(right.x) * halfTileWidth + abs(right.y) * halfTileHeight),
            )
            // Subtracting represented positions can perturb an exactly sideways projection of the drift.
            val coordinateRoundoff = 4.0 * Math.ulp(maxOf(abs(start.x), abs(start.y), abs(end.x), abs(end.y)))
            map.anyBlocked(minOf(start.x, far.x) - extentX, maxOf(start.x, far.x) + extentX,
                minOf(start.y, far.y) - extentY, maxOf(start.y, far.y) + extentY) { x, y ->
                val fromCell = Position(start.x - (x + 0.5) * map.tileWidth, start.y - (y + 0.5) * map.tileHeight)
                fraction = minOf(fraction, entry(projections, fromCell, drift, fraction, coordinateRoundoff))
                fraction == 0.0
            }
        }
        return when (fraction) {
            0.0 -> start
            1.0 -> end
            else -> interpolate(start, drift, fraction)
        }
    }

    fun contains(map: HullTerrainGrid, position: Position): Boolean = contains(map.width, map.height, position)

    fun contains(width: Double, height: Double, position: Position): Boolean =
        position.x >= extentX && position.x <= width - extentX &&
            position.y >= extentY && position.y <= height - extentY

    private fun boundaryFraction(start: Double, drift: Double, extent: Double, maximum: Double): Double {
        if (start < extent || start > maximum - extent) return 0.0
        return when {
            drift > 0.0 -> ((maximum - extent - start) / drift).coerceIn(0.0, 1.0)
            drift < 0.0 -> ((extent - start) / drift).coerceIn(0.0, 1.0)
            else -> 1.0
        }
    }

    private data class Projection(val axis: Position, val reach: Double) {
        fun dot(point: Position): Double = axis.x * point.x + axis.y * point.y
    }

    private fun entry(projections: List<Projection>, offset: Position, drift: Position, limit: Double,
                      coordinateRoundoff: Double): Double {
        var enter = 0.0
        var leave = limit
        var leastOverlap = Double.POSITIVE_INFINITY
        var leastOverlapCannotIncrease = false
        var exitsOnAnAxis = false
        for (projection in projections) {
            val start = projection.dot(offset)
            val projectedDrift = projection.dot(drift)
            val speed = if (abs(projectedDrift) <= coordinateRoundoff) 0.0 else projectedDrift
            // Preserve touching as nonpenetrating despite projection roundoff at cardinal headings.
            val reach = projection.reach - 4.0 * Math.ulp(projection.reach)
            val overlap = reach - abs(start)
            val movingOut = speed != 0.0 && (start == 0.0 || (start > 0.0) == (speed > 0.0))
            exitsOnAnAxis = exitsOnAnAxis || movingOut
            if (overlap < leastOverlap) {
                leastOverlap = overlap
                leastOverlapCannotIncrease = speed == 0.0 || movingOut
            } else if (overlap == leastOverlap) {
                leastOverlapCannotIncrease = leastOverlapCannotIncrease || speed == 0.0 || movingOut
            }
            if (speed == 0.0) {
                if (overlap <= 0.0) return limit
                continue
            }
            val near = (-reach - start) / speed
            val far = (reach - start) / speed
            enter = maxOf(enter, minOf(near, far))
            leave = minOf(leave, maxOf(near, far))
            if (enter >= leave) return limit
        }
        if (leastOverlap > 0.0 && leastOverlapCannotIncrease && exitsOnAnAxis) return limit
        return enter
    }

    private fun interpolate(start: Position, drift: Position, fraction: Double): Position =
        Position(start.x + drift.x * fraction, start.y + drift.y * fraction)
}
