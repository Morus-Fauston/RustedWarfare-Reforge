package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.sim.MapBounds
import com.rustedwarfare.reforge.core.sim.Position
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Single unified radius-aware grid segment clearance & intersection primitive (ENG-P017-004).
 *
 * Replaces the 5 previously duplicated grid segment raycasts (`CenterLineCells`,
 * `ExactCenterLineCells`, legacy center-only `PlanningSegmentClearance`,
 * `NavigationGuidance.gridLineUnblocked` / `segmentClearWithHull`, and
 * `SharedLandingSites.hasPassableTerrainLos`):
 * - `clearanceRadius <= 0.0`: exact half-open cell intersection `[left, right) x [top, bottom)`
 *   (with optional closed outer-map edges and exact binary64 corner tie-breaking).
 * - `clearanceRadius > 0.0`: exact closed-form Euclidean distance between segment `[start, end]`
 *   and obstacle rectangle `[left, right] x [top, bottom]` (zero discretization step loops and
 *   zero heap allocations), querying only candidate blocked words via [BlockedCellIndex].
 */
internal object PlanningSegmentClearance {
    private fun interface CellBlocked {
        fun isBlocked(col: Int, row: Int): Boolean
    }

    private fun interface BlockedRange {
        fun anyBlocked(c0: Int, c1: Int, r0: Int, r1: Int, predicate: (Int, Int) -> Boolean): Boolean
    }

    fun allows(
        map: PlanningTerrain,
        start: Position,
        end: Position,
        boundaryRadius: Double,
        clearanceRadius: Double = 0.0,
    ): Boolean {
        val mapWidth = map.widthTiles.toDouble() * map.tileWidth
        val mapHeight = map.heightTiles.toDouble() * map.tileHeight
        if (!MapBounds.contains(mapWidth, mapHeight, start, boundaryRadius) ||
            !MapBounds.contains(mapWidth, mapHeight, end, boundaryRadius)
        ) return false
        val r = if (clearanceRadius.isFinite() && clearanceRadius > 0.0) clearanceRadius else 0.0
        val firstColumn = floor((minOf(start.x, end.x) - r) / map.tileWidth).toInt().coerceIn(0, map.widthTiles)
        val lastColumn = floor((maxOf(start.x, end.x) + r) / map.tileWidth).toInt().coerceIn(-1, map.widthTiles - 1)
        val firstRow = floor((minOf(start.y, end.y) - r) / map.tileHeight).toInt().coerceIn(0, map.heightTiles)
        val lastRow = floor((maxOf(start.y, end.y) + r) / map.tileHeight).toInt().coerceIn(-1, map.heightTiles - 1)
        if (firstColumn > lastColumn || firstRow > lastRow) return true
        // Exact O(1) Chebyshev clearance-ball fast path on base-resolution grids: clearanceRings(sc, sr) == K
        // guarantees every base cell within Chebyshev distance <= K - 1 tiles of (sc, sr) is unblocked.
        if (!map.isSubdivided) {
            val sc = floor(start.x / map.tileWidth).toInt().coerceIn(0, map.widthTiles - 1)
            val sr = floor(start.y / map.tileHeight).toInt().coerceIn(0, map.heightTiles - 1)
            val maxCheb = max(max(sc - firstColumn, lastColumn - sc), max(sr - firstRow, lastRow - sr))
            if (map.clearanceRings(sc, sr) > maxCheb) return true
        }
        return allows(
            widthTiles = map.widthTiles,
            heightTiles = map.heightTiles,
            tileWidth = map.tileWidth,
            tileHeight = map.tileHeight,
            start = start,
            end = end,
            boundaryRadius = boundaryRadius,
            clearanceRadius = clearanceRadius,
            blocked = CellBlocked { col, row -> map.isBlocked(col, row) },
            candidates = BlockedRange { c0, c1, r0, r1, pred -> map.anyBlocked(c0, c1, r0, r1, pred) },
        )
    }

