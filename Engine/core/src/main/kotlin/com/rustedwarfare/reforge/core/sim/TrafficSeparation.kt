package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Longitudinal separation for cooperative ground traffic, sampled before any unit moves. */
internal object TrafficSeparation {
    private const val CELL = 64.0

    // Soft steering can yield to the normal route and hard-contact simulation when crowded.
    // Each spatial query examines at most 81 cells and 64 occupants in deterministic order.
    internal class Visits(var cells: Long = 0, var bodies: Long = 0, var incomplete: Long = 0)
    private class Spatial<T>(val items: List<T>, position: (T) -> Position) {
        private fun cell(v: Double) = floor(v / CELL).toInt()
        private val grid = items.groupBy { item -> position(item).let { cell(it.x) to cell(it.y) } }
        fun query(loX: Double, hiX: Double, loY: Double, hiY: Double, visits: Visits): List<T>? {
            val x0 = cell(loX); val x1 = cell(hiX); val y0 = cell(loY); val y1 = cell(hiY)
            if (x1.toLong() - x0 >= 9 || y1.toLong() - y0 >= 9) {
                if (items.size <= 64) { visits.bodies += items.size; return items }
                visits.incomplete++; return null
            }
            val found = ArrayList<T>()
            for (x in x0..x1) for (y in y0..y1) {
                visits.cells++
                val bucket = grid[x to y] ?: continue
                if (bucket.size > 64 - found.size) { visits.incomplete++; return null }
                visits.bodies += bucket.size
                found.addAll(bucket)
            }
            return found
        }
    }

    /** Aim through the usable part of a straight mouth after traffic pushes a body off its route. */
    fun mouthAim(unit: MutableUnit, planned: Position): Position {
        if (UnitLocomotion.movement(unit).type != MovementType.LAND) return planned
        val entry = unit.passageEntry ?: return planned
        val shape = unit.definition.movementCollision as? MovementCollisionShape.Circle ?: return planned
        val mouth = entry.passage.mouths[entry.side]
        val outlet = entry.passage.mouths[1 - entry.side]
        if (!entry.admitted || mouth.horizontal != outlet.horizontal || mouth.outward == outlet.outward ||
            entry.passage.width >= 4 * shape.radius || outlet.depth(unit.position) > 0.0) return planned
        val depth = mouth.depth(unit.position)
        if (mouth.depth(planned) >= depth || mouth.depth(planned) >= 0.0) return planned
        val mid = (mouth.low + mouth.high) / 2.0
        val lateral = if (mouth.horizontal) unit.position.y else unit.position.x
        val clearance = (mouth.high - mouth.low) / 2.0 - shape.radius
        if (clearance < 0.0) return planned
        val along = when {
            depth >= 0.0 && abs(lateral - mid) > clearance -> mouth.plane + mouth.outward * shape.radius
            else -> if (mouth.horizontal) planned.x else planned.y
        }
        return if (mouth.horizontal) Position(along, mid) else Position(mid, along)
    }

    /** Insert a local bypass only when a parked friendly body lies on the current straight leg. */
    class ParkedNeighbors(units: Collection<MutableUnit>, private val visits: Visits,
                          private val known: (MutableUnit, MutableUnit) -> Boolean) {
        private val parked = units.filter { it.alive && it.carriedBy == null && it.attachment == null &&
            it.moveTarget == null && it.speed == 0.0 }
        private class View(val bodies: List<MutableUnit>) {
            val index = Spatial(bodies) { it.position }
            val largest = bodies.maxOfOrNull { it.definition.contactBufferCollision.boundingRadius } ?: 0.0
        }
        private val views = mutableMapOf<String, View>()

        fun bypass(unit: MutableUnit, target: Position, fits: (Position, Position) -> Boolean): List<Position> {
            if (parked.isEmpty()) return emptyList()
            val view = views.getOrPut(unit.ownerPlayerId) { View(parked.filter { known(unit, it) }) }
            if (view.bodies.isEmpty()) return emptyList()
            val index = view.index
            val largest = view.largest
            val start = unit.position
            val length = hypot(target.x - start.x, target.y - start.y)
            if (length == 0.0) return emptyList()
            val dx = (target.x - start.x) / length
            val dy = (target.y - start.y) / length
            val radius = unit.definition.contactBufferCollision.boundingRadius
            val reach = 5 * (radius + largest)
            var first = Double.POSITIVE_INFINITY
            var obstruction: MutableUnit? = null
            val nearby = index.query(start.x - reach, start.x + reach, start.y - reach, start.y + reach, visits)
                ?: return emptyList()
            for (peer in nearby) {
                    if (peer === unit || peer.ownerPlayerId != unit.ownerPlayerId || !UnitContact.shareLayer(unit, peer) ||
                        unit.landingMember?.group?.let { it === peer.landingMember?.group } == true) continue
                    val clearance = radius + peer.definition.contactBufferCollision.boundingRadius
                    val vx = peer.position.x - start.x
                    val vy = peer.position.y - start.y
                    val along = vx * dx + vy * dy
                    if (along > 0.0 && along < first && along < 4 * clearance && along + clearance < length &&
                        abs(vy * dx - vx * dy) < clearance) {
                        first = along
                        obstruction = peer
                    }
            }
            val peer = obstruction ?: return emptyList()
            val clearance = radius + peer.definition.contactBufferCollision.boundingRadius
            val side = if ((peer.position.y - start.y) * dx - (peer.position.x - start.x) * dy > 0.0) -1 else 1
            for (sign in listOf(side, -side)) {
                val midX = peer.position.x - sign * dy * (clearance + radius * 0.5)
                val midY = peer.position.y + sign * dx * (clearance + radius * 0.5)
                val entry = Position(midX - clearance * dx, midY - clearance * dy)
                val exit = Position(midX + clearance * dx, midY + clearance * dy)
                fun clear(from: Position, to: Position): Boolean {
                    val margin = radius + largest
                    val candidates = index.query(min(from.x,to.x)-margin, max(from.x,to.x)+margin,
                        min(from.y,to.y)-margin, max(from.y,to.y)+margin, visits) ?: return false
                    val vx = to.x-from.x; val vy = to.y-from.y
                    val len2 = vx*vx + vy*vy
                    for (other in candidates) {
                        if (other === unit || !UnitContact.shareLayer(unit, other) || !known(unit, other)) continue
                        val px = other.position.x-from.x; val py = other.position.y-from.y
                        val t = if (len2 == 0.0) 0.0 else ((px*vx+py*vy)/len2).coerceIn(0.0,1.0)
                        if (hypot(px-t*vx, py-t*vy) < radius+other.definition.contactBufferCollision.boundingRadius) return false
                    }
                    return fits(from,to)
                }
                if (clear(start, entry) && clear(entry, exit) && clear(exit, target)) return listOf(entry, exit)
            }
            return emptyList()
        }
    }

