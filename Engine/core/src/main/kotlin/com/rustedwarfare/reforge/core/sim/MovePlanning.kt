package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.path.FirstSegmentDecision
import com.rustedwarfare.reforge.core.path.PathSearchCounters
import com.rustedwarfare.reforge.core.path.PathSearchReason
import com.rustedwarfare.reforge.core.path.PlanningSegmentClearance
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import com.rustedwarfare.reforge.core.path.PlanningWorkSlice
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

internal data class MovePlanningOccupant(
    val body: MutableUnit,
    val pose: MovePlanningPose,
    val reserved: GroupMoveSlot?,
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
    val segmentPeers: (MovePlanningMember, Position) -> List<MovePlanningOccupant> = { _, _ -> emptyList() },
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
                saved.reserved == next.reserved && saved.idle == next.idle && saved.obstructsRoute == next.obstructsRoute && saved.fixed == next.fixed
        }
}

private fun MoveAction.sameMoveRequest(other: MoveAction): Boolean =
    unitId == other.unitId && actorPlayerId == other.actorPlayerId && world === other.world && target == other.target

internal data class PlannedMove(val slot: GroupMoveSlot?, val counters: PathSearchCounters?, val searchNanos: Long,
                                val nearest: Boolean = false)

/**
 * Per-unit move & repair planning queue belonging to the same candidate transaction as movement.
 *
 * ENG-P016-009B: legacy multi-member group slot allocation (OpenGroundGroup / GroupMoveDestinations.Assignment /
 * ManeuverRepair.GROUP_SLOT) was removed; all group parking allocation lives in the single terminal landing layer
 * ([SharedLandingSites] / [MixedLandingBays]).
 */
internal class MovePlanning {
    private class Order(val actions: MutableList<MoveAction>,
                        val repair: ManeuverRepair? = null, var requestKey: Any = Any(),
                        var fastLaneEligible: Boolean = false)

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

    private fun queueFor(order: Order): LinkedHashSet<Order> = when {
        order.repair == ManeuverRepair.SHORE_REJOIN -> optionalRejoins
        order.repair != null -> p2Repairs
        order.fastLaneEligible -> p0FastLane
        else -> p0Ordinary
    }

    private fun allOrdersSequence(): Sequence<Order> =
        p0FastLane.asSequence() + p0Ordinary.asSequence() + p2Repairs.asSequence() + optionalRejoins.asSequence()

    fun fork(rollback: PathSearchRollback): MovePlanning = MovePlanning().also { pending ->
        for (order in allOrdersSequence()) {
            pending.add(order)
            workByOrder[order]?.let { pending.workByOrder[order] = it.fork(rollback) }
        }
    }

    fun enqueue(action: MoveAction, group: GroupMoveRequest? = null, selectionSize: Int = group?.members?.size ?: 1) {
        val prior = byMember[action.unitId]
        val reusable = prior?.takeIf { old ->
            old.repair == null && old.actions.firstOrNull { it.unitId == action.unitId }?.sameMoveRequest(action) == true
        }?.let(workByOrder::get)
        cancel(action.unitId)
        val fastLane = selectionSize <= FAST_LANE_MAX_SQUAD_SIZE
        val order = Order(mutableListOf(action), fastLaneEligible = fastLane)
        add(order)
        if (reusable != null) workByOrder[order] = reusable
    }

    fun cancel(unitId: String) {
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
        add(Order(mutableListOf(action), reason))
    }

    private fun add(order: Order) {
        queueFor(order).add(order)
        for (action in order.actions) byMember[action.unitId] = order
    }

    fun pending(unitId: String): Boolean = unitId in byMember
    fun repairing(unitId: String): Boolean = byMember[unitId]?.repair != null
    fun holdsManeuverRoute(unitId: String): Boolean = byMember[unitId]?.repair?.let { it != ManeuverRepair.SHORE_REJOIN } == true

    fun hasPendingFastLaneOrders(): Boolean = p0FastLane.isNotEmpty()

    fun hasPendingPlayerOrders(): Boolean = p0FastLane.isNotEmpty() || p0Ordinary.isNotEmpty()

