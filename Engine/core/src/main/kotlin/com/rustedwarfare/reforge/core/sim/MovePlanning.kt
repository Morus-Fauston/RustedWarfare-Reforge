package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.path.PathSearchCounters
import com.rustedwarfare.reforge.core.path.PathSearchReason
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import com.rustedwarfare.reforge.core.path.PlanningWorkSlice
import com.rustedwarfare.reforge.core.path.FirstSegmentDecision
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

internal data class MovePlanningOccupant(val body: MutableUnit, val pose: MovePlanningPose,
                                         val reserved: GroupMoveSlot?, val idle: Boolean, val obstructsRoute: Boolean = false,
                                         val knownRouteAim: Position? = null, val fixed: Boolean = false)

internal enum class ManeuverRepair(val reason: PathSearchReason) {
    SHORE_REJOIN(PathSearchReason.ROUTE_REPAIR),
    CONTACT(PathSearchReason.ROUTE_REPAIR),
    OVERTAKE(PathSearchReason.ROUTE_REPAIR),
    CONNECTION(PathSearchReason.ROUTE_REPAIR),
    KNOWLEDGE(PathSearchReason.KNOWLEDGE_REPLAN),
    GROUP_SLOT(PathSearchReason.ROUTE_REPAIR),
}

internal data class MovePlanningInput(
    val map: MapDefinition,
    val members: List<MovePlanningMember>,
    val occupants: List<MovePlanningOccupant>,
    val collectDiagnostics: Boolean,
    val repair: ManeuverRepair? = null,
    val segmentPeers: List<MovePlanningOccupant> = emptyList(),
    val queuePeers: List<Position> = emptyList(),
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
                                val preserveCurrentRoute: Boolean = false, val nearest: Boolean = false,
                                val branch: BranchPassage? = null)

/** The ordinary order queue belongs to the same candidate transaction as movement. */
internal class MovePlanning {
    private class Order(val group: GroupMoveRequest?, val actions: MutableList<MoveAction>,
                        val repair: ManeuverRepair? = null, var requestKey: Any = Any(),
                        var fastLaneEligible: Boolean = false)

