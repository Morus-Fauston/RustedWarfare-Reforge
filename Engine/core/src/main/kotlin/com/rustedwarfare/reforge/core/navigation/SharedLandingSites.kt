package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.ScreenPlane
import com.rustedwarfare.reforge.core.sim.MapBounds
import com.rustedwarfare.reforge.core.sim.MutableUnit
import com.rustedwarfare.reforge.core.sim.WorldHandle

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rectangular front no-parking corridor in front of a factory exit doorway (ENG-P017-008).
 * Prevents newly produced units from parking inside the exit throat and triggers active
 * tangential fan-out left/right around the staging anchor.
 */
internal data class NoParkingCorridor(
    val originX: Double,
    val originY: Double,
    val dirX: Double,
    val dirY: Double,
    val clearDist: Double,
    val halfWidth: Double,
    val minSettleAlong: Double = 0.0,
) {
    fun contains(point: Position, radius: Double): Boolean {
        val dx = point.x - originX
        val dy = point.y - originY
        val along = dx * dirX + dy * dirY
        if (minSettleAlong > 0.0 && along < minSettleAlong) return true
        if (along < -radius || along > clearDist) return false
        val across = abs(dx * (-dirY) + dy * dirX)
        return across <= halfWidth + radius
    }
}

/** The selected members and target of one ordinary or explicit cohort-escort group order. */
class GroupMoveRequest internal constructor(
    internal val world: WorldHandle,
    internal val completedStep: Long?,
    internal val actor: String,
    internal val click: Position,
    internal val members: Set<String>,
    internal val maxRadiusByMovement: Map<MovementType, Double> = emptyMap(),
    internal val dynamicCluster: Boolean = false,
    internal val noParkingCorridor: NoParkingCorridor? = null,
    internal val traversalMode: TraversalMode = TraversalMode.INDEPENDENT,
    internal val sharedCorridorRadiusByMovement: Map<MovementType, Double> = emptyMap(),
    internal val cohortEscortBiasByUnit: Map<String, Double> = emptyMap(),
    internal val cohortStartDistances: Map<String, Double> = emptyMap(),
) {
    internal val trafficOrder: List<String> = members.sorted()
    internal val flowFieldEligible: Boolean
        get() = members.size >= ChunkedFlowFieldAdapter.MIN_GROUP_SIZE ||
            (dynamicCluster && noParkingCorridor == null) ||
            (traversalMode == TraversalMode.COHORT_ESCORT && members.size >= 2 && sharedCorridorRadiusByMovement.isNotEmpty())

    internal fun repairDue(step: Long): Boolean = (step - (completedStep ?: -1L) - 1L) % ROUTE_RETRY_TICKS == 0L
}

internal const val ROUTE_RETRY_TICKS = 60L

internal fun MutableUnit.wallRepairDue(step: Long): Boolean = terrainCorrectionTime >= 3.0 && moveTarget != null &&
    (landingMember?.group?.repairDue(step) ?: (step % ROUTE_RETRY_TICKS == 0L))

internal fun sharedWallRepairDue(units: Collection<MutableUnit>, step: Long): Boolean = units.any {
    it.landingMember != null && it.wallRepairDue(step)
}

/** Arrival receipts carry the click for display. Live unified landings carry the order identity. */
internal fun GroupMoveRequest.hasParkedMember(unit: MutableUnit): Boolean {
    if (unit.ownerPlayerId != actor || unit.moveTarget != null) return false
    if (!dynamicCluster && unit.unitId !in members) return false
    return unit.landingMember?.let { it.parked && it.group === this } == true
}

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

        unit.updateNavigation { it.withIndexedLandingBody(IndexedBody(bySite, reservation, body, nextClaim)) }
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
 * Immutable per-unit terminal landing state for the unified single terminal landing layer (ENG-P017-007).
 *
 * Units travelling en route hold no pre-claimed lattice coordinates. Only heterogeneous scarce-bay
 * protection ([MixedLandingBays]) sets [claimed] (with its true terminal arrival [claimedHeading])
 * inside the terminal apron; all groups converge centripetally toward the goal, respect cul-de-sac
 * pocket topology (Stage A), swap claims in O(1) when blocked by a deeper-claimed peer, slide around
 * settled wings when closer/flank room exists, and settle contagiously without plugging throats.
 */
internal data class LandingMember(
    val group: GroupMoveRequest,
    val claimed: Position? = null,
    val claimedHeading: Double = 0.0,
    val parked: Boolean = false,
    val flankSign: Int = 0,
    val lowProgressTicks: Long = 0L,
    val bestDistToGoal: Double = Double.POSITIVE_INFINITY,
)

/**
 * One-shot local reverse-BFS topological classifier around a group click point (Cul-de-sac Stage A, ENG-P017-007).
 *
 * Built at most once per `(SharedLandingSites, PlanningTerrain)` in O(localWindow) (~<= 0.05 ms) and cached
 * immutably so 60Hz ticks query `(isInterior, topologicalDepth, isInMouthBand)` in pure O(1) with zero
 * allocation and zero rollback cost:
 * - Detects confined blind/narrow-mouth pockets by checking lateral wall confinement along expanding BFS shells
 *   from the click cell outward until the wavefront breaks into an unconfined exterior domain;
 * - Tags interior cells (`isInterior = true`) with `topologicalDepth` strictly increasing toward the back wall
 *   (deepest-first fill) and exterior cells (`isInterior = false`) with topological distance from the mouth
 *   (outer wings farther from the mouth fill cleanly while keeping mouth approach open until interior saturates).
 */
