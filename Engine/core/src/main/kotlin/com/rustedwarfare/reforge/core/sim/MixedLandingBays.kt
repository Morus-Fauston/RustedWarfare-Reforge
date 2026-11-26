package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Survey the transverse row at the requested parking depth. Each bounded opening supplies a
 * centre line. The open apron in front of it must stay clear for vehicles still entering. */
internal class MixedLandingBays(private val click: Position, private val horizontal: Boolean, private val reach: Double) {
    private var ground: PlanningTerrain? = null
    private var next = 0
    private var first = -1
    private var openings = emptyList<Position>()
    private var complete = false
    fun openApron(terrain: PlanningTerrain): Boolean = ground === terrain && complete && openings.isEmpty()
    private data class Band(val low: Int, val high: Int, val crossMid: Double)
    private var bands = emptyList<Band>()
    private var bandIndex = 0
    private var backward = 0
    private var forward = 0
    private var lowerDone = false

    private data class Trial(val id: String, val site: Position)
    private data class Pose(val position: Position, val heading: Double) {
        companion object {
            fun geometry(unit: MutableUnit) = Pose(unit.position,
                if (unit.definition.movementCollision is MovementCollisionShape.Circle) 0.0 else unit.heading)
        }
    }
    private data class Obstacle(val id: String, val pose: Pose, val shape: MovementCollisionShape)
    private enum class Stage { DIRECT, ELBOW, SEARCH, VERIFY, DONE }
    private data class Witness(val terrain: PlanningTerrain, val traffic: List<Obstacle>, val origin: Pose,
                               var stage: Stage, var path: List<Position>?, var leg: Int = 0,
                               var from: Pose = origin, var search: ExactPathSearch? = null) {
        fun fork(rollback: PathSearchRollback) = copy(search = search?.fork(rollback))
    }
    private val trials = mutableMapOf<Trial, Witness>()
    private var lastServed: String? = null

    fun fork(rollback: PathSearchRollback) = MixedLandingBays(click, horizontal, reach).also {
        for ((key, witness) in trials) it.trials[key] = witness.fork(rollback)
        it.lastServed = lastServed
        it.ground = ground
        it.next = next
        it.first = first
        it.openings = openings
        it.complete = complete
        it.bands = bands
        it.bandIndex = bandIndex
        it.backward = backward
        it.forward = forward
        it.lowerDone = lowerDone
    }

    /** Null means more work; an empty list delegates ordinary open ground back to the lattice. */
    fun sites(terrain: PlanningTerrain, budget: PathWorkBudget): List<Position>? {
        val tile = if (horizontal) terrain.tileHeight else terrain.tileWidth
        val cross = if (horizontal) click.y else click.x
        val count = if (horizontal) terrain.heightTiles else terrain.widthTiles
        val centre = floor(cross / tile).toInt().coerceIn(0, count - 1)
        val span = ceil(reach / tile).toInt()
        val lo = maxOf(0, centre - span)
        val hi = minOf(count - 1, centre + span)
        if (ground !== terrain) {
            trials.clear()
            ground = terrain
            next = lo
            first = -1
            openings = emptyList()
            bands = emptyList()
            bandIndex = 0
            backward = 0
            forward = 0
            lowerDone = false
            complete = false
        }
        if (!complete) {
            val column = floor(click.x / terrain.tileWidth).toInt().coerceIn(0, terrain.widthTiles - 1)
            val row = floor(click.y / terrain.tileHeight).toInt().coerceIn(0, terrain.heightTiles - 1)
            while (next <= hi + 1) {
                if (!budget.take(1)) return null
                val open = next <= hi && !(if (horizontal) terrain.isBlocked(column, next) else terrain.isBlocked(next, row))
                if (open && first == -1) first = next
                if (!open && first >= 0) {
                    // A cut edge inside the survey is not a wall.
                    if ((first > lo || lo == 0) && (next <= hi || hi == count - 1)) {
                        val mid = (first + next) * 0.5 * tile
                        bands = bands + Band(first, next - 1, mid)
                    }
                    first = -1
                }
                next++
            }
            // Follow the sides of each opening back to its mouth and forward to its end.
            // A click anywhere in the same recess addresses the same usable interior.
            val depth = if (horizontal) column else row
            val depthCells = if (horizontal) terrain.widthTiles else terrain.heightTiles
            val depthTile = if (horizontal) terrain.tileWidth else terrain.tileHeight
            fun open(d: Int, c: Int) = d in 0 until depthCells && c in 0 until count &&
                !(if (horizontal) terrain.isBlocked(d, c) else terrain.isBlocked(c, d))
            if (bands.size >= 2 && bands.any { centre in it.low..it.high }) while (bandIndex < bands.size) {
                if (!budget.take(1)) return null
                val band = bands[bandIndex]
                val d = if (!lowerDone) depth - backward - 1 else depth + forward + 1
                val same = abs(d - depth) <= span && open(d, band.low) && open(d, band.high) &&
                    !open(d, band.low - 1) && !open(d, band.high + 1)
                if (!lowerDone && same) backward++ else if (!lowerDone) lowerDone = true else if (same) forward++ else {
                    val mid = (2.0 * depth - backward + forward + 1.0) * depthTile / 2.0
                    openings = openings + if (horizontal) Position(mid, band.crossMid) else Position(band.crossMid, mid)
                    bandIndex++
                    backward = 0
                    forward = 0
                    lowerDone = false
                }
            }
            complete = true
        }
        return openings
    }

