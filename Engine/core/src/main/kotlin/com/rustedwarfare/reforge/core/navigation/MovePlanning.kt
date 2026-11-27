package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.ScreenPlane
import com.rustedwarfare.reforge.core.sim.MapBounds
import com.rustedwarfare.reforge.core.sim.MutableUnit
import com.rustedwarfare.reforge.core.sim.MoveAction

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import java.util.Collections

internal data class MovePlanningPose(val position: Position, val heading: Double, val layer: ContactLayer?,
                                     val shape: MovementCollisionShape)

internal data class MovePlanningMember(
    val action: MoveAction,
    val body: MutableUnit?,
    val pose: MovePlanningPose,
    val start: Position,
    val radius: Double,
    val terrain: PlanningTerrain,
    val hull: HullRoute?,
    val hasBirthDelay: Boolean,
    val inputPreparationNanos: Long,
)

internal data class PlannedRoute(val position: Position, val heading: Double, val path: List<Position>)

internal data class MovePlanningOccupant(
    val body: MutableUnit,
    val pose: MovePlanningPose,
    val idle: Boolean,
    val obstructsRoute: Boolean = false,
    val fixed: Boolean = false,
)

internal enum class ManeuverRepair(val reason: PathSearchReason) {
    SHORE_REJOIN(PathSearchReason.ROUTE_REPAIR),
    CONTACT(PathSearchReason.ROUTE_REPAIR),
    CONNECTION(PathSearchReason.ROUTE_REPAIR),
    KNOWLEDGE(PathSearchReason.KNOWLEDGE_REPLAN),
}

internal data class MovePlanningInput(
    val map: MapDefinition,
    val members: List<MovePlanningMember>,
    val occupants: List<MovePlanningOccupant>,
    val collectDiagnostics: Boolean,
    val repair: ManeuverRepair? = null,
    val segmentPeers: List<MovePlanningOccupant> = emptyList(),
) {
    fun matches(current: MovePlanningInput): Boolean =
        matchesMembers(current) && sameOccupants(occupants, current.occupants)

    fun matchesMembers(current: MovePlanningInput): Boolean =
        matchesMembers(current, replacingCommand = false)

    fun matchesRepeatedCommand(current: MovePlanningInput): Boolean =
        collectDiagnostics == current.collectDiagnostics && repair == null && current.repair == null &&
            matchesMembers(current, replacingCommand = true) && sameOccupants(occupants, current.occupants)

    private fun matchesMembers(current: MovePlanningInput, replacingCommand: Boolean): Boolean =
        map === current.map && members.size == current.members.size &&
            members.indices.all { index ->
                val saved = members[index]
                val next = current.members[index]
                (if (replacingCommand) saved.action.sameMoveRequest(next.action) &&
                    saved.body?.landingMember?.group === next.body?.landingMember?.group else saved.action == next.action) &&
                    saved.pose == next.pose && saved.start == next.start &&
                    saved.radius == next.radius && saved.terrain === next.terrain && saved.hull == next.hull &&
                    saved.hasBirthDelay == next.hasBirthDelay
            }

    fun matchesRouteBlockers(current: MovePlanningInput): Boolean =
        sameOccupants(occupants.filter { it.obstructsRoute }, current.occupants.filter { it.obstructsRoute })

    private fun sameOccupants(previous: List<MovePlanningOccupant>, current: List<MovePlanningOccupant>): Boolean =
        previous.size == current.size && previous.indices.all { index ->
            val saved = previous[index]
            val next = current[index]
            saved.body.unitId == next.body.unitId && saved.pose == next.pose &&
                saved.idle == next.idle && saved.obstructsRoute == next.obstructsRoute && saved.fixed == next.fixed
        }
}

private fun MoveAction.sameMoveRequest(other: MoveAction): Boolean =
    unitId == other.unitId && actorPlayerId == other.actorPlayerId && world === other.world && target == other.target

internal data class PlannedMove(val route: PlannedRoute?, val counters: PathSearchCounters?, val searchNanos: Long,
                                val nearest: Boolean = false)

internal enum class TacticalUnreachablePolicy {
    CANCEL_ROUTE,
    SILENT_IGNORE,
    STALL_MOVE_GUARDED,
}

internal data class TacticalRouteRequest(
    val unitId: String,
    val actionId: String,
    val atStep: Long?,
    val start: Position,
    val target: Position,
    val radius: Double,
    val targetRange: Double,
    val terrain: PlanningTerrain,
    val hull: HullRoute?,
    val occupancy: RouteOccupancy? = null,
    val occupantIds: List<String> = emptyList(),
    val reason: PathSearchReason,
    val isRepair: Boolean = false,
    val collectDiagnostics: Boolean = false,
    val inputPreparationNanos: Long = 0L,
    val preservedPrefix: List<Position> = emptyList(),
    val preservedSuffix: List<Position> = emptyList(),
    val fallbackFullTarget: Position? = null,
    val isCircleShape: Boolean = true,
    val unreachablePolicy: TacticalUnreachablePolicy = TacticalUnreachablePolicy.CANCEL_ROUTE,
)

internal sealed interface TacticalRouteResolution {
    data class Ready(
        val points: List<Position>,
        val counters: PathSearchCounters?,
        val preparationNanos: Long,
        val searchNanos: Long,
        val preservedPrefixCount: Int,
    ) : TacticalRouteResolution