    fun hasPendingRepairs(): Boolean = p2Repairs.isNotEmpty() || optionalRejoins.isNotEmpty()

    fun advance(
        pathPolicy: PathPolicy,
        budget: PathWorkBudget,
        snapshot: (List<MoveAction>, GroupMoveRequest?, ManeuverRepair?) -> MovePlanningInput,
        publish: (MovePlanningMember, GroupMoveRequest?, ManeuverRepair?, List<String>, PlannedMove) -> Unit,
        samples: MutableList<PlanningWorkSlice>? = null,
        liveUnitCount: () -> Int = { error("Missing shore snapshot size") },
        executionStep: Long = 0,
        p1FlowWork: ((PathWorkBudget) -> Unit)? = null,
        rotateQueues: Boolean = true,
        p2BudgetAlreadyUsed: Int = 0,
    ): Int {
        // Priority 0a: Fast-lane individual / small-squad (<= 5 units) player commands.
        processQueue(p0FastLane, pathPolicy, budget, snapshot, publish, samples, liveUnitCount, executionStep)
        if (budget.exhausted) return 0

        // Priority 0b: Remaining ordinary player move commands.
        processQueue(p0Ordinary, pathPolicy, budget, snapshot, publish, samples, liveUnitCount, executionStep)
        if (budget.exhausted) return 0

        // Priority 1: Optional shared-flow-field incremental work slot.
        if (p1FlowWork != null && !budget.exhausted) {
            p1FlowWork(budget)
            if (budget.exhausted) return 0
        }

        // Priority 2: Background repair queue with anti-starvation rotation and bounded step share.
        if (rotateQueues && p2Repairs.size > 1) {
            val head = p2Repairs.first()
            p2Repairs.remove(head)
            p2Repairs.add(head)
        }
        // A repair must fit at least one indivisible path operation. Ordinary orders retain first service.
        val maxP2Total = maxOf(
            minOf(budget.limit, PathWorkBudget.OPERATION_COST),
            (budget.limit * P2_REPAIR_MAX_SHARE_NUMERATOR) / P2_REPAIR_MAX_SHARE_DENOMINATOR,
        )
        val remainingP2Cap = (maxP2Total - p2BudgetAlreadyUsed).coerceAtLeast(0)
        val remainingStep = budget.limit - budget.used
        val p2Limit = minOf(remainingStep, remainingP2Cap)
        if (p2Limit <= 0) return 0
        val p2SubBudget = PathWorkBudget(p2Limit)
        val p2UsedBefore = p2SubBudget.used
        processQueue(
            p2Repairs, pathPolicy, p2SubBudget, snapshot, publish, samples, liveUnitCount, executionStep,
        )
        val p2Consumed = p2SubBudget.used - p2UsedBefore
        if (p2Consumed > 0) budget.take(p2Consumed)
        if (budget.exhausted) return p2Consumed

        // Optional lowest-priority shore rejoins.
        processQueue(optionalRejoins, pathPolicy, budget, snapshot, publish, samples, liveUnitCount, executionStep)
        return p2Consumed
    }

