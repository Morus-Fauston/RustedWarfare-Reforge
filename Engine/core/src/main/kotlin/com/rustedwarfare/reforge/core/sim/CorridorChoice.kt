package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.path.PathSearchCounters
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import kotlin.math.abs
import kotlin.math.hypot

/** Compare the normal approach with one route avoiding the occupied throat.
 * Both distances use world units. Queue length is converted to equivalent driving
 * distance using vehicle spacing, so equal-speed members can compare travel times. */
internal class CorridorChoice {
    private data class Gate(val alongX: Boolean, val center: Position, val halfWidth: Double) : RouteOccupancy {
        fun axis(p: Position) = if (alongX) p.x else p.y
        fun side(p: Position) = if (alongX) p.y else p.x
        override fun allows(start: Position, target: Position, fromHeading: Double, toHeading: Double): Boolean {
            val a = axis(start) - axis(center)
            val b = axis(target) - axis(center)
            if (a * b > 0.0) return true
            if (a == b) return maxOf(side(start), side(target)) < side(center)-halfWidth || minOf(side(start),side(target)) > side(center)+halfWidth
            val y = side(start) + (side(target) - side(start)) * a / (a-b)
            return abs(y - side(center)) > halfWidth
        }
        override val simplifyRoute get() = true
    }
    private var observed: Gate? = null
    private var observedDistance = Double.POSITIVE_INFINITY
    fun crossingSection(): Pair<Boolean, Position>? = (nearest ?: observed)?.let { it.alongX to it.center }
    private var nearest: Gate? = null
    private var nearestDistance = Double.POSITIVE_INFINITY
    private var seen = 0
    private var counted = 0
    private var waiting = 0
    private var alternate: ExactPathSearch? = null
    private var normal: PlannedMove? = null
    fun fork(rollback: PathSearchRollback) = CorridorChoice().also {
        it.observed = observed; it.observedDistance = observedDistance
        it.nearest = nearest; it.nearestDistance = nearestDistance; it.seen = seen
        it.counted = counted; it.waiting = waiting; it.alternate = alternate?.fork(rollback); it.normal = normal
    }
    fun normalRoute() = normal
    fun choose(primary: PlannedMove, member: MovePlanningMember, peers: List<Position>,
               policy: PathPolicy, budget: PathWorkBudget, rollback: PathSearchRollback?): PlannedMove? {
        val slot = primary.slot ?: return primary
        if (primary.nearest || peers.size < 2) return primary
        normal = primary
        val map = member.terrain
        val tile = minOf(map.tileWidth, map.tileHeight).toDouble()
        while (seen < peers.size) {
            if (!budget.take(1)) return null
            val peer = peers[seen++]
            val col = (peer.x / map.tileWidth).toInt(); val row = (peer.y / map.tileHeight).toInt()
            if (col !in 1 until map.widthTiles-1 || row !in 1 until map.heightTiles-1 || map.isBlocked(col,row)) continue
            val alongX = map.isBlocked(col,row-1) && map.isBlocked(col,row+1)
            if (!alongX && !(map.isBlocked(col-1,row) && map.isBlocked(col+1,row))) continue
            val center = Position((col+0.5)*map.tileWidth,(row+0.5)*map.tileHeight)
            val g = Gate(alongX,center,0.5*(if (alongX) map.tileHeight else map.tileWidth))
            val dist = abs(g.axis(center)-g.axis(member.start))
            if (dist < observedDistance && (g.axis(center)-g.axis(member.start))*(g.axis(member.action.target)-g.axis(center)) > 0 &&
                abs(g.side(center)-g.side(member.start)) <= 12*tile) {
                observed = g
                observedDistance = dist
            }
            if ((g.axis(center)-g.axis(member.start))*(g.axis(member.action.target)-g.axis(center)) <= 0.0 ||
                dist < 3*tile || dist > 12*tile || dist >= nearestDistance || abs(g.side(center)-g.side(member.start)) > 4*tile) continue
            // Drivers already inside the single-file approach stay on their original route.
            val dir = (g.axis(center)-g.axis(member.start)).compareTo(0.0)
            val depth = (1..4).takeWhile { n ->
                val x = if (alongX) col - n*dir else col
                val y = if (alongX) row else row - n*dir
                x in 1 until map.widthTiles-1 && y in 1 until map.heightTiles-1 && !map.isBlocked(x,y) &&
                    (if (alongX) map.isBlocked(x,y-1) && map.isBlocked(x,y+1) else map.isBlocked(x-1,y) && map.isBlocked(x+1,y))
            }.size
            val mouthDistance = dist - (depth+0.5)*(if (alongX) map.tileWidth else map.tileHeight)
            if (mouthDistance < 4*tile) continue
            nearest = g; nearestDistance = dist
        }
        val g = nearest ?: return primary
        while (counted < peers.size) {
            if (!budget.take(1)) return null
            val peer = peers[counted++]
            val towards = (g.axis(g.center)-g.axis(member.start)).compareTo(0.0)
            val offset = (g.axis(peer)-g.axis(g.center))*towards
            if (offset in -nearestDistance..(tile*2) && abs(g.side(peer)-g.side(g.center)) <= 2.5*tile) waiting++
        }
        if (waiting < 2 || slot.path.size > 32) return primary
        var start = member.start; var crosses = false; var length = 0.0
        for (point in slot.path) {
            if (!g.allows(start,point,0.0,0.0)) crosses = true
            length += hypot(point.x-start.x, point.y-start.y); start=point
        }
        if (!crosses) return primary
        val search = alternate ?: policy.beginExact(map,member.start,slot.position,member.radius,member.hull,g,
            primary.counters?.let { PathSearchCounters() }).let { if (rollback == null) it else it.fork(rollback) }.also { alternate=it }
        search.advance(budget)
        if (!search.finished) return null
        val totalCounters = primary.counters?.let { a -> search.counters?.let { b -> PathSearchCounters(
            a.actualSearchCalls + b.actualSearchCalls, a.candidatesChecked + b.candidatesChecked,
            a.popped + b.popped, a.expanded + b.expanded, a.enqueued + b.enqueued,
            a.neighborsChecked + b.neighborsChecked, a.heuristicCalls + b.heuristicCalls)
        } ?: a }
        val compared = primary.copy(counters = totalCounters)
        val path = search.path ?: return compared
        var detour = 0.0; start = member.start
        for (point in path) { detour += hypot(point.x-start.x,point.y-start.y); start = point }
        val queueDistance = waiting * (2*member.radius + tile)
        return if (detour < length+queueDistance) compared.copy(slot=GroupMoveDestinations.slot(checkNotNull(member.body),slot.position,path)) else compared
    }
}

