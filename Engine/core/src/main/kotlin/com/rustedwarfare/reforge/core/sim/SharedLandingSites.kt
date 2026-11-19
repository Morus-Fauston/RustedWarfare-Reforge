package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.path.PlanningSegmentClearance
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import kotlin.math.ceil
import kotlin.math.sqrt

/** Physical locations belong to the command. A travelling vehicle has no claim until it enters the landing area. */
internal data class LandingMember(val group: GroupMoveRequest, val claimed: Position? = null, val parked: Boolean = false, val ingress: Position? = null, val guided: Boolean = false, val preciseApproach: Boolean = false)

internal class SharedLandingSites(private val map: MapDefinition, private val group: GroupMoveRequest,
                                  private val spacing: Double, private val fillOrigin: Position, private val spread: Double, private val outside: Boolean) {
    private var points = GroupMoveDestinations.Points(map, group.click, spacing)
    private var sites = emptyList<Position>()

    private data class Scan(val entry: Position, val start: Position, val terrain: PlanningTerrain,
                            val index: Int, val best: Position?, val score: Double)
    private val scans = mutableMapOf<String, Scan>()

    // Resume the pair sweep on the next candidate step. Leave most of the queue for arrivals.
    private var smallTurn = 0
    fun beginService() { smallTurn = (smallTurn + 1) % 3 }

    private fun quota(budget: PathWorkBudget, divisor: Int, phase: Int): Int {
        val available = budget.limit - budget.used
        return if (available >= divisor) available / divisor else if (smallTurn == phase) available else 0
    }

    private var pairRow = 0
    private var pairColumn = 1

    fun visitApproachingPairs(members: List<MutableUnit>, budget: PathWorkBudget, visit: (MutableUnit, MutableUnit) -> Unit) {
        if (members.size < 2) return
        val count = members.size.toLong() * (members.size - 1) / 2
        val allowance = minOf(count, quota(budget, 4, 0).toLong()).toInt()
        repeat(allowance) {
            if (pairRow >= members.lastIndex) { pairRow = 0; pairColumn = 1 }
            if (pairColumn >= members.size) { pairRow++; pairColumn = pairRow + 1 }
            if (pairRow >= members.lastIndex) { pairRow = 0; pairColumn = 1 }
            if (!budget.take(1)) return
            visit(members[pairRow], members[pairColumn])
            pairColumn++
        }
    }

    private var entryCursor = 0
    private var followerCursor = 0
    private var drivingCursor = 0

    /** A partial sweep leaves capacity for new arrivals and resumes at the first unvisited peer. */
    fun visitEntrances(entering: List<MutableUnit>, members: List<MutableUnit>, budget: PathWorkBudget,
                       exchange: (MutableUnit, MutableUnit) -> Unit) {
        if (entering.isEmpty() || members.isEmpty()) return
        val limit = minOf(entering.size.toLong() * members.size, quota(budget, 3, 1).toLong()).toInt()
        repeat(limit) {
            entryCursor %= entering.size
            followerCursor %= members.size
            val front = entering[entryCursor]
            val other = members[followerCursor]
            if (!budget.take(1)) return
            followerCursor++
            if (followerCursor == members.size) { followerCursor = 0; entryCursor++ }
            if (front.moveTarget != null && other.moveTarget != null && wouldSeal(front, other)) exchange(front, other)
        }
    }

    fun visitDriving(driving: List<MutableUnit>, budget: PathWorkBudget, visit: (MutableUnit) -> Unit) {
        repeat(driving.size) {
            if (!budget.take(1)) return
            drivingCursor %= driving.size
            val unit = driving[drivingCursor++]
            visit(unit)
        }
    }

    private data class LaneSearch(val entrance: Position, val index: Int, val deepestId: String?, val deepestSite: Position?)
    private val laneSearches = mutableMapOf<String, LaneSearch>()
    private val memberIds = group.members.toList()

    /** Scan stable command identities across steps. Never hand off an old ingress after a new claim. */
    fun deepestEntering(unit: MutableUnit, entrance: Position, budget: PathWorkBudget,
                        lookup: (String) -> MutableUnit?): MutableUnit? {
        val saved = laneSearches[unit.unitId]?.takeIf { it.entrance == entrance }
        fun currentSite(id: String?): Position? {
            val other = id?.let(lookup) ?: return null
            val landing = other.landingMember ?: return null
            return landing.ingress?.takeIf { other.alive && other.moveTarget != null && landing.group === group &&
                landing.claimed == null && sameLane(entrance, it) }
        }
        val resume = saved?.takeIf { it.deepestSite == currentSite(it.deepestId) }
        var deepest = resume?.deepestId
        var site = resume?.deepestSite
        for (index in (resume?.index ?: 0) until memberIds.size) {
            if (!budget.take(1)) {
                laneSearches[unit.unitId] = LaneSearch(entrance, index, deepest, site)
                return null
            }
            val id = memberIds[index]
            val candidate = currentSite(id) ?: continue
            if (site == null || depth(candidate) > depth(site)) {
                deepest = id
                site = candidate
            }
        }
        laneSearches.remove(unit.unitId)
        return deepest?.let(lookup)
    }

    private var repairTerrain: Pair<PlanningTerrain, PlanningTerrain>? = null

    /** A parked disk can close the tile-centre graph while leaving a traversable gap between centres. */
    fun parkingTerrain(source: PlanningTerrain): PlanningTerrain {
        repairTerrain?.takeIf { it.first === source }?.let { return it.second }
        val subdivision = listOf(4, 2).firstOrNull { source.tileWidth % it == 0 && source.tileHeight % it == 0 &&
            source.widthTiles.toLong() * source.heightTiles * it * it <= 262144L } ?: return source
        val refined = source.subdivided(subdivision)
        repairTerrain = source to refined
        return refined
    }

    fun fork(rollback: PathSearchRollback) = SharedLandingSites(map, group, spacing, fillOrigin, spread, outside).also {
        it.points = points.fork(rollback)
        it.smallTurn = smallTurn
        it.entryCursor = entryCursor
        it.followerCursor = followerCursor
        it.drivingCursor = drivingCursor
        it.pairRow = pairRow
        it.pairColumn = pairColumn
        it.scans.putAll(scans)
        it.laneSearches.putAll(laneSearches)
        it.sites = sites
        it.repairTerrain = repairTerrain
    }

    fun supply(terrain: PlanningTerrain, body: MutableUnit, budget: PathWorkBudget) {
        while (sites.size < group.members.size && !points.exhausted && budget.take()) {
            val p = points.next() ?: break
            if (UnitContact.poseFits(body.definition.movementCollision, p, body.heading, HullTerrainGrid.authorized(terrain))) sites = sites + p
        }
    }

    fun near(p: Position): Boolean {
        val reach = spacing * (sqrt(group.members.size.toDouble()) + 2.0)
        return ScreenPlane.distanceSquared(p, group.click) <= reach * reach
    }

    fun choose(unit: MutableUnit, terrain: PlanningTerrain, occupied: Set<Position>, centre: Position, budget: PathWorkBudget): Position? {
        if (sites.size < group.members.size && !points.exhausted) return null
        val saved = scans[unit.unitId]?.takeIf { it.terrain === terrain }
        val start = saved?.start ?: unit.position
        val entry = saved?.entry ?: Position(group.click.x + (unit.position.x - centre.x) * spread,
            group.click.y + (unit.position.y - centre.y) * spread)
        val axisX = fillOrigin.x - group.click.x
        val axisY = fillOrigin.y - group.click.y
        val axisLength = maxOf(1.0, sqrt(axisX * axisX + axisY * axisY))
        var best = saved?.best
        var bestDistance = saved?.score ?: Double.POSITIVE_INFINITY
        for (index in (saved?.index ?: 0) until sites.size) {
            if (!budget.take(1)) {
                scans[unit.unitId] = Scan(entry, start, terrain, index, best, bestDistance)
                return null
            }
            val site = sites[index]
            val distance = if (outside) {
                val lateral = kotlin.math.abs((site.x - entry.x) * axisY - (site.y - entry.y) * axisX) / axisLength
                val depth = ((site.x - group.click.x) * axisX + (site.y - group.click.y) * axisY) / axisLength
                lateral * (sqrt(group.members.size.toDouble()) + 1.0) - depth
            } else sqrt(ScreenPlane.distanceSquared(entry, site)) +
                0.25 * sqrt(ScreenPlane.distanceSquared(fillOrigin, site))
            if (site in occupied || distance >= bestDistance) continue
            if (PlanningSegmentClearance.allows(terrain, start, site, unit.definition.movementCollision.boundingRadius)) {
                best = site
                bestDistance = distance
            }
        }
        scans.remove(unit.unitId)
        return best?.takeIf { it !in occupied && (start == unit.position ||
            PlanningSegmentClearance.allows(terrain, unit.position, it, unit.definition.movementCollision.boundingRadius)) }
    }

    /** Join the final lane outside the parking area before driving between parked bodies. */
    fun approach(unit: MutableUnit, target: Position, terrain: PlanningTerrain): List<Position> {
        if (!outside) return listOf(target)
        val dx = fillOrigin.x - group.click.x
        val dy = fillOrigin.y - group.click.y
        val length = sqrt(dx * dx + dy * dy)
        if (length == 0.0) return listOf(target)
        val ax = dx / length
        val ay = dy / length
        val depth = (target.x - group.click.x) * ax + (target.y - group.click.y) * ay
        val approachDistance = spacing * (sqrt(group.members.size / Math.PI) + 1.0) + depth
        val entry = Position(target.x - approachDistance * ax, target.y - approachDistance * ay)
        if ((entry.x - unit.position.x) * ax + (entry.y - unit.position.y) * ay <= 0.0) return listOf(target)
        val radius = unit.definition.movementCollision.boundingRadius
        if (!PlanningSegmentClearance.allows(terrain, unit.position, entry, radius) ||
            !PlanningSegmentClearance.allows(terrain, entry, target, radius)) return listOf(target)
        return listOf(entry, target)
    }

    fun incomingGuide(unit: MutableUnit, centre: Position, terrain: PlanningTerrain): Position? {
        if (!outside) return null
        val dx = fillOrigin.x - group.click.x
        val dy = fillOrigin.y - group.click.y
        val length = sqrt(dx * dx + dy * dy)
        if (length == 0.0) return null
        val side = ((unit.position.y - centre.y) * dx - (unit.position.x - centre.x) * dy) * spread / length
        val distance = spacing * (sqrt(group.members.size / Math.PI) + 1.0)
        val p = Position(group.click.x - (distance * dx + side * dy) / length,
            group.click.y + (side * dx - distance * dy) / length)
        val radius = unit.definition.movementCollision.boundingRadius
        return p.takeIf { PlanningSegmentClearance.allows(terrain, unit.position, p, radius) &&
            PlanningSegmentClearance.allows(terrain, p, group.click, radius) }
    }

    fun atEntrance(position: Position, preview: Position): Boolean {
        val dx = fillOrigin.x - group.click.x
        val dy = fillOrigin.y - group.click.y
        val length = sqrt(dx * dx + dy * dy)
        return depth(position) >= -spacing * (sqrt(group.members.size / Math.PI) + 1.0) * length &&
            kotlin.math.abs((position.x - preview.x) * dy - (position.y - preview.y) * dx) <= spacing * length
    }

    fun sameLane(a: Position, b: Position): Boolean {
        val dx = fillOrigin.x - group.click.x
        val dy = fillOrigin.y - group.click.y
        return kotlin.math.abs((a.x - b.x) * dy - (a.y - b.y) * dx) < spacing * sqrt(dx * dx + dy * dy) * 0.5
    }

    fun wouldSeal(near: MutableUnit, far: MutableUnit): Boolean {
        val a = near.landingMember?.claimed ?: return false
        val b = far.landingMember?.claimed ?: return false
        if (a == b) return false
        if (!outside) {
            // Fill through the entrance before parking a disk across the remaining approach.
            return ScreenPlane.distanceSquared(near.position, b) < ScreenPlane.distanceSquared(far.position, b) &&
                UnitContact.withinSweptBounds(far, far.position, b, near)
        }
        if (!sameLane(a, b) || depth(a) >= depth(b)) return false
        if (sameLane(far.position, b) && depth(far.position) >= depth(near.position)) return false
        val dx = fillOrigin.x - group.click.x
        val dy = fillOrigin.y - group.click.y
        val length = sqrt(dx * dx + dy * dy)
        return depth(far.position) < depth(a) + length * near.definition.movementCollision.boundingRadius * 2.0
    }

    fun depth(p: Position): Double = (p.x - group.click.x) * (fillOrigin.x - group.click.x) +
        (p.y - group.click.y) * (fillOrigin.y - group.click.y)

    companion object {
        fun forGroup(map: MapDefinition, group: GroupMoveRequest, body: MutableUnit, members: List<MutableUnit>): SharedLandingSites {
            val lattice = minOf(map.tileWidth, map.tileHeight) * 0.5
            val diameter = body.definition.contactBufferCollision.boundingRadius * 2.0
            val spacing = maxOf(1.0, ceil(diameter / lattice)) * lattice
            val dx = group.click.x - members.sumOf { it.position.x } / members.size
            val dy = group.click.y - members.sumOf { it.position.y } / members.size
            val radius = spacing * sqrt(group.members.size / Math.PI)
            val fraction = minOf(0.5, radius / maxOf(1.0, sqrt(dx * dx + dy * dy)))
            val centre = Position(group.click.x - dx, group.click.y - dy)
            val extent = sqrt(members.sumOf { ScreenPlane.distanceSquared(centre, it.position) } / members.size)
            return SharedLandingSites(map, group, spacing, Position(group.click.x + dx * fraction, group.click.y + dy * fraction),
                minOf(1.0, radius / maxOf(spacing, extent * sqrt(2.0))), sqrt(dx * dx + dy * dy) > extent + radius)
        }
    }
}