    data class Unreachable(
        val counters: PathSearchCounters?,
        val preparationNanos: Long,
        val searchNanos: Long,
    ) : TacticalRouteResolution

    data object Pending : TacticalRouteResolution
}

/** The ordinary order queue and all tactical route searches belong to the same budgeted candidate transaction. */
internal class MovePlanning {
    private class Order(
        val action: MoveAction,
        val repair: ManeuverRepair? = null,
        var requestKey: Any = Any(),
        var fastLaneEligible: Boolean = false,
    )

    private class TacticalJob(
        var request: TacticalRouteRequest,
        var search: ExactPathSearch,
        var cache: PathClearanceCache,
        var searchNanos: Long = 0L,
    ) {
        fun fork(rollback: PathSearchRollback): TacticalJob = TacticalJob(
            request = request,
            search = search.fork(rollback),
            cache = cache.fork(),
            searchNanos = searchNanos,
        )

        fun advance(budget: PathWorkBudget): Int {
            if (search.finished || budget.exhausted) return 0
            val usedBefore = budget.used
            val t0 = if (request.collectDiagnostics) System.nanoTime() else 0L
            search.advance(budget, cache)
            if (request.collectDiagnostics) {
                searchNanos += (System.nanoTime() - t0).coerceAtLeast(0L)
            }
            return budget.used - usedBefore
        }
    }

    companion object {
        internal const val FAST_LANE_MAX_SQUAD_SIZE: Int = 5
        internal const val P2_REPAIR_MAX_SHARE_NUMERATOR: Int = 3
        internal const val P2_REPAIR_MAX_SHARE_DENOMINATOR: Int = 4
    }

    private val p0FastLane = linkedSetOf<Order>()
    private val p0Ordinary = linkedSetOf<Order>()
    private val p2Repairs = linkedSetOf<Order>()
    private val optionalRejoins = linkedSetOf<Order>()
    private val byMember = HashMap<String, Order>()
    private val workByOrder = HashMap<Order, Work>()
    private val tacticalJobs = LinkedHashMap<String, TacticalJob>()
    private var activeRollback: PathSearchRollback? = null

    private fun queueFor(order: Order): LinkedHashSet<Order> = when {
        order.repair == ManeuverRepair.SHORE_REJOIN -> optionalRejoins
        order.repair != null -> p2Repairs
        isFastLanePlayerOrder(order) -> p0FastLane
        else -> p0Ordinary
    }

    private fun isFastLanePlayerOrder(order: Order): Boolean {
        if (order.repair != null) return false
        return order.fastLaneEligible
    }

    private fun allOrdersSequence(): Sequence<Order> =
        p0FastLane.asSequence() + p0Ordinary.asSequence() + p2Repairs.asSequence() + optionalRejoins.asSequence()

    fun fork(rollback: PathSearchRollback): MovePlanning = MovePlanning().also { pending ->
        pending.activeRollback = rollback
        for (order in allOrdersSequence()) {
            pending.add(order)
            workByOrder[order]?.let { pending.workByOrder[order] = it.fork(rollback) }
        }
        for ((unitId, job) in tacticalJobs) {
            pending.tacticalJobs[unitId] = job.fork(rollback)
        }
    }

    fun enqueue(action: MoveAction, selectionSize: Int = 1) {
        val prior = byMember[action.unitId]
        val reusable = prior?.takeIf { old ->
            old.repair == null && old.action.sameMoveRequest(action)
        }?.let(workByOrder::get)
        cancel(action.unitId)
        val fastLane = selectionSize <= FAST_LANE_MAX_SQUAD_SIZE
        val order = Order(action, fastLaneEligible = fastLane)
        add(order)
        if (reusable != null) workByOrder[order] = reusable
    }

    fun cancel(unitId: String) {
        tacticalJobs.remove(unitId)
        val order = byMember.remove(unitId) ?: return
        workByOrder.remove(order)
        p0FastLane.remove(order)
        p0Ordinary.remove(order)
        p2Repairs.remove(order)
        optionalRejoins.remove(order)
    }

    fun repair(action: MoveAction, reason: ManeuverRepair) {
        val existing = byMember[action.unitId]
        if (existing != null && (existing.repair == null || existing.repair >= reason)) return
        cancel(action.unitId)
        add(Order(action, reason))
    }

    private fun add(order: Order) {
        queueFor(order).add(order)
        byMember[order.action.unitId] = order
    }

    fun pending(unitId: String): Boolean = unitId in byMember || unitId in tacticalJobs
    fun hasPendingTactical(unitId: String): Boolean = unitId in tacticalJobs
    fun repairing(unitId: String): Boolean = byMember[unitId]?.repair != null || tacticalJobs[unitId]?.request?.isRepair == true
    fun holdsManeuverRoute(unitId: String): Boolean = byMember[unitId]?.repair?.let { it != ManeuverRepair.SHORE_REJOIN } == true

    fun hasPendingFastLaneOrders(): Boolean = p0FastLane.isNotEmpty()

    fun hasPendingPlayerOrders(): Boolean =
        p0FastLane.isNotEmpty() || p0Ordinary.isNotEmpty() || tacticalJobs.values.any { !it.request.isRepair && !it.search.finished }

