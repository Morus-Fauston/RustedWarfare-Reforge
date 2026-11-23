package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
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
    internal val maxRadiusByMovement: Map<MovementType, Double> = emptyMap(),
) {
    internal fun repairDue(step: Long): Boolean = (step - (completedStep ?: -1L) - 1L) % ROUTE_RETRY_TICKS == 0L
}

internal const val ROUTE_RETRY_TICKS = 60L

internal fun MutableUnit.wallRepairDue(step: Long): Boolean = terrainCorrectionTime >= 3.0 && moveTarget != null &&
    (landingMember?.group?.repairDue(step) ?: (step % ROUTE_RETRY_TICKS == 0L))

internal fun StepState.sharedWallRepairDue(step: Long): Boolean = units.values.any {
    it.landingMember != null && it.wallRepairDue(step)
}

/** Single-track landing ownership check: live parked members carry their exact order identity. */
internal fun GroupMoveRequest.hasParkedMember(unit: MutableUnit): Boolean =
    unit.ownerPlayerId == actor && unit.moveTarget == null && unit.unitId in members &&
        unit.landingMember?.let { it.parked && it.group === this } == true

internal data class GroupMoveSlot(val position: Position, val heading: Double, val path: List<Position>)

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
