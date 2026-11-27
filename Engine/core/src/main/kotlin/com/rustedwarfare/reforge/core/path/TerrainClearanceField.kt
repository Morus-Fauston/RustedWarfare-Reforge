package com.rustedwarfare.reforge.core.path

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Immutable terrain clearance distance field and lazy 4-connected component topology attached to
 * a [PlanningTerrain] snapshot.
 *
 * - [clearance] holds per-cell Chebyshev (L-infinity) distance in tiles to the **nearest blocked
 *   obstacle cell only** (map borders are checked separately by [allowsCenter] / [allowsDiskClearance]):
 *   - `0`: cell itself is blocked
 *   - `1`: cell is passable and at least one 8-neighbor is blocked
 *   - `k`: all in-bounds cells within Chebyshev distance `<= k - 1` are passable
 *   - [MAX_CLEARANCE] (`32767`): no blocked cell exists within 32766 tiles (e.g. completely open map)
 * - [baseComponents] and [bandComponents] are computed lazily on first connectivity query so
 *   intermediate terrain snapshots that are never queried pay zero full-map BFS cost.
 *
 * Incremental updates ([withChangedCells]) copy the `ShortArray` and run a bounded local brushfire
 * pass over dirty neighborhoods without rescanning the entire map.
 */
internal class TerrainClearanceField private constructor(
    val width: Int,
    val height: Int,
    private val clearance: ShortArray,
    precomputedBaseComponents: IntArray? = null,
) {
    private val componentCacheLock = Any()
    @Volatile
    private var baseComponents: IntArray? = precomputedBaseComponents
    private var bandComponents: HashMap<Int, IntArray>? = null

    /**
     * Exact Chebyshev clearance in tile rings to the nearest blocked obstacle cell (excluding map
     * borders; map border containment is checked separately via [allowsCenter]).
     */
    fun clearanceRings(col: Int, row: Int): Int =
        clearance[row * width + col].toInt() and 0xFFFF

    fun clearanceRingsAt(index: Int): Int =
        clearance[index].toInt() and 0xFFFF

    /**
     * Returns true iff cell `(col, row)` is passable and its center `(col + 0.5)*tileWidth, (row + 0.5)*tileHeight`
     * lies within map bounds inset by [radius], matching [AuthorizedNavigationTerrain.allowsCenter].
     */
    fun allowsCenter(col: Int, row: Int, tileWidth: Int, tileHeight: Int, radius: Double): Boolean {
        if (!radius.isFinite() || radius < 0.0) return false
        val idx = row * width + col
        if (clearance[idx].toInt() == 0) return false
        val cx = (col + 0.5) * tileWidth
        val cy = (row + 0.5) * tileHeight
        val mapW = width.toDouble() * tileWidth
        val mapH = height.toDouble() * tileHeight
        return cx >= radius && cy >= radius && cx <= mapW - radius && cy <= mapH - radius
    }

    /**
     * Strict physical disk clearance check in O(1): returns true when a disk of [radius] centered
     * at `(col, row)` clears both map edges ([allowsCenter]) and all blocked obstacle tiles
     * ([clearanceRings] >= required Chebyshev rings).
     */
    fun allowsDiskClearance(col: Int, row: Int, tileWidth: Int, tileHeight: Int, radius: Double): Boolean {
        if (!allowsCenter(col, row, tileWidth, tileHeight, radius)) return false
        val minTile = min(tileWidth, tileHeight).toDouble()
        val extraRings = if (radius <= minTile * 0.5) 0 else ceil((radius - minTile * 0.5) / minTile).toInt()
        val required = 1 + extraRings
        return clearanceRings(col, row) >= required
    }

    private fun ensureBaseComponents(): IntArray {
        baseComponents?.let { return it }
        synchronized(componentCacheLock) {
            baseComponents?.let { return it }
            return computeComponents(width, height, clearance, 1).also { baseComponents = it }
        }
    }

    /**
     * Returns the 4-connected component id for cell `(col, row)` on the base passability graph
     * (`clearance >= 1`), or `0` if blocked.
     */
    fun componentId(col: Int, row: Int): Int = ensureBaseComponents()[row * width + col]

    /**
     * Returns the 4-connected component id for cell `(col, row)` under minimum Chebyshev ring
     * threshold [minRings] (`>= 1`).
     */
    fun componentId(col: Int, row: Int, minRings: Int): Int {
        if (minRings <= 1) return componentId(col, row)
        val table = componentsForRings(minRings)
        return table[row * width + col]
    }

    /**
     * Conservative O(1) 4-connected reachability test between `(startCol, startRow)` and
     * `(goalCol, goalRow)` on the base passability graph (`clearance >= 1`) or wider ring band.
     *
     * Does NOT reject border cells based on center-inset margins so units with `radius > tile/2`
     * departing from legal off-center edge positions or arriving via `checkEverySegment` neighbor
     * sweeps are never falsely rejected.
     */
    fun isConnected(startCol: Int, startRow: Int, goalCol: Int, goalRow: Int, minRings: Int = 1): Boolean {
        val sc = componentId(startCol, startRow, minRings)
        if (sc == 0) return false
        return sc == componentId(goalCol, goalRow, minRings)
    }

    private fun componentsForRings(minRings: Int): IntArray {
        synchronized(componentCacheLock) {
            val map = bandComponents ?: HashMap<Int, IntArray>().also { bandComponents = it }
            return map.getOrPut(minRings) { computeComponents(width, height, clearance, minRings) }
        }
    }

    /**
     * Incrementally updates the Chebyshev clearance field after [changedIndices] flip their
     * blocked state in [newBlocked]. Duplicate indices in [changedIndices] are handled safely;
     * component tables remain lazy and are rebuilt only if connectivity is later queried.
     */
    fun withChangedCells(newBlocked: BooleanArray, changedIndices: IntArray): TerrainClearanceField {
        if (changedIndices.isEmpty()) return this
        val total = width * height
        if (changedIndices.size * 8 >= total) {
            val rebuilt = computeChebyshevClearance(width, height, newBlocked)
            return TerrainClearanceField(width, height, rebuilt, precomputedBaseComponents = null)
        }
        val nextClearance = clearance.copyOf()
        incrementalUpdateClearance(width, height, newBlocked, nextClearance, changedIndices)
        return TerrainClearanceField(width, height, nextClearance, precomputedBaseComponents = null)
    }

    companion object {
        const val MAX_CLEARANCE: Int = 32_767

        fun build(width: Int, height: Int, blocked: BooleanArray): TerrainClearanceField {
            val clearance = computeChebyshevClearance(width, height, blocked)
            return TerrainClearanceField(width, height, clearance, precomputedBaseComponents = null)
        }

        /**
         * Two-pass exact Chebyshev (L-infinity) distance transform to nearest blocked cell in O(W*H)
         * with zero heap allocations beyond the single output [ShortArray].
         */
        private fun computeChebyshevClearance(width: Int, height: Int, blocked: BooleanArray): ShortArray {
            val n = width * height
            val dist = ShortArray(n)
            val inf = MAX_CLEARANCE
            // Pass 1: top-left to bottom-right
            for (y in 0 until height) {
                val rowOff = y * width
                for (x in 0 until width) {
                    val idx = rowOff + x
                    if (blocked[idx]) {
                        dist[idx] = 0
                    } else {
                        var best = inf
                        if (x > 0) {
                            val d = (dist[idx - 1].toInt() and 0xFFFF) + 1
                            if (d < best) best = d
                        }
                        if (y > 0) {
                            val up = idx - width
                            val dUp = (dist[up].toInt() and 0xFFFF) + 1
                            if (dUp < best) best = dUp
                            if (x > 0) {
                                val dUl = (dist[up - 1].toInt() and 0xFFFF) + 1
                                if (dUl < best) best = dUl
                            }
                            if (x + 1 < width) {
                                val dUr = (dist[up + 1].toInt() and 0xFFFF) + 1
                                if (dUr < best) best = dUr
                            }
                        }
                        dist[idx] = best.coerceAtMost(inf).toShort()
                    }
                }
            }
            // Pass 2: bottom-right to top-left
            for (y in height - 1 downTo 0) {
                val rowOff = y * width
                for (x in width - 1 downTo 0) {
                    val idx = rowOff + x
                    var best = dist[idx].toInt() and 0xFFFF
                    if (best == 0) continue
                    if (x + 1 < width) {
                        val d = (dist[idx + 1].toInt() and 0xFFFF) + 1
                        if (d < best) best = d
                    }
                    if (y + 1 < height) {
                        val dn = idx + width
                        val dDn = (dist[dn].toInt() and 0xFFFF) + 1
                        if (dDn < best) best = dDn
                        if (x > 0) {
                            val dDl = (dist[dn - 1].toInt() and 0xFFFF) + 1
                            if (dDl < best) best = dDl
                        }
                        if (x + 1 < width) {
                            val dDr = (dist[dn + 1].toInt() and 0xFFFF) + 1
                            if (dDr < best) best = dDr
                        }
                    }
                    dist[idx] = best.coerceAtMost(inf).toShort()
                }
            }
            return dist
        }

        /**
         * Exact local incremental brushfire update for Chebyshev clearance:
         * 1. Phase A (newly unblocked): deduplicates seeds, invalidates the local dependent cone
         *    (`nd == curOld + 1`, guarded against `curOld >= inf`), and re-seeds from all intact
         *    non-invalidated neighbors around the perimeter using a primitive min-heap.
         * 2. Phase B (newly blocked): deduplicates seeds (`dist != 0`), drops them to 0, and runs
         *    a monotone 8-neighbor BFS wave relaxing any neighbor whose clearance exceeds `d + 1`.
         */
        private fun incrementalUpdateClearance(
            width: Int,
            height: Int,
            blocked: BooleanArray,
            dist: ShortArray,
            changedIndices: IntArray,
        ) {
            val n = width * height
            val inf = MAX_CLEARANCE
            var hasLower = false
            var hasRaise = false
            for (idx in changedIndices) {
                if (blocked[idx]) hasLower = true else hasRaise = true
            }

            // Phase A: handle newly unblocked cells (raise clearance in affected cone)
            if (hasRaise) {
                val queue = IntArray(n)
                var head = 0
                var tail = 0
                val invalidated = BooleanArray(n)
                val oldVal = ShortArray(n)

                for (idx in changedIndices) {
                    if (!blocked[idx] && !invalidated[idx] && (dist[idx].toInt() and 0xFFFF) == 0) {
                        oldVal[idx] = 0
                        dist[idx] = inf.toShort()
                        invalidated[idx] = true
                        queue[tail++] = idx
                    }
                }
                while (head < tail) {
                    val cur = queue[head++]
                    val curOld = oldVal[cur].toInt() and 0xFFFF
                    if (curOld >= inf) continue
                    val nextTarget = curOld + 1
                    val cx = cur % width
                    val cy = cur / width
                    val minY = max(0, cy - 1)
                    val maxY = min(height - 1, cy + 1)
                    val minX = max(0, cx - 1)
                    val maxX = min(width - 1, cx + 1)
                    for (ny in minY..maxY) {
                        val rowOff = ny * width
                        for (nx in minX..maxX) {
                            val ni = rowOff + nx
                            if (ni == cur || invalidated[ni] || blocked[ni]) continue
                            val nd = dist[ni].toInt() and 0xFFFF
                            if (nd == nextTarget) {
                                oldVal[ni] = dist[ni]
                                dist[ni] = inf.toShort()
                                invalidated[ni] = true
                                queue[tail++] = ni
                            }
                        }
                    }
                }
                val reseedCount = tail
                if (reseedCount > 0) {
                    var hCap = max(16, reseedCount * 2)
                    var hCells = IntArray(hCap)
                    var hVals = IntArray(hCap)
                    var heapSize = 0
                    fun pushGrow(cell: Int, v: Int) {
                        if (heapSize == hCap) {
                            hCap = hCap shl 1
                            hCells = hCells.copyOf(hCap)
                            hVals = hVals.copyOf(hCap)
                        }
                        var i = heapSize++
                        hCells[i] = cell
                        hVals[i] = v
                        while (i > 0) {
                            val p = (i - 1) ushr 1
                            if (hVals[p] <= hVals[i]) break
                            val tc = hCells[p]; hCells[p] = hCells[i]; hCells[i] = tc
                            val tv = hVals[p]; hVals[p] = hVals[i]; hVals[i] = tv
                            i = p
                        }
                    }
                    fun popGrow(): Int {
                        val top = hCells[0]
                        val last = --heapSize
                        if (last > 0) {
                            hCells[0] = hCells[last]
                            hVals[0] = hVals[last]
                            var i = 0
                            while (true) {
                                val l = (i shl 1) + 1
                                if (l >= last) break
                                val r = l + 1
                                val best = if (r < last && hVals[r] < hVals[l]) r else l
                                if (hVals[i] <= hVals[best]) break
                                val tc = hCells[i]; hCells[i] = hCells[best]; hCells[best] = tc
                                val tv = hVals[i]; hVals[i] = hVals[best]; hVals[best] = tv
                                i = best
                            }
                        }
                        return top
                    }

                    for (k in 0 until reseedCount) {
                        val cur = queue[k]
                        val cx = cur % width
                        val cy = cur / width
                        var best = inf
                        val minY = max(0, cy - 1)
                        val maxY = min(height - 1, cy + 1)
                        val minX = max(0, cx - 1)
                        val maxX = min(width - 1, cx + 1)
                        for (ny in minY..maxY) {
                            val rowOff = ny * width
                            for (nx in minX..maxX) {
                                val ni = rowOff + nx
                                if (ni == cur || invalidated[ni]) continue
                                val nd = (dist[ni].toInt() and 0xFFFF) + 1
                                if (nd < best) best = nd
                            }
                        }
                        if (best < inf) {
                            dist[cur] = best.toShort()
                            pushGrow(cur, best)
                        }
                    }
                    while (heapSize > 0) {
                        val cur = popGrow()
                        val cd = dist[cur].toInt() and 0xFFFF
                        val nd = cd + 1
                        if (nd >= inf) continue
                        val cx = cur % width
                        val cy = cur / width
                        val minY = max(0, cy - 1)
                        val maxY = min(height - 1, cy + 1)
                        val minX = max(0, cx - 1)
                        val maxX = min(width - 1, cx + 1)
                        for (ny in minY..maxY) {
                            val rowOff = ny * width
                            for (nx in minX..maxX) {
                                val ni = rowOff + nx
                                if (ni == cur || !invalidated[ni]) continue
                                if (nd < (dist[ni].toInt() and 0xFFFF)) {
                                    dist[ni] = nd.toShort()
                                    pushGrow(ni, nd)
                                }
                            }
                        }
                    }
                }
            }

            // Phase B: handle newly blocked cells (lower clearance via deduplicated monotone BFS wave)
            if (hasLower) {
                val queue = IntArray(n)
                var head = 0
                var tail = 0
                for (idx in changedIndices) {
                    if (blocked[idx] && (dist[idx].toInt() and 0xFFFF) != 0) {
                        dist[idx] = 0
                        queue[tail++] = idx
                    }
                }
                while (head < tail) {
                    val cur = queue[head++]
                    val nd = (dist[cur].toInt() and 0xFFFF) + 1
                    val cx = cur % width
                    val cy = cur / width
                    val minY = max(0, cy - 1)
                    val maxY = min(height - 1, cy + 1)
                    val minX = max(0, cx - 1)
                    val maxX = min(width - 1, cx + 1)
                    for (ny in minY..maxY) {
                        val rowOff = ny * width
                        for (nx in minX..maxX) {
                            val ni = rowOff + nx
                            if (ni == cur) continue
                            if (nd < (dist[ni].toInt() and 0xFFFF)) {
                                dist[ni] = nd.toShort()
                                queue[tail++] = ni
                            }
                        }
                    }
                }
            }
        }

        /**
         * Iterative primitive-array BFS labeling 4-connected passable components (`clearance >= minRings`).
         * Zero per-cell object allocations.
         */
        private fun computeComponents(width: Int, height: Int, clearance: ShortArray, minRings: Int): IntArray {
            val n = width * height
            val comp = IntArray(n)
            val queue = IntArray(n)
            var nextId = 0
            for (start in 0 until n) {
                if (comp[start] != 0 || (clearance[start].toInt() and 0xFFFF) < minRings) continue
                val id = ++nextId
                comp[start] = id
                var head = 0
                var tail = 0
                queue[tail++] = start
                while (head < tail) {
                    val cur = queue[head++]
                    val cx = cur % width
                    val cy = cur / width
                    if (cy > 0) {
                        val ni = cur - width
                        if (comp[ni] == 0 && (clearance[ni].toInt() and 0xFFFF) >= minRings) {
                            comp[ni] = id; queue[tail++] = ni
                        }
                    }
                    if (cx > 0) {
                        val ni = cur - 1
                        if (comp[ni] == 0 && (clearance[ni].toInt() and 0xFFFF) >= minRings) {
                            comp[ni] = id; queue[tail++] = ni
                        }
                    }
                    if (cx + 1 < width) {
                        val ni = cur + 1
                        if (comp[ni] == 0 && (clearance[ni].toInt() and 0xFFFF) >= minRings) {
                            comp[ni] = id; queue[tail++] = ni
                        }
                    }
                    if (cy + 1 < height) {
                        val ni = cur + width
                        if (comp[ni] == 0 && (clearance[ni].toInt() and 0xFFFF) >= minRings) {
                            comp[ni] = id; queue[tail++] = ni
                        }
                    }
                }
            }
            return comp
        }
    }
}