    /** Swept disk clearance shorthand where obstacle clearance and map-edge inset share [radius]. */
    fun allowsClearance(map: PlanningTerrain, start: Position, end: Position, radius: Double): Boolean =
        allows(map, start, end, boundaryRadius = radius, clearanceRadius = radius)

    /** Grid-cell-to-cell straight line check (replaces `NavigationGuidance.gridLineUnblocked`). */
    fun gridLineUnblocked(map: PlanningTerrain, c0: Int, r0: Int, c1: Int, r1: Int): Boolean {
        if (c0 == c1 && r0 == r1) return !map.isBlocked(c0, r0)
        val start = Position((c0 + 0.5) * map.tileWidth, (r0 + 0.5) * map.tileHeight)
        val end = Position((c1 + 0.5) * map.tileWidth, (r1 + 0.5) * map.tileHeight)
        return allows(map, start, end, boundaryRadius = 0.0, clearanceRadius = 0.0)
    }

    /**
     * Unified hull-aware segment clearance check (consolidates `NavigationGuidance.segmentClearWithHull`
     * and `SharedLandingSites.hasPassableTerrainLos`).
     *
     * Circular hulls are verified in exact closed form via [allows] with `clearanceRadius = terrainR`;
     * rectangular hulls additionally verify oriented pose clearance along the swept segment.
     */
    fun allowsHull(
        terrain: PlanningTerrain,
        hullGrid: HullTerrainGrid?,
        terrainHull: MovementCollisionShape?,
        from: Position,
        to: Position,
        heading: Double,
        boundaryRadius: Double,
        terrainRadius: Double,
    ): Boolean {
        val effClearance = if (terrainHull != null) max(0.0, terrainRadius) else 0.0
        if (!allows(terrain, from, to, boundaryRadius = boundaryRadius, clearanceRadius = effClearance)) return false
        if (terrainHull !is MovementCollisionShape.Rectangle) return true
        val minTile = min(terrain.tileWidth, terrain.tileHeight).toDouble()
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dist = hypot(dx, dy)
        if (dist <= 1e-4) return true
        val stepLen = max(4.0, minTile * 0.45)
        val steps = ceil(dist / stepLen).toInt().coerceAtLeast(1)
        val grid = hullGrid ?: HullTerrainGrid.authorized(terrain)
        for (i in 1..steps) {
            val t = i.toDouble() / steps.toDouble()
            val sample = Position(from.x + dx * t, from.y + dy * t)
            if (!UnitContact.poseFits(terrainHull, sample, heading, grid)) return false
        }
        return true
    }

    /** Trusted movement check only. This truth input is never used to select a planning route. */
    fun allowsMovement(map: MapDefinition, start: Position, end: Position, radius: Double): Boolean =
        allows(
            widthTiles = map.widthTiles,
            heightTiles = map.heightTiles,
            tileWidth = map.tileWidth,
            tileHeight = map.tileHeight,
            start = start,
            end = end,
            boundaryRadius = radius,
            clearanceRadius = 0.0,
            blocked = CellBlocked { col, row -> map.blocking.isBlocked(col, row) },
            candidates = if (map.blocking.widthTiles == map.widthTiles && map.blocking.heightTiles == map.heightTiles)
                BlockedRange { c0, c1, r0, r1, pred -> map.blocking.anyBlocked(c0, c1, r0, r1, pred) } else null,
        )