    fun hasPendingRepairs(): Boolean =
        p2Repairs.isNotEmpty() || optionalRejoins.isNotEmpty() || tacticalJobs.values.any { it.request.isRepair && !it.search.finished }

    fun completedRepairTacticalRequests(): List<TacticalRouteRequest> =
        tacticalJobs.values.filter { it.request.isRepair && it.search.finished }.map { it.request }

    fun advance(
        pathPolicy: ClearanceAStarPolicy,
        budget: PathWorkBudget,
        snapshot: (MoveAction, ManeuverRepair?) -> MovePlanningInput,
        publish: (MovePlanningMember, ManeuverRepair?, List<String>, PlannedMove) -> Unit,
        samples: MutableList<PlanningWorkSlice>? = null,
        liveUnitCount: () -> Int = { error("Missing shore snapshot size") },
        executionStep: Long = 0,
        p1FlowWork: ((PathWorkBudget) -> Unit)? = null,
        rotateQueues: Boolean = true,
        p2BudgetAlreadyUsed: Int = 0,
    ): Int {
        // Rotate active heads inside same-tier queues between simulation steps for fair time-slicing.
        // Only rotate multi-order repair queues (P2) between steps so stale/stuck repairs never starve peers;
        // preserve deterministic FIFO arrival order for fresh player orders in p0Ordinary so corridor warming
        // and multi-unit order publication stay reproducible.
        if (rotateQueues) {
            if (p2Repairs.size > 1) {
                val front = p2Repairs.first()
                if (front in workByOrder) {
                    p2Repairs.remove(front)
                    p2Repairs.add(front)
                }
            }
        }
        // Tier 0a: Fast-lane fresh player orders (1..5 unit squads) ALWAYS preempt first with guaranteed budget.
        processQueue(p0FastLane, pathPolicy, budget, snapshot, publish, samples, liveUnitCount, executionStep)
        if (budget.exhausted) return 0

        // Tier 0b: Ordinary fresh player orders (large groups) and suspended P0 tactical searches run ahead of P1/P2.
        processQueue(p0Ordinary, pathPolicy, budget, snapshot, publish, samples, liveUnitCount, executionStep)
        if (budget.exhausted) return 0
        advanceSuspendedTactical(isRepair = false, budget = budget, samples = samples)
        if (budget.exhausted) return 0

        // Tier 1: Shared flow-field build / dirty-chunk refresh slot (hook ready for ENG-P016-008).
        if (p1FlowWork != null && !budget.exhausted) {
            p1FlowWork(budget)
            if (budget.exhausted) return 0
        }

        // Tier 2: Background repairs (fair round-robin within tier, strictly isolated budget cap).
        val stepP2Cap = if (p1FlowWork != null || optionalRejoins.isNotEmpty()) {
            maxOf(8, budget.limit * P2_REPAIR_MAX_SHARE_NUMERATOR / P2_REPAIR_MAX_SHARE_DENOMINATOR)
        } else budget.limit
        val remainingP2Cap = (stepP2Cap - p2BudgetAlreadyUsed).coerceAtLeast(0)
        val p2SubBudget = PathWorkBudget(minOf(budget.remaining, remainingP2Cap))
        val p2UsedBefore = p2SubBudget.used
        processQueue(
            p2Repairs, pathPolicy, p2SubBudget, snapshot, publish, samples, liveUnitCount, executionStep,
        )
        if (!p2SubBudget.exhausted) {
            advanceSuspendedTactical(isRepair = true, budget = p2SubBudget, samples = samples)
        }
        val p2Consumed = p2SubBudget.used - p2UsedBefore
        if (p2Consumed > 0) budget.take(p2Consumed)
        if (budget.exhausted) return p2Consumed

        // Optional lowest-priority shore rejoins.
        processQueue(optionalRejoins, pathPolicy, budget, snapshot, publish, samples, liveUnitCount, executionStep)
        return p2Consumed
    }

    private fun advanceSuspendedTactical(
        isRepair: Boolean,
        budget: PathWorkBudget,
        samples: MutableList<PlanningWorkSlice>?,
    ) {
        if (tacticalJobs.isEmpty() || budget.exhausted) return
        // Fair round-robin: rotate head if multi-entry unfinished jobs exist
        val matchingKeys = tacticalJobs.entries
            .filter { it.value.request.isRepair == isRepair && !it.value.search.finished }
            .map { it.key }
        for (unitId in matchingKeys) {
            if (budget.exhausted) break
            val job = tacticalJobs[unitId] ?: continue
            val consumed = job.advance(budget)
            if (consumed > 0) {
                samples?.add(
                    PlanningWorkSlice(
                        Collections.singletonList(unitId),
                        job.request.reason,
                        consumed,
                        job.search.finished,
                        false,
                    ),
                )
            }
        }
    }

