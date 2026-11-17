package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MapDefinition

/** The selected members and target of one ordinary group order. */
class GroupMoveRequest internal constructor(
    internal val world: WorldHandle,
    internal val completedStep: Long?,
    internal val actor: String,
    internal val click: Position,
    internal val members: Set<String>,
)

internal data class GroupMoveSlot(val position: Position, val heading: Double, val path: List<Position>)
internal data class GroupMoveReservation(val plan: GroupMoveRequest, val slot: GroupMoveSlot,
                                         val occupiedTicks: Long = 0, val needsReplacement: Boolean = false)

internal object GroupMoveDestinations {
    fun positions(map: MapDefinition, click: Position): List<Position> {
        val spacing = minOf(map.tileWidth, map.tileHeight) / 2.0
        return buildList {
            // Search around the exact click, through a radius of three map cells.
            for (dy in -6..6) for (dx in -6..6) {
                if (dx * dx + dy * dy <= 36) add(Position(click.x + dx * spacing, click.y + dy * spacing))
            }
        }.sortedWith(compareBy<Position> { ScreenPlane.distanceSquared(click, it) }.thenBy { it.x }.thenBy { it.y })
    }

    class Assignment(private val choices: List<Pair<MutableUnit, List<PlannedMove>>>) {
        private enum class Phase { FILTER, RESERVE, RECONCILE }
        private enum class Check { MORE, CLEAR, BLOCKED }
        private var phase = Phase.FILTER
        private val staying = mutableListOf<MutableUnit>()
        private val stayingIds = hashSetOf<String>()
        private val usable = mutableListOf<Pair<MutableUnit, List<PlannedMove>>>()
        private val reserved = mutableListOf<Pair<MutableUnit, PlannedMove>>()
        private val reservedIds = hashSetOf<String>()
        private val conflicts = mutableListOf<MutableUnit>()
        private var memberIndex = 0
        private var optionIndex = 0
        private var pairIndex = 0
        private val filteredOptions = mutableListOf<PlannedMove>()
        var destinations: Map<String, PlannedMove>? = null
            private set

        fun fork(): Assignment = Assignment(choices).also {
            it.phase = phase
            it.staying.addAll(staying)
            it.stayingIds.addAll(stayingIds)
            it.usable.addAll(usable)
            it.reserved.addAll(reserved)
            it.reservedIds.addAll(reservedIds)
            it.conflicts.addAll(conflicts)
            it.memberIndex = memberIndex
            it.optionIndex = optionIndex
            it.pairIndex = pairIndex
            it.filteredOptions.addAll(filteredOptions)
            it.destinations = destinations
        }

        fun advance(budget: PathWorkBudget) {
            while (destinations == null && budget.take()) {
                when (phase) {
                    Phase.FILTER -> filterOne()
                    Phase.RESERVE -> reserveOne()
                    Phase.RECONCILE -> reconcileOne()
                }
            }
        }

        private fun filterOne() {
            if (memberIndex == choices.size) {
                usable.sortWith(compareBy<Pair<MutableUnit, List<PlannedMove>>> { it.second.size }.thenBy { it.first.updateKey })
                phase = Phase.RESERVE
                memberIndex = 0
                return
            }
            val (unit, options) = choices[memberIndex]
            if (optionIndex == options.size) {
                usable += unit to filteredOptions.toList()
                filteredOptions.clear()
                nextMember()
                return
            }
            val option = options[optionIndex]
            val slot = checkNotNull(option.slot)
            when (checkPair(staying.size) { staying[it] !== unit && overlaps(unit, slot, staying[it]) }) {
                Check.MORE -> Unit
                Check.CLEAR -> {
                    filteredOptions += option
                    nextOption()
                }
                Check.BLOCKED -> nextOption()
            }
        }

        private fun reserveOne() {
            if (memberIndex == usable.size) {
                phase = Phase.RECONCILE
                memberIndex = 0
                return
            }
            val (unit, options) = usable[memberIndex]
            if (optionIndex == options.size) return nextMember()
            val option = options[optionIndex]
            val slot = checkNotNull(option.slot)
            when (checkPair(reserved.size) { index ->
                val (other, assigned) = reserved[index]
                overlaps(unit, slot, other, checkNotNull(assigned.slot))
            }) {
                Check.MORE -> Unit
                Check.CLEAR -> {
                    reserved += unit to option
                    reservedIds += unit.unitId
                    nextMember()
                }
                Check.BLOCKED -> nextOption()
            }
        }

        private fun reconcileOne() {
            if (memberIndex < choices.size) {
                val unit = choices[memberIndex].first
                // A member without a destination keeps its current pose.
                if (unit.unitId in stayingIds || unit.unitId in reservedIds) return nextMember()
                when (checkPair(reserved.size) { index ->
                    val (other, planned) = reserved[index]
                    overlaps(other, checkNotNull(planned.slot), unit)
                }) {
                    Check.MORE -> Unit
                    Check.CLEAR -> nextMember()
                    Check.BLOCKED -> {
                        conflicts += unit
                        nextMember()
                    }
                }
                return
            }
            if (conflicts.isEmpty()) {
                destinations = reserved.associate { (unit, slot) -> unit.unitId to slot }
            } else {
                for (unit in conflicts) {
                    staying += unit
                    stayingIds += unit.unitId
                }
                usable.clear()
                reserved.clear()
                reservedIds.clear()
                conflicts.clear()
                phase = Phase.FILTER
                memberIndex = 0
            }
        }

        private fun nextMember() {
            memberIndex++
            optionIndex = 0
            pairIndex = 0
        }

        private fun nextOption() {
            optionIndex++
            pairIndex = 0
        }

        /** Evaluate no more than one pair under the caller's current work allowance. */
        private inline fun checkPair(size: Int, overlaps: (Int) -> Boolean): Check {
            if (pairIndex < size && overlaps(pairIndex++)) return Check.BLOCKED
            return if (pairIndex == size) Check.CLEAR else Check.MORE
        }
    }

    fun available(unit: MutableUnit, points: List<Position>, route: (Position) -> List<Position>?,
                  vacant: (GroupMoveSlot) -> Boolean): Sequence<GroupMoveSlot> =
        points.asSequence().mapNotNull { point -> route(point)?.let { slot(unit, point, it) } }.filter(vacant)

    fun overlaps(unit: MutableUnit, slot: GroupMoveSlot, other: MutableUnit, assigned: GroupMoveSlot): Boolean =
        UnitContact.shareLayer(unit, other) && UnitContact.posesOverlap(unit.definition.movementCollision,
            slot.position, slot.heading, other.definition.movementCollision, assigned.position, assigned.heading)

    fun overlaps(unit: MutableUnit, slot: GroupMoveSlot, other: MutableUnit): Boolean =
        UnitContact.shareLayer(unit, other) && UnitContact.posesOverlap(unit.definition.movementCollision,
            slot.position, slot.heading, other.definition.movementCollision, other.position, other.heading)

    fun slot(unit: MutableUnit, point: Position, path: List<Position>): GroupMoveSlot =
        GroupMoveSlot(point, arrivalHeading(unit, path), path)

    private fun arrivalHeading(unit: MutableUnit, path: List<Position>): Double {
        val shape = unit.definition.movementCollision
        if (shape !is MovementCollisionShape.Rectangle) return unit.heading
        var from = unit.position
        var heading = unit.heading
        for (point in path) {
            if (from != point) heading = ScreenPlane.desired(from, point)
            from = point
        }
        return heading
    }
}
