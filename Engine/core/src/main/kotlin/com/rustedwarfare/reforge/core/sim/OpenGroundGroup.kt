package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import kotlin.math.sqrt

/** Share the proof of reachability, not a guessed route. Equal circles in one known clear
 * rectangle have the same reachable candidates. Otherwise leave the constrained-member solver in charge. */
internal class OpenGroundGroup(private val input: MovePlanningInput, private val group: GroupMoveRequest) {
    private val members = input.members
    private val first = members.first()
    private val terrain = first.terrain
    private val buffer = first.body!!.definition.contactBufferCollision.boundingRadius
    private val pad = maxOf(terrain.tileWidth.toDouble(), terrain.tileHeight.toDouble(), buffer * (sqrt(members.size.toDouble()) + 2) * 2)
    private var left = group.click.x - pad
    private var top = group.click.y - pad
    private var right = group.click.x + pad
    private var bottom = group.click.y + pad
    private var validating = 0
    private var occupant = 0
    private var cell = 0
    private var member = 0
    private var pair = 0
    private var point: Position? = null
    private var proposal: PlannedMove? = null
    private var source = GroupMoveDestinations.Points(input.map, group.click)
    private var chosen = mutableListOf<Int>()
    private var used = mutableSetOf<Int>()
    private var scan = 0
    private var closest = -1
    private var distance = Double.POSITIVE_INFINITY
    private var reservations = mutableListOf<PlannedMove>()
    private var leftPair = 0
    private var rightPair = 1
    private var exchanged = false
    private var publishIndex = 0
    private var slotIndex = mutableMapOf<String, Int>()
    val pendingRoutes get() = publishIndex < members.size
    fun heldSlot(id: String): GroupMoveSlot? = if (member == members.size && leftPair >= chosen.lastIndex)
        slotIndex[id]?.let { reservations[it].slot } else null

    private var original = mutableListOf<Int>()
    private var rollback: PathSearchRollback? = null
    var rejected = false
        private set
    var outerDistance = 0.0
        private set

    fun fork(undo: PathSearchRollback): OpenGroundGroup = OpenGroundGroup(input, group).also {
        it.left = left; it.top = top; it.right = right; it.bottom = bottom
        it.validating = validating; it.occupant = occupant; it.cell = cell; it.member = member; it.pair = pair
        it.point = point; it.proposal = proposal; it.source = source.fork(undo); it.reservations = reservations
        it.chosen = chosen; it.used = used; it.scan = scan; it.closest = closest; it.distance = distance
        it.leftPair = leftPair; it.rightPair = rightPair; it.exchanged = exchanged
        it.slotIndex = slotIndex; it.publishIndex = publishIndex; it.original = original
        it.rollback = undo; it.rejected = rejected; it.outerDistance = outerDistance
    }

    private fun reject(): Nothing? { rejected = true; return null }

    /** The same swept area is the dependency for planning, repeated orders and live publication. */
    fun dependsOn(o: MovePlanningOccupant): Boolean {
        if (o.pose.layer != first.pose.layer) return false
        if (validating < members.size) return true // Bounds may still grow before the first occupancy check.
        val reach = buffer + o.body.definition.contactBufferCollision.boundingRadius
        fun near(p: Position) = p.x in left-reach..right+reach && p.y in top-reach..bottom+reach
        return near(o.pose.position) || o.reserved?.let { near(it.position) } == true
    }

