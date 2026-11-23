package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.path.PlanningSegmentClearance
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import kotlin.math.round
import kotlin.math.ceil
import kotlin.math.sqrt

/** Cells actually consulted by a parking query, including the moving hull's swept bounds. */
internal class LandingFootprint(private val occupancy: LandingOccupancy, private val since: Long) {
    private val cells = HashSet<Long>()
    var stale = false
        private set
    private var undo: PathSearchRollback? = null
    fun journal(rollback: PathSearchRollback) { undo = rollback }
    fun invalidate(rollback: PathSearchRollback?) {
        if (stale) return
        stale = true
        rollback?.record { stale = false }
    }
    fun visit(a: Position, b: Position = a, radius: Double = 0.0) {
        if (stale) return
        eachCell(a, b, radius) { key ->
            if (cells.add(key)) {
                occupancy.watch(key, this, since, undo)
                undo?.record { cells.remove(key) }
            }
        }
    }
    companion object {
        inline fun eachCell(a: Position, b: Position, radius: Double, visit: (Long) -> Unit) {
            cellRange(minOf(a.x, b.x) - radius, maxOf(a.x, b.x) + radius).forEach { x ->
                cellRange(minOf(a.y, b.y) - radius, maxOf(a.y, b.y) + radius).forEach { y ->
                    visit((x.toLong() shl 32) or (y.toLong() and 0xffffffffL))
                }
            }
        }
        fun cellRange(lo: Double, hi: Double) = kotlin.math.floor(lo / 32).toInt()..kotlin.math.floor(hi / 32).toInt()
    }
}

/** Reverse lookup and revision of the traffic that can change a last arrival's exact search. */
internal class LandingOccupancy(private val bySite: MutableMap<Position, Set<String>> = hashMapOf(),
                                private val byUnit: MutableMap<String, Set<Position>> = hashMapOf(),
                                private val stopped: MutableMap<String, StopPose> = hashMapOf(),
                                private val stamps: MutableMap<Long, Long> = hashMapOf(),
                                private val watchers: MutableMap<Long, java.util.WeakHashMap<LandingFootprint, Boolean>> = hashMapOf(),
                                private val reservedCells: MutableMap<Long, MutableSet<String>> = hashMapOf(),
                                private val undo: PathSearchRollback? = null) {
    internal data class StopPose(val pose: Position, val heading: Double, val layer: ContactLayer,
                                val order: GroupMoveRequest?, val parked: Boolean, val radius: Double)
    // Immutable summary of the index inputs, shared across a candidate fork.
    internal class IndexedBody(val owner: Any, val site: Position?, val body: Position?, val stop: StopPose?)

    var revision = 0L
        private set
    fun fork(rollback: PathSearchRollback) = LandingOccupancy(bySite, byUnit, stopped, stamps, watchers, reservedCells, rollback).also {
        it.revision = revision
    }

    private fun indexReservation(id: String, site: Position, radius: Double, add: Boolean) {
        LandingFootprint.eachCell(site, site, radius) { key ->
            if (add) reservedCells.getOrPut(key) { hashSetOf() }.add(id)
            else reservedCells[key]?.let { ids ->
                ids.remove(id)
                if (ids.isEmpty()) reservedCells.remove(key)
            }
        }
    }

    fun leavesReservedSitesClear(car: MutableUnit, units: Map<String, MutableUnit>,
                                at: Position = car.position, facing: Double = car.heading): Boolean {
        val hull = car.definition.movementCollision
        LandingFootprint.eachCell(at, at, hull.boundingRadius) { key ->
            for (id in reservedCells[key].orEmpty()) {
                val peer = units[id] ?: continue
                if (peer === car || peer.ownerPlayerId != car.ownerPlayerId || peer.moveTarget == null ||
                    !UnitContact.shareLayer(car, peer)) continue
                val member = peer.landingMember ?: continue
                val site = member.claimed ?: member.ingress ?: continue
                val otherHull = peer.definition.movementCollision
                val heading = member.claimHeading ?: if (otherHull is MovementCollisionShape.Rectangle)
                    GroupMoveDestinations.slot(peer, site, peer.pathPoints).heading else peer.heading
                if (UnitContact.posesOverlap(hull, at, facing, otherHull, site, heading)) return false
            }
        }
        return true
    }

    /** A cell change reaches only searches that visited that cell. Retired searches need no world scan to detach. */
    fun watch(key: Long, footprint: LandingFootprint, since: Long, rollback: PathSearchRollback?) {
        if ((stamps[key] ?: 0L) > since) {
            footprint.invalidate(rollback)
            return
        }
        val listeners = watchers.getOrPut(key) { java.util.WeakHashMap() }
        listeners[footprint] = true
        rollback?.record { listeners.remove(footprint) }
    }

    private fun stamp(p: Position, radius: Double) {
        LandingFootprint.eachCell(p, p, radius) { key ->
            val old = stamps.put(key, revision)
            if (old != revision) {
                undo?.record { if (old == null) stamps.remove(key) else stamps[key] = old }
                watchers[key]?.keys?.forEach { it.invalidate(undo) }
            }
        }
    }

    private fun trackStop(unit: MutableUnit) {
        val old = stopped[unit.unitId]
        val layer = if (unit.moveTarget == null) UnitContact.layer(unit) else null
        val member = unit.landingMember
        if (layer == null && old == null || old != null && layer == old.layer && old.pose == unit.position &&
            old.heading == unit.heading && old.radius == unit.definition.movementCollision.boundingRadius && old.order === member?.group && old.parked == (member?.parked == true)) return
        if (layer == null) stopped.remove(unit.unitId) else stopped[unit.unitId] =
            StopPose(unit.position, unit.heading, layer, member?.group, member?.parked == true, unit.definition.movementCollision.boundingRadius)
        revision++
        old?.let { stamp(it.pose, it.radius) }
        if (layer != null) stamp(unit.position, unit.definition.movementCollision.boundingRadius)
        undo?.record { if (old == null) stopped.remove(unit.unitId) else stopped[unit.unitId] = old }
    }

    fun update(units: Collection<MutableUnit>) {
        for (unit in units) synchronize(unit)
    }

    fun update(unit: MutableUnit) = synchronize(unit)

    private fun synchronize(unit: MutableUnit) {
        val member = unit.landingMember
        val reservation = (member?.claimed ?: member?.ingress)?.takeIf { unit.alive }
        val body = unit.position.takeIf { unit.alive && member?.parked == true }
        val layer = if (unit.moveTarget == null) UnitContact.layer(unit) else null
        val indexed = unit.indexedLandingBody
        val previousSite = indexed?.takeIf { it.owner === bySite }?.site
        if (reservation != previousSite) {
            val radius = unit.definition.movementCollision.boundingRadius
            previousSite?.let { indexReservation(unit.unitId, it, radius, false) }
            reservation?.let { indexReservation(unit.unitId, it, radius, true) }
            undo?.record {
                reservation?.let { indexReservation(unit.unitId, it, radius, false) }
                previousSite?.let { indexReservation(unit.unitId, it, radius, true) }
            }
        }
        if (indexed != null && indexed.owner === bySite && indexed.site == reservation && indexed.body == body) {
            val stop = indexed.stop
            if (layer == null && stop == null || stop != null && layer == stop.layer && stop.pose == unit.position &&
                stop.heading == unit.heading && stop.radius == unit.definition.movementCollision.boundingRadius &&
                stop.order === member?.group && stop.parked == (member?.parked == true)) return
        }
        trackStop(unit)
        unit.indexedLandingBody = IndexedBody(bySite, reservation, body, stopped[unit.unitId])
        val old = byUnit[unit.unitId].orEmpty()
        if (old.size == (if (reservation != null) 1 else 0) + (if (body != null && body != reservation) 1 else 0) &&
            (reservation == null || reservation in old) && (body == null || body in old)) return
        val next = setOfNotNull(reservation, body)
        fun replace(from: Set<Position>, to: Set<Position>) {
            for (site in from - to) {
                val rest = bySite.getValue(site) - unit.unitId
                if (rest.isEmpty()) bySite.remove(site) else bySite[site] = rest
            }
            for (site in to - from) bySite[site] = bySite[site].orEmpty() + unit.unitId
            if (to.isEmpty()) byUnit.remove(unit.unitId) else byUnit[unit.unitId] = to
        }
        replace(old, next)
        revision++
        for (p in old) stamp(p, 0.0)
        for (p in next) stamp(p, 0.0)
        undo?.record { replace(next, old) }
    }

    fun at(site: Position): Set<String> = bySite[site].orEmpty()
}