    private data class Driver(val unit: MutableUnit, val dx: Double, val dy: Double, val radius: Double,
                              val cruise: Double, val braking: Double)

    /** Intersect a crossing pair's finite-horizon reciprocal velocity half-plane with our forward speed axis. */
    private fun crossingSpeed(car: Driver, peer: Driver, x: Double, y: Double): Double {
        if (abs(car.dx * peer.dx + car.dy * peer.dy) >= 0.75 || car.unit.passageEntry != null ||
            peer.unit.passageEntry != null || car.unit.definition.movementCollision !is MovementCollisionShape.Circle ||
            peer.unit.definition.movementCollision !is MovementCollisionShape.Circle) return car.cruise
        val stream = car.unit.landingMember?.group ?: car.unit.groupMoveReservation?.plan
        val otherStream = peer.unit.landingMember?.group ?: peer.unit.groupMoveReservation?.plan
        if (stream != null && stream === otherStream) return car.cruise
        // A crossing conflict lies ahead of both vehicles. Ignore converging same-order paths
        // and peers which have already cleared the intersection and are turning to park.
        val determinant = car.dx * peer.dy - car.dy * peer.dx
        if ((x * peer.dy - y * peer.dx) / determinant <= 0.0 ||
            (x * car.dy - y * car.dx) / determinant <= 0.0) return car.cruise
        val distance2 = x * x + y * y
        val radius = car.unit.definition.contactBufferCollision.boundingRadius + peer.unit.definition.contactBufferCollision.boundingRadius
        if (distance2 <= radius * radius || distance2 > 16 * radius * radius) return car.cruise
        val a = ScreenPlane.displacement(car.unit.heading, car.unit.speed)
        val b = ScreenPlane.displacement(peer.unit.heading, peer.unit.speed)
        val vx = a.x - b.x
        val vy = a.y - b.y
        val inverseHorizon = max(car.cruise, peer.cruise) / (4 * radius)
        val wx = vx - x * inverseHorizon
        val wy = vy - y * inverseHorizon
        val dot = wx * x + wy * y
        val norm2 = wx * wx + wy * wy
        val nx: Double
        val ny: Double
        val correction: Double
        if (dot < 0 && dot * dot > radius * radius * norm2) {
            val norm = sqrt(norm2)
            nx = wx / norm
            ny = wy / norm
            correction = radius * inverseHorizon - norm
        } else {
            val leg = sqrt(distance2 - radius * radius)
            val side = if (x * wy - y * wx > 0.0) 1 else -1
            nx = -(side * y * leg + x * radius) / distance2
            ny = (side * x * leg - y * radius) / distance2
            correction = -(vx * nx + vy * ny)
        }
        val along = car.dx * nx + car.dy * ny
        if (along >= -1e-8) return car.cruise
        return ((a.x * nx + a.y * ny + 0.5 * correction) / along).coerceIn(0.0, car.cruise)
    }