internal class PocketTopology private constructor(
    val terrain: PlanningTerrain,
    val minCol: Int,
    val minRow: Int,
    val localW: Int,
    val localH: Int,
    val hasPocket: Boolean,
    val mouthShellDist: Int,
    val interiorCellCount: Int,
    val mouthCenter: Position,
    private val topoDist: IntArray,
    private val interiorMask: BooleanArray,
    private val depthArr: IntArray,
    private val mouthBandMask: BooleanArray,
) {
    private fun localIndex(pos: Position): Int {
        val c = floor(pos.x / terrain.tileWidth).toInt().coerceIn(0, terrain.widthTiles - 1) - minCol
        val r = floor(pos.y / terrain.tileHeight).toInt().coerceIn(0, terrain.heightTiles - 1) - minRow
        if (c !in 0 until localW || r !in 0 until localH) return -1
        return r * localW + c
    }

    fun isReachable(pos: Position): Boolean {
        val idx = localIndex(pos)
        return idx >= 0 && topoDist[idx] >= 0
    }

    fun isInteriorAt(pos: Position): Boolean {
        if (!hasPocket) return false
        val idx = localIndex(pos)
        return idx >= 0 && interiorMask[idx]
    }

    fun isInMouthBand(pos: Position): Boolean {
        if (!hasPocket) return false
        val idx = localIndex(pos)
        return idx >= 0 && mouthBandMask[idx]
    }

    fun topologicalDepthAt(pos: Position): Int {
        val idx = localIndex(pos)
        return if (idx >= 0) depthArr[idx] else Int.MIN_VALUE
    }

    fun bfsStepsFromClick(pos: Position): Int {
        val idx = localIndex(pos)
        return if (idx >= 0) topoDist[idx] else -1
    }

    fun estimatedInteriorCapacity(unitRadius: Double): Int {
        if (!hasPocket || interiorCellCount <= 0) return 0
        val tileArea = terrain.tileWidth.toDouble() * terrain.tileHeight.toDouble()
        // Account for finite-corridor wall margins + contact-buffer packing density (~0.55-0.60 packing factor)
        val effectiveSide = 2.35 * unitRadius + 2.0
        val unitFootprint = max(25.0, effectiveSide * effectiveSide)
        return max(1, floor((interiorCellCount * tileArea) / unitFootprint).toInt())
    }

    companion object {
        private const val MAX_LOCAL_RADIUS_TILES = 24
        private const val MAX_CONFINED_SPAN_TILES = 6
        private val ORTHO_DC = intArrayOf(1, -1, 0, 0)
        private val ORTHO_DR = intArrayOf(0, 0, 1, -1)

        fun analyze(terrain: PlanningTerrain, click: Position, groupSize: Int): PocketTopology {
            val w = terrain.widthTiles
            val h = terrain.heightTiles
            val rawCol = floor(click.x / terrain.tileWidth).toInt().coerceIn(0, w - 1)
            val rawRow = floor(click.y / terrain.tileHeight).toInt().coerceIn(0, h - 1)
            val radius = min(MAX_LOCAL_RADIUS_TILES, max(10, ceil(sqrt(groupSize.toDouble()) * 1.8).toInt() + 4))
            val minC = max(0, rawCol - radius)
            val maxC = min(w - 1, rawCol + radius)
            val minR = max(0, rawRow - radius)
            val maxR = min(h - 1, rawRow + radius)
            val lw = maxC - minC + 1
            val lh = maxR - minR + 1
            val total = lw * lh

            val dist = IntArray(total) { -1 }
            val interior = BooleanArray(total)
            val depth = IntArray(total) { Int.MIN_VALUE }
            val mouthBand = BooleanArray(total)

            // Resolve seed cell (click cell or nearest passable cell inside window)
            var seedC = rawCol
            var seedR = rawRow
            if (terrain.isBlocked(seedC, seedR)) {
                var bestD2 = Int.MAX_VALUE
                var found = false
                for (r in minR..maxR) {
                    for (c in minC..maxC) {
                        if (terrain.isBlocked(c, r)) continue
                        val dc = c - rawCol
                        val dr = r - rawRow
                        val d2 = dc * dc + dr * dr
                        if (d2 < bestD2) {
                            bestD2 = d2
                            seedC = c
                            seedR = r
                            found = true
                        }
                    }
                }
                if (!found) {
                    return PocketTopology(
                        terrain, minC, minR, lw, lh, false, -1, 0, click,
                        dist, interior, depth, mouthBand,
                    )
                }
            }

            fun blockedGlobal(c: Int, r: Int): Boolean =
                c !in 0 until w || r !in 0 until h || terrain.isBlocked(c, r)

            fun isConfinedCell(c: Int, r: Int): Boolean {
                var left = 0
                var leftWall = false
                while (left < MAX_CONFINED_SPAN_TILES) {
                    if (blockedGlobal(c - left - 1, r)) { leftWall = true; break }
                    left++
                }
                var right = 0
                var rightWall = false
                while (right < MAX_CONFINED_SPAN_TILES) {
                    if (blockedGlobal(c + right + 1, r)) { rightWall = true; break }
                    right++
                }
                if (leftWall && rightWall && left + 1 + right <= MAX_CONFINED_SPAN_TILES) return true

                var up = 0
                var upWall = false
                while (up < MAX_CONFINED_SPAN_TILES) {
                    if (blockedGlobal(c, r - up - 1)) { upWall = true; break }
                    up++
                }
                var down = 0
                var downWall = false
                while (down < MAX_CONFINED_SPAN_TILES) {
                    if (blockedGlobal(c, r + down + 1)) { downWall = true; break }
                    down++
                }
                return upWall && downWall && up + 1 + down <= MAX_CONFINED_SPAN_TILES
            }

            // One-shot bounded local BFS from seed cell
            val queue = IntArray(total)
            var head = 0
            var tail = 0
            val seedLocal = (seedR - minR) * lw + (seedC - minC)
            dist[seedLocal] = 0
            queue[tail++] = seedLocal
            var maxShell = 0

            while (head < tail) {
                val cur = queue[head++]
                val cd = dist[cur]
                if (cd > maxShell) maxShell = cd
                val lc = cur % lw
                val lr = cur / lw
                val gc = minC + lc
                val gr = minR + lr
                for (d in 0 until 4) {
                    val nlc = lc + ORTHO_DC[d]
                    val nlr = lr + ORTHO_DR[d]
                    if (nlc !in 0 until lw || nlr !in 0 until lh) continue
                    val nIdx = nlr * lw + nlc
                    if (dist[nIdx] >= 0) continue
                    if (terrain.isBlocked(minC + nlc, minR + nlr)) continue
                    dist[nIdx] = cd + 1
                    queue[tail++] = nIdx
                }
            }

            // Classify per-shell confinement vs open exterior expansion
            val shellTotal = IntArray(maxShell + 1)
            val shellOpen = IntArray(maxShell + 1)
            val confinedMask = BooleanArray(total)
            for (i in 0 until tail) {
                val idx = queue[i]
                val d = dist[idx]
                val gc = minC + (idx % lw)
                val gr = minR + (idx / lw)
                shellTotal[d]++
                val conf = isConfinedCell(gc, gr)
                confinedMask[idx] = conf
                if (!conf) shellOpen[d]++
            }

            val seedConfined = confinedMask[seedLocal]
            var mouthDist = -1
            if (seedConfined) {
                // Find the first shell where the BFS wave exits the confined pocket into unconfined exterior space
                for (d in 1..maxShell) {
                    if (shellOpen[d] > 0) {
                        // Verify exterior truly expands (at least one later shell has broader open width)
                        val laterOpen = (d..min(maxShell, d + 3)).maxOfOrNull { shellOpen[it] } ?: 0
                        if (laterOpen >= 3) {
                            mouthDist = d
                            break
                        }
                    }
                }
            }

            if (mouthDist <= 0) {
                // Ordinary open ground or blind closed corridor without an exterior opening
                for (i in 0 until tail) {
                    val idx = queue[i]
                    depth[idx] = -dist[idx]
                }
                return PocketTopology(
                    terrain, minC, minR, lw, lh, false, -1, 0, click,
                    dist, interior, depth, mouthBand,
                )
            }

            var interiorCount = 0
            var mouthSumX = 0.0
            var mouthSumY = 0.0
            var mouthCount = 0
            for (i in 0 until tail) {
                val idx = queue[i]
                val d = dist[idx]
                val gc = minC + (idx % lw)
                val gr = minR + (idx / lw)
                val isInt = confinedMask[idx] && d < mouthDist
                interior[idx] = isInt
                if (isInt) {
                    interiorCount++
                    // Deeper inside the pocket (smaller BFS distance from back click, or larger distance from mouth) => higher depth
                    depth[idx] = mouthDist - d
                } else {
                    // Exterior overflow: cells farther from the mouth get higher rank than immediate mouth entrance
                    depth[idx] = -(d - mouthDist + 1)
                }
                if (d in (mouthDist - 1)..(mouthDist + 1)) {
                    mouthBand[idx] = true
                    if (d == mouthDist) {
                        mouthSumX += (gc + 0.5) * terrain.tileWidth
                        mouthSumY += (gr + 0.5) * terrain.tileHeight
                        mouthCount++
                    }
                }
            }

            val mouthPos = if (mouthCount > 0) Position(mouthSumX / mouthCount, mouthSumY / mouthCount) else click
            return PocketTopology(
                terrain, minC, minR, lw, lh,
                hasPocket = interiorCount >= 4,
                mouthShellDist = mouthDist,
                interiorCellCount = interiorCount,
                mouthCenter = mouthPos,
                topoDist = dist,
                interiorMask = interior,
                depthArr = depth,
                mouthBandMask = mouthBand,
            )
        }
    }
}