    private fun allows(
        widthTiles: Int,
        heightTiles: Int,
        tileWidth: Int,
        tileHeight: Int,
        start: Position,
        end: Position,
        boundaryRadius: Double,
        clearanceRadius: Double,
        blocked: CellBlocked,
        candidates: BlockedRange?,
    ): Boolean {
        val mapWidth = widthTiles.toDouble() * tileWidth
        val mapHeight = heightTiles.toDouble() * tileHeight
        if (!MapBounds.contains(mapWidth, mapHeight, start, boundaryRadius) ||
            !MapBounds.contains(mapWidth, mapHeight, end, boundaryRadius)
        ) return false

        val r = if (clearanceRadius.isFinite() && clearanceRadius > 0.0) clearanceRadius else 0.0
        val firstColumn = floor((minOf(start.x, end.x) - r) / tileWidth).toInt().coerceIn(0, widthTiles)
        val lastColumn = floor((maxOf(start.x, end.x) + r) / tileWidth).toInt().coerceIn(-1, widthTiles - 1)
        val firstRow = floor((minOf(start.y, end.y) - r) / tileHeight).toInt().coerceIn(0, heightTiles)
        val lastRow = floor((maxOf(start.y, end.y) + r) / tileHeight).toInt().coerceIn(-1, heightTiles - 1)
        if (firstColumn > lastColumn || firstRow > lastRow) return true
        if (r == 0.0 && firstColumn == lastColumn && firstRow == lastRow) {
            return !blocked.isBlocked(firstColumn, firstRow)
        }
        val boxCols = lastColumn - firstColumn + 1
        val boxRows = lastRow - firstRow + 1
        // For long segments (bounding box > 32 cells), walk a tight band along the segment so
        // blocked cells hit early (in O(length) instead of scanning an O(length^2) AABB).
        // Mathematically equivalent: any cell within distance `r` of `[start, end]` must fall
        // within `[minCoord - r, maxCoord + r]` over the segment's span across that column/row.
        if (boxCols.toLong() * boxRows.toLong() > 32L) {
            return allowsCorridorBand(
                widthTiles = widthTiles,
                heightTiles = heightTiles,
                tileWidth = tileWidth,
                tileHeight = tileHeight,
                start = start,
                end = end,
                r = r,
                mapWidth = mapWidth,
                mapHeight = mapHeight,
                firstColumn = firstColumn,
                lastColumn = lastColumn,
                firstRow = firstRow,
                lastRow = lastRow,
                blocked = blocked,
            )
        }
        if (candidates != null) {
            return !candidates.anyBlocked(firstColumn, lastColumn, firstRow, lastRow) { column, row ->
                if (r == 0.0) entersCell(start, end, column, row, tileWidth, tileHeight)
                else entersCellWithClearance(start, end, r, column, row, tileWidth, tileHeight, mapWidth, mapHeight, allowSeparation = true)
            }
        }
        for (row in firstRow..lastRow) {
            for (column in firstColumn..lastColumn) {
                if (!blocked.isBlocked(column, row)) continue
                val hit = if (r == 0.0) entersCell(start, end, column, row, tileWidth, tileHeight)
                else entersCellWithClearance(start, end, r, column, row, tileWidth, tileHeight, mapWidth, mapHeight, allowSeparation = true)
                if (hit) return false
            }
        }
        return true
    }

