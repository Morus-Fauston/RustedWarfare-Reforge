package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.sim.Position
import kotlin.math.ceil
import kotlin.math.min

/**
 * Player-knowledge-isolated read-only navigation terrain contract (ADR-016 / ENG-P017-004).
 *
 * Maintained incrementally per `(playerId, movementType)` without retaining any reference to
 * hidden truth maps. Supplies O(1) Chebyshev clearance rings, multi-band 4-connected component
 * topology, static gate-throat / wide-corridor yield masks, unified radius-aware grid segment
 * clearance raycasts, and cached building-overlap safe escape views.
 */
interface AuthorizedNavigationTerrain {
    val widthTiles: Int
    val heightTiles: Int
    val tileWidth: Int
    val tileHeight: Int

    fun isBlocked(column: Int, row: Int): Boolean

    /** O(1) Chebyshev obstacle clearance in tile rings (`0` if blocked, `1` if touching an obstacle). */
    fun clearanceRings(column: Int, row: Int): Int

    /** Required Chebyshev clearance ring band (`>= 1`) for a circular hull of [radius] world units. */
    fun clearanceBandFor(radius: Double): Int {
        val minTile = min(tileWidth, tileHeight).toDouble()
        if (radius <= minTile * 0.5) return 1
        return 1 + ceil((radius - minTile * 0.5) / minTile).toInt()
    }

    /** True iff cell `(column, row)` is passable and its center lies at least [radius] inside map edges. */
    fun allowsCenter(column: Int, row: Int, radius: Double): Boolean

    /** True iff a disk of [radius] centered at `(column, row)` clears both map edges and all obstacles. */
    fun allowsDiskClearance(column: Int, row: Int, radius: Double): Boolean

    /** O(1) 4-connected component ID under minimum clearance band [minRings] (`0` if below threshold). */
    fun componentId(column: Int, row: Int, minRings: Int = 1): Int

    /** O(1) 4-connected reachability test between two grid cells under minimum clearance band [minRings]. */
    fun isConnected(
        startCol: Int,
        startRow: Int,
        goalCol: Int,
        goalRow: Int,
        minRings: Int = 1,
    ): Boolean

    /** True iff passable cell `(column, row)` lies inside a bounded interior gate throat (`<= 8` tiles wide). */
    fun isGateThroat(column: Int, row: Int): Boolean

    /**
     * Returns `1` for a horizontal 1-tile pinhole (blocked above & below),
     * `2` for a vertical 1-tile pinhole (blocked left & right), or `0` otherwise.
     */
    fun singleTilePinholeAxis(column: Int, row: Int): Int

    /** Unified radius-aware segment clearance check (`clearanceRadius = 0.0` tests exact center-line). */
    fun allowsSegment(
        start: Position,
        end: Position,
        clearanceRadius: Double,
        boundaryRadius: Double = clearanceRadius,
    ): Boolean
}

internal object NavigationTopologyMasks {
    const val MAX_GATE_SPAN_TILES: Int = 8
    val EMPTY_BOOLEAN_ARRAY: BooleanArray = BooleanArray(0)

    fun singleTilePinholeAxis(terrain: AuthorizedNavigationTerrain, col: Int, row: Int): Int {
        val w = terrain.widthTiles
        val h = terrain.heightTiles
        if (col !in 0 until w || row !in 0 until h || terrain.isBlocked(col, row)) return 0
        val upB = row == 0 || terrain.isBlocked(col, row - 1)
        val downB = row == h - 1 || terrain.isBlocked(col, row + 1)
        val leftB = col == 0 || terrain.isBlocked(col - 1, row)
        val rightB = col == w - 1 || terrain.isBlocked(col + 1, row)
        if (upB && downB && !leftB && !rightB) return 1
        if (leftB && rightB && !upB && !downB) return 2
        return 0
    }

    fun isVerticallyBoundedThroat(terrain: AuthorizedNavigationTerrain, c: Int, r: Int): Boolean {
        val h = terrain.heightTiles
        var upDist = -1
        for (d in 1..MAX_GATE_SPAN_TILES) {
            val nr = r - d
            if (nr <= 0 || terrain.isBlocked(c, nr)) {
                if (nr > 0 && terrain.isBlocked(c, nr)) upDist = d
                break
            }
        }
        if (upDist < 0) return false
        var downDist = -1
        for (d in 1..MAX_GATE_SPAN_TILES) {
            val nr = r + d
            if (nr >= h - 1 || terrain.isBlocked(c, nr)) {
                if (nr < h - 1 && terrain.isBlocked(c, nr)) downDist = d
                break
            }
        }
        return downDist > 0 && (upDist + downDist - 1) <= MAX_GATE_SPAN_TILES
    }

    fun isHorizontallyBoundedThroat(terrain: AuthorizedNavigationTerrain, c: Int, r: Int): Boolean {
        val w = terrain.widthTiles
        var leftDist = -1
        for (d in 1..MAX_GATE_SPAN_TILES) {
            val nc = c - d
            if (nc <= 0 || terrain.isBlocked(nc, r)) {
                if (nc > 0 && terrain.isBlocked(nc, r)) leftDist = d
                break
            }
        }
        if (leftDist < 0) return false
        var rightDist = -1
        for (d in 1..MAX_GATE_SPAN_TILES) {
            val nc = c + d
            if (nc >= w - 1 || terrain.isBlocked(nc, r)) {
                if (nc < w - 1 && terrain.isBlocked(nc, r)) rightDist = d
                break
            }
        }
        return rightDist > 0 && (leftDist + rightDist - 1) <= MAX_GATE_SPAN_TILES
    }

    fun computeGateThroatMask(terrain: PlanningTerrain): BooleanArray {
        val w = terrain.widthTiles
        val h = terrain.heightTiles
        val mask = BooleanArray(w * h)
        for (r in 1 until h - 1) {
            val base = r * w
            for (c in 1 until w - 1) {
                if (terrain.isBlocked(c, r)) continue
                if (isVerticallyBoundedThroat(terrain, c, r) || isHorizontallyBoundedThroat(terrain, c, r)) {
                    mask[base + c] = true
                }
            }
        }
        return mask
    }

    fun computeWideYieldMask(
        terrain: PlanningTerrain,
        wideRings: Int,
        throat: BooleanArray,
    ): BooleanArray {
        val w = terrain.widthTiles
        val h = terrain.heightTiles
        val mask = BooleanArray(w * h)
        val cf = terrain.clearanceField()
        for (r in 1 until h - 1) {
            val base = r * w
            for (c in 1 until w - 1) {
                val idx = base + c
                val rings = cf.clearanceRingsAt(idx)
                if (rings == 0) continue
                if (rings in wideRings..(wideRings + 2)) {
                    mask[idx] = true
                } else if (rings == 1 && throat[idx] && singleTilePinholeAxis(terrain, c, r) == 0) {
                    val adjWide =
                        (c > 0 && cf.clearanceRingsAt(idx - 1) >= wideRings) ||
                            (c + 1 < w && cf.clearanceRingsAt(idx + 1) >= wideRings) ||
                            (r > 0 && cf.clearanceRingsAt(idx - w) >= wideRings) ||
                            (r + 1 < h && cf.clearanceRingsAt(idx + w) >= wideRings)
                    if (adjWide) mask[idx] = true
                }
            }
        }
        return mask
    }
}
