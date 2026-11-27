package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.MapBounds

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** Continuous circle sweep against a tile's rounded clearance boundary, with verified contact-normal slide. */
internal class CircularTerrain(
    val radius: Double,
    val boundaryRadius: Double = radius,
) {
    fun resolve(map: HullTerrainGrid, start: Position, end: Position): Position {
        if (start == end || !contains(map, start)) return start
        val dx = end.x - start.x
        val dy = end.y - start.y
        fun edge(value: Double, delta: Double, maximum: Double): Double = when {
            delta > 0.0 -> ((maximum - boundaryRadius - value) / delta).coerceIn(0.0, 1.0)
            delta < 0.0 -> ((boundaryRadius - value) / delta).coerceIn(0.0, 1.0)
            else -> 1.0
        }
        var fraction = minOf(edge(start.x, dx, map.width), edge(start.y, dy, map.height))
        if (fraction == 0.0) return start
        val far = Position(start.x + fraction * dx, start.y + fraction * dy)
        val length = hypot(dx, dy)
        if (length == 0.0) return end
        val rounding = 4.0 * Math.ulp(maxOf(map.width, map.height, radius))
        val reach = radius - rounding
        if (reach <= 0.0) return far
        val drift = Position(dx, dy)

        map.anyBlocked(minOf(start.x, far.x) - radius, maxOf(start.x, far.x) + radius,
            minOf(start.y, far.y) - radius, maxOf(start.y, far.y) + radius) { column, row ->
            val left = column.toDouble() * map.tileWidth
            val right = left + map.tileWidth
            val top = row.toDouble() * map.tileHeight
            val bottom = top + map.tileHeight
            fraction = roundRectangleEntry(start, drift, reach, left, right, top, bottom, fraction)
            fraction == 0.0
        }
        return when (fraction) {
            0.0 -> start
            1.0 -> end
            else -> Position(start.x + fraction * dx, start.y + fraction * dy)
        }
    }

    /**
     * Sweeps [start] -> [end] and, when clipped by a blocked tile face or corner, projects the
     * remaining displacement onto the contact tangent and runs a second verified sweep so circular
     * units glide smoothly along walls and around convex corners with zero penetration.
     */
    fun resolveWithSlide(map: HullTerrainGrid, start: Position, end: Position): Position {
        val first = resolve(map, start, end)
        if (first == end || !contains(map, first)) return first
        val remX = end.x - first.x
        val remY = end.y - first.y
        if (remX * remX + remY * remY <= 1e-18) return first

        var hitNormalX = 0.0
        var hitNormalY = 0.0
        var bestGap = Double.POSITIVE_INFINITY
        val probePad = radius + 2.0
        map.anyBlocked(
            first.x - probePad, first.x + probePad,
            first.y - probePad, first.y + probePad,
        ) { column, row ->
            val left = column.toDouble() * map.tileWidth
            val right = left + map.tileWidth
            val top = row.toDouble() * map.tileHeight
            val bottom = top + map.tileHeight
            val cx = first.x.coerceIn(left, right)
            val cy = first.y.coerceIn(top, bottom)
            val nx = first.x - cx
            val ny = first.y - cy
            val d = hypot(nx, ny)
            val gap = abs(d - radius)
            if (d > 1e-9 && gap <= 1e-3 && (nx * remX + ny * remY) < 0.0 && gap < bestGap) {
                bestGap = gap
                hitNormalX = nx / d
                hitNormalY = ny / d
            }
            false
        }
        if (bestGap.isFinite()) {
            val into = remX * hitNormalX + remY * hitNormalY
            if (into < 0.0) {
                val rounding = 4.0 * Math.ulp(maxOf(map.width, map.height, radius))
                val slideEnd = Position(
                    first.x + (remX - hitNormalX * into) + hitNormalX * (2.0 * rounding),
                    first.y + (remY - hitNormalY * into) + hitNormalY * (2.0 * rounding),
                )
                return resolve(map, first, slideEnd)
            }
        }
        return first
    }

    fun contains(map: HullTerrainGrid, position: Position): Boolean =
        MapBounds.contains(map.width, map.height, position, boundaryRadius)

    companion object {
        fun separatesFrom(start: Position, dx: Double, dy: Double, radius: Double,
                          left: Double, right: Double, top: Double, bottom: Double): Boolean {
            val ox = start.x - start.x.coerceIn(left, right)
            val oy = start.y - start.y.coerceIn(top, bottom)
            if (ox != 0.0 || oy != 0.0) return hypot(ox, oy) < radius && ox * dx + oy * dy >= 0.0

            val nearest = minOf(start.x - left, right - start.x, start.y - top, bottom - start.y)
            return (start.x - left == nearest && dx <= 0.0) || (right - start.x == nearest && dx >= 0.0) ||
                (start.y - top == nearest && dy <= 0.0) || (bottom - start.y == nearest && dy >= 0.0)
        }
    }
}

internal fun roundedRectangleClearance(point: Position, halfLength: Double, halfWidth: Double, radius: Double): Double {
    val x = abs(point.x) - halfLength
    val y = abs(point.y) - halfWidth
    return hypot(x.coerceAtLeast(0.0), y.coerceAtLeast(0.0)) + minOf(0.0, maxOf(x, y)) - radius
}

/** The open interior of a rounded rectangle consists of two strips and four corner disks. */
internal fun roundRectangleEntry(start: Position, drift: Position, radius: Double, left: Double, right: Double,
                                 top: Double, bottom: Double, limit: Double): Double {
    val length = hypot(drift.x, drift.y)
    if (length == 0.0) return limit
    val vx = drift.x / length
    val vy = drift.y / length
    if (CircularTerrain.separatesFrom(start, vx, vy, radius, left, right, top, bottom)) return limit
    if (radius == 0.0) return insideRectangle(start, drift, left, right, top, bottom, limit)

    // A disk has a single corner center and no strips with a nonempty interior.
    var fraction = if (left == right && top == bottom) limit else minOf(limit,
        insideRectangle(start, drift, left - radius, right + radius, top, bottom, limit),
        insideRectangle(start, drift, left, right, top - radius, bottom + radius, limit))
    for (i in 0..(if (left == right) 0 else 1)) for (j in 0..(if (top == bottom) 0 else 1)) {
        val x = if (i == 0) left else right
        val y = if (j == 0) top else bottom
        val toX = x - start.x
        val toY = y - start.y
        val perpendicular = abs(toX * vy - toY * vx)
        if (perpendicular >= radius) continue
        val parallel = toX * vx + toY * vy
        val spread = sqrt((radius - perpendicular) * (radius + perpendicular))
        if (parallel + spread <= 0.0) continue
        fraction = minOf(fraction, (parallel - spread).coerceAtLeast(0.0) / length)
    }
    return fraction
}

private fun insideRectangle(start: Position, drift: Position, left: Double, right: Double,
                            top: Double, bottom: Double, limit: Double): Double {
    var enter = 0.0
    var leave = limit
    fun clip(value: Double, velocity: Double, low: Double, high: Double): Boolean {
        if (velocity == 0.0) return value > low && value < high
        val a = (low - value) / velocity
        val b = (high - value) / velocity
        enter = maxOf(enter, minOf(a, b))
        leave = minOf(leave, maxOf(a, b))
        return enter < leave
    }
    return if (clip(start.x, drift.x, left, right) && clip(start.y, drift.y, top, bottom)) enter else limit
}