/**
 * Unified terminal landing coordinator for a group order (ENG-P017-007).
 *
 * Abolishes legacy static lattice pre-claiming and `GroupMoveDestinations.Assignment`.
 * Holds budgeted heterogeneous scarce-bay protection ([mixedBays]) and the lazily-built
 * immutable one-shot [PocketTopology] cache (Cul-de-sac Stage A).
 */
internal class SharedLandingSites(
    private val group: GroupMoveRequest,
    private val spacing: Double,
    var mixedBays: MixedLandingBays? = null,
    private var cachedTopologies: List<PocketTopology> = emptyList(),
) {
    private var searchCredit = 0

    fun fork(rollback: PathSearchRollback) = SharedLandingSites(
        group, spacing, mixedBays?.fork(rollback), cachedTopologies,
    ).also {
        it.searchCredit = searchCredit
    }

    /** Returns the lazily-computed immutable one-shot local BFS topology for [terrain] in O(1) steady state. */
    fun pocketTopology(terrain: PlanningTerrain): PocketTopology {
        val current = cachedTopologies
        for (i in 0 until current.size) {
            val entry = current[i]
            if (entry.terrain === terrain) return entry
        }
        val computed = PocketTopology.analyze(terrain, group.click, group.members.size)
        cachedTopologies = current + computed
        return computed
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

        /**
         * O(1) Anonymous Slot Swap (Cul-de-sac Stage A, ENG-P017-007):
         *
         * When a front/shallow member [shallowUnit] blocks a trailing same-group, same-hull peer [deeperPeer]
         * that holds a strictly deeper claimed destination (`deeperClaim`), swap their target claims and routes
         * atomically in O(1) so the front vehicle drives inward to fill the deeper bay and the trailing vehicle
         * takes over the outer bay — eliminating O(N^2) pairwise flood-fill detour searches.
         */
        fun tryAnonymousSlotSwap(
            host: UnitMovementHost,
            shallowUnit: MutableUnit,
            deeperPeer: MutableUnit,
            topology: PocketTopology? = null,
        ): Boolean {
            if (shallowUnit === deeperPeer || !shallowUnit.alive || !deeperPeer.alive) return false
            if (shallowUnit.ownerPlayerId != deeperPeer.ownerPlayerId) return false
            if (shallowUnit.definition.movementCollision != deeperPeer.definition.movementCollision) return false
            if (UnitLocomotion.movement(shallowUnit).type != UnitLocomotion.movement(deeperPeer).type) return false
            val mA = shallowUnit.landingMember ?: return false
            val mB = deeperPeer.landingMember ?: return false
            if (mA.group !== mB.group || mA.parked || mB.parked) return false
            val deepClaim = mB.claimed ?: return false
            val shallowClaim = mA.claimed
            if (shallowClaim == deepClaim) return false

            // Confirm deeperPeer's claim is strictly deeper (topologically or closer to click) than shallowUnit's
            val goal = mA.group.click
            val deepIsDeeper = if (shallowClaim != null) {
                if (topology != null && topology.hasPocket) {
                    topology.topologicalDepthAt(deepClaim) > topology.topologicalDepthAt(shallowClaim)
                } else {
                    ScreenPlane.distanceSquared(deepClaim, goal) < ScreenPlane.distanceSquared(shallowClaim, goal) - 1.0
                }
            } else {
                // shallowUnit has no explicit claim yet (driving to click/overflow) while deeperPeer holds a deeper bay
                true
            }
            if (!deepIsDeeper) return false

            // Only swap when shallowUnit is physically closer to the deep claim than deeperPeer is (i.e. blocking ahead)
            val distA = ScreenPlane.distanceSquared(shallowUnit.position, deepClaim)
            val distB = ScreenPlane.distanceSquared(deeperPeer.position, deepClaim)
            if (distA >= distB) return false

            val nextTargetForA = deepClaim
            val nextTargetForB = shallowClaim ?: goal
            val nextMemberA = mA.copy(claimed = deepClaim, claimedHeading = mB.claimedHeading, lowProgressTicks = 0L)
            val nextMemberB = mB.copy(claimed = shallowClaim, claimedHeading = mA.claimedHeading, lowProgressTicks = 0L)

            val contractA = shallowUnit.motionContract?.withAnchor(nextTargetForA) ?: MotionContract.sharedCluster(nextTargetForA)
            val contractB = deeperPeer.motionContract?.withAnchor(nextTargetForB) ?: MotionContract.sharedCluster(nextTargetForB)

            shallowUnit.updateNavigation {
                it.withLandingMember(nextMemberA)
                    .bindCommandTarget(
                        target = nextTargetForA,
                        contract = contractA,
                        followsManeuverRoute = true,
                        landingMember = nextMemberA,
                    )
                    .withPathPoints(listOf(nextTargetForA))
            }
            deeperPeer.updateNavigation {
                it.withLandingMember(nextMemberB)
                    .bindCommandTarget(
                        target = nextTargetForB,
                        contract = contractB,
                        followsManeuverRoute = shallowClaim != null,
                        landingMember = nextMemberB,
                    )
                    .withPathPoints(listOf(nextTargetForB))
            }
            host.landingOccupancy.update(shallowUnit)
            host.landingOccupancy.update(deeperPeer)
            return true
        }
    }
}

