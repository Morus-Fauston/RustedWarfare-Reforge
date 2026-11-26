package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.path.PlanningSegmentClearance
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rollback-safe registry of active heterogeneous scarce-bay claims ([ActiveClaim]) and parked site keys.
 *
 * Designed for O(1) zero-allocation steady-state cost during 60Hz ticks when no scarce bays are actively
 * claimed (`activeClaims.isEmpty()`), while providing full range-independent geometric overlap protection
 * (using true terminal bay arrival heading) across commands and players.
 */
internal class LandingOccupancy(
    private val bySite: MutableMap<Position, Set<String>> = hashMapOf(),
    private val byUnit: MutableMap<String, Set<Position>> = hashMapOf(),
    private val activeClaims: MutableMap<String, ActiveClaim> = linkedMapOf(),
    private val undo: PathSearchRollback? = null,
) {
    internal data class ActiveClaim(
        val unitId: String,
        val ownerPlayerId: String,
        val group: GroupMoveRequest,
        val layer: ContactLayer,
        val shape: MovementCollisionShape,
        val site: Position,
        val heading: Double,
    )

    internal class IndexedBody(val owner: Any, val site: Position?, val body: Position?, val claim: ActiveClaim?)

    fun fork(rollback: PathSearchRollback) =
        LandingOccupancy(bySite, byUnit, activeClaims, rollback)

    fun update(units: Collection<MutableUnit>) {
        for (unit in units) synchronize(unit)
    }

    fun update(unit: MutableUnit) = synchronize(unit)

    private fun synchronize(unit: MutableUnit) {
        val member = unit.landingMember
        val reservation = member?.claimed?.takeIf { unit.alive && !member.parked && unit.moveTarget != null }
        val body = unit.position.takeIf { unit.alive && member?.parked == true }
        val layer = if (reservation != null) UnitContact.layer(unit) else null
        val nextClaim = if (reservation != null && layer != null) {
            ActiveClaim(
                unitId = unit.unitId,
                ownerPlayerId = unit.ownerPlayerId,
                group = member.group,
                layer = layer,
                shape = unit.definition.movementCollision,
                site = reservation,
                heading = member.claimedHeading,
            )
        } else null
        val indexed = unit.indexedLandingBody
        if (indexed != null && indexed.owner === bySite &&
            indexed.site == reservation && indexed.body == body && indexed.claim == nextClaim
        ) return

        val oldClaim = activeClaims[unit.unitId]
        if (oldClaim != nextClaim) {
            if (nextClaim == null) activeClaims.remove(unit.unitId) else activeClaims[unit.unitId] = nextClaim
            undo?.record { if (oldClaim == null) activeClaims.remove(unit.unitId) else activeClaims[unit.unitId] = oldClaim }
        }

        unit.indexedLandingBody = IndexedBody(bySite, reservation, body, nextClaim)
        val old = byUnit[unit.unitId].orEmpty()
        if (old.size == (if (reservation != null) 1 else 0) + (if (body != null && body != reservation) 1 else 0) &&
            (reservation == null || reservation in old) && (body == null || body in old)
        ) return
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
        undo?.record { replace(next, old) }
    }

    fun at(site: Position): Set<String> = bySite[site].orEmpty()

    /**
     * Checks whether placing [unit] at ([at], [heading]) geometrically overlaps ANY active scarce-bay claim
     * on the same contact layer, regardless of how far away the claiming unit currently is.
     */
    fun overlapsAnyActiveClaim(
        unit: MutableUnit,
        at: Position,
        heading: Double,
        unitsById: Map<String, MutableUnit>,
        knowsOccupant: (MutableUnit, MutableUnit) -> Boolean,
    ): Boolean {
        if (activeClaims.isEmpty()) return false
        val ownLayer = UnitContact.layer(unit) ?: return false
        val ownShape = unit.definition.movementCollision
        for (claim in activeClaims.values) {
            if (claim.unitId == unit.unitId || claim.layer != ownLayer) continue
            val ownerUnit = unitsById[claim.unitId] ?: continue
            if (!ownerUnit.alive || ownerUnit.moveTarget == null) continue
            if (claim.ownerPlayerId != unit.ownerPlayerId && !knowsOccupant(unit, ownerUnit)) continue
            if (UnitContact.posesOverlap(ownShape, at, heading, claim.shape, claim.site, claim.heading)) {
                return true
            }
        }
        return false
    }
}