    private fun allowsCorridorBand(
        widthTiles: Int,
        heightTiles: Int,
        tileWidth: Int,
        tileHeight: Int,
        start: Position,
        end: Position,
        r: Double,
        mapWidth: Double,
        mapHeight: Double,
        firstColumn: Int,
        lastColumn: Int,
        firstRow: Int,
        lastRow: Int,
        blocked: CellBlocked,
    ): Boolean {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val tw = tileWidth.toDouble()
        val th = tileHeight.toDouble()
        // O(1) midpoint & quarter-point blocked probes when clearance radius > 0 (for r == 0 half-open
        // edge grazing must go through exact `entersCell` tie-breaking instead of raw floor probes).
        if (r > 0.0) {
            val midCol = floor((start.x + dx * 0.5) / tw).toInt().coerceIn(0, widthTiles - 1)
            val midRow = floor((start.y + dy * 0.5) / th).toInt().coerceIn(0, heightTiles - 1)
            if (blocked.isBlocked(midCol, midRow) &&
                entersCellWithClearance(start, end, r, midCol, midRow, tileWidth, tileHeight, mapWidth, mapHeight, allowSeparation = true)
            ) return false
            val q1Col = floor((start.x + dx * 0.25) / tw).toInt().coerceIn(0, widthTiles - 1)
            val q1Row = floor((start.y + dy * 0.25) / th).toInt().coerceIn(0, heightTiles - 1)
            if (blocked.isBlocked(q1Col, q1Row) &&
                entersCellWithClearance(start, end, r, q1Col, q1Row, tileWidth, tileHeight, mapWidth, mapHeight, allowSeparation = true)
            ) return false
            val q3Col = floor((start.x + dx * 0.75) / tw).toInt().coerceIn(0, widthTiles - 1)
            val q3Row = floor((start.y + dy * 0.75) / th).toInt().coerceIn(0, heightTiles - 1)
            if (blocked.isBlocked(q3Col, q3Row) &&
                entersCellWithClearance(start, end, r, q3Col, q3Row, tileWidth, tileHeight, mapWidth, mapHeight, allowSeparation = true)
            ) return false
        }
        val eps = 1e-9
        // Walk from `start` toward `end` along the dominant tile axis so obstacles near `start`
        // reject immediately.
        if (abs(dx) / tw >= abs(dy) / th) {
            val step = if (dx >= 0.0) 1 else -1
            val cStart = if (step > 0) firstColumn else lastColumn
            val cEnd = if (step > 0) lastColumn else firstColumn
            val padY = r + eps
            var col = cStart
            while (true) {
                val cellMinX = col * tw - r
                val cellMaxX = (col + 1) * tw + r
                val t0: Double
                val t1: Double
                if (dx == 0.0) {
                    t0 = 0.0
                    t1 = 1.0
                } else {
                    val ta = (cellMinX - start.x) / dx
                    val tb = (cellMaxX - start.x) / dx
                    t0 = max(0.0, min(ta, tb))
                    t1 = min(1.0, max(ta, tb))
                }
                if (t0 <= t1) {
                    val ya = start.y + dy * t0
                    val yb = start.y + dy * t1
                    val rLo = floor((min(ya, yb) - padY) / th).toInt().coerceIn(firstRow, lastRow)
                    val rHi = floor((max(ya, yb) + padY) / th).toInt().coerceIn(firstRow, lastRow)
                    for (row in rLo..rHi) {
                        if (!blocked.isBlocked(col, row)) continue
                        val hit = if (r == 0.0) entersCell(start, end, col, row, tileWidth, tileHeight)
                        else entersCellWithClearance(start, end, r, col, row, tileWidth, tileHeight, mapWidth, mapHeight, allowSeparation = true)
                        if (hit) return false
                    }
                }
                if (col == cEnd) break
                col += step
            }
        } else {
            val step = if (dy >= 0.0) 1 else -1
            val rStart = if (step > 0) firstRow else lastRow
            val rEnd = if (step > 0) lastRow else firstRow
            val padX = r + eps
            var row = rStart
            while (true) {
                val cellMinY = row * th - r
                val cellMaxY = (row + 1) * th + r
                val t0: Double
                val t1: Double
                if (dy == 0.0) {
                    t0 = 0.0
                    t1 = 1.0
                } else {
                    val ta = (cellMinY - start.y) / dy
                    val tb = (cellMaxY - start.y) / dy
                    t0 = max(0.0, min(ta, tb))
                    t1 = min(1.0, max(ta, tb))
                }
                if (t0 <= t1) {
                    val xa = start.x + dx * t0
                    val xb = start.x + dx * t1
                    val cLo = floor((min(xa, xb) - padX) / tw).toInt().coerceIn(firstColumn, lastColumn)
                    val cHi = floor((max(xa, xb) + padX) / tw).toInt().coerceIn(firstColumn, lastColumn)
                    for (col in cLo..cHi) {
                        if (!blocked.isBlocked(col, row)) continue
                        val hit = if (r == 0.0) entersCell(start, end, col, row, tileWidth, tileHeight)
                        else entersCellWithClearance(start, end, r, col, row, tileWidth, tileHeight, mapWidth, mapHeight, allowSeparation = true)
                        if (hit) return false
                    }
                }
                if (row == rEnd) break
                row += step
            }
        }
        return true
    }