/** A separating line between the pads keeps both swept disks disjoint along the two straight approaches. */
internal fun landingApproachesSeparate(first: MutableUnit, firstSite: Position, second: MutableUnit, secondSite: Position): Boolean {
    if (firstSite == secondSite) return false
    fun ownSide(unit: MutableUnit, own: Position, other: Position): Boolean {
        val dx = other.x - own.x
        val dy = other.y - own.y
        val separation = sqrt(dx * dx + dy * dy)
        val projection = (unit.position.x - own.x) * dx + (unit.position.y - own.y) * dy
        val clearance = separation * (separation / 2 - unit.definition.movementCollision.boundingRadius)
        return clearance >= 0 && projection <= clearance
    }
    return ownSide(first, firstSite, secondSite) && ownSide(second, secondSite, firstSite)
}

/** Physical locations belong to the command. A travelling vehicle has no claim until it enters the landing area. */
internal data class LandingMember(val group: GroupMoveRequest, val claimed: Position? = null, val parked: Boolean = false, val ingress: Position? = null, val preciseApproach: Boolean = false,
                                  val pushed: Boolean = false, val restingSite: Position? = null, val restingPose: Position? = null,
                                  val pushedPose: Position? = null, val releasedSite: Position? = null, val noRouteTicks: Long? = null, val stoppedOrder: UnitTask? = null, val waitingForBay: String? = null,
                                  val claimHeading: Double? = null)

