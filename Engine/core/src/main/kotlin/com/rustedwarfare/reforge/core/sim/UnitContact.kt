package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementProfile
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.TransportProfile
import com.rustedwarfare.reforge.core.content.UnitDefinition
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.PI

internal enum class ContactLayer { SURFACE, SUBMERGED }

private fun UnitDefinition.contactLayerAt(height: Double): ContactLayer? = when {
    CombatTargetQuery.isUnderwater(this, height) -> ContactLayer.SUBMERGED
    movement.type == MovementType.AIR &&
        (height >= 4.0 || movement.flight == null && amphibiousJet == null && transport != TransportProfile.NATIVE_DROPSHIP) -> null
    else -> ContactLayer.SURFACE
}

/** Whether the given pose height supports body contact for this definition. */
fun UnitDefinition.hasContactLayerAt(height: Double): Boolean = contactLayerAt(height) != null

/** Physical contacts use the current authoritative poses, independently of route knowledge. */
internal object UnitContact {
    // ENG-P014-003 starting value for the planned playtest.
    private const val PROVISIONAL_PUSH_MASS_RATIO = 8.0
    private const val MAX_PUSH_DEPTH = 8

    private data class Contact(val fraction: Double, val blocker: MutableUnit)
    data class Adjustment(val unit: MutableUnit, val position: Position)
    data class Translation(val position: Position, val displaced: List<Adjustment> = emptyList(),
                           val legacyYield: Boolean = false, val supported: Boolean = false)

    class YieldRequest(val collisionMap: MapDefinition, val stepIndex: Long, val unitsById: Map<String, MutableUnit>,
                       val canYield: (MutableUnit) -> Boolean)

    fun layer(unit: MutableUnit, height: Double = unit.height): ContactLayer? = when {
        !unit.alive || unit.carriedBy != null || unit.attachment != null || unit.definition.building != null -> null
        else -> unit.definition.contactLayerAt(height)
    }

    fun shareLayer(first: MutableUnit, second: MutableUnit): Boolean =
        layer(first)?.let { it == layer(second) } == true

    fun posesOverlap(first: MovementCollisionShape, at: Position, heading: Double,
                     second: MovementCollisionShape, other: Position, otherHeading: Double): Boolean {
        val offset = subtract(at, other)
        val reach = boundingRadius(first) + boundingRadius(second)
        return abs(offset.x) <= reach && abs(offset.y) <= reach &&
            Body(first, heading).overlaps(Body(second, otherHeading), offset)
    }

    fun poseFits(shape: MovementCollisionShape, at: Position, heading: Double, map: HullTerrainGrid): Boolean {
        val inside = when (shape) {
            is MovementCollisionShape.Circle -> CircularTerrain(shape.radius).contains(map, at)
            is MovementCollisionShape.Rectangle -> RectangularTerrain(shape, heading).contains(map, at)
        }
        return inside && !overlapsTerrain(Body(shape, heading), at, map)
    }

    fun inContact(unit: MutableUnit, other: MutableUnit): Boolean {
        val layer = layer(unit) ?: return false
        if (layer(other) != layer) return false
        val a = Body(unit.definition.movementCollision, unit.heading)
        val b = Body(other.definition.movementCollision, other.heading)
        val roundoff = 4.0 * Math.ulp(maxOf(abs(unit.position.x), abs(unit.position.y),
            abs(other.position.x), abs(other.position.y), a.reach, b.reach))
        return a.clearance(b, subtract(unit.position, other.position)) <= roundoff
    }

    fun turn(unit: MutableUnit, shape: MovementCollisionShape.Rectangle, from: Double, requested: Double,
             units: Collection<MutableUnit>): Double {
        if (from == requested) return from
        val layer = layer(unit) ?: return requested
        val radius = boundingRadius(shape)
        var heading = requested
        for (other in units) {
            if (unit === other || layer(other) != layer) continue
            val offset = subtract(other.position, unit.position)
            val otherShape = other.definition.movementCollision
            val reach = radius + boundingRadius(otherShape)
            if (abs(offset.x) > reach || abs(offset.y) > reach) continue
            val limit = when (otherShape) {
                is MovementCollisionShape.Circle -> RectangularTurn.againstCircle(shape, otherShape.radius, offset, from, heading)
                is MovementCollisionShape.Rectangle -> RectangularTurn.againstRectangle(shape, otherShape, negate(offset), other.heading, from, heading)
            }
            heading = safeTurnEndpoint(shape, otherShape, negate(offset), other.heading, from, limit)
            if (heading == from) return from
        }
        return heading
    }