    internal sealed interface Decision {
        data object Pending : Decision
        data object Ordinary : Decision
        data class Claim(val car: MutableUnit, val path: List<Position>) : Decision
    }

    /** Allocate at most the last two unclaimed bays. A peer can protect a scarce bay before
     * reaching it. Only a vehicle which has reached the parking area can acquire its own claim. */
    fun choose(members: List<MutableUnit>, candidates: List<Position>, occupied: (MutableUnit, Position, Double) -> Boolean,
               near: (Position) -> Boolean, terrain: (MutableUnit) -> PlanningTerrain, paths: PathPolicy,
               budget: PathWorkBudget, advance: (ExactPathSearch, PathWorkBudget) -> Unit,
               obstacles: (MutableUnit) -> List<MutableUnit>): Decision {
        val pending = members.filter { it.landingMember?.let { state -> !state.parked && state.claimed == null } == true }
        if (pending.size !in 1..2) return Decision.Ordinary
        val minGroupRadius = members.minOfOrNull { it.definition.movementCollision.boundingRadius } ?: 0.0
        val maxGroupRadius = members.maxOfOrNull { it.definition.movementCollision.boundingRadius } ?: 0.0
        val hasLargerInGroup = maxGroupRadius > minGroupRadius + 0.5 ||
            members.any { it.definition.movementCollision is MovementCollisionShape.Rectangle }
        val hasLargerPending = pending.any {
            it.definition.movementCollision is MovementCollisionShape.Rectangle ||
                it.definition.movementCollision.boundingRadius > minGroupRadius + 0.5
        }
        if (hasLargerInGroup && !hasLargerPending) return Decision.Ordinary
        val ready = pending.filter { near(it.position) && it.moveTarget != null && it.pathPoints.isNotEmpty() }
            .sortedBy { ScreenPlane.distanceSquared(it.position, click) }
        val car = ready.firstOrNull { it.unitId != lastServed } ?: ready.firstOrNull() ?: return Decision.Pending
        lastServed = car.unitId
        val peer = pending.firstOrNull { it !== car }
        val traffic = pending.associate { it.unitId to obstacles(it) }
        fun routeFor(body: MutableUnit, site: Position): Witness =
            route(body, site, terrain(body), traffic.getValue(body.unitId), paths, budget, advance)
        fun arrivalHeading(body: MutableUnit, path: List<Position>): Double {
            val end = path.last()
            val prev = if (path.size >= 2) path[path.size - 2] else body.position
            return if (prev != end) ScreenPlane.desired(prev, end) else body.heading
        }
        for (site in candidates.sortedBy { ScreenPlane.distanceSquared(car.position, it) }) {
            if (occupied(car, site, car.heading)) continue
            val own = routeFor(car, site)
            if (own.stage != Stage.DONE) return Decision.Pending
            val path = own.path ?: continue
            if (occupied(car, site, arrivalHeading(car, path))) continue
            if (peer != null && candidates.none { other ->
                    if (site == other || occupied(peer, other, peer.heading)) false else {
                        val alternative = routeFor(peer, other)
                        if (alternative.stage != Stage.DONE) return Decision.Pending
                        val altPath = alternative.path ?: return@none false
                        !occupied(peer, other, arrivalHeading(peer, altPath))
                    }
                }) continue
            return Decision.Claim(car, path)
        }
        // The bay survey owns these openings, including a temporarily blocked approach.
        return Decision.Pending
    }