internal class SharedLandingSites(private val map: MapDefinition, private val group: GroupMoveRequest,
                                  private val spacing: Double, private val fillOrigin: Position, private val outside: Boolean) {
    var mixedBays: MixedLandingBays? = null
        private set
    private var points = GroupMoveDestinations.Points(map, group.click, spacing)
    private var sites = emptyList<Position>()
    private var suppliedSites = emptySet<Position>()

    private data class Scan(val entry: Position, val start: Position, val terrain: PlanningTerrain,
                            val index: Int, val best: Position?, val score: Double,
                            val bestBlocked: Position?, val blockedScore: Double, val allTaken: Boolean)
    private val scans = mutableMapOf<String, Scan>()
    private data class EntryScan(val start: Position, val terrain: PlanningTerrain, val index: Int, val nearest: Position?)
    private val entryScans = mutableMapOf<String, EntryScan>()
    fun surveyingEntry(id: String) = id in entryScans || id in scans

    private var drivingCursor = 0

    fun visitDriving(driving: List<MutableUnit>, budget: PathWorkBudget, visit: (MutableUnit) -> Unit) {
        repeat(driving.size) {
            if (!budget.take(1)) return
            drivingCursor %= driving.size
            val unit = driving[drivingCursor++]
            visit(unit)
        }
    }

    private val memberIds = group.members.toList()
    private val memberRanks = memberIds.withIndex().associate { it.value to it.index }

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

    fun fork(rollback: PathSearchRollback) = SharedLandingSites(map, group, spacing, fillOrigin, outside).also {
        it.mixedBays = mixedBays?.fork(rollback)
        it.points = points.fork(rollback)
        it.drivingCursor = drivingCursor
        it.scans.putAll(scans)
        it.entryScans.putAll(entryScans)
        it.pushScans.putAll(pushScans)
        it.pushCursor = pushCursor
        it.needed = needed
        it.sites = sites
        it.suppliedSites = suppliedSites
        it.survey = survey?.fork(rollback)
        it.searchCredit = searchCredit
        it.movedAside = movedAside
        it.yieldScan = yieldScan?.let { scan -> scan.copy(query = scan.query?.fork(rollback), extra = scan.extra?.fork(rollback)) }
        it.lastArrival = lastArrival?.fork(rollback)
        it.repairTerrain = repairTerrain
    }

    private var needed = group.members.size

    fun supply(terrain: PlanningTerrain, body: MutableUnit, budget: PathWorkBudget) {
        val previousSize = sites.size
        while (sites.size < needed && !points.exhausted && budget.take(1)) {
            val p = points.next() ?: break
            if (UnitContact.poseFits(body.definition.movementCollision, p, body.heading, HullTerrainGrid.authorized(terrain))) {
                sites = sites + p
            }
        }
        if (previousSize < sites.size) suppliedSites = suppliedSites + sites.subList(previousSize, sites.size)
    }

    // Inside half the lattice pitch, at most one generated point can be within reach.
    private fun nearestSupplied(p: Position): Position? {
        val x = round((p.x - group.click.x) / spacing)
        val y = round((p.y - group.click.y) / spacing)
        val pad = Position(group.click.x + x * spacing, group.click.y + y * spacing)
        return pad.takeIf { it in suppliedSites }
    }

    private enum class PushCheck { SITE, POSE, ROUTE, SWEEP, PUBLISH }
    private data class PushScan(val pose: Position, val heading: Double, val check: PushCheck = PushCheck.SITE,
                                val index: Int = 0, val leg: Int = 0, val site: Position? = null,
                                val terrain: PlanningTerrain)
    private val pushScans = mutableMapOf<String, PushScan>()
    private var pushCursor = 0
    val checkingTraffic get() = pushScans.isNotEmpty()
    fun routePublished(peer: MutableUnit) {
        val index = memberRanks[peer.unitId] ?: return
        for ((id, scan) in pushScans) {
            if (id != peer.unitId && (scan.check > PushCheck.ROUTE || scan.check == PushCheck.ROUTE &&
                    (index < scan.index || index == scan.index && scan.leg > 0))) {
                pushScans[id] = scan.copy(check = PushCheck.ROUTE, index = index, leg = 0)
            }
        }
    }

    /** Only a newly obstructed leg (or a replaced suffix) invalidates a route the cursor has already visited. */
    fun trafficChanged(body: MutableUnit, previous: MutableUnit?, worldIndex: Int, lookup: (String) -> MutableUnit?) {
        if (!body.alive) return
        val own = body.landingMember?.group === group
        val route = own && body.moveTarget != null && body.pathPoints.isNotEmpty()
        val newRoute = previous == null || previous.landingMember?.group !== group ||
            previous.adoptedRouteRevision != body.adoptedRouteRevision || previous.moveTarget != body.moveTarget
        val peerIndex = if (route) memberRanks[body.unitId] else null
        for ((id, scan) in pushScans) {
            val site = scan.site ?: continue
            val car = lookup(id) ?: continue
            if (car === body || scan.check < PushCheck.ROUTE) continue
            val visitedRoute = peerIndex != null && (scan.check > PushCheck.ROUTE || peerIndex < scan.index ||
                peerIndex == scan.index && scan.leg > 0)
            if (visitedRoute && (newRoute || UnitContact.withinSweptBounds(body, body.position, body.pathPoints.first(), car, scan.pose) ||
                    UnitContact.withinSweptBounds(body, body.position, body.pathPoints.first(), car, site) ||
                    scan.check == PushCheck.ROUTE && peerIndex == scan.index && previous?.pathPoints?.size != body.pathPoints.size)) {
                pushScans[id] = scan.copy(check = PushCheck.ROUTE, index = checkNotNull(peerIndex), leg = 0)
                continue
            }
            val visitedBody = scan.check == PushCheck.PUBLISH || scan.check == PushCheck.SWEEP && worldIndex < scan.index
            if (!visitedBody || site == scan.pose || own && body.landingMember?.parked != true || !UnitContact.shareLayer(car, body)) continue
            if (UnitContact.withinSweptBounds(car, scan.pose, site, body)) {
                pushScans[id] = scan.copy(check = PushCheck.SWEEP, index = worldIndex, leg = 0)
            }
        }
    }

    /** Each visit consumes one unit, including exact-centre publication. A failed step discards its cursors. */
    fun checkPushed(cars: List<MutableUnit>, world: List<MutableUnit>, budget: PathWorkBudget, radius: Double,
                    lookup: (String) -> MutableUnit?, occupied: (Position) -> Boolean,
                    terrain: (MutableUnit) -> PlanningTerrain, blocksApproach: (MutableUnit, MutableUnit, Position) -> Boolean,
                    claim: (MutableUnit, Position) -> Unit) {
        if (cars.isEmpty()) return
        val finished = hashSetOf<String>()
        while (finished.size < cars.size && !budget.exhausted) {
            pushCursor %= cars.size
            val car = cars[pushCursor++]
            val id = car.unitId
            if (id in finished) continue
            check(budget.take(1))
            val state = checkNotNull(car.landingMember)
            val ground = terrain(car)
            val scan = pushScans[id]?.takeIf { it.pose == car.position && it.terrain === ground &&
                (it.heading == car.heading || car.definition.movementCollision is MovementCollisionShape.Circle) } ?: PushScan(car.position, car.heading, terrain = ground)
            fun finish() { pushScans.remove(id); finished += id }
            fun reject() {
                car.landingMember = state.copy(restingSite = null, restingPose = null, pushedPose = null)
                finish()
            }
            if (state.pushedPose != car.position && state.restingPose != car.position && !state.pushed) { reject(); continue }
            val site = scan.site
            when (scan.check) {
                PushCheck.SITE -> {
                    val single = radius < spacing / 2
                    val candidate = if (single) nearestSupplied(car.position) else sites.getOrNull(scan.index)
                    if (candidate == null) reject()
                    else if (!occupied(candidate) && ScreenPlane.distanceSquared(car.position, candidate) <= radius * radius)
                        pushScans[id] = scan.copy(check = PushCheck.POSE, site = candidate, index = 0)
                    else if (single) reject() else pushScans[id] = scan.copy(index = scan.index + 1)
                }
                PushCheck.POSE -> {
                    if (!UnitContact.poseFits(car.definition.movementCollision, checkNotNull(site), car.heading,
                            HullTerrainGrid.authorized(ground))) reject()
                    else pushScans[id] = scan.copy(check = PushCheck.ROUTE)
                }
                PushCheck.ROUTE -> {
                    val peerId = memberIds.getOrNull(scan.index)
                    if (peerId == null) {
                        val next = if (site == car.position) PushCheck.PUBLISH else PushCheck.SWEEP
                        pushScans[id] = scan.copy(check = next, index = 0)
                        continue
                    }
                    val peer = lookup(peerId)
                    val to = peer?.pathPoints?.getOrNull(scan.leg)
                    if (peer == null || peer === car || !peer.alive || peer.moveTarget == null || peer.landingMember?.group !== group || to == null) {
                        pushScans[id] = scan.copy(index = scan.index + 1, leg = 0)
                        continue
                    }
                    val from = if (scan.leg == 0) peer.position else peer.pathPoints[scan.leg - 1]
                    if (UnitContact.withinSweptBounds(peer, from, to, car) ||
                        UnitContact.withinSweptBounds(peer, from, to, car, checkNotNull(site))) reject()
                    else pushScans[id] = if (scan.leg + 1 == peer.pathPoints.size) scan.copy(index = scan.index + 1, leg = 0)
                        else scan.copy(leg = scan.leg + 1)
                }
                PushCheck.SWEEP -> {
                    val other = world.getOrNull(scan.index)
                    if (other == null) pushScans[id] = scan.copy(check = PushCheck.PUBLISH)
                    else if (blocksApproach(car, other, checkNotNull(site))) reject()
                    else pushScans[id] = scan.copy(index = scan.index + 1)
                }
                PushCheck.PUBLISH -> {
                    if (occupied(checkNotNull(site))) reject()
                    else {
                        if (!state.pushed && state.restingSite == site && state.restingPose == car.position &&
                            car.speed == 0.0 && car.angularVelocity == 0.0) claim(car, site)
                        else car.landingMember = state.copy(restingSite = site, restingPose = car.position)
                        finish()
                    }
                }
            }
        }
    }

    private class TrafficSurvey(val rear: String, val goal: Position, val start: Position,
        val heading: Double, val terrain: PlanningTerrain, val revision: Long, val vision: Any?,
        val parked: ArrayList<MutableUnit> = arrayListOf(), val gates: ArrayList<Int> = arrayListOf(),
        var cursor: Int = 0, var last: Boolean = true, val footprint: LandingFootprint, val undo: PathSearchRollback? = null) {
        fun fork(rollback: PathSearchRollback) = TrafficSurvey(rear, goal, start, heading, terrain, revision, vision,
            parked, gates, cursor, last, footprint, rollback).also { footprint.journal(rollback) }
        fun visit(body: MutableUnit, mover: MutableUnit, group: GroupMoveRequest, visible: Boolean) {
            cursor++
            if (body === mover || !body.alive) return
            val state = body.landingMember
            if (state?.group === group && !state.parked) last = false
            if (!visible || body.moveTarget != null || !UnitContact.shareLayer(mover, body)) return
            if (state?.group === group && state.parked && state.stoppedOrder != null && body.tasks.isEmpty()) {
                gates.add(parked.size)
                undo?.record { gates.removeAt(gates.lastIndex) }
            }
            parked.add(body.copy())
            undo?.record { parked.removeAt(parked.lastIndex) }
        }
    }
    private var survey: TrafficSurvey? = null
    private var searchCredit = 0

    /** The exact planner's atomic operation costs eight. Save paid units from short slices. */
    fun advanceExact(query: ExactPathSearch, budget: PathWorkBudget) {
        val available = budget.limit - budget.used
        val total = searchCredit + available
        val quantum = total / 8 * 8
        if (quantum == 0) { budget.take(available); searchCredit = total; return }
        val slice = PathWorkBudget(quantum)
        query.advance(slice)
        val used = slice.used - searchCredit
        check(used >= 0 && budget.take(used))
        searchCredit = 0
        if (!query.finished) {
            searchCredit = budget.limit - budget.used
            budget.take(searchCredit)
        }
    }

    private data class YieldScan(val stage: Int = 0, val gateIndex: Int = 0, val cursor: Int = 0,
        val bay: Position? = null, val frontPath: List<Position>? = null, val query: ExactPathSearch? = null,
        val extra: GroupMoveDestinations.Points? = null, val refineBay: Boolean = false)
    private var yieldScan: YieldScan? = null
    private var movedAside = emptySet<String>()
    data class ArrivalRoute(val rearPath: List<Position>, val frontId: String? = null, val frontPath: List<Position> = emptyList())

    /** Null while evaluating a released original. Active group arrivals keep the ordinary selection path. */
    fun recoverLast(rear: MutableUnit, goal: Position, terrain: PlanningTerrain, index: LandingOccupancy, vision: Any?,
        budget: PathWorkBudget, policy: PathPolicy, world: List<MutableUnit>, occupied: (Position) -> Boolean,
        visible: (MutableUnit) -> Boolean): ArrivalRoute? {
        var traffic = survey
        if (traffic == null || traffic.rear != rear.unitId || traffic.goal != goal || traffic.start != rear.position ||
            traffic.heading != rear.heading || traffic.terrain !== terrain || traffic.footprint.stale || traffic.vision !== vision) {
            traffic = TrafficSurvey(rear.unitId, goal, rear.position, rear.heading, terrain, index.revision, vision,
                footprint = LandingFootprint(index, index.revision))
            survey = traffic
            yieldScan = null
            lastArrival = null
            searchCredit = 0
        }
        fun checkedSite(site: Position): Boolean { traffic.footprint.visit(site); return occupied(site) }
        fun checkedBodies(car: MutableUnit, bodies: List<MutableUnit>): RouteOccupancy {
            val occupancy = UnitContact.routeAroundLayer(car, bodies)
            val footprint = traffic.footprint
            val radius = car.definition.movementCollision.boundingRadius
            return RouteOccupancy { start, end, before, after ->
                footprint.visit(start, end, radius)
                occupancy?.allows(start, end, before, after) != false
            }
        }
        fun current(route: ArrivalRoute): ArrivalRoute? = route.takeUnless { traffic.footprint.stale }
        while (traffic.cursor < world.size) {
            if (!budget.take(1)) return null
            val body = world[traffic.cursor]
            traffic.visit(body, rear, group, visible(body))
        }
        if (!traffic.last || checkedSite(goal) || budget.exhausted) return null
        val alternative = replacementRoute(rear, terrain, ::checkedSite, budget, policy) {
            checkedBodies(rear, traffic.parked)
        }
        if (alternative != null) return current(ArrivalRoute(alternative))
        if (lastArrival?.let { it.checkingOriginal && it.search?.finished == true && it.search.path != null } != true) return null
        var job = yieldScan ?: YieldScan()
        val grid = parkingTerrain(terrain)
        while (!budget.exhausted && job.stage != 3) {
            val slot = traffic.gates.getOrNull(job.gateIndex)
            val front = slot?.let(traffic.parked::get)
            if (job.stage != 0 && (front == null || front.unitId in movedAside)) {
                if (front == null) { yieldScan = job.copy(stage = 3); return null }
                job = YieldScan(stage = 4, gateIndex = job.gateIndex + 1)
                yieldScan = job
                continue
            }
            var query = job.query
            if (query == null) {
                if (!budget.take(1)) return null
                val car = if (job.stage == 1) checkNotNull(front) else rear
                var target = goal
                if (job.stage == 1 && job.refineBay) target = checkNotNull(job.bay)
                else if (job.stage == 1) {
                    if (job.cursor < sites.size) {
                        target = sites[job.cursor]
                        job = job.copy(cursor = job.cursor + 1, bay = target)
                    } else {
                        val extra = job.extra ?: GroupMoveDestinations.Points(map, group.click, spacing)
                        val next = extra.next()
                        if (next == null || !near(next)) {
                            job = YieldScan(stage = 4, gateIndex = job.gateIndex + 1)
                            yieldScan = job
                            continue
                        }
                        target = next
                        job = job.copy(extra = extra, bay = target)
                    }
                    yieldScan = job
                    if (target == goal || checkedSite(target) || !UnitContact.poseFits(car.definition.movementCollision,
                        target, car.heading, HullTerrainGrid.authorized(terrain))) continue
                }
                // O(1) view substitutes the gate body without filtering the world for each trial.
                val base = traffic.parked
                val bodies = if (job.stage == 0) base else object : AbstractList<MutableUnit>() {
                    val replaced = checkNotNull(slot)
                    val substitute = when (job.stage) {
                        1 -> rear.copy()
                        2 -> checkNotNull(front).copy(position = checkNotNull(job.bay))
                        else -> null
                    }
                    override val size = base.size - if (substitute == null) 1 else 0
                    override fun get(index: Int): MutableUnit = if (index != replaced) base[index] else substitute ?: base.last()
                }
                query = policy.beginExact(if (job.stage == 1 && !job.refineBay) terrain else grid, car.position, target,
                    car.definition.movementCollision.boundingRadius, HullRoute.forUnit(car), checkedBodies(car, bodies))
                job = job.copy(query = query)
                yieldScan = job
            }
            advanceExact(query, budget)
            if (!query.finished) return null
            val path = query.path?.takeIf { it.isNotEmpty() }
            job = when (job.stage) {
                0 -> if (path != null) return current(ArrivalRoute(path)) else job.copy(stage = 4, query = null)
                4 -> if (path == null) YieldScan(stage = 4, gateIndex = job.gateIndex + 1) else job.copy(stage = 1, query = null)
                1 -> job.copy(stage = if (path == null) 1 else 2, frontPath = path, query = null,
                    refineBay = path == null && !job.refineBay && grid !== terrain)
                else -> if (path != null && !occupied(checkNotNull(job.bay)) && !occupied(goal)) {
                    return current(ArrivalRoute(path, checkNotNull(front).unitId, checkNotNull(job.frontPath)))
                } else job.copy(stage = 1, query = null)
            }
            yieldScan = job
        }
        return null
    }

    /** Consume the permission only in the candidate step that publishes both reservations. */
    fun acceptYield(rear: MutableUnit, front: MutableUnit, goal: Position, bay: Position,
                    occupied: (Position) -> Boolean): Boolean {
        val queued = rear.tasks.firstOrNull() ?: return false
        val gate = front.landingMember ?: return false
        val waiting = rear.landingMember ?: return false
        if (!front.alive || !rear.alive || gate.group !== group || waiting.group !== group ||
            !gate.parked || gate.stoppedOrder == null || front.tasks.isNotEmpty() ||
            waiting.releasedSite != goal || waiting.claimed != null || queued.actionId != rear.originMoveId ||
            front.unitId in movedAside || goal == bay || occupied(goal) || occupied(bay)) return false
        movedAside = movedAside + front.unitId
        yieldScan = null
        return true
    }

    private data class LastArrival(val id: String, val start: Position, val original: Position,
                                   val terrain: PlanningTerrain, val alternatives: GroupMoveDestinations.Points,
                                   val target: Position?, val search: ExactPathSearch?, val checkingOriginal: Boolean) {
        fun fork(undo: PathSearchRollback) = copy(alternatives = alternatives.fork(undo), search = search?.fork(undo))
    }
    private var lastArrival: LastArrival? = null

    /** A mobile obstruction cannot justify abandoning the original pad. First search on authorized terrain alone. */
    private fun replacementRoute(unit: MutableUnit, terrain: PlanningTerrain, occupied: (Position) -> Boolean,
                         budget: PathWorkBudget, paths: PathPolicy, obstacles: () -> RouteOccupancy?): List<Position>? {
        val original = unit.landingMember?.releasedSite ?: return null
        val radius = unit.definition.movementCollision.boundingRadius
        val hull = HullRoute.forUnit(unit)
        var job = lastArrival?.takeIf { it.id == unit.unitId && it.original == original &&
            it.terrain === terrain && it.start == unit.position } ?: LastArrival(unit.unitId, unit.position, original,
            terrain, GroupMoveDestinations.Points(map, group.click, spacing), original,
            paths.beginExact(terrain, unit.position, original, radius, hull), true)
        lastArrival = job
        while (!budget.exhausted) {
            val search = job.search
            if (search != null) {
                if (!search.finished) advanceExact(search, budget)
                if (!search.finished) return null
                val path = search.path
                if (job.checkingOriginal && path != null) return null
                if (!job.checkingOriginal && path != null && path.isNotEmpty() && !occupied(path.last())) return path
                job = job.copy(target = null, search = null, checkingOriginal = false)
                lastArrival = job
            }
            if (!budget.take(1)) return null
            val point = job.alternatives.next() ?: return null
            if (point == original || occupied(point) || !UnitContact.poseFits(unit.definition.movementCollision,
                    point, unit.heading, HullTerrainGrid.authorized(terrain))) continue
            job = job.copy(target = point, search = paths.beginExact(terrain, unit.position, point, radius, hull, obstacles()))
            lastArrival = job
        }
        return null
    }

    fun near(p: Position): Boolean {
        val reach = spacing * (sqrt(group.members.size.toDouble()) + 2.0)
        val anchor = sites.firstOrNull() ?: group.click
        return ScreenPlane.distanceSquared(p, group.click) <= reach * reach ||
            ScreenPlane.distanceSquared(p, anchor) <= reach * reach
    }

    /** The final route target and a stationary queue are both admissible destinations inside the landing area. */
    fun canPark(host: UnitMovementHost, car: MutableUnit, map: MapDefinition): Boolean {
        val at = car.position
        if (!near(at)) return false
        val hull = car.definition.movementCollision
        val layer = UnitContact.layer(car) ?: return false
        val target = car.moveTarget ?: return false
        val member = car.landingMember ?: return false
        val dist = sqrt(ScreenPlane.distanceSquared(at, target))
        val grid = HullTerrainGrid.authorized(host.planningTerrainFor(car, map))
        // A centred bay must accommodate the stopping error on both sides.
        // A nearest endpoint can itself be tangent to the shoreline.
        val mirrored = Position(target.x + (target.x - at.x), target.y + (target.y - at.y))
        val coversClick = car.speed == 0.0 && MoveArrival.distanceToHull(hull, at, car.heading, group.click) == 0.0
        val reached = coversClick || dist <= minOf(host.movementContract.targetRadius, hull.boundingRadius / 2) &&
            (member.claimed == null || UnitContact.poseFits(hull, mirrored, car.heading, grid))
        val blocked = car.stalledRouteTicks > 0L || car.hardContactLimitedTicks > 0L || car.routeTranslationClipped
        if (!reached && !blocked) return false
        val lookAhead = car.definition.contactBufferCollision.boundingRadius
        val fraction = if (dist > lookAhead) lookAhead / dist else 1.0
        val toward = Position(at.x + (target.x - at.x) * fraction, at.y + (target.y - at.y) * fraction)
        val nearby = host.spatialIndex.querySwept(SpatialLayer.fromContact(layer), at, toward, hull.boundingRadius)
        var queued = false
        for (peer in nearby) {
            if (car === peer || !UnitContact.shareLayer(car, peer)) continue
            if (UnitContact.posesOverlap(hull, at, car.heading, peer.definition.movementCollision,
                    peer.position, peer.heading)) return false
            if (reached || peer.ownerPlayerId != car.ownerPlayerId || peer.moveTarget != null ||
                peer.tasks.isNotEmpty() || peer.speed != 0.0 || peer.angularVelocity != 0.0) continue
            if (UnitContact.routeAroundLayer(car, listOf(peer))?.allows(at, toward, car.heading, car.heading) == false)
                queued = true
        }
        return (reached || queued) && host.leavesReservedSitesClear(car) && UnitContact.poseFits(hull, at, car.heading, grid)
    }

    fun choose(
        unit: MutableUnit,
        terrain: PlanningTerrain,
        occupied: (Position) -> Boolean,
        budget: PathWorkBudget,
        unobstructed: ((Position) -> Boolean)? = null,
    ): Position? {
        if (sites.size < group.members.size && !points.exhausted) return null
        val saved = scans[unit.unitId]?.takeIf { it.terrain === terrain }
        val partial = entryScans[unit.unitId]?.takeIf { it.terrain === terrain }
        val start = saved?.start ?: partial?.start ?: unit.position
        val radius = unit.definition.movementCollision.boundingRadius
        val entry = saved?.entry ?: run {
            var nearest = partial?.nearest
            var distance = nearest?.let { ScreenPlane.distanceSquared(start, it) } ?: Double.POSITIVE_INFINITY
            for (i in (partial?.index ?: 0) until sites.size) {
                if (!budget.take(1)) {
                    entryScans[unit.unitId] = EntryScan(start, terrain, i, nearest)
                    return null
                }
                val candidate = sites[i]
                val d = ScreenPlane.distanceSquared(start, candidate)
                if (d < distance && MixedLandingBays.clears(unit, listOf(candidate), terrain)) {
                    nearest = candidate
                    distance = d
                }
            }
            entryScans.remove(unit.unitId)
            nearest ?: return null
        }
        fun hasPassingRoom(point: Position): Boolean {
            val col = (point.x / terrain.tileWidth).toInt()
            val row = (point.y / terrain.tileHeight).toInt()
            if (col !in 0 until terrain.widthTiles || row !in 0 until terrain.heightTiles) return false
            val rings = terrain.clearanceRings(col, row)
            return (rings - 1).toDouble() * minOf(terrain.tileWidth, terrain.tileHeight) >= spacing + radius
        }
        val openEntry = hasPassingRoom(entry) && hasPassingRoom(group.click)
        val released = unit.landingMember?.releasedSite
        fun better(candidate: Position, dist: Double, current: Position?, currentDist: Double): Boolean {
            if (current == null) return true
            if (candidate == released && current != released) return false
            if (candidate != released && current == released) return true
            return dist < currentDist
        }
        var allTaken = saved?.allTaken ?: true
        var best = saved?.best
        var bestDistance = saved?.score ?: Double.POSITIVE_INFINITY
        var bestBlocked = saved?.bestBlocked
        var bestBlockedDistance = saved?.blockedScore ?: Double.POSITIVE_INFINITY
        for (index in (saved?.index ?: 0) until sites.size) {
            if (!budget.take(1)) {
                scans[unit.unitId] = Scan(entry, start, terrain, index, best, bestDistance, bestBlocked, bestBlockedDistance, allTaken)
                return null
            }
            val site = sites[index]
            // Fill through the pad at this vehicle's entrance before parking across it.
            // Pads on separate approaches compete by travel distance.
            val dx = site.x - start.x
            val dy = site.y - start.y
            val travel = dx * dx + dy * dy
            val projection = (entry.x - start.x) * dx + (entry.y - start.y) * dy
            val cross = (entry.x - start.x) * dy - (entry.y - start.y) * dx
            val throughEntry = site == entry || projection > 0 && projection <= travel && cross * cross <= radius * radius * travel
            val distance = if (throughEntry && !openEntry) -travel else travel
            if (occupied(site)) continue
            allTaken = false
            val beatsClear = better(site, distance, best, bestDistance)
            val beatsBlocked = best == null && better(site, distance, bestBlocked, bestBlockedDistance)
            if (!beatsClear && !beatsBlocked) continue
            val route = approach(unit, site, terrain)
            val terrainClear = MixedLandingBays.clears(unit, route, terrain)
            if (!terrainClear) continue
            val isUnobstructed = if (unobstructed == null) true else {
                val res = unobstructed(site)
                if (!res && budget.exhausted) {
                    scans[unit.unitId] = Scan(entry, start, terrain, index, best, bestDistance, bestBlocked, bestBlockedDistance, allTaken)
                    return null
                }
                res
            }
            if (isUnobstructed) {
                if (beatsClear) {
                    best = site
                    bestDistance = distance
                }
            } else if (beatsBlocked) {
                bestBlocked = site
                bestBlockedDistance = distance
            }
        }
        scans.remove(unit.unitId)
        val chosen = best ?: bestBlocked
        if ((allTaken || (released != null && (chosen == null || chosen == released))) && sites.isNotEmpty()) {
            needed = maxOf(needed, sites.size + 1)
        }
        return chosen?.takeUnless(occupied)
    }

    /** Join the final lane outside the parking area before driving between parked bodies. */
    fun approach(unit: MutableUnit, target: Position, terrain: PlanningTerrain): List<Position> {
        if (!outside || mixedBays?.openApron(terrain) == false) return listOf(target)
        if (mixedBays == null && PlanningSegmentClearance.allows(terrain, unit.position, target,
                unit.definition.movementCollision.boundingRadius)) return listOf(target)
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
        if (PlanningSegmentClearance.allows(terrain, unit.position, entry, radius) &&
            PlanningSegmentClearance.allows(terrain, entry, target, radius)) return listOf(entry, target)
        val travel = (target.x - unit.position.x) * ax + (target.y - unit.position.y) * ay
        val lane = Position(target.x - travel * ax, target.y - travel * ay)
        if (travel > 0 && PlanningSegmentClearance.allows(terrain, unit.position, lane, radius) &&
            PlanningSegmentClearance.allows(terrain, lane, target, radius)) return listOf(lane, target)
        return listOf(target)
    }

    companion object {
        fun forGroup(map: MapDefinition, group: GroupMoveRequest, members: List<MutableUnit>): SharedLandingSites {
            val lattice = minOf(map.tileWidth, map.tileHeight) * 0.5
            // A pair needs the sum of its two envelopes. Doubling a lone long hull would
            // put the next pad outside the very room both vehicles can occupy.
            var largest = 0.0
            var second = 0.0
            for (car in members) {
                val radius = car.definition.contactBufferCollision.boundingRadius
                if (radius >= largest) { second = largest; largest = radius } else second = maxOf(second, radius)
            }
            val spacing = maxOf(1.0, ceil((largest + second) / lattice)) * lattice
            val dx = group.click.x - members.sumOf { it.position.x } / members.size
            val dy = group.click.y - members.sumOf { it.position.y } / members.size
            val radius = spacing * sqrt(group.members.size / Math.PI)
            val fraction = minOf(0.5, radius / maxOf(1.0, sqrt(dx * dx + dy * dy)))
            val centre = Position(group.click.x - dx, group.click.y - dy)
            val extent = sqrt(members.sumOf { ScreenPlane.distanceSquared(centre, it.position) } / members.size)
            return SharedLandingSites(map, group, spacing, Position(group.click.x + dx * fraction, group.click.y + dy * fraction),
                sqrt(dx * dx + dy * dy) > extent + radius).also { pool ->
                    if (members.any { it.definition.movementCollision != members.first().definition.movementCollision })
                        pool.mixedBays = MixedLandingBays.forApproach(group.click, dx, dy, spacing * (sqrt(group.members.size.toDouble()) + 2.0))
                }
        }
    }
}