    /** A root in the obstacle's rotated frame can round across the hard world-space boundary. */
    private fun safeTurnEndpoint(shape: MovementCollisionShape.Rectangle, other: MovementCollisionShape,
                                 offset: Position, otherHeading: Double, from: Double, limit: Double): Double {
        if (from == limit) return from
        val fixed = Body(other, otherHeading)
        fun overlaps(heading: Double) = Body(shape, heading).overlaps(fixed, offset)
        if (!overlaps(limit) || overlaps(from)) return limit
        val change = ScreenPlane.difference(limit, from)
        fun headingAt(fraction: Double) = if (fraction == 0.0) from else ScreenPlane.normalize(from + change * fraction)
        var safe = 0.0
        var blocked = 1.0
        repeat(52) {
            val middle = (safe + blocked) * 0.5
            if (overlaps(headingAt(middle))) blocked = middle else safe = middle
        }
        return headingAt(safe)
    }

    /** Per-step settlement in the simulation's existing motion update order. */
    class Translations(private val delta: Double, private val movePending: (String) -> Boolean) {
        private val startingPositions = mutableMapOf<String, Position>()
        private val maximumSpeeds = mutableMapOf<String, Double>()
        private val settled = mutableSetOf<String>()
        private val pushLimits = mutableMapOf<String, Double>()
        private var gatheringSupport: Map<GatheringGoal, Set<String>>? = null
        private val hardLimited = mutableSetOf<String>()
        private val ownProgress = mutableSetOf<String>()
        private val pushedDisplacements = mutableMapOf<String, Position>()

        fun recordOwnMotion(unit: MutableUnit, before: Position, after: Position, turned: Boolean,
                            turnLimited: Boolean) {
            if (distance(before, after) > positionRoundoff(before, after) || turned) ownProgress += unit.unitId
            if (turnLimited) hardLimited += unit.unitId
        }

        fun recordPush(unit: MutableUnit, destination: Position) {
            val prior = pushedDisplacements[unit.unitId] ?: Position(0.0, 0.0)
            pushedDisplacements[unit.unitId] = Position(prior.x + destination.x - unit.position.x,
                prior.y + destination.y - unit.position.y)
        }

        /** Settle after every mover so a later push cannot masquerade as this unit's own progress. */
        fun finish(units: Collection<MutableUnit>, samples: MutableList<MotionStepSample>?) {
            for (unit in units) {
                val active = unit.moveTarget != null && unit.tasks.firstOrNull()?.kind == UnitTaskKind.MOVE
                unit.hardContactLimitedTicks = when {
                    !active -> 0
                    unit.unitId in hardLimited -> unit.hardContactLimitedTicks + 1
                    unit.unitId in pushedDisplacements && unit.unitId !in ownProgress -> unit.hardContactLimitedTicks
                    else -> 0
                }
            }
            if (samples != null) {
                val byId = units.associateBy { it.unitId }
                for (index in samples.indices) {
                    val sample = samples[index]
                    samples[index] = sample.copy(hardContactLimitedTicks = byId.getValue(sample.unitId).hardContactLimitedTicks,
                        pushedDisplacement = pushedDisplacements[sample.unitId] ?: Position(0.0, 0.0))
                }
            }
        }

