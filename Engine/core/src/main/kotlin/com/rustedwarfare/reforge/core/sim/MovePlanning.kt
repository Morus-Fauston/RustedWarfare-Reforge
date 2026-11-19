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
                (if (replacingCommand) saved.action.sameMoveRequest(next.action) else saved.action == next.action) &&
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
                                val preserveCurrentRoute: Boolean = false, val nearest: Boolean = false)

/** The ordinary order queue belongs to the same candidate transaction as movement. */
internal class MovePlanning {
    private class Order(val group: GroupMoveRequest?, val actions: MutableList<MoveAction>,
                        val repair: ManeuverRepair? = null, var requestKey: Any = Any())

    private val orders = linkedSetOf<Order>()
    private val optionalRejoins = linkedSetOf<Order>()
    private val byMember = HashMap<String, Order>()
    private val byGroup = HashMap<GroupMoveRequest, Order>()
    private val workByOrder = HashMap<Order, Work>()

    fun fork(rollback: PathSearchRollback): MovePlanning = MovePlanning().also { pending ->
        for (order in orders.asSequence() + optionalRejoins.asSequence()) {
            // Single orders are removed as a whole, so their requests can be shared across rollback.
            val request = if (order.group == null) order else
                Order(order.group, order.actions.toMutableList(), order.repair, order.requestKey)
            pending.add(request)
            workByOrder[order]?.let { pending.workByOrder[request] = it.fork(rollback) }
        }
    }

    fun enqueue(action: MoveAction, group: GroupMoveRequest?) {
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
            val order = Order(group, mutableListOf(action))
            add(order)
            if (reusable != null) workByOrder[order] = reusable
        }
        else {
            existing.actions += action
            existing.requestKey = Any()
            // A repeated selection is received member by member. Keep its saved work
            // until the next simulation step can validate the complete new request.
            byMember[action.unitId] = existing
        }
    }

    fun cancel(unitId: String) {
        val order = byMember.remove(unitId) ?: return
        workByOrder.remove(order)
        if (order.actions.size == 1) {
            if (order.repair == ManeuverRepair.SHORE_REJOIN) optionalRejoins.remove(order) else orders.remove(order)
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
        if (order.repair == ManeuverRepair.SHORE_REJOIN) optionalRejoins.add(order) else orders.add(order)
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

    fun advance(
        pathPolicy: PathPolicy,
        budget: PathWorkBudget,
        snapshot: (List<MoveAction>, GroupMoveRequest?, ManeuverRepair?) -> MovePlanningInput,
        publish: (MovePlanningMember, GroupMoveRequest?, ManeuverRepair?, List<String>, PlannedMove) -> Unit,
        samples: MutableList<PlanningWorkSlice>? = null,
        liveUnitCount: () -> Int = { error("Missing shore snapshot size") },
    ) {
        val ordinary = orders.iterator()
        val optional = optionalRejoins.iterator()
        while (!budget.exhausted) {
            val queued = if (ordinary.hasNext()) ordinary else if (optional.hasNext()) optional else break
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
            var result = work.advance(pathPolicy, budget)
            if (work.restartTail) {
                record(completed = false, restarted = true)
                workByOrder.remove(order)
                queued.remove(); orders.add(order)
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
                        queued.remove(); orders.add(order)
                        return
                    }
                    val checked = publication.validateCurrent(current,budget)
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
                if (!work.stillValid(current, result)) {
                    record(completed = true, restarted = true)
                    // The retry waits behind other orders. Bind its next pose
                    // when it actually gets budget, rather than saving a fresh
                    // but already unworked snapshot that can move while queued.
                    workByOrder.remove(order)
                    // A moving member or occupant must not keep independent orders behind
                    // a search that is repeatedly invalidated before publication.
                    queued.remove()
                    orders.add(order)
                    return
                }
                if (order.repair == ManeuverRepair.GROUP_SLOT && current.members.singleOrNull()?.pose?.shape is MovementCollisionShape.Circle) {
                    val live = current.members.single()
                    val planned = result[live.action.unitId]
                    if (planned?.slot != null) {
                        val publication = work.publication ?: OvertakePublication(current, planned,
                            work.input.members.single().start, rejoinOnly = true).also { work.publication = it }
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
            if ((order.repair == null || order.repair == ManeuverRepair.CONTACT) && member?.body?.landingMember != null) {
                val end = result[member.action.unitId]?.slot?.path?.firstOrNull { it != member.start }
                if (end != null) {
                    val live = liveInput ?: work.input
                    val blockers = work.checkFirstSegment(member, end, live.segmentPeers, budget)
                    if (blockers == null) {
                        workByOrder[order] = work
                        queued.remove(); orders.add(order)
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
                        queued.remove(); orders.add(order)
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

        fun checkFirstSegment(member: MovePlanningMember, end: Position, peers: List<MovePlanningOccupant>,
                              budget: PathWorkBudget): List<MovePlanningOccupant>? {
            val body = checkNotNull(member.body)
            val blocked = mutableListOf<MovePlanningOccupant>()
            for (peer in peers) {
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
        private var cache = group?.let { PathClearanceCache(reuseApproach = input.repair == null && input.members.size >= 2) }
        private var rollback: PathSearchRollback? = null
        private val candidates = mutableListOf<Pair<MutableUnit, MutableList<PlannedMove>>>()
        private var immobileMembers = mutableMapOf<String, PlannedMove>()
        private var assignment: GroupMoveDestinations.Assignment? = null

        fun rebind(current: MovePlanningInput): Boolean {
            if (!scoped(input).matchesRepeatedCommand(scoped(current))) return false
            input = current
            return true
        }

        fun fork(rollback: PathSearchRollback): Work = Work(input, group, batchSize).also { copy ->
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
            if (input.repair != ManeuverRepair.GROUP_SLOT) return scoped(input).matches(scoped(current))
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

        fun advance(pathPolicy: PathPolicy, budget: PathWorkBudget): Map<String, PlannedMove>? {
            searchedResult?.let { return it }
            if (group == null) {
                val member = input.members.singleOrNull() ?: return emptyMap()
                if (input.repair == ManeuverRepair.SHORE_REJOIN) return checkShoreSuccessor(member, budget)?.also { searchedResult = it }
                val point = member.action.target
                val attempt = if (member.hasBirthDelay) {
                    if (!budget.take()) return null
                    birthRoute(member, point)
                } else planRoute(member, point, pathPolicy, budget) ?: return null
                return mapOf(member.action.unitId to attempt).also { searchedResult = it }
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
            if (active.path == null && input.repair == ManeuverRepair.CONTACT &&
                (member.body?.stalledRouteTicks ?: 0L) >= 420L &&
                (input.occupants.none { it.obstructsRoute } || input.occupants.any { it.obstructsRoute && it.fixed })) {
                val result = PlannedMove(slot(member, member.start, listOf(member.start)), active.counters, searchNanos, nearest = true)
                search = null
                searchingNearest = false
                searchNanos = 0L
                return result
            }
            val stationaryPath = active.path?.all { it == member.start } == true
            if ((active.path == null || stationaryPath) && !searchingNearest && (group == null || point == group.click) &&
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