    /**
     * Half-open terrain cell intersection `[left, left + tileWidth) x [top, top + tileHeight)`
     * agreeing with floor-based center membership at all endpoints and corners.
     */
    fun entersCell(
        a: Position,
        b: Position,
        column: Int,
        row: Int,
        tileWidth: Int,
        tileHeight: Int,
    ): Boolean {
        var low = 0.0
        var high = 1.0
        var lowClosed = true
        var highClosed = true
        fun clip(start: Double, delta: Double, lower: Double, upper: Double): Boolean {
            if (delta == 0.0) return start >= lower && start < upper
            val increasing = delta > 0.0
            val enter = ((if (increasing) lower else upper) - start) / delta
            val leave = ((if (increasing) upper else lower) - start) / delta
            val enterClosed = increasing
            val leaveClosed = !increasing
            if (enter > low) {
                low = enter
                lowClosed = enterClosed
            } else if (enter == low) {
                lowClosed = lowClosed && enterClosed
            }
            if (leave < high) {
                high = leave
                highClosed = leaveClosed
            } else if (leave == high) {
                highClosed = highClosed && leaveClosed
            }
            return low < high || (low == high && lowClosed && highClosed)
        }
        val left = column * tileWidth.toDouble()
        val top = row * tileHeight.toDouble()
        return clip(a.x, b.x - a.x, left, left + tileWidth) &&
            clip(a.y, b.y - a.y, top, top + tileHeight)
    }

    /**
     * Exact binary64 segment-to-cell intersection supporting closed outer-map boundaries
     * (`lastColumn` / `lastRow`) for multi-cell high-speed terrain sweeps.
     *
     * Fast-paths non-ambiguous cells in pure `Double` and invokes exact [BigDecimal] cross-multiplication
     * only when `enter` and `leave` lie within 16 ULPs of a corner tie.
     */
    fun entersCellExact(
        a: Position,
        b: Position,
        column: Int,
        row: Int,
        tileWidth: Int,
        tileHeight: Int,
        lastColumn: Boolean,
        lastRow: Boolean,
    ): Boolean {
        val left = column.toDouble() * tileWidth
        val right = (column.toLong() + 1L).toDouble() * tileWidth
        val top = row.toDouble() * tileHeight
        val bottom = (row.toLong() + 1L).toDouble() * tileHeight
        val dx = b.x - a.x
        val dy = b.y - a.y

        var low = 0.0
        var high = 1.0
        var ambiguous = false

        fun fastClip(start: Double, delta: Double, lower: Double, upper: Double, upperClosed: Boolean): Boolean {
            if (delta == 0.0) {
                return start >= lower && (start < upper || (upperClosed && start == upper))
            }
            val increasing = delta > 0.0
            val enter = ((if (increasing) lower else upper) - start) / delta
            val leave = ((if (increasing) upper else lower) - start) / delta
            val tol = 16.0 * Math.ulp(max(1.0, max(abs(enter), abs(leave))))
            if (abs(enter - high) <= tol || abs(leave - low) <= tol ||
                abs(enter) <= tol || abs(enter - 1.0) <= tol ||
                abs(leave) <= tol || abs(leave - 1.0) <= tol
            ) {
                ambiguous = true
            }
            if (enter > low) low = enter
            if (leave < high) high = leave
            return low <= high + tol
        }

        if (!fastClip(a.x, dx, left, right, lastColumn)) return false
        if (!fastClip(a.y, dy, top, bottom, lastRow)) return false
        if (!ambiguous && low < high) return true
        return entersCellExactBigDecimal(a, b, column, row, tileWidth, tileHeight, lastColumn, lastRow)
    }