        fun resolve(unit: MutableUnit, requested: Position, units: Collection<MutableUnit>, map: MapDefinition,
                    unitsById: Map<String, MutableUnit>, canPush: ((MutableUnit, MutableUnit) -> Boolean)?,
                    yielding: YieldRequest? = null, canBuffer: (MutableUnit) -> Boolean): Translation {
            val support = if (canPush == null) null else gatheringGoal(unit, movePending)?.let { goal ->
                val bodies = gatheringSupport ?: supportedGatherings(units, movePending).also { gatheringSupport = it }
                bodies[goal]
            }
            if (support != null && unit.unitId in support) return Translation(unit.position, supported = true)
            if (unit.position == requested) return Translation(requested)
            val origin = startingPositions.getOrPut(unit.unitId) { unit.position }
            val ownLimit = pushLimits[unit.unitId]?.let { maxOf(maximumTravel(unit), it) }
            val target = if (ownLimit == null) requested else
                lastLegalPoint(unit.position, requested) { distance(origin, it) <= ownLimit + positionRoundoff(origin, it) }
            val moverMass = if (canPush == null || unit.unitId in settled) null else positiveMass(unit)
            val hardContacts = translationContacts(unit, target, units, simultaneous = moverMass != null)
            if (hardContacts.isNotEmpty() || target != requested) hardLimited += unit.unitId
            val hardFraction = hardContacts.minOfOrNull { it.fraction } ?: 1.0
            val clipped = pointAt(unit.position, target, hardFraction)
            val hardBlocker = hardContacts.firstOrNull()?.blocker
            if (hardBlocker != null && yielding != null && yielding.canYield(hardBlocker)) {
                sideShift(unit, target, hardBlocker, units, yielding)?.let {
                    hardLimited -= unit.unitId
                    return Translation(target, listOf(it), legacyYield = true)
                }
            }
            if (canPush == null || moverMass == null) return Translation(clipped)
            val softContact = if (hardContacts.isEmpty()) translationContact(unit, target, units, buffered = true, included = canBuffer) else null
            val contacts = if (softContact == null) hardContacts else listOf(softContact)
            if (contacts.isEmpty()) return Translation(target)
            fun transferRatio(pusher: MutableUnit, blocker: MutableUnit): Double? {
                val ratio = positiveMass(blocker)?.div(moverMass) ?: return null
                if (blocker.unitId in settled || support?.contains(blocker.unitId) == true || !canPush(pusher, blocker)) return null
                return ratio.takeIf { it <= PROVISIONAL_PUSH_MASS_RATIO }
            }
            val pressureTravel = if (softContact == null) 1.0 - hardFraction
                else (1.0 - softContact.fraction) * bufferPressure(unit, softContact.blocker, target)
            if (pressureTravel == 0.0) return Translation(clipped)

            val drift = subtract(target, unit.position)
            val members = contacts.mapTo(mutableListOf()) { contact ->
                val ratio = transferRatio(unit, contact.blocker) ?: return Translation(clipped)
                ChainLink(contact.blocker, 0.0, 1, ratio)
            }
            val connected = members.mapTo(mutableSetOf(unit.unitId)) { it.unit.unitId }
            var freeShare = Double.POSITIVE_INFINITY
            var pinned: ChainLink? = null
            while (freeShare > 0.0) {
                // Every link sees the same share and set of connected bodies in a round.
                val share = minOf(freeShare, shareForPressure(pressureTravel, members))
                val nextRound = linkedMapOf<String, ChainLink>()
                for (member in members) {
                    val wanted = share - member.contactOffset
                    if (wanted <= 0.0) continue
                    val endpoint = offsetAlong(member.unit.position, drift, wanted)
                    val nextContacts = translationContacts(member.unit, endpoint, units, ignoredIds = connected, simultaneous = true)
                    for (next in nextContacts) {
                        val offset = member.contactOffset + wanted * next.fraction
                        val ratio = transferRatio(member.unit, next.blocker)
                        if (ratio == null || member.depth == MAX_PUSH_DEPTH) {
                            if (offset < freeShare) {
                                freeShare = offset
                                pinned = member.takeIf { next.blocker.unitId !in settled && support?.contains(next.blocker.unitId) != true }
                            }
                            continue
                        }
                        val link = ChainLink(next.blocker, offset, member.depth + 1, ratio)
                        val previous = nextRound[next.blocker.unitId]
                        if (previous == null || offset < previous.contactOffset) nextRound[next.blocker.unitId] = link
                    }
                }
                val additions = nextRound.values.filter { it.contactOffset <= freeShare }
                if (additions.isEmpty()) break
                for (link in additions) {
                    connected += link.unit.unitId
                    members += link
                }
            }
            val pressureShare = shareForPressure(pressureTravel, members)
            val requestedShare = minOf(freeShare, pressureShare)

            val chain = members.map { constrainedLink(it, map) }
            val rootBody = Body(unit.definition.movementCollision, unit.heading)
            fun moverEnd(share: Double): Position = if (softContact == null) offsetAlong(clipped, drift, share)
                else offsetAlong(target, drift, -members.sumOf { it.massRatio * it.travel(share) })

            fun clearAt(share: Double): Boolean {
                val end = moverEnd(share)
                val moverTravel = distance(origin, end)
                val destinations = chain.map { member ->
                    offsetAlong(member.link.unit.position, drift, member.link.travel(share))
                }
                if (translationContact(unit, end, units, ignoredIds = connected) != null) return false
                for ((i, member) in chain.withIndex()) {
                    val moved = member.link.unit
                    val destination = destinations[i]
                    if (destination != moved.position) {
                        val limit = maxOf(member.maxTravel, moverTravel)
                        if (distance(member.start, destination) > limit + positionRoundoff(member.start, destination) ||
                            !UnitAttachments.containsAdjustedSlots(unitsById, moved, map, destination) ||
                            !clearsTerrain(moved, moved.definition.movementCollision, member.body, destination, map, member.terrain, member.grid) ||
                            translationContact(moved, destination, units, ignoredIds = connected) != null) return false
                    }
                    if (!clearRelative(rootBody, unit.position, end, member.body, moved.position, destination))
                        return false
                    for (j in 0 until i) {
                        val earlier = chain[j]
                        if (!clearRelative(earlier.body, earlier.link.unit.position, destinations[j], member.body, moved.position, destination))
                            return false
                    }
                }
                return true
            }

            val actualShare = lastLegalFraction(requestedShare, ::clearAt)
            val axialAdjustments = chain.mapNotNull { member ->
                val moved = member.link.unit
                val destination = offsetAlong(moved.position, drift, member.link.travel(actualShare))
                if (distance(moved.position, destination) <= positionRoundoff(moved.position, destination)) null
                else Adjustment(moved, destination)
            }
            val translation = if (axialAdjustments.isNotEmpty()) Translation(moverEnd(actualShare), axialAdjustments)
                else if (softContact != null || (requestedShare == 0.0 && pinned == null)) Translation(clipped)
                else slide(unit, target, clipped, origin, drift, pressureShare,
                    (pinned?.let { link -> chain.firstOrNull { it.link == link } } ?: chain.last()), units, unitsById, map)
            if (translation.displaced.isEmpty()) return translation
            settled += unit.unitId
            for (adjustment in translation.displaced) {
                settled += adjustment.unit.unitId
                pushLimits[adjustment.unit.unitId] = distance(origin, translation.position)
            }
            return translation
        }

