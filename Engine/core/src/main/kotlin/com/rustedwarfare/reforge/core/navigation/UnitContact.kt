package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.ScreenPlane
import com.rustedwarfare.reforge.core.sim.MapBounds
import com.rustedwarfare.reforge.core.sim.MutableUnit

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
    UnitLocomotion.isUnderwater(this, height) -> ContactLayer.SUBMERGED
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

    fun canPushMass(pusher: MutableUnit, blocker: MutableUnit): Boolean {
        val moving = positiveMass(pusher) ?: return false
        val fixed = positiveMass(blocker) ?: return false
        return fixed / moving <= PROVISIONAL_PUSH_MASS_RATIO
    }
    private const val MAX_PUSH_DEPTH = 8

    private data class Contact(val fraction: Double, val blocker: MutableUnit)
    data class Adjustment(val unit: MutableUnit, val position: Position)
    data class Translation(val position: Position, val displaced: List<Adjustment> = emptyList(),
                           val supported: Boolean = false)

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

    fun poseClearance(first: MovementCollisionShape, at: Position, heading: Double,
                      second: MovementCollisionShape, other: Position, otherHeading: Double): Double =
        Body(first, heading).clearance(Body(second, otherHeading), subtract(at, other))

    fun separatesExistingOverlap(first: MovementCollisionShape, at: Position, heading: Double, next: Position,
                                 second: MovementCollisionShape, other: Position, otherHeading: Double): Boolean {
        val body = Body(first, heading)
        val fixed = Body(second, otherHeading)
        val offset = subtract(at, other)
        return body.overlaps(fixed, offset) && body.entry(fixed, offset, subtract(next, at), 1.0) == 1.0
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
             spatialIndex: WorldSpatialIndex): Double {
        if (from == requested) return from
        val layer = layer(unit) ?: return requested
        val radius = boundingRadius(shape)
        val candidates = spatialIndex.queryBounds(
            SpatialLayer.fromContact(layer),
            unit.position.x - radius, unit.position.x + radius,
            unit.position.y - radius, unit.position.y + radius,
        )
        return turn(unit, shape, from, requested, candidates)
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

    fun canAdvanceAgainstTurn(unit: MutableUnit, shape: MovementCollisionShape.Rectangle, from: Double,
                              probe: Double, spatialIndex: WorldSpatialIndex, known: (MutableUnit) -> Boolean,
                              movable: (MutableUnit) -> Boolean): Boolean {
        val layer = layer(unit) ?: return false
        val radius = boundingRadius(shape)
        val candidates = spatialIndex.queryBounds(
            SpatialLayer.fromContact(layer),
            unit.position.x - radius, unit.position.x + radius,
            unit.position.y - radius, unit.position.y + radius,
        )
        return canAdvanceAgainstTurn(unit, shape, from, probe, candidates, known, movable)
    }

    fun canAdvanceAgainstTurn(unit: MutableUnit, shape: MovementCollisionShape.Rectangle, from: Double,
                              probe: Double, occupants: Collection<MutableUnit>, known: (MutableUnit) -> Boolean,
                              movable: (MutableUnit) -> Boolean): Boolean {
        var blockedByMovable = false
        for (other in occupants) {
            if (other === unit || !shareLayer(unit, other) || !known(other)) continue
            if (turn(unit, shape, from, probe, listOf(other)) == probe) continue
            if (!movable(other) || !canPushMass(unit, other)) return false
            blockedByMovable = true
        }
        return blockedByMovable
    }

    /** A root in the obstacle's rotated frame can round across the hard world-space boundary. */
    private fun safeTurnEndpoint(shape: MovementCollisionShape.Rectangle, other: MovementCollisionShape,
                                 offset: Position, otherHeading: Double, from: Double, limit: Double): Double {
        if (from == limit) return from
        val fixed = Body(other, otherHeading)
        val initial = Body(shape, from)
        val minimum = minOf(initial.clearance(fixed, offset), -4.0 * Math.ulp(maxOf(initial.reach, fixed.reach)))
        fun overlaps(heading: Double) = Body(shape, heading).clearance(fixed, offset) < minimum
        if (!overlaps(limit)) return limit
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
    class Translations(
        private val delta: Double,
        private val spatialIndex: WorldSpatialIndex,
        private val isSeparatingBirth: (MutableUnit) -> Boolean = { false },
        private val containsAdjustedSlots: (MutableUnit, MapDefinition, Position) -> Boolean = { _, _, _ -> true },
        private val movePending: (String) -> Boolean,
    ) {
        private val startingPositions = mutableMapOf<String, Position>()
        private val maximumSpeeds = mutableMapOf<String, Double>()
        private val settled = mutableSetOf<String>()
        private val pushLimits = mutableMapOf<String, Double>()
        private var gatheringSupport: Map<GatheringGoal, Set<String>>? = null
        private val hardLimited = mutableSetOf<String>()
        private val planningWait = mutableSetOf<String>()
        fun recordPlanningWait(unit: MutableUnit) { planningWait += unit.unitId }
        private val ownProgress = mutableSetOf<String>()
        private val pushedDisplacements = mutableMapOf<String, Position>()

        fun recordOwnMotion(unit: MutableUnit, before: Position, after: Position, turned: Boolean,
                            turnLimited: Boolean) {
            val translated = distance(before, after) > positionRoundoff(before, after)
            if (translated || turned) ownProgress += unit.unitId
            if (turnLimited) hardLimited += unit.unitId
        }

        fun recordPush(unit: MutableUnit, destination: Position) {
            val prior = pushedDisplacements[unit.unitId] ?: Position(0.0, 0.0)
            pushedDisplacements[unit.unitId] = Position(prior.x + destination.x - unit.position.x,
                prior.y + destination.y - unit.position.y)
        }

        /** Settle after every mover so a later push cannot masquerade as this unit's own progress. */
        fun finish(units: Collection<MutableUnit>, samples: MutableList<MotionStepSample>?, occupancy: LandingOccupancy? = null) {
            occupancy?.update(units)
            for (unit in units) {
                val active = unit.moveTarget != null && unit.motionContract?.tracksHardContactLimit == true
                val nextHardTicks = when {
                    !active -> 0
                    unit.unitId in planningWait -> unit.hardContactLimitedTicks
                    unit.unitId in hardLimited -> unit.hardContactLimitedTicks + 1
                    unit.unitId in pushedDisplacements && unit.unitId !in ownProgress -> unit.hardContactLimitedTicks
                    else -> 0
                }
                unit.updateNavigation { it.withHardContactLimitedTicks(nextHardTicks) }
            }
            if (samples != null) {
                val byId = units.associateBy { it.unitId }
                for (index in samples.indices) {
                    val sample = samples[index]
                    val body = byId.getValue(sample.unitId)
                    samples[index] = sample.copy(hardContactLimitedTicks = body.hardContactLimitedTicks,
                        landingClaim = body.landingMember?.claimed, landingIngress = null,
                        landingParked = body.landingMember?.parked == true,
                        pushedDisplacement = pushedDisplacements[sample.unitId] ?: Position(0.0, 0.0))
                }
            }
        }

        fun resolve(unit: MutableUnit, requested: Position, map: MapDefinition,
                    unitsById: Map<String, MutableUnit>, canPush: ((MutableUnit, MutableUnit) -> Boolean)?,
                    ground: MapDefinition, freeBlockedTurn: Boolean = false,
                    canBuffer: (MutableUnit) -> Boolean): Translation {
            val support = if (canPush == null) null else gatheringGoal(unit, movePending)?.let { goal ->
                val bodies = gatheringSupport ?: supportedGatherings(spatialIndex, movePending).also { gatheringSupport = it }
                bodies[goal]
            }
            if (support != null && unit.unitId in support) return Translation(unit.position, supported = true)
            if (unit.position == requested) return Translation(requested)
            val origin = startingPositions.getOrPut(unit.unitId) { unit.position }
            val ownLimit = pushLimits[unit.unitId]?.let { maxOf(maximumTravel(unit), it) }
            val target = if (ownLimit == null) requested else
                lastLegalPoint(unit.position, requested) { distance(origin, it) <= ownLimit + positionRoundoff(origin, it) }
            val moverMass = if (canPush == null || unit.unitId in settled) null else positiveMass(unit)
            val hardContacts = translationContacts(unit, target, spatialIndex, simultaneous = moverMass != null)
            if (hardContacts.isNotEmpty() || target != requested) hardLimited += unit.unitId
            val hardFraction = hardContacts.minOfOrNull { it.fraction } ?: 1.0
            val clipped = pointAt(unit.position, target, hardFraction)
            if (canPush == null || moverMass == null) return Translation(clipped)
            val softContact = if (hardContacts.isEmpty()) translationContact(unit, target, spatialIndex, buffered = true, included = canBuffer)
                ?: if (freeBlockedTurn) {
                    val contactLayer = layer(unit)
                    val bufRadius = boundingRadius(unit.definition.contactBufferCollision)
                    val nearby = if (contactLayer == null) emptyList() else spatialIndex.queryBounds(
                        SpatialLayer.fromContact(contactLayer),
                        target.x - bufRadius, target.x + bufRadius,
                        target.y - bufRadius, target.y + bufRadius,
                        useBufferRadius = true,
                    )
                    nearby.firstOrNull { other -> other !== unit && shareLayer(unit, other) && canBuffer(other) &&
                        distance(target, other.position) < distance(unit.position, other.position) &&
                        bufferPressure(unit, other, target) > 0.0
                    }?.let { Contact(0.0, it) }
                } else null else null
            val contacts = if (softContact == null) hardContacts else listOf(softContact)
            if (contacts.isEmpty()) return Translation(target)
            for (contact in contacts) {
                parkedPeerTangentSlide(unit, target, origin, contact.blocker, unitsById, map, ground)?.let { return it }
            }
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
            var pinnedByImmovable = false
            while (freeShare > 0.0) {
                // Every link sees the same share and set of connected bodies in a round.
                val share = minOf(freeShare, shareForPressure(pressureTravel, members))
                val nextRound = linkedMapOf<String, ChainLink>()
                for (member in members) {
                    val wanted = share - member.contactOffset
                    if (wanted <= 0.0) continue
                    val endpoint = offsetAlong(member.unit.position, drift, wanted)
                    val nextContacts = translationContacts(member.unit, endpoint, spatialIndex, ignoredIds = connected, simultaneous = true)
                    for (next in nextContacts) {
                        val offset = member.contactOffset + wanted * next.fraction
                        val ratio = transferRatio(member.unit, next.blocker)
                        if (ratio == null || member.depth == MAX_PUSH_DEPTH) {
                            if (offset < freeShare) {
                                freeShare = offset
                                pinned = member.takeIf { next.blocker.unitId !in settled && support?.contains(next.blocker.unitId) != true }
                                pinnedByImmovable = pinned != null && ratio == null
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

            val producerId = unit.productionOrigin?.producerId
            val chain = members.map {
                constrainedLink(it, map, sameProducer = producerId != null && it.unit.productionOrigin?.producerId == producerId)
            }
            val rootBody = Body(unit.definition.movementCollision, unit.heading)
            // A newer hull can be born inside its predecessor. Hold the newborn at
            // first contact while the existing hull clears that overlap under pressure.
            val separatingBirth = if (producerId != null && isSeparatingBirth(unit)) {
                chain.filter { member ->
                    val other = member.link.unit
                    other.productionOrigin?.producerId == producerId &&
                        rootBody.overlaps(member.body, subtract(unit.position, other.position))
                }
            } else emptyList()
            fun moverEnd(share: Double): Position = when {
                separatingBirth.isNotEmpty() -> clipped
                softContact == null -> offsetAlong(clipped, drift, share)
                else -> offsetAlong(target, drift, -members.sumOf { it.massRatio * it.travel(share) })
            }

            fun clearAt(share: Double): Boolean {
                val end = moverEnd(share)
                val moverTravel = distance(origin, end)
                val destinations = chain.map { member ->
                    offsetAlong(member.link.unit.position, drift, member.link.travel(share))
                }
                if (translationContact(unit, end, spatialIndex, ignoredIds = connected) != null) return false
                for ((i, member) in chain.withIndex()) {
                    val moved = member.link.unit
                    val destination = destinations[i]
                    if (destination != moved.position) {
                        val limit = maxOf(member.maxTravel, moverTravel)
                        if (distance(member.start, destination) > limit + positionRoundoff(member.start, destination) ||
                            !containsAdjustedSlots(moved, map, destination) ||
                            !clearsTerrain(moved, moved.definition.movementCollision, member.body, destination, map, member.terrain, member.grid, ground, unitsById) ||
                            translationContact(moved, destination, spatialIndex, ignoredIds = connected) != null) return false
                    }
                    if (!clearRelative(rootBody, unit.position, end, member.body, moved.position, destination,
                            requireClearEnd = unit.nearestMoveTarget != null && unit.definition.movementCollision is MovementCollisionShape.Rectangle,
                            allowExistingOverlap = member in separatingBirth))
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
            // Sliding under forward pressure can clear a grazing hull where an axial push cannot.
            val sideRelease = if (freeBlockedTurn && chain.size == 1 && pressureShare > 0.0)
                slide(unit, target, clipped, origin, drift, pressureShare, chain.single(),
                    unitsById, map, ground, chargeMass = true).takeIf { it.displaced.isNotEmpty() } else null
            val axialTranslation = if (axialAdjustments.isNotEmpty()) Translation(moverEnd(actualShare), axialAdjustments) else null
            val slideMember = pinned?.let { link -> chain.firstOrNull { it.link == link } } ?: chain.last()
            val chokedWallIdle = axialTranslation != null &&
                slideMember.link.unit.moveTarget == null && !movePending(slideMember.link.unit.unitId) &&
                distance(unit.position, axialTranslation.position) <= 1e-6
            val chokedSlide = if (sideRelease == null && softContact == null &&
                (axialTranslation == null || chokedWallIdle) &&
                (requestedShare > 0.0 || pinned != null)
            ) {
                slide(
                    unit, target, clipped, origin, drift, pressureShare, slideMember,
                    unitsById, map, ground, allowIdleSelfTravel = pinnedByImmovable,
                )
            } else null
            val translation = sideRelease ?: when {
                chokedSlide != null && chokedSlide.displaced.isNotEmpty() &&
                    (axialTranslation == null ||
                        distance(unit.position, chokedSlide.position) > distance(unit.position, axialTranslation.position) + 1e-6) ->
                    chokedSlide
                axialTranslation != null -> axialTranslation
                chokedSlide != null -> chokedSlide
                else -> Translation(clipped)
            }
            if (translation.displaced.isEmpty()) return translation
            settled += unit.unitId
            for (adjustment in translation.displaced) {
                settled += adjustment.unit.unitId
                pushLimits[adjustment.unit.unitId] = distance(origin, translation.position)
            }
            return translation
        }

        /**
         * Preserves stationary parked companions at a shared gathering: when a member of the same
         * order touches an already-parked peer off-axis, deflect along the open tangent instead of
         * shoving the parked peer out of its bay. Moving-traffic contactSlide was removed in ENG-P016-009A.
         */
        private fun parkedPeerTangentSlide(
            unit: MutableUnit, target: Position, origin: Position, other: MutableUnit,
            unitsById: Map<String, MutableUnit>, map: MapDefinition, ground: MapDefinition,
        ): Translation? {
            if (unit.moveTarget == null || other.moveTarget != null || movePending(other.unitId)) return null
            val bothCircles = unit.definition.movementCollision is MovementCollisionShape.Circle &&
                other.definition.movementCollision is MovementCollisionShape.Circle
            if (!bothCircles) return null
            val reservation = unit.groupMoveReservation
            val sameLandingParked = unit.landingMember != null &&
                other.landingMember?.group === unit.landingMember?.group &&
                other.landingMember?.parked == true
            val parkedPeer = reservation?.plan?.hasParkedMember(other) == true || sameLandingParked
            val drift = subtract(target, unit.position)
            val length = hypot(drift.x, drift.y)
            if (length == 0.0) return null
            val normal = subtract(unit.position, other.position)
            val separation = hypot(normal.x, normal.y)
            if (separation == 0.0) return null
            val normalDotDrift = normal.x * drift.x + normal.y * drift.y
            if (normalDotDrift >= 0.0) return null
            val headOnCos = -normalDotDrift / (separation * length)
            // Always tangent-slide around same-order parked companions; for other stationary circles,
            // tangent-slide only on near-perpendicular grazing contact (headOnCos < 0.25) so movers glide past
            // instead of initiating a stalled chain push against an entire parked cluster.
            if (!parkedPeer && headOnCos >= 0.25) return null
            var tangent = Position(-normal.y / separation, normal.x / separation)
            val aim = if (parkedPeer) checkNotNull(unit.moveTarget) else (unit.pathPoints.firstOrNull() ?: checkNotNull(unit.moveTarget))
            val toward = subtract(aim, unit.position)
            val projection = dot(tangent, toward)
            if (projection < 0.0 || (projection == 0.0 && tangent.x * -drift.y + tangent.y * drift.x < 0.0)) {
                tangent = negate(tangent)
            }
            val wanted = offsetAlong(unit.position, tangent, length)
            val body = Body(unit.definition.movementCollision, unit.heading)
            val terrain = UnitLocomotion.movement(unit).type
            val grid = HullTerrainGrid.authoritative(map, terrain)
            val limit = pushLimits[unit.unitId]?.let { maxOf(maximumTravel(unit), it) }
            val actual = lastLegalPoint(unit.position, wanted) { point ->
                (limit == null || distance(origin, point) <= limit + positionRoundoff(origin, point)) &&
                    containsAdjustedSlots(unit, map, point) &&
                    clearsTerrain(unit, unit.definition.movementCollision, body, point, map, terrain, grid, ground, unitsById) &&
                    translationContact(unit, point, spatialIndex) == null
            }
            return if (distance(unit.position, actual) > positionRoundoff(unit.position, actual)) Translation(actual) else null
        }

        private fun slide(unit: MutableUnit, target: Position, clipped: Position, origin: Position, drift: Position,
                          share: Double, member: ConstrainedLink,
                          unitsById: Map<String, MutableUnit>, map: MapDefinition, ground: MapDefinition,
                          chargeMass: Boolean = false, allowIdleSelfTravel: Boolean = false): Translation {
            val pushed = member.link.unit
            val length = hypot(drift.x, drift.y)
            if (length == 0.0) return Translation(clipped)
            val side = Position(-drift.y / length, drift.x / length)
            val shape = pushed.definition.movementCollision
            val effectiveMaxTravel = if (allowIdleSelfTravel) maximumTravel(pushed) else member.maxTravel
            val initialLimit = maxOf(effectiveMaxTravel, distance(origin, target))
            val stepMag = minOf(share * length, initialLimit)
            data class SlideCandidate(val wanted: Position, val charge: Boolean, val lockstepAlongWall: Boolean)
            val candidates = ArrayList<SlideCandidate>(3)
            if (shape is MovementCollisionShape.Circle && shape.radius > 0.0 &&
                pushed.moveTarget == null && !movePending(pushed.unitId) &&
                pushed.productionOrigin == null && stepMag > 0.0
            ) {
                val rawForward = offsetAlong(pushed.position, drift, stepMag / length)
                val wallSlid = CircularTerrain(shape.radius).resolveWithSlide(member.grid, pushed.position, rawForward)
                val wallVec = subtract(wallSlid, pushed.position)
                if (wallSlid != rawForward && dot(wallVec, drift) > 0.0 &&
                    hypot(wallVec.x, wallVec.y) > positionRoundoff(pushed.position, wallSlid)
                ) {
                    candidates += SlideCandidate(wallSlid, charge = true, lockstepAlongWall = true)
                }
            }
            candidates += SlideCandidate(offsetAlong(pushed.position, side, -stepMag), charge = chargeMass, lockstepAlongWall = false)
            candidates += SlideCandidate(offsetAlong(pushed.position, side, stepMag), charge = chargeMass, lockstepAlongWall = false)
            val mover = Body(unit.definition.movementCollision, unit.heading)
            val unitMotion = UnitLocomotion.movement(unit).type
            val unitTerrain = if (unitMotion != MovementType.AIR) unitMotion
                else if (unit.flightLandedInWater) MovementType.WATER else MovementType.LAND
            val unitGrid by lazy(LazyThreadSafetyMode.NONE) { HullTerrainGrid.authoritative(map, unitTerrain) }
            val moverTravelCap = pushLimits[unit.unitId]?.let { maxOf(maximumTravel(unit), it) } ?: maximumTravel(unit)
            for ((wanted, charge, lockstepAlongWall) in candidates) {
                val actual = lastLegalPoint(pushed.position, wanted) { candidate ->
                    distance(member.start, candidate) <= initialLimit + positionRoundoff(member.start, candidate) &&
                        containsAdjustedSlots(pushed, map, candidate) &&
                        clearsTerrain(pushed, shape, member.body, candidate, map, member.terrain, member.grid, ground, unitsById) &&
                        translationContact(pushed, candidate, spatialIndex) == null
                }
                if (distance(pushed.position, actual) <= positionRoundoff(pushed.position, actual)) continue
                fun settle(destination: Position): Position? {
                    if (lockstepAlongWall) {
                        val delta = subtract(destination, pushed.position)
                        val coEnd = Position(unit.position.x + delta.x, unit.position.y + delta.y)
                        val requireClearRect = unit.nearestMoveTarget != null &&
                            unit.definition.movementCollision is MovementCollisionShape.Rectangle
                        if (distance(origin, coEnd) <= moverTravelCap + positionRoundoff(origin, coEnd) &&
                            containsAdjustedSlots(unit, map, coEnd) &&
                            clearsTerrain(unit, unit.definition.movementCollision, mover, coEnd, map, unitTerrain, unitGrid, ground, unitsById) &&
                            translationContact(unit, coEnd, spatialIndex, shifted = Adjustment(pushed, destination)) == null &&
                            clearRelative(mover, unit.position, coEnd, member.body, pushed.position, destination,
                                requireClearEnd = requireClearRect)
                        ) {
                            return coEnd
                        }
                    }
                    val cost = if (charge) member.link.massRatio * distance(pushed.position, destination) / length else 0.0
                    val available = offsetAlong(target, drift, -cost)
                    val ahead = translationContact(unit, available, spatialIndex, shifted = Adjustment(pushed, destination))
                    val limit = pointAt(unit.position, available, ahead?.fraction ?: 1.0)
                    fun clear(end: Position) = clearRelative(mover, unit.position, end,
                        member.body, pushed.position, destination, requireClearEnd = true)
                    if (!clear(limit) && !clear(unit.position)) return null
                    return lastLegalPoint(unit.position, limit, ::clear)
                }
                val destination = lastLegalPoint(pushed.position, actual) { at ->
                    val end = settle(at)
                    end != null && distance(member.start, at) <= maxOf(effectiveMaxTravel, distance(origin, end)) +
                        positionRoundoff(member.start, at)
                }
                if (distance(pushed.position, destination) <= positionRoundoff(pushed.position, destination)) continue
                return Translation(settle(destination) ?: continue, listOf(Adjustment(pushed, destination)))
            }
            return Translation(clipped)
        }

        private fun constrainedLink(link: ChainLink, map: MapDefinition, sameProducer: Boolean = false): ConstrainedLink {
            val pushed = link.unit
            val motion = UnitLocomotion.movement(pushed).type
            val terrain = if (motion != MovementType.AIR) motion
                else if (pushed.flightLandedInWater) MovementType.WATER else MovementType.LAND
            val selfTravel = if (!sameProducer && pushed.moveTarget == null && pushed.birthRemaining <= 0f) 0.0 else maximumTravel(pushed)
            return ConstrainedLink(link, startingPositions.getOrPut(pushed.unitId) { pushed.position }, selfTravel,
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
        val target = unit.motionContract?.gatheringTarget ?: return null
        val layer = layer(unit) ?: return null
        return GatheringGoal(unit.ownerPlayerId, layer, target)
    }

    /** Opposing pressure on an occupied shared target supports a stationary cluster.
     * Closing the contact component over successive rings lets the next step release it when that support opens. */
    private fun supportedGatherings(spatialIndex: WorldSpatialIndex,
                                    movePending: (String) -> Boolean): Map<GatheringGoal, Set<String>> {
        val groups = linkedMapOf<GatheringGoal, MutableList<MutableUnit>>()
        for (sLayer in listOf(SpatialLayer.SURFACE, SpatialLayer.SUBMERGED)) {
            for (unit in spatialIndex.allInLayer(sLayer)) {
                val goal = gatheringGoal(unit, movePending) ?: continue
                groups.getOrPut(goal) { mutableListOf() }.add(unit)
            }
        }
        val result = mutableMapOf<GatheringGoal, Set<String>>()
        for ((goal, approaching) in groups) {
            if (approaching.size < 3) continue
            val sLayer = SpatialLayer.fromContact(goal.layer)
            val bodyCache = HashMap<MutableUnit, Body>()
            fun bodyOf(u: MutableUnit): Body = bodyCache.getOrPut(u) { Body(u.definition.movementCollision, u.heading) }
            val point = Body(MovementCollisionShape.Circle(0.0), 0.0)
            val nearGoal = spatialIndex.queryBounds(sLayer, goal.position.x, goal.position.x, goal.position.y, goal.position.y)
            val occupied = nearGoal.firstOrNull { unit ->
                if (unit.ownerPlayerId != goal.playerId || layer(unit) != goal.layer) return@firstOrNull false
                val body = bodyOf(unit)
                distance(unit.position, goal.position) <= body.reach &&
                    body.clearance(point, subtract(unit.position, goal.position)) <= 0.0
            } ?: continue
            val connected = mutableSetOf(occupied)
            val open = ArrayDeque<MutableUnit>()
            open.addLast(occupied)
            while (open.isNotEmpty()) {
                val from = open.removeFirst()
                val body = bodyOf(from)
                val margin = body.reach + 1.0
                val neighbors = spatialIndex.queryBounds(
                    sLayer,
                    from.position.x - margin, from.position.x + margin,
                    from.position.y - margin, from.position.y + margin,
                )
                for (other in neighbors) {
                    if (other in connected || other.ownerPlayerId != goal.playerId || layer(other) != goal.layer) continue
                    val outline = bodyOf(other)
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
                              second: Body, otherStart: Position, otherEnd: Position, requireClearEnd: Boolean = false,
                              allowExistingOverlap: Boolean = false): Boolean {
        val offset = subtract(start, otherStart)
        val endOffset = subtract(end, otherEnd)
        val drift = subtract(endOffset, offset)
        val roundoff = positionRoundoff(end, otherEnd)
        val entry = first.entry(second, offset, drift, 1.0)
        val startClearance = first.clearance(second, offset)
        val endClearance = first.clearance(second, endOffset)
        val minimum = if (allowExistingOverlap) minOf(0.0, startClearance) else 0.0
        // Monotonically non-worsening sub-ULP contact across IEEE-754 2^k exponent steps (e.g. y = 256.0 -> < 256.0):
        // never permits squeezing closer into a pinned blocker (`endClearance < startClearance` still fails), while
        // preventing co-moving pairs from freezing when `positionRoundoff(end, otherEnd)` halves across 2^k.
        val endClear = if (requireClearEnd) {
            !first.overlaps(second, endOffset)
        } else {
            endClearance >= minimum - roundoff ||
                (startClearance < -roundoff && startClearance >= -1e-11 && endClearance >= startClearance)
        }
        return endClear && (1.0 - entry) * hypot(drift.x, drift.y) <= roundoff
    }

    private fun offsetAlong(point: Position, drift: Position, fraction: Double): Position =
        Position(point.x + drift.x * fraction, point.y + drift.y * fraction)



    private fun pointAt(start: Position, end: Position, fraction: Double): Position = when (fraction) {
        0.0 -> start
        1.0 -> end
        else -> Position(start.x + (end.x - start.x) * fraction, start.y + (end.y - start.y) * fraction)
    }

    fun lastLegalPoint(start: Position, end: Position, legal: (Position) -> Boolean): Position {
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

    private fun translationContact(unit: MutableUnit, requested: Position, spatialIndex: WorldSpatialIndex,
                                   shifted: Adjustment? = null, ignoredIds: Set<String> = emptySet(),
                                   buffered: Boolean = false, included: ((MutableUnit) -> Boolean)? = null): Contact? =
        translationContacts(unit, requested, spatialIndex, shifted, ignoredIds, buffered, included).firstOrNull()

    private fun translationContacts(unit: MutableUnit, requested: Position, spatialIndex: WorldSpatialIndex,
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
        var bestSingle: Contact? = null
        var contacts: MutableList<Contact>? = null
        val minX = minOf(start.x, requested.x) - ownReach
        val maxX = maxOf(start.x, requested.x) + ownReach
        val minY = minOf(start.y, requested.y) - ownReach
        val maxY = maxOf(start.y, requested.y) + ownReach
        val shiftedUnit = shifted?.unit
        var sawShifted = false
        fun inspect(other: MutableUnit) {
            if (unit === other || other.unitId in ignoredIds) return
            if (other === shiftedUnit) sawShifted = true
            val otherCenter = if (shiftedUnit === other) shifted.position else other.position
            val offsetX = start.x - otherCenter.x
            val offsetY = start.y - otherCenter.y
            val otherShape = shape(other)
            val reach = ownReach + boundingRadius(otherShape)
            if (abs(offsetX) > reach + sweepX || abs(offsetY) > reach + sweepY) return
            if (layer(other) != layer || included != null && !included(other)) return
            val moving = body ?: Body(ownShape, unit.heading).also { body = it }
            val limit = moving.entry(Body(otherShape, other.heading), Position(offsetX, offsetY), drift, 1.0)
            if (limit >= 1.0) return
            if (!simultaneous) {
                val current = bestSingle
                if (current == null || limit < fraction ||
                    (limit == fraction && other.updateKey < current.blocker.updateKey)
                ) {
                    fraction = limit
                    bestSingle = Contact(limit, other)
                }
                return
            }
            val list = contacts ?: mutableListOf<Contact>().also { contacts = it }
            val tol = positionRoundoff(start, otherCenter)
            if (list.isEmpty()) {
                fraction = limit
                list += Contact(limit, other)
            } else if (limit < fraction) {
                fraction = limit
                // Unconditionally prune any earlier contacts outside tie tolerance of the new minimum fraction.
                list.removeIf { existing ->
                    val c = if (shiftedUnit === existing.blocker) shifted.position else existing.blocker.position
                    (existing.fraction - fraction) * length > positionRoundoff(start, c)
                }
                list += Contact(limit, other)
            } else if ((limit - fraction) * length <= tol) {
                list += Contact(limit, other)
            }
        }
        spatialIndex.forEachInBoundsUnordered(
            SpatialLayer.fromContact(layer),
            minX, maxX, minY, maxY,
            useBufferRadius = buffered,
        ) { other ->
            inspect(other)
            false
        }
        if (shiftedUnit != null && !sawShifted && shiftedUnit !== unit && shiftedUnit.unitId !in ignoredIds) {
            inspect(shiftedUnit)
        }
        if (!simultaneous) {
            return bestSingle?.let(::listOf) ?: emptyList()
        }
        val list = contacts ?: return emptyList()
        // Order-independent final tie filter against global minimum fraction.
        if (list.size > 1) {
            list.removeIf { existing ->
                val c = if (shiftedUnit === existing.blocker) shifted.position else existing.blocker.position
                (existing.fraction - fraction) * length > positionRoundoff(start, c)
            }
            list.sortBy { it.blocker.updateKey }
        }
        return list
    }

    private fun clearsTerrain(unit: MutableUnit, shape: MovementCollisionShape, body: Body, target: Position,
                              map: MapDefinition, terrain: MovementType, ordinaryGrid: HullTerrainGrid,
                              ground: MapDefinition, units: Map<String, MutableUnit>): Boolean {
        // A paid birth can still overlap its producer after Stop; pressure may only relieve that overlap.
        // Keep underlying terrain and all other buildings in the same authoritative sweep.
        val producerCells = unit.productionOrigin?.let { origin ->
            BuildingOverlapEscape.departingSeaFactoryCells(ground, unit, units[origin.producerId], target, shape,
                requireExitOrder = false)
        }.orEmpty()
        val grid = checkNotNull(BuildingOverlapEscape.leavingGrid(map, ground, terrain, producerCells, ordinaryGrid))
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

    fun withinSweptBounds(unit: MutableUnit, start: Position, end: Position, other: MutableUnit, pose: Position = other.position): Boolean {
        val radius = unit.definition.movementCollision.boundingRadius + other.definition.movementCollision.boundingRadius
        val dx = end.x - start.x
        val dy = end.y - start.y
        val x = pose.x - start.x
        val y = pose.y - start.y
        val lengthSquared = dx * dx + dy * dy
        val fraction = if (lengthSquared == 0.0) 0.0 else ((x * dx + y * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(x - fraction * dx, y - fraction * dy) <= Math.nextUp(radius)
    }

    fun routeAround(unit: MutableUnit, occupants: List<MutableUnit>): RouteOccupancy? {
        val contactLayer = layer(unit) ?: return null
        return routeAroundLayer(unit, occupants.filter { layer(it) == contactLayer })
    }

    fun routeAroundLayer(unit: MutableUnit, obstacles: List<MutableUnit>): RouteOccupancy? {
        if (obstacles.isEmpty()) return null
        val shape = unit.definition.movementCollision
        return object : RouteOccupancy {
          override val nearestBoundaryCount: Int get() = if (shape is MovementCollisionShape.Circle) obstacles.size else 0
          override fun nearestBoundary(index: Int, target: Position): Position? {
            val moving = shape as? MovementCollisionShape.Circle ?: return null
            val fixed = obstacles[index]
            val circle = fixed.definition.movementCollision as? MovementCollisionShape.Circle ?: return null
            val dx = target.x - fixed.position.x
            val dy = target.y - fixed.position.y
            val length = hypot(dx, dy)
            if (length == 0.0) return null
            // Stay outside the hard Minkowski boundary under binary64 rounding.
            var reach = moving.radius + circle.radius
            repeat(8) { reach = Math.nextUp(reach) }
            return Position(fixed.position.x + dx * reach / length, fixed.position.y + dy * reach / length)
          }
          override fun allows(start: Position, target: Position, fromHeading: Double, toHeading: Double): Boolean {
            val before = fromHeading
            val after = toHeading
            val drift = subtract(target, start)
            val moving = Body(shape, after)
            return obstacles.all { occupant ->
                val fixedShape = occupant.definition.movementCollision
                val offset = subtract(start, occupant.position)
                val heading = if (shape !is MovementCollisionShape.Rectangle || before == after) after
                    else when (fixedShape) {
                        is MovementCollisionShape.Circle -> RectangularTurn.againstCircle(shape, fixedShape.radius,
                            negate(offset), before, after)
                        is MovementCollisionShape.Rectangle -> RectangularTurn.againstRectangle(shape, fixedShape,
                            offset, occupant.heading, before, after)
                    }
                val fixedBody = Body(fixedShape, occupant.heading)
                heading == after && (if (drift.x == 0.0 && drift.y == 0.0) !moving.overlaps(fixedBody, offset)
                    else moving.entry(fixedBody, offset, drift, 1.0) == 1.0)
            }
          }
        }
    }

    fun canChangeHeight(unit: MutableUnit, requested: Double, spatialIndex: WorldSpatialIndex,
                        map: MapDefinition, buildings: DynamicBuildingGrid?): Boolean {
        val current = layer(unit)
        val target = layer(unit, requested)
        if (current == target) return true
        val pose = unit.position
        val body = Body(unit.definition.movementCollision, unit.heading)
        // A large step between air and submerged depth still needs clearance at the surface.
        val crossesSurface = current == null && target == ContactLayer.SUBMERGED || target == null && current == ContactLayer.SUBMERGED
        if (target == null && !crossesSurface) return true
        val candidates = ArrayList<MutableUnit>()
        if (target != null) {
            candidates.addAll(spatialIndex.queryBounds(
                SpatialLayer.fromContact(target),
                pose.x - body.reach, pose.x + body.reach,
                pose.y - body.reach, pose.y + body.reach,
            ))
        }
        if (crossesSurface && target != ContactLayer.SURFACE) {
            candidates.addAll(spatialIndex.queryBounds(
                SpatialLayer.SURFACE,
                pose.x - body.reach, pose.x + body.reach,
                pose.y - body.reach, pose.y + body.reach,
            ))
        }
        return canChangeHeight(unit, requested, candidates, map, buildings)
    }

    fun canChangeHeight(unit: MutableUnit, requested: Double, units: Collection<MutableUnit>,
                        map: MapDefinition, buildings: DynamicBuildingGrid?): Boolean {
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

    // Only rectangles project onto local axes.
    private val circleAxis = Position(1.0, 0.0)

    private class Body(private val shape: MovementCollisionShape, heading: Double) {
        val reach = boundingRadius(shape)
        private val forward = if (shape is MovementCollisionShape.Rectangle) ScreenPlane.displacement(heading, 1.0) else circleAxis
        private val right = if (shape is MovementCollisionShape.Rectangle) Position(-forward.y, forward.x) else circleAxis

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
