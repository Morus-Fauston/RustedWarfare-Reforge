package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import com.rustedwarfare.reforge.core.path.PathSearchCounters
import com.rustedwarfare.reforge.core.path.PlanningSegmentClearance
import java.util.PriorityQueue

/** The candidate and waypoint policies are experimental and replaceable for later calibration. */
internal interface PathPolicy {
    fun findPath(map: PlanningTerrain, start: Position, target: Position, radius: Double,
                 counters: PathSearchCounters? = null, targetRange: Double = 0.0): List<Position>?
}

internal class ClearanceAStarPolicy(
    private val candidatePolicy: PathCandidatePolicy,
    private val pointPolicy: PathPointPolicy,
    private val terrain: PlanningTerrainPolicy,
) : PathPolicy {
    override fun findPath(map: PlanningTerrain, start: Position, target: Position, radius: Double,
                          counters: PathSearchCounters?, targetRange: Double): List<Position>? {
        if (counters != null) counters.actualSearchCalls++
        require(targetRange.isFinite() && targetRange >= 0.0)
        if (targetRange > 0.0) return findPathToRange(map, start, target, radius, targetRange, counters)
        if (!MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth,
                map.heightTiles.toDouble() * map.tileHeight, start, radius) ||
            !MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth,
                map.heightTiles.toDouble() * map.tileHeight, target, radius)) return null
        val startCell = cellOf(map, start) ?: return null
        val goalCell = cellOf(map, target) ?: return null
        if (terrain.intersects(map, start, radius) || terrain.intersects(map, target, radius)) return null
        if (PlanningSegmentClearance.allows(map, start, target, radius)) return listOf(target)
        val candidates = Array(map.heightTiles) { BooleanArray(map.widthTiles) }
        for (row in 0 until map.heightTiles) for (col in 0 until map.widthTiles) {
            if (counters != null) counters.candidatesChecked++
            candidates[row][col] = candidatePolicy.allows(map, col, row, radius)
        }
        if (!candidates[goalCell.second][goalCell.first]) return null

        data class Node(val col: Int, val row: Int, val g: Int, val f: Int)
        val open = PriorityQueue<Node>(compareBy<Node> { it.f }.thenBy { it.row }.thenBy { it.col }.thenBy { it.g })
        val startKey = startCell.first to startCell.second
        val cameFrom = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
        val cost = HashMap<Pair<Int, Int>, Int>().apply { this[startKey] = 0 }
        if (counters != null) {
            counters.enqueued++
            counters.heuristicCalls++
        }
        open += Node(startCell.first, startCell.second, 0, heuristic(startCell.first, startCell.second, goalCell.first, goalCell.second))

        while (open.isNotEmpty()) {
            val current = open.remove()
            if (counters != null) counters.popped++
            val currentKey = current.col to current.row
            if (cost[currentKey] != current.g) continue
            if (currentKey == goalCell.first to goalCell.second) {
                val cells = ArrayList<Pair<Int, Int>>()
                var cursor = currentKey
                cells += cursor
                while (cursor != startKey) {
                    cursor = cameFrom[cursor] ?: return null
                    cells += cursor
                }
                cells.reverse()
                val points = cells.drop(1).map { (col, row) ->
                    pointPolicy.point(map, col, row)
                }.toMutableList()
                if (points.lastOrNull() != target) points += target
                return simplify(map, start, points, radius)
            }
            if (counters != null) counters.expanded++
            // Same row/column tie order as the old sorted list: up, left, right, down.
            for (direction in 0..3) {
                val nextCol = current.col + when (direction) { 1 -> -1; 2 -> 1; else -> 0 }
                val nextRow = current.row + when (direction) { 0 -> -1; 3 -> 1; else -> 0 }
                if (nextCol !in 0 until map.widthTiles || nextRow !in 0 until map.heightTiles) continue
                if (counters != null) counters.neighborsChecked++
                if (!candidates[nextRow][nextCol]) continue
                if (currentKey == startKey &&
                    !PlanningSegmentClearance.allows(map, start, pointPolicy.point(map, nextCol, nextRow), radius)
                ) continue
                val nextKey = nextCol to nextRow
                val nextCost = current.g + 1
                if (nextCost < (cost[nextKey] ?: Int.MAX_VALUE)) {
                    cost[nextKey] = nextCost
                    cameFrom[nextKey] = currentKey
                    if (counters != null) {
                        counters.enqueued++
                        counters.heuristicCalls++
                    }
                    open += Node(nextCol, nextRow, nextCost, nextCost + heuristic(nextCol, nextRow, goalCell.first, goalCell.second))
                }
            }
        }
        return null
    }

    /** Reach a continuous legal stance; the blocked target center is never opened for traversal. */
    private fun findPathToRange(map: PlanningTerrain, start: Position, target: Position, radius: Double,
                                range: Double, counters: PathSearchCounters?): List<Position>? {
        val width = map.widthTiles.toDouble() * map.tileWidth
        val height = map.heightTiles.toDouble() * map.tileHeight
        if (!MapBounds.contains(width, height, start, radius) || terrain.intersects(map, start, radius)) return null
        val startKey = checkNotNull(cellOf(map, start))
        val points = Array(map.heightTiles) { arrayOfNulls<Position>(map.widthTiles) }
        val goals = Array(map.heightTiles) { arrayOfNulls<Position>(map.widthTiles) }
        for (row in 0 until map.heightTiles) for (col in 0 until map.widthTiles) {
            if (counters != null) counters.candidatesChecked++
            if (map.isBlocked(col, row)) continue
            val left = maxOf(col.toDouble() * map.tileWidth, radius)
            val top = maxOf(row.toDouble() * map.tileHeight, radius)
            val right = minOf(Math.nextDown((col.toDouble() + 1.0) * map.tileWidth), width - radius)
            val bottom = minOf(Math.nextDown((row.toDouble() + 1.0) * map.tileHeight), height - radius)
            if (left > right || top > bottom) continue
            val center = pointPolicy.point(map, col, row)
            val point = Position(center.x.coerceIn(left, right), center.y.coerceIn(top, bottom))
            if (terrain.intersects(map, point, radius)) continue
            points[row][col] = point
            val goal = Position(target.x.coerceIn(left, right), target.y.coerceIn(top, bottom))
            if (ScreenPlane.distanceSquared(goal, target) < range * range && !terrain.intersects(map, goal, radius))
                goals[row][col] = goal
        }
        data class Node(val col: Int, val row: Int, val g: Int)
        val open = PriorityQueue<Node>(compareBy<Node> { it.g }.thenBy { it.row }.thenBy { it.col })
        val cameFrom = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
        val cost = HashMap<Pair<Int, Int>, Int>().apply { this[startKey] = 0 }
        open += Node(startKey.first, startKey.second, 0)
        if (counters != null) counters.enqueued++
        while (open.isNotEmpty()) {
            val current = open.remove()
            if (counters != null) counters.popped++
            val key = current.col to current.row
            if (cost[key] != current.g) continue
            val from = if (key == startKey) start else checkNotNull(points[current.row][current.col])
            val goal = goals[current.row][current.col]
            if (goal != null && PlanningSegmentClearance.allows(map, from, goal, radius)) {
                val cells = arrayListOf(key)
                var cursor = key
                while (cursor != startKey) {
                    cursor = cameFrom[cursor] ?: return null
                    cells += cursor
                }
                cells.reverse()
                val path = cells.drop(1).map { (col, row) -> checkNotNull(points[row][col]) }.toMutableList()
                if (path.lastOrNull() != goal) path += goal
                return simplify(map, start, path, radius)
            }
            if (counters != null) counters.expanded++
            for (direction in 0..3) {
                val col = current.col + when (direction) { 1 -> -1; 2 -> 1; else -> 0 }
                val row = current.row + when (direction) { 0 -> -1; 3 -> 1; else -> 0 }
                if (col !in 0 until map.widthTiles || row !in 0 until map.heightTiles) continue
                if (counters != null) counters.neighborsChecked++
                val point = points[row][col] ?: continue
                if (!PlanningSegmentClearance.allows(map, from, point, radius)) continue
                val next = col to row
                val nextCost = current.g + 1
                if (nextCost < (cost[next] ?: Int.MAX_VALUE)) {
                    cost[next] = nextCost
                    cameFrom[next] = key
                    open += Node(col, row, nextCost)
                    if (counters != null) counters.enqueued++
                }
            }
        }
        return null
    }

    private fun simplify(map: PlanningTerrain, start: Position, points: List<Position>, radius: Double): List<Position>? {
        val result = ArrayList<Position>()
        var anchor = start
        var next = 0
        while (next < points.size) {
            val farthest = (points.lastIndex downTo next).firstOrNull {
                PlanningSegmentClearance.allows(map, anchor, points[it], radius)
            } ?: return null
            anchor = points[farthest]
            result += anchor
            next = farthest + 1
        }
        return result
    }

    private fun cellOf(map: PlanningTerrain, position: Position): Pair<Int, Int>? {
        val col = MapBounds.cell(position.x, map.tileWidth, map.widthTiles)
        val row = MapBounds.cell(position.y, map.tileHeight, map.heightTiles)
        return if (col in 0 until map.widthTiles && row in 0 until map.heightTiles) col to row else null
    }

    private fun heuristic(col: Int, row: Int, goalCol: Int, goalRow: Int): Int =
        kotlin.math.abs(col - goalCol) + kotlin.math.abs(row - goalRow)
}