        private fun slide(unit: MutableUnit, target: Position, clipped: Position, origin: Position, drift: Position,
                          share: Double, member: ConstrainedLink, units: Collection<MutableUnit>,
                          unitsById: Map<String, MutableUnit>, map: MapDefinition): Translation {
            val pushed = member.link.unit
            val length = hypot(drift.x, drift.y)
            val limit = maxOf(member.maxTravel, distance(origin, clipped))
            val lateral = minOf(share * length, limit)
            val side = Position(-drift.y / length, drift.x / length)
            val shape = pushed.definition.movementCollision
            for (direction in listOf(-1.0, 1.0)) {
                val wanted = offsetAlong(pushed.position, side, lateral * direction)
                val actual = lastLegalPoint(pushed.position, wanted) { candidate ->
                    distance(member.start, candidate) <= limit + positionRoundoff(member.start, candidate) &&
                        UnitAttachments.containsAdjustedSlots(unitsById, pushed, map, candidate) &&
                        clearsTerrain(pushed, shape, member.body, candidate, map, member.terrain, member.grid) &&
                        translationContact(pushed, candidate, units) == null
                }
                if (distance(pushed.position, actual) <= positionRoundoff(pushed.position, actual)) continue
                val adjustment = Adjustment(pushed, actual)
                val ahead = translationContact(unit, target, units, shifted = adjustment)
                return Translation(pointAt(unit.position, target, ahead?.fraction ?: 1.0), listOf(adjustment))
            }
            return Translation(clipped)
        }

        private fun constrainedLink(link: ChainLink, map: MapDefinition): ConstrainedLink {
            val pushed = link.unit
            val motion = UnitLocomotion.movement(pushed).type
            val terrain = if (motion != MovementType.AIR) motion
                else if (pushed.flightLandedInWater) MovementType.WATER else MovementType.LAND
            return ConstrainedLink(link, startingPositions.getOrPut(pushed.unitId) { pushed.position }, maximumTravel(pushed),
                Body(pushed.definition.movementCollision, pushed.heading), terrain, HullTerrainGrid.authoritative(map, terrain))
        }

        fun maximumSpeed(unit: MutableUnit, movement: MovementProfile): Double =
            maximumSpeeds.getOrPut(unit.unitId) { UnitLocomotion.effectiveMaximumSpeed(unit, movement) }

        private fun maximumTravel(unit: MutableUnit): Double =
            maximumSpeed(unit, UnitLocomotion.movement(unit)) * delta
    }

    private data class GatheringGoal(val playerId: String, val layer: ContactLayer, val position: Position)

    private fun gatheringGoal(unit: MutableUnit, movePending: (String) -> Boolean): GatheringGoal? {
        if (movePending(unit.unitId)) return null
        val target = unit.tasks.firstOrNull()?.takeIf { it.kind == UnitTaskKind.MOVE && !it.factoryExit }?.target ?: return null
        val layer = layer(unit) ?: return null
        return GatheringGoal(unit.ownerPlayerId, layer, target)
    }

    /** Opposing pressure on an occupied shared target supports a stationary cluster.
     * Closing the contact component over successive rings lets the next step release it when that support opens. */
    private fun supportedGatherings(units: Collection<MutableUnit>,
                                    movePending: (String) -> Boolean): Map<GatheringGoal, Set<String>> {
        val groups = units.mapNotNull { unit -> gatheringGoal(unit, movePending)?.let { it to unit } }
            .groupBy({ it.first }, { it.second })
        val result = mutableMapOf<GatheringGoal, Set<String>>()
        for ((goal, approaching) in groups) {
            if (approaching.size < 3) continue
            val candidates = units.asSequence().filter { it.ownerPlayerId == goal.playerId && layer(it) == goal.layer }
                .associateWith { Body(it.definition.movementCollision, it.heading) }
            val point = Body(MovementCollisionShape.Circle(0.0), 0.0)
            val occupied = candidates.keys.firstOrNull { unit ->
                val body = Body(unit.definition.movementCollision, unit.heading)
                distance(unit.position, goal.position) <= body.reach &&
                    body.clearance(point, subtract(unit.position, goal.position)) <= 0.0
            } ?: continue
            val connected = mutableSetOf(occupied)
            val open = ArrayDeque<MutableUnit>()
            open.addLast(occupied)
            while (open.isNotEmpty()) {
                val from = open.removeFirst()
                val body = candidates.getValue(from)
                for ((other, outline) in candidates) {
                    if (other in connected) continue
                    val offset = subtract(from.position, other.position)
                    val reach = body.reach + outline.reach
                    val roundoff = positionRoundoff(from.position, other.position)
                    if (abs(offset.x) > reach + roundoff || abs(offset.y) > reach + roundoff ||
                        body.clearance(outline, offset) > roundoff) continue
                    connected += other
                    open.addLast(other)
                }
            }
            val bearings = approaching.asSequence().filter { it in connected && it.position != goal.position }
                .map { atan2(it.position.y - goal.position.y, it.position.x - goal.position.x) }.sorted().toList()
            if (bearings.size < 3) continue
            var prior = bearings.last() - 2.0 * PI
            val surrounded = bearings.all { bearing ->
                (bearing - prior <= PI).also { prior = bearing }
            }
            if (surrounded) result[goal] = connected.mapTo(mutableSetOf()) { it.unitId }
        }
        return result
    }

