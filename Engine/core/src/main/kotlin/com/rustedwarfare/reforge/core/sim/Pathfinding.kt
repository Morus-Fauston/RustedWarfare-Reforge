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

    fun beginNearest(map: PlanningTerrain, start: Position, target: Position, radius: Double,
                     hull: HullRoute? = null, occupancy: RouteOccupancy? = null,
                     counters: PathSearchCounters? = null): ExactPathSearch
}


internal data class PathGridKey(val col: Int, val row: Int, val heading: Double = 0.0,
                                val initialPose: Boolean = false)

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
    val reuseApproach: Boolean = true,
    private val rectangleChecks: RectangleChecks = RectangleChecks(),
    private val departureNeighbors: MutableMap<DepartureSource, List<PathGridKey>> = HashMap(),
) {
    private data class Approach(val terrain: PlanningTerrain, val radius: Double, val start: Position,
                                val path: List<Position>)
    private var approach: Approach? = null

    fun fork(): PathClearanceCache = PathClearanceCache(HashMap(grids), reuseApproach, rectangleChecks, departureNeighbors).also {
        it.approach = approach
    }

    fun rememberApproach(map: PlanningTerrain, radius: Double, start: Position, path: List<Position>) {
        if (path.size in 2..8) approach = Approach(map, radius, start, path.toList())
    }

    fun approachTo(map: PlanningTerrain, radius: Double, start: Position, target: Position): List<Position>? {
        val saved = approach ?: return null
        if (saved.terrain !== map || saved.radius != radius || saved.start != start) return null
        val reach = minOf(map.tileWidth, map.tileHeight).toDouble()
        if (ScreenPlane.distanceSquared(target, saved.path.last()) > reach * reach) return null
        val prefixEnd = saved.path[saved.path.lastIndex - 1]
        if (!PlanningSegmentClearance.allows(map, prefixEnd, target, radius)) return null
        return saved.path.dropLast(1) + target
    }

    internal data class DepartureSource(val map: PlanningTerrain, val shape: MovementCollisionShape.Rectangle,
                                       val radius: Double, val reverse: Boolean, val origin: Position,
                                       val from: Position, val heading: Double)

    fun departureNeighbors(map: PlanningTerrain, hull: RectangularDeparture, radius: Double, origin: Position,
                           from: Position, heading: Double): List<PathGridKey>? =
        departureNeighbors[DepartureSource(map, hull.shape, radius, hull.reverse, origin, from, heading)]

    fun rememberDepartureNeighbors(map: PlanningTerrain, hull: RectangularDeparture, radius: Double, origin: Position,
                                   from: Position, heading: Double, neighbors: List<PathGridKey>,
                                   rollback: PathSearchRollback?) {
        // This cache changes deterministic work cost, so candidate-step writes must be undone.
        // Saturate instead of evicting; a failed step must also preserve the admission order.
        if (departureNeighbors.size >= 32_768) return
        val key = DepartureSource(map, hull.shape, radius, hull.reverse, origin, from, heading)
        if (key in departureNeighbors) return
        departureNeighbors[key] = neighbors
        rollback?.record { departureNeighbors.remove(key) }
    }
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
    val nearestBoundaryCount: Int get() = 0
    fun nearestBoundary(index: Int, target: Position): Position? = null
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

    override fun beginNearest(map: PlanningTerrain, start: Position, target: Position, radius: Double,
                              hull: HullRoute?, occupancy: RouteOccupancy?, counters: PathSearchCounters?): ExactPathSearch =
        ClearPathSearch(map, start, target, radius, hull, occupancy, counters, nearest = true)

    private data class Node(val key: PathGridKey, val g: Int, val f: Int)
    private class Frontier {
        val open = PriorityQueue<Node>(nodeOrder)
        val parents = HashMap<PathGridKey, PathGridKey>()
        val costs = HashMap<PathGridKey, Int>()
    }

    private enum class SearchPhase { START, APPROACH, CLEARANCE, SEARCH, BOUNDARY, RECONSTRUCT, SIMPLIFY, FINISHED }

    private inner class ClearPathSearch(
        private val map: PlanningTerrain,
        private val start: Position,
        private val target: Position,
        private val radius: Double,
        private val hull: HullRoute?,
        private val occupancy: RouteOccupancy?,
        override val counters: PathSearchCounters?,
        private val nearest: Boolean = false,
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
        private var nearestKey: PathGridKey? = null
        private var endpoint = target
        private var nearestDistance = Double.POSITIVE_INFINITY
        private var nearestCenterDistance = Double.POSITIVE_INFINITY
        private var boundaryKey: PathGridKey? = null
        private var boundaryIndex = 0
        override var path: List<Position>? = null
            private set
        override val finished: Boolean get() = phase == SearchPhase.FINISHED

        override fun fork(rollback: PathSearchRollback): ExactPathSearch =
            ClearPathSearch(map, start, target, radius, hull, occupancy, counters?.copy(), nearest).also {
                it.phase = phase
                it.startKey = startKey
                it.goal = goal
                it.hullGrid = hullGrid
                // Clearance writes only beyond cellsChecked. The old prefix is intact after a failed step.
                it.clearance = clearance
                it.cellsChecked = cellsChecked
                it.frontier = frontier
                it.rollback = rollback
                it.cursor = cursor
                it.useDepartureLattice = useDepartureLattice
                it.rawPoints = rawPoints
                it.simplified = simplified
                it.anchor = anchor
                it.simplifyStart = simplifyStart
                it.simplifyProbe = simplifyProbe
                it.path = path
                it.nearestKey = nearestKey
                it.endpoint = endpoint
                it.nearestDistance = nearestDistance
                it.nearestCenterDistance = nearestCenterDistance
                it.boundaryKey = boundaryKey
                it.boundaryIndex = boundaryIndex
            }

        override fun advance(budget: PathWorkBudget, cache: PathClearanceCache?) {
            while (!finished && budget.take(workCost(cache))) {
                when (phase) {
                    SearchPhase.START -> initialize(cache)
                    SearchPhase.APPROACH -> checkApproach(cache)
                    SearchPhase.CLEARANCE -> prepareClearance(cache)
                    SearchPhase.SEARCH -> expand(cache)
                    SearchPhase.BOUNDARY -> considerOccupiedBoundary()
                    SearchPhase.RECONSTRUCT -> reconstruct()
                    SearchPhase.SIMPLIFY -> simplifyOne(cache)
                    SearchPhase.FINISHED -> error("Completed path cannot consume more work")
                }
            }
        }

        private fun workCost(cache: PathClearanceCache?): Int {
            if (phase != SearchPhase.SEARCH || cache == null || hull !is RectangularDeparture || occupancy != null) return 8
            if (!useDepartureLattice) return 1
            val key = frontier?.open?.peek()?.key ?: return 8
            val from = if (key == startKey) start else routePoint(key.col, key.row)
            return if (cache.departureNeighbors(map, hull, radius, start, from, key.heading) != null) 1 else 8
        }

        private fun complete(points: List<Position>? = null) {
            path = points
            phase = SearchPhase.FINISHED
        }

        private fun initialize(cache: PathClearanceCache?) {
            if (counters != null) counters.actualSearchCalls++
            val width = map.widthTiles.toDouble() * map.tileWidth
            val height = map.heightTiles.toDouble() * map.tileHeight
            if (!MapBounds.contains(width, height, start, radius) ||
                !MapBounds.contains(width, height, target, if (nearest) 0.0 else radius)) return complete()
            val startCell = cellOf(map, start) ?: return complete()
            goal = cellOf(map, target) ?: return complete()
            if (terrain.intersects(map, start, radius) || !nearest && terrain.intersects(map, target, radius)) return complete()
            hullGrid = if (hull != null || nearest) HullTerrainGrid.authorized(map) else null
            // Every orientation contains this disk, so an obstructed disk rules out the endpoint.
            if (!nearest && hull is RectangularDeparture && hull.maneuver &&
                !UnitContact.poseFits(MovementCollisionShape.Circle(minOf(hull.shape.length, hull.shape.width) / 2.0),
                    target, 0.0, checkNotNull(hullGrid))) return complete()
            val heading = hull?.heading ?: 0.0
            if (nearest && !canLeaveStart()) return complete()
            if (arrival(start, target, heading, cache) != null) return complete(listOf(target))
            // A displaced pose and its tile center are different reachable
            // states. Merging them prevents a legal rejoin around a bank corner.
            startKey = PathGridKey(startCell.first, startCell.second, heading,
                initialPose = start != pointPolicy.point(map, startCell.first, startCell.second))
            if (nearest && start != pointPolicy.point(map, startCell.first, startCell.second)) useDepartureLattice = true
            if (!nearest && !checkEverySegment && cache?.reuseApproach == true) phase = SearchPhase.APPROACH
            else loadClearance(cache)
        }

        // A shortcut still needs a charged final segment check. A rejected probe resumes
        // the exact search without repeating the direct segment on the next step.
        private fun checkApproach(cache: PathClearanceCache?) {
            val route = cache?.approachTo(map, radius, start, target)
            if (route != null) complete(route) else loadClearance(cache)
        }

        private fun loadClearance(cache: PathClearanceCache?) {
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
            if (!nearest && !isCandidate(col, row) && !useDepartureLattice && !canUseDepartureLattice()) return complete()
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
            val canDepartBetweenCenters = (hull as? RectangularDeparture)?.maneuver == true || hull == null && occupancy != null
            if (!canDepartBetweenCenters || useDepartureLattice) return false
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
                } else if (nearestKey != null) {
                    cursor = nearestKey
                    rawPoints = mutableListOf()
                    phase = SearchPhase.RECONSTRUCT
                } else complete()
                return
            }
            rollback?.record { routes.open += current }
            if (counters != null) counters.popped++
            val key = current.key
            if (routes.costs[key] != current.g) return
            val from = if (key == startKey) start else routePoint(key.col, key.row)
            if (nearest) considerNearest(key, from)
            val targetCell = checkNotNull(goal)
            val reached = if (nearest) nearestDistance == 0.0
                else if (checkEverySegment) arrival(from, target, key.heading, cache) != null
                else key.col == targetCell.first && key.row == targetCell.second
            if (reached) {
                cursor = if (nearest) nearestKey else key
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
            } else if (cache != null && hull is RectangularDeparture && occupancy == null && useDepartureLattice) {
                val neighbors = cache.departureNeighbors(map, hull, radius, start, from, key.heading)
                    ?: buildList { scanNeighbors(key, from, cache) { add(it) } }.also {
                        cache.rememberDepartureNeighbors(map, hull, radius, start, from, key.heading, it, rollback)
                    }
                neighbors.forEach(::visit)
            } else scanNeighbors(key, from, cache, ::visit)
            if (nearest && (occupancy?.nearestBoundaryCount ?: 0) > 0) {
                boundaryKey = key
                boundaryIndex = 0
                phase = SearchPhase.BOUNDARY
            }
        }

        /** One occupant's continuous extremum per work unit, with scalar rollback state. */
        private fun considerOccupiedBoundary() {
            val obstacles = checkNotNull(occupancy)
            if (boundaryIndex == obstacles.nearestBoundaryCount) {
                phase = SearchPhase.SEARCH
                return
            }
            val probe = obstacles.nearestBoundary(boundaryIndex++, target) ?: return
            val key = checkNotNull(boundaryKey)
            val from = if (key == startKey) start else routePoint(key.col, key.row)
            val shape = (hull as? HullRoute.Circle)?.let { MovementCollisionShape.Circle(it.radius) }
                ?: if (hull == null) MovementCollisionShape.Circle(radius) else return
            val heading = ScreenPlane.desired(from, probe)
            if (!UnitContact.poseFits(shape, probe, heading, checkNotNull(hullGrid)) ||
                arrival(from, probe, key.heading, null) == null) return
            val distance = MoveArrival.distanceToHull(shape, probe, heading, target)
            val center = ScreenPlane.distanceSquared(probe, target)
            if (distance < nearestDistance || distance == nearestDistance && center < nearestCenterDistance) {
                nearestDistance = distance
                nearestCenterDistance = center
                nearestKey = key
                endpoint = probe
            }
        }

        private fun canLeaveStart(): Boolean {
            val shape = when (hull) {
                is RectangularDeparture -> hull.shape
                is HullRoute.Circle -> MovementCollisionShape.Circle(hull.radius)
                null -> MovementCollisionShape.Circle(radius)
            }
            val grid = checkNotNull(hullGrid)
            val before = hull?.heading ?: 0.0
            val headings = listOf(before, ScreenPlane.desired(start, target)) + (0..7).map { it * 45.0 }
            val travel = minOf(map.tileWidth, map.tileHeight) / 4.0
            val roundoff = 128.0 * Math.ulp(maxOf(kotlin.math.abs(start.x), kotlin.math.abs(start.y)))
            for (heading in headings) {
                if (shape is MovementCollisionShape.Rectangle &&
                    RectangularTurn.resolve(grid, shape, start, before, heading) != heading) continue
                val drift = ScreenPlane.displacement(heading, travel)
                val aim = Position(start.x + drift.x, start.y + drift.y)
                val end = when (shape) {
                    is MovementCollisionShape.Circle -> CircularTerrain(shape.radius).resolve(grid, start, aim)
                    is MovementCollisionShape.Rectangle -> RectangularTerrain(shape, heading).resolve(grid, start, aim)
                }
                var low = 0.0
                var high = 1.0
                repeat(48) {
                    val mid = (low + high) / 2.0
                    val point = Position(start.x + (end.x - start.x) * mid, start.y + (end.y - start.y) * mid)
                    if (UnitContact.poseFits(shape, point, heading, grid) &&
                        occupancy?.allows(start, point, before, heading) != false) low = mid else high = mid
                }
                val dx = (end.x - start.x) * low
                val dy = (end.y - start.y) * low
                if (dx * dx + dy * dy > roundoff * roundoff) return true
            }
            return false
        }

        private fun considerNearest(key: PathGridKey, from: Position) {
            val shape = when (hull) {
                is RectangularDeparture -> hull.shape
                is HullRoute.Circle -> MovementCollisionShape.Circle(hull.radius)
                null -> MovementCollisionShape.Circle(radius)
            }
            val grid = checkNotNull(hullGrid)
            val direction = ScreenPlane.desired(from, target)
            val headings = if (shape is MovementCollisionShape.Rectangle) listOf(direction, key.heading) else listOf(direction)
            for (heading in headings) {
                if (shape is MovementCollisionShape.Rectangle &&
                    (RectangularTurn.resolve(grid, shape, from, key.heading, heading) != heading ||
                        !UnitContact.poseFits(shape, from, heading, grid))) continue
                val aim = if (shape is MovementCollisionShape.Rectangle && heading == key.heading && heading != direction) {
                    val forward = ScreenPlane.displacement(heading, 1.0)
                    val travel = maxOf(0.0, (target.x - from.x) * forward.x + (target.y - from.y) * forward.y)
                    Position(from.x + forward.x * travel, from.y + forward.y * travel)
                } else target
                var point = when (shape) {
                    is MovementCollisionShape.Circle -> CircularTerrain(shape.radius).resolve(grid, from, aim)
                    is MovementCollisionShape.Rectangle -> RectangularTerrain(shape, heading).resolve(grid, from, aim)
                }
                fun legal(probe: Position) = UnitContact.poseFits(shape, probe, heading, grid) &&
                    occupancy?.allows(from, probe, key.heading, heading) != false
                if (!legal(point)) {
                    var low = 0.0
                    var high = 1.0
                    val end = point
                    // Fixed-size continuous refinement, independent of searched map size.
                    repeat(48) {
                        val mid = (low + high) / 2.0
                        val probe = Position(from.x + (end.x - from.x) * mid, from.y + (end.y - from.y) * mid)
                        if (legal(probe)) low = mid else high = mid
                    }
                    point = Position(from.x + (end.x - from.x) * low, from.y + (end.y - from.y) * low)
                }
                if (!legal(point)) continue
                // A clipped ray need not meet an axis-aligned bank at its closest
                // tangent when the click lies between lattice rows or columns.
                val probes = if (shape is MovementCollisionShape.Circle)
                    listOf(point, Position(point.x, target.y), Position(target.x, point.y)) else listOf(point)
                for (probe in probes) {
                    if (probe != point && (!legal(probe) || arrival(from, probe, key.heading, null) == null)) continue
                    val distance = MoveArrival.distanceToHull(shape, probe, if (probe == from) key.heading else heading, target)
                    val center = ScreenPlane.distanceSquared(probe, target)
                    if (distance < nearestDistance || distance == nearestDistance && center < nearestCenterDistance) {
                        nearestDistance = distance
                        nearestCenterDistance = center
                        nearestKey = key
                        endpoint = probe
                    }
                }
            }
        }

        private inline fun scanNeighbors(key: PathGridKey, from: Position, cache: PathClearanceCache?,
                                         accept: (PathGridKey) -> Unit) {
            val directions = if (key == startKey && from != routePoint(key.col, key.row)) listOf(0 to 0) + offsets else offsets
            for ((dx, dy) in directions) {
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
            if (points.lastOrNull() != endpoint) appendRoutePoint(points, endpoint)
            if (checkEverySegment) {
                complete(points)
            } else {
                simplifyProbe = points.lastIndex
                simplified = mutableListOf()
                phase = SearchPhase.SIMPLIFY
            }
        }

        private fun simplifyOne(cache: PathClearanceCache?) {
            val points = checkNotNull(rawPoints)
            if (simplifyStart == points.size) {
                val route = checkNotNull(simplified)
                if (!nearest) cache?.rememberApproach(map, radius, start, route)
                return complete(route)
            }
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
