package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MapDefinition
import java.util.PriorityQueue
import kotlin.math.ceil
import kotlin.math.floor

/** The selected members and target of one ordinary group order. */
class GroupMoveRequest internal constructor(
    internal val world: WorldHandle,
    internal val completedStep: Long?,
    internal val actor: String,
    internal val click: Position,
    internal val members: Set<String>,
    internal val sharedLanding: Boolean = false,
) {
    internal val trafficOrder = if (sharedLanding) members.sorted() else emptyList()

    internal fun repairDue(step: Long): Boolean = (step - (completedStep ?: -1L) - 1L) % ROUTE_RETRY_TICKS == 0L
}

internal const val ROUTE_RETRY_TICKS = 60L

internal fun MutableUnit.wallRepairDue(step: Long): Boolean = terrainCorrectionTime >= 3.0 && moveTarget != null &&
    (landingMember?.group?.repairDue(step) ?: (step % ROUTE_RETRY_TICKS == 0L))

internal fun StepState.sharedWallRepairDue(step: Long): Boolean = units.values.any {
    it.landingMember != null && it.wallRepairDue(step)
}

/** Arrival receipts carry the click for display. Live shared landings carry the order identity. */
internal fun GroupMoveRequest.hasParkedMember(unit: MutableUnit): Boolean {
    if (unit.ownerPlayerId != actor || unit.moveTarget != null || unit.unitId !in members) return false
    if (sharedLanding) return unit.landingMember?.let { it.parked && it.group === this } == true
    return unit.parkedReservationOwner === this
}

internal data class GroupMoveSlot(val position: Position, val heading: Double, val path: List<Position>)
internal data class GroupMoveReservation(val plan: GroupMoveRequest, val slot: GroupMoveSlot,
                                         val occupiedTicks: Long = 0, val needsReplacement: Boolean = false,
                                         val occupiedBy: String? = null)