    private data class ChainLink(val unit: MutableUnit, val contactOffset: Double, val depth: Int, val massRatio: Double) {
        fun travel(share: Double): Double = (share - contactOffset).coerceAtLeast(0.0)
    }
    private data class ConstrainedLink(val link: ChainLink, val start: Position, val maxTravel: Double, val body: Body,
                                       val terrain: MovementType, val grid: HullTerrainGrid)

    private fun positiveMass(unit: MutableUnit): Double? = unit.definition.mass?.takeIf { it.isFinite() && it > 0.0 }

    private fun shareForPressure(pressure: Double, members: List<ChainLink>): Double {
        var weightedPressure = pressure
        var relativeMass = 1.0
        var share = pressure
        for (member in members.sortedBy { it.contactOffset }) {
            if (member.contactOffset >= share) break
            // Each member consumes mass-weighted travel only once the previous links close its gap.
            weightedPressure += member.massRatio * member.contactOffset
            relativeMass += member.massRatio
            share = weightedPressure / relativeMass
        }
        return share
    }

    private fun bufferPressure(unit: MutableUnit, blocker: MutableUnit, target: Position): Double {
        val first = unit.definition.movementCollision
        val second = blocker.definition.movementCollision
        val offset = subtract(target, blocker.position)
        val hardGap = Body(first, unit.heading).clearance(Body(second, blocker.heading), offset)
        val bufferGap = Body(unit.definition.contactBufferCollision, unit.heading)
            .clearance(Body(blocker.definition.contactBufferCollision, blocker.heading), offset)
        if (bufferGap >= 0.0) return 0.0
        return -bufferGap / (hardGap.coerceAtLeast(0.0) - bufferGap)
    }

    private fun clearRelative(first: Body, start: Position, end: Position,
                              second: Body, otherStart: Position, otherEnd: Position): Boolean {
        val offset = subtract(start, otherStart)
        val endOffset = subtract(end, otherEnd)
        val drift = subtract(endOffset, offset)
        val roundoff = positionRoundoff(end, otherEnd)
        val entry = first.entry(second, offset, drift, 1.0)
        return first.clearance(second, endOffset) >= -roundoff &&
            (1.0 - entry) * hypot(drift.x, drift.y) <= roundoff
    }

    private fun offsetAlong(point: Position, drift: Position, fraction: Double): Position =
        Position(point.x + drift.x * fraction, point.y + drift.y * fraction)



    private fun pointAt(start: Position, end: Position, fraction: Double): Position = when (fraction) {
        0.0 -> start
        1.0 -> end
        else -> Position(start.x + (end.x - start.x) * fraction, start.y + (end.y - start.y) * fraction)
    }

    private fun lastLegalPoint(start: Position, end: Position, legal: (Position) -> Boolean): Position {
        if (start == end) return end
        return pointAt(start, end, lastLegalFraction(1.0) { legal(pointAt(start, end, it)) })
    }

    private fun lastLegalFraction(maximum: Double, legal: (Double) -> Boolean): Double {
        if (maximum == 0.0 || legal(maximum)) return maximum
        var clear = 0.0
        var blocked = maximum
        repeat(48) {
            val fraction = (clear + blocked) * .5
            if (legal(fraction)) clear = fraction else blocked = fraction
        }
        return clear
    }

    private fun distance(a: Position, b: Position): Double = hypot(a.x - b.x, a.y - b.y)

    private fun positionRoundoff(a: Position, b: Position): Double =
        4.0 * Math.ulp(maxOf(abs(a.x), abs(a.y), abs(b.x), abs(b.y)))

    private fun translationContact(unit: MutableUnit, requested: Position, units: Collection<MutableUnit>,
                                   shifted: Adjustment? = null, ignoredIds: Set<String> = emptySet(),
                                   buffered: Boolean = false, included: ((MutableUnit) -> Boolean)? = null): Contact? =
        translationContacts(unit, requested, units, shifted, ignoredIds, buffered, included).firstOrNull()