    fun speedLimits(units: Collection<MutableUnit>, visits: Visits): Map<String, Double> {
        val drivers = units.mapNotNull { unit ->
            if (unit.moveTarget == null || unit.tasks.firstOrNull()?.kind != UnitTaskKind.MOVE ||
                !unit.alive || unit.carriedBy != null || unit.attachment != null) return@mapNotNull null
            val movement = UnitLocomotion.movement(unit)
            if (movement.type != MovementType.LAND || movement.moveSpeed <= 0.0) return@mapNotNull null
            // Landing claims already arbitrate the last approach between adjacent resting sites.
            val claim = unit.landingMember?.claimed
            if (claim != null && unit.moveTarget == claim && unit.pathPoints.size == 1) {
                val reach = 3 * unit.definition.contactBufferCollision.boundingRadius
                if (ScreenPlane.distanceSquared(unit.position, claim) <= reach * reach) return@mapNotNull null
            }
            val aim = unit.pathPoints.firstOrNull() ?: return@mapNotNull null
            val x = aim.x - unit.position.x
            val y = aim.y - unit.position.y
            val distance = hypot(x, y)
            if (distance < 1e-6) return@mapNotNull null
            Driver(unit, x / distance, y / distance, unit.definition.movementCollision.boundingRadius,
                movement.moveSpeed, movement.moveDecelerationSpeed * movement.moveSpeed)
        }
        if (drivers.size < 2) return emptyMap()
        val caps = mutableMapOf<String, Double>()
        for (allies in drivers.groupBy { it.unit.ownerPlayerId }.values) {
            val grid = Spatial(allies) { it.unit.position }
            val largest = allies.maxOf { it.radius }
            val largestBuffer = allies.maxOf { it.unit.definition.contactBufferCollision.boundingRadius }
            for (car in allies) {
                if (car.braking <= 0.0) continue
                val p = car.unit.position
                val reach = max(4 * (car.unit.definition.contactBufferCollision.boundingRadius + largestBuffer),
                    2 * (car.radius + largest) + car.cruise + car.cruise * car.cruise / (2 * car.braking))
                var cap = car.cruise
                val peers = grid.query(p.x - reach, p.x + reach, p.y - reach, p.y + reach, visits) ?: continue
                for (peer in peers) {
                        if (peer === car || peer.unit.ownerPlayerId != car.unit.ownerPlayerId || !UnitContact.shareLayer(car.unit, peer.unit)) continue
                        val vx = peer.unit.position.x - p.x
                        val vy = peer.unit.position.y - p.y
                        cap = min(cap, crossingSpeed(car, peer, vx, vy))
                        val entry = car.unit.passageEntry
                        val otherEntry = peer.unit.passageEntry
                        if (entry != null && otherEntry != null && entry.passage === otherEntry.passage &&
                            entry.side == otherEntry.side && entry.passage.width < 2 * (car.radius + peer.radius)) {
                            val mouth = entry.passage.mouths[entry.side]
                            val ownDepth = mouth.depth(p)
                            val otherDepth = mouth.depth(peer.unit.position)
                            if (ownDepth > -car.radius && otherDepth > -2 * peer.radius) {
                                // Use the same ordering for both drivers as they converge on a one-car mouth.
                                val peerFirst = otherEntry.waitingSince < entry.waitingSince ||
                                    otherEntry.waitingSince == entry.waitingSince && peer.unit.updateKey < car.unit.updateKey
                                if (!peerFirst && otherDepth >= ownDepth - car.radius) continue
                                if (peerFirst && otherDepth <= ownDepth + car.radius) {
                                    val room = max(0.0, ownDepth - max(0.0, otherDepth) - car.radius - peer.radius)
                                    cap = min(cap, sqrt(2 * car.braking * room))
                                    continue
                                }
                            }
                        }
                        val along = vx * car.dx + vy * car.dy
                        val across = abs(vx * car.dy - vy * car.dx)
                        val hard = car.radius + peer.radius
                        // Longitudinal braking follows a leader. A vehicle abreast can take its own forward step.
                        val peerAcross = abs(vx * peer.dy - vy * peer.dx)
                        val peerForward = vx * peer.dx + vy * peer.dy
                        val abreast = entry == null && otherEntry == null && along <= across && abs(peerForward) <= peerAcross &&
                            vx * vx + vy * vy <= hard * hard * 1.21
                        if (along <= 0.0 || abreast || across >= hard) continue
                        val peerAlong = -vx * peer.dx - vy * peer.dy
                        if (peerAlong > 0.0 && (along < peerAlong - 1e-6 ||
                            abs(along - peerAlong) <= 1e-6 && car.unit.unitId < peer.unit.unitId)) continue
                        val gap = along - sqrt(max(0.0, hard * hard - across * across))
                        val alignment = max(0.0, car.dx * peer.dx + car.dy * peer.dy)
                        val leaderSpeed = abs(peer.unit.speed) * alignment
                        val room = max(0.0, gap - min(car.radius, peer.radius) * 0.5 - car.cruise)
                        cap = min(cap, sqrt(leaderSpeed * leaderSpeed + 2.0 * car.braking * room))
                }
                if (cap < car.cruise) caps[car.unit.unitId] = cap
            }
        }
        return caps
    }
}