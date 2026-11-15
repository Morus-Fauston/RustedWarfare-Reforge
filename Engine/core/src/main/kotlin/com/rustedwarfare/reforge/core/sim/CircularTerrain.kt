package com.rustedwarfare.reforge.core.sim

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** Continuous circle sweep against a tile's rounded clearance boundary. */
internal class CircularTerrain(private val radius: Double) {
    fun resolve(map: HullTerrainGrid, start: Position, end: Position): Position {
        if (start == end || !contains(map, start)) return start
        val dx = end.x - start.x
        val dy = end.y - start.y
        fun edge(value: Double, delta: Double, maximum: Double): Double = when {
            delta > 0.0 -> ((maximum - radius - value) / delta).coerceIn(0.0, 1.0)
            delta < 0.0 -> ((radius - value) / delta).coerceIn(0.0, 1.0)
            else -> 1.0
        }
        var fraction = minOf(edge(start.x, dx, map.width), edge(start.y, dy, map.height))
        if (fraction == 0.0) return start
        val far = Position(start.x + fraction * dx, start.y + fraction * dy)
        val length = hypot(dx, dy)
        if (length == 0.0) return end
        val vx = dx / length
        val vy = dy / length
        val rounding = 4.0 * Math.ulp(maxOf(map.width, map.height, radius))
        val reach = radius - rounding
        if (reach <= 0.0) return far

        map.anyBlocked(minOf(start.x, far.x) - radius, maxOf(start.x, far.x) + radius,
            minOf(start.y, far.y) - radius, maxOf(start.y, far.y) + radius) { column, row ->
            val left = column.toDouble() * map.tileWidth
            val right = left + map.tileWidth
            val top = row.toDouble() * map.tileHeight
            val bottom = top + map.tileHeight
            if (separatesFrom(start, vx, vy, reach, left, right, top, bottom)) return@anyBlocked false

            // Two strips and four corner disks form the open interior of the rounded tile.
            fraction = minOf(fraction, insideRectangle(start, dx, dy, left - reach, right + reach, top, bottom, fraction),
                insideRectangle(start, dx, dy, left, right, top - reach, bottom + reach, fraction))
            for (x in listOf(left, right)) for (y in listOf(top, bottom)) {
                val toX = x - start.x
                val toY = y - start.y
                val perpendicular = abs(toX * vy - toY * vx)
                if (perpendicular >= reach) continue
                val parallel = toX * vx + toY * vy
                val spread = sqrt((reach - perpendicular) * (reach + perpendicular))
                if (parallel + spread <= 0.0) continue
                fraction = minOf(fraction, (parallel - spread).coerceAtLeast(0.0) / length)
            }
            fraction == 0.0
        }
        return when (fraction) {
            0.0 -> start
            1.0 -> end
            else -> Position(start.x + fraction * dx, start.y + fraction * dy)
        }
    }

    fun contains(map: HullTerrainGrid, position: Position): Boolean =
        MapBounds.contains(map.width, map.height, position, radius)

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

    private fun insideRectangle(start: Position, dx: Double, dy: Double, left: Double, right: Double,
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
        return if (clip(start.x, dx, left, right) && clip(start.y, dy, top, bottom)) enter else limit
    }
}