    private fun translationContacts(unit: MutableUnit, requested: Position, units: Collection<MutableUnit>,
                                    shifted: Adjustment? = null, ignoredIds: Set<String> = emptySet(),
                                    buffered: Boolean = false, included: ((MutableUnit) -> Boolean)? = null,
                                    simultaneous: Boolean = false): List<Contact> {
        val start = unit.position
        if (start == requested) return emptyList()
        val layer = layer(unit) ?: return emptyList()
        fun shape(other: MutableUnit) =
            if (buffered) other.definition.contactBufferCollision else other.definition.movementCollision
        val ownShape = shape(unit)
        val ownReach = boundingRadius(ownShape)
        var body: Body? = null
        val drift = subtract(requested, start)
        val sweepX = abs(drift.x)
        val sweepY = abs(drift.y)
        val length = if (simultaneous) hypot(drift.x, drift.y) else 0.0
        var fraction = 1.0
        val contacts = mutableListOf<Contact>()
        for (other in units) {
            if (unit === other || other.unitId in ignoredIds) continue
            val otherCenter = if (shifted?.unit === other) shifted.position else other.position
            val offsetX = start.x - otherCenter.x
            val offsetY = start.y - otherCenter.y
            val otherShape = shape(other)
            val reach = ownReach + boundingRadius(otherShape)
            if (abs(offsetX) > reach + sweepX || abs(offsetY) > reach + sweepY) continue
            if (layer(other) != layer || included != null && !included(other)) continue
            val moving = body ?: Body(ownShape, unit.heading).also { body = it }
            val limit = moving.entry(Body(otherShape, other.heading), Position(offsetX, offsetY), drift,
                if (simultaneous) 1.0 else fraction)
            if (limit >= 1.0) continue
            val tied = simultaneous && contacts.isNotEmpty() &&
                abs(limit - fraction) * length <= positionRoundoff(start, otherCenter)
            if (tied) {
                fraction = minOf(fraction, limit)
                contacts += Contact(limit, other)
            } else if (limit < fraction) {
                fraction = limit
                contacts.clear()
                contacts += Contact(limit, other)
                if (!simultaneous && fraction == 0.0) break
            }
        }
        return contacts
    }

    private fun sideShift(mover: MutableUnit, requested: Position, blocker: MutableUnit,
                          units: Collection<MutableUnit>, yielding: YieldRequest): Adjustment? {
        val map = yielding.collisionMap
        val drift = subtract(requested, mover.position)
        val length = hypot(drift.x, drift.y)
        val side = Position(-drift.y / length, drift.x / length)
        val moverBody = Body(mover.definition.movementCollision, mover.heading)
        val blockerShape = blocker.definition.movementCollision
        val blockerBody = Body(blockerShape, blocker.heading)
        val width = moverBody.radiusAlong(side) + blockerBody.radiusAlong(side)
        val offset = dot(subtract(blocker.position, mover.position), side)
        val maximumShift = minOf(map.tileWidth, map.tileHeight).toDouble()
        val motion = UnitLocomotion.movement(blocker).type
        val surfaceTerrain = if (motion != MovementType.AIR) motion
            else if (blocker.flightLandedInWater) MovementType.WATER else MovementType.LAND
        val grid = HullTerrainGrid.authoritative(map, surfaceTerrain)
        val contactLayer = layer(blocker)
        fun position(delta: Double) =
            Position(blocker.position.x + side.x * delta, blocker.position.y + side.y * delta)

        fun legalShift(delta: Double): Adjustment? {
            val end = position(delta)
            if (!UnitAttachments.containsAdjustedSlots(yielding.unitsById, blocker, map, end)) return null
            if (!clearsTerrain(blocker, blockerShape, blockerBody, end, map, surfaceTerrain, grid)) return null
            if (translationContact(blocker, end, units) != null || units.any { other ->
                other !== blocker && layer(other) == contactLayer &&
                    blockerBody.overlaps(Body(other.definition.movementCollision, other.heading), subtract(end, other.position))
            }) return null
            val shift = Adjustment(blocker, end)
            return shift.takeIf { translationContact(mover, requested, units, it) == null }
        }

        val fullClearance = listOf(width - offset, -width - offset).sortedBy { abs(it) }
        // Leave clearance for the current direction where the stopped unit can stay there.
        for (delta in fullClearance) {
            if (delta != 0.0 && abs(delta) <= maximumShift) legalShift(delta)?.let { return it }
        }
        fun clearsActualStep(delta: Double): Boolean {
            val fromBlocker = subtract(mover.position, position(delta))
            return !moverBody.overlaps(blockerBody, fromBlocker) &&
                moverBody.entry(blockerBody, fromBlocker, drift, 1.0) == 1.0
        }
        val shorter = fullClearance.mapNotNull { projected ->
            val boundary = projected.coerceIn(-maximumShift, maximumShift)
            if (boundary == 0.0 || !clearsActualStep(boundary)) return@mapNotNull null
            var blockedFraction = 0.0
            var clearFraction = 1.0
            repeat(48) {
                val middle = (blockedFraction + clearFraction) / 2.0
                if (clearsActualStep(boundary * middle)) clearFraction = middle
                else blockedFraction = middle
            }
            boundary * clearFraction
        }.sortedBy { abs(it) }
        for (delta in shorter) {
            legalShift(delta)?.let { return it }
        }
        return null
    }