/** Locate a barrier separating an empty fork before any driver can witness its traffic. */
internal class BranchSurvey(private val member: MovePlanningMember) {
    private val map = member.terrain
    private val dx = member.action.target.x - member.start.x
    private val dy = member.action.target.y - member.start.y
    private val divisions = kotlin.math.ceil(2 * maxOf(abs(dx)/map.tileWidth, abs(dy)/map.tileHeight)).toInt().coerceAtLeast(1)
    private var probe = 1
    var section: Pair<Boolean, Position>? = null
        private set
    fun fork() = BranchSurvey(member).also { it.probe = probe; it.section = section }
    fun advance(budget: PathWorkBudget): Boolean {
        while (section == null && probe < divisions) {
            if (!budget.take(1)) return false
            val t = probe++.toDouble() / divisions
            val col = kotlin.math.floor((member.start.x + t*dx)/map.tileWidth).toInt()
            val row = kotlin.math.floor((member.start.y + t*dy)/map.tileHeight).toInt()
            if (col !in 0 until map.widthTiles || row !in 0 until map.heightTiles) continue
            val alongX = abs(dx) >= abs(dy)
            val throat = if (alongX) row in 1 until map.heightTiles-1 && map.isBlocked(col,row-1) && map.isBlocked(col,row+1)
                         else col in 1 until map.widthTiles-1 && map.isBlocked(col-1,row) && map.isBlocked(col+1,row)
            if (throat || map.isBlocked(col,row)) {
                section = alongX to Position((col+0.5)*map.tileWidth, (row+0.5)*map.tileHeight)
            }
        }
        return true
    }
}

/** Cross-section of the route selected by this driver on its authorized terrain. */
internal data class BranchPassage(val terrain: PlanningTerrain, val alongX: Boolean, val crossing: Position,
                                  val direction: Int) {
    val depth get() = if (alongX) terrain.tileWidth else terrain.tileHeight
    val width get() = if (alongX) terrain.tileHeight else terrain.tileWidth
    fun along(p: Position) = if (alongX) p.x else p.y
    fun lateral(p: Position) = if (alongX) p.y else p.x
    fun offset(p: Position) = direction * (along(p) - along(crossing))
    fun crossing(from: Position, to: Position): Position? {
        val a = offset(from)
        val b = offset(to)
        if (a == b || a*b > 0.0) return null
        val t = a/(a-b)
        return Position(from.x + t*(to.x-from.x), from.y + t*(to.y-from.y))
    }
}

/** Every route leg and every live peer read consumes one planning work unit. */
internal class BranchRepair(private val branch: BranchPassage, private val group: GroupMoveRequest,
                            private val start: Position, private val path: List<Position>) {
    private var leg = 0
    private var changed = false
    var nextBranch: BranchPassage? = null
        private set
    private var peer = 0
    fun fork() = BranchRepair(branch, group, start, path).also {
        it.leg = leg; it.changed = changed; it.peer = peer; it.nextBranch = nextBranch
    }
    fun preserve(driver: MutableUnit, step: Long, budget: PathWorkBudget, lookup: (String) -> MutableUnit?): Boolean? {
        while (!changed && leg < path.size) {
            if (!budget.take(1)) return null
            val a = if (leg == 0) start else path[leg-1]
            val b = path[leg++]
            val cross = branch.crossing(a, b) ?: continue
            nextBranch = if (branch.offset(b) > branch.offset(a)) branch.copy(crossing = cross) else null
            changed = nextBranch == null ||
                abs(branch.lateral(cross)-branch.lateral(branch.crossing)) > branch.width
        }
        if (!changed || branch.offset(driver.position) > 2*branch.depth) return false
        if (driver.lastRoadProgress?.let { it.group === group && step-it.step in 0..8 } == true) return true
        while (peer < group.trafficOrder.size) {
            if (!budget.take(1)) return null
            val other = lookup(group.trafficOrder[peer++]) ?: continue
            if (other === driver || !other.alive || other.landingMember?.group !== group) continue
            val motion = other.lastRoadProgress ?: continue
            if (motion.group !== group || step-motion.step !in 0..8) continue
            val offset = branch.offset(other.position)
            val lateral = branch.lateral(other.position) - branch.lateral(branch.crossing)
            if (offset < branch.offset(driver.position) || abs(offset) > 2*branch.depth || abs(lateral) > branch.width) continue
            val forward = (if (branch.alongX) motion.dx else motion.dy) * branch.direction
            val sideways = if (branch.alongX) motion.dy else motion.dx
            // Approaching the mouth can mean steering sideways into the selected lane.
            if (forward > 0.0 || offset < 0 && abs(lateral + sideways) < abs(lateral)) return true
        }
        return false
    }
}

internal data class RoadProgress(val group: GroupMoveRequest, val step: Long, val dx: Double, val dy: Double)
