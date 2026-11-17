package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.path.PathSearchCounters
import com.rustedwarfare.reforge.core.path.PathSearchReason
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

internal data class MovePlanningOccupant(val body: MutableUnit, val pose: MovePlanningPose,
                                         val reserved: GroupMoveSlot?, val idle: Boolean, val obstructsRoute: Boolean = false,
                                         val knownRouteAim: Position? = null)

internal enum class ManeuverRepair(val reason: PathSearchReason) {
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
) {
    fun matches(current: MovePlanningInput): Boolean =
        matchesMembers(current) && sameOccupants(occupants, current.occupants)

    fun matchesMembers(current: MovePlanningInput): Boolean =
        map === current.map && members.size == current.members.size &&
            members.indices.all { index ->
                val saved = members[index]
                val next = current.members[index]
                saved.action == next.action && saved.pose == next.pose && saved.start == next.start &&
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
                saved.reserved == next.reserved && saved.idle == next.idle && saved.obstructsRoute == next.obstructsRoute
        }
}

internal data class PlannedMove(val slot: GroupMoveSlot?, val counters: PathSearchCounters?, val searchNanos: Long,
                                val preserveCurrentRoute: Boolean = false)

/** The ordinary order queue belongs to the same candidate transaction as movement. */
internal class MovePlanning {
    private class Order(val group: GroupMoveRequest?, val actions: MutableList<MoveAction>,
                        val repair: ManeuverRepair? = null)

    private val orders = linkedSetOf<Order>()
    private val byMember = HashMap<String, Order>()
    private val byGroup = HashMap<GroupMoveRequest, Order>()
    private val workByOrder = HashMap<Order, Work>()

    fun fork(rollback: PathSearchRollback): MovePlanning = MovePlanning().also { pending ->
        for (order in orders) {
            // Single orders are removed as a whole, so their requests can be shared across rollback.
            val request = if (order.group == null) order else Order(order.group, order.actions.toMutableList(), order.repair)
            pending.add(request)
            workByOrder[order]?.let { pending.workByOrder[request] = it.fork(rollback) }
        }
    }

    fun enqueue(action: MoveAction, group: GroupMoveRequest?) {
        cancel(action.unitId)
        val existing = group?.let(byGroup::get)
        if (existing == null) add(Order(group, mutableListOf(action)))
        else {
            existing.actions += action
            workByOrder.remove(existing)
            byMember[action.unitId] = existing
        }
    }