    private fun clearsTerrain(unit: MutableUnit, shape: MovementCollisionShape, body: Body, target: Position,
                              map: MapDefinition, terrain: MovementType, grid: HullTerrainGrid): Boolean {
        val traversed = when (shape) {
            is MovementCollisionShape.Circle -> if (shape.radius > 0.0)
                CircularTerrain(shape.radius).resolve(grid, unit.position, target) == target
            else MapBounds.contains(grid.width, grid.height, target, 0.0) &&
                TerrainMotion.resolve(map, unit.position, target, movementType = terrain) == target
            is MovementCollisionShape.Rectangle ->
                RectangularTerrain(shape, unit.heading).resolve(grid, unit.position, target) == target
        }
        return traversed && !overlapsTerrain(body, target, grid)
    }

    fun routeAround(unit: MutableUnit, occupants: List<MutableUnit>): RouteOccupancy? {
        val contactLayer = layer(unit) ?: return null
        val obstacles = occupants.filter { layer(it) == contactLayer }
        if (obstacles.isEmpty()) return null
        val shape = unit.definition.movementCollision
        return RouteOccupancy { start, target, before, after ->
            val drift = subtract(target, start)
            val moving = Body(shape, after)
            obstacles.all { occupant ->
                val fixedShape = occupant.definition.movementCollision
                val offset = subtract(start, occupant.position)
                val heading = if (shape !is MovementCollisionShape.Rectangle || before == after) after
                    else when (fixedShape) {
                        is MovementCollisionShape.Circle -> RectangularTurn.againstCircle(shape, fixedShape.radius,
                            negate(offset), before, after)
                        is MovementCollisionShape.Rectangle -> RectangularTurn.againstRectangle(shape, fixedShape,
                            offset, occupant.heading, before, after)
                    }
                heading == after && moving.entry(Body(fixedShape, occupant.heading), offset, drift, 1.0) == 1.0
            }
        }
    }

    fun canChangeHeight(unit: MutableUnit, requested: Double, units: Collection<MutableUnit>,
                        map: MapDefinition, buildings: BuildingTerrain?): Boolean {
        val current = layer(unit)
        val target = layer(unit, requested)
        if (current == target) return true
        val pose = unit.position
        val body = Body(unit.definition.movementCollision, unit.heading)
        // A large step between air and submerged depth still needs clearance at the surface.
        val crossesSurface = current == null && target == ContactLayer.SUBMERGED || target == null && current == ContactLayer.SUBMERGED
        if (target == null && !crossesSurface) return true
        for (other in units) {
            if (other === unit) continue
            val otherLayer = layer(other) ?: continue
            if (otherLayer != target && !(crossesSurface && otherLayer == ContactLayer.SURFACE)) continue
            val offset = subtract(pose, other.position)
            val shape = other.definition.movementCollision
            val reach = body.reach + boundingRadius(shape)
            if (abs(offset.x) > reach || abs(offset.y) > reach) continue
            if (body.overlaps(Body(shape, other.heading), offset)) return false
        }
        val buildingCells = buildings?.cells.orEmpty()
        if (current != null && (target != ContactLayer.SURFACE || buildingCells.isEmpty())) return true
        val movement = if (unit.amphibiousState == null) MovementType.LAND else MovementType.WATER
        val grid = HullTerrainGrid.authoritative(buildings?.collisionMap ?: map, movement)
        return !overlapsTerrain(body, pose, grid) { x, y -> current == null || (x to y) in buildingCells }
    }

    private fun overlapsTerrain(body: Body, center: Position, grid: HullTerrainGrid,
                                includeCell: (Int, Int) -> Boolean = { _, _ -> true }): Boolean {
        val cell = Body(MovementCollisionShape.Rectangle(grid.tileHeight.toDouble(), grid.tileWidth.toDouble()), 0.0)
        return grid.anyBlocked(center.x - body.reach, center.x + body.reach, center.y - body.reach, center.y + body.reach) { x, y ->
            val offset = Position(center.x - (x + 0.5) * grid.tileWidth, center.y - (y + 0.5) * grid.tileHeight)
            includeCell(x, y) && body.overlaps(cell, offset)
        }
    }

    fun boundingRadius(shape: MovementCollisionShape): Double = shape.boundingRadius

    private class Body(private val shape: MovementCollisionShape, heading: Double) {
        val reach = boundingRadius(shape)
        private val forward = ScreenPlane.displacement(heading, 1.0)
        private val right = Position(-forward.y, forward.x)

