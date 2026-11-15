package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Continuous in-place rotation. On each quadrant, all four SAT projections reduce to sine inequalities. */
internal object RectangularTurn {
    fun resolve(map: HullTerrainGrid, shape: MovementCollisionShape.Rectangle,
                center: Position, from: Double, requested: Double): Double {
        val change = ScreenPlane.difference(requested, from)
        if (change == 0.0) return from
        val arc = Arc(Math.toRadians(from), Math.toRadians(change))
        val halfLength = shape.length / 2.0
        val halfWidth = shape.width / 2.0
        val edgeX = minOf(center.x, map.width - center.x)
        val edgeY = minOf(center.y, map.height - center.y)
        var fraction = arc.firstOverlap(1.0) { s, c -> listOf(Crossing(halfLength * s, halfWidth * c, edgeX)) }
        fraction = arc.firstOverlap(fraction) { s, c -> listOf(Crossing(halfWidth * s, halfLength * c, edgeY)) }
        if (fraction > 0.0) {
            val reach = hypot(halfLength, halfWidth)
            val tileHalfWidth = map.tileWidth / 2.0
            val tileHalfHeight = map.tileHeight / 2.0
            map.anyBlocked(center.x - reach, center.x + reach, center.y - reach, center.y + reach) { column, row ->
                val dx = center.x - (column + 0.5) * map.tileWidth
                val dy = center.y - (row + 0.5) * map.tileHeight
                val sine = sin(arc.start)
                val cosine = cos(arc.start)
                val startingOverlap = minOf(
                    halfLength * abs(sine) + halfWidth * abs(cosine) + tileHalfWidth - abs(dx),
                    halfWidth * abs(sine) + halfLength * abs(cosine) + tileHalfHeight - abs(dy),
                    halfLength + tileHalfWidth * abs(sine) + tileHalfHeight * abs(cosine) - abs(dx * sine - dy * cosine),
                    halfWidth + tileHalfWidth * abs(cosine) + tileHalfHeight * abs(sine) - abs(dx * cosine + dy * sine),
                ).coerceAtLeast(0.0)
                // A protected factory exit can already touch its producer. A turn must not deepen that overlap.
                fraction = arc.firstOverlap(fraction) { s, c ->
                    listOf(
                        Crossing(halfLength * s, halfWidth * c, abs(dx) - tileHalfWidth + startingOverlap),
                        Crossing(halfWidth * s, halfLength * c, abs(dy) - tileHalfHeight + startingOverlap),
                        Crossing(tileHalfWidth * s - dx, tileHalfHeight * c + dy, -halfLength + startingOverlap),
                        Crossing(tileHalfWidth * s + dx, tileHalfHeight * c - dy, -halfLength + startingOverlap),
                        Crossing(tileHalfHeight * s - dy, tileHalfWidth * c - dx, -halfWidth + startingOverlap),
                        Crossing(tileHalfHeight * s + dy, tileHalfWidth * c + dx, -halfWidth + startingOverlap),
                    )
                }
                fraction == 0.0
            }
        }
        fun at(progress: Double) =
            if (progress == 1.0) requested else ScreenPlane.normalize(from + change * progress)
        fun fits(heading: Double) = RectangularTerrain(shape, heading).contains(map, center)
        val heading = at(fraction)
        if (fits(heading)) return heading

        // A trigonometric root can round to the outside of the closed map edge.
        var legal = 0.0
        var illegal = fraction
        repeat(52) {
            val middle = (legal + illegal) / 2.0
            if (fits(at(middle))) legal = middle else illegal = middle
        }
        return at(legal)
    }

    private class Arc(val start: Double, private val change: Double) {
        private val quadrants = (-3..6).map { (it * PI / 2.0 - start) / change }.filter { it > 0.0 && it < 1.0 }.sorted()

        fun firstOverlap(limit: Double, constraints: (Double, Double) -> List<Crossing>): Double {
            val split = listOf(0.0) + quadrants.filter { it < limit } + limit
            for ((begin, end) in split.zipWithNext()) {
                if (end <= begin) continue
                val middle = start + change * ((begin + end) / 2.0)
                val axes = constraints(if (sin(middle) >= 0.0) 1.0 else -1.0, if (cos(middle) >= 0.0) 1.0 else -1.0)
                val candidates = mutableListOf(begin, end)
                for (axis in axes) axis.addCrossings(start, change, begin, end, candidates)
                candidates.sort()
                for ((low, high) in candidates.zipWithNext()) {
                    if (low == high) continue
                    val angle = start + change * ((low + high) / 2.0)
                    val sine = sin(angle)
                    val cosine = cos(angle)
                    if (axes.all { it.contains(sine, cosine) }) return low
                }
            }
            return limit
        }
    }

    private class Crossing(private val sine: Double, private val cosine: Double, threshold: Double) {
        // Avoid classifying roundoff at a tangent as positive penetration.
        private val limit = threshold + 4.0 * Math.ulp(maxOf(abs(threshold), abs(sine), abs(cosine)))
        fun contains(s: Double, c: Double): Boolean = sine * s + cosine * c > limit

        fun addCrossings(start: Double, change: Double, begin: Double, end: Double, points: MutableList<Double>) {
            val radius = hypot(sine, cosine)
            if (radius == 0.0 || abs(limit) >= radius) return
            val phase = atan2(cosine, sine)
            val principal = asin(limit / radius)
            for (root in listOf(principal - phase, PI - principal - phase)) {
                for (cycle in -2..2) {
                    val t = (root + cycle * (2.0 * PI) - start) / change
                    if (t > begin && t < end) points += t
                }
            }
        }
    }
}