    private fun entersCellExactBigDecimal(
        start: Position,
        end: Position,
        column: Int,
        row: Int,
        tileWidth: Int,
        tileHeight: Int,
        lastColumn: Boolean,
        lastRow: Boolean,
    ): Boolean {
        val x = BigDecimal(start.x)
        val y = BigDecimal(start.y)
        val dx = BigDecimal(end.x).subtract(x)
        val dy = BigDecimal(end.y).subtract(y)
        var low = ExactTime.ZERO
        var high = ExactTime.ONE
        var lowClosed = true
        var highClosed = true

        fun clip(
            s: BigDecimal,
            delta: BigDecimal,
            lower: BigDecimal,
            upper: BigDecimal,
            upperClosed: Boolean,
        ): Boolean {
            if (delta.signum() == 0) {
                return s >= lower && (s < upper || (upperClosed && s.compareTo(upper) == 0))
            }
            val increasing = delta.signum() > 0
            val enter = ExactTime.of((if (increasing) lower else upper).subtract(s), delta)
            val leave = ExactTime.of((if (increasing) upper else lower).subtract(s), delta)
            val enterClosed = increasing || upperClosed
            val leaveClosed = !increasing || upperClosed
            when (enter.compareTo(low)) {
                1 -> {
                    low = enter
                    lowClosed = enterClosed
                }
                0 -> lowClosed = lowClosed && enterClosed
            }
            when (leave.compareTo(high)) {
                -1 -> {
                    high = leave
                    highClosed = leaveClosed
                }
                0 -> highClosed = highClosed && leaveClosed
            }
            return low < high || (low.compareTo(high) == 0 && lowClosed && highClosed)
        }

        val left = BigDecimal.valueOf(column.toLong() * tileWidth)
        val right = BigDecimal.valueOf((column.toLong() + 1L) * tileWidth)
        val top = BigDecimal.valueOf(row.toLong() * tileHeight)
        val bottom = BigDecimal.valueOf((row.toLong() + 1L) * tileHeight)
        return clip(x, dx, left, right, lastColumn) && clip(y, dy, top, bottom, lastRow)
    }

    /**
     * Returns true iff an open disk of [clearanceRadius] swept from [a] to [b] penetrates
     * terrain cell `(column, row)` (or the center-line itself enters the half-open cell).
     *
     * When [allowSeparation] is true, matches `CircularTerrain.resolve` (`HullRoute.Circle`)
     * by permitting a disk that starts within the rounded collar (`hypot(ox, oy) < reach` outside
     * the hard cell) to move monotonically away (`ox * dx + oy * dy >= 0`).
     */
    fun entersCellWithClearance(
        a: Position,
        b: Position,
        clearanceRadius: Double,
        column: Int,
        row: Int,
        tileWidth: Int,
        tileHeight: Int,
        mapWidth: Double = Double.POSITIVE_INFINITY,
        mapHeight: Double = Double.POSITIVE_INFINITY,
        allowSeparation: Boolean = false,
    ): Boolean {
        if (!clearanceRadius.isFinite() || clearanceRadius <= 0.0) {
            return entersCell(a, b, column, row, tileWidth, tileHeight)
        }
        val scale = if (mapWidth.isFinite() && mapHeight.isFinite()) {
            maxOf(mapWidth, mapHeight, clearanceRadius)
        } else {
            clearanceRadius
        }
        val reach = clearanceRadius - 4.0 * Math.ulp(scale)
        if (reach <= 0.0) return entersCell(a, b, column, row, tileWidth, tileHeight)
        val left = column * tileWidth.toDouble()
        val right = left + tileWidth
        val top = row * tileHeight.toDouble()
        val bottom = top + tileHeight
        val dx = b.x - a.x
        val dy = b.y - a.y
        if (allowSeparation && (dx != 0.0 || dy != 0.0)) {
            if (separatesFromCell(a, dx, dy, reach, left, right, top, bottom)) {
                return false
            }
        }
        if (entersCell(a, b, column, row, tileWidth, tileHeight)) return true
        val minX = minOf(a.x, b.x)
        val maxX = maxOf(a.x, b.x)
        val minY = minOf(a.y, b.y)
        val maxY = maxOf(a.y, b.y)
        if (maxX + reach <= left || minX - reach >= right || maxY + reach <= top || minY - reach >= bottom) {
            return false
        }
        return segmentDistanceSquaredToRect(a, b, left, right, top, bottom) < reach * reach
    }