        fun entry(other: Body, offset: Position, drift: Position, limit: Double): Double {
            val rect = shape as? MovementCollisionShape.Rectangle
            val otherRect = other.shape as? MovementCollisionShape.Rectangle
            if (rect != null && otherRect != null) return rectangleEntry(other, rect, otherRect, offset, drift, limit)
            val rounding = 4.0 * Math.ulp(maxOf(reach, other.reach))
            return when {
                rect != null -> sweepCircle(local(negate(offset)), local(negate(drift)),
                    other.reach - rounding, rect, limit)
                otherRect != null -> sweepCircle(other.local(offset), other.local(drift),
                    reach - rounding, otherRect, limit)
                else -> sweepCircle(offset, drift, reach + other.reach - rounding, null, limit)
            }
        }

        fun overlaps(other: Body, offset: Position): Boolean {
            return clearance(other, offset) < -4.0 * Math.ulp(maxOf(reach, other.reach))
        }

        fun clearance(other: Body, offset: Position): Double {
            val rect = shape as? MovementCollisionShape.Rectangle
            val otherRect = other.shape as? MovementCollisionShape.Rectangle
            if (rect != null && otherRect != null) {
                return listOf(forward, right, other.forward, other.right).maxOf { axis ->
                    abs(dot(offset, axis)) - project(rect, axis) - other.project(otherRect, axis)
                }
            }
            return when {
                rect != null -> roundedRectangleClearance(local(offset), rect.length / 2.0, rect.width / 2.0, other.reach)
                otherRect != null -> roundedRectangleClearance(other.local(offset), otherRect.length / 2.0, otherRect.width / 2.0, reach)
                else -> hypot(offset.x, offset.y) - (reach + other.reach)
            }
        }

        private fun local(point: Position) = Position(dot(point, forward), dot(point, right))

        private fun project(rect: MovementCollisionShape.Rectangle, axis: Position) =
            abs(dot(axis, forward)) * rect.length / 2.0 + abs(dot(axis, right)) * rect.width / 2.0

        fun radiusAlong(axis: Position): Double = when (shape) {
            is MovementCollisionShape.Circle -> shape.radius
            is MovementCollisionShape.Rectangle -> project(shape, axis)
        }

        private fun rectangleEntry(other: Body, rect: MovementCollisionShape.Rectangle,
                                   otherRect: MovementCollisionShape.Rectangle, offset: Position,
                                   drift: Position, limit: Double): Double {
            var enter = 0.0
            var leave = limit
            var leastOverlap = Double.POSITIVE_INFINITY
            var leastOverlapDoesNotIncrease = false
            var exitsOnAnAxis = false
            for (axis in listOf(forward, right, other.forward, other.right)) {
                val position = dot(offset, axis)
                val projectedDrift = dot(drift, axis)
                val speed = if (abs(projectedDrift) <= 4.0 * Math.ulp(maxOf(abs(drift.x), abs(drift.y)))) 0.0 else projectedDrift
                val reach = project(rect, axis) + other.project(otherRect, axis)
                val interior = reach - 4.0 * Math.ulp(reach)
                val overlap = interior - abs(position)
                val movesOut = speed != 0.0 && (position == 0.0 || (position > 0.0) == (speed > 0.0))
                val overlapDoesNotIncrease = speed == 0.0 || movesOut
                if (overlap < leastOverlap) leastOverlapDoesNotIncrease = overlapDoesNotIncrease
                if (overlap == leastOverlap) leastOverlapDoesNotIncrease = leastOverlapDoesNotIncrease || overlapDoesNotIncrease
                leastOverlap = minOf(overlap, leastOverlap)
                exitsOnAnAxis = exitsOnAnAxis || movesOut
                if (speed == 0.0) {
                    if (overlap <= 0.0) return limit
                    continue
                }
                val low = (-interior - position) / speed
                val high = (interior - position) / speed
                enter = maxOf(enter, minOf(low, high))
                leave = minOf(leave, maxOf(low, high))
                if (enter >= leave) return limit
            }
            return if (leastOverlap > 0.0 && leastOverlapDoesNotIncrease && exitsOnAnAxis) limit else enter
        }
    }

    private fun sweepCircle(start: Position, drift: Position, radius: Double,
                            rect: MovementCollisionShape.Rectangle?, limit: Double): Double {
        // This circle pair only gets farther apart. Avoid a spurious entry at a rounded tangent.
        if (rect == null && dot(start, drift) >= 0.0) return limit
        val halfLength = (rect?.length ?: 0.0) / 2.0
        val halfWidth = (rect?.width ?: 0.0) / 2.0
        return roundRectangleEntry(start, drift, radius.coerceAtLeast(0.0), -halfLength, halfLength, -halfWidth, halfWidth, limit)
    }

    private fun dot(a: Position, b: Position) = a.x * b.x + a.y * b.y
    private fun subtract(a: Position, b: Position) = Position(a.x - b.x, a.y - b.y)
    private fun negate(point: Position) = Position(-point.x, -point.y)
}
