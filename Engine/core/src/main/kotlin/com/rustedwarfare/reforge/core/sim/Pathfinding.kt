package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import java.util.PriorityQueue
import kotlin.math.hypot

/** The candidate and waypoint policies are experimental and replaceable for later calibration. */
internal interface PathPolicy {
    fun findPath(map: MapDefinition, start: Position, target: Position, radius: Double): List<Position>?
}

internal class ClearanceAStarPolicy(
    private val candidatePolicy: PathCandidatePolicy,
    private val pointPolicy: PathPointPolicy,
    private val terrain: TerrainPolicy,
) : PathPolicy {
    override fun findPath(map: MapDefinition, start: Position, target: Position, radius: Double): List<Position>? {
        val startCell = cellOf(map, start) ?: return null
        val goalCell = cellOf(map, target) ?: return null
        if (terrain.intersects(map, target, radius)) return null
        val candidates = Array(map.heightTiles) { BooleanArray(map.widthTiles) }
        for (row in 0 until map.heightTiles) for (col in 0 until map.widthTiles) {
            candidates[row][col] = candidatePolicy.allows(map, col, row, radius)
        }
        if (!candidates[startCell.second][startCell.first] || !candidates[goalCell.second][goalCell.first]) return null

        data class Node(val col: Int, val row: Int, val g: Int, val f: Int)
        val open = PriorityQueue<Node>(compareBy<Node> { it.f }.thenBy { it.row }.thenBy { it.col }.thenBy { it.g })
        val startKey = startCell.first to startCell.second
        val cameFrom = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
        val cost = HashMap<Pair<Int, Int>, Int>().apply { this[startKey] = 0 }
        open += Node(startCell.first, startCell.second, 0, heuristic(startCell.first, startCell.second, goalCell.first, goalCell.second))

        while (open.isNotEmpty()) {
            val current = open.remove()
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
                if (points.isEmpty()) points += target else points[points.lastIndex] = target
                return points
            }
            for ((nextCol, nextRow) in neighbors(current.col, current.row, map.widthTiles, map.heightTiles)) {
                if (!candidates[nextRow][nextCol]) continue
                val nextKey = nextCol to nextRow
                val nextCost = current.g + 1
                if (nextCost < (cost[nextKey] ?: Int.MAX_VALUE)) {
                    cost[nextKey] = nextCost
                    cameFrom[nextKey] = currentKey
                    open += Node(nextCol, nextRow, nextCost, nextCost + heuristic(nextCol, nextRow, goalCell.first, goalCell.second))
                }
            }
        }
        return null
    }

    private fun cellOf(map: MapDefinition, position: Position): Pair<Int, Int>? {
        val col = kotlin.math.floor(position.x / map.tileWidth).toInt()
        val row = kotlin.math.floor(position.y / map.tileHeight).toInt()
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
    fun allows(map: MapDefinition, col: Int, row: Int, radius: Double): Boolean
}

internal class CenterClearance(private val terrain: TerrainPolicy) : PathCandidatePolicy {
    override fun allows(map: MapDefinition, col: Int, row: Int, radius: Double): Boolean =
        !map.blocking.isBlocked(col, row) && !terrain.intersects(map, TileCenters.point(map, col, row), radius)
}

internal fun interface PathPointPolicy {
    fun point(map: MapDefinition, col: Int, row: Int): Position
}

internal object TileCenters : PathPointPolicy {
    override fun point(map: MapDefinition, col: Int, row: Int): Position =
        Position((col + 0.5) * map.tileWidth, (row + 0.5) * map.tileHeight)
}

internal fun interface TerrainPolicy {
    fun intersects(map: MapDefinition, position: Position, radius: Double): Boolean
}

internal object CircleTerrain : TerrainPolicy {
    override fun intersects(map: MapDefinition, position: Position, radius: Double): Boolean {
        for (row in 0 until map.heightTiles) for (col in 0 until map.widthTiles) {
            if (!map.blocking.isBlocked(col, row)) continue
            val left = col * map.tileWidth.toDouble()
            val top = row * map.tileHeight.toDouble()
            val closestX = position.x.coerceIn(left, left + map.tileWidth)
            val closestY = position.y.coerceIn(top, top + map.tileHeight)
            if (hypot(position.x - closestX, position.y - closestY) <= radius) return true
        }
        return false
    }
}
