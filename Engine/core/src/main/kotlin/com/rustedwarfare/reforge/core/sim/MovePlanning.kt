package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.path.PathSearchCounters
import com.rustedwarfare.reforge.core.path.PlanningTerrain

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
                                         val reserved: GroupMoveSlot?, val idle: Boolean)

internal data class MovePlanningInput(
    val map: MapDefinition,
    val members: List<MovePlanningMember>,
    val occupants: List<MovePlanningOccupant>,
    val collectDiagnostics: Boolean,
) {
    fun matches(current: MovePlanningInput): Boolean =
        map === current.map && members.size == current.members.size &&
            members.indices.all { index ->
                val saved = members[index]
                val next = current.members[index]
                saved.action == next.action && saved.pose == next.pose && saved.start == next.start &&
                    saved.radius == next.radius && saved.terrain === next.terrain && saved.hull == next.hull &&
                    saved.hasBirthDelay == next.hasBirthDelay
            } && occupants.size == current.occupants.size &&
            occupants.indices.all { index ->
                val saved = occupants[index]
                val next = current.occupants[index]
                saved.body.unitId == next.body.unitId && saved.pose == next.pose &&
                    saved.reserved == next.reserved && saved.idle == next.idle
            }
}

internal data class PlannedMove(val slot: GroupMoveSlot?, val counters: PathSearchCounters?, val searchNanos: Long)

/** The ordinary order queue belongs to the same candidate transaction as movement. */
internal class MovePlanning {
    private class Order(val group: GroupMoveRequest?, val actions: MutableList<MoveAction>, var work: Work? = null) {
        fun fork(rollback: PathSearchRollback) = Order(group, actions.toMutableList(), work?.fork(rollback))
    }

    private val orders = linkedSetOf<Order>()
    private val byMember = HashMap<String, Order>()
    private val byGroup = HashMap<GroupMoveRequest, Order>()

    fun fork(rollback: PathSearchRollback): MovePlanning = MovePlanning().also { pending ->
        for (order in orders) pending.add(order.fork(rollback))
    }

    fun enqueue(action: MoveAction, group: GroupMoveRequest?) {
        cancel(action.unitId)
        val existing = group?.let(byGroup::get)
        if (existing == null) add(Order(group, mutableListOf(action)))
        else {
            existing.actions += action
            existing.work = null
            byMember[action.unitId] = existing
        }
    }

    fun cancel(unitId: String) {
        val order = byMember.remove(unitId) ?: return
        order.actions.removeAll { it.unitId == unitId }
        if (order.actions.isEmpty()) {
            orders.remove(order)
            order.group?.let(byGroup::remove)
        } else {
            order.work = null
        }
    }

    private fun add(order: Order) {
        orders.add(order)
        order.group?.let { byGroup[it] = order }
        for (action in order.actions) byMember[action.unitId] = order
    }

    fun pending(unitId: String): Boolean = unitId in byMember

    fun advance(
        pathPolicy: PathPolicy,
        budget: PathWorkBudget,
        snapshot: (List<MoveAction>, GroupMoveRequest?) -> MovePlanningInput,
        publish: (MovePlanningMember, GroupMoveRequest?, PlannedMove) -> Unit,
    ) {
        val queued = orders.iterator()
        while (queued.hasNext() && !budget.exhausted) {
            val order = queued.next()
            val previousWork = order.work
            if (previousWork == null && order.group != null && !budget.take()) return
            val work = previousWork ?: Work(snapshot(order.actions, order.group), order.group).also { order.work = it }
            val result = work.advance(pathPolicy, budget) ?: return
            if (previousWork != null) {
                val current = snapshot(order.actions, order.group)
                if (!work.input.matches(current)) {
                    order.work = Work(current, order.group)
                    return
                }
            }
            for (member in work.input.members) {
                val planned = result[member.action.unitId] ?: PlannedMove(null, null, 0)
                publish(member, order.group, planned)
            }
            for (action in order.actions) byMember.remove(action.unitId)
            order.group?.let(byGroup::remove)
            queued.remove()
        }
    }

    private class Work(val input: MovePlanningInput, private val group: GroupMoveRequest?,
                       private val groupPoints: List<Position>? = group?.let { GroupMoveDestinations.positions(input.map, it.click) }) {
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

        fun fork(rollback: PathSearchRollback): Work = Work(input, group, groupPoints).also { copy ->
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
        }

        fun advance(pathPolicy: PathPolicy, budget: PathWorkBudget): Map<String, PlannedMove>? {
            if (group == null) {
                val member = input.members.singleOrNull() ?: return emptyMap()
                val point = member.action.target
                val attempt = if (member.hasBirthDelay) {
                    if (!budget.take()) return null
                    birthRoute(member, point)
                } else planRoute(member, point, pathPolicy, budget) ?: return null
                return mapOf(member.action.unitId to attempt)
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
                if (slot != null && (vacant(member, slot, budget) ?: return null))
                    currentMemberCandidates += attempt
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
            if (input.members.isEmpty()) return emptyMap()
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
            val active = search ?: policy.beginExact(member.terrain, member.start, point, member.radius,
                member.hull, counters = if (input.collectDiagnostics) PathSearchCounters() else null).also { search = it }
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
                if (holder.reserved?.let { GroupMoveDestinations.overlaps(body, slot, holder.body, it) } == true ||
                    holder.idle && GroupMoveDestinations.overlaps(body, slot, holder.body)) return false
            }
            return true
        }
    }
}
