package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import com.rustedwarfare.reforge.core.path.PathSearchCounters
import com.rustedwarfare.reforge.core.path.PlanningSegmentClearance
import java.util.PriorityQueue
import kotlin.math.floor
import kotlin.math.hypot

/** The candidate and waypoint policies are experimental and replaceable for later calibration. */
internal interface PathPolicy {
    fun findPath(map: PlanningTerrain, start: Position, target: Position, radius: Double,
                 counters: PathSearchCounters? = null): List<Position>?
}

internal class ClearanceAStarPolicy(
    private val candidatePolicy: PathCandidatePolicy,
    private val pointPolicy: PathPointPolicy,
    private val terrain: PlanningTerrainPolicy,
) : PathPolicy {
    override fun findPath(map: PlanningTerrain, start: Position, target: Position, radius: Double,
                          counters: PathSearchCounters?): List<Position>? {
        if (counters != null) counters.actualSearchCalls++
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
            for ((nextCol, nextRow) in neighbors(current.col, current.row, map.widthTiles, map.heightTiles)) {
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

    private fun neighbors(col: Int, row: Int, width: Int, height: Int): List<Pair<Int, Int>> =
        listOf(row - 1 to col, row to col - 1, row to col + 1, row + 1 to col)
            .map { (nextRow, nextCol) -> nextCol to nextRow }
            .filter { (nextCol, nextRow) -> nextCol in 0 until width && nextRow in 0 until height }
            .sortedWith(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })

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

internal object PlanningCircleTerrain : PlanningTerrainPolicy {
    override fun intersects(map: PlanningTerrain, position: Position, radius: Double): Boolean =
        !MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth,
            map.heightTiles.toDouble() * map.tileHeight, position, radius) ||
        circleIntersectsTerrain(map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight,
            position, radius, map::isBlocked)
}

internal object PlanningCenterTerrain : PlanningTerrainPolicy {
    override fun intersects(map: PlanningTerrain, position: Position, radius: Double): Boolean =
        !MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth,
            map.heightTiles.toDouble() * map.tileHeight, position, radius) ||
        map.isBlocked(MapBounds.cell(position.x, map.tileWidth, map.widthTiles),
            MapBounds.cell(position.y, map.tileHeight, map.heightTiles))
}

/** Conservative broad phase only: the original closed-circle narrow phase decides contact.
 * One extra cell on each side retains grid-line tangency and floating-point boundary cases.
 * Clamp as doubles before converting to Int, including when finite inputs sum to infinity.
 */
private inline fun circleIntersectsTerrain(width: Int, height: Int, tileWidth: Int, tileHeight: Int,
                                         position: Position, radius: Double,
                                         blocked: (Int, Int) -> Boolean): Boolean {
    val firstColumn = (floor((position.x - radius) / tileWidth) - 1).coerceIn(0.0, width.toDouble()).toInt()
    val lastColumn = (floor((position.x + radius) / tileWidth) + 1).coerceIn(-1.0, width - 1.0).toInt()
    val firstRow = (floor((position.y - radius) / tileHeight) - 1).coerceIn(0.0, height.toDouble()).toInt()
    val lastRow = (floor((position.y + radius) / tileHeight) + 1).coerceIn(-1.0, height - 1.0).toInt()
    for (row in firstRow..lastRow) for (col in firstColumn..lastColumn) {
        if (!blocked(col, row)) continue
        val left = col * tileWidth.toDouble()
        val top = row * tileHeight.toDouble()
        if (circleIntersectsRectangle(position, radius, left, top, left + tileWidth, top + tileHeight)) return true
    }
    return false
}

private fun circleIntersectsRectangle(position: Position, radius: Double, left: Double, top: Double, right: Double, bottom: Double): Boolean {
    val closestX = position.x.coerceIn(left, right)
    val closestY = position.y.coerceIn(top, bottom)
    return hypot(position.x - closestX, position.y - closestY) <= radius
}

internal fun interface TerrainPolicy {
    fun intersects(map: MapDefinition, position: Position, radius: Double): Boolean
}

internal object CircleTerrain : TerrainPolicy {
    override fun intersects(map: MapDefinition, position: Position, radius: Double): Boolean =
        circleIntersectsTerrain(map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight,
            position, radius, map.blocking::isBlocked)
}

internal object CenterTerrain : TerrainPolicy {
    override fun intersects(map: MapDefinition, position: Position, radius: Double): Boolean =
        map.blocking.isBlocked(MapBounds.cell(position.x, map.tileWidth, map.widthTiles),
            MapBounds.cell(position.y, map.tileHeight, map.heightTiles))
}