    fun advance(budget: PathWorkBudget, plan: (MovePlanningMember, Position) -> PlannedMove?): Map<String, PlannedMove>? {
        val margin = maxOf(first.radius, first.pose.shape.boundingRadius)
        val width = terrain.widthTiles.toDouble() * terrain.tileWidth
        val height = terrain.heightTiles.toDouble() * terrain.tileHeight
        while (validating < members.size) {
            if (!budget.take(1)) return null
            val m = members[validating]; val body = m.body ?: return reject()
            if (m.hasBirthDelay || m.pose.shape !is MovementCollisionShape.Circle ||
                body.definition !== first.body!!.definition || m.pose.layer != first.pose.layer || m.terrain !== terrain ||
                m.radius != first.radius || m.hull != null ||
                !MapBounds.contains(width, height, m.start, margin) ||
                (validating > 0 && members[validating - 1].body!!.updateKey >= body.updateKey)) return reject()
            left = minOf(left, m.start.x); right = maxOf(right, m.start.x)
            top = minOf(top, m.start.y); bottom = maxOf(bottom, m.start.y); validating++
        }
        // The box includes all segments between all starts and candidates, padded by the travelling hull.
        while (occupant < input.occupants.size) {
            if (!budget.take(1)) return null
            if (dependsOn(input.occupants[occupant++])) return reject()
        }
        val x = MapBounds.cell((left - margin).coerceIn(0.0, width), terrain.tileWidth, terrain.widthTiles)
        val y = MapBounds.cell((top - margin).coerceIn(0.0, height), terrain.tileHeight, terrain.heightTiles)
        val cols = MapBounds.cell((right + margin).coerceIn(0.0, width), terrain.tileWidth, terrain.widthTiles) - x + 1
        val rows = MapBounds.cell((bottom + margin).coerceIn(0.0, height), terrain.tileHeight, terrain.heightTiles) - y + 1
        val count = cols.toLong() * rows
        // A distant, scattered selection should not scan a huge box to discover a shortcut.
        if (count > members.size.toLong() * 256) return reject()
        while (cell < count) {
            if (!budget.take(1)) return null
            if (terrain.isBlocked(x + cell % cols, y + cell / cols)) return reject()
            cell++
        }
        while (member < members.size) {
            val candidate = point ?: run {
                if (!budget.take()) return null
                val p = source.next() ?: return reject()
                outerDistance = sqrt(ScreenPlane.distanceSquared(group.click, p))
                if (p.x !in left..right || p.y !in top..bottom) return reject()
                p.also { point = it }
            }
            if (!MapBounds.contains(width, height, candidate, margin)) { point = null; continue }
            // Fill from the click outwards. Among equally capable members, offer a space to
            // the one with the shortest approach, then use stable update order to break ties.
            while (scan < members.size) {
                if (!budget.take(1)) return null
                val index = scan++
                if (index in used) continue
                val cost = ScreenPlane.distanceSquared(members[index].start, candidate)
                if (cost < distance) { closest = index; distance = cost }
            }
            val m = members[closest]; val body = m.body!!
            // Match positional capture in the ordinary solver. A reserved circle's heading is irrelevant.
            val at = if (ScreenPlane.distanceSquared(m.start, candidate) < 49.0) m.start else candidate
            val pose = GroupMoveSlot(at, m.pose.heading, emptyList())
            var blocked = false
            while (pair < reservations.size) {
                if (!budget.take(1)) return null
                val earlier = pair++
                if (GroupMoveDestinations.overlaps(body, pose, members[chosen[earlier]].body!!, reservations[earlier].slot!!)) {
                    blocked = true; break
                }
            }
            if (!blocked) {
                val route = proposal ?: (plan(m, candidate) ?: return null).also { proposal = it }
                if (route.nearest || route.slot?.position != at || route.slot.path.size != 1) return reject()
                val index = closest; val order = chosen; val assigned = used
                val indices = slotIndex; val id = m.action.unitId
                indices[id] = member; rollback?.record { indices.remove(id) }
                chosen += index; used += index
                val initial = original; initial += index; rollback?.record { initial.removeAt(initial.lastIndex) }
                rollback?.record { order.removeAt(order.lastIndex); assigned.remove(index) }
                reservations += route
                val list = reservations; rollback?.record { list.removeAt(list.lastIndex) }; member++
            }
            point = null; proposal = null; pair = 0; scan = 0; closest = -1; distance = Double.POSITIVE_INFINITY
        }
        // The spaces are fixed. In an obstacle-free rectangle crossing approaches can
        // be uncrossed by exchanging drivers, without moving any destination outward.
        fun length(i: Int, j: Int) = sqrt(ScreenPlane.distanceSquared(members[chosen[i]].start, reservations[j].slot!!.position))
        while (leftPair < chosen.lastIndex) {
            if (!budget.take()) return null
            val a = leftPair; val b = rightPair
            val aa = length(a, a); val bb = length(b, b); val ab = length(a, b); val ba = length(b, a)
            if (minOf(aa, bb, ab, ba) >= 7.0 && ab + ba + 1e-7 < aa + bb) {
                val order = chosen; val first = order[a]; val second = order[b]
                rollback?.record { order[a] = first; order[b] = second }
                val indices = slotIndex; val x = members[first].action.unitId; val y = members[second].action.unitId
                rollback?.record { indices[x] = a; indices[y] = b }
                indices[x] = b; indices[y] = a
                order[a] = second; order[b] = first; exchanged = true
            }
            if (++rightPair == chosen.size) { leftPair++; rightPair = leftPair + 1 }
            if (leftPair == chosen.lastIndex && exchanged) { exchanged = false; leftPair = 0; rightPair = 1 }
        }
        // Ownership is now final. A later driver's unfinished search must not hold a ready prefix.
        val ready = linkedMapOf<String, PlannedMove>()
        while (publishIndex < chosen.size) {
            if (chosen[publishIndex] != original[publishIndex]) {
                val index = publishIndex; val list = reservations; val prev = list[index]; val at = prev.slot!!.position
                val next = plan(members[chosen[index]], at) ?: break
                if (next.nearest || next.slot?.position != at || next.slot.path.size != 1) return reject()
                rollback?.record { list[index] = prev }; list[index] = next
            } else if (!budget.take(1)) break
            ready[members[chosen[publishIndex]].action.unitId] = reservations[publishIndex]
            publishIndex++
        }
        return ready.ifEmpty { null }
    }
}
