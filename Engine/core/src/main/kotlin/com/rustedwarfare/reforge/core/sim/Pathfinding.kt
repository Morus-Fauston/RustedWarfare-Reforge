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
    private var _remaining = limit
    val remaining: Int get() = _remaining
    val used: Int get() = limit - _remaining
    val exhausted: Boolean get() = _remaining == 0

    fun take(cost: Int = 8): Boolean {
        if (_remaining < cost) {
            _remaining = 0
            return false
        }
        _remaining -= cost
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
    private var commitHooks: MutableList<() -> Unit>? = null
    private var committed: Boolean = false

    fun record(undo: () -> Unit) {
        if (committed) return
        val recorded = changes ?: mutableListOf<() -> Unit>().also { changes = it }
        recorded += undo
    }

    fun onCommit(action: () -> Unit) {
        if (committed) return
        val hooks = commitHooks ?: mutableListOf<() -> Unit>().also { commitHooks = it }
        hooks += action
    }

    fun commit() {
        committed = true
        changes = null
        val hooks = commitHooks
        commitHooks = null
        hooks?.forEach { it() }
    }

    fun restore() {
        commitHooks = null
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
    private val frontierPool: ArrayDeque<PrimitiveGridFrontier> = ArrayDeque(2)

    // Share the same frontierPool across candidate forks: checkouts register rollback.record(release),
    // and releases register rollback.onCommit(release) so an uncommitted/aborted step NEVER exposes
    // a still-live frontier to another search while committed steps reuse the primitive IntArrays.
    fun fork(): PathClearanceCache = PathClearanceCache(
        HashMap(grids), reuseApproach, rectangleChecks, departureNeighbors,
    ).also {
        it.approach = approach
        it.sharedPool = sharedPool
    }

    private var sharedPool: ArrayDeque<PrimitiveGridFrontier> = frontierPool

    internal val pooledFrontierCount: Int get() = sharedPool.size

    fun acquirePrimitiveFrontier(width: Int, height: Int, rollback: PathSearchRollback?): PrimitiveGridFrontier {
        val pool = sharedPool
        val iter = pool.iterator()
        while (iter.hasNext()) {
            val f = iter.next()
            if (f.width == width && f.height == height) {
                iter.remove()
                f.resetForNextSearch()
                rollback?.record { releaseNow(f) }
                return f
            }
        }
        return PrimitiveGridFrontier(width, height, initialPoseIndex = width * height)
    }

    fun releasePrimitiveFrontier(frontier: PrimitiveGridFrontier, rollback: PathSearchRollback?) {
        if (rollback == null) {
            releaseNow(frontier)
        } else {
            rollback.onCommit { releaseNow(frontier) }
        }
    }

    private fun releaseNow(frontier: PrimitiveGridFrontier) {
        val pool = sharedPool
        if (pool.size < 2 && pool.none { it === frontier }) {
            pool.addLast(frontier)
        }
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
    val simplifyRoute: Boolean get() = false
    val nearestBoundaryCount: Int get() = 0
    fun nearestBoundary(index: Int, target: Position): Position? = null
}

internal class ClearanceAStarPolicy(
    private val candidatePolicy: PathCandidatePolicy,
    private val pointPolicy: PathPointPolicy,
    private val terrain: PlanningTerrainPolicy,
) : PathPolicy {
    private val usesDefaultCenterClearance: Boolean =
        candidatePolicy is CenterClearance && candidatePolicy.usesPlanningCenterTerrain && terrain === PlanningCenterTerrain
    override fun findPath(map: PlanningTerrain, start: Position, target: Position, radius: Double,
                          counters: PathSearchCounters?, targetRange: Double,
                          hull: HullRoute?, occupancy: RouteOccupancy?): List<Position>? {
        require(targetRange.isFinite() && targetRange >= 0.0)
        if (targetRange > 0.0) {
            if (counters != null) counters.actualSearchCalls++
            return findPathToRange(map, start, target, radius, targetRange, hull, occupancy, counters)
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
        private val fastOrthogonalCircleStep = hull is HullRoute.Circle &&
            hull.radius <= 0.5 * minOf(map.tileWidth, map.tileHeight).toDouble() &&
            occupancy == null && pointPolicy === TileCenters
        private val usesPrimitiveFrontier = hull !is RectangularDeparture
        private val offsets = if (hull is RectangularDeparture) rectangleOffsets else orthogonal
        private var phase = SearchPhase.START
        private var startKey: PathGridKey? = null
        private var startStateIndex: Int = -1
        private var goal: Pair<Int, Int>? = null
        private var hullGrid: HullTerrainGrid? = null
        private var clearance: BooleanArray? = null
        private var cellsChecked = 0
        private var frontier: Frontier? = null
        private var primitiveFrontier: PrimitiveGridFrontier? = null
        private var rollback: PathSearchRollback? = null
        private var activeCache: PathClearanceCache? = null
        private var cursor: PathGridKey? = null
        private var cursorState: Int = -1
        private var useDepartureLattice = false
        private var rawPoints: MutableList<Position>? = null
        private var simplified: MutableList<Position>? = null
        private var anchor = start
        private var simplifyStart = 0
        private var simplifyProbe = 0
        private var nearestKey: PathGridKey? = null
        private var nearestState: Int = -1
        private var endpoint = target
        private var nearestDistance = Double.POSITIVE_INFINITY
        private var nearestCenterDistance = Double.POSITIVE_INFINITY
        private var boundaryKey: PathGridKey? = null
        private var boundaryState: Int = -1
        private var boundaryIndex = 0
        private var localExpanded = 0
        override var path: List<Position>? = null
            private set
        override val finished: Boolean get() = phase == SearchPhase.FINISHED

        override fun fork(rollback: PathSearchRollback): ExactPathSearch =
            ClearPathSearch(map, start, target, radius, hull, occupancy, counters?.copy(), nearest).also {
                it.phase = phase
                it.startKey = startKey
                it.startStateIndex = startStateIndex
                it.goal = goal
                it.hullGrid = hullGrid
                // Clearance writes only beyond cellsChecked. The old prefix is intact after a failed step.
                it.clearance = clearance
                it.cellsChecked = cellsChecked
                it.frontier = frontier
                it.primitiveFrontier = primitiveFrontier
                it.rollback = rollback
                it.cursor = cursor
                it.cursorState = cursorState
                it.useDepartureLattice = useDepartureLattice
                it.rawPoints = rawPoints
                it.simplified = simplified
                it.anchor = anchor
                it.simplifyStart = simplifyStart
                it.simplifyProbe = simplifyProbe
                it.path = path
                it.nearestKey = nearestKey
                it.nearestState = nearestState
                it.endpoint = endpoint
                it.nearestDistance = nearestDistance
                it.nearestCenterDistance = nearestCenterDistance
                it.boundaryKey = boundaryKey
                it.boundaryState = boundaryState
                it.boundaryIndex = boundaryIndex
                it.localExpanded = localExpanded
            }

        override fun advance(budget: PathWorkBudget, cache: PathClearanceCache?) {
            activeCache = cache
            while (!finished && budget.take(workCost(cache))) {
                when (phase) {
                    SearchPhase.START -> initialize(cache)
                    SearchPhase.APPROACH -> checkApproach(cache)
                    SearchPhase.CLEARANCE -> prepareClearance(cache)
                    SearchPhase.SEARCH -> if (usesPrimitiveFrontier) expandPrimitive(cache) else expand(cache)
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

        private fun releaseFrontier() {
            val finishedFrontier = primitiveFrontier ?: return
            primitiveFrontier = null
            activeCache?.releasePrimitiveFrontier(finishedFrontier, rollback)
        }

        private fun complete(points: List<Position>? = null) {
            releaseFrontier()
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
            if (!nearest && hull !is RectangularDeparture && occupancy != null &&
                !occupancy.allows(target, target, heading, heading)) return complete()
            if (nearest && !canLeaveStart()) return complete()
            if (arrival(start, target, heading, cache) != null) return complete(listOf(target))
            // A displaced pose and its tile center are different reachable
            // states. Merging them prevents a legal rejoin around a bank corner.
            startKey = PathGridKey(startCell.first, startCell.second, heading,
                initialPose = start != pointPolicy.point(map, startCell.first, startCell.second))
            if (nearest && start != pointPolicy.point(map, startCell.first, startCell.second)) useDepartureLattice = true
            if (!nearest && usesDefaultCenterClearance && hull !is RectangularDeparture && !useDepartureLattice) {
                val (goalCol, goalRow) = checkNotNull(goal)
                if (!map.isCellConnected(startCell.first, startCell.second, goalCol, goalRow) &&
                    !canUseDepartureLattice()) {
                    return complete()
                }
            }
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
            if (usesDefaultCenterClearance) {
                beginQueue(cache)
                return
            }
            val cached = cache?.get(map, radius)
            clearance = cached ?: BooleanArray(map.widthTiles * map.heightTiles)
            if (cached == null) phase = SearchPhase.CLEARANCE else beginQueue(cache)
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
                beginQueue(cache)
            }
        }

        private fun beginQueue(cache: PathClearanceCache? = null) {
            val (col, row) = checkNotNull(goal)
            if (!nearest && !checkEverySegment && !isCandidate(col, row) && !useDepartureLattice && !canUseDepartureLattice()) return complete()
            val first = checkNotNull(startKey)
            if (usesPrimitiveFrontier) {
                val prior = primitiveFrontier
                val pf = if (prior != null && rollback == null) {
                    prior.resetForNextSearch()
                    prior
                } else {
                    if (prior != null) {
                        primitiveFrontier = null
                        (cache ?: activeCache)?.releasePrimitiveFrontier(prior, rollback)
                    }
                    val cellCount = map.widthTiles * map.heightTiles
                    (cache ?: activeCache)?.acquirePrimitiveFrontier(map.widthTiles, map.heightTiles, rollback)
                        ?: PrimitiveGridFrontier(map.widthTiles, map.heightTiles, initialPoseIndex = cellCount)
                }
                primitiveFrontier = pf
                val startIdx = if (first.initialPose) map.widthTiles * map.heightTiles else pf.stateIndex(first.col, first.row)
                startStateIndex = startIdx
                val remaining = heuristic(first.col, first.row, col, row)
                if (counters != null) {
                    counters.enqueued++
                    counters.heuristicCalls++
                }
                pf.setRootWithStart(startIdx, remaining, first.col, first.row, rollback)
            } else {
                val routes = Frontier()
                frontier = routes
                routes.costs[first] = 0
                enqueue(routes, first, 0)
            }
            val prevExpanded = localExpanded
            localExpanded = 0
            rollback?.record { localExpanded = prevExpanded }
            phase = SearchPhase.SEARCH
        }

        private fun isCandidate(col: Int, row: Int): Boolean {
            if (usesDefaultCenterClearance) {
                if (counters != null) counters.candidatesChecked++
                return map.allowsCenter(col, row, radius)
            }
            return checkNotNull(clearance)[row * map.widthTiles + col]
        }

        // A legal center can lie between tile centers, for example in a two-tile canal.
        // Keep the grid spacing while preserving the departure's offset within each cell.
        private fun canUseDepartureLattice(): Boolean {
            val canDepartBetweenCenters = (hull as? RectangularDeparture)?.maneuver == true ||
                (hull == null && occupancy != null) ||
                (hull is HullRoute.Circle && occupancy != null && localExpanded <= 4)
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

        private fun expandPrimitive(cache: PathClearanceCache?) {
            val pf = checkNotNull(primitiveFrontier)
            val sKey = checkNotNull(startKey)
            val startCol = sKey.col
            val startRow = sKey.row
            var currentState = -1
            var currentG = 0
            val hasEntry = pf.poll(startCol, startRow, rollback) { st, g, _ ->
                currentState = st
                currentG = g
            }
            if (!hasEntry) {
                if (canUseDepartureLattice()) {
                    useDepartureLattice = true
                    beginQueue(cache)
                } else if (nearestState >= 0) {
                    cursorState = nearestState
                    rawPoints = mutableListOf()
                    phase = SearchPhase.RECONSTRUCT
                } else complete()
                return
            }
            if (counters != null) counters.popped++
            if (pf.costOf(currentState) != currentG) return
            val curCol = pf.colOf(currentState, startCol)
            val curRow = pf.rowOf(currentState, startRow)
            val isStart = currentState == startStateIndex
            val from = if (isStart) start else routePoint(curCol, curRow)
            if (nearest) considerNearestPrimitive(currentState, from)
            val (goalCol, goalRow) = checkNotNull(goal)
            val reached = if (nearest) nearestDistance == 0.0
                else if (checkEverySegment) arrival(from, target, 0.0, cache) != null
                else curCol == goalCol && curRow == goalRow
            if (reached) {
                cursorState = if (nearest) nearestState else currentState
                rawPoints = mutableListOf()
                phase = SearchPhase.RECONSTRUCT
                return
            }
            if (counters != null) counters.expanded++
            val prevLocalExp = localExpanded
            localExpanded = prevLocalExp + 1
            rollback?.record { localExpanded = prevLocalExp }
            val nextG = currentG + 1
            val w = map.widthTiles
            val h = map.heightTiles
            if (isStart && from != routePoint(curCol, curRow)) {
                considerPrimitiveNeighbor(pf, currentState, from, curCol, curRow, goalCol, goalRow, startCol, startRow, nextG, cache, isStart)
            }
            if (curRow > 0) considerPrimitiveNeighbor(pf, currentState, from, curCol, curRow - 1, goalCol, goalRow, startCol, startRow, nextG, cache, isStart)
            if (curCol > 0) considerPrimitiveNeighbor(pf, currentState, from, curCol - 1, curRow, goalCol, goalRow, startCol, startRow, nextG, cache, isStart)
            if (curCol + 1 < w) considerPrimitiveNeighbor(pf, currentState, from, curCol + 1, curRow, goalCol, goalRow, startCol, startRow, nextG, cache, isStart)
            if (curRow + 1 < h) considerPrimitiveNeighbor(pf, currentState, from, curCol, curRow + 1, goalCol, goalRow, startCol, startRow, nextG, cache, isStart)

            if (nearest && (occupancy?.nearestBoundaryCount ?: 0) > 0) {
                boundaryState = currentState
                boundaryIndex = 0
                phase = SearchPhase.BOUNDARY
            }
        }

        private fun considerPrimitiveNeighbor(
            pf: PrimitiveGridFrontier,
            currentState: Int,
            from: Position,
            col: Int,
            row: Int,
            goalCol: Int,
            goalRow: Int,
            startCol: Int,
            startRow: Int,
            nextG: Int,
            cache: PathClearanceCache?,
            isStart: Boolean,
        ) {
            if (counters != null) counters.neighborsChecked++
            val nextState = pf.stateIndex(col, row)
            if (nextG >= pf.costOf(nextState)) return
            if (if (useDepartureLattice) map.isBlocked(col, row) else !isCandidate(col, row)) return
            val point = routePoint(col, row)
            if (checkEverySegment) {
                if (!(fastOrthogonalCircleStep && !isStart && !useDepartureLattice)) {
                    if (arrival(from, point, 0.0, cache) == null) return
                }
            } else if (isStart && !PlanningSegmentClearance.allows(map, from, point, radius)) {
                return
            }
            val rem = heuristic(col, row, goalCol, goalRow)
            if (pf.tryRelaxWithStart(nextState, currentState, nextG, nextG + rem, startCol, startRow, rollback)) {
                if (counters != null) {
                    counters.enqueued++
                    counters.heuristicCalls++
                }
            }
        }

        private fun expand(cache: PathClearanceCache?) {
            val routes = checkNotNull(frontier)
            val current = routes.open.poll()
            if (current == null) {
                if (canUseDepartureLattice()) {
                    useDepartureLattice = true
                    beginQueue(cache)
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
            val shape = (hull as? HullRoute.Circle)?.let { MovementCollisionShape.Circle(it.radius) }
                ?: if (hull == null) MovementCollisionShape.Circle(radius) else return
            // A circle scores the same at every heading. Only a better endpoint needs a swept route check.
            val distance = MoveArrival.distanceToHull(shape, probe, 0.0, target)
            val center = ScreenPlane.distanceSquared(probe, target)
            if (!(distance < nearestDistance || distance == nearestDistance && center < nearestCenterDistance)) return
            val from: Position
            val fromHeading: Double
            if (usesPrimitiveFrontier) {
                val st = boundaryState
                val pf = checkNotNull(primitiveFrontier)
                val sKey = checkNotNull(startKey)
                from = if (st == startStateIndex) start else routePoint(pf.colOf(st, sKey.col), pf.rowOf(st, sKey.row))
                fromHeading = 0.0
            } else {
                val key = checkNotNull(boundaryKey)
                from = if (key == startKey) start else routePoint(key.col, key.row)
                fromHeading = key.heading
            }
            val heading = ScreenPlane.desired(from, probe)
            if (!UnitContact.poseFits(shape, probe, heading, checkNotNull(hullGrid)) ||
                arrival(from, probe, fromHeading, null) == null) return
            nearestDistance = distance
            nearestCenterDistance = center
            if (usesPrimitiveFrontier) nearestState = boundaryState else nearestKey = boundaryKey
            endpoint = probe
        }

        private fun canLeaveStart(): Boolean {
            val shape = when (hull) {
                is RectangularDeparture -> hull.shape
                is HullRoute.Circle -> MovementCollisionShape.Circle(hull.radius)
                null -> MovementCollisionShape.Circle(radius)
            }
            val circleBoundR = (hull as? HullRoute.Circle)?.boundaryRadius ?: radius
            val grid = checkNotNull(hullGrid)
            if (shape is MovementCollisionShape.Circle && circleBoundR > shape.radius) {
                val sc = cellOf(map, start)
                if (sc != null) {
                    val hasPassableOrtho = orthogonal.any { (dc, dr) ->
                        val nc = sc.first + dc
                        val nr = sc.second + dr
                        nc in 0 until map.widthTiles && nr in 0 until map.heightTiles && !map.isBlocked(nc, nr)
                    }
                    if (!hasPassableOrtho && circleBoundR >= minOf(map.tileWidth, map.tileHeight) * 0.5) {
                        return false
                    }
                }
            }
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
                    is MovementCollisionShape.Circle -> CircularTerrain(shape.radius, circleBoundR).resolve(grid, start, aim)
                    is MovementCollisionShape.Rectangle -> RectangularTerrain(shape, heading).resolve(grid, start, aim)
                }
                var low = 0.0
                var high = 1.0
                repeat(48) {
                    val mid = (low + high) / 2.0
                    val point = Position(start.x + (end.x - start.x) * mid, start.y + (end.y - start.y) * mid)
                    if (MapBounds.contains(grid.width, grid.height, point, if (shape is MovementCollisionShape.Circle) circleBoundR else 0.0) &&
                        UnitContact.poseFits(shape, point, heading, grid) &&
                        occupancy?.allows(start, point, before, heading) != false) low = mid else high = mid
                }
                val dx = (end.x - start.x) * low
                val dy = (end.y - start.y) * low
                if (dx * dx + dy * dy > roundoff * roundoff) return true
            }
            return false
        }

        private fun considerNearestPrimitive(state: Int, from: Position) {
            val circleBoundR = (hull as? HullRoute.Circle)?.boundaryRadius ?: radius
            val terrainR = when (hull) {
                is HullRoute.Circle -> hull.radius
                else -> radius
            }
            val shape = MovementCollisionShape.Circle(terrainR)
            val grid = checkNotNull(hullGrid)
            val direction = ScreenPlane.desired(from, target)
            val end = CircularTerrain(terrainR, circleBoundR).resolve(grid, from, target)
            fun legal(point: Position): Boolean =
                MapBounds.contains(grid.width, grid.height, point, circleBoundR) &&
                    UnitContact.poseFits(shape, point, direction, grid) &&
                    occupancy?.allows(from, point, 0.0, direction) != false
            val point = if (legal(end)) end else {
                var low = 0.0
                var high = 1.0
                repeat(48) {
                    val mid = (low + high) / 2.0
                    val candidate = Position(from.x + (end.x - from.x) * mid, from.y + (end.y - from.y) * mid)
                    if (legal(candidate)) low = mid else high = mid
                }
                Position(from.x + (end.x - from.x) * low, from.y + (end.y - from.y) * low)
            }
            if (!legal(point)) return
            val probes = listOf(point, Position(point.x, target.y), Position(target.x, point.y))
            for (probe in probes) {
                val distance = MoveArrival.distanceToHull(shape, probe, if (probe == from) 0.0 else direction, target)
                val center = ScreenPlane.distanceSquared(probe, target)
                if (distance < nearestDistance || distance == nearestDistance && center < nearestCenterDistance) {
                    if (probe != point && (!legal(probe) || arrival(from, probe, 0.0, null) == null)) continue
                    nearestDistance = distance
                    nearestCenterDistance = center
                    nearestState = state
                    endpoint = probe
                }
            }
        }

        private fun considerNearest(key: PathGridKey, from: Position) {
            val circleBoundR = (hull as? HullRoute.Circle)?.boundaryRadius ?: radius
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
                    is MovementCollisionShape.Circle -> CircularTerrain(shape.radius, circleBoundR).resolve(grid, from, aim)
                    is MovementCollisionShape.Rectangle -> RectangularTerrain(shape, heading).resolve(grid, from, aim)
                }
                fun legal(probe: Position): Boolean {
                    val inBounds = if (shape is MovementCollisionShape.Circle) {
                        MapBounds.contains(grid.width, grid.height, probe, circleBoundR)
                    } else true
                    return inBounds && UnitContact.poseFits(shape, probe, heading, grid) &&
                        occupancy?.allows(from, probe, key.heading, heading) != false
                }
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
                    if (!legal(point)) continue
                }
                // A clipped ray need not meet an axis-aligned bank at its closest
                // tangent when the click lies between lattice rows or columns.
                val probes = if (shape is MovementCollisionShape.Circle)
                    listOf(point, Position(point.x, target.y), Position(target.x, point.y)) else listOf(point)
                for (probe in probes) {
                    val distance = MoveArrival.distanceToHull(shape, probe, if (probe == from) key.heading else heading, target)
                    val center = ScreenPlane.distanceSquared(probe, target)
                    if (distance < nearestDistance || distance == nearestDistance && center < nearestCenterDistance) {
                        if (probe != point && (!legal(probe) || arrival(from, probe, key.heading, null) == null)) continue
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
            val points = checkNotNull(rawPoints)
            if (usesPrimitiveFrontier) {
                val st = cursorState
                if (st != startStateIndex) {
                    val pf = checkNotNull(primitiveFrontier)
                    val sKey = checkNotNull(startKey)
                    appendRoutePoint(points, routePoint(pf.colOf(st, sKey.col), pf.rowOf(st, sKey.row)))
                    cursorState = pf.parentOf(st)
                    return
                }
                releaseFrontier()
            } else {
                val key = checkNotNull(cursor)
                if (key != startKey) {
                    appendRoutePoint(points, routePoint(key.col, key.row))
                    cursor = checkNotNull(frontier).parents.getValue(key)
                    return
                }
                frontier = null
            }
            points.reverse()
            rollback?.record { points.reverse() }
            if (points.lastOrNull() != endpoint) appendRoutePoint(points, endpoint)
            if (checkEverySegment && ((hull as? HullRoute.Circle)?.simplify != true || occupancy?.simplifyRoute == false)) {
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
                if (!nearest && !checkEverySegment) cache?.rememberApproach(map, radius, start, route)
                return complete(route)
            }
            if (simplifyProbe < simplifyStart) return complete()
            val next = points[simplifyProbe]
            if (arrival(anchor, next, 0.0, cache) != null) {
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
    private fun findPathToRange(
        map: PlanningTerrain,
        start: Position,
        target: Position,
        radius: Double,
        range: Double,
        hull: HullRoute? = null,
        occupancy: RouteOccupancy? = null,
        counters: PathSearchCounters?,
    ): List<Position>? {
        val width = map.widthTiles.toDouble() * map.tileWidth
        val height = map.heightTiles.toDouble() * map.tileHeight
        val terrainR = when (hull) {
            is HullRoute.Circle -> hull.radius
            is RectangularDeparture -> minOf(hull.shape.length, hull.shape.width) * 0.5
            null -> 0.0
        }
        val boundR = when (hull) {
            is HullRoute.Circle -> maxOf(radius, hull.boundaryRadius)
            else -> radius
        }
        if (!MapBounds.contains(width, height, start, boundR) || terrain.intersects(map, start, boundR)) return null
        val startKey = cellOf(map, start) ?: return null
        val hullGrid = if (terrainR > 0.0) HullTerrainGrid.authorized(map) else null
        val circle = if (terrainR > 0.0) CircularTerrain(terrainR, boundR) else null
        val circleShape = if (terrainR > 0.0) MovementCollisionShape.Circle(terrainR) else null
        if (hullGrid != null && circleShape != null &&
            !UnitContact.poseFits(MovementCollisionShape.Circle((terrainR - 1e-6).coerceAtLeast(0.0)), start, 0.0, hullGrid)
        ) {
            return null
        }

        val rangeSq = range * range
        if (ScreenPlane.distanceSquared(start, target) < rangeSq &&
            (hullGrid == null || circleShape == null || UnitContact.poseFits(circleShape, start, 0.0, hullGrid))
        ) {
            return listOf(start)
        }

        val w = map.widthTiles
        val h = map.heightTiles
        val total = w * h
        val tw = map.tileWidth.toDouble()
        val th = map.tileHeight.toDouble()
        val targetCol = kotlin.math.floor(target.x / tw).toInt().coerceIn(0, w - 1)
        val targetRow = kotlin.math.floor(target.y / th).toInt().coerceIn(0, h - 1)
        val rangeManhattanSlack = kotlin.math.ceil(range / tw).toInt() + kotlin.math.ceil(range / th).toInt() + 1

        fun segClear(from: Position, to: Position): Boolean {
            if (occupancy != null) {
                val heading = if (from == to) 0.0 else ScreenPlane.desired(from, to)
                if (!occupancy.allows(from, to, heading, heading)) return false
            }
            if (circle != null && hullGrid != null) {
                return circle.contains(hullGrid, from) && circle.resolve(hullGrid, from, to) == to
            }
            return PlanningSegmentClearance.allows(map, from, to, boundR)
        }

        fun poseClear(pos: Position): Boolean {
            if (!MapBounds.contains(width, height, pos, boundR) || terrain.intersects(map, pos, boundR)) return false
            if (hullGrid != null && circleShape != null) {
                if (!UnitContact.poseFits(circleShape, pos, 0.0, hullGrid)) return false
            }
            if (occupancy != null && !occupancy.allows(pos, pos, 0.0, 0.0)) return false
            return true
        }

        fun cellPoint(col: Int, row: Int): Position? {
            if (counters != null) counters.candidatesChecked++
            if (map.isBlocked(col, row)) return null
            var left = maxOf(col * tw, boundR)
            var top = maxOf(row * th, boundR)
            var right = minOf(Math.nextDown((col + 1.0) * tw), width - boundR)
            var bottom = minOf(Math.nextDown((row + 1.0) * th), height - boundR)
            if (terrainR > 0.0) {
                if (col > 0 && map.isBlocked(col - 1, row)) left = maxOf(left, col * tw + terrainR)
                if (col + 1 < w && map.isBlocked(col + 1, row)) right = minOf(right, (col + 1.0) * tw - terrainR)
                if (row > 0 && map.isBlocked(col, row - 1)) top = maxOf(top, row * th + terrainR)
                if (row + 1 < h && map.isBlocked(col, row + 1)) bottom = minOf(bottom, (row + 1.0) * th - terrainR)
            }
            if (left > right || top > bottom) return null
            val center = pointPolicy.point(map, col, row)
            val point = Position(center.x.coerceIn(left, right), center.y.coerceIn(top, bottom))
            return point.takeIf(::poseClear)
        }

        fun cellGoal(col: Int, row: Int): Position? {
            if (map.isBlocked(col, row)) return null
            var left = maxOf(col * tw, boundR)
            var top = maxOf(row * th, boundR)
            var right = minOf(Math.nextDown((col + 1.0) * tw), width - boundR)
            var bottom = minOf(Math.nextDown((row + 1.0) * th), height - boundR)
            if (terrainR > 0.0) {
                if (col > 0 && map.isBlocked(col - 1, row)) left = maxOf(left, col * tw + terrainR)
                if (col + 1 < w && map.isBlocked(col + 1, row)) right = minOf(right, (col + 1.0) * tw - terrainR)
                if (row > 0 && map.isBlocked(col, row - 1)) top = maxOf(top, row * th + terrainR)
                if (row + 1 < h && map.isBlocked(col, row + 1)) bottom = minOf(bottom, (row + 1.0) * th - terrainR)
            }
            if (left > right || top > bottom) return null
            var goal = Position(target.x.coerceIn(left, right), target.y.coerceIn(top, bottom))
            if (ScreenPlane.distanceSquared(goal, target) >= rangeSq) return null
            if (!poseClear(goal) && circle != null && hullGrid != null) {
                val center = Position(pointPolicy.point(map, col, row).x.coerceIn(left, right), pointPolicy.point(map, col, row).y.coerceIn(top, bottom))
                if (!poseClear(center)) return null
                val swept = circle.resolve(hullGrid, center, goal)
                goal = if (poseClear(swept)) swept else return null
                if (ScreenPlane.distanceSquared(goal, target) >= rangeSq) return null
            }
            return goal.takeIf(::poseClear)
        }

        // Fast-path: check start cell's own clamped goal before touching any frontier!
        val startCellGoal = cellGoal(startKey.first, startKey.second)
        if (startCellGoal != null && segClear(start, startCellGoal)) {
            return listOf(startCellGoal)
        }

        val pf = acquireRangeFrontier(w, h)
        val startIdx = pf.stateIndex(startKey.first, startKey.second)
        val startRem = maxOf(0, heuristic(startKey.first, startKey.second, targetCol, targetRow) - rangeManhattanSlack)
        pf.setRootWithStart(startIdx, startRem, startKey.first, startKey.second, rollback = null)
        if (counters != null) counters.enqueued++
        while (!pf.isEmpty()) {
            var curIdx = -1
            var curG = 0
            pf.poll(startKey.first, startKey.second, rollback = null) { st, g, _ ->
                curIdx = st
                curG = g
            }
            if (counters != null) counters.popped++
            if (pf.costOf(curIdx) != curG) continue
            val curCol = curIdx % w
            val curRow = curIdx / w
            val from = if (curIdx == startIdx) start else cellPoint(curCol, curRow) ?: continue
            val goalPos = cellGoal(curCol, curRow)
            if (goalPos != null && segClear(from, goalPos)) {
                val cellIndices = ArrayList<Int>()
                var cursor = curIdx
                cellIndices += cursor
                while (cursor != startIdx) {
                    cursor = pf.parentOf(cursor)
                    if (cursor < 0) return null
                    cellIndices += cursor
                }
                cellIndices.reverse()
                val path = ArrayList<Position>(cellIndices.size)
                for (k in 1 until cellIndices.size) {
                    val cIdx = cellIndices[k]
                    path += cellPoint(cIdx % w, cIdx / w) ?: return null
                }
                if (path.lastOrNull() != goalPos) path += goalPos
                return simplifyWithClearance(start, path, ::segClear)
            }
            if (counters != null) counters.expanded++
            val nextG = curG + 1
            for (direction in 0..3) {
                val col = curCol + when (direction) { 1 -> -1; 2 -> 1; else -> 0 }
                val row = curRow + when (direction) { 0 -> -1; 3 -> 1; else -> 0 }
                if (col !in 0 until w || row !in 0 until h) continue
                if (counters != null) counters.neighborsChecked++
                val nextIdx = row * w + col
                if (nextG >= pf.costOf(nextIdx)) continue
                val point = cellPoint(col, row) ?: continue
                if (!segClear(from, point)) continue
                val rem = maxOf(0, heuristic(col, row, targetCol, targetRow) - rangeManhattanSlack)
                if (pf.tryRelaxWithStart(nextIdx, curIdx, nextG, nextG + rem, startKey.first, startKey.second, rollback = null)) {
                    if (counters != null) counters.enqueued++
                }
            }
        }
        return null
    }

    private inline fun simplifyWithClearance(
        start: Position,
        points: List<Position>,
        allows: (Position, Position) -> Boolean,
    ): List<Position>? {
        val result = ArrayList<Position>(points.size)
        var anchor = start
        var next = 0
        while (next < points.size) {
            val farthest = (points.lastIndex downTo next).firstOrNull {
                allows(anchor, points[it])
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

        private val rangeFrontierPool = ThreadLocal<PrimitiveGridFrontier?>()

        fun acquireRangeFrontier(w: Int, h: Int): PrimitiveGridFrontier {
            val cached = rangeFrontierPool.get()
            if (cached != null && cached.width == w && cached.height == h) {
                cached.resetForNextSearch()
                return cached
            }
            return PrimitiveGridFrontier(w, h, initialPoseIndex = w * h).also {
                rangeFrontierPool.set(it)
            }
        }
    }
}

internal sealed interface HullRoute {
    val heading: Double
    fun arrival(terrain: HullTerrainGrid, start: Position, target: Position, from: Double): Double?

    data class Circle(
        val radius: Double,
        val boundaryRadius: Double = radius,
        val simplify: Boolean = true,
    ) : HullRoute {
        override val heading = 0.0
        override fun arrival(terrain: HullTerrainGrid, start: Position, target: Position, from: Double): Double? =
            CircularTerrain(radius, boundaryRadius).let {
                if (it.contains(terrain, start) && it.resolve(terrain, start, target) == target) heading else null
            }
    }

    companion object {
        fun forUnit(
            unit: MutableUnit,
            maneuver: Boolean = false,
            tileWidth: Int = 20,
            tileHeight: Int = 20,
        ): HullRoute? = when (val shape = UnitLocomotion.terrainHull(unit, tileWidth, tileHeight)) {
            is MovementCollisionShape.Rectangle -> {
                val movement = UnitLocomotion.movement(unit)
                val useManeuver = maneuver && unit.mechState == null && !movement.slidingMode
                RectangularDeparture(shape, unit.heading, !useManeuver && movement.reverseSpeedPercentage > 0.95, useManeuver)
            }
            is MovementCollisionShape.Circle -> Circle(
                radius = shape.radius,
                boundaryRadius = maxOf(shape.radius, Transport.boundaryRadius(unit)),
                simplify = UnitLocomotion.movement(unit).type != com.rustedwarfare.reforge.core.content.MovementType.WATER,
            )
            null -> null
        }

        fun forUnit(unit: MutableUnit, map: MapDefinition, maneuver: Boolean = false): HullRoute? =
            forUnit(unit, maneuver, map.tileWidth, map.tileHeight)
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
    internal val usesPlanningCenterTerrain: Boolean get() = terrain === PlanningCenterTerrain

    override fun allows(map: PlanningTerrain, col: Int, row: Int, radius: Double): Boolean {
        if (map.isBlocked(col, row)) return false
        if (terrain !== PlanningCenterTerrain) return !terrain.intersects(map, TileCenters.point(map, col, row), radius)
        return map.allowsCenter(col, row, radius)
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