    private fun processQueue(
        queue: LinkedHashSet<Order>,
        pathPolicy: PathPolicy,
        budget: PathWorkBudget,
        snapshot: (List<MoveAction>, GroupMoveRequest?, ManeuverRepair?) -> MovePlanningInput,
        publish: (MovePlanningMember, GroupMoveRequest?, ManeuverRepair?, List<String>, PlannedMove) -> Unit,
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
                samples?.add(PlanningWorkSlice(Collections.unmodifiableList(order.actions.map { it.unitId }),
                    order.repair?.reason ?: PathSearchReason.USER_MOVE, budget.used - usedBefore, completed, restarted,
                    0, firstSegment))
            }
            var previousWork = workByOrder[order]
            if (previousWork != null && previousWork.requestKey !== order.requestKey) {
                if (!budget.take()) {
                    record(completed = false)
                    return
                }
                val current = snapshot(order.actions, null, order.repair)
                if (!previousWork.rebind(current)) previousWork = Work(current)
                previousWork.requestKey = order.requestKey
                workByOrder[order] = previousWork
            }
            if (previousWork == null && order.repair != null && !budget.take()) {
                record(completed = false)
                return
            }
            val work = previousWork ?: Work(snapshot(order.actions, null, order.repair)).also {
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
                    } else if (!work.input.matches(snapshot(order.actions, null, order.repair))) result = emptyMap()
                }
            } else if (previousWork != null) {
                val current = snapshot(order.actions, null, order.repair)
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
                val end = result[member.action.unitId]?.slot?.path?.firstOrNull { it != member.start }
                if (end != null) {
                    val live = liveInput ?: work.input
                    val peers = live.segmentPeers(member, end)
                    val blockers = work.checkFirstSegment(member, end, peers, budget)
                    if (blockers == null) {
                        workByOrder[order] = work
                        queued.remove(); queue.add(order)
                        record(completed = false)
                        return
                    }
                    if (samples != null) firstSegment = FirstSegmentDecision(member.action.actionId, member.action.unitId,
                        member.start, end, blockers.map { it.body.unitId }, if (blockers.isEmpty()) "adopt" else "replan-member", peers.size)
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
            for (plannedMember in work.input.members) {
                if (order.repair == ManeuverRepair.SHORE_REJOIN && plannedMember.action.unitId !in result) continue
                val planned = result[plannedMember.action.unitId] ?: PlannedMove(null, null, 0)
                val occupants = if (order.repair == null) emptyList()
                    else work.input.occupants.filter { it.obstructsRoute }.map { it.body.unitId }
                publish(plannedMember, null, order.repair, occupants, planned)
            }
            for (action in order.actions) byMember.remove(action.unitId)
            workByOrder.remove(order)
            queued.remove()
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
        private var checkingSlot: PlannedMove? = null
        private var occupantIndex = -1
        private var cache: PathClearanceCache? = PathClearanceCache(reuseApproach = false)
        private var rollback: PathSearchRollback? = null

        fun adoptSpliced(liveInput: MovePlanningInput, spliced: Map<String, PlannedMove>) {
            input = liveInput
            searchedResult = spliced
            checkedPeers.clear()
        }

        fun checkFirstSegment(member: MovePlanningMember, end: Position, peers: List<MovePlanningOccupant>,
                              budget: PathWorkBudget): List<MovePlanningOccupant>? {
            val body = checkNotNull(member.body)
            val blocked = mutableListOf<MovePlanningOccupant>()
            for (peer in peers) {
                // Moving bodies are handled by local avoidance. Only a stationary obstruction invalidates publication.
                if (!peer.idle && !peer.fixed) continue
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
            copy.checkingSlot = checkingSlot
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
         * When a circular unit was nudged slightly by a peer while its multi-step search was in flight,
         * splice the route directly without discarding and restarting the whole search.
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
                            input.occupants[i].reserved == current.occupants[i].reserved &&
                            input.occupants[i].idle == current.occupants[i].idle &&
                            input.occupants[i].obstructsRoute == current.occupants[i].obstructsRoute &&
                            input.occupants[i].fixed == current.occupants[i].fixed
                    }
                ) result else null
            }
            val planned = result[live.action.unitId] ?: return null
            val slot = planned.slot ?: return null
            if (slot.path.isEmpty()) return null
            val first = slot.path.first()
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
                legClear(live.start, first) -> slot.path
                slot.path.size > 1 && legClear(live.start, slot.path[1]) -> slot.path.drop(1)
                else -> {
                    val mid = Position(
                        (kotlin.math.floor(live.start.x / input.map.tileWidth) + 0.5) * input.map.tileWidth,
                        (kotlin.math.floor(live.start.y / input.map.tileHeight) + 0.5) * input.map.tileHeight,
                    )
                    val bend = listOf(mid, saved.start).firstOrNull { p ->
                        p != live.start && p != first && legClear(live.start, p) && legClear(p, first)
                    } ?: return null
                    listOf(bend) + slot.path
                }
            }
            val updatedSlot = slot.copy(path = splicedPath)
            return mapOf(live.action.unitId to planned.copy(slot = updatedSlot))
        }

        private fun checkShoreSuccessor(member: MovePlanningMember, budget: PathWorkBudget): Map<String, PlannedMove>? {
            val body = checkNotNull(member.body)
            val next = body.pathPoints.getOrNull(1) ?: return emptyMap()
            val hull = member.hull as? RectangularDeparture ?: return emptyMap()
            if (occupantIndex < 0) {
                if (!budget.take()) return null
                val heading = hull.arrival(HullTerrainGrid.authorized(member.terrain), member.start, next, member.pose.heading)
                    ?: return emptyMap()
                checkingSlot = PlannedMove(GroupMoveSlot(next, heading, listOf(next)), null, 0)
                occupantIndex = 0
            }
            val accepted = checkNotNull(checkingSlot)
            val heading = checkNotNull(accepted.slot).heading
            while (occupantIndex < input.occupants.size) {
                if (!budget.take()) return null
                val obstacle = input.occupants[occupantIndex++].body
                if (UnitContact.routeAround(body, listOf(obstacle))?.allows(member.start, next, member.pose.heading, heading) == false)
                    return emptyMap()
            }
            return mapOf(member.action.unitId to accepted)
        }

        fun advance(pathPolicy: PathPolicy, budget: PathWorkBudget): Map<String, PlannedMove>? {
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
            return PlannedMove(if (fits) slot(member, point, listOf(point)) else null, null, 0)
        }

        private fun planRoute(member: MovePlanningMember, point: Position, policy: PathPolicy,
                              budget: PathWorkBudget): PlannedMove? {
            val active = search ?: run {
                val occupancy = member.body?.let { body ->
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
                val result = PlannedMove(slot(member, member.start, listOf(member.start)), active.counters, searchNanos, nearest = true)
                search = null
                searchingNearest = false
                searchNanos = 0L
                return result
            }
            val stationaryPath = active.path?.all { it == member.start } == true
            val ordinaryRepairWithoutManeuver = (input.repair == ManeuverRepair.KNOWLEDGE || input.repair == ManeuverRepair.CONNECTION) &&
                member.body?.followsManeuverRoute == false && member.body.landingMember == null
            if ((active.path == null || stationaryPath) && !searchingNearest &&
                !ordinaryRepairWithoutManeuver &&
                input.repair != ManeuverRepair.CONTACT) {
                searchingNearest = true
                search = null
                return null
            }
            val slot = active.path?.let { slot(member, if (searchingNearest) it.last() else point, it) }
            val result = PlannedMove(slot, active.counters, searchNanos, nearest = searchingNearest)
            search = null
            searchingNearest = false
            searchNanos = 0L
            return result
        }

        private fun canStopAfterContact(member: MovePlanningMember, occupants: List<MovePlanningOccupant>): Boolean {
            val unit = member.body ?: return false
            if (unit.stalledRouteTicks < 420L) return false
            val blockers = occupants.filter { it.obstructsRoute }
            if (unit.landingMember != null)
                return blockers.isEmpty() || blockers.any { it.fixed }
            return blockers.all {
                it.fixed && it.body.moveTarget == null || opposingBlockedMoves(unit, it.body)
            }
        }

        private fun opposingBlockedMoves(unit: MutableUnit, other: MutableUnit): Boolean {
            if (other.ownerPlayerId != unit.ownerPlayerId || other.landingMember != null ||
                other.stalledRouteTicks < 420L) return false
            fun blockedBy(driver: MutableUnit, blocker: MutableUnit): Boolean {
                val task = driver.tasks.firstOrNull() ?: return false
                if (task.kind != UnitTaskKind.MOVE || task.target != driver.moveTarget) return false
                val first = driver.pathPoints.firstOrNull() ?: return false
                val aim = if (first == driver.position) driver.pathPoints.getOrNull(1) ?: return false else first
                return UnitContact.routeAround(driver, listOf(blocker))
                    ?.allows(driver.position, aim, driver.heading, driver.heading) == false
            }
            return blockedBy(unit, other) && blockedBy(other, unit)
        }

        private fun slot(member: MovePlanningMember, point: Position, path: List<Position>): GroupMoveSlot =
            member.body?.let { GroupMoveDestinations.slot(it, point, path) }
                ?: GroupMoveSlot(point, member.pose.heading, path)
    }
}