internal object GroupMoveDestinations {
    /** Finite map lattice, emitted nearest first without constructing the whole map. */
    class Points(private val map: MapDefinition, private val click: Position,
                 private val spacing: Double = minOf(map.tileWidth, map.tileHeight) / 2.0) {
        private data class Offset(val x: Int, val y: Int) {
            val distanceSquared get() = x.toDouble() * x + y.toDouble() * y
        }
        private val minX = ceil(-click.x / spacing).toInt()
        private val maxX = floor((map.widthTiles.toDouble() * map.tileWidth - click.x) / spacing).toInt()
        private val minY = ceil(-click.y / spacing).toInt()
        private val maxY = floor((map.heightTiles.toDouble() * map.tileHeight - click.y) / spacing).toInt()
        private var pending = PriorityQueue(compareBy<Offset> { it.distanceSquared }.thenBy { it.x }.thenBy { it.y })
        private var seen = hashSetOf<Offset>()
        private var rollback: PathSearchRollback? = null

        init {
            if (minX <= maxX && minY <= maxY) add(Offset(0.coerceIn(minX, maxX), 0.coerceIn(minY, maxY)))
        }

        val exhausted get() = pending.isEmpty()

        fun fork(rollback: PathSearchRollback): Points = Points(map, click, spacing).also {
            it.pending = pending
            it.seen = seen
            it.rollback = rollback
        }

        /** Caller charges one work unit for this pop and its four bounded neighbours. */
        fun next(): Position? {
            val at = pending.poll() ?: return null
            rollback?.record { pending.add(at) }
            if (at.x > minX) add(Offset(at.x - 1, at.y))
            if (at.x < maxX) add(Offset(at.x + 1, at.y))
            if (at.y > minY) add(Offset(at.x, at.y - 1))
            if (at.y < maxY) add(Offset(at.x, at.y + 1))
            return Position(click.x + at.x * spacing, click.y + at.y * spacing)
        }

        private fun add(offset: Offset) {
            if (seen.add(offset)) {
                pending.add(offset)
                rollback?.record { check(pending.remove(offset)); check(seen.remove(offset)) }
            }
        }
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
        private var filteredOptions = mutableListOf<PlannedMove>()
        private var rollback: PathSearchRollback? = null
        var destinations: Map<String, PlannedMove>? = null
            private set

        fun fork(rollback: PathSearchRollback): Assignment = Assignment(choices).also {
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
            it.filteredOptions = filteredOptions
            it.rollback = rollback
            it.destinations = destinations
        }

        fun advance(budget: PathWorkBudget) {
            while (destinations == null && budget.take(pairWorkCost())) {
                when (phase) {
                    Phase.FILTER -> filterOne()
                    Phase.RESERVE -> reserveOne()
                    Phase.RECONCILE -> reconcileOne()
                }
            }
        }

        /** Broadphase and the single circle-distance predicate are constant
         * checks. Rectangle projection checks retain the full geometry charge,
         * as in route publication; no motion sweep is performed here.
         * Candidate order and the actual overlap predicates remain unchanged.
         */
        private fun pairWorkCost(): Int {
            fun cost(unit: MutableUnit, slot: GroupMoveSlot, other: MutableUnit,
                     assigned: GroupMoveSlot? = null): Int {
                if (!UnitContact.shareLayer(unit, other)) return 1
                if (unit.definition.contactBufferCollision is MovementCollisionShape.Circle &&
                    other.definition.contactBufferCollision is MovementCollisionShape.Circle) return 1
                val position = assigned?.position ?: other.position
                val reach = unit.definition.contactBufferCollision.boundingRadius +
                    other.definition.contactBufferCollision.boundingRadius
                return if (kotlin.math.abs(slot.position.x - position.x) > reach ||
                    kotlin.math.abs(slot.position.y - position.y) > reach) 1 else 8
            }
            return when (phase) {
                Phase.FILTER -> {
                    val (unit, options) = choices.getOrNull(memberIndex) ?: return 8
                    val slot = options.getOrNull(optionIndex)?.slot ?: return 8
                    val other = staying.getOrNull(pairIndex) ?: return 8
                    if (other === unit) 1 else cost(unit, slot, other)
                }
                Phase.RESERVE -> {
                    val (unit, options) = usable.getOrNull(memberIndex) ?: return 8
                    val slot = options.getOrNull(optionIndex)?.slot ?: return 8
                    val (other, assigned) = reserved.getOrNull(pairIndex) ?: return 8
                    cost(unit, slot, other, checkNotNull(assigned.slot))
                }
                Phase.RECONCILE -> {
                    val unit = choices.getOrNull(memberIndex)?.first ?: return 8
                    if (unit.unitId in stayingIds || unit.unitId in reservedIds) return 8
                    val (other, assigned) = reserved.getOrNull(pairIndex) ?: return 8
                    cost(other, checkNotNull(assigned.slot), unit)
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
                usable += unit to filteredOptions
                filteredOptions = mutableListOf()
                nextMember()
                return
            }
            val option = options[optionIndex]
            val slot = checkNotNull(option.slot)
            when (checkPair(staying.size) { staying[it] !== unit && overlaps(unit, slot, staying[it]) }) {
                Check.MORE -> Unit
                Check.CLEAR -> {
                    filteredOptions += option
                    val list = filteredOptions
                    rollback?.record { list.removeAt(list.lastIndex) }
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

    fun overlaps(unit: MutableUnit, slot: GroupMoveSlot, other: MutableUnit, assigned: GroupMoveSlot): Boolean =
        UnitContact.shareLayer(unit, other) && UnitContact.posesOverlap(unit.definition.contactBufferCollision,
            slot.position, slot.heading, other.definition.contactBufferCollision, assigned.position, assigned.heading)

    fun overlaps(unit: MutableUnit, slot: GroupMoveSlot, other: MutableUnit): Boolean =
        UnitContact.shareLayer(unit, other) && UnitContact.posesOverlap(unit.definition.contactBufferCollision,
            slot.position, slot.heading, other.definition.contactBufferCollision, other.position, other.heading)

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