    // A route is immutable after publication. Sharing this value across candidate steps
    // keeps a failed publication from warming the next attempt.
    private data class Corridor(val group: GroupMoveRequest, val terrain: PlanningTerrain,
                                val radius: Double, val origin: Position, val path: List<Position>)
    private var corridors: List<Corridor> = emptyList()

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
    private val byGroup = HashMap<GroupMoveRequest, Order>()
    private val workByOrder = HashMap<Order, Work>()

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
        pending.corridors = corridors
        for (order in allOrdersSequence()) {
            // Single orders are removed as a whole, so their requests can be shared across rollback.
            val request = if (order.group == null) order else
                Order(order.group, order.actions.toMutableList(), order.repair, order.requestKey, order.fastLaneEligible)
            pending.add(request)
            workByOrder[order]?.let { pending.workByOrder[request] = it.fork(rollback) }
        }
    }

    fun enqueue(action: MoveAction, group: GroupMoveRequest?, selectionSize: Int = group?.members?.size ?: 1) {
        val prior = byMember[action.unitId]
        val reusable = prior?.takeIf { old ->
            old.repair == null && old.actions.firstOrNull { it.unitId == action.unitId }?.sameMoveRequest(action) == true &&
                if (group == null) old.group == null else old.group?.let {
                    it.world === group.world && it.actor == group.actor && it.click == group.click &&
                        it.members == group.members && old.actions.mapTo(hashSetOf()) { member -> member.unitId } == group.members
                } == true
        }?.let(workByOrder::get)
        cancel(action.unitId)
        val existing = group?.let(byGroup::get)
        if (existing == null) {
            val fastLane = selectionSize <= FAST_LANE_MAX_SQUAD_SIZE
            val order = Order(group, mutableListOf(action), fastLaneEligible = fastLane)
            add(order)
            if (reusable != null) workByOrder[order] = reusable
        } else {
            val prevQueue = queueFor(existing)
            existing.actions += action
            if (selectionSize > FAST_LANE_MAX_SQUAD_SIZE || existing.actions.size > FAST_LANE_MAX_SQUAD_SIZE) {
                existing.fastLaneEligible = false
            }
            existing.requestKey = Any()
            val nextQueue = queueFor(existing)
            if (prevQueue !== nextQueue) {
                prevQueue.remove(existing)
                nextQueue.add(existing)
            }
            // A repeated selection is received member by member. Keep its saved work
            // until the next simulation step can validate the complete new request.
            byMember[action.unitId] = existing
        }
    }

    fun cancel(unitId: String) {
        val order = byMember.remove(unitId) ?: return
        workByOrder.remove(order)
        if (order.actions.size == 1) {
            p0FastLane.remove(order)
            p0Ordinary.remove(order)
            p2Repairs.remove(order)
            optionalRejoins.remove(order)
            if (order.repair == null) order.group?.let(byGroup::remove)
        } else {
            order.actions.removeAll { it.unitId == unitId }
        }
    }

    fun repair(action: MoveAction, reason: ManeuverRepair, group: GroupMoveRequest? = null) {
        val existing = byMember[action.unitId]
        if (existing != null && (existing.repair == null || existing.repair >= reason)) return
        cancel(action.unitId)
        add(Order(group, mutableListOf(action), reason))
    }

    private fun add(order: Order) {
        queueFor(order).add(order)
        if (order.repair == null) order.group?.let { byGroup[it] = order }
        for (action in order.actions) byMember[action.unitId] = order
    }

    fun pendingReservation(unitId: String): GroupMoveReservation? {
        val order = byMember[unitId] ?: return null
        val group = order.group ?: return null
        return workByOrder[order]?.heldSlot(unitId)?.let { GroupMoveReservation(group, it) }
    }

    fun pending(unitId: String): Boolean = unitId in byMember
    fun repairing(unitId: String): Boolean = byMember[unitId]?.repair != null
    fun holdsManeuverRoute(unitId: String): Boolean = byMember[unitId]?.repair?.let { it != ManeuverRepair.SHORE_REJOIN } == true
    fun replacingDestination(unitId: String): Boolean = byMember[unitId]?.repair == ManeuverRepair.GROUP_SLOT
    fun preparingPublication(unitId: String): Boolean = byMember[unitId]?.let { workByOrder[it]?.publication != null } == true

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
        lookupTraffic: (String) -> MutableUnit? = { null },
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
        processQueue(p0FastLane, pathPolicy, budget, snapshot, publish, samples, liveUnitCount, executionStep, lookupTraffic)
        if (budget.exhausted) return 0

        // Tier 0b: Ordinary fresh player orders (large groups) run ahead of P1/P2 so player clicks are never starved.
        processQueue(p0Ordinary, pathPolicy, budget, snapshot, publish, samples, liveUnitCount, executionStep, lookupTraffic)
        if (budget.exhausted) return 0

        // Tier 1: Shared flow-field build / dirty-chunk refresh slot (hook ready for ENG-P016-008).
        if (p1FlowWork != null && !budget.exhausted) {
            p1FlowWork(budget)
            if (budget.exhausted) return 0
        }

        // Tier 2: Background repairs (fair round-robin within tier, strictly isolated budget cap).
        // Enforce a step-wide 3/4 budget cap on P2 repairs whenever P1 flow work or optional rejoins
        // share the step (or across multiple same-step advance passes), leaving headroom without starving repairs.
        val stepP2Cap = if (p1FlowWork != null || optionalRejoins.isNotEmpty()) {
            maxOf(8, budget.limit * P2_REPAIR_MAX_SHARE_NUMERATOR / P2_REPAIR_MAX_SHARE_DENOMINATOR)
        } else budget.limit
        val remainingP2Cap = (stepP2Cap - p2BudgetAlreadyUsed).coerceAtLeast(0)
        val p2SubBudget = PathWorkBudget(minOf(budget.remaining, remainingP2Cap))
        val p2UsedBefore = p2SubBudget.used
        processQueue(
            p2Repairs, pathPolicy, p2SubBudget, snapshot, publish, samples, liveUnitCount, executionStep, lookupTraffic,
        )
        val p2Consumed = p2SubBudget.used - p2UsedBefore
        if (p2Consumed > 0) budget.take(p2Consumed)
        if (budget.exhausted) return p2Consumed

        // Optional lowest-priority shore rejoins.
        processQueue(optionalRejoins, pathPolicy, budget, snapshot, publish, samples, liveUnitCount, executionStep, lookupTraffic)
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
        lookupTraffic: (String) -> MutableUnit?,
    ) {
        val queued = queue.iterator()
        while (!budget.exhausted && queued.hasNext()) {
            val order = queued.next()
            val usedBefore = budget.used
            val publicationBefore = workByOrder[order]?.publication?.workUnits ?: 0
            var activePublicationWork = publicationBefore
            var firstSegment: FirstSegmentDecision? = null
            fun record(completed: Boolean, restarted: Boolean = false) {
                samples?.add(PlanningWorkSlice(Collections.unmodifiableList(order.actions.map { it.unitId }),
                    order.repair?.reason ?: PathSearchReason.USER_MOVE, budget.used - usedBefore, completed, restarted,
                    (workByOrder[order]?.publication?.workUnits ?: activePublicationWork) - publicationBefore, firstSegment))
            }
            var previousWork = workByOrder[order]
            if (previousWork != null && previousWork.requestKey !== order.requestKey) {
                if (!budget.take()) {
                    record(completed = false)
                    return
                }
                val current = snapshot(order.actions, order.group, order.repair)
                if (!previousWork.rebind(current)) previousWork = Work(current, order.group)
                previousWork.requestKey = order.requestKey
                workByOrder[order] = previousWork
            }
            if (previousWork == null && (order.group != null || order.repair != null) && !budget.take()) {
                record(completed = false)
                return
            }
            val work = previousWork ?: Work(snapshot(order.actions, order.group, order.repair), order.group).also {
                it.requestKey = order.requestKey
            }
            var result = work.advance(pathPolicy, budget, corridors)
            if (work.restartTail) {
                record(completed = false, restarted = true)
                workByOrder.remove(order)
                queued.remove(); queue.add(order)
                return
            }
            if (result == null) {
                record(completed = false)
                workByOrder[order] = work
                return
            }
            var liveInput: MovePlanningInput? = null
            if (order.repair == ManeuverRepair.OVERTAKE) {
                val current = snapshot(order.actions, order.group, order.repair)
                // A circle can finish while its search is still queued. An
                // empty live request must never publish the saved move again.
                if (current.members.isEmpty()) {
                    record(completed = true)
                    for (action in order.actions) byMember.remove(action.unitId)
                    workByOrder.remove(order)
                    queued.remove()
                    continue
                }
                val publication = work.publication ?: current.members.singleOrNull()?.let {
                    OvertakePublication(current, result[it.action.unitId] ?: PlannedMove(null,null,0), work.input.members.single().start)
                }?.also { work.publication = it }
                if (publication != null) {
                    publication.advance(budget)
                    activePublicationWork = publication.workUnits
                    if (publication.result == null) {
                        workByOrder[order] = work
                        record(completed = false)
                        return
                    }
                    val live = current.members.singleOrNull()
                    val saved = publication.input.members.single()
                    if (live == null || live.action != saved.action || live.radius != saved.radius ||
                        live.terrain !== saved.terrain || live.hull != saved.hull || live.hasBirthDelay != saved.hasBirthDelay ||
                        live.pose.layer != saved.pose.layer || live.pose.shape != saved.pose.shape) {
                        record(completed = true, restarted = true)
                        workByOrder.remove(order)
                        queued.remove(); queue.add(order)
                        return
                    }
                    val branchChecked = work.checkBranch(checkNotNull(publication.result), live, executionStep, budget, lookupTraffic)
                    if (branchChecked == null) {
                        workByOrder[order] = work
                        record(completed = false)
                        return
                    }
                    val checked = if (branchChecked.preserveCurrentRoute) branchChecked else publication.validateCurrent(current,budget)?.copy(branch = branchChecked.branch)
                    activePublicationWork = publication.workUnits
                    if(checked==null) {
                        workByOrder[order]=work
                        record(completed=false)
                        return
                    }
                    result = mapOf(saved.action.unitId to checked)
                }
            } else if (previousWork != null && order.repair == ManeuverRepair.SHORE_REJOIN) {
                if (result.isNotEmpty()) {
                    // The final live snapshot and equality check must fit this step. A dense,
                    // stale, or blocked optional shortcut never invalidates the original route.
                    // Size is O(1). Reserve the full live enumeration and a comparison
                    // for every possible current entrant before materializing any of them.
                    val cost = 8L + 2L * liveUnitCount()
                    if (cost > budget.limit) result = emptyMap()
                    else if (!budget.take(cost.toInt())) {
                        workByOrder[order] = work
                        record(completed = false)
                        return
                    } else if (!work.input.matches(snapshot(order.actions, order.group, order.repair))) result = emptyMap()
                }
            } else if (previousWork != null) {
                val current = snapshot(order.actions, order.group, order.repair)
                liveInput = current
                val valid = work.stillValid(current, result)
                val spliced = if (valid) result else work.trySpliceMicroNudge(current, result)
                if (spliced == null) {
                    record(completed = true, restarted = true)
                    // The retry waits behind other orders. Bind its next pose
                    // when it actually gets budget, rather than saving a fresh
                    // but already unworked snapshot that can move while queued.
                    workByOrder.remove(order)
                    // A moving member or occupant must not keep independent orders behind
                    // a search that is repeatedly invalidated before publication.
                    queued.remove()
                    queue.add(order)
                    return
                }
                if (!valid) {
                    // Atomically adopt live start/pose AND spliced routes so any cross-step suspension
                    // (e.g. checkFirstSegment running out of budget) stays consistent with live pose.
                    work.adoptSpliced(current, spliced)
                }
                result = spliced
                if (order.repair == ManeuverRepair.GROUP_SLOT && current.members.singleOrNull()?.pose?.shape is MovementCollisionShape.Circle) {
                    val live = current.members.single()
                    val planned = result[live.action.unitId]
                    if (planned?.slot != null) {
                        val publication = work.publication ?: OvertakePublication(current, planned,
                            live.start, rejoinOnly = true).also { work.publication = it }
                        publication.advance(budget)
                        activePublicationWork = publication.workUnits
                        if (publication.result == null) {
                            workByOrder[order] = work
                            record(completed = false)
                            return
                        }
                        val checked = publication.validateCurrent(current, budget)
                        activePublicationWork = publication.workUnits
                        if (checked == null) {
                            workByOrder[order] = work
                            record(completed = false)
                            return
                        }
                        result = mapOf(live.action.unitId to checked)
                    }
                }
            }
            val member = work.input.members.singleOrNull()
            if (order.repair == ManeuverRepair.CONTACT && member != null) {
                val planned = result[member.action.unitId]
                if (planned != null) {
                    val checked = work.checkBranch(planned, liveInput?.members?.singleOrNull() ?: member,
                        executionStep, budget, lookupTraffic)
                    if (checked == null) {
                        workByOrder[order] = work
                        record(completed = false)
                        return
                    }
                    result = mapOf(member.action.unitId to checked)
                }
            }
            if ((order.repair == null || order.repair == ManeuverRepair.CONTACT) && member?.body?.landingMember != null) {
                val end = result[member.action.unitId]?.takeUnless { it.preserveCurrentRoute }?.slot?.path?.firstOrNull { it != member.start }
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
                        val retry = Work(live, order.group)
                        retry.localObstacles = blockers
                        retry.requestKey = order.requestKey
                        workByOrder[order] = retry
                        queued.remove(); queue.add(order)
                        record(completed = false, restarted = true)
                        return
                    }
                }
            }
            val partial = work.moreRoutes
            record(completed = !partial)
            for (member in work.input.members) {
                if ((order.repair == ManeuverRepair.SHORE_REJOIN || partial || work.hasReleased) && member.action.unitId !in result) continue
                val planned = result[member.action.unitId] ?: PlannedMove(null, null, 0)
                val occupants = if (order.repair == null) emptyList()
                    else if(order.repair==ManeuverRepair.OVERTAKE) work.publication?.currentOccupantIds.orEmpty()
                    else work.input.occupants.filter { it.obstructsRoute }.map { it.body.unitId }
                val landing = member.body?.landingMember?.group
                val path = planned.slot?.path
                if (order.repair == null && landing != null && path != null && path.size in 2..32 &&
                    !planned.nearest && path.last() == landing.click && !work.usedCorridor &&
                    !work.isTrafficDetour(path)) {
                    val current = Corridor(landing, member.terrain, member.radius, member.start, path.toList())
                    val compatible = corridors.takeWhile { it.group === landing && it.terrain === member.terrain }
                    corridors = listOf(current) + compatible.take(15)
                }
                publish(member, order.group, order.repair, occupants, planned)
            }
            if (partial) {
                work.noteReleased(checkNotNull(order.group), result)
                for (id in result.keys) byMember.remove(id)
                order.actions.removeAll { it.unitId in result }
                workByOrder[order] = work
                return
            }
            for (action in order.actions) byMember.remove(action.unitId)
            if (order.repair == null) order.group?.let(byGroup::remove)
            workByOrder.remove(order)
            queued.remove()
        }
    }

    private class Work(var input: MovePlanningInput, private val group: GroupMoveRequest?,
                       private var batchSize: Int = initialBatchSize(input)) {
        private var pointSource = group?.let { GroupMoveDestinations.Points(input.map, it.click) }
        private var groupPoints = mutableListOf<Position>()
        private var openGround: OpenGroundGroup? = null
        private var released: Map<String, GroupMoveReservation> = emptyMap()
        val hasReleased get() = released.isNotEmpty()
        val moreRoutes get() = openGround?.pendingRoutes == true
        var restartTail = false
            private set
        fun heldSlot(id: String) = if (hasReleased) openGround?.heldSlot(id) else null
        fun noteReleased(group: GroupMoveRequest, ready: Map<String, PlannedMove>) {
            released = released + ready.mapValues { GroupMoveReservation(group, checkNotNull(it.value.slot)) }
        }
        private var triedOpenGround = false
        private companion object {
            fun initialBatchSize(input: MovePlanningInput): Int {
                val members = input.members
                if (input.repair != null || members.size !in 2..28) return 113
                return if (members.all { it.pose.shape is MovementCollisionShape.Circle && !it.hasBirthDelay && it.body?.definition?.movement?.type == MovementType.LAND })
                    maxOf(1, members.size * 4) else 113
            }
        }
        private var generating = true
        private var outerDistance = 0.0
        var requestKey: Any? = null
        var publication: OvertakePublication? = null
        private var searchedResult: Map<String, PlannedMove>? = null
        private var cursor = 0
        private var pointIndex = 0
        private var search: ExactPathSearch? = null
        private var searchingNearest = false
        var localObstacles = emptyList<MovePlanningOccupant>()
        private var checkedPeers = mutableMapOf<String, Pair<MovePlanningPose, Boolean>>()

        fun adoptSpliced(liveInput: MovePlanningInput, spliced: Map<String, PlannedMove>) {
            input = liveInput
            searchedResult = spliced
        }

        fun checkFirstSegment(member: MovePlanningMember, end: Position, peers: List<MovePlanningOccupant>,
                              budget: PathWorkBudget): List<MovePlanningOccupant>? {
            val body = checkNotNull(member.body)
            val blocked = mutableListOf<MovePlanningOccupant>()
            for (peer in peers) {
                // Only stationary/parked/immovable peers force a pre-launch detour; moving peers
                // in other groups are resolved dynamically by 2D local velocity avoidance (ENG-P016-006).
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

        private var searchNanos = 0L
        private var checkingSlot: PlannedMove? = null
        private var occupantIndex = -1
        private var cache: PathClearanceCache? = PathClearanceCache(reuseApproach = group != null && input.repair == null && input.members.size >= 2)
        private var rollback: PathSearchRollback? = null
        private val candidates = mutableListOf<Pair<MutableUnit, MutableList<PlannedMove>>>()
        private var immobileMembers = mutableMapOf<String, PlannedMove>()
        private var assignment: GroupMoveDestinations.Assignment? = null

        fun rebind(current: MovePlanningInput): Boolean {
            if (!scoped(input).matchesRepeatedCommand(scoped(current))) return false
            input = current
            return true
        }

        private var choice: CorridorChoice? = null
        private var inspectedBranch: BranchRepair? = null
        private var branchVerdict: Boolean? = null
        private var selectedRoad: PlannedMove? = null
        private var initialLeg = 0
        private var roadSurvey: BranchSurvey? = null

        fun checkBranch(plan: PlannedMove, member: MovePlanningMember, step: Long, budget: PathWorkBudget,
                        lookup: (String) -> MutableUnit?): PlannedMove? {
            val body = member.body ?: return plan
            val landing = body.landingMember ?: return plan
            val branch = landing.branch ?: return plan
            val slot = plan.slot ?: return plan
            if (branch.terrain !== member.terrain || landing.claimed != null || plan.preserveCurrentRoute || plan.nearest ||
                body.pathPoints.isEmpty() || slot.position != body.pathPoints.last()) return plan
            val proof = inspectedBranch ?: BranchRepair(branch, landing.group, member.start, slot.path).also { inspectedBranch = it }
            val keep = branchVerdict ?: proof.preserve(body, step, budget, lookup)?.also { branchVerdict = it } ?: return null
            return if (keep) plan.copy(preserveCurrentRoute = true) else plan.copy(branch = proof.nextBranch)
        }

        private var corridorIndex = 0
        private var triedCorridor = false
        var usedCorridor = false
            private set

        // A detour carries this driver's queue estimate. Followers can only inherit the geometric approach.
        fun isTrafficDetour(path: List<Position>): Boolean = choice?.normalRoute()?.slot?.path?.let { it !== path } == true

        fun fork(rollback: PathSearchRollback): Work = Work(input, group, batchSize).also { copy ->
            copy.inspectedBranch = inspectedBranch?.fork()
            copy.branchVerdict = branchVerdict
            copy.selectedRoad = selectedRoad
            copy.initialLeg = initialLeg
            copy.roadSurvey = roadSurvey?.fork()
            copy.choice = choice?.fork(rollback)
            copy.corridorIndex = corridorIndex
            copy.triedCorridor = triedCorridor
            copy.usedCorridor = usedCorridor
            copy.pointSource = pointSource?.fork(rollback)
            copy.groupPoints = groupPoints
            copy.openGround = openGround?.fork(rollback)
            copy.released = released; copy.restartTail = restartTail
            copy.triedOpenGround = triedOpenGround
            copy.generating = generating
            copy.outerDistance = outerDistance
            copy.requestKey = requestKey
            copy.publication = publication?.fork(rollback)
            copy.searchedResult = searchedResult
            copy.cursor = cursor
            copy.pointIndex = pointIndex
            copy.localObstacles = localObstacles
            copy.checkedPeers.putAll(checkedPeers)
            copy.search = search?.fork(rollback)
            copy.searchingNearest = searchingNearest
            copy.searchNanos = searchNanos
            copy.checkingSlot = checkingSlot
            copy.occupantIndex = occupantIndex
            copy.cache = cache?.fork()
            copy.rollback = rollback
            copy.candidates.addAll(candidates)
            copy.immobileMembers = immobileMembers
            copy.assignment = assignment?.fork(rollback)
        }

        fun stillValid(current: MovePlanningInput, result: Map<String, PlannedMove>): Boolean {
            if (hasReleased) {
                // Only already released holders still driving the very same reserved slot are internal.
                // A replacement order, new parking position or external entrant invalidates the proof.
                val remaining = input.copy(members = input.members.filterNot { it.action.unitId in released })
                val external = current.occupants.filterNot { o ->
                    val held = released[o.body.unitId]
                    held != null && o.body.groupMoveReservation?.plan === held.plan && o.reserved == held.slot
                }
                return scoped(remaining).matches(scoped(current.copy(occupants = external)))
            }
            if (input.repair != ManeuverRepair.GROUP_SLOT) {
                if (!scoped(input).matches(scoped(current))) return false
                val member = current.members.singleOrNull()
                return input.repair != ManeuverRepair.CONTACT || member == null ||
                    result[member.action.unitId]?.nearest != true || canStopAfterContact(member, current.occupants)
            }
            val chosen = input.members.singleOrNull()?.let { result[it.action.unitId]?.slot }
            val rejoiningCircle = chosen != null && input.members.singleOrNull()?.pose?.shape is MovementCollisionShape.Circle &&
                current.members.size == input.members.size
            val requests = if (rejoiningCircle) current.copy(members = current.members.mapIndexed { index, live ->
                live.copy(start = input.members[index].start, pose = live.pose.copy(position = input.members[index].pose.position))
            }) else current
            if (!input.matchesMembers(requests)) return false
            // A slowly moving holder must not restart replacement forever. Its
            // current pose is checked at the chosen slot below, and live hard
            // contact still guards every route step. A changed set of blockers
            // changes the routing problem and does require another search.
            if (input.occupants.filter { it.obstructsRoute }.map { it.body.unitId } !=
                current.occupants.filter { it.obstructsRoute }.map { it.body.unitId }) return false
            val member = input.members.singleOrNull() ?: return true
            val body = checkNotNull(member.body)
            if (chosen != null) return current.occupants.none { blocks(body, chosen, it) }

            // Exhaustion is valid only against the same current occupancy. There
            // is no need to retain and rescan a list of every rejected map point.
            return input.matches(current)
        }

        /**
         * Anti-livelock micro-nudge splice (ENG-P016-005):
         * When a circular unit was nudged slightly by a peer while its multi-step search was in flight,
         * exact `saved.start == live.start` equality would otherwise discard the completed path and
         * send the unit to the back of the queue in an infinite livelock.
         *
         * If the unit's command, terrain snapshot, hull/radius, and obstructing blockers are unchanged,
         * and the live position drifted by at most 1.5 tiles (and > 0) while keeping a clear segment
         * (either directly to the first waypoint or via a 1-tile cell-center bend), splice the route
         * directly and publish without restarting the whole search.
         */
        fun trySpliceMicroNudge(
            current: MovePlanningInput,
            result: Map<String, PlannedMove>,
        ): Map<String, PlannedMove>? {
            if (hasReleased || input.repair == ManeuverRepair.GROUP_SLOT || input.repair == ManeuverRepair.SHORE_REJOIN) return null
            if (input.map !== current.map || input.members.size != 1 || current.members.size != 1) return null
            val saved = input.members[0]
            val live = current.members[0]
            if (saved.action != live.action || saved.terrain !== live.terrain ||
                saved.radius != live.radius || saved.hull != live.hull || saved.hasBirthDelay != live.hasBirthDelay) return null
            // Only splice circular non-rectangular bodies (rectangles require exact swept turn proofs).
            if (live.pose.shape !is MovementCollisionShape.Circle) return null
            // If exact start didn't move, failure came from heading rotation (for circular units heading is
            // irrelevant to path validity!), or terrain/occupant changes.
            val nudgeDistSq = ScreenPlane.distanceSquared(saved.start, live.start)
            val maxNudge = minOf(input.map.tileWidth, input.map.tileHeight).toDouble() * 1.5
            if (nudgeDistSq > maxNudge * maxNudge) return null
            // Significant continuous push or blocker order change during a CONTACT repair still yields
            // to preserve stalled contact-repair invalidation and live-order recheck semantics.
            if (input.repair == ManeuverRepair.CONTACT) return null
            // Obstructing route blockers must still match (or have only drifted slightly without blocking the splice).
            val savedBlockers = input.occupants.filter { it.obstructsRoute }.map { it.body.unitId }
            val liveBlockers = current.occupants.filter { it.obstructsRoute }.map { it.body.unitId }
            if (savedBlockers != liveBlockers) return null
            // If position didn't move at all (only heading changed, e.g. optimistic turn in place) and
            // occupants still match, publish directly without splicing waypoints!
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
                if (!com.rustedwarfare.reforge.core.path.PlanningSegmentClearance.allows(live.terrain, from, to, live.radius)) {
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

        /** Distant changes only invalidate a search after its frontier reaches them. */
        private fun scoped(value: MovePlanningInput): MovePlanningInput {
            val request = group ?: return value
            openGround?.let { open -> return value.copy(occupants = value.occupants.filter(open::dependsOn)) }
            val radius = value.members.maxOfOrNull { checkNotNull(it.body).definition.contactBufferCollision.boundingRadius } ?: 0.0
            return value.copy(occupants = value.occupants.filter { holder ->
                val reach = outerDistance + radius + holder.body.definition.contactBufferCollision.boundingRadius
                ScreenPlane.distanceSquared(request.click, holder.pose.position) <= reach * reach ||
                    holder.reserved?.let { ScreenPlane.distanceSquared(request.click, it.position) <= reach * reach } == true
            })
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

        fun advance(pathPolicy: PathPolicy, budget: PathWorkBudget, corridors: List<Corridor>): Map<String, PlannedMove>? {
            searchedResult?.let { return it }
            if (group == null) {
                val member = input.members.singleOrNull() ?: return emptyMap()
                if (input.repair == ManeuverRepair.SHORE_REJOIN) return checkShoreSuccessor(member, budget)?.also { searchedResult = it }
                val point = member.action.target
                selectedRoad?.let { return selectCorridor(it, member, pathPolicy, budget) }
                choice?.normalRoute()?.let { return selectCorridor(it, member, pathPolicy, budget) }
                if (input.repair == null && member.body?.landingMember != null && !member.hasBirthDelay &&
                    localObstacles.isEmpty() && input.occupants.isEmpty() && member.hull is HullRoute.Circle) {
                    val reach = 4.0 * minOf(member.terrain.tileWidth, member.terrain.tileHeight)
                    while (corridorIndex < corridors.size && !triedCorridor) {
                        if (!budget.take(1)) return null
                        val corridor = corridors[corridorIndex++]
                        if (corridor.group !== member.body.landingMember?.group || corridor.terrain !== member.terrain ||
                            corridor.radius != member.radius) continue
                        val dx = corridor.origin.x - member.start.x
                        val dy = corridor.origin.y - member.start.y
                        if (dx * dx + dy * dy > reach * reach) continue
                        val aheadX = point.x - corridor.origin.x
                        val aheadY = point.y - corridor.origin.y
                        val scale = kotlin.math.hypot(aheadX, aheadY)
                        if (scale == 0.0 || dx * aheadX + dy * aheadY <= member.radius * scale ||
                            kotlin.math.abs(dx * aheadY - dy * aheadX) > 2.0 * member.radius * scale) continue
                        if (!budget.take()) { corridorIndex--; return null }
                        val entrance = corridor.path.first()
                        val grid = HullTerrainGrid.authorized(member.terrain)
                        if (member.hull.arrival(grid, member.start, entrance, member.pose.heading) != null) {
                            usedCorridor = true
                            val result = PlannedMove(slot(member, point, corridor.path),
                                if (input.collectDiagnostics) PathSearchCounters() else null, 0)
                            return selectCorridor(result, member, pathPolicy, budget)
                        }
                    }
                    triedCorridor = true
                }
                val attempt = if (member.hasBirthDelay) {
                    if (!budget.take()) return null
                    birthRoute(member, point)
                } else planRoute(member, point, pathPolicy, budget) ?: return null
                return selectCorridor(attempt, member, pathPolicy, budget)
            }
            if (input.members.isEmpty()) return emptyMap()
            if (!triedOpenGround && input.repair == null && input.members.size >= 2 &&
                input.members.first().body != null && input.members.first().pose.shape is MovementCollisionShape.Circle &&
                input.members.first().hull == null && pathPolicy is ClearanceAStarPolicy) {
                val open = openGround ?: OpenGroundGroup(input, group).also { openGround = it }
                val result = open.advance(budget) { m, point -> planRoute(m, point, pathPolicy, budget, clearApproach = true) }
                outerDistance = open.outerDistance
                if (!open.rejected) return result
                if (hasReleased) { restartTail = true; return null }
                openGround = null; triedOpenGround = true
            }
            val source = checkNotNull(pointSource)
            if (generating) {
                while (groupPoints.size < batchSize && !source.exhausted) {
                    if (!budget.take()) return null
                    val point = checkNotNull(source.next())
                    outerDistance = kotlin.math.sqrt(ScreenPlane.distanceSquared(checkNotNull(group).click, point))
                    if (input.repair != ManeuverRepair.GROUP_SLOT || point != input.members.single().action.target) {
                        groupPoints += point
                        val points = groupPoints
                        rollback?.record { points.removeAt(points.lastIndex) }
                    }
                }
                generating = false
            }
            while (groupPoints.isNotEmpty() && cursor < input.members.size && !budget.exhausted) {
                val member = input.members[cursor]
                if (member.action.unitId in immobileMembers) {
                    if (!budget.take()) return null
                    cursor++
                    pointIndex = 0
                    continue
                }
                if (cursor == candidates.size) candidates += checkNotNull(member.body) to mutableListOf<PlannedMove>()
                val point = groupPoints[pointIndex]
                // A circle's destination clearance is independent of its arrival
                // heading. Reject occupied replacement points before paying for
                // a full route to each one; vacant() retains its budgeted cursor
                // across steps and the later slot check reuses that result.
                val precheck = input.repair == ManeuverRepair.GROUP_SLOT && member.pose.shape is MovementCollisionShape.Circle
                val available = if (precheck && checkingSlot == null)
                    vacant(member, slot(member, point, emptyList()), budget) ?: return null else true
                val attempt = if (!available) PlannedMove(null, null, 0) else checkingSlot ?: run {
                    if (member.hasBirthDelay) {
                        if (!budget.take()) return null
                        birthRoute(member, point)
                    } else planRoute(member, point, pathPolicy, budget) ?: return null
                }.also {
                    checkingSlot = it
                    // A nearest route or positional capture can end somewhere
                    // other than the prechecked lattice point. Check that pose
                    // with its own cursor before accepting this replacement.
                    if (precheck && it.slot?.position != point) occupantIndex = -1
                }
                val slot = attempt.slot
                if (slot == null && attempt.nearest && point == group.click) {
                    val id = member.action.unitId
                    val rejected = immobileMembers
                    rejected[id] = attempt
                    rollback?.record { rejected.remove(id) }
                    checkingSlot = null
                    occupantIndex = -1
                    cursor++
                    pointIndex = 0
                    continue
                }
                if (slot != null && (vacant(member, slot, budget) ?: return null)) {
                    if (input.repair == ManeuverRepair.GROUP_SLOT) return mapOf(member.action.unitId to attempt)
                    val options = candidates[cursor].second
                    options += attempt
                    rollback?.record { options.removeAt(options.lastIndex) }
                }
                checkingSlot = null
                occupantIndex = -1
                pointIndex++
                if (pointIndex == groupPoints.size) {
                    cursor++
                    pointIndex = 0
                }
            }
            if (groupPoints.isNotEmpty() && cursor < input.members.size) return null
            val result = if (input.repair == ManeuverRepair.GROUP_SLOT) emptyMap() else {
                val ongoing = assignment ?: GroupMoveDestinations.Assignment(candidates.toList()).also { assignment = it }
                ongoing.advance(budget)
                ongoing.destinations ?: return null
            }
            if (result.size + immobileMembers.size == input.members.size || source.exhausted) return result + immobileMembers
            // Try the next nearest batch with accumulated choices. No fixed radius
            // can end a reachable member's search; only finite map exhaustion can.
            groupPoints = mutableListOf()
            batchSize = (batchSize.toLong() * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            cursor = 0
            pointIndex = 0
            assignment = null
            generating = true
            return null
        }

        private fun birthRoute(member: MovePlanningMember, point: Position): PlannedMove {
            val map = input.map
            val fits = MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth,
                map.heightTiles.toDouble() * map.tileHeight, point, member.radius)
            return PlannedMove(if (fits) slot(member, point, listOf(point)) else null, null, 0)
        }

        private fun selectCorridor(primary: PlannedMove, member: MovePlanningMember, policy: PathPolicy,
                                   budget: PathWorkBudget): Map<String, PlannedMove>? {
            var selected = selectedRoad ?: (if (input.queuePeers.size < 2 || input.occupants.isNotEmpty() || localObstacles.isNotEmpty()) primary
                else (choice ?: CorridorChoice().also { choice = it }).choose(primary,member,input.queuePeers,policy,budget,rollback)
                    ?: return null).also { selectedRoad = it }
            val path = selected.slot?.path
            var section = choice?.crossingSection()
            if (section == null && input.repair == null && member.body?.landingMember != null &&
                member.hull is HullRoute.Circle && !path.isNullOrEmpty() && !selected.nearest) {
                val survey = roadSurvey ?: BranchSurvey(member).also { roadSurvey = it }
                if (!survey.advance(budget)) return null
                section = survey.section
            }
            if (section != null && path != null) {
                val (alongX, center) = section
                val direction = if (alongX) member.action.target.x.compareTo(member.start.x) else member.action.target.y.compareTo(member.start.y)
                val gate = BranchPassage(member.terrain, alongX, center, direction)
                while (initialLeg < path.size) {
                    if (!budget.take(1)) return null
                    val a = if (initialLeg == 0) member.start else path[initialLeg-1]
                    val b = path[initialLeg++]
                    val point = gate.crossing(a, b) ?: continue
                    selected = selected.copy(branch = gate.copy(crossing = point))
                    break
                }
            }
            return mapOf(member.action.unitId to selected).also { searchedResult = it }
        }

        private fun planRoute(member: MovePlanningMember, point: Position, policy: PathPolicy,
                              budget: PathWorkBudget, clearApproach: Boolean = false): PlannedMove? {
            val active = search ?: run {
                // The open-ground solver checks every relevant holder once, and only accepts
                // direct segments inside that checked area. Do not reread the world per slot.
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
                val result = PlannedMove(slot(member, member.start, listOf(member.start)), active.counters, searchNanos, nearest = true)
                search = null
                searchingNearest = false
                searchNanos = 0L
                return result
            }
            val stationaryPath = active.path?.all { it == member.start } == true
            val ownsGroupSlot = member.body?.groupMoveReservation != null && input.repair != null && input.repair != ManeuverRepair.GROUP_SLOT
            // Ordinary non-maneuver MOVE repairs (KNOWLEDGE / CONNECTION) preserve the legacy
            // unreachable-route cancellation contract instead of switching to searchingNearest;
            // dead-end / island nearest-edge routing is handled by flow-field saturation in ENG-P016-008.
            val ordinaryRepairWithoutManeuver = (input.repair == ManeuverRepair.KNOWLEDGE || input.repair == ManeuverRepair.CONNECTION) &&
                member.body?.followsManeuverRoute == false
            if ((active.path == null || stationaryPath) && !searchingNearest && (group == null || point == group.click) &&
                !ownsGroupSlot &&
                !ordinaryRepairWithoutManeuver &&
                input.repair != ManeuverRepair.CONTACT &&
                !(input.repair == ManeuverRepair.OVERTAKE && member.body?.groupMoveReservation != null)) {
                searchingNearest = true
                search = null
                return null
            }
            val slot = active.path?.let { slot(member, if (searchingNearest) it.last() else point, it) }
            val result = PlannedMove(slot, active.counters, searchNanos, nearest = searchingNearest)
            search = null
            searchingNearest = false
            searchNanos = 0
            return result
        }

        private fun canStopAfterContact(member: MovePlanningMember, occupants: List<MovePlanningOccupant>): Boolean {
            val unit = member.body ?: return false
            if (unit.stalledRouteTicks < 420L) return false
            val blockers = occupants.filter { it.obstructsRoute }
            if (unit.landingMember != null || unit.groupMoveReservation != null)
                return blockers.isEmpty() || blockers.any { it.fixed }
            return blockers.all {
                it.fixed && it.body.moveTarget == null || opposingBlockedMoves(unit, it.body)
            }
        }

        private fun opposingBlockedMoves(unit: MutableUnit, other: MutableUnit): Boolean {
            if (other.ownerPlayerId != unit.ownerPlayerId || other.landingMember != null ||
                other.groupMoveReservation != null || other.stalledRouteTicks < 420L) return false
            // Each current route must lead into the other hull. A new order can
            // change that fact before either unit has changed pose.
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

        private fun slot(member: MovePlanningMember, point: Position, path: List<Position>): GroupMoveSlot {
            // Positional arrival can finish a group member at its existing pose.
            // Reserve that actual footprint, rather than a final orientation it
            // must never manoeuvre merely to match. Nearest search keeps its
            // precise endpoint and birth departure keeps its separate contract.
            if (group != null && !searchingNearest && !member.hasBirthDelay && path.size == 1 &&
                ScreenPlane.distanceSquared(member.start, point) < 49.0) {
                return GroupMoveSlot(member.start, checkNotNull(member.body).heading, listOf(member.start))
            }
            return member.body?.let { GroupMoveDestinations.slot(it, point, path) }
                ?: GroupMoveSlot(point, member.pose.heading, path)
        }

        private fun vacant(member: MovePlanningMember, slot: GroupMoveSlot, budget: PathWorkBudget): Boolean? {
            if (occupantIndex == -1) {
                if (!budget.take()) return null
                if (!UnitContact.poseFits(member.pose.shape, slot.position, slot.heading,
                        HullTerrainGrid.authorized(member.terrain))) return false
                occupantIndex = 0
            }
            val body = checkNotNull(member.body)
            while (occupantIndex < input.occupants.size) {
                if (!budget.take()) return null
                val holder = input.occupants[occupantIndex++]
                if (blocks(body, slot, holder)) {
                    return false
                }
            }
            return true
        }

        private fun blocks(body: MutableUnit, slot: GroupMoveSlot, holder: MovePlanningOccupant): Boolean =
            holder.reserved?.let { GroupMoveDestinations.overlaps(body, slot, holder.body, it) } == true ||
                holder.idle && GroupMoveDestinations.overlaps(body, slot, holder.body)
    }
}