    private fun route(body: MutableUnit, site: Position, terrain: PlanningTerrain, obstacles: List<MutableUnit>,
                      paths: PathPolicy, budget: PathWorkBudget, advance: (ExactPathSearch, PathWorkBudget) -> Unit): Witness {
        val key = Trial(body.unitId, site)
        val pose = Pose.geometry(body)
        val traffic = obstacles.map { Obstacle(it.unitId, Pose.geometry(it), it.definition.movementCollision) }
        val shape = body.definition.movementCollision
        val grid = HullTerrainGrid.authorized(terrain)
        val occupancy = UnitContact.routeAround(body, obstacles)
        fun fresh(hint: List<Position>? = null) = Witness(terrain, traffic, pose,
            if (hint == null) Stage.DIRECT else Stage.VERIFY, hint ?: listOf(site)).also { trials[key] = it }
        var w = trials[key] ?: fresh()
        if (w.terrain !== terrain || w.traffic != traffic) w = fresh()
        if (w.origin != pose && w.stage != Stage.SEARCH) w = fresh(w.path.takeIf { w.stage == Stage.DONE })
        val hull = checkNotNull(HullRoute.forUnit(body, maneuver = true))
        while (w.stage != Stage.DONE) {
            if (w.stage == Stage.SEARCH) {
                if (budget.exhausted) return w
                val radius = if (shape is MovementCollisionShape.Rectangle) minOf(shape.width, shape.length) / 2 else shape.boundingRadius
                val query = w.search ?: paths.beginExact(terrain, body.position, site, radius, hull, occupancy).also { w.search = it }
                if (!query.finished) advance(query, budget)
                if (!query.finished) return w
                val path = query.path?.takeIf { it.lastOrNull() == site }
                if (path == null && w.origin != pose) { w = fresh(); continue }
                w = Witness(terrain, traffic, pose, if (path == null) Stage.DONE else Stage.VERIFY, path)
                trials[key] = w
                continue
            }
            if (!budget.take(1)) break
            val points = checkNotNull(w.path)
            val end = points[w.leg]
            val heading = if (end == w.from.position) w.from.heading else hull.arrival(grid, w.from.position, end, w.from.heading)
            if (heading != null && UnitContact.poseFits(shape, end, heading, grid) &&
                occupancy?.allows(w.from.position, end, w.from.heading, heading) != false && obstacles.none {
                    UnitContact.posesOverlap(shape, end, heading, it.definition.movementCollision, it.position, it.heading)
                }) {
                w.from = Pose(end, heading)
                if (++w.leg == points.size) w.stage = Stage.DONE
                continue
            }
            // A circular endpoint has no alternate heading; no detour can make an occupied endpoint free.
            val impossible = shape is MovementCollisionShape.Circle && (!UnitContact.poseFits(shape, site, pose.heading, grid) ||
                obstacles.any { UnitContact.posesOverlap(shape, site, pose.heading, it.definition.movementCollision, it.position, it.heading) })
            w.leg = 0
            w.from = pose
            when {
                impossible || w.stage == Stage.VERIFY -> { w.stage = Stage.DONE; w.path = null }
                w.stage == Stage.DIRECT -> {
                    val elbow = if (horizontal) Position(pose.position.x, site.y) else Position(site.x, pose.position.y)
                    w.path = listOf(elbow, site)
                    w.stage = Stage.ELBOW
                }
                else -> { w.path = null; w.stage = Stage.SEARCH }
            }
        }
        return w
    }

    companion object {
        fun clears(body: MutableUnit, points: List<Position>, terrain: PlanningTerrain): Boolean {
            val hull = HullRoute.forUnit(body, maneuver = true) ?: return false
            val grid = HullTerrainGrid.authorized(terrain)
            var start = body.position
            var heading = body.heading
            for (target in points) {
                if (target == start) continue
                heading = hull.arrival(grid, start, target, heading) ?: return false
                if (!UnitContact.poseFits(body.definition.movementCollision, target, heading, grid)) return false
                start = target
            }
            return true
        }

        fun forApproach(click: Position, dx: Double, dy: Double, reach: Double) =
            MixedLandingBays(click, abs(dx) >= abs(dy), reach)
    }
}