    fun cancel(unitId: String) {
        val order = byMember.remove(unitId) ?: return
        workByOrder.remove(order)
        if (order.actions.size == 1) {
            orders.remove(order)
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
        orders.add(order)
        if (order.repair == null) order.group?.let { byGroup[it] = order }
        for (action in order.actions) byMember[action.unitId] = order
    }

    fun pending(unitId: String): Boolean = unitId in byMember
    fun repairing(unitId: String): Boolean = byMember[unitId]?.repair != null
    fun preparingPublication(unitId: String): Boolean = byMember[unitId]?.let { workByOrder[it]?.publication != null } == true

    fun advance(
        pathPolicy: PathPolicy,
        budget: PathWorkBudget,
        snapshot: (List<MoveAction>, GroupMoveRequest?, ManeuverRepair?) -> MovePlanningInput,
        publish: (MovePlanningMember, GroupMoveRequest?, ManeuverRepair?, List<String>, PlannedMove) -> Unit,
        samples: MutableList<PlanningWorkSlice>? = null,
    ) {
        val queued = orders.iterator()
        while (queued.hasNext() && !budget.exhausted) {
            val order = queued.next()
            val usedBefore = budget.used
            val publicationBefore = workByOrder[order]?.publication?.workUnits ?: 0
            var activePublicationWork = publicationBefore
            fun record(completed: Boolean, restarted: Boolean = false) {
                samples?.add(PlanningWorkSlice(Collections.unmodifiableList(order.actions.map { it.unitId }),
                    order.repair?.reason ?: PathSearchReason.USER_MOVE, budget.used - usedBefore, completed, restarted,
                    (workByOrder[order]?.publication?.workUnits ?: activePublicationWork) - publicationBefore))
            }
            val previousWork = workByOrder[order]
            if (previousWork == null && (order.group != null || order.repair != null) && !budget.take()) {
                record(completed = false)
                return
            }
            val work = previousWork ?: Work(snapshot(order.actions, order.group, order.repair), order.group)
            var result = work.advance(pathPolicy, budget)
            if (result == null) {
                record(completed = false)
                workByOrder[order] = work
                return
            }
            if (order.repair == ManeuverRepair.OVERTAKE) {
                val current = snapshot(order.actions, order.group, order.repair)
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
                        workByOrder[order] = Work(current, order.group)
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
            } else if (previousWork != null) {
                val current = snapshot(order.actions, order.group, order.repair)
                if (!work.stillValid(current, result)) {
                    record(completed = true, restarted = true)
                    workByOrder[order] = Work(current, order.group)
                    // A moving member or occupant must not keep independent orders behind
                    // a search that is repeatedly invalidated before publication.
                    queued.remove()
                    orders.add(order)
                    return
                }
            }
            record(completed = true)
            for (member in work.input.members) {
                val planned = result[member.action.unitId] ?: PlannedMove(null, null, 0)
                val occupants = if (order.repair == null) emptyList()
                    else if(order.repair==ManeuverRepair.OVERTAKE) work.publication?.currentOccupantIds.orEmpty()
                    else work.input.occupants.filter { it.obstructsRoute }.map { it.body.unitId }
                publish(member, order.group, order.repair, occupants, planned)
            }
            for (action in order.actions) byMember.remove(action.unitId)
            if (order.repair == null) order.group?.let(byGroup::remove)
            workByOrder.remove(order)
            queued.remove()
        }
    }

    private class Work(val input: MovePlanningInput, private val group: GroupMoveRequest?,
                       private val groupPoints: List<Position>? = group?.let {
                           val points = GroupMoveDestinations.positions(input.map, it.click)
                           if (input.repair == ManeuverRepair.GROUP_SLOT) {
                               val prior = input.members.singleOrNull()?.action?.target
                               points.filterNot { point -> point == prior }
                           } else points
                       }) {
        var publication: OvertakePublication? = null
        private var searchedResult: Map<String, PlannedMove>? = null
        private var cursor = 0
        private var pointIndex = 0
        private var search: ExactPathSearch? = null
        private var searchNanos = 0L
        private var checkingSlot: PlannedMove? = null
        private var occupantIndex = -1
        private var cache = group?.let { PathClearanceCache() }
        private val candidates = mutableListOf<Pair<MutableUnit, List<PlannedMove>>>()
        private val currentMemberCandidates = mutableListOf<PlannedMove>()
        private var assignment: GroupMoveDestinations.Assignment? = null
        private val blockedSlots = if (input.repair == ManeuverRepair.GROUP_SLOT)
            mutableListOf<Pair<GroupMoveSlot, String>>() else null

        fun fork(rollback: PathSearchRollback): Work = Work(input, group, groupPoints).also { copy ->
            copy.publication = publication?.fork(rollback)
            copy.searchedResult = searchedResult
            copy.cursor = cursor
            copy.pointIndex = pointIndex
            copy.search = search?.fork(rollback)
            copy.searchNanos = searchNanos
            copy.checkingSlot = checkingSlot
            copy.occupantIndex = occupantIndex
            copy.cache = cache?.fork()
            copy.candidates.addAll(candidates)
            copy.currentMemberCandidates.addAll(currentMemberCandidates)
            copy.assignment = assignment?.fork()
            blockedSlots?.let { checkNotNull(copy.blockedSlots).addAll(it) }
        }

        fun stillValid(current: MovePlanningInput, result: Map<String, PlannedMove>): Boolean {
            if (input.repair != ManeuverRepair.GROUP_SLOT) return input.matches(current)
            if (!input.matchesMembers(current) || !input.matchesRouteBlockers(current)) return false
            val member = input.members.singleOrNull() ?: return true
            val body = checkNotNull(member.body)
            val chosen = result[member.action.unitId]?.slot
            if (chosen != null) return current.occupants.none { blocks(body, chosen, it) }

            // A failed option stays rejected if its actual blocking pose still overlaps it.
            val occupantsById = current.occupants.associateBy { it.body.unitId }
            return checkNotNull(blockedSlots).all { (option, blockerId) ->
                occupantsById[blockerId]?.let { blocks(body, option, it) } == true
            }
        }

        fun advance(pathPolicy: PathPolicy, budget: PathWorkBudget): Map<String, PlannedMove>? {
            searchedResult?.let { return it }
            if (group == null) {
                val member = input.members.singleOrNull() ?: return emptyMap()
                val point = member.action.target
                val attempt = if (member.hasBirthDelay) {
                    if (!budget.take()) return null
                    birthRoute(member, point)
                } else planRoute(member, point, pathPolicy, budget) ?: return null
                return mapOf(member.action.unitId to attempt).also { searchedResult = it }
            }
            while (cursor < input.members.size && !budget.exhausted) {
                val member = input.members[cursor]
                val points = checkNotNull(groupPoints)
                val point = points[pointIndex]
                val attempt = checkingSlot ?: run {
                    if (member.hasBirthDelay) {
                        if (!budget.take()) return null
                        birthRoute(member, point)
                    } else planRoute(member, point, pathPolicy, budget) ?: return null
                }.also { checkingSlot = it }
                val slot = attempt.slot
                if (slot != null && (vacant(member, slot, budget) ?: return null)) {
                    if (input.repair == ManeuverRepair.GROUP_SLOT) return mapOf(member.action.unitId to attempt)
                    currentMemberCandidates += attempt
                }
                checkingSlot = null
                occupantIndex = -1
                pointIndex++
                if (pointIndex == points.size) {
                    candidates += checkNotNull(member.body) to currentMemberCandidates.toList()
                    currentMemberCandidates.clear()
                    cursor++
                    pointIndex = 0
                }
            }
            if (cursor < input.members.size) return null
            if (input.members.isEmpty() || input.repair == ManeuverRepair.GROUP_SLOT) return emptyMap()
            val ongoing = assignment ?: GroupMoveDestinations.Assignment(candidates.toList()).also { assignment = it }
            ongoing.advance(budget)
            return ongoing.destinations
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
                val occupancy = when (input.repair) {
                    ManeuverRepair.CONTACT, ManeuverRepair.OVERTAKE, ManeuverRepair.GROUP_SLOT -> UnitContact.routeAround(checkNotNull(member.body),
                        input.occupants.filter { it.obstructsRoute }.map { it.body })
                    else -> null
                }
                policy.beginExact(member.terrain, member.start, point, member.radius,
                    member.hull, occupancy, if (input.collectDiagnostics) PathSearchCounters() else null).also { search = it }
            }
            val startTime = if (input.collectDiagnostics) System.nanoTime() else 0
            active.advance(budget, cache)
            if (input.collectDiagnostics) searchNanos += (System.nanoTime() - startTime).coerceAtLeast(0)
            if (!active.finished) return null
            val slot = active.path?.let { slot(member, point, it) }
            val result = PlannedMove(slot, active.counters, searchNanos)
            search = null
            searchNanos = 0
            return result
        }

        private fun slot(member: MovePlanningMember, point: Position, path: List<Position>): GroupMoveSlot =
            member.body?.let { GroupMoveDestinations.slot(it, point, path) }
                ?: GroupMoveSlot(point, member.pose.heading, path)

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
                    blockedSlots?.add(slot.copy(path = emptyList()) to holder.body.unitId)
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