internal fun interface PathCandidatePolicy {
    fun allows(map: PlanningTerrain, col: Int, row: Int, radius: Double): Boolean
}

internal class CenterClearance(private val terrain: PlanningTerrainPolicy) : PathCandidatePolicy {
    override fun allows(map: PlanningTerrain, col: Int, row: Int, radius: Double): Boolean =
        !map.isBlocked(col, row) && !terrain.intersects(map, TileCenters.point(map, col, row), radius)
}

internal fun interface PathPointPolicy {
    fun point(map: PlanningTerrain, col: Int, row: Int): Position
}

internal object TileCenters : PathPointPolicy {
    override fun point(map: PlanningTerrain, col: Int, row: Int): Position =
        Position((col + 0.5) * map.tileWidth, (row + 0.5) * map.tileHeight)
}

internal fun interface PlanningTerrainPolicy {
    fun intersects(map: PlanningTerrain, position: Position, radius: Double): Boolean
}

internal object PlanningCenterTerrain : PlanningTerrainPolicy {
    override fun intersects(map: PlanningTerrain, position: Position, radius: Double): Boolean =
        !MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth,
            map.heightTiles.toDouble() * map.tileHeight, position, radius) ||
        map.isBlocked(MapBounds.cell(position.x, map.tileWidth, map.widthTiles),
            MapBounds.cell(position.y, map.tileHeight, map.heightTiles))
}

internal fun interface TerrainPolicy {
    fun intersects(map: MapDefinition, position: Position, radius: Double): Boolean
}

internal object CenterTerrain : TerrainPolicy {
    override fun intersects(map: MapDefinition, position: Position, radius: Double): Boolean =
        map.blocking.isBlocked(MapBounds.cell(position.x, map.tileWidth, map.widthTiles),
            MapBounds.cell(position.y, map.tileHeight, map.heightTiles))
}