    fun resolveTactical(
        request: TacticalRouteRequest,
        pathPolicy: ClearanceAStarPolicy,
        budget: PathWorkBudget,
        samples: MutableList<PlanningWorkSlice>? = null,
    ): TacticalRouteResolution {
        val existing = tacticalJobs[request.unitId]
        val job = if (existing != null && canReuseTacticalJob(existing.request, request, existing.search.finished)) {
            existing
        } else {
            createTacticalJob(request, pathPolicy).also { tacticalJobs[request.unitId] = it }
        }

        if (!job.search.finished && !budget.exhausted) {
            val consumed = job.advance(budget)
            if (consumed > 0) {
                samples?.add(
                    PlanningWorkSlice(
                        Collections.singletonList(request.unitId),
                        request.reason,
                        consumed,
                        job.search.finished,
                        false,
                    ),
                )
            }
        }

        if (!job.search.finished) {
            return TacticalRouteResolution.Pending
        }

        tacticalJobs.remove(request.unitId)
        val rawPath = job.search.path
            ?: return TacticalRouteResolution.Unreachable(
                counters = job.search.counters,
                preparationNanos = job.request.inputPreparationNanos,
                searchNanos = job.searchNanos,
            )

        val adaptedStart = spliceTacticalStartIfNeeded(job.request, request, rawPath)
        if (adaptedStart == null) {
            // Unit moved significantly across blocked terrain while search was suspended: restart cleanly on next slice
            val restarted = createTacticalJob(request, pathPolicy)
            tacticalJobs[request.unitId] = restarted
            samples?.add(
                PlanningWorkSlice(
                    Collections.singletonList(request.unitId),
                    request.reason,
                    0,
                    completed = false,
                    restarted = true,
                ),
            )
            return TacticalRouteResolution.Pending
        }

        val adaptedEnd = spliceTacticalGoalIfNeeded(job.request, request, adaptedStart)
        if (adaptedEnd == null) {
            val restarted = createTacticalJob(request, pathPolicy)
            tacticalJobs[request.unitId] = restarted
            samples?.add(
                PlanningWorkSlice(
                    Collections.singletonList(request.unitId),
                    request.reason,
                    0,
                    completed = false,
                    restarted = true,
                ),
            )
            return TacticalRouteResolution.Pending
        }

        val withPrefix = if (request.preservedPrefix.isEmpty()) {
            adaptedEnd
        } else {
            val suffix = if (adaptedEnd.firstOrNull() == request.preservedPrefix.last()) adaptedEnd.drop(1) else adaptedEnd
            request.preservedPrefix + suffix
        }
        val finalPoints = if (request.preservedSuffix.isEmpty()) {
            withPrefix
        } else {
            withPrefix + request.preservedSuffix
        }

        return TacticalRouteResolution.Ready(
            points = finalPoints,
            counters = job.search.counters,
            preparationNanos = job.request.inputPreparationNanos,
            searchNanos = job.searchNanos,
            preservedPrefixCount = request.preservedPrefix.size + request.preservedSuffix.size,
        )
    }

    private fun createTacticalJob(
        request: TacticalRouteRequest,
        pathPolicy: ClearanceAStarPolicy,
    ): TacticalJob {
        val counters = if (request.collectDiagnostics) PathSearchCounters() else null
        val cache = PathClearanceCache(reuseApproach = false)
        val rawSearch = if (request.targetRange > 0.0) {
            pathPolicy.beginRange(
                request.terrain,
                request.start,
                request.target,
                request.radius,
                request.targetRange,
                request.hull,
                request.occupancy,
                counters,
            )
        } else {
            pathPolicy.beginExact(
                request.terrain,
                request.start,
                request.target,
                request.radius,
                request.hull,
                request.occupancy,
                counters,
            )
        }
        val boundSearch = activeRollback?.let { rawSearch.fork(it) } ?: rawSearch
        return TacticalJob(request = request, search = boundSearch, cache = cache)
    }

    private fun canReuseTacticalJob(
        saved: TacticalRouteRequest,
        live: TacticalRouteRequest,
        finished: Boolean = false,
    ): Boolean {
        if (saved.actionId != live.actionId ||
            saved.reason != live.reason ||
            saved.isRepair != live.isRepair ||
            saved.radius != live.radius ||
            saved.targetRange != live.targetRange ||
            saved.terrain !== live.terrain ||
            saved.occupantIds != live.occupantIds ||
            saved.preservedPrefix != live.preservedPrefix
        ) return false

        val minTile = minOf(live.terrain.tileWidth, live.terrain.tileHeight).toDouble()
        val startOkay = if (saved.start == live.start || (finished && live.isCircleShape)) {
            saved.hull == live.hull || live.isCircleShape
        } else if (live.isCircleShape) {
            val maxDrift = minTile * 2.0
            ScreenPlane.distanceSquared(saved.start, live.start) <= maxDrift * maxDrift ||
                PlanningSegmentClearance.allows(
                    live.terrain, saved.start, live.start, live.radius,
                )
        } else false
        if (!startOkay) return false

        if (saved.target == live.target) return true
        if (live.targetRange > 0.0) {
            val driftSq = ScreenPlane.distanceSquared(saved.target, live.target)
            return driftSq <= minTile * minTile
        }
        // Moving pursuit target hysteresis while multi-step search is in flight:
        // keep in-flight search as long as target stays within 2 tiles of saved goal cell,
        // or within 35% of remaining straight-line distance while crossing distant obstacles
        // (preventing live-lock starvation in large-group 350x350 pursuits; tail is spliced on completion).
        val sc = MapBounds.cell(saved.target.x, live.terrain.tileWidth, live.terrain.widthTiles)
        val sr = MapBounds.cell(saved.target.y, live.terrain.tileHeight, live.terrain.heightTiles)
        val lc = MapBounds.cell(live.target.x, live.terrain.tileWidth, live.terrain.widthTiles)
        val lr = MapBounds.cell(live.target.y, live.terrain.tileHeight, live.terrain.heightTiles)
        if (maxOf(kotlin.math.abs(sc - lc), kotlin.math.abs(sr - lr)) <= 2) return true
        val minBridgeDistSq = (6.0 * minTile) * (6.0 * minTile)
        val remainingDistSq = ScreenPlane.distanceSquared(live.start, live.target)
        return remainingDistSq > minBridgeDistSq &&
            ScreenPlane.distanceSquared(saved.target, live.target) <= 0.12 * remainingDistSq
    }

