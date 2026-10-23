package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.sim.Position
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Pure planning-path association only; not a movement collision policy or truth query. */
internal object KnowledgePathImpact {
    fun affectsCenter(position: Position, remaining: List<Position>, target: Position,
                      column: Int, row: Int, tileWidth: Int, tileHeight: Int): Boolean {
        var from = position
        for (to in remaining.ifEmpty { listOf(target) }) {
            if (CenterLineCells.enters(from, to, column, row, tileWidth, tileHeight)) return true
            from = to
        }
        return false
    }

    fun affects(position: Position, remaining: List<Position>, target: Position, radius: Double,
                column: Int, row: Int, tileWidth: Int, tileHeight: Int): Boolean {
        val left = column * tileWidth.toDouble()
        val top = row * tileHeight.toDouble()
        val right = left + tileWidth
        val bottom = top + tileHeight
        var from = position
        for (to in remaining.ifEmpty { listOf(target) }) {
            val magnitude = maxOf(1.0, abs(from.x), abs(from.y), abs(to.x), abs(to.y), abs(left), abs(top), abs(right), abs(bottom))
            // Binary-power scaling preserves exact integer tangency; arbitrary divisors can round a touch into a miss.
            val scale = Math.scalb(1.0, Math.getExponent(magnitude))
            val a = Position(from.x / scale, from.y / scale)
            val b = Position(to.x / scale, to.y / scale)
            // Compare distances, not squares: at extreme scales both squared values underflow.
            if (segmentDistance(a, b, left / scale, top / scale, right / scale, bottom / scale) <= radius / scale) return true
            from = to
        }
        return false
    }

    private fun segmentDistance(first: Position, second: Position, left: Double, top: Double,
                                right: Double, bottom: Double): Double {
        fun pointRectangle(p: Position): Double = hypot(p.x - p.x.coerceIn(left, right), p.y - p.y.coerceIn(top, bottom))
        // Start at the nearby endpoint so a finite local miss is not rounded to t=1 on both axes.
        val a = if (pointRectangle(first) <= pointRectangle(second)) first else second
        val b = if (a === first) second else first
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = hypot(dx, dy)
        if (length == 0.0) return pointRectangle(a)
        var low = 0.0
        var high = 1.0
        fun clip(start: Double, delta: Double, lower: Double, upper: Double): Boolean {
            if (delta == 0.0) return start in lower..upper
            val t0 = (lower - start) / delta
            val t1 = (upper - start) / delta
            low = max(low, min(t0, t1))
            high = min(high, max(t0, t1))
            return low <= high
        }
        if (clip(a.x, dx, left, right) && clip(a.y, dy, top, bottom)) return 0.0
        val ux = dx / length
        val uy = dy / length
        fun cornerSegment(x: Double, y: Double): Double {
            val distance = ((x - a.x) * ux + (y - a.y) * uy).coerceIn(0.0, length)
            return hypot((x - a.x) - distance * ux, (y - a.y) - distance * uy)
        }
        return minOf(pointRectangle(a), pointRectangle(b), cornerSegment(left, top), cornerSegment(right, top),
            cornerSegment(left, bottom), cornerSegment(right, bottom))
    }
}