/**
 * Continuous centripetal convergence, tangential flank sliding, bottleneck-throat protection,
 * and Cul-de-sac Stage A saturation overflow settling engine (ENG-P017-007).
 *
 * Core invariants:
 * - Zero mid-route interference: outside the bounded terminal neighborhood and not contagiously touching
 *   an already-settled peer of the same group, returns en-route immediately without mutating targets.
 * - Geodesic & clearance-aware throat protection: prevents corking 1-tile, narrow 2-tile (where two hulls
 *   cannot pass abreast), and diagonal 45-deg bottlenecks while upstream teammates (measured by flow-field
 *   geodesic distance) are still advancing, AND protects cul-de-sac mouths while interior pockets are filling.
 * - O(1) anonymous slot swap: if a shallow unit blocks a same-shape peer holding a deeper scarce-bay claim,
 *   atomically swaps their claims instead of spawning pairwise detour searches.
 * - Cross-corner saturation overflow cascade: once a pocket fills and overflow wraps around corner walls,
 *   exterior peers touching settled same-group teammates fan symmetrically along the flow-field transverse
 *   wings and cascade-settle cleanly at legal poses without deadlocking behind walls.
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
    private const val OVERFLOW_STALL_TICKS = 20L
    private const val ZERO_SEED_NUCLEATION_TICKS = 8L
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
        convergence: CohortCoordinator.ConvergenceAdvice = CohortCoordinator.ConvergenceAdvice.NONE,
        precomputedHasTerrainLos: Boolean? = null,
    ): Decision? {
        val member = unit.landingMember ?: return null
        if (member.parked) return null
        val hardR = max(1.0, unit.definition.movementCollision.boundingRadius)
        val distToGoal = hypot(goal.x - unit.position.x, goal.y - unit.position.y)
        val geoDistToGoal = host.navigationGuidance.sampleGeodesicDistance(host, unit, terrain, unit.position, goal)
        val pool = host.landingSitesFor(member.group, map)
        val topology = pool?.pocketTopology(terrain)

        // Explicit scarce-bay claim from MixedLandingBays: check O(1) anonymous swap against any trailing
        // deeper-claimed peer of identical hull before letting exact bay arrival handle it cleanly.
        if (member.claimed != null) {
            val layer = UnitContact.layer(unit)
            if (layer != null) {
                val spatialLayer = SpatialLayer.fromContact(layer)
                val swapQueryR = hardR * 3.5 + 16.0
                val nearPeers = host.spatialIndex.queryBounds(
                    spatialLayer,
                    unit.position.x - swapQueryR, unit.position.x + swapQueryR,
                    unit.position.y - swapQueryR, unit.position.y + swapQueryR,
                    useBufferRadius = false,
                )
                for (i in 0 until nearPeers.size) {
                    val peer = nearPeers[i]
                    if (peer !== unit && SharedLandingSites.tryAnonymousSlotSwap(host, unit, peer, topology)) {
                        val swapped = unit.landingMember ?: member
                        return Decision(flankAim = null, settleNow = false, inClusterZone = true, updatedMember = swapped)
                    }
                }
            }
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
        val boundR = max(terrainR, UnitLocomotion.boundaryRadius(unit))
        val mapW = map.widthTiles.toDouble() * map.tileWidth
        val mapH = map.heightTiles.toDouble() * map.tileHeight
        val maxGroupR = max(hardR, member.group.maxRadiusByMovement[UnitLocomotion.movement(unit).type] ?: hardR)
        val baseZoneR = min(340.0, max(58.0, sqrt(member.group.members.size.toDouble()) * max(max(hardR * 2.4, maxGroupR * 2.2), 20.0) + 40.0))
        val landingZoneR = if (member.group.dynamicCluster) max(baseZoneR, min(360.0, max(180.0, hardR * 22.0))) else baseZoneR
        val hullGrid = HullTerrainGrid.authorized(terrain)

        val hasTerrainLosToGoal = precomputedHasTerrainLos
            ?: hasPassableTerrainLos(terrain, hullGrid, terrainShape, unit.position, goal, unit.heading, terrainR)

        // Check whether unit physically touches an already-settled peer of the SAME group (with clear terrain LOS to that peer).
        // This enables both large open-field perimeter contagion AND Cul-de-sac Stage A cross-corner overflow settling!
        fun touchesSettledPeerWithLos(): Boolean {
            val edgeQueryR = hardR + max(36.0, hardR * 1.25)
            val edgePeers = host.spatialIndex.queryBounds(
                spatialLayer,
                unit.position.x - edgeQueryR, unit.position.x + edgeQueryR,
                unit.position.y - edgeQueryR, unit.position.y + edgeQueryR,
                useBufferRadius = false,
            )
            for (i in 0 until edgePeers.size) {
                val other = edgePeers[i]
                if (other === unit || !other.alive) continue
                if (other.landingMember?.group !== member.group || other.landingMember?.parked != true) continue
                val otherR = max(1.0, other.definition.movementCollision.boundingRadius)
                val reach = hardR + otherR + max(15.0, hardR * 1.20)
                if (ScreenPlane.distanceSquared(unit.position, other.position) <= reach * reach &&
                    (terrainShape == null || hasPassableTerrainLos(terrain, hullGrid, terrainShape, unit.position, other.position, unit.heading, terrainR))
                ) {
                    return true
                }
            }
            return false
        }

        val overflowCrossCorner = if (!hasTerrainLosToGoal) {
            val withinOverflowHorizon = geoDistToGoal <= landingZoneR * 1.35 ||
                (topology != null && topology.hasPocket && topology.isReachable(unit.position))
            withinOverflowHorizon && touchesSettledPeerWithLos()
        } else false

        if (!hasTerrainLosToGoal && !overflowCrossCorner) {
            val clean = if (member.flankSign != 0 || member.lowProgressTicks != 0L || member.bestDistToGoal.isFinite()) {
                member.copy(flankSign = 0, lowProgressTicks = 0L, bestDistToGoal = Double.POSITIVE_INFINITY)
            } else member
            return Decision(flankAim = null, settleNow = false, inClusterZone = false, updatedMember = clean)
        }

        if (hasTerrainLosToGoal && distToGoal > landingZoneR) {
            if (!touchesSettledPeerWithLos()) {
                val clean = if (member.flankSign != 0 || member.lowProgressTicks != 0L || member.bestDistToGoal.isFinite()) {
                    member.copy(flankSign = 0, lowProgressTicks = 0L, bestDistToGoal = Double.POSITIVE_INFINITY)
                } else member
                return Decision(flankAim = null, settleNow = false, inClusterZone = false, updatedMember = clean)
            }
        }

        val poseLegalNow = canSettleAtPose(host, unit, mapW, mapH, boundR, terrainShape, hullGrid, spatialLayer)
        val throatBlocked = isPluggingBottleneckThroat(
            host, unit, map, terrain, member.group, goal, hardR, maxGroupR, geoDistToGoal, spatialLayer,
        )
        val pocketMouthBlocked = topology != null && topology.hasPocket &&
            isPluggingFillingPocketMouth(host, unit, topology, member.group, hardR, spatialLayer)
        val isFactoryFan = member.group.noParkingCorridor != null
        val inNoParkingCorridor = isCircle && member.group.noParkingCorridor?.contains(unit.position, hardR) == true
        // For no-rally factory fan units still clear of their own outer fan slot, do not hijack steering
        // while they are actively moving toward their assigned fan slot; only engage settling once inside
        // their own slot neighborhood or genuinely slowed/stalled outside the factory doorway.
        if (isFactoryFan && isCircle && !inNoParkingCorridor && distToGoal > max(28.0, hardR * 4.5) &&
            abs(unit.speed) > max(0.25, 0.35 * maxSpeed)
        ) {
            return Decision(flankAim = null, settleNow = false, inClusterZone = false, updatedMember = member)
        }
        val inThroat = throatBlocked || pocketMouthBlocked || inNoParkingCorridor

        // Advance direction: use direct goal vector when straight LOS exists; use geodesic flow direction around corners!
        val flowDir = if (!hasTerrainLosToGoal) host.navigationGuidance.sampleFlowDirection(host, unit, terrain, goal) else null
        val gdx = when {
            !hasTerrainLosToGoal && flowDir != null -> flowDir.first
            distToGoal > 1e-5 -> (goal.x - unit.position.x) / distToGoal
            else -> desiredDirX
        }
        val gdy = when {
            !hasTerrainLosToGoal && flowDir != null -> flowDir.second
            distToGoal > 1e-5 -> (goal.y - unit.position.y) / distToGoal
            else -> desiredDirY
        }

        // 1. Core seed direct arrival: lead unit(s) reaching the core center settle accurately on goal.
        val coreCaptureR = max(1.5, host.movementContract.targetRadius)
        if (hasTerrainLosToGoal && distToGoal <= coreCaptureR && poseLegalNow) {
            if (!inThroat) {
                return Decision(flankAim = null, settleNow = true, inClusterZone = true, updatedMember = member)
            }
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

        var hasRealSettledCore = false
        var connectedToSettledCore = hasTerrainLosToGoal && isCircle &&
            member.group.noParkingCorridor == null && distToGoal <= max(6.5, hardR * 1.15)
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
            val reach = hardR + otherR + max(15.0, hardR * 1.20)
            if (dist > reach) continue
            if (terrainShape != null && !hasPassableTerrainLos(terrain, hullGrid, terrainShape, unit.position, other.position, unit.heading, terrainR)) {
                continue
            }
            val countsAsCore = if (isFactoryFan) isSettledPeer else (isSettledPeer || (isStationary && otherDistToGoal < distToGoal - 0.5))
            if (countsAsCore) {
                hasRealSettledCore = true
                connectedToSettledCore = true
                if (hasTerrainLosToGoal && otherDistToGoal <= otherR + 6.0 && dist <= hardR + otherR + max(8.0, hardR * 0.75)) {
                    touchingCoreSeed = true
                }
            }
            val countsAsBlocker = if (isFactoryFan) isSettledPeer else isStationary
            if (countsAsBlocker && dist > 1e-5) {
                val fwd = (ox * gdx + oy * gdy) / dist
                val lat = abs(ox * gdy - oy * gdx)
                if (fwd >= 0.25 && lat <= hardR + otherR + 4.0) {
                    frontBlockedByStationary = true
                } else if (overflowCrossCorner && isSettledPeer) {
                    // Around a pocket wall corner, check blocking along Euclidean mouth/goal direction too
                    val ex = (goal.x - unit.position.x) / max(1e-5, distToGoal)
                    val ey = (goal.y - unit.position.y) / max(1e-5, distToGoal)
                    val eFwd = (ox * ex + oy * ey) / dist
                    if (eFwd >= 0.25) frontBlockedByStationary = true
                }
            }
        }

        val inClusterZone = connectedToSettledCore || anySettledNearby || (hasTerrainLosToGoal && distToGoal <= max(28.0, hardR * 2.8))
        val progressMetric = if (hasTerrainLosToGoal) distToGoal else geoDistToGoal

        // ENG-P017-009: Zero-seed convergence nucleation inside the basin.
        // Never reset `lowProgressTicks` when nudged past 6.5px by moving teammates, and never launch
        // high-speed tangential `probeFlank` around an empty core (settledCount == 0).
        val inZeroSeedBasin = !hasRealSettledCore && !isFactoryFan && isCircle && hasTerrainLosToGoal &&
            convergence.inConvergenceBasin && !convergence.hasSettledSeed
        if (inZeroSeedBasin && !frontBlockedByStationary) {
            val holdR = max(hardR * 4.5, 52.0)
            val coreNucleationR = max(hardR * 2.4, 26.0)
            if (distToGoal <= holdR) {
                val progressed = updateRadialProgressTicks(member, progressMetric, delta).copy(flankSign = 0)
                val lowSpd = abs(unit.speed) <= max(0.22, 0.35 * maxSpeed)
                val leaderArchLocked = convergence.isNucleationLeader && convergence.inwardSqueezed &&
                    progressed.lowProgressTicks >= ZERO_SEED_NUCLEATION_TICKS
                val canNucleateHere = poseLegalNow && !inThroat && convergence.inwardSqueezed && (
                    leaderArchLocked || (distToGoal <= coreNucleationR && (
                        (convergence.isNucleationLeader && (lowSpd && progressed.lowProgressTicks >= 4L || progressed.lowProgressTicks >= ZERO_SEED_NUCLEATION_TICKS)) ||
                            progressed.lowProgressTicks >= ZERO_SEED_NUCLEATION_TICKS ||
                            (distToGoal <= max(6.5, hardR * 1.15) && progressed.lowProgressTicks >= 4L && lowSpd)
                        ))
                    )
                return Decision(
                    flankAim = null,
                    settleNow = canNucleateHere,
                    inClusterZone = inClusterZone || distToGoal <= max(hardR * 3.5, 38.0),
                    updatedMember = progressed,
                )
            }
        }

        // Not blocked by any stationary core yet: keep driving straight toward goal!
        if (!connectedToSettledCore && !frontBlockedByStationary) {
            val nextBest = min(member.bestDistToGoal, progressMetric)
            val updated = if (member.flankSign != 0 || member.lowProgressTicks != 0L || member.bestDistToGoal != nextBest) {
                member.copy(flankSign = 0, lowProgressTicks = 0L, bestDistToGoal = nextBest)
            } else member
            return Decision(flankAim = null, settleNow = false, inClusterZone = inClusterZone, updatedMember = updated)
        }

        // Update monotone radial/geodesic progress tracker: orbiting at constant radius does NOT reset lowProgressTicks!
        val progressed = updateRadialProgressTicks(member, progressMetric, delta)

        // 3. Rectangular hulls (2–7 naval fleets or mixed groups): settle cleanly adjacent to settled core at legal pose.
        if (!isCircle) {
            val updated = progressed.copy(flankSign = 0)
            val lowSpdRect = abs(unit.speed) <= 0.30 * maxSpeed
            val throatFallback = !inNoParkingCorridor && inThroat && lowSpdRect && updated.lowProgressTicks >= THROAT_DEADLOCK_SETTLE_TICKS
            val blockedOrStalled = (frontBlockedByStationary && (lowSpdRect || updated.lowProgressTicks >= SETTLE_STALL_TICKS)) ||
                (lowSpdRect && updated.lowProgressTicks >= SETTLE_STALL_TICKS)
            val canSettle = !inNoParkingCorridor && connectedToSettledCore && poseLegalNow && (!inThroat || throatFallback) && blockedOrStalled
            return Decision(flankAim = null, settleNow = canSettle, inClusterZone = true, updatedMember = updated)
        }

        // 4. Continuous tangential flank sliding check (for circular units):
        // Never trigger empty-core tangential flanking when no settled/stationary core body exists yet!
        var chosenSign = 0
        var chosenAim: Position? = null
        val inExteriorOverflow = overflowCrossCorner ||
            (topology != null && topology.hasPocket && !topology.isInteriorAt(unit.position) && connectedToSettledCore)
        val fanOutMode = isFactoryFan && inNoParkingCorridor
        if ((hasRealSettledCore || inThroat || fanOutMode) && (!touchingCoreSeed || inThroat || fanOutMode)) {
            val probeStep = max(hardR * 1.4, 9.5)
            val flankDistAllowance = when {
                inNoParkingCorridor -> Double.POSITIVE_INFINITY
                isFactoryFan -> max(20.0, hardR * 3.2)
                inThroat -> max(16.0, hardR * 2.8)
                inExteriorOverflow -> max(14.0, hardR * 2.0)
                else -> -1.0
            }
            val leftFlank = when {
                inNoParkingCorridor -> probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = -1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = 0.50,
                ) ?: probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = -1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = 0.22,
                ) ?: probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = -1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = 0.0,
                )
                isFactoryFan -> probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = -1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = 0.28,
                ) ?: probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = -1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = 0.05,
                )
                inThroat -> probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = -1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = -0.22,
                ) ?: probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = -1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = 0.0,
                )
                inExteriorOverflow -> probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = -1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = 0.08,
                )
                else -> probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = -1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = 0.352,
                ) ?: if (distToGoal > hardR * 3.5) {
                    probeFlank(
                        host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                        spatialLayer, goal, distToGoal, gdx, gdy, sign = -1, probeStep = probeStep,
                        distDeltaCap = hardR * 0.45, inwardWeight = 0.12,
                    )
                } else null
            }
            val rightFlank = when {
                inNoParkingCorridor -> probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = +1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = 0.50,
                ) ?: probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = +1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = 0.22,
                ) ?: probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = +1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = 0.0,
                )
                isFactoryFan -> probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = +1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = 0.28,
                ) ?: probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = +1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = 0.05,
                )
                inThroat -> probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = +1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = -0.22,
                ) ?: probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = +1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = 0.0,
                )
                inExteriorOverflow -> probeFlank(
                    host, unit, terrain, hullGrid, mapW, mapH, boundR, terrainShape, terrainR, hardR,
                    spatialLayer, goal, distToGoal, gdx, gdy, sign = +1, probeStep = probeStep,
                    distDeltaCap = flankDistAllowance, inwardWeight = 0.08,
                )
                else -> probeFlank(
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
                        isFactoryFan -> if ((unit.updateKey.hashCode() and 1) == 0) +1 else -1
                        inExteriorOverflow -> if ((unit.unitId.hashCode() and 1) == 0) +1 else -1
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

        // If an open flank exists AND unit has not stalled radially/geodesically for the settle window, slide into the wing!
        val flankWindow = if (inExteriorOverflow) OVERFLOW_STALL_TICKS else SETTLE_STALL_TICKS
        if (chosenAim != null && (inThroat || updated.lowProgressTicks < flankWindow)) {
            return Decision(
                flankAim = chosenAim,
                settleNow = false,
                inClusterZone = true,
                updatedMember = updated,
            )
        }

        // 5. Anti-throat-plugging contagious settling & Cul-de-sac Stage A Saturation Overflow Cascade:
        val lowSpeed = abs(unit.speed) <= max(0.22, 0.35 * maxSpeed)
        val throatSaturatedExit = !inNoParkingCorridor && inThroat && chosenAim == null &&
            (lowSpeed || frontBlockedByStationary) && updated.lowProgressTicks >= THROAT_DEADLOCK_SETTLE_TICKS
        val overflowCascadeReady = overflowCrossCorner && !inThroat && (
            (frontBlockedByStationary && (lowSpeed || updated.lowProgressTicks >= 8L)) ||
                updated.lowProgressTicks >= OVERFLOW_STALL_TICKS
            )
        val openSettleReady = !inThroat && (
            (frontBlockedByStationary && chosenAim == null && (lowSpeed || updated.lowProgressTicks >= 6L)) ||
                ((lowSpeed || frontBlockedByStationary) && updated.lowProgressTicks >= SETTLE_STALL_TICKS) ||
                updated.lowProgressTicks >= HIGH_SPEED_STALL_TICKS
            )
        val readyToSettle = !inNoParkingCorridor && connectedToSettledCore && poseLegalNow &&
            (openSettleReady || overflowCascadeReady || throatSaturatedExit)
        return Decision(
            flankAim = if (readyToSettle) null else chosenAim,
            settleNow = readyToSettle,
            inClusterZone = true,
            updatedMember = updated,
        )
    }

    private fun hasPassableTerrainLos(
        terrain: PlanningTerrain,
        hullGrid: HullTerrainGrid,
        terrainShape: MovementCollisionShape?,
        from: Position,
        to: Position,
        heading: Double,
        terrainR: Double,
    ): Boolean = PlanningSegmentClearance.allowsHull(
        terrain = terrain,
        hullGrid = hullGrid,
        terrainHull = terrainShape,
        from = from,
        to = to,
        heading = heading,
        boundaryRadius = terrainR,
        terrainRadius = terrainR,
    )

    private fun updateRadialProgressTicks(
        member: LandingMember,
        distMetric: Double,
        delta: Double,
    ): LandingMember {
        if (!member.bestDistToGoal.isFinite() || distMetric < member.bestDistToGoal - RADIAL_PROGRESS_EPS) {
            return member.copy(lowProgressTicks = 0L, bestDistToGoal = distMetric)
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
            if (!PlanningSegmentClearance.allowsClearance(terrain, unit.position, probe, terrainR)) return null
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

    /**
     * Protects a cul-de-sac pocket mouth from premature corking while unparked teammates are still
     * actively filling the interior pocket below its estimated capacity (Stage A Rule 1).
     */
    private fun isPluggingFillingPocketMouth(
        host: UnitMovementHost,
        unit: MutableUnit,
        topology: PocketTopology,
        group: GroupMoveRequest,
        hardR: Double,
        spatialLayer: SpatialLayer,
    ): Boolean {
        if (!topology.isInMouthBand(unit.position)) return false
        val capacity = topology.estimatedInteriorCapacity(hardR)
        val searchR = max(topology.terrain.tileWidth, topology.terrain.tileHeight) * 12.0
        val nearby = host.spatialIndex.queryBounds(
            spatialLayer,
            topology.mouthCenter.x - searchR, topology.mouthCenter.x + searchR,
            topology.mouthCenter.y - searchR, topology.mouthCenter.y + searchR,
            useBufferRadius = false,
        )
        var interiorParked = 0
        var interiorMoving = 0
        var exteriorWaiting = false
        val ownSteps = topology.bfsStepsFromClick(unit.position)
        for (i in 0 until nearby.size) {
            val other = nearby[i]
            if (other === unit || !other.alive) continue
            val ol = other.landingMember ?: continue
            if (ol.group !== group) continue
            if (topology.isInteriorAt(other.position)) {
                if (ol.parked) interiorParked++
                else if (other.moveTarget != null && abs(other.speed) > 0.1) interiorMoving++
            } else if (!ol.parked && other.moveTarget != null) {
                val otherSteps = topology.bfsStepsFromClick(other.position)
                if (otherSteps > ownSteps) exteriorWaiting = true
            }
        }
        return interiorMoving > 0 && interiorParked < capacity && exteriorWaiting
    }

    /**
     * Width- and geodesic-aware bottleneck throat detector (ENG-P017-007):
     * Uses [AuthorizedNavigationTerrain.clearanceRings] + wall-to-wall span checks to detect
     * 1-tile pinches, narrow 2-tile gates where two hulls cannot pass abreast, and 45-deg
     * diagonal chokepoints, and verifies upstream teammates using flow-field geodesic distance.
     */
    private fun isPluggingBottleneckThroat(
        host: UnitMovementHost,
        unit: MutableUnit,
        map: MapDefinition,
        terrain: PlanningTerrain,
        group: GroupMoveRequest,
        goal: Position,
        hardR: Double,
        maxGroupR: Double,
        ownGeoDist: Double,
        spatialLayer: SpatialLayer,
    ): Boolean {
        val tw = terrain.tileWidth.toDouble()
        val th = terrain.tileHeight.toDouble()
        val minTile = min(tw, th)
        val twoHullWidth = max(4.0 * hardR, 2.0 * (hardR + maxGroupR))

        fun blocked(c: Int, r: Int): Boolean =
            c !in 0 until terrain.widthTiles || r !in 0 until terrain.heightTiles || terrain.isBlocked(c, r)

        fun isPinchCell(c: Int, r: Int): Boolean {
            if (c !in 0 until terrain.widthTiles || r !in 0 until terrain.heightTiles || terrain.isBlocked(c, r)) return false
            val rings = terrain.clearanceRings(c, r)
            // O(1) wide-open fast-reject via AuthorizedNavigationTerrain clearance rings
            if (rings >= 2 && (rings * 2.0 - 1.0) * minTile >= twoHullWidth) return false
            // 1. Axis-aligned horizontal pinch (walls left & right within < twoHullWidth)
            var left = 0
            var leftWall = false
            while (left < 3) {
                if (blocked(c - left - 1, r)) { leftWall = true; break }
                left++
            }
            var right = 0
            var rightWall = false
            while (right < 3) {
                if (blocked(c + right + 1, r)) { rightWall = true; break }
                right++
            }
            if (leftWall && rightWall && (left + 1 + right) * tw < twoHullWidth) return true

            // 2. Axis-aligned vertical pinch (walls up & down within < twoHullWidth)
            var up = 0
            var upWall = false
            while (up < 3) {
                if (blocked(c, r - up - 1)) { upWall = true; break }
                up++
            }
            var down = 0
            var downWall = false
            while (down < 3) {
                if (blocked(c, r + down + 1)) { downWall = true; break }
                down++
            }
            if (upWall && downWall && (up + 1 + down) * th < twoHullWidth) return true

            // 3. Diagonal 45-deg chokepoint (opposing diagonal walls within clearanceRings <= 1)
            if (rings <= 1 && ((blocked(c - 1, r - 1) && blocked(c + 1, r + 1)) ||
                    (blocked(c - 1, r + 1) && blocked(c + 1, r - 1)))
            ) {
                return true
            }
            return false
        }

        fun cellCorksPinch(px: Double, py: Double): Boolean {
            val c = floor(px / tw).toInt().coerceIn(0, terrain.widthTiles - 1)
            val r = floor(py / th).toInt().coerceIn(0, terrain.heightTiles - 1)
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
            val otherGeo = host.navigationGuidance.sampleGeodesicDistance(host, other, terrain, other.position, goal)
            if (otherGeo > ownGeoDist + hardR * 0.5) return true
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