/**
 * Immutable per-unit terminal landing state for the unified single terminal landing layer (ENG-P016-009B).
 *
 * Units travelling en route hold no pre-claimed lattice coordinates. Only heterogeneous scarce-bay
 * protection ([MixedLandingBays]) sets [claimed] (with its true terminal arrival [claimedHeading])
 * inside the terminal apron; all circular groups converge centripetally toward the goal, slide around
 * settled wings when closer open room exists, and settle contagiously in O(1) without plugging 1-tile throats.
 */
internal data class LandingMember(
    val group: GroupMoveRequest,
    val claimed: Position? = null,
    val claimedHeading: Double = 0.0,
    val parked: Boolean = false,
    val flankSign: Int = 0,
    val lowProgressTicks: Long = 0L,
    val bestDistToGoal: Double = Double.POSITIVE_INFINITY,
    val stoppedOrder: UnitTask? = null,
)

/**
 * Unified terminal landing coordinator for a group order (ENG-P016-009B).
 *
 * Abolishes square-lattice pre-claiming, 16x subdivided detour grids, mid-route `incomingGuide`,
 * and 5-stage yielding state machines. Retains ONLY budgeted heterogeneous scarce-bay protection
 * ([mixedBays]) when a mixed-size group enters a constrained terminal apron.
 */
