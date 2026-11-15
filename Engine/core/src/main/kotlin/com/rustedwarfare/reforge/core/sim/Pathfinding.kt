package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import com.rustedwarfare.reforge.core.path.PathSearchCounters
import com.rustedwarfare.reforge.core.path.PlanningSegmentClearance
import java.util.PriorityQueue

/** The candidate and waypoint policies are experimental and replaceable for later calibration. */
internal interface PathPolicy {
    fun findPath(map: PlanningTerrain, start: Position, target: Position, radius: Double,
                 counters: PathSearchCounters? = null, targetRange: Double = 0.0,
                 hull: HullRoute? = null): List<Position>?
}

internal class ClearanceAStarPolicy(
    private val candidatePolicy: PathCandidatePolicy,
    private val pointPolicy: PathPointPolicy,
    private val terrain: PlanningTerrainPolicy,
) : PathPolicy {
    override fun findPath(map: PlanningTerrain, start: Position, target: Position, radius: Double,
                          counters: PathSearchCounters?, targetRange: Double,
                          hull: HullRoute?): List<Position>? {
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
        val hullGrid = hull?.let { HullTerrainGrid.authorized(map) }
        fun arrival(from: Position, point: Position, heading: Double): Double? =
            hull?.arrival(checkNotNull(hullGrid), from, point, heading)
        if (if (hull == null) PlanningSegmentClearance.allows(map, start, target, radius)
            else arrival(start, target, hull.heading) != null) return listOf(target)

        val candidates = Array(map.heightTiles) { BooleanArray(map.widthTiles) }
        for (row in 0 until map.heightTiles) for (col in 0 until map.widthTiles) {
            if (counters != null) counters.candidatesChecked++
            candidates[row][col] = candidatePolicy.allows(map, col, row, radius)
        }
        if (!candidates[goalCell.second][goalCell.first]) return null

        data class Key(val col: Int, val row: Int, val heading: Double = 0.0)
        data class Node(val key: Key, val g: Int, val f: Int)
        fun remaining(col: Int, row: Int): Int = if (hull == null)
            heuristic(col, row, goalCell.first, goalCell.second)
        else maxOf(kotlin.math.abs(col - goalCell.first), kotlin.math.abs(row - goalCell.second))
        val startKey = Key(startCell.first, startCell.second, hull?.heading ?: 0.0)
        val open = PriorityQueue<Node>(compareBy<Node> { it.f }.thenBy { it.key.row }.thenBy { it.key.col }
            .thenBy { it.g }.thenBy { it.key.heading })
        val cameFrom = HashMap<Key, Key>()
        val cost = HashMap<Key, Int>().apply { this[startKey] = 0 }
        val offsets = if (hull is RectangularDeparture) orthogonal + diagonal else orthogonal
        if (counters != null) {
            counters.enqueued++
            counters.heuristicCalls++
        }
        open += Node(startKey, 0, remaining(startKey.col, startKey.row))

        while (open.isNotEmpty()) {
            val current = open.remove()
            if (counters != null) counters.popped++
            val key = current.key
            if (cost[key] != current.g) continue
            val from = if (key == startKey) start else pointPolicy.point(map, key.col, key.row)
            val finish = if (hull == null) key.col == goalCell.first && key.row == goalCell.second
                else arrival(from, target, key.heading) != null
            if (finish) {
                val points = mutableListOf<Position>()
                var cursor = key
                while (cursor != startKey) {
                    points += pointPolicy.point(map, cursor.col, cursor.row)
                    cursor = cameFrom.getValue(cursor)
                }
                points.reverse()
                if (points.lastOrNull() != target) points += target
                return if (hull == null) simplify(map, start, points, radius) else points
            }
            if (counters != null) counters.expanded++
            for ((dx, dy) in offsets) {
                val col = key.col + dx
                val row = key.row + dy
                if (col !in 0 until map.widthTiles || row !in 0 until map.heightTiles) continue
                if (counters != null) counters.neighborsChecked++
                if (!candidates[row][col]) continue
                val point = pointPolicy.point(map, col, row)
                val heading = if (hull != null) arrival(from, point, key.heading) ?: continue
                    else {
                        if (key == startKey && !PlanningSegmentClearance.allows(map, from, point, radius)) continue
                        0.0
                    }
                val next = Key(col, row, heading)
                val nextCost = current.g + 1
                if (nextCost < (cost[next] ?: Int.MAX_VALUE)) {
                    cost[next] = nextCost
                    cameFrom[next] = key
                    if (counters != null) {
                        counters.enqueued++
                        counters.heuristicCalls++
                    }
                    open += Node(next, nextCost, nextCost + remaining(col, row))
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

    private companion object {
        val orthogonal = listOf(0 to -1, -1 to 0, 1 to 0, 0 to 1)
        val diagonal = listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)
    }
}

internal sealed interface HullRoute {
    val heading: Double
    fun arrival(terrain: HullTerrainGrid, start: Position, target: Position, from: Double): Double?

    data class Circle(val radius: Double) : HullRoute {
        override val heading = 0.0
        override fun arrival(terrain: HullTerrainGrid, start: Position, target: Position, from: Double): Double? =
            CircularTerrain(radius).let { if (it.contains(terrain, start) && it.resolve(terrain, start, target) == target) heading else null }
    }

    companion object {
        fun forUnit(unit: MutableUnit): HullRoute? = when (val shape = UnitLocomotion.terrainHull(unit)) {
            is MovementCollisionShape.Rectangle -> RectangularDeparture(shape, unit.heading,
                UnitLocomotion.movement(unit).reverseSpeedPercentage > 0.95)
            is MovementCollisionShape.Circle -> Circle(shape.radius)
            null -> null
        }
    }
}

internal data class RectangularDeparture(
    val shape: MovementCollisionShape.Rectangle,
    override val heading: Double,
    val reverse: Boolean,
) : HullRoute {
    fun headingTowards(start: Position, target: Position, current: Double): Double {
        if (start == target) return current
        val direct = ScreenPlane.desired(start, target)
        return if (reverse && kotlin.math.abs(ScreenPlane.difference(direct, current)) > 100.0)
            ScreenPlane.normalize(direct + 180.0) else direct
    }

    override fun arrival(terrain: HullTerrainGrid, start: Position, target: Position, from: Double): Double? {
        val to = headingTowards(start, target, from)
        val hull = RectangularTerrain(shape, to)
        return if (hull.contains(terrain, start) && hull.resolve(terrain, start, target) == target &&
            RectangularTurn.resolve(terrain, shape, start, from, to) == to) to else null
    }
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
    fun intersects(map: MapDefinition, position: Position, radius: Double,
                   movementType: com.rustedwarfare.reforge.core.content.MovementType): Boolean
}

internal object CenterTerrain : TerrainPolicy {
    override fun intersects(map: MapDefinition, position: Position, radius: Double,
                            movementType: com.rustedwarfare.reforge.core.content.MovementType): Boolean =
        map.blockingFor(movementType)
            .isBlocked(MapBounds.cell(position.x, map.tileWidth, map.widthTiles),
            MapBounds.cell(position.y, map.tileHeight, map.heightTiles))
}