    private fun spliceTacticalStartIfNeeded(
        saved: TacticalRouteRequest,
        live: TacticalRouteRequest,
        rawPath: List<Position>,
    ): List<Position>? {
        if (saved.start == live.start || rawPath.isEmpty()) return rawPath
        if (!live.isCircleShape) return null
        fun legClear(from: Position, to: Position): Boolean {
            if (!PlanningSegmentClearance.allows(live.terrain, from, to, live.radius)) {
                return false
            }
            val hull = live.hull
            if (hull != null && hull.arrival(HullTerrainGrid.authorized(live.terrain), from, to, hull.heading) == null) {
                return false
            }
            return live.occupancy?.allows(from, to, 0.0, 0.0) != false
        }
        val first = rawPath.first()
        return when {
            legClear(live.start, first) -> rawPath
            rawPath.size > 1 && legClear(live.start, rawPath[1]) -> rawPath.drop(1)
            else -> {
                val mid = Position(
                    (kotlin.math.floor(live.start.x / live.terrain.tileWidth) + 0.5) * live.terrain.tileWidth,
                    (kotlin.math.floor(live.start.y / live.terrain.tileHeight) + 0.5) * live.terrain.tileHeight,
                )
                val bend = listOf(mid, saved.start).firstOrNull { p ->
                    p != live.start && p != first && legClear(live.start, p) && legClear(p, first)
                } ?: return null
                listOf(bend) + rawPath
            }
        }
    }

    private fun spliceTacticalGoalIfNeeded(
        saved: TacticalRouteRequest,
        live: TacticalRouteRequest,
        path: List<Position>,
    ): List<Position>? {
        if (saved.target == live.target || path.isEmpty()) return path
        if (live.targetRange > 0.0) {
            val end = path.last()
            return if (ScreenPlane.distanceSquared(end, live.target) < live.targetRange * live.targetRange) path else null
        }
        val width = live.terrain.widthTiles.toDouble() * live.terrain.tileWidth
        val height = live.terrain.heightTiles.toDouble() * live.terrain.tileHeight
        if (!MapBounds.contains(width, height, live.target, live.radius) ||
            PlanningCenterTerrain.intersects(live.terrain, live.target, live.radius)
        ) return null
        val anchor = if (path.size >= 2) path[path.size - 2] else live.start
        val tailOk = if (live.hull == null) {
            PlanningSegmentClearance.allows(live.terrain, anchor, live.target, live.radius)
        } else {
            live.hull.arrival(HullTerrainGrid.authorized(live.terrain), anchor, live.target, live.hull.heading) != null
        }
        if (tailOk && live.occupancy?.allows(anchor, live.target, 0.0, 0.0) != false) {
            return if (path.size == 1) listOf(live.target) else path.dropLast(1) + live.target
        }
        // If multi-segment detour around obstacle, keep prefix to reach around obstacle first; updateAttackRoute will refine tail near arrival
        return if (path.size >= 2) path else null
    }