    private fun separatesFromCell(
        start: Position,
        dx: Double,
        dy: Double,
        reach: Double,
        left: Double,
        right: Double,
        top: Double,
        bottom: Double,
    ): Boolean {
        val len = hypot(dx, dy)
        if (len == 0.0) return false
        val vx = dx / len
        val vy = dy / len
        val ox = start.x - start.x.coerceIn(left, right)
        val oy = start.y - start.y.coerceIn(top, bottom)
        if (ox != 0.0 || oy != 0.0) {
            return hypot(ox, oy) < reach && ox * vx + oy * vy >= 0.0
        }
        val nearest = minOf(start.x - left, right - start.x, start.y - top, bottom - start.y)
        return (start.x - left == nearest && vx <= 0.0) ||
            (right - start.x == nearest && vx >= 0.0) ||
            (start.y - top == nearest && vy <= 0.0) ||
            (bottom - start.y == nearest && vy >= 0.0)
    }

    /**
     * Exact closed-form minimum squared Euclidean distance between segment `[a, b]` and closed
     * axis-aligned rectangle `[left, right] x [top, bottom]` in O(1) with zero allocations.
     */
    fun segmentDistanceSquaredToRect(
        a: Position,
        b: Position,
        left: Double,
        right: Double,
        top: Double,
        bottom: Double,
    ): Double {
        if (segmentIntersectsClosedRect(a, b, left, right, top, bottom)) return 0.0
        var best = min(
            pointDistanceSquaredToRect(a.x, a.y, left, right, top, bottom),
            pointDistanceSquaredToRect(b.x, b.y, left, right, top, bottom),
        )
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 0.0) return best
        best = min(best, pointDistanceSquaredToSegment(left, top, a.x, a.y, dx, dy, lenSq))
        best = min(best, pointDistanceSquaredToSegment(right, top, a.x, a.y, dx, dy, lenSq))
        best = min(best, pointDistanceSquaredToSegment(left, bottom, a.x, a.y, dx, dy, lenSq))
        best = min(best, pointDistanceSquaredToSegment(right, bottom, a.x, a.y, dx, dy, lenSq))
        return best
    }

    private fun segmentIntersectsClosedRect(
        a: Position,
        b: Position,
        left: Double,
        right: Double,
        top: Double,
        bottom: Double,
    ): Boolean {
        var tMin = 0.0
        var tMax = 1.0
        val dx = b.x - a.x
        if (dx == 0.0) {
            if (a.x < left || a.x > right) return false
        } else {
            val t1 = (left - a.x) / dx
            val t2 = (right - a.x) / dx
            tMin = max(tMin, min(t1, t2))
            tMax = min(tMax, max(t1, t2))
            if (tMin > tMax) return false
        }
        val dy = b.y - a.y
        if (dy == 0.0) {
            if (a.y < top || a.y > bottom) return false
        } else {
            val t1 = (top - a.y) / dy
            val t2 = (bottom - a.y) / dy
            tMin = max(tMin, min(t1, t2))
            tMax = min(tMax, max(t1, t2))
            if (tMin > tMax) return false
        }
        return true
    }

    private fun pointDistanceSquaredToRect(
        px: Double,
        py: Double,
        left: Double,
        right: Double,
        top: Double,
        bottom: Double,
    ): Double {
        val cx = px.coerceIn(left, right)
        val cy = py.coerceIn(top, bottom)
        val dx = px - cx
        val dy = py - cy
        return dx * dx + dy * dy
    }

    private fun pointDistanceSquaredToSegment(
        px: Double,
        py: Double,
        ax: Double,
        ay: Double,
        dx: Double,
        dy: Double,
        lenSq: Double,
    ): Double {
        val t = (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        val qx = ax + t * dx - px
        val qy = ay + t * dy - py
        return qx * qx + qy * qy
    }

    private class ExactTime(
        private val numerator: BigDecimal,
        private val denominator: BigDecimal,
    ) : Comparable<ExactTime> {
        override fun compareTo(other: ExactTime): Int =
            numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator))

        companion object {
            val ZERO = ExactTime(BigDecimal.ZERO, BigDecimal.ONE)
            val ONE = ExactTime(BigDecimal.ONE, BigDecimal.ONE)
            fun of(numerator: BigDecimal, denominator: BigDecimal): ExactTime =
                if (denominator.signum() < 0) ExactTime(numerator.negate(), denominator.negate())
                else ExactTime(numerator, denominator)
        }
    }
}
