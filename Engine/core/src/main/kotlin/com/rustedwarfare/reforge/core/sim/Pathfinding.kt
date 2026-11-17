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
                 hull: HullRoute? = null, occupancy: RouteOccupancy? = null): List<Position>?

    fun beginExact(map: PlanningTerrain, start: Position, target: Position, radius: Double,
                   hull: HullRoute? = null, occupancy: RouteOccupancy? = null,
                   counters: PathSearchCounters? = null): ExactPathSearch
}


internal data class PathGridKey(val col: Int, val row: Int, val heading: Double = 0.0)

internal class PathWorkBudget(val limit: Int) {
    private var remaining = limit
    val used: Int get() = limit - remaining
    val exhausted: Boolean get() = remaining == 0

    fun take(cost: Int = 8): Boolean {
        if (remaining < cost) {
            remaining = 0
            return false
        }
        remaining -= cost
        return true
    }
}

internal interface ExactPathSearch {
    val finished: Boolean
    val path: List<Position>?
    val counters: PathSearchCounters?
    fun fork(rollback: PathSearchRollback): ExactPathSearch
    fun advance(budget: PathWorkBudget, cache: PathClearanceCache? = null)
}

/** Shared mutable search data needs undo only for work done in the current candidate step. */
internal class PathSearchRollback {
    private var changes: MutableList<() -> Unit>? = null

    fun record(undo: () -> Unit) {
        val recorded = changes ?: mutableListOf<() -> Unit>().also { changes = it }
        recorded += undo
    }

    fun commit() { changes = null }

    fun restore() {
        changes?.asReversed()?.forEach { it() }
        changes = null
    }
}

/** Reuse clearance grids and repeated shape checks inside one group order. */
internal class PathClearanceCache(
    private val grids: MutableMap<Pair<PlanningTerrain, Double>, BooleanArray> = HashMap(),
    private val rectangleChecks: RectangleChecks = RectangleChecks(),
) {
    fun fork(): PathClearanceCache = PathClearanceCache(HashMap(grids), rectangleChecks)
    fun get(terrain: PlanningTerrain, radius: Double): BooleanArray? = grids[terrain to radius]
    fun put(terrain: PlanningTerrain, radius: Double, cells: BooleanArray) {
        grids[terrain to radius] = cells
    }

    fun rectangularArrival(map: PlanningTerrain, terrain: HullTerrainGrid, hull: RectangularDeparture,
                           from: Position, target: Position, fromHeading: Double): Double? =
        rectangleChecks.arrival(map, terrain, hull, from, target, fromHeading)

    fun rectangularNeighbors(map: PlanningTerrain, hull: RectangularDeparture, radius: Double,
                             from: Position, heading: Double, build: () -> List<PathGridKey>): List<PathGridKey> =
        rectangleChecks.neighbors(map, hull, radius, from, heading, build)

    /** These caches affect cost on the host but every queue pop consumes the same work allowance. */
    internal class RectangleChecks {
        private data class Turn(val map: PlanningTerrain, val shape: MovementCollisionShape.Rectangle,
                                val at: Position, val from: Double, val to: Double)
        private data class Sweep(val map: PlanningTerrain, val shape: MovementCollisionShape.Rectangle,
                                 val from: Position, val target: Position, val heading: Double)
        private data class NeighborSource(val map: PlanningTerrain, val shape: MovementCollisionShape.Rectangle,
                                          val radius: Double, val reverse: Boolean,
                                          val at: Position, val heading: Double)
        private val turns = BoundedChecks<Turn, Boolean>()
        private val sweeps = BoundedChecks<Sweep, Boolean>()
        private val local = BoundedChecks<NeighborSource, List<PathGridKey>>()

        fun arrival(map: PlanningTerrain, terrain: HullTerrainGrid, hull: RectangularDeparture,
                    from: Position, target: Position, heading: Double): Double? {
            val next = hull.headingTowards(from, target, heading)
            val movesClear = sweeps.getOrPut(Sweep(map, hull.shape, from, target, next)) {
                val rectangle = RectangularTerrain(hull.shape, next)
                rectangle.contains(terrain, from) && rectangle.resolve(terrain, from, target) == target
            }
            if (!movesClear) return null
            val turnsClear = turns.getOrPut(Turn(map, hull.shape, from, heading, next)) {
                RectangularTurn.resolve(terrain, hull.shape, from, heading, next) == next
            }
            return next.takeIf { turnsClear }
        }

        fun neighbors(map: PlanningTerrain, hull: RectangularDeparture, radius: Double, from: Position,
                      heading: Double, build: () -> List<PathGridKey>): List<PathGridKey> =
            local.getOrPut(NeighborSource(map, hull.shape, radius, hull.reverse, from, heading), build)

        private class BoundedChecks<K, V> : LinkedHashMap<K, V>(64, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > 32_768
        }
    }
}