    private fun processQueue(
        queue: LinkedHashSet<Order>,
        pathPolicy: ClearanceAStarPolicy,
        budget: PathWorkBudget,
        snapshot: (MoveAction, ManeuverRepair?) -> MovePlanningInput,
        publish: (MovePlanningMember, ManeuverRepair?, List<String>, PlannedMove) -> Unit,
        samples: MutableList<PlanningWorkSlice>?,
        liveUnitCount: () -> Int,
        executionStep: Long,
    ) {
        val queued = queue.iterator()
        while (!budget.exhausted && queued.hasNext()) {
            val order = queued.next()
            val usedBefore = budget.used
            var firstSegment: FirstSegmentDecision? = null
            fun record(completed: Boolean, restarted: Boolean = false) {
                samples?.add(PlanningWorkSlice(Collections.singletonList(order.action.unitId),
                    order.repair?.reason ?: PathSearchReason.USER_MOVE, budget.used - usedBefore, completed, restarted,
                    0, firstSegment))
            }
            var previousWork = workByOrder[order]
            if (previousWork != null && previousWork.requestKey !== order.requestKey) {
                if (!budget.take()) {
                    record(completed = false)
                    return
                }
                val current = snapshot(order.action, order.repair)
                if (!previousWork.rebind(current)) previousWork = Work(current)
                previousWork.requestKey = order.requestKey
                workByOrder[order] = previousWork
            }
            if (previousWork == null && order.repair != null && !budget.take()) {
                record(completed = false)
                return
            }
            val work = previousWork ?: Work(snapshot(order.action, order.repair)).also {
                it.requestKey = order.requestKey
            }
            var result = work.advance(pathPolicy, budget)
            if (result == null) {
                record(completed = false)
                workByOrder[order] = work
                return
            }
            var liveInput: MovePlanningInput? = null
            if (previousWork != null && order.repair == ManeuverRepair.SHORE_REJOIN) {
                if (result.isNotEmpty()) {
                    val cost = 8L + 2L * liveUnitCount()
                    if (cost > budget.limit) result = emptyMap()
                    else if (!budget.take(cost.toInt())) {
                        workByOrder[order] = work
                        record(completed = false)
                        return
                    } else if (!work.input.matches(snapshot(order.action, order.repair))) result = emptyMap()
                }
            } else if (previousWork != null) {
                val current = snapshot(order.action, order.repair)
                liveInput = current
                val valid = work.stillValid(current, result)
                val spliced = if (valid) result else work.trySpliceMicroNudge(current, result)
                if (spliced == null) {
                    record(completed = true, restarted = true)
                    workByOrder.remove(order)
                    queued.remove()
                    queue.add(order)
                    return
                }
                if (!valid) {
                    work.adoptSpliced(current, spliced)
                }
                result = spliced
            }
            val member = work.input.members.singleOrNull()
            if ((order.repair == null || order.repair == ManeuverRepair.CONTACT) && member?.body?.landingMember != null) {
                val end = result[member.action.unitId]?.route?.path?.firstOrNull { it != member.start }
                if (end != null) {
                    val live = liveInput ?: work.input
                    val blockers = work.checkFirstSegment(member, end, live.segmentPeers, budget)
                    if (blockers == null) {
                        workByOrder[order] = work
                        queued.remove(); queue.add(order)
                        record(completed = false)
                        return
                    }
                    if (samples != null) firstSegment = FirstSegmentDecision(member.action.actionId, member.action.unitId,
                        member.start, end, blockers.map { it.body.unitId }, if (blockers.isEmpty()) "adopt" else "replan-member")
                    if (blockers.isNotEmpty()) {
                        val retry = Work(live)
                        retry.localObstacles = blockers
                        retry.requestKey = order.requestKey
                        workByOrder[order] = retry
                        queued.remove(); queue.add(order)
                        record(completed = false, restarted = true)
                        return
                    }
                }
            }
            record(completed = true)
            byMember.remove(order.action.unitId)
            workByOrder.remove(order)
            queued.remove()
            for (m in work.input.members) {
                if (order.repair == ManeuverRepair.SHORE_REJOIN && m.action.unitId !in result) continue
                val planned = result[m.action.unitId] ?: PlannedMove(null, null, 0)
                val occupants = if (order.repair == null) emptyList()
                    else work.input.occupants.filter { it.obstructsRoute }.map { it.body.unitId }
                publish(m, order.repair, occupants, planned)
            }
        }
    }

    private class Work(var input: MovePlanningInput) {
        var requestKey: Any? = null
        private var searchedResult: Map<String, PlannedMove>? = null
        private var search: ExactPathSearch? = null
        private var searchingNearest = false
        var localObstacles = emptyList<MovePlanningOccupant>()
        private var checkedPeers = mutableMapOf<String, Pair<MovePlanningPose, Boolean>>()
        private var searchNanos = 0L
        private var checkingRoute: PlannedMove? = null
        private var occupantIndex = -1
        private var cache: PathClearanceCache? = PathClearanceCache(reuseApproach = false)
        private var rollback: PathSearchRollback? = null

        fun adoptSpliced(liveInput: MovePlanningInput, spliced: Map<String, PlannedMove>) {
            input = liveInput
            searchedResult = spliced
        }

        fun checkFirstSegment(member: MovePlanningMember, end: Position, peers: List<MovePlanningOccupant>,
                              budget: PathWorkBudget): List<MovePlanningOccupant>? {
            val body = checkNotNull(member.body)
            val blocked = mutableListOf<MovePlanningOccupant>()
            for (peer in peers) {
                val peerActiveGroup = peer.body.landingMember?.group
                if (!peer.idle && !peer.fixed && peerActiveGroup != null && peerActiveGroup !== body.landingMember?.group) continue
                val id = peer.body.unitId
                if (!UnitContact.withinSweptBounds(body, member.start, end, peer.body)) continue
                val previous = checkedPeers[id]
                val obstructs = if (previous?.first == peer.pose) previous.second else {
                    if (!budget.take()) return null
                    val intersects = UnitContact.routeAround(body, listOf(peer.body))?.allows(member.start, end,
                        body.heading, body.heading) == false
                    checkedPeers[id] = peer.pose to intersects
                    intersects
                }
                if (obstructs) blocked += peer
            }
            return blocked
        }

        fun rebind(current: MovePlanningInput): Boolean {
            if (!input.matchesRepeatedCommand(current)) return false
            input = current
            return true
        }

        fun fork(rollback: PathSearchRollback): Work = Work(input).also { copy ->
            copy.requestKey = requestKey
            copy.searchedResult = searchedResult
            copy.localObstacles = localObstacles
            copy.checkedPeers.putAll(checkedPeers)
            copy.search = search?.fork(rollback)
            copy.searchingNearest = searchingNearest
            copy.searchNanos = searchNanos
            copy.checkingRoute = checkingRoute
            copy.occupantIndex = occupantIndex
            copy.cache = cache?.fork()
            copy.rollback = rollback
        }