internal class SharedLandingSites(
    private val group: GroupMoveRequest,
    private val spacing: Double,
    var mixedBays: MixedLandingBays? = null,
) {
    private var searchCredit = 0

    fun fork(rollback: PathSearchRollback) = SharedLandingSites(group, spacing, mixedBays?.fork(rollback)).also {
        it.searchCredit = searchCredit
    }

    /** The exact planner's atomic operation costs eight. Save paid units from short slices. */
    fun advanceExact(query: ExactPathSearch, budget: PathWorkBudget) {
        val available = budget.limit - budget.used
        val total = searchCredit + available
        val quantum = total / 8 * 8
        if (quantum == 0) {
            budget.take(available)
            searchCredit = total
            return
        }
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

    fun near(p: Position): Boolean {
        val reach = spacing * (sqrt(group.members.size.toDouble()) + 2.0)
        return ScreenPlane.distanceSquared(p, group.click) <= reach * reach
    }

    companion object {
        fun forGroup(map: MapDefinition, group: GroupMoveRequest, members: List<MutableUnit>): SharedLandingSites {
            val lattice = minOf(map.tileWidth, map.tileHeight) * 0.5
            var largest = 0.0
            var second = 0.0
            for (car in members) {
                val radius = car.definition.contactBufferCollision.boundingRadius
                if (radius >= largest) {
                    second = largest
                    largest = radius
                } else {
                    second = maxOf(second, radius)
                }
            }
            val spacing = maxOf(1.0, ceil((largest + second) / lattice)) * lattice
            val dx = group.click.x - members.sumOf { it.position.x } / maxOf(1, members.size)
            val dy = group.click.y - members.sumOf { it.position.y } / maxOf(1, members.size)
            val mixed = if (members.any { it.definition.movementCollision != members.first().definition.movementCollision }) {
                MixedLandingBays.forApproach(group.click, dx, dy, spacing * (sqrt(group.members.size.toDouble()) + 2.0))
            } else null
            return SharedLandingSites(group, spacing, mixed)
        }
    }
}

/**
 * Continuous centripetal convergence, tangential flank sliding, and O(1) anti-throat-plugging
 * contagious settling engine (ENG-P016-009B).
 *
 * Four-layer separation invariants:
 * - Zero mid-route interference: outside the bounded terminal neighborhood or separated from `goal`
 *   by impassable terrain (checked at full hull passability radius, never leaking through sub-hull gaps),
 *   returns en-route immediately without mutating navigation targets or claims.
 * - Inside the terminal neighborhood: units drive continuously toward `goal`; when blocked by already-settled
 *   peers, circular units probe left/right tangential flanks (`probeFlank`) to fan smoothly into open wings.
 * - O(1) anti-throat-plugging with anti-deadlock guarantee: units corking a 1-tile throat resist settling
 *   while open side room exists or trailing teammates are advancing behind them, but cleanly allow settling
 *   inside blind 1-tile dead-ends or genuinely saturated rooms (`chosenAim == null` after sustained stall).
 * - Atomic legal settling: verifies terrain hull fit, map bounds, exact stationary boundary tangency
 *   (`UnitContact.poseClearance`), and range-independent active scarce-bay claim protection (`LandingOccupancy`).
 */
internal object TerminalLanding {
    internal data class Decision(
        val flankAim: Position?,
        val settleNow: Boolean,
        val inClusterZone: Boolean,
        val updatedMember: LandingMember,
    )

    private const val SETTLE_STALL_TICKS = 16L
    private const val HIGH_SPEED_STALL_TICKS = 32L
    private const val THROAT_DEADLOCK_SETTLE_TICKS = 48L
    private const val RADIAL_PROGRESS_EPS = 1.25

    fun evaluate(
        host: UnitMovementHost,
        unit: MutableUnit,
        map: MapDefinition,
        terrain: PlanningTerrain,
        goal: Position,
        desiredDirX: Double,
        desiredDirY: Double,
        maxSpeed: Double,
        delta: Double,
    ): Decision? {
        val member = unit.landingMember ?: return null
        if (member.parked) return null
        val hardR = max(1.0, unit.definition.movementCollision.boundingRadius)
        val distToGoal = hypot(goal.x - unit.position.x, goal.y - unit.position.y)

        // Explicit scarce-bay claim from MixedLandingBays: let exact bay arrival handle it cleanly.
        if (member.claimed != null) {
            return Decision(
                flankAim = null,
                settleNow = false,
                inClusterZone = distToGoal <= hardR * 3.5,
                updatedMember = member,
            )
        }

        val layer = UnitContact.layer(unit) ?: return null
        val spatialLayer = SpatialLayer.fromContact(layer)
        val isCircle = unit.definition.movementCollision is MovementCollisionShape.Circle
        val terrainShape = UnitLocomotion.terrainHull(unit, map)
        val terrainR = UnitLocomotion.terrainPassabilityRadius(unit, map.tileWidth, map.tileHeight)
        val boundR = max(terrainR, Transport.boundaryRadius(unit))
        val mapW = map.widthTiles.toDouble() * map.tileWidth
        val mapH = map.heightTiles.toDouble() * map.tileHeight
        val maxGroupR = max(hardR, member.group.maxRadiusByMovement[UnitLocomotion.movement(unit).type] ?: hardR)
        val landingZoneR = min(340.0, max(58.0, sqrt(member.group.members.size.toDouble()) * max(max(hardR * 2.4, maxGroupR * 2.2), 20.0) + 40.0))
        val hullGrid = HullTerrainGrid.authorized(terrain)

        // Strictly zero landing interference while separated from `goal` by impassable terrain!
        val hasTerrainLosToGoal = hasPassableTerrainLos(terrain, hullGrid, terrainShape, unit.position, goal, unit.heading, terrainR)
        if (!hasTerrainLosToGoal) {
            val clean = if (member.flankSign != 0 || member.lowProgressTicks != 0L || member.bestDistToGoal.isFinite()) {
                member.copy(flankSign = 0, lowProgressTicks = 0L, bestDistToGoal = Double.POSITIVE_INFINITY)
            } else member
            return Decision(flankAim = null, settleNow = false, inClusterZone = false, updatedMember = clean)
        }
        if (distToGoal > landingZoneR) {
            // Outside nominal cluster radius: stay strictly en route UNLESS physically touching an already-settled
            // peer of the same group on this open target side (contagious outer perimeter of large 100+ clusters).
            val edgeQueryR = hardR + max(36.0, hardR * 1.25)
            val edgePeers = host.spatialIndex.queryBounds(
                spatialLayer,
                unit.position.x - edgeQueryR, unit.position.x + edgeQueryR,
                unit.position.y - edgeQueryR, unit.position.y + edgeQueryR,
                useBufferRadius = false,
            )
            var touchesSettledPeer = false
            for (i in 0 until edgePeers.size) {
                val other = edgePeers[i]
                if (other === unit || !other.alive) continue
                if (other.landingMember?.group !== member.group || other.landingMember?.parked != true) continue
                val otherR = max(1.0, other.definition.movementCollision.boundingRadius)
                val reach = hardR + otherR + max(15.0, hardR * 1.20)
                if (ScreenPlane.distanceSquared(unit.position, other.position) <= reach * reach &&
                    (terrainShape == null || hasPassableTerrainLos(terrain, hullGrid, terrainShape, unit.position, other.position, unit.heading, terrainR))
                ) {
                    touchesSettledPeer = true
                    break
                }
            }
            if (!touchesSettledPeer) {
                val clean = if (member.flankSign != 0 || member.lowProgressTicks != 0L || member.bestDistToGoal.isFinite()) {
                    member.copy(flankSign = 0, lowProgressTicks = 0L, bestDistToGoal = Double.POSITIVE_INFINITY)
                } else member
                return Decision(flankAim = null, settleNow = false, inClusterZone = false, updatedMember = clean)
            }
        }

        val poseLegalNow = canSettleAtPose(host, unit, mapW, mapH, boundR, terrainShape, hullGrid, spatialLayer)
        val inThroat = isPluggingSingleTileThroat(host, unit, map, terrain, member.group, goal, hardR, spatialLayer)
        val gdx = if (distToGoal > 1e-5) (goal.x - unit.position.x) / distToGoal else desiredDirX
        val gdy = if (distToGoal > 1e-5) (goal.y - unit.position.y) / distToGoal else desiredDirY

        // 1. Core seed direct arrival: lead unit(s) reaching the core center settle accurately on goal.
        // Preserve exact 1e-6 arrival precision for maneuver-routed nearest-reachable shore/barrier endpoints,
        // while allowing flow-field boundary targets (`!followsManeuverRoute`) normal targetRadius capture.
        val exactNearestSnap = unit.nearestMoveTarget != null && unit.followsManeuverRoute
        val coreCaptureR = if (exactNearestSnap) 0.000001 else max(1.5, host.movementContract.targetRadius)
        if (distToGoal <= coreCaptureR && poseLegalNow) {
            if (!inThroat) {
                return Decision(flankAim = null, settleNow = true, inClusterZone = true, updatedMember = member)
            }
            // Anti-deadlock for 1-tile blind dead-ends: if at the goal itself and neither flank can step out, settle now.
            val probeStep = max(hardR * 1.4, 9.5)
            val escapeCap = max(16.0, hardR * 2.8)
            val canEscapeLeft = probeFlank(
                host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                spatialLayer, goal, distToGoal, gdx, gdy, sign = -1, probeStep = probeStep,
                distDeltaCap = escapeCap, inwardWeight = 0.0,
            ) != null
            val canEscapeRight = probeFlank(
                host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                spatialLayer, goal, distToGoal, gdx, gdy, sign = +1, probeStep = probeStep,
                distDeltaCap = escapeCap, inwardWeight = 0.0,
            ) != null
            if (!canEscapeLeft && !canEscapeRight) {
                return Decision(flankAim = null, settleNow = true, inClusterZone = true, updatedMember = member)
            }
        }

        // 2. O(1) spatial neighborhood inspection around the unit.
        val queryR = hardR + max(36.0, hardR * 1.25)
        val neighbors = host.spatialIndex.queryBounds(
            spatialLayer,
            unit.position.x - queryR, unit.position.x + queryR,
            unit.position.y - queryR, unit.position.y + queryR,
            useBufferRadius = false,
        )

        // Only treat very tight core proximity as self-seeded (never prematurely settling an unobstructed large lead vehicle).
        var connectedToSettledCore = !exactNearestSnap && distToGoal <= max(6.0, hardR * 0.6)
        var frontBlockedByStationary = false
        var anySettledNearby = false
        var touchingCoreSeed = false
        for (i in 0 until neighbors.size) {
            val other = neighbors[i]
            if (other === unit || !other.alive) continue
            if (!host.knowsRouteOccupant(unit, other)) continue
            val otherR = max(1.0, other.definition.movementCollision.boundingRadius)
            val ox = other.position.x - unit.position.x
            val oy = other.position.y - unit.position.y
            val dist = hypot(ox, oy)
            val isSettledPeer = other.landingMember?.group === member.group && other.landingMember?.parked == true
            val isStationary = other.moveTarget == null
            if (isSettledPeer) anySettledNearby = true
            val otherDistToGoal = hypot(goal.x - other.position.x, goal.y - other.position.y)
            // Keep contactReach == frontReach so units held at ORCA comfort spacing around a settled perimeter
            // never fall into a frontBlocked-without-connectedToSettledCore dead zone.
            val reach = hardR + otherR + max(15.0, hardR * 1.20)
            if (dist > reach) continue
            // Never treat a stationary unit across an impassable wall or sub-hull narrow gap as a cluster neighbor!
            if (terrainShape != null && !hasPassableTerrainLos(terrain, hullGrid, terrainShape, unit.position, other.position, unit.heading, terrainR)) {
                continue
            }
            if (isSettledPeer || (isStationary && otherDistToGoal < distToGoal - 0.5)) {
                connectedToSettledCore = true
                if (otherDistToGoal <= otherR + 6.0 && dist <= hardR + otherR + max(8.0, hardR * 0.75)) {
                    touchingCoreSeed = true
                }
            }
            if (isStationary && dist > 1e-5) {
                val fwd = (ox * gdx + oy * gdy) / dist
                val lat = abs(ox * gdy - oy * gdx)
                if (fwd >= 0.25 && lat <= hardR + otherR + 4.0) {
                    frontBlockedByStationary = true
                }
            }
        }

        val inClusterZone = connectedToSettledCore || anySettledNearby || distToGoal <= max(28.0, hardR * 2.8)

        // Not blocked by any stationary core yet: keep driving straight toward goal!
        if (!connectedToSettledCore && !frontBlockedByStationary) {
            val nextBest = min(member.bestDistToGoal, distToGoal)
            val updated = if (member.flankSign != 0 || member.lowProgressTicks != 0L || member.bestDistToGoal != nextBest) {
                member.copy(flankSign = 0, lowProgressTicks = 0L, bestDistToGoal = nextBest)
            } else member
            return Decision(flankAim = null, settleNow = false, inClusterZone = inClusterZone, updatedMember = updated)
        }

        // Update monotone radial progress tracker: orbiting at constant radius does NOT reset lowProgressTicks!
        val progressed = updateRadialProgressTicks(member, distToGoal, delta)

        // 3. Rectangular hulls in mixed groups (e.g. long truck arriving next to parked small car inside a room):
        // settle cleanly once blocked adjacent to settled core at legal pose.
        if (!isCircle) {
            val updated = progressed.copy(flankSign = 0)
            val lowSpdRect = abs(unit.speed) <= 0.30 * maxSpeed
            val throatFallback = inThroat && lowSpdRect && updated.lowProgressTicks >= THROAT_DEADLOCK_SETTLE_TICKS
            val canSettle = connectedToSettledCore && frontBlockedByStationary && poseLegalNow &&
                (!inThroat || throatFallback) &&
                (lowSpdRect || updated.lowProgressTicks >= SETTLE_STALL_TICKS)
            return Decision(flankAim = null, settleNow = canSettle, inClusterZone = true, updatedMember = updated)
        }

        // 4. Continuous tangential flank sliding check (for circular units):
        // Skip flanking if already touching the core seed covering the goal (prevents orbiting the lead unit),
        // unless inside or corking a 1-tile throat that must be cleared for trailing teammates.
        var chosenSign = 0
        var chosenAim: Position? = null
        if (!touchingCoreSeed || inThroat) {
            val probeStep = max(hardR * 1.4, 9.5)
            val flankDistAllowance = if (inThroat) max(16.0, hardR * 2.8) else -1.0
            val leftFlank = if (inThroat) {
                probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = -1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = -0.22,
                ) ?: probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = -1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = 0.0,
                )
            } else {
                probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = -1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = 0.352,
                ) ?: if (distToGoal > hardR * 3.5) {
                    // Shoulder wrap-around probe: when a fat mixed cluster blocks the inward chord, allow
                    // curving around the outer shoulder instead of stacking into a long conga line back into gates.
                    probeFlank(
                        host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                        spatialLayer, goal, distToGoal, gdx, gdy, sign = -1, probeStep = probeStep,
                        distDeltaCap = hardR * 0.45, inwardWeight = 0.12,
                    )
                } else null
            }
            val rightFlank = if (inThroat) {
                probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = +1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = -0.22,
                ) ?: probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = +1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = 0.0,
                )
            } else {
                probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = +1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = 0.352,
                ) ?: if (distToGoal > hardR * 3.5) {
                    probeFlank(
                        host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                        spatialLayer, goal, distToGoal, gdx, gdy, sign = +1, probeStep = probeStep,
                        distDeltaCap = hardR * 0.45, inwardWeight = 0.12,
                    )
                } else null
            }
            when {
                leftFlank != null && rightFlank != null -> {
                    chosenSign = when {
                        member.flankSign == -1 -> -1
                        member.flankSign == +1 -> +1
                        leftFlank.second + 0.5 < rightFlank.second -> -1
                        rightFlank.second + 0.5 < leftFlank.second -> +1
                        (unit.unitId.hashCode() and 1) == 0 -> +1
                        else -> -1
                    }
                    chosenAim = if (chosenSign < 0) leftFlank.first else rightFlank.first
                }
                leftFlank != null -> {
                    chosenSign = -1
                    chosenAim = leftFlank.first
                }
                rightFlank != null -> {
                    chosenSign = +1
                    chosenAim = rightFlank.first
                }
            }
        }

        val updated = progressed.copy(flankSign = chosenSign)

        // If an open closer flank exists AND unit has not stalled radially for the settle window, slide into the wing!
        if (chosenAim != null && (inThroat || updated.lowProgressTicks < SETTLE_STALL_TICKS)) {
            return Decision(
                flankAim = chosenAim,
                settleNow = false,
                inClusterZone = true,
                updatedMember = updated,
            )
        }

        // 5. O(1) Anti-throat-plugging contagious settling (with anti-deadlock exit for saturated rooms/dead-ends):
        val lowSpeed = abs(unit.speed) <= max(0.22, 0.35 * maxSpeed)
        val throatSaturatedExit = inThroat && chosenAim == null &&
            (lowSpeed || frontBlockedByStationary) && updated.lowProgressTicks >= THROAT_DEADLOCK_SETTLE_TICKS
        val openSettleReady = !inThroat && (
            (frontBlockedByStationary && chosenAim == null && (lowSpeed || updated.lowProgressTicks >= 6L)) ||
                ((lowSpeed || frontBlockedByStationary) && updated.lowProgressTicks >= SETTLE_STALL_TICKS) ||
                updated.lowProgressTicks >= HIGH_SPEED_STALL_TICKS
            )
        val readyToSettle = connectedToSettledCore && poseLegalNow && (openSettleReady || throatSaturatedExit)
        return Decision(
            flankAim = if (readyToSettle) null else chosenAim,
            settleNow = readyToSettle,
            inClusterZone = true,
            updatedMember = updated,
        )
    }

    /**
     * Verifies straight-line terrain passability between [from] and [to] at the unit's true terrain hull width,
     * ensuring large hulls cannot see across 1-tile pinholes or walls they physically cannot traverse.
     */
    private fun hasPassableTerrainLos(
        terrain: PlanningTerrain,
        hullGrid: HullTerrainGrid,
        terrainShape: MovementCollisionShape?,
        from: Position,
        to: Position,
        heading: Double,
        terrainR: Double,
    ): Boolean {
        if (!PlanningSegmentClearance.allows(terrain, from, to, terrainR)) return false
        if (terrainShape == null) return true
        val minTile = min(terrain.tileWidth, terrain.tileHeight).toDouble()
        if (terrainR <= minTile * 0.5) return true
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dist = hypot(dx, dy)
        if (dist <= minTile * 0.25) return true
        val stepSize = max(4.0, minTile * 0.5)
        val steps = max(1, ceil(dist / stepSize).toInt())
        for (i in 1 until steps) {
            val t = i.toDouble() / steps
            val sample = Position(from.x + dx * t, from.y + dy * t)
            if (!UnitContact.poseFits(terrainShape, sample, heading, hullGrid)) return false
        }
        return true
    }

    private fun updateRadialProgressTicks(
        member: LandingMember,
        distToGoal: Double,
        delta: Double,
    ): LandingMember {
        if (!member.bestDistToGoal.isFinite() || distToGoal < member.bestDistToGoal - RADIAL_PROGRESS_EPS) {
            return member.copy(lowProgressTicks = 0L, bestDistToGoal = distToGoal)
        }
        return if (delta > 0.0) member.copy(lowProgressTicks = member.lowProgressTicks + 1L) else member
    }

    private fun probeFlank(
        host: UnitMovementHost,
        unit: MutableUnit,
        terrain: PlanningTerrain,
        hullGrid: HullTerrainGrid,
        mapW: Double,
        mapH: Double,
        boundR: Double,
        terrainShape: MovementCollisionShape?,
        terrainR: Double,
        hardR: Double,
        spatialLayer: SpatialLayer,
        goal: Position,
        distToGoal: Double,
        gdx: Double,
        gdy: Double,
        sign: Int,
        probeStep: Double,
        distDeltaCap: Double,
        inwardWeight: Double = 0.352,
    ): Pair<Position, Double>? {
        val tx = if (sign < 0) -gdy else gdy
        val ty = if (sign < 0) gdx else -gdx
        val tangWeight = sqrt(max(0.0, 1.0 - inwardWeight * inwardWeight))
        val rawX = tangWeight * tx + inwardWeight * gdx
        val rawY = tangWeight * ty + inwardWeight * gdy
        val norm = hypot(rawX, rawY)
        if (norm <= 1e-6) return null
        val fx = rawX / norm
        val fy = rawY / norm
        val probe = Position(unit.position.x + fx * probeStep, unit.position.y + fy * probeStep)
        val probeDist = hypot(goal.x - probe.x, goal.y - probe.y)
        if (probeDist > distToGoal + distDeltaCap) return null
        if (!MapBounds.contains(mapW, mapH, probe, boundR)) return null
        if (terrainShape != null) {
            if (!PlanningSegmentClearance.allows(terrain, unit.position, probe, terrainR)) return null
            if (!UnitContact.poseFits(terrainShape, probe, unit.heading, hullGrid)) return null
        }
        val queryR = hardR + 22.0
        val nearProbe = host.spatialIndex.queryBounds(
            spatialLayer,
            probe.x - queryR, probe.x + queryR,
            probe.y - queryR, probe.y + queryR,
            useBufferRadius = false,
        )
        for (i in 0 until nearProbe.size) {
            val other = nearProbe[i]
            if (other === unit || !other.alive) continue
            if (!host.knowsRouteOccupant(unit, other)) continue
            val otherR = max(1.0, other.definition.movementCollision.boundingRadius)
            val stationaryOrPinned = other.moveTarget == null || abs(other.speed) <= 0.08 * max(0.2, UnitLocomotion.movement(other).moveSpeed)
            if (!stationaryOrPinned) continue
            val minGap = hardR + otherR + 0.75
            if (ScreenPlane.distanceSquared(probe, other.position) < minGap * minGap) return null
        }
        val aimStep = max(26.0, hardR * 2.4)
        val aim = Position(unit.position.x + fx * aimStep, unit.position.y + fy * aimStep)
        return aim to probeDist
    }

    private fun isPluggingSingleTileThroat(
        host: UnitMovementHost,
        unit: MutableUnit,
        map: MapDefinition,
        terrain: PlanningTerrain,
        group: GroupMoveRequest,
        goal: Position,
        hardR: Double,
        spatialLayer: SpatialLayer,
    ): Boolean {
        fun blocked(c: Int, r: Int): Boolean =
            c !in 0 until terrain.widthTiles || r !in 0 until terrain.heightTiles || terrain.isBlocked(c, r)
        fun isPinchCell(c: Int, r: Int): Boolean {
            if (c !in 0 until terrain.widthTiles || r !in 0 until terrain.heightTiles || terrain.isBlocked(c, r)) return false
            return (blocked(c - 1, r) && blocked(c + 1, r)) || (blocked(c, r - 1) && blocked(c, r + 1))
        }
        fun cellCorksPinch(px: Double, py: Double): Boolean {
            val c = floor(px / terrain.tileWidth).toInt().coerceIn(0, terrain.widthTiles - 1)
            val r = floor(py / terrain.tileHeight).toInt().coerceIn(0, terrain.heightTiles - 1)
            return isPinchCell(c, r) ||
                isPinchCell(c - 1, r) || isPinchCell(c + 1, r) ||
                isPinchCell(c, r - 1) || isPinchCell(c, r + 1)
        }
        val onOrCorkingPinch = cellCorksPinch(unit.position.x, unit.position.y) ||
            cellCorksPinch(unit.position.x - hardR, unit.position.y) ||
            cellCorksPinch(unit.position.x + hardR, unit.position.y) ||
            cellCorksPinch(unit.position.x, unit.position.y - hardR) ||
            cellCorksPinch(unit.position.x, unit.position.y + hardR)
        if (!onOrCorkingPinch) return false

        val ownDist = hypot(goal.x - unit.position.x, goal.y - unit.position.y)
        val searchR = max(map.tileWidth, map.tileHeight) * 4.5
        val nearby = host.spatialIndex.queryBounds(
            spatialLayer,
            unit.position.x - searchR, unit.position.x + searchR,
            unit.position.y - searchR, unit.position.y + searchR,
            useBufferRadius = false,
        )
        for (i in 0 until nearby.size) {
            val other = nearby[i]
            if (other === unit || !other.alive || other.moveTarget == null) continue
            val otherLanding = other.landingMember ?: continue
            if (otherLanding.group !== group || otherLanding.parked) continue
            val otherDist = hypot(goal.x - other.position.x, goal.y - other.position.y)
            if (otherDist > ownDist + hardR * 0.5) return true
        }
        return false
    }

    private fun canSettleAtPose(
        host: UnitMovementHost,
        unit: MutableUnit,
        mapW: Double,
        mapH: Double,
        boundR: Double,
        terrainShape: MovementCollisionShape?,
        hullGrid: HullTerrainGrid,
        spatialLayer: SpatialLayer,
    ): Boolean {
        if (!MapBounds.contains(mapW, mapH, unit.position, if (terrainShape is MovementCollisionShape.Circle) boundR else 0.0)) {
            return false
        }
        if (terrainShape != null && !UnitContact.poseFits(terrainShape, unit.position, unit.heading, hullGrid)) {
            return false
        }
        // Range-independent geometric protection of all active scarce-bay claims (uses true terminal bay heading).
        if (host.landingOccupancy.overlapsAnyActiveClaim(unit, unit.position, unit.heading, host.units, host::knowsRouteOccupant)) {
            return false
        }
        val ownShape = unit.definition.movementCollision
        val ownR = ownShape.boundingRadius
        val queryR = ownR + 30.0
        val near = host.spatialIndex.queryBounds(
            spatialLayer,
            unit.position.x - queryR, unit.position.x + queryR,
            unit.position.y - queryR, unit.position.y + queryR,
            useBufferRadius = false,
        )
        for (i in 0 until near.size) {
            val other = near[i]
            if (other === unit || !other.alive) continue
            if (!UnitContact.shareLayer(unit, other)) continue
            if (other.moveTarget == null) {
                // Allow exact stationary boundary tangency (SPEC §2026-11-23) up to binary64 position roundoff.
                val clearance = UnitContact.poseClearance(
                    ownShape, unit.position, unit.heading,
                    other.definition.movementCollision, other.position, other.heading,
                )
                val tangencyEps = max(1e-6, 16.0 * Math.ulp(maxOf(abs(unit.position.x), abs(unit.position.y), ownR, other.definition.movementCollision.boundingRadius)))
                if (clearance < -tangencyEps) return false
            }
        }
        return true
    }
}