internal fun interface RouteOccupancy {
    fun allows(start: Position, target: Position, fromHeading: Double, toHeading: Double): Boolean
}

internal class ClearanceAStarPolicy(
    private val candidatePolicy: PathCandidatePolicy,
    private val pointPolicy: PathPointPolicy,
    private val terrain: PlanningTerrainPolicy,
) : PathPolicy {
    override fun findPath(map: PlanningTerrain, start: Position, target: Position, radius: Double,
                          counters: PathSearchCounters?, targetRange: Double,
                          hull: HullRoute?, occupancy: RouteOccupancy?): List<Position>? {
        require(targetRange.isFinite() && targetRange >= 0.0)
        if (targetRange > 0.0) {
            if (counters != null) counters.actualSearchCalls++
            return findPathToRange(map, start, target, radius, targetRange, counters)
        }
        val search = beginExact(map, start, target, radius, hull, occupancy, counters)
        while (!search.finished) search.advance(PathWorkBudget(Int.MAX_VALUE))
        return search.path
    }

    override fun beginExact(map: PlanningTerrain, start: Position, target: Position, radius: Double,
                            hull: HullRoute?, occupancy: RouteOccupancy?, counters: PathSearchCounters?): ExactPathSearch =
        ClearPathSearch(map, start, target, radius, hull, occupancy, counters)

    private data class Node(val key: PathGridKey, val g: Int, val f: Int)
    private class Frontier {
        val open = PriorityQueue<Node>(nodeOrder)
        val parents = HashMap<PathGridKey, PathGridKey>()
        val costs = HashMap<PathGridKey, Int>()
    }

    private enum class SearchPhase { START, CLEARANCE, SEARCH, RECONSTRUCT, SIMPLIFY, FINISHED }

    private inner class ClearPathSearch(
        private val map: PlanningTerrain,
        private val start: Position,
        private val target: Position,
        private val radius: Double,
        private val hull: HullRoute?,
        private val occupancy: RouteOccupancy?,
        override val counters: PathSearchCounters?,
    ) : ExactPathSearch {
        private val checkEverySegment = hull != null || occupancy != null
        private val offsets = if (hull is RectangularDeparture) rectangleOffsets else orthogonal
        private var phase = SearchPhase.START
        private var startKey: PathGridKey? = null
        private var goal: Pair<Int, Int>? = null
        private var hullGrid: HullTerrainGrid? = null
        private var clearance: BooleanArray? = null
        private var cellsChecked = 0
        private var frontier: Frontier? = null
        private var rollback: PathSearchRollback? = null
        private var cursor: PathGridKey? = null
        private var useDepartureLattice = false
        private var rawPoints: MutableList<Position>? = null
        private var simplified: MutableList<Position>? = null
        private var anchor = start
        private var simplifyStart = 0
        private var simplifyProbe = 0
        override var path: List<Position>? = null
            private set
        override val finished: Boolean get() = phase == SearchPhase.FINISHED

        override fun fork(rollback: PathSearchRollback): ExactPathSearch =
            ClearPathSearch(map, start, target, radius, hull, occupancy, counters?.copy()).also {
                it.phase = phase
                it.startKey = startKey
                it.goal = goal
                it.hullGrid = hullGrid
                // Clearance writes only beyond cellsChecked. The old prefix is intact after a failed step.
                it.clearance = clearance
                it.cellsChecked = cellsChecked
                it.frontier = frontier
                if (frontier != null || rawPoints != null || simplified != null) it.rollback = rollback
                it.cursor = cursor
                it.useDepartureLattice = useDepartureLattice
                it.rawPoints = rawPoints
                it.simplified = simplified
                it.anchor = anchor
                it.simplifyStart = simplifyStart
                it.simplifyProbe = simplifyProbe
                it.path = path
            }

        override fun advance(budget: PathWorkBudget, cache: PathClearanceCache?) {
            while (!finished && budget.take(workCost(cache))) {
                when (phase) {
                    SearchPhase.START -> initialize(cache)
                    SearchPhase.CLEARANCE -> prepareClearance(cache)
                    SearchPhase.SEARCH -> expand(cache)
                    SearchPhase.RECONSTRUCT -> reconstruct()
                    SearchPhase.SIMPLIFY -> simplifyOne()
                    SearchPhase.FINISHED -> error("Completed path cannot consume more work")
                }
            }
        }

        private fun workCost(cache: PathClearanceCache?): Int =
            if (phase == SearchPhase.SEARCH && cache != null && hull is RectangularDeparture &&
                occupancy == null && !useDepartureLattice) 1 else 8

        private fun complete(points: List<Position>? = null) {
            path = points
            phase = SearchPhase.FINISHED
        }

        private fun initialize(cache: PathClearanceCache?) {
            if (counters != null) counters.actualSearchCalls++
            val width = map.widthTiles.toDouble() * map.tileWidth
            val height = map.heightTiles.toDouble() * map.tileHeight
            if (!MapBounds.contains(width, height, start, radius) ||
                !MapBounds.contains(width, height, target, radius)) return complete()
            val startCell = cellOf(map, start) ?: return complete()
            goal = cellOf(map, target) ?: return complete()
            if (terrain.intersects(map, start, radius) || terrain.intersects(map, target, radius)) return complete()
            hullGrid = hull?.let { HullTerrainGrid.authorized(map) }
            // Every orientation contains this disk, so an obstructed disk rules out the endpoint.
            if (hull is RectangularDeparture && hull.maneuver &&
                !UnitContact.poseFits(MovementCollisionShape.Circle(minOf(hull.shape.length, hull.shape.width) / 2.0),
                    target, 0.0, checkNotNull(hullGrid))) return complete()
            val heading = hull?.heading ?: 0.0
            if (arrival(start, target, heading, cache) != null) return complete(listOf(target))
            startKey = PathGridKey(startCell.first, startCell.second, heading)
            val cached = cache?.get(map, radius)
            clearance = cached ?: BooleanArray(map.widthTiles * map.heightTiles)
            if (cached == null) phase = SearchPhase.CLEARANCE else beginQueue()
        }

        private fun prepareClearance(cache: PathClearanceCache?) {
            val cells = checkNotNull(clearance)
            val limit = minOf(cells.size, cellsChecked + CLEARANCE_CELLS_PER_WORK)
            while (cellsChecked < limit) {
                val index = cellsChecked++
                if (counters != null) counters.candidatesChecked++
                cells[index] = candidatePolicy.allows(map, index % map.widthTiles, index / map.widthTiles, radius)
            }
            if (cellsChecked == cells.size) {
                cache?.put(map, radius, cells)
                beginQueue()
            }
        }

        private fun beginQueue() {
            val (col, row) = checkNotNull(goal)
            if (!isCandidate(col, row) && !useDepartureLattice && !canUseDepartureLattice()) return complete()
            val first = checkNotNull(startKey)
            val routes = Frontier()
            frontier = routes
            routes.costs[first] = 0
            enqueue(routes, first, 0)
            phase = SearchPhase.SEARCH
        }

        private fun isCandidate(col: Int, row: Int): Boolean = checkNotNull(clearance)[row * map.widthTiles + col]

        // A legal center can lie between tile centers, for example in a two-tile canal.
        // Keep the grid spacing while preserving the departure's offset within each cell.
        private fun canUseDepartureLattice(): Boolean {
            if ((hull as? RectangularDeparture)?.maneuver != true || useDepartureLattice) return false
            val key = checkNotNull(startKey)
            return start != pointPolicy.point(map, key.col, key.row)
        }

        private fun routePoint(col: Int, row: Int): Position {
            if (!useDepartureLattice) return pointPolicy.point(map, col, row)
            val origin = checkNotNull(startKey)
            return Position(start.x + (col - origin.col).toDouble() * map.tileWidth,
                start.y + (row - origin.row).toDouble() * map.tileHeight)
        }

        private fun arrival(from: Position, point: Position, heading: Double, cache: PathClearanceCache?): Double? {
            if (cache != null && hull is RectangularDeparture && occupancy == null)
                return cache.rectangularArrival(map, checkNotNull(hullGrid), hull, from, point, heading)
            val nextHeading = if (hull == null) {
                if (!PlanningSegmentClearance.allows(map, from, point, radius)) return null
                heading
            } else hull.arrival(checkNotNull(hullGrid), from, point, heading) ?: return null
            return nextHeading.takeIf { occupancy?.allows(from, point, heading, nextHeading) != false }
        }

        private fun enqueue(routes: Frontier, key: PathGridKey, cost: Int) {
            val (goalCol, goalRow) = checkNotNull(goal)
            val remaining = if (hull == null) heuristic(key.col, key.row, goalCol, goalRow)
                else maxOf(kotlin.math.abs(key.col - goalCol), kotlin.math.abs(key.row - goalRow))
            if (counters != null) {
                counters.enqueued++
                counters.heuristicCalls++
            }
            val node = Node(key, cost, cost + remaining)
            routes.open += node
            rollback?.record { check(routes.open.remove(node)) }
        }

        private fun expand(cache: PathClearanceCache?) {
            val routes = checkNotNull(frontier)
            val current = routes.open.poll()
            if (current == null) {
                if (canUseDepartureLattice()) {
                    useDepartureLattice = true
                    beginQueue()
                } else complete()
                return
            }
            rollback?.record { routes.open += current }
            if (counters != null) counters.popped++
            val key = current.key
            if (routes.costs[key] != current.g) return
            val from = if (key == startKey) start else routePoint(key.col, key.row)
            val targetCell = checkNotNull(goal)
            val reached = if (checkEverySegment) arrival(from, target, key.heading, cache) != null
                else key.col == targetCell.first && key.row == targetCell.second
            if (reached) {
                cursor = key
                rawPoints = mutableListOf()
                phase = SearchPhase.RECONSTRUCT
                return
            }
            if (counters != null) counters.expanded++
            fun visit(next: PathGridKey) {
                val nextCost = current.g + 1
                val previousCost = routes.costs[next]
                if (nextCost < (previousCost ?: Int.MAX_VALUE)) {
                    routes.costs[next] = nextCost
                    val previousParent = routes.parents.put(next, key)
                    rollback?.record {
                        if (previousCost == null) routes.costs.remove(next) else routes.costs[next] = previousCost
                        if (previousParent == null) routes.parents.remove(next) else routes.parents[next] = previousParent
                    }
                    enqueue(routes, next, nextCost)
                }
            }
            if (cache != null && hull is RectangularDeparture && occupancy == null && !useDepartureLattice) {
                val neighbors = cache.rectangularNeighbors(map, hull, radius, from, key.heading) {
                    buildList { scanNeighbors(key, from, cache) { add(it) } }
                }
                neighbors.forEach(::visit)
            } else scanNeighbors(key, from, cache, ::visit)
        }

        private inline fun scanNeighbors(key: PathGridKey, from: Position, cache: PathClearanceCache?,
                                         accept: (PathGridKey) -> Unit) {
            for ((dx, dy) in offsets) {
                val col = key.col + dx
                val row = key.row + dy
                if (col !in 0 until map.widthTiles || row !in 0 until map.heightTiles) continue
                if (counters != null) counters.neighborsChecked++
                if (if (useDepartureLattice) map.isBlocked(col, row) else !isCandidate(col, row)) continue
                val point = routePoint(col, row)
                val heading = if (checkEverySegment) arrival(from, point, key.heading, cache) ?: continue else {
                    if (key == startKey && !PlanningSegmentClearance.allows(map, from, point, radius)) continue
                    0.0
                }
                accept(PathGridKey(col, row, heading))
            }
        }

        private fun reconstruct() {
            val key = checkNotNull(cursor)
            val points = checkNotNull(rawPoints)
            if (key != startKey) {
                appendRoutePoint(points, routePoint(key.col, key.row))
                cursor = checkNotNull(frontier).parents.getValue(key)
                return
            }
            frontier = null
            points.reverse()
            rollback?.record { points.reverse() }
            if (points.lastOrNull() != target) appendRoutePoint(points, target)
            if (checkEverySegment) {
                complete(points)
            } else {
                simplifyProbe = points.lastIndex
                simplified = mutableListOf()
                phase = SearchPhase.SIMPLIFY
            }
        }

        private fun simplifyOne() {
            val points = checkNotNull(rawPoints)
            if (simplifyStart == points.size) return complete(checkNotNull(simplified))
            if (simplifyProbe < simplifyStart) return complete()
            val next = points[simplifyProbe]
            if (PlanningSegmentClearance.allows(map, anchor, next, radius)) {
                anchor = next
                appendPoint(checkNotNull(simplified), next)
                simplifyStart = simplifyProbe + 1
                simplifyProbe = points.lastIndex
            } else {
                simplifyProbe--
            }
        }

        private fun appendPoint(points: MutableList<Position>, point: Position) {
            points += point
            rollback?.record { points.removeAt(points.lastIndex) }
        }

        private fun appendRoutePoint(points: MutableList<Position>, point: Position) {
            val last = points.lastIndex
            if ((hull as? RectangularDeparture)?.maneuver == true && last >= 1 &&
                ScreenPlane.desired(points[last - 1], points[last]) == ScreenPlane.desired(points[last], point)) {
                val replaced = points.set(last, point)
                rollback?.record { points[last] = replaced }
            } else appendPoint(points, point)
        }
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
        const val CLEARANCE_CELLS_PER_WORK = 32
        val orthogonal = listOf(0 to -1, -1 to 0, 1 to 0, 0 to 1)
        val rectangleOffsets = orthogonal + listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)
        val nodeOrder = compareBy<Node> { it.f }.thenBy { it.key.row }.thenBy { it.key.col }
            .thenBy { it.g }.thenBy { it.key.heading }
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
        fun forUnit(unit: MutableUnit, maneuver: Boolean = false): HullRoute? = when (val shape = UnitLocomotion.terrainHull(unit)) {
            is MovementCollisionShape.Rectangle -> {
                val movement = UnitLocomotion.movement(unit)
                val useManeuver = maneuver && unit.mechState == null && !movement.slidingMode
                RectangularDeparture(shape, unit.heading, !useManeuver && movement.reverseSpeedPercentage > 0.95, useManeuver)
            }
            is MovementCollisionShape.Circle -> Circle(shape.radius)
            null -> null
        }
    }
}

internal data class RectangularDeparture(
    val shape: MovementCollisionShape.Rectangle,
    override val heading: Double,
    val reverse: Boolean,
    val maneuver: Boolean = false,
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
    override fun allows(map: PlanningTerrain, col: Int, row: Int, radius: Double): Boolean {
        if (map.isBlocked(col, row)) return false
        if (terrain !== PlanningCenterTerrain) return !terrain.intersects(map, TileCenters.point(map, col, row), radius)
        return MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth, map.heightTiles.toDouble() * map.tileHeight,
            (col + 0.5) * map.tileWidth, (row + 0.5) * map.tileHeight, radius)
    }
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