        fun stillValid(current: MovePlanningInput, result: Map<String, PlannedMove>): Boolean {
            if (!input.matches(current)) return false
            val member = current.members.singleOrNull()
            return input.repair != ManeuverRepair.CONTACT || member == null ||
                result[member.action.unitId]?.nearest != true || canStopAfterContact(member, current.occupants)
        }

        /**
         * Anti-livelock micro-nudge splice (ENG-P016-005):
         * Splices a completed circular path when the unit drifted slightly (<= 1.5 tiles) while
         * its multi-step search was in flight without discarding the whole search.
         */
        fun trySpliceMicroNudge(
            current: MovePlanningInput,
            result: Map<String, PlannedMove>,
        ): Map<String, PlannedMove>? {
            if (input.repair == ManeuverRepair.SHORE_REJOIN) return null
            if (input.map !== current.map || input.members.size != 1 || current.members.size != 1) return null
            val saved = input.members[0]
            val live = current.members[0]
            if (saved.action != live.action || saved.terrain !== live.terrain ||
                saved.radius != live.radius || saved.hull != live.hull || saved.hasBirthDelay != live.hasBirthDelay) return null
            if (live.pose.shape !is MovementCollisionShape.Circle) return null
            val nudgeDistSq = ScreenPlane.distanceSquared(saved.start, live.start)
            val maxNudge = minOf(input.map.tileWidth, input.map.tileHeight).toDouble() * 1.5
            if (nudgeDistSq > maxNudge * maxNudge) return null
            if (input.repair == ManeuverRepair.CONTACT) return null
            val savedBlockers = input.occupants.filter { it.obstructsRoute }.map { it.body.unitId }
            val liveBlockers = current.occupants.filter { it.obstructsRoute }.map { it.body.unitId }
            if (savedBlockers != liveBlockers) return null
            if (nudgeDistSq == 0.0) {
                return if (saved.pose.layer == live.pose.layer &&
                    saved.pose.shape == live.pose.shape &&
                    input.occupants.size == current.occupants.size &&
                    input.occupants.indices.all { i ->
                        input.occupants[i].body.unitId == current.occupants[i].body.unitId &&
                            input.occupants[i].pose == current.occupants[i].pose &&
                            input.occupants[i].idle == current.occupants[i].idle &&
                            input.occupants[i].obstructsRoute == current.occupants[i].obstructsRoute &&
                            input.occupants[i].fixed == current.occupants[i].fixed
                    }
                ) result else null
            }
            val planned = result[live.action.unitId] ?: return null
            val route = planned.route ?: return null
            if (route.path.isEmpty()) return null
            val first = route.path.first()
            val liveOccupancy = live.body?.let { body ->
                UnitContact.routeAround(body, current.occupants.filter { it.obstructsRoute }.map { it.body })
            }
            fun legClear(from: Position, to: Position): Boolean {
                if (!PlanningSegmentClearance.allows(live.terrain, from, to, live.radius)) {
                    return false
                }
                val hull = live.hull
                if (hull != null && hull.arrival(HullTerrainGrid.authorized(live.terrain), from, to, live.pose.heading) == null) {
                    return false
                }
                return liveOccupancy?.allows(from, to, live.pose.heading, live.pose.heading) != false
            }
            val splicedPath = when {
                legClear(live.start, first) -> route.path
                route.path.size > 1 && legClear(live.start, route.path[1]) -> route.path.drop(1)
                else -> {
                    val mid = Position(
                        (kotlin.math.floor(live.start.x / input.map.tileWidth) + 0.5) * input.map.tileWidth,
                        (kotlin.math.floor(live.start.y / input.map.tileHeight) + 0.5) * input.map.tileHeight,
                    )
                    val bend = listOf(mid, saved.start).firstOrNull { p ->
                        p != live.start && p != first && legClear(live.start, p) && legClear(p, first)
                    } ?: return null
                    listOf(bend) + route.path
                }
            }
            val updatedRoute = route.copy(path = splicedPath)
            return mapOf(live.action.unitId to planned.copy(route = updatedRoute))
        }

        private fun checkShoreSuccessor(member: MovePlanningMember, budget: PathWorkBudget): Map<String, PlannedMove>? {
            val body = checkNotNull(member.body)
            val next = body.pathPoints.getOrNull(1) ?: return emptyMap()
            val hull = member.hull as? RectangularDeparture ?: return emptyMap()
            if (occupantIndex < 0) {
                if (!budget.take()) return null
                val heading = hull.arrival(HullTerrainGrid.authorized(member.terrain), member.start, next, member.pose.heading)
                    ?: return emptyMap()
                checkingRoute = PlannedMove(PlannedRoute(next, heading, listOf(next)), null, 0)
                occupantIndex = 0
            }
            val accepted = checkNotNull(checkingRoute)
            val heading = checkNotNull(accepted.route).heading
            while (occupantIndex < input.occupants.size) {
                if (!budget.take()) return null
                val obstacle = input.occupants[occupantIndex++].body
                if (UnitContact.routeAround(body, listOf(obstacle))?.allows(member.start, next, member.pose.heading, heading) == false)
                    return emptyMap()
            }
            return mapOf(member.action.unitId to accepted)
        }

