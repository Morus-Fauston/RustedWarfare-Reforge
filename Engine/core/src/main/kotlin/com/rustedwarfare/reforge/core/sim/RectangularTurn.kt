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
        if (change == 0.0) {
            // Canonical cardinal alignment can differ from the integrated angle by a few ulps.
            return if (requested == from || UnitContact.poseFits(shape, center, requested, map)) requested else from
        }
        val arc = Arc(Math.toRadians(from), Math.toRadians(change))
        val halfLength = shape.length / 2.0
        val halfWidth = shape.width / 2.0
        val edgeX = minOf(center.x, map.width - center.x)
        val edgeY = minOf(center.y, map.height - center.y)
        var fraction = arc.firstMatch(1.0) { s, c -> listOf(Crossing(halfLength * s, halfWidth * c, edgeX)) }
        fraction = arc.firstMatch(fraction) { s, c -> listOf(Crossing(halfWidth * s, halfLength * c, edgeY)) }
        if (fraction > 0.0) {
            val reach = hypot(halfLength, halfWidth)
            val tileHalfWidth = map.tileWidth / 2.0
            val tileHalfHeight = map.tileHeight / 2.0
            map.anyBlocked(center.x - reach, center.x + reach, center.y - reach, center.y + reach) { column, row ->
                val dx = center.x - (column + 0.5) * map.tileWidth
                val dy = center.y - (row + 0.5) * map.tileHeight
                // A protected factory exit can already touch its producer. A turn must not deepen that overlap.
                fraction = arc.firstRectangleOverlap(fraction, halfLength, halfWidth, dx, dy, tileHalfWidth, tileHalfHeight)
                fraction == 0.0
            }
        }
        fun at(progress: Double) =
            if (progress == 1.0) requested else ScreenPlane.normalize(from + change * progress)
        fun fits(heading: Double) = UnitContact.poseFits(shape, center, heading, map)
        val heading = at(fraction)
        if (heading != requested && ScreenPlane.difference(requested, heading) == 0.0 &&
            UnitContact.poseFits(shape, center, requested, map)) return requested
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

    fun againstRectangle(shape: MovementCollisionShape.Rectangle, other: MovementCollisionShape.Rectangle,
                         offset: Position, otherHeading: Double, from: Double, requested: Double): Double {
        val change = ScreenPlane.difference(requested, from)
        if (change == 0.0) return from
        val forward = ScreenPlane.displacement(otherHeading, 1.0)
        val dx = offset.y * forward.x - offset.x * forward.y
        val dy = -offset.x * forward.x - offset.y * forward.y
        val localFrom = ScreenPlane.normalize(from - otherHeading)
        val arc = Arc(Math.toRadians(localFrom), Math.toRadians(change))
        val fraction = arc.firstRectangleOverlap(1.0, shape.length / 2.0, shape.width / 2.0,
            dx, dy, other.width / 2.0, other.length / 2.0, stopAtReentry = true)
        return if (fraction == 1.0) requested else ScreenPlane.normalize(from + fraction * change)
    }

    fun pointWithinMap(center: Position, offset: Position, radius: Double, width: Double, height: Double,
                       from: Double, requested: Double, fits: (Double) -> Boolean): Double {
        val change = ScreenPlane.difference(requested, from)
        if (change == 0.0) return requested
        val arc = Arc(Math.toRadians(from), Math.toRadians(change))
        val left = Crossing(-offset.y, -offset.x, center.x - radius, false)
        val right = Crossing(offset.y, offset.x, width - radius - center.x, false)
        val top = Crossing(-offset.x, offset.y, center.y - radius, false)
        val bottom = Crossing(offset.x, -offset.y, height - radius - center.y, false)
        val edges = listOf(left, right, top, bottom)
        val fraction = arc.firstWhere(0.0, 1.0, edges) { sine, cosine -> edges.any { it.contains(sine, cosine) } }
        fun at(progress: Double) =
            if (progress == 1.0) requested else ScreenPlane.normalize(from + progress * change)
        val heading = at(fraction)
        if (fits(heading)) return heading
        var low = 0.0
        var high = fraction
        repeat(52) {
            val middle = (low + high) / 2.0
            if (fits(at(middle))) low = middle else high = middle
        }
        return at(low)
    }

    fun againstCircle(shape: MovementCollisionShape.Rectangle, radius: Double, offset: Position,
                      from: Double, requested: Double): Double {
        val change = ScreenPlane.difference(requested, from)
        if (change == 0.0) return from
        val arc = Arc(Math.toRadians(from), Math.toRadians(change))
        val x = offset.x
        val y = offset.y
        fun local(sine: Double, cosine: Double) = Position(x * sine - y * cosine, x * cosine + y * sine)
        val halfLength = shape.length / 2.0
        val halfWidth = shape.width / 2.0
        val startingClearance = roundedRectangleClearance(local(sin(arc.start), cos(arc.start)), halfLength, halfWidth, radius)
        val rounding = 4.0 * Math.ulp(maxOf(halfLength, halfWidth, radius, abs(x), abs(y)))
        val fraction = contactLimit(1.0, (-startingClearance).coerceAtLeast(0.0)) { begin, end, penetration, overlaps ->
            val adjustedRadius = radius - penetration - rounding
            val reach = maxOf(0.0, adjustedRadius)
            val length = halfLength + minOf(0.0, adjustedRadius)
            val width = halfWidth + minOf(0.0, adjustedRadius)
            if (length <= 0.0 || width <= 0.0) return@contactLimit if (overlaps) end else begin
            val sides = mutableListOf(
                Crossing(x, -y, length + reach, false), Crossing(-x, y, length + reach, false),
                Crossing(y, x, width + reach, false), Crossing(-y, -x, width + reach, false),
            )
            if (reach > 0.0) {
                // The distance to the moving center is constant along the arc, so the
                // corner roots are also projection thresholds in sine and cosine.
                val distance = (x * x + y * y + length * length + width * width - reach * reach) / 2.0
                for (a in listOf(-length, length)) for (b in listOf(-width, width)) {
                    sides += Crossing(a * x + b * y, -a * y + b * x, distance, false)
                }
            }
            arc.firstWhere(begin, end, sides) { sine, cosine ->
                val inside = roundedRectangleClearance(local(sine, cosine), length, width, reach) < 0.0
                inside == overlaps
            }
        }
        return if (fraction == 1.0) requested else ScreenPlane.normalize(from + fraction * change)
    }

    /** The initial overlap permits only the prefix up to the first fully separated pose. */
    private fun contactLimit(limit: Double, initialOverlap: Double,
                             firstMatch: (Double, Double, Double, Boolean) -> Double): Double {
        val cap = firstMatch(0.0, limit, initialOverlap, true)
        if (initialOverlap == 0.0) return cap
        val cleared = firstMatch(0.0, cap, 0.0, false)
        return if (cleared == cap) cap else firstMatch(cleared, cap, 0.0, true)
    }

    private class Arc(val start: Double, private val change: Double) {
        private val quadrants = (-3..6).map { (it * PI / 2.0 - start) / change }.filter { it > 0.0 && it < 1.0 }.sorted()

        fun firstRectangleOverlap(limit: Double, halfLength: Double, halfWidth: Double,
                                  dx: Double, dy: Double, otherWidth: Double, otherHeight: Double,
                                  stopAtReentry: Boolean = false): Double {
            val sine = sin(start)
            val cosine = cos(start)
            val overlap = minOf(
                halfLength * abs(sine) + halfWidth * abs(cosine) + otherWidth - abs(dx),
                halfWidth * abs(sine) + halfLength * abs(cosine) + otherHeight - abs(dy),
                halfLength + otherWidth * abs(sine) + otherHeight * abs(cosine) - abs(dx * sine - dy * cosine),
                halfWidth + otherWidth * abs(cosine) + otherHeight * abs(sine) - abs(dx * cosine + dy * sine),
            ).coerceAtLeast(0.0)
            fun match(begin: Double, end: Double, penetration: Double, overlaps: Boolean) =
                firstMatch(end, begin, overlaps) { s, c ->
                    listOf(
                        Crossing(halfLength * s, halfWidth * c, abs(dx) - otherWidth + penetration),
                        Crossing(halfWidth * s, halfLength * c, abs(dy) - otherHeight + penetration),
                        Crossing(otherWidth * s - dx, otherHeight * c + dy, -halfLength + penetration),
                        Crossing(otherWidth * s + dx, otherHeight * c - dy, -halfLength + penetration),
                        Crossing(otherHeight * s - dy, otherWidth * c - dx, -halfWidth + penetration),
                        Crossing(otherHeight * s + dy, otherWidth * c + dx, -halfWidth + penetration),
                    )
                }
            return if (stopAtReentry) contactLimit(limit, overlap, ::match) else match(0.0, limit, overlap, true)
        }

        fun firstMatch(limit: Double, begin: Double = 0.0, overlaps: Boolean = true,
                       constraints: (Double, Double) -> List<Crossing>): Double {
            val split = listOf(begin) + quadrants.filter { it > begin && it < limit } + limit
            for ((low, high) in split.zipWithNext()) {
                if (high <= low) continue
                val middle = start + change * ((low + high) / 2.0)
                val axes = constraints(if (sin(middle) >= 0.0) 1.0 else -1.0, if (cos(middle) >= 0.0) 1.0 else -1.0)
                val match = firstWhere(low, high, axes) { sine, cosine -> axes.all { it.contains(sine, cosine) } == overlaps }
                if (match < high) return match
            }
            return limit
        }

        fun firstWhere(begin: Double, end: Double, boundaries: List<Crossing>,
                       contains: (Double, Double) -> Boolean): Double {
            val candidates = mutableListOf(begin, end)
            for (boundary in boundaries) boundary.addCrossings(start, change, begin, end, candidates)
            candidates.sort()
            for ((low, high) in candidates.zipWithNext()) {
                if (low == high) continue
                val angle = start + change * ((low + high) / 2.0)
                if (contains(sin(angle), cos(angle))) return low
            }
            return end
        }
    }

    private class Crossing(private val sine: Double, private val cosine: Double, threshold: Double,
                           tolerateRoundoff: Boolean = true) {
        // Avoid classifying roundoff at a tangent as positive penetration.
        private val limit = if (tolerateRoundoff) threshold + 4.0 * Math.ulp(maxOf(abs(threshold), abs(sine), abs(cosine)))
            else threshold
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
