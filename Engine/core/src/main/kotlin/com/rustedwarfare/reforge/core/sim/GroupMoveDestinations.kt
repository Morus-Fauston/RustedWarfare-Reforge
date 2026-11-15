package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MapDefinition

/** A selection's proposals, validated again as each ordinary Move is submitted. */
class GroupMovePlan internal constructor(
    internal val world: WorldHandle,
    internal val completedStep: Long?,
    internal val actor: String,
    internal val click: Position,
    internal val members: Set<String>,
    internal val destinations: Map<String, GroupMoveSlot>,
)

internal data class GroupMoveSlot(val position: Position, val heading: Double, val path: List<Position>)
internal data class GroupMoveReservation(val plan: GroupMovePlan, val slot: GroupMoveSlot, val occupiedTicks: Long = 0)

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

    fun assign(members: List<MutableUnit>, points: List<Position>,
               route: (MutableUnit, Position) -> List<Position>?,
               vacant: (MutableUnit, GroupMoveSlot) -> Boolean): Map<String, GroupMoveSlot> {
        val choices = members.map { unit ->
            unit to available(unit, points, { route(unit, it) }, { vacant(unit, it) }).toList()
        }
        val priority = compareBy<Pair<MutableUnit, List<GroupMoveSlot>>> { it.second.size }.thenBy { it.first.updateKey }
        val staying = linkedMapOf<String, MutableUnit>()
        while (true) {
            val usable = choices.map { (unit, options) ->
                unit to options.filter { slot ->
                    staying.values.none { other -> other !== unit && overlaps(unit, slot, other) }
                }
            }.sortedWith(priority)
            val reserved = mutableListOf<Pair<MutableUnit, GroupMoveSlot>>()
            for ((unit, candidates) in usable) {
                val slot = candidates.firstOrNull { candidate ->
                    reserved.none { (other, assigned) -> overlaps(unit, candidate, other, assigned) }
                } ?: continue
                reserved += unit to slot
            }
            val assigned = reserved.associate { (unit, slot) -> unit.unitId to slot }
            // A member without a destination keeps its existing pose. Reconcile any
            // reservations depending on it leaving, using the already computed paths.
            val leftInPlace = members.filter { it.unitId !in assigned && it.unitId !in staying }
            val conflicts = leftInPlace.filter { other ->
                reserved.any { (unit, slot) -> overlaps(unit, slot, other) }
            }
            if (conflicts.isEmpty()) return assigned
            for (other in conflicts) staying[other.unitId] = other
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