        fun advance(pathPolicy: ClearanceAStarPolicy, budget: PathWorkBudget): Map<String, PlannedMove>? {
            searchedResult?.let { return it }
            val member = input.members.singleOrNull() ?: return emptyMap()
            if (input.repair == ManeuverRepair.SHORE_REJOIN) return checkShoreSuccessor(member, budget)?.also { searchedResult = it }
            val point = member.action.target
            val attempt = if (member.hasBirthDelay) {
                if (!budget.take()) return null
                birthRoute(member, point)
            } else planRoute(member, point, pathPolicy, budget) ?: return null
            return mapOf(member.action.unitId to attempt).also { searchedResult = it }
        }

        private fun birthRoute(member: MovePlanningMember, point: Position): PlannedMove {
            val map = input.map
            val fits = MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth,
                map.heightTiles.toDouble() * map.tileHeight, point, member.radius)
            return PlannedMove(if (fits) routeFor(member, point, listOf(point)) else null, null, 0)
        }

        private fun planRoute(member: MovePlanningMember, point: Position, policy: ClearanceAStarPolicy,
                              budget: PathWorkBudget, clearApproach: Boolean = false): PlannedMove? {
            val active = search ?: run {
                val occupancy = if (clearApproach) null else member.body?.let { body ->
                    UnitContact.routeAround(body, (input.occupants.filter { it.obstructsRoute } + localObstacles).map { it.body })
                }
                val counters = if (input.collectDiagnostics) PathSearchCounters() else null
                val started = if (searchingNearest) policy.beginNearest(member.terrain, member.start, point, member.radius,
                    member.hull, occupancy, counters) else policy.beginExact(member.terrain, member.start, point, member.radius,
                    member.hull, occupancy, counters)
                (rollback?.let { started.fork(it) } ?: started).also { search = it }
            }
            val startTime = if (input.collectDiagnostics) System.nanoTime() else 0
            active.advance(budget, cache)
            if (input.collectDiagnostics) searchNanos += (System.nanoTime() - startTime).coerceAtLeast(0)
            if (!active.finished) return null
            if (active.path == null && input.repair == ManeuverRepair.CONTACT && canStopAfterContact(member, input.occupants)) {
                val result = PlannedMove(routeFor(member, member.start, listOf(member.start)), active.counters, searchNanos, nearest = true)
                search = null
                searchingNearest = false
                searchNanos = 0L
                return result
            }
            val stationaryPath = active.path?.all { it == member.start } == true
            val ordinaryRepairWithoutManeuver = (input.repair == ManeuverRepair.KNOWLEDGE || input.repair == ManeuverRepair.CONNECTION) &&
                member.body != null && !member.body.followsManeuverRoute &&
                member.body.landingMember == null
            if ((active.path == null || stationaryPath) && !searchingNearest &&
                !ordinaryRepairWithoutManeuver &&
                input.repair != ManeuverRepair.CONTACT) {
                searchingNearest = true
                search = null
                return null
            }
            val planned = active.path?.let { routeFor(member, if (searchingNearest) it.last() else point, it) }
            val result = PlannedMove(planned, active.counters, searchNanos, nearest = searchingNearest)
            search = null
            searchingNearest = false
            searchNanos = 0
            return result
        }

        private fun canStopAfterContact(member: MovePlanningMember, occupants: List<MovePlanningOccupant>): Boolean {
            val unit = member.body ?: return false
            if (unit.stalledRouteTicks < 420L) return false
            val blockers = occupants.filter { it.obstructsRoute }
            if (blockers.isEmpty()) return false
            if (unit.landingMember != null)
                return blockers.any { it.fixed && it.body.moveTarget == null }
            return blockers.all {
                it.fixed && it.body.moveTarget == null || opposingBlockedMoves(unit, it.body)
            }
        }

        private fun opposingBlockedMoves(unit: MutableUnit, other: MutableUnit): Boolean {
            if (other.ownerPlayerId != unit.ownerPlayerId || other.landingMember != null ||
                other.stalledRouteTicks < 420L) return false
            fun blockedBy(driver: MutableUnit, blocker: MutableUnit): Boolean {
                val pointGoal = driver.motionContract?.goal as? MotionGoalGeometry.Point
                if (pointGoal == null || driver.moveTarget == null || pointGoal.anchor != driver.moveTarget) return false
                val first = driver.pathPoints.firstOrNull() ?: return false
                val aim = if (first == driver.position) driver.pathPoints.getOrNull(1) ?: return false else first
                return UnitContact.routeAround(driver, listOf(blocker))
                    ?.allows(driver.position, aim, driver.heading, driver.heading) == false
            }
            return blockedBy(unit, other) && blockedBy(other, unit)
        }

        private fun routeFor(member: MovePlanningMember, point: Position, path: List<Position>): PlannedRoute {
            val body = member.body ?: return PlannedRoute(point, member.pose.heading, path)
            val shape = body.definition.movementCollision
            if (shape !is MovementCollisionShape.Rectangle) return PlannedRoute(point, body.heading, path)
            var from = body.position
            var heading = body.heading
            for (waypoint in path) {
                if (from != waypoint) heading = ScreenPlane.desired(from, waypoint)
                from = waypoint
            }
            return PlannedRoute(point, heading, path)
        }
    }
}
