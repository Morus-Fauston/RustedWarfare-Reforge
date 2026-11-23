package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.AttackMovementProfile
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementProfile
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.path.PlanningSegmentClearance
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2D local velocity avoidance and differential kinematics deep module (ADR-012 / ENG-P016-006).
 *
 * Encapsulates:
 * 1. Fog-of-war isolated deterministic Top-K neighbor sampling from the step-start frozen spatial index.
 * 2. Dimensionless adaptive 2D Boids soft separation scaled by unit hard radius, max speed, turn rate,
 *    and braking distance, with smooth lateral attenuation inside true single-lane passages.
 * 3. Full 2D ORCA half-plane linear programming with uniform clockwise/right-hand micro-bias on head-on
 *    and collinear approaches (no |dot| >= 0.75 filter, no 1D axial projection).
 * 4. Hard static terrain wall half-planes and ORCA 3D LP minimal-penetration fallback when dynamic
 *    neighbor half-planes have no strict 2D feasible solution.
 * 5. Short-horizon reachable steering sector constraint, anti-jitter heading hysteresis, and cosine
 *    smooth throttle modulation mapped to differential steering kinematics.
 */
internal object LocomotionModule {
    private const val EPS = 1e-7
    private const val TOP_K_NEIGHBORS = 24
    private const val MAX_WALL_TILES_RADIUS = 2

    /** 2D vector in ScreenPlane coordinates (x-right, y-down). */
    internal data class Vec2(val x: Double, val y: Double) {
        operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
        operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
        operator fun times(s: Double) = Vec2(x * s, y * s)
        fun dot(o: Vec2): Double = x * o.x + y * o.y
        /** 2D cross product (a.x * b.y - a.y * b.x) in ScreenPlane (x-right, y-down). */
        fun det(o: Vec2): Double = x * o.y - y * o.x
        fun lengthSq(): Double = x * x + y * y
        fun length(): Double = hypot(x, y)
        fun normalized(): Vec2 {
            val len = length()
            return if (len > EPS) Vec2(x / len, y / len) else ZERO
        }

        companion object {
            val ZERO = Vec2(0.0, 0.0)
            fun fromHeading(headingDeg: Double, magnitude: Double = 1.0): Vec2 {
                val rad = Math.toRadians(headingDeg)
                return Vec2(sin(rad) * magnitude, -cos(rad) * magnitude)
            }
        }
    }

    /**
     * Directed half-plane constraint in 2D velocity space.
     * Feasible region: (v - point) dot normal >= 0.
     * With tangent direction = Vec2(normal.y, -normal.x):
     *   direction.det(u) = normal.y * u.y - (-normal.x) * u.x = normal.dot(u).
     * Thus direction.det(v - point) >= 0 is algebraically identical to normal.dot(v - point) >= 0.
     */
    internal data class HalfPlane(
        val point: Vec2,
        val normal: Vec2,
        val direction: Vec2 = Vec2(normal.y, -normal.x),
    ) {
        fun signedMargin(v: Vec2): Double = (v - point).dot(normal)
    }

    /** Self-contained immutable locomotion solve output for one step. */
    internal data class SolveResult(
        val applied: Boolean,
        val solvedVelocity: Vec2,
        val targetHeadingDeg: Double,
        val speedTarget: Double,
        val requestedFraction: Double,
        val neighborCount: Int,
        val wallConstraintCount: Int,
        val used3dFallback: Boolean,
        val headingErrorDeg: Double,
        val nextAvoidHeadingDeg: Double?,
    )

    /** Dimensionless kinematic scale derived purely from unit geometry and movement profile. */
    internal data class KinematicScale(
        val hardRadius: Double,
        val bufferRadius: Double,
        val maxSpeed: Double,
        val maxTurnDegPerTick: Double,
        val deceleration: Double,
        val brakingDistance: Double,
        val perceptionRadius: Double,
        val timeHorizon: Double,
        val wallTimeHorizon: Double,
        val comfortSpacing: Double,
    ) {
        companion object {
            fun from(unit: MutableUnit, movement: MovementProfile, maxSpeed: Double): KinematicScale {
                val hard = max(1.0, unit.definition.movementCollision.boundingRadius)
                val buf = max(hard, unit.definition.contactBufferCollision.boundingRadius)
                val vMax = max(0.05, maxSpeed)
                val turnDeg = max(0.2, movement.maxTurnSpeed)
                val decel = max(0.02, movement.moveDecelerationSpeed) * vMax
                val brakeDist = (vMax * vMax) / (2.0 * decel)
                val turnRadius = if (turnDeg > 0.05) vMax / Math.toRadians(turnDeg) else hard * 2.0
                val baseTau = clamp(
                    (2.4 * hard + 0.8 * brakeDist) / vMax + 0.35 * min(turnRadius / vMax, 25.0),
                    10.0,
                    55.0,
                )
                val wallTau = clamp(baseTau * 0.25, 3.0, 9.0)
                val perception = max(
                    5.5 * hard,
                    min(16.0 * hard, 2.5 * hard + 1.35 * vMax * baseTau + 0.6 * brakeDist),
                )
                val comfort = max(buf * 1.15, hard * 1.45)
                return KinematicScale(
                    hardRadius = hard,
                    bufferRadius = buf,
                    maxSpeed = vMax,
                    maxTurnDegPerTick = turnDeg,
                    deceleration = decel,
                    brakingDistance = brakeDist,
                    perceptionRadius = perception,
                    timeHorizon = baseTau,
                    wallTimeHorizon = wallTau,
                    comfortSpacing = comfort,
                )
            }
        }
    }

    /**
     * Solves 2D local velocity avoidance + differential steering intent for a single ground/water/hover
     * unit during active locomotion (MOVE, PATROL, approach legs of ATTACK/BUILD/RECLAIM).
     */
    fun solveGroundLocomotion(
        host: UnitMovementHost,
        unit: MutableUnit,
        terrain: PlanningTerrain,
        movement: MovementProfile,
        desiredTarget: Position,
        baseSpeedTarget: Double,
        maximumSpeed: Double,
        finalPoint: Boolean,
        delta: Double,
        coordinates: CoordinatePolicy,
    ): SolveResult {
        if (maximumSpeed <= EPS || delta <= 0.0) {
            return passthrough(unit, desiredTarget, baseSpeedTarget, maximumSpeed, coordinates)
        }
        val collision = unit.definition.movementCollision
        if (collision.boundingRadius <= 0.0) {
            return passthrough(unit, desiredTarget, baseSpeedTarget, maximumSpeed, coordinates)
        }
        val contactLayer = UnitContact.layer(unit)
            ?: return passthrough(unit, desiredTarget, baseSpeedTarget, maximumSpeed, coordinates)
        val spatialLayer = SpatialLayer.fromContact(contactLayer)
        val scale = KinematicScale.from(unit, movement, maximumSpeed)

        val toTarget = Vec2(desiredTarget.x - unit.position.x, desiredTarget.y - unit.position.y)
        val distToTarget = toTarget.length()
        if (distToTarget <= EPS) {
            return passthrough(unit, desiredTarget, 0.0, maximumSpeed, coordinates)
        }
        val desiredDir = Vec2(toTarget.x / distToTarget, toTarget.y / distToTarget)

        // Inside a single-lane narrow passage managed by PassageAdmission, follow the admitted passage line
        // without lateral deflection into the narrow walls.
        val entry = unit.passageEntry
        if (entry != null && entry.passage.width < 3.6 * scale.hardRadius) {
            return passthrough(unit, desiredTarget, baseSpeedTarget, maximumSpeed, coordinates)
        }
        val preciseFinalLanding = finalPoint && (
            unit.landingMember?.preciseApproach == true ||
                (unit.landingMember?.claimed != null && distToTarget <= scale.hardRadius * 1.4) ||
                (unit.nearestMoveTarget != null && distToTarget <= scale.hardRadius * 2.0)
            )
        if (preciseFinalLanding) {
            return passthrough(unit, desiredTarget, baseSpeedTarget, maximumSpeed, coordinates)
        }

        // 1. Fog-of-war isolated Top-K neighbor sampling from the step-start frozen snapshot.
        val queryRadius = scale.perceptionRadius + scale.hardRadius * 2.0
        val neighbors = host.spatialIndex.queryFrozenTopK(
            observer = unit,
            layer = spatialLayer,
            radius = queryRadius,
            limit = TOP_K_NEIGHBORS,
            includeBufferPadding = true,
        ) { candidate ->
            if (candidate.hardRadius <= 0.0) return@queryFrozenTopK false
            candidate.ownerPlayerId == unit.ownerPlayerId ||
                host.isVisibleTo(unit.ownerPlayerId, candidate.position, candidate.nativeTransportPlacement)
        }

        // Dedicated opposing-flow lookahead query (strictly fog-of-war isolated):
        // Prevents same-group teammates in a large formation (e.g. 25v25) from saturating Top-K and hiding
        // an oncoming opposing wave until collision. Only samples moving visible peers traveling opposite.
        val opposingLookahead = max(scale.perceptionRadius * 2.8, min(56.0 * scale.hardRadius, 2.0 * maximumSpeed * scale.timeHorizon * 4.2))
        val opposingPeers = if (distToTarget > scale.hardRadius * 4.0) {
            host.spatialIndex.queryFrozenTopK(
                observer = unit,
                layer = spatialLayer,
                radius = opposingLookahead,
                limit = 8,
                includeBufferPadding = false,
            ) { candidate ->
                if (candidate.hardRadius <= 0.0 || !candidate.moving || candidate.speed <= 0.12 * maximumSpeed) return@queryFrozenTopK false
                val pDir = Vec2.fromHeading(candidate.heading, 1.0)
                if (desiredDir.dot(pDir) >= -0.25) return@queryFrozenTopK false
                val rx = candidate.position.x - unit.position.x
                val ry = candidate.position.y - unit.position.y
                val fwd = rx * desiredDir.x + ry * desiredDir.y
                if (fwd < -scale.hardRadius * 2.0) return@queryFrozenTopK false
                candidate.ownerPlayerId == unit.ownerPlayerId ||
                    host.isVisibleTo(unit.ownerPlayerId, candidate.position, candidate.nativeTransportPlacement)
            }
        } else emptyList()

        if (neighbors.isEmpty() && opposingPeers.isEmpty()) {
            return passthrough(unit, desiredTarget, baseSpeedTarget, maximumSpeed, coordinates)
        }

        val ownSnap = host.spatialIndex.frozenUnit(unit.unitId)
        val ownPos = ownSnap?.position ?: unit.position
        val ownVel = frozenVelocity(ownSnap, unit, movement)
        val desiredSpeed = clamp(abs(baseSpeedTarget), 0.0, maximumSpeed)
        val basePrefVel = desiredDir * desiredSpeed

        // Measure local corridor clearance to attenuate lateral separation in true single-lane passages.
        val localClearanceWorld = estimateLocalClearanceWorld(terrain, ownPos)
        val singleLaneWidth = 3.6 * scale.hardRadius
        val wideOpenWidth = 6.5 * scale.hardRadius
        val corridorOpenness = clamp(
            (localClearanceWorld - singleLaneWidth) / max(1.0, wideOpenWidth - singleLaneWidth),
            0.0,
            1.0,
        )

        val attackTargetId = unit.attackCommand?.targetUnitId ?: unit.primaryCombatTargetId

        // 2. Dimensionless 2D Boids soft separation + preemptive right-hand lane shift + wide-road overtaking + range-arc spread.
        var sepX = 0.0
        var sepY = 0.0
        var rightShift = 0.0
        var overtakeShift = 0.0
        var activeDynamicConstraints = 0
        val rightVec = Vec2(-desiredDir.y, desiredDir.x) // 90 deg CW (right-hand in ScreenPlane x-right, y-down)
        val attackUnit = attackTargetId?.let { host.units[it] }?.takeIf {
            it.alive && (it.ownerPlayerId == unit.ownerPlayerId || host.isVisibleTo(unit.ownerPlayerId, it.position))
        }
        val combatWeaponRange = if (attackUnit != null) UnitCombat.profile(unit)?.attackRange ?: 0.0 else 0.0
        val isRangedPursuer = attackUnit != null && combatWeaponRange > scale.hardRadius * 4.0

        val dynamicLines = ArrayList<HalfPlane>(neighbors.size)

        for (i in 0 until neighbors.size) {
            val peer = neighbors[i]
            val peerUnit = host.units[peer.unitId]

            // Do not back away from an explicit visible combat target we are actively pursuing to engage.
            if (attackTargetId != null && peer.unitId == attackTargetId) continue
            // Same-group parked peers around a shared-landing cluster:
            // - If we already claimed a validated bay, never let parked peers repel us off our bay approach.
            // - If we are still approaching the shared click to claim a bay, keep ORCA skin tight (1.005)
            //   and zero out backward Boids push so we glide around the cluster edge into open perimeter bays.
            val sameGroupParked = unit.landingMember != null &&
                peerUnit?.landingMember?.group === unit.landingMember?.group &&
                peerUnit?.landingMember?.parked == true
            if (sameGroupParked && unit.landingMember?.claimed != null && distToTarget <= scale.hardRadius * 3.0) {
                val toPeerX = peer.position.x - ownPos.x
                val toPeerY = peer.position.y - ownPos.y
                val fwdToPeer = toPeerX * desiredDir.x + toPeerY * desiredDir.y
                val latToPeer = abs(toPeerX * rightVec.x + toPeerY * rightVec.y)
                if (fwdToPeer <= 0.0 || latToPeer > (scale.hardRadius + peer.hardRadius) * 0.98) {
                    continue
                }
            }

            val relPos = Vec2(peer.position.x - ownPos.x, peer.position.y - ownPos.y)
            val distSq = relPos.lengthSq()
            val dist = sqrt(distSq)
            val combinedHard = scale.hardRadius + peer.hardRadius
            val combinedComfort = max(
                scale.comfortSpacing + peer.hardRadius * 1.15,
                scale.bufferRadius + peer.bufferRadius + 0.15 * min(scale.hardRadius, peer.hardRadius),
            )
            val peerVel = frozenVelocity(peer, peerUnit)

            val peerAnchored = isAnchoredOrStationary(peer, peerUnit)
            val peerMoving = !peerAnchored
            val maxInteractDist = if (peerAnchored) {
                max(combinedComfort * 2.5, combinedHard * 3.8)
            } else {
                max(scale.perceptionRadius + peer.hardRadius, combinedComfort * 2.2)
            }
            if (dist > maxInteractDist) continue

            // If approaching a final destination directly occupied by an anchored/very-slow peer
            // on a single-unit order, allow closing straight into hard-contact range so ADR-009
            // UnitContact resolves contact/stop cleanly.
            val peerVerySlow = peerAnchored || peerVel.length() < 0.05 * maximumSpeed
            if (finalPoint && unit.landingMember == null && peerVerySlow) {
                val peerToGoal = hypot(peer.position.x - desiredTarget.x, peer.position.y - desiredTarget.y)
                if (peerToGoal <= combinedHard + 6.0) {
                    continue
                }
            }
            // ADR-009 hard-contact harmony:
            // (a) Inside a true single-lane corridor (or 1-tile gate) with no passing width, defer stationary
            //     blockers directly ahead to UnitContact so exact contact/stop and mass-transfer work cleanly.
            // (b) For single-unit moves against an isolated stationary blocker directly ahead on the centerline
            //     (same-layer pushable blocker, e.g. submerged submarine pushing a stationary submerged blocker),
            //     or when already in direct bow contact, defer to ADR-009 contact/push when appropriate.
            if (peerAnchored && peerUnit != null) {
                val fwdDot = relPos.dot(desiredDir)
                val latAbs = abs(relPos.dot(rightVec))
                if (corridorOpenness <= 0.05 && unit.landingMember == null && fwdDot > 0.0 && latAbs < combinedHard) {
                    continue
                }
                // If stationary blocker is directly ahead and BOTH perpendicular sides are blocked (true 1-tile pinch),
                // defer to ADR-009 UnitContact so units close cleanly to contact/push boundary.
                if (unit.landingMember == null && fwdDot > 0.0 && latAbs < combinedHard * 0.5 && dist < combinedHard * 3.0) {
                    val leftPos = Position(ownPos.x - rightVec.x * combinedHard * 1.2, ownPos.y - rightVec.y * combinedHard * 1.2)
                    val rightPos = Position(ownPos.x + rightVec.x * combinedHard * 1.2, ownPos.y + rightVec.y * combinedHard * 1.2)
                    val leftCol = floor(leftPos.x / terrain.tileWidth).toInt().coerceIn(0, terrain.widthTiles - 1)
                    val leftRow = floor(leftPos.y / terrain.tileHeight).toInt().coerceIn(0, terrain.heightTiles - 1)
                    val rightCol = floor(rightPos.x / terrain.tileWidth).toInt().coerceIn(0, terrain.widthTiles - 1)
                    val rightRow = floor(rightPos.y / terrain.tileHeight).toInt().coerceIn(0, terrain.heightTiles - 1)
                    if (terrain.isBlocked(leftCol, leftRow) && terrain.isBlocked(rightCol, rightRow)) {
                        continue
                    }
                }
                if (unit.landingMember == null && dist <= combinedHard * 1.03 && fwdDot > 0.0 && latAbs < combinedHard * 0.35) {
                    if (!host.canDisplaceInContact(peerUnit)) {
                        return passthrough(unit, desiredTarget, baseSpeedTarget, maximumSpeed, coordinates)
                    }
                    continue
                }
                if (contactLayer == ContactLayer.SUBMERGED && neighbors.size == 1 &&
                    fwdDot > 0.0 && latAbs < combinedHard * 0.25 &&
                    UnitContact.canPushMass(unit, peerUnit)
                ) {
                    continue
                }
            }
            // Heavy pusher (mass >= 10x peer mass, e.g. experimental / heavy crusher) driving straight into a
            // lighter peer on its centerline defers to ADR-009 mass-transfer push rather than taking a wide detour.
            if (peerUnit != null) {
                val selfMass = unit.definition.mass
                val peerMass = peerUnit.definition.mass
                if (selfMass != null && peerMass != null && selfMass >= peerMass * 10.0 &&
                    relPos.dot(desiredDir) > 0.0 && abs(relPos.dot(rightVec)) < combinedHard * 0.35 &&
                    UnitContact.canPushMass(unit, peerUnit)
                ) {
                    continue
                }
            }

            val ownWeight = computeResponsibility(unit, scale.hardRadius, peer, peerUnit, peerAnchored)

            // --- Boids soft separation ---
            val sameFlow = peerMoving && peerVel.length() > 0.1 * maximumSpeed &&
                peerVel.normalized().dot(desiredDir) > 0.35
            val hasClaimedBay = unit.landingMember?.let { it.claimed != null || it.ingress != null } == true
            val finalApproachFade = when {
                sameGroupParked && hasClaimedBay -> 0.0
                sameGroupParked -> 0.35
                finalPoint -> clamp((distToTarget - scale.hardRadius * 1.5) / (scale.hardRadius * 4.0), 0.12, 1.0)
                else -> 1.0
            }
            val softRange = when {
                sameGroupParked -> combinedHard * 1.55
                sameFlow -> combinedComfort * (1.05 + 0.25 * corridorOpenness)
                else -> combinedComfort * (1.20 + 0.40 * corridorOpenness)
            }
            if (dist < softRange && finalApproachFade > 0.05) {
                val away = if (dist > EPS) {
                    Vec2(-relPos.x / dist, -relPos.y / dist)
                } else {
                    val angle = if (unit.updateKey < peer.updateKey) 0.0 else PI
                    Vec2(cos(angle), sin(angle))
                }
                val closeness = clamp((softRange - dist) / max(1.0, softRange - combinedHard * 0.7), 0.0, 1.25)
                val along = away.dot(desiredDir)
                val lateral = away.dot(rightVec)

                val lateralGain = if (unit.landingMember != null) 0.08 + 0.92 * corridorOpenness else corridorOpenness
                val flowScale = if (sameFlow) 0.55 + 0.12 * corridorOpenness else 1.0
                val sepGain = (0.38 + 0.42 * ownWeight) * flowScale * closeness * closeness * maximumSpeed * finalApproachFade
                val alongWeight = if (sameFlow) 0.25 else 0.55
                val effAlong = if (sameGroupParked && along < 0.0) 0.0 else along * alongWeight
                val effLateral = if (sameGroupParked && abs(lateral) < 0.25) {
                    // Break symmetry smoothly around a parked teammate directly on our nose toward an open bay
                    val click = unit.landingMember?.group?.click
                    val sideFromClick = if (click != null) {
                        val toSelf = Vec2(ownPos.x - click.x, ownPos.y - click.y)
                        if (toSelf.dot(rightVec) >= 0.0) 0.75 else -0.75
                    } else 0.75
                    sideFromClick
                } else lateral
                val pushX = desiredDir.x * effAlong + rightVec.x * (effLateral * lateralGain)
                val pushY = desiredDir.y * effAlong + rightVec.y * (effLateral * lateralGain)
                sepX += pushX * sepGain
                sepY += pushY * sepGain
            }

            // --- Preemptive right-hand bias for head-on / opposing traffic ---
            // East (1,0) -> rightVec = South (0,1); West (-1,0) -> rightVec = North (0,-1).
            // Detect opposing flow early across a broad lateral fan so entire blocks peel onto right-hand
            // dual lanes cleanly BEFORE contact, never colliding head-on in the center!
            if (peerMoving) {
                val peerSpeed = peerVel.length()
                if (peerSpeed > 0.10 * maximumSpeed) {
                    val peerDir = Vec2(peerVel.x / peerSpeed, peerVel.y / peerSpeed)
                    val headOnCos = desiredDir.dot(peerDir)
                    val forwardProj = relPos.dot(desiredDir)
                    val lateralOffset = relPos.dot(rightVec)
                    val headOnRange = max(combinedHard * 11.0, (maximumSpeed + peerSpeed) * scale.timeHorizon * 1.45)
                    if (headOnCos < -0.20 && forwardProj > -combinedHard * 0.8 && forwardProj < headOnRange) {
                        // Wider lane margin in open field so opposing formations separate completely into dual lanes;
                        // tighter inside corridors so units hug the right wall without pushing into it.
                        val laneMargin = combinedComfort * (1.85 + 2.15 * corridorOpenness)
                        if (lateralOffset > -laneMargin && lateralOffset < laneMargin * 1.15) {
                            val urgency = clamp(1.0 - max(0.0, forwardProj) / headOnRange, 0.25, 1.0)
                            val alignment = clamp((-headOnCos - 0.20) / 0.80, 0.30, 1.0)
                            // If opposing peer is ahead or slightly to our right, strongly keep shifting right!
                            // If opposing peer is already well to our left (lateralOffset < -combinedComfort), gentle hold suffices.
                            val sideBoost = if (lateralOffset >= -combinedHard * 0.6) 1.35 else {
                                clamp(1.0 - (-lateralOffset) / laneMargin, 0.20, 1.0)
                            }
                            rightShift += (0.75 + 0.65 * corridorOpenness) * urgency * alignment * sideBoost * maximumSpeed
                        }
                    }
                }
            }

            // --- Wide-road real-time 0-stop oblique overtaking (ENG-P016-007) ---
            // When a faster unit catches a slower same-direction vehicle ahead in a passable corridor/open field,
            // choose the clearer lateral side (left or right, respecting walls via terrain check) and apply a smooth
            // oblique lane-change bias in prefVel so 2D ORCA glides around at cruise speed without bumping or waiting 60 ticks.
            val sameLandingGroup = unit.landingMember != null &&
                peerUnit?.landingMember?.group === unit.landingMember?.group
            val peerMaxSpeed = peerUnit?.let { UnitLocomotion.effectiveMaximumSpeed(it, UnitLocomotion.movement(it)) } ?: maximumSpeed
            val slowerClassPeer = peerMaxSpeed < maximumSpeed * 0.90
            val canOvertakeHere = corridorOpenness > 0.15 && localClearanceWorld >= combinedHard * 2.15
            if (canOvertakeHere && !sameGroupParked) {
                val forwardProj = relPos.dot(desiredDir)
                val lateralOffset = relPos.dot(rightVec)
                val peerAlongSpeed = peerVel.dot(desiredDir)
                val closingRate = desiredSpeed - peerAlongSpeed
                // Overtake when catching a slower vehicle class (mixed speeds), a stationary/anchored non-group unit,
                // or a genuinely stalled peer outside our own dense homogeneous landing crowd.
                val sameClassInLandingGroup = sameLandingGroup && !slowerClassPeer
                val allowSameClassOvertake = !sameClassInLandingGroup ||
                    (corridorOpenness >= 0.75 && distToTarget > combinedHard * 14.0)
                val isSignificantlySlower = (peerAnchored && !sameLandingGroup) ||
                    (sameFlow && closingRate > max(0.12 * maximumSpeed, 0.18 * desiredSpeed) && allowSameClassOvertake)
                val overtakeHorizon = min(distToTarget, max(combinedHard * 5.0, closingRate * scale.timeHorizon * 1.65))
                val overtakeLatLimit = if (peerAnchored) combinedHard * 1.15 else combinedComfort * 1.15
                if (isSignificantlySlower && forwardProj > 0.0 && forwardProj < overtakeHorizon &&
                    abs(lateralOffset) < overtakeLatLimit
                ) {
                    val passClear = combinedHard * 1.35 + scale.hardRadius * 0.4
                    val rightProbe = Position(
                        peer.position.x + rightVec.x * passClear,
                        peer.position.y + rightVec.y * passClear,
                    )
                    val leftProbe = Position(
                        peer.position.x - rightVec.x * passClear,
                        peer.position.y - rightVec.y * passClear,
                    )
                    val rightClear = isPointClearOnTerrain(terrain, rightProbe, scale.hardRadius * 0.9)
                    val leftClear = isPointClearOnTerrain(terrain, leftProbe, scale.hardRadius * 0.9)
                    if (rightClear || leftClear) {
                        val sideSign = when {
                            rightClear && !leftClear -> 1.0
                            leftClear && !rightClear -> -1.0
                            lateralOffset > scale.hardRadius * 0.25 -> -1.0 // peer is on our right -> pass on left
                            lateralOffset < -scale.hardRadius * 0.25 -> 1.0 // peer is on our left -> pass on right
                            else -> if ((stableTieHash(unit.updateKey) and 1L) == 0L) -1.0 else 1.0
                        }
                        val urgency = clamp(1.0 - max(0.0, forwardProj) / max(1.0, overtakeHorizon), 0.30, 1.0)
                        val overlapAlign = clamp(1.0 - abs(lateralOffset) / max(1.0, combinedComfort * 1.15), 0.25, 1.0)
                        val shiftMag = (0.58 + 0.37 * corridorOpenness) * urgency * overlapAlign * desiredSpeed
                        overtakeShift += sideSign * shiftMag
                    }
                }
            }

            // --- Combat ranged-arc natural flanking spread (ENG-P016-007) ---
            // When pursuing a combat target and approaching its range rim behind a friendly unit that is already
            // stationary/firing (or closing slowly) along our sightline to the enemy, fan tangentially around the
            // firing arc instead of queueing in a dead column behind the front row.
            if (isRangedPursuer && peer.ownerPlayerId == unit.ownerPlayerId) {
                val toEnemy = Vec2(attackUnit.position.x - ownPos.x, attackUnit.position.y - ownPos.y)
                val distToEnemy = toEnemy.length()
                if (distToEnemy > combatWeaponRange * 0.95 && distToEnemy < combatWeaponRange * 2.4) {
                    val fwdToPeer = relPos.dot(desiredDir)
                    val latToPeer = relPos.dot(rightVec)
                    if (fwdToPeer > 0.0 && fwdToPeer < combinedComfort * 3.0 && abs(latToPeer) < combinedComfort * 1.45 &&
                        (peerAnchored || peerVel.length() < 0.25 * maximumSpeed)
                    ) {
                        val fanSign = when {
                            latToPeer > scale.hardRadius * 0.2 -> -1.0
                            latToPeer < -scale.hardRadius * 0.2 -> 1.0
                            else -> if ((stableTieHash(unit.updateKey) and 1L) == 0L) -1.0 else 1.0
                        }
                        val arcPush = fanSign * (0.78 * desiredSpeed) * clamp(1.0 - fwdToPeer / (combinedComfort * 3.0), 0.35, 1.0)
                        overtakeShift += arcPush
                    }
                }
            }

            // --- 3. Full 2D ORCA half-plane for this peer ---
            if (preciseFinalLanding && dist > combinedHard * 1.25) continue
            if (sameGroupParked && dist > combinedHard * 1.85) continue
            // Inside a true 1-tile pinch (both perpendicular sides blocked by walls), skip ORCA lateral
            // deflection for a peer clearing perpendicular off the centerline (e.g. hovercraft entering a
            // 1-tile canal as a blocker vacates) so the unit tracks the corridor centerline cleanly.
            if (corridorOpenness <= 0.05 && abs(relPos.dot(rightVec)) < combinedHard * 1.25 &&
                unit.landingMember == null && peerMoving && peerVel.length() > EPS &&
                abs(peerVel.normalized().dot(desiredDir)) < 0.35
            ) {
                continue
            }

            val skinFactor = when {
                sameGroupParked -> 1.002
                finalPoint && distToTarget < scale.hardRadius * 5.0 -> 1.01
                else -> 1.06
            }
            val orcaPlane = buildDynamicOrcaPlane(
                relPos = relPos,
                ownVel = ownVel,
                peerVel = peerVel,
                combinedRadius = combinedHard * skinFactor,
                timeHorizon = when {
                    sameGroupParked -> scale.timeHorizon * 0.45
                    peerAnchored -> scale.timeHorizon * 0.70
                    peerMoving && peerVel.length() > EPS && peerVel.normalized().dot(desiredDir) < -0.2 -> scale.timeHorizon * 1.25
                    else -> scale.timeHorizon * 0.85
                },
                delta = delta,
                responsibility = ownWeight,
                desiredDir = desiredDir,
                peerMoving = peerMoving,
                tieBreakSign = if (unit.updateKey < peer.updateKey) 1.0 else -1.0,
            )
            if (orcaPlane != null) {
                dynamicLines.add(orcaPlane)
                activeDynamicConstraints++
            }
        }

        // Apply long-range opposing-flow right-hand lane shift from dedicated opposingPeers query
        // so entire opposing formations peel onto right-hand dual lanes BEFORE contact.
        if (opposingPeers.isNotEmpty()) {
            var oppAccum = 0.0
            for (i in 0 until opposingPeers.size) {
                val peer = opposingPeers[i]
                if (attackTargetId != null && peer.unitId == attackTargetId) continue
                val relPos = Vec2(peer.position.x - ownPos.x, peer.position.y - ownPos.y)
                val forwardProj = relPos.dot(desiredDir)
                val lateralOffset = relPos.dot(rightVec)
                val combinedHard = scale.hardRadius + peer.hardRadius
                val laneBand = max(combinedHard * 3.5, opposingLookahead * (0.28 + 0.30 * corridorOpenness))
                // Only shift right if the opposing peer is NOT already comfortably passed on our left lane
                if (forwardProj > -combinedHard && forwardProj < opposingLookahead &&
                    lateralOffset > -laneBand * 0.55 && lateralOffset < laneBand
                ) {
                    val urgency = clamp(1.0 - max(0.0, forwardProj) / opposingLookahead, 0.25, 1.0)
                    // Strongest when opposing peer is ahead or slightly to our right (crossing our lane)
                    val sideFactor = if (lateralOffset >= -combinedHard * 1.2) 1.0 else {
                        clamp(1.0 - (-lateralOffset - combinedHard * 1.2) / max(1.0, laneBand * 0.55), 0.15, 1.0)
                    }
                    oppAccum += urgency * sideFactor
                }
            }
            if (oppAccum > 0.0) {
                val scaledOpp = min(1.0, oppAccum / 1.5) * (0.65 + 0.55 * corridorOpenness) * maximumSpeed
                rightShift = max(rightShift, scaledOpp)
            }
        }

        if (activeDynamicConstraints == 0 && hypot(sepX, sepY) <= EPS && rightShift <= EPS && abs(overtakeShift) <= EPS) {
            return passthrough(unit, desiredTarget, baseSpeedTarget, maximumSpeed, coordinates)
        }

        // Compose preferred velocity: desired + clamped Boids separation + right-hand dual-lane shift + oblique overtake.
        val maxSepMag = maximumSpeed * (0.45 + 0.35 * corridorOpenness)
        val rawSepLen = hypot(sepX, sepY)
        var clampedSep = if (rawSepLen > maxSepMag && rawSepLen > EPS) {
            Vec2(sepX * (maxSepMag / rawSepLen), sepY * (maxSepMag / rawSepLen))
        } else Vec2(sepX, sepY)
        val clampedRight = min(rightShift, maximumSpeed * (0.65 + 0.55 * corridorOpenness))
        val maxOvertake = maximumSpeed * (0.55 + 0.35 * corridorOpenness)
        // Opposing right-hand traffic takes precedence over left-side overtaking so overtaking never crosses into oncoming traffic.
        val clampedOvertake = if (clampedRight > 0.15 * maximumSpeed && overtakeShift < 0.0) 0.0
            else clamp(overtakeShift, -maxOvertake, maxOvertake)
        // When an oncoming opposing flow triggers right-hand lane shifting, damp teammate Boids push
        // that pushes LEFT (into the oncoming lane) so upper/inner rows peel right cohesively with the formation.
        if (clampedRight > 0.20 * maximumSpeed) {
            val sepAlong = clampedSep.dot(desiredDir)
            val sepLat = clampedSep.dot(rightVec)
            if (sepLat < 0.0) {
                clampedSep = desiredDir * sepAlong + rightVec * (sepLat * 0.22)
            }
        }

        val totalLateralShift = clampedRight + clampedOvertake
        var prefVel = Vec2(
            basePrefVel.x + clampedSep.x + rightVec.x * totalLateralShift,
            basePrefVel.y + clampedSep.y + rightVec.y * totalLateralShift,
        )
        val prefLen = prefVel.length()
        if (prefLen > maximumSpeed && prefLen > EPS) {
            prefVel = prefVel * (maximumSpeed / prefLen)
        }

        // 4. Static terrain wall half-planes (hard constraints preserved even during 3D LP fallback).
        val wallLines = buildStaticWallHalfPlanes(
            terrain = terrain,
            pos = ownPos,
            hardRadius = scale.hardRadius,
            wallTimeHorizon = scale.wallTimeHorizon,
            delta = delta,
            maxSpeed = maximumSpeed,
        )

        val allLines = ArrayList<HalfPlane>(wallLines.size + dynamicLines.size)
        allLines.addAll(wallLines)
        allLines.addAll(dynamicLines)
        val numFixed = wallLines.size

        // Solve 2D LP; if infeasible among dynamic lines, fall back to ORCA 3D LP preserving all static walls
        // and minimizing worst-case penetration across dynamic half-planes (with prefVel tie-break along free tangents).
        val lpOut = solveLinearProgram(allLines, numFixed, maximumSpeed, prefVel)
        val vSolved = lpOut.velocity

        // When LP produces near-zero velocity because prefVel points straight into an arc of stationary
        // blockers (even though a legal tangent exists in the same half-space), test a strictly LP-feasible
        // tangential nudge (respecting ALL wallLines AND ALL dynamicLines within LP tolerance) so units
        // approaching a convex cluster or gate edge glide smoothly around instead of stalling at v = 0.
        val vFinal = if (vSolved.length() < 0.08 * maximumSpeed && desiredSpeed > 0.1 * maximumSpeed) {
            val worstDyn = if (lpOut.used3dFallback) dynamicLines.minOfOrNull { it.signedMargin(vSolved) } ?: 0.0 else 0.0
            val click = unit.landingMember?.group?.click
            val prefSign = if (click != null && Vec2(ownPos.x - click.x, ownPos.y - click.y).dot(rightVec) < 0.0) -1.0 else 1.0
            val spd = min(desiredSpeed, maximumSpeed * 0.35)
            val candidates = arrayOf(
                (desiredDir * 0.45 + rightVec * (0.89 * prefSign)).normalized() * spd,
                (rightVec * prefSign) * spd,
                (desiredDir * 0.45 - rightVec * (0.89 * prefSign)).normalized() * spd,
                (rightVec * -prefSign) * spd,
                (desiredDir * -0.25 + rightVec * (0.97 * prefSign)).normalized() * (spd * 0.8),
                (desiredDir * -0.25 - rightVec * (0.97 * prefSign)).normalized() * (spd * 0.8),
            )
            candidates.firstOrNull { cand ->
                wallLines.all { it.signedMargin(cand) >= -1e-4 } &&
                    dynamicLines.all { it.signedMargin(cand) >= worstDyn - 1e-3 }
            } ?: vSolved
        } else vSolved

        // 5. Short-horizon reachable steering sector + anti-jitter hysteresis + cosine smooth throttle.
        val solvedSpeed = vFinal.length()
        if (solvedSpeed <= 0.05 * maximumSpeed && lpOut.used3dFallback) {
            // If already hard-overlapping and 3D LP produces near-zero velocity (e.g. boxed in on all 4 sides),
            // fall through to ADR-009 UnitContact so hard overlap resolution / movement-blocked fires cleanly
            // instead of silently idling with candidate == position.
            var overlapping = false
            for (i in 0 until neighbors.size) {
                val peer = neighbors[i]
                val dx = peer.position.x - ownPos.x
                val dy = peer.position.y - ownPos.y
                val hard = scale.hardRadius + peer.hardRadius
                if (dx * dx + dy * dy < hard * hard) {
                    overlapping = true
                    break
                }
            }
            if (overlapping) {
                return passthrough(unit, desiredTarget, baseSpeedTarget, maximumSpeed, coordinates)
            }
        }
        if (solvedSpeed <= EPS) {
            return SolveResult(
                applied = true,
                solvedVelocity = Vec2.ZERO,
                targetHeadingDeg = unit.heading,
                speedTarget = 0.0,
                requestedFraction = 0.0,
                neighborCount = neighbors.size,
                wallConstraintCount = wallLines.size,
                used3dFallback = lpOut.used3dFallback,
                headingErrorDeg = 0.0,
                nextAvoidHeadingDeg = unit.nav.lastAvoidHeadingDeg,
            )
        }

        val rawTargetHeading = headingFromVector(vFinal)

        val isRect = unit.definition.movementCollision is MovementCollisionShape.Rectangle
        val prevAvoid = unit.nav.lastAvoidHeadingDeg
        val travelHeading = if (unit.reversing) coordinates.normalize(unit.heading + 180.0) else unit.heading
        val rawDiffFromCurrent = coordinates.difference(rawTargetHeading, travelHeading)

        // Short-horizon reachable steering sector + anti-jitter hysteresis with U-turn continuity:
        // - Reference the active travel heading (body heading + 180 deg while reversing) so reversing hulls
        //   never fight a bow-side sector clamp.
        // - For slow-turning rectangular hulls, bound lateral heading swings to a reachable lookahead sector
        //   so stern sweep never clips corridor walls.
        // - Near 180-deg reversals, preserve the established turn sign instead of flipping across +/-180 deg.
        val sectorClampedHeading = if (isRect) {
            val maxSectorOffset = clamp(scale.maxTurnDegPerTick * delta * 4.0, 18.0, 75.0)
            if (abs(rawDiffFromCurrent) > maxSectorOffset) {
                val turnSign = if (abs(rawDiffFromCurrent) > 155.0 && prevAvoid != null) {
                    val prevSide = coordinates.difference(prevAvoid, travelHeading)
                    if (prevSide < 0.0) -1.0 else 1.0
                } else if (rawDiffFromCurrent < 0.0) -1.0 else 1.0
                coordinates.normalize(travelHeading + turnSign * maxSectorOffset)
            } else rawTargetHeading
        } else {
            if (abs(rawDiffFromCurrent) > 165.0 && prevAvoid != null) {
                val prevSide = coordinates.difference(prevAvoid, travelHeading)
                if (abs(prevSide) > 1.0) {
                    val turnSign = if (prevSide < 0.0) -1.0 else 1.0
                    coordinates.normalize(travelHeading + turnSign * 165.0)
                } else rawTargetHeading
            } else rawTargetHeading
        }

        val stabilizedHeading = if (prevAvoid != null) {
            val deltaFromPrev = coordinates.difference(sectorClampedHeading, prevAvoid)
            val deadbandDeg = if (isRect) 4.5 else 2.5
            when {
                abs(deltaFromPrev) < deadbandDeg -> prevAvoid
                abs(rawDiffFromCurrent) > 110.0 -> {
                    // Genuine large reorientation / U-turn: commit cleanly without cross-side damping lock.
                    sectorClampedHeading
                }
                abs(deltaFromPrev) > 35.0 && abs(rawDiffFromCurrent) > 20.0 -> {
                    val blend = if (isRect) 0.40 else 0.72
                    coordinates.normalize(prevAvoid + deltaFromPrev * blend)
                }
                else -> {
                    val alpha = if (isRect) 0.50 else 0.85
                    coordinates.normalize(prevAvoid + deltaFromPrev * alpha)
                }
            }
        } else sectorClampedHeading

        val headingErrorAfterTurn = coordinates.difference(stabilizedHeading, travelHeading)

        val absErr = abs(headingErrorAfterTurn)
        val cosThrottle = when {
            absErr <= 12.0 -> 1.0
            absErr >= 95.0 -> 0.0
            else -> {
                val rad = Math.toRadians((absErr - 12.0) * (90.0 / 83.0))
                val c = cos(rad)
                if (isRect) c * c else max(0.0, c)
            }
        }
        val finalSpeedTarget = min(maximumSpeed, solvedSpeed) * cosThrottle
        val finalFraction = if (maximumSpeed > EPS) finalSpeedTarget / maximumSpeed else 0.0

        return SolveResult(
            applied = true,
            solvedVelocity = vFinal,
            targetHeadingDeg = stabilizedHeading,
            speedTarget = finalSpeedTarget,
            requestedFraction = finalFraction,
            neighborCount = neighbors.size,
            wallConstraintCount = wallLines.size,
            used3dFallback = lpOut.used3dFallback,
            headingErrorDeg = headingErrorAfterTurn,
            nextAvoidHeadingDeg = stabilizedHeading,
        )
    }

    /**
     * Constructs a single full-2D ORCA half-plane for neighbor at relPos = p_j - p_i
     * with relative velocity relVel = v_i - v_j.
     * Implements exact Van den Berg et al. (2011) ORCA geometry in 2D with a small consistent
     * right-hand / clockwise symmetry-breaking bias on head-on or nearly collinear approaches.
     */
    internal fun buildDynamicOrcaPlane(
        relPos: Vec2,
        ownVel: Vec2,
        peerVel: Vec2,
        combinedRadius: Double,
        timeHorizon: Double,
        delta: Double,
        responsibility: Double,
        desiredDir: Vec2,
        peerMoving: Boolean,
        tieBreakSign: Double,
    ): HalfPlane? {
        val distSq = relPos.lengthSq()
        val combinedRadiusSq = combinedRadius * combinedRadius
        val relVel = ownVel - peerVel
        val invTau = 1.0 / max(delta, timeHorizon)

        val u: Vec2
        val normal: Vec2

        if (distSq > combinedRadiusSq) {
            val w = relVel - relPos * invTau
            val wLenSq = w.lengthSq()
            val dotProduct1 = w.dot(relPos)

            if (dotProduct1 < 0.0 && dotProduct1 * dotProduct1 > combinedRadiusSq * wLenSq) {
                val wLen = sqrt(wLenSq)
                val unitW = if (wLen > EPS) Vec2(w.x / wLen, w.y / wLen) else Vec2(-desiredDir.y, desiredDir.x)
                normal = unitW
                u = unitW * (combinedRadius * invTau - wLen)
            } else {
                val leg = sqrt(max(0.0, distSq - combinedRadiusSq))
                val cross = relPos.det(w)

                // In ScreenPlane (x-right, y-down), rightOfRel = (-relPos.y, relPos.x) gives relPos.det(rightOfRel) > 0.
                val peerSpeed = peerVel.length()
                val opposing = peerMoving && peerSpeed > EPS &&
                    (peerVel.x * desiredDir.x + peerVel.y * desiredDir.y) / peerSpeed < -0.20
                val biasThreshold = when {
                    opposing -> 0.42 * sqrt(distSq) * max(0.25, w.length())
                    peerMoving -> 0.18 * sqrt(distSq) * max(0.2, w.length())
                    else -> 0.04 * sqrt(distSq) * max(0.1, w.length())
                }
                val chooseRightLeg = when {
                    cross > biasThreshold -> true
                    cross < -biasThreshold -> false
                    else -> if (opposing) true else tieBreakSign >= 0.0
                }

                val legDir: Vec2
                val legNormal: Vec2
                if (chooseRightLeg) {
                    legDir = Vec2(
                        (relPos.x * leg - relPos.y * combinedRadius) / distSq,
                        (relPos.x * combinedRadius + relPos.y * leg) / distSq,
                    ).normalized()
                    legNormal = Vec2(-legDir.y, legDir.x)
                } else {
                    legDir = Vec2(
                        (relPos.x * leg + relPos.y * combinedRadius) / distSq,
                        (-relPos.x * combinedRadius + relPos.y * leg) / distSq,
                    ).normalized()
                    legNormal = Vec2(legDir.y, -legDir.x)
                }
                val proj = relVel.dot(legDir)
                u = legDir * proj - relVel
                normal = legNormal
            }
        } else {
            val invStep = 1.0 / max(delta, 2.0)
            val w = relVel - relPos * invStep
            val wLen = w.length()
            val unitW = if (wLen > EPS) {
                Vec2(w.x / wLen, w.y / wLen)
            } else {
                val dist = sqrt(distSq)
                if (dist > EPS) Vec2(-relPos.x / dist, -relPos.y / dist)
                else Vec2(-desiredDir.y, desiredDir.x)
            }
            normal = unitW
            u = unitW * (combinedRadius * invStep - wLen)
        }

        val normUnit = normal.normalized()
        if (normUnit.lengthSq() <= EPS) return null
        val point = ownVel + u * responsibility
        return HalfPlane(point = point, normal = normUnit)
    }

    /**
     * Builds hard static terrain wall half-planes from nearby blocked tiles around pos.
     * These occupy indices 0 until numFixed and are strictly preserved by 2D LP and 3D LP fallback.
     */
    internal fun buildStaticWallHalfPlanes(
        terrain: PlanningTerrain,
        pos: Position,
        hardRadius: Double,
        wallTimeHorizon: Double,
        delta: Double,
        maxSpeed: Double,
    ): List<HalfPlane> {
        val tw = terrain.tileWidth.toDouble()
        val th = terrain.tileHeight.toDouble()
        val mapW = terrain.widthTiles * tw
        val mapH = terrain.heightTiles * th
        val invWallTau = 1.0 / max(delta, wallTimeHorizon)
        val wallReach = hardRadius + maxSpeed * wallTimeHorizon * 0.85 + 3.0
        val lines = ArrayList<HalfPlane>(8)

        val leftGap = pos.x - hardRadius
        if (leftGap < wallReach) lines.add(HalfPlane(Vec2(-leftGap * invWallTau, 0.0), Vec2(1.0, 0.0)))
        val rightGap = (mapW - pos.x) - hardRadius
        if (rightGap < wallReach) lines.add(HalfPlane(Vec2(rightGap * invWallTau, 0.0), Vec2(-1.0, 0.0)))
        val topGap = pos.y - hardRadius
        if (topGap < wallReach) lines.add(HalfPlane(Vec2(0.0, -topGap * invWallTau), Vec2(0.0, 1.0)))
        val bottomGap = (mapH - pos.y) - hardRadius
        if (bottomGap < wallReach) lines.add(HalfPlane(Vec2(0.0, bottomGap * invWallTau), Vec2(0.0, -1.0)))

        val centerCol = floor(pos.x / tw).toInt()
        val centerRow = floor(pos.y / th).toInt()
        val scanTiles = max(MAX_WALL_TILES_RADIUS, (wallReach / min(tw, th)).toInt().coerceAtMost(3))
        val c0 = max(0, centerCol - scanTiles)
        val c1 = min(terrain.widthTiles - 1, centerCol + scanTiles)
        val r0 = max(0, centerRow - scanTiles)
        val r1 = min(terrain.heightTiles - 1, centerRow + scanTiles)

        data class WallCand(val dist: Double, val plane: HalfPlane)
        val cands = ArrayList<WallCand>(12)

        for (r in r0..r1) {
            val minY = r * th
            val maxY = minY + th
            for (c in c0..c1) {
                if (!terrain.isBlocked(c, r)) continue
                val minX = c * tw
                val maxX = minX + tw
                val closestX = pos.x.coerceIn(minX, maxX)
                val closestY = pos.y.coerceIn(minY, maxY)
                val dx = pos.x - closestX
                val dy = pos.y - closestY
                val dist = hypot(dx, dy)
                if (dist > wallReach) continue

                val normal = if (dist > EPS) {
                    Vec2(dx / dist, dy / dist)
                } else {
                    val dL = pos.x - minX
                    val dR = maxX - pos.x
                    val dT = pos.y - minY
                    val dB = maxY - pos.y
                    val m = min(min(dL, dR), min(dT, dB))
                    when (m) {
                        dL -> Vec2(-1.0, 0.0)
                        dR -> Vec2(1.0, 0.0)
                        dT -> Vec2(0.0, -1.0)
                        else -> Vec2(0.0, 1.0)
                    }
                }
                val gap = dist - hardRadius * 1.02
                val minNormalVel = if (gap >= 0.0) -gap * invWallTau
                else (-gap / max(delta, 2.0)).coerceAtMost(maxSpeed * 0.5)
                val plane = HalfPlane(point = normal * minNormalVel, normal = normal)
                cands.add(WallCand(dist, plane))
            }
        }

        if (cands.size > 1) cands.sortBy { it.dist }
        val maxTileWalls = 8
        var added = 0
        for (i in 0 until cands.size) {
            val cand = cands[i].plane
            var redundant = false
            for (j in 0 until lines.size) {
                val existing = lines[j]
                if (existing.normal.dot(cand.normal) > 0.985) {
                    if (existing.point.dot(existing.normal) >= cand.point.dot(cand.normal) - 1e-4) {
                        redundant = true
                        break
                    }
                }
            }
            if (!redundant) {
                lines.add(cand)
                added++
                if (added >= maxTileWalls) break
            }
        }
        return lines
    }

    internal data class LpResult(val velocity: Vec2, val used3dFallback: Boolean)

    /**
     * Exact RVO2 incremental 2D Linear Program with 3D LP minimal-penetration fallback.
     * Lines 0 until numFixed are hard static obstacle half-planes.
     * Lines numFixed until lines.size are dynamic neighbor half-planes.
     */
    internal fun solveLinearProgram(
        lines: List<HalfPlane>,
        numFixed: Int,
        maxSpeed: Double,
        prefVelocity: Vec2,
    ): LpResult {
        val out = Array(1) { Vec2.ZERO }
        val lineFail = linearProgram2(lines, maxSpeed, prefVelocity, directionOpt = false, tieBreakPref = prefVelocity, result = out)
        if (lineFail < lines.size) {
            linearProgram3(lines, numFixed, lineFail, maxSpeed, prefVelocity, out)
            return LpResult(out[0], used3dFallback = true)
        }
        return LpResult(out[0], used3dFallback = false)
    }

    private fun linearProgram1(
        lines: List<HalfPlane>,
        lineNo: Int,
        radius: Double,
        optVelocity: Vec2,
        directionOpt: Boolean,
        tieBreakPref: Vec2,
        result: Array<Vec2>,
    ): Boolean {
        val line = lines[lineNo]
        val dotProduct = line.point.dot(line.direction)
        val discriminant = dotProduct * dotProduct + radius * radius - line.point.lengthSq()
        if (discriminant < 0.0) return false

        val sqrtDisc = sqrt(discriminant)
        var tLeft = -dotProduct - sqrtDisc
        var tRight = -dotProduct + sqrtDisc

        for (i in 0 until lineNo) {
            val other = lines[i]
            val denominator = line.direction.det(other.direction)
            val numerator = other.direction.det(line.point - other.point)
            if (abs(denominator) <= EPS) {
                if (numerator < 0.0) return false
                continue
            }
            val t = numerator / denominator
            if (denominator >= 0.0) {
                tRight = min(tRight, t)
            } else {
                tLeft = max(tLeft, t)
            }
            if (tLeft > tRight) return false
        }

        if (directionOpt) {
            val dirProj = optVelocity.dot(line.direction)
            result[0] = when {
                dirProj > EPS -> line.point + line.direction * tRight
                dirProj < -EPS -> line.point + line.direction * tLeft
                else -> {
                    // Orthogonal tie along the constraint tangent (e.g. dynamic line parallel to a static wall):
                    // preserve forward intent by projecting prefVelocity onto [tLeft, tRight] instead of snapping to -maxSpeed.
                    val tPref = clamp(line.direction.dot(tieBreakPref - line.point), tLeft, tRight)
                    line.point + line.direction * tPref
                }
            }
        } else {
            val t = line.direction.dot(optVelocity - line.point)
            result[0] = when {
                t < tLeft -> line.point + line.direction * tLeft
                t > tRight -> line.point + line.direction * tRight
                else -> line.point + line.direction * t
            }
        }
        return true
    }

    private fun linearProgram2(
        lines: List<HalfPlane>,
        radius: Double,
        optVelocity: Vec2,
        directionOpt: Boolean,
        tieBreakPref: Vec2,
        result: Array<Vec2>,
    ): Int {
        result[0] = if (directionOpt) {
            optVelocity * radius
        } else if (optVelocity.lengthSq() > radius * radius) {
            optVelocity.normalized() * radius
        } else {
            optVelocity
        }

        for (i in 0 until lines.size) {
            if (lines[i].signedMargin(result[0]) < -EPS) {
                val tempResult = result[0]
                if (!linearProgram1(lines, i, radius, optVelocity, directionOpt, tieBreakPref, result)) {
                    result[0] = tempResult
                    return i
                }
            }
        }
        return lines.size
    }

    private fun linearProgram3(
        lines: List<HalfPlane>,
        numFixed: Int,
        beginLine: Int,
        radius: Double,
        prefVelocity: Vec2,
        result: Array<Vec2>,
    ) {
        var distance = 0.0
        val start = max(numFixed, beginLine)
        for (i in start until lines.size) {
            if (lines[i].signedMargin(result[0]) < -distance) {
                val projLines = ArrayList<HalfPlane>(i + 1)
                for (k in 0 until numFixed) {
                    projLines.add(lines[k])
                }
                for (j in numFixed until i) {
                    val determinant = lines[i].direction.det(lines[j].direction)
                    val pt: Vec2
                    if (abs(determinant) <= EPS) {
                        if (lines[i].direction.dot(lines[j].direction) > 0.0) {
                            continue
                        } else {
                            pt = (lines[i].point + lines[j].point) * 0.5
                        }
                    } else {
                        val factor = lines[j].direction.det(lines[i].point - lines[j].point) / determinant
                        pt = lines[i].point + lines[i].direction * factor
                    }
                    val dir = (lines[j].direction - lines[i].direction).normalized()
                    if (dir.lengthSq() > EPS) {
                        val norm = Vec2(-dir.y, dir.x)
                        projLines.add(HalfPlane(point = pt, normal = norm, direction = dir))
                    }
                }
                val tempResult = result[0]
                if (linearProgram2(projLines, radius, lines[i].normal, directionOpt = true, tieBreakPref = prefVelocity, result = result) < projLines.size) {
                    result[0] = tempResult
                }
                distance = -lines[i].signedMargin(result[0])
            }
        }
    }

    internal fun estimateLocalClearanceWorld(terrain: PlanningTerrain, pos: Position): Double {
        val tw = terrain.tileWidth.toDouble()
        val th = terrain.tileHeight.toDouble()
        val worldW = terrain.widthTiles * tw
        val worldH = terrain.heightTiles * th
        val col = floor(pos.x / tw).toInt().coerceIn(0, terrain.widthTiles - 1)
        val row = floor(pos.y / th).toInt().coerceIn(0, terrain.heightTiles - 1)
        val rings = terrain.clearanceRings(col, row)
        val obstacleClearance = max(0.0, (2 * rings - 1) * min(tw, th))
        // TerrainClearanceField only measures blocked obstacle tiles, not map edges; include border clearance
        // so corridors bounded on one side by a map edge also attenuate lateral separation properly.
        val distToBorder = min(min(pos.x, worldW - pos.x), min(pos.y, worldH - pos.y))
        val borderClearance = max(0.0, 2.0 * distToBorder)
        return min(obstacleClearance, borderClearance)
    }

    private fun isAnchoredOrStationary(peer: FrozenSpatialUnit, peerUnit: MutableUnit?): Boolean {
        if (!peer.moving) return true
        if (peerUnit == null) return false
        if (peerUnit.moveTarget == null && peerUnit.speed <= EPS) return true
        if (peerUnit.landingMember?.parked == true) return true
        if (peerUnit.constructionWorking) return true
        if (peerUnit.spiderBlink != null) return true
        // Standing-to-fire units (either explicit attack hold or patrol engage hold) bear 0.0 yield responsibility.
        if (peerUnit.moveTarget == null && (peerUnit.primaryCombatTargetId != null || peerUnit.attackCommand != null)) return true
        val waitingEntry = peerUnit.passageEntry
        if (waitingEntry != null && !waitingEntry.admitted && !waitingEntry.entered && abs(peerUnit.speed) < 0.05) {
            val mouth = waitingEntry.passage.mouths[waitingEntry.side]
            val peerRadius = max(1.0, peerUnit.definition.movementCollision.boundingRadius)
            val across = if (mouth.horizontal) peerUnit.position.y else peerUnit.position.x
            val clearBand = peerRadius * 1.25
            val clearedOutlet = across < mouth.low - clearBand || across > mouth.high + clearBand
            if (clearedOutlet) return true
        }
        return false
    }

    internal fun computeResponsibility(
        self: MutableUnit,
        selfRadius: Double,
        peer: FrozenSpatialUnit,
        peerUnit: MutableUnit?,
        peerAnchored: Boolean,
    ): Double {
        if (peerAnchored) return 1.0
        val selfMass = self.definition.mass ?: (selfRadius * selfRadius * 50.0)
        val peerMass = peerUnit?.definition?.mass ?: (peer.hardRadius * peer.hardRadius * 50.0)
        val selfArea = max(1.0, selfRadius * selfRadius)
        val peerArea = max(1.0, peer.hardRadius * peer.hardRadius)
        val selfMove = UnitLocomotion.movement(self)
        val peerMove = peerUnit?.let { UnitLocomotion.movement(it) }
        val selfAgility = max(0.2, selfMove.moveSpeed * max(0.5, selfMove.maxTurnSpeed))
        val peerAgility = if (peerMove != null) max(0.2, peerMove.moveSpeed * max(0.5, peerMove.maxTurnSpeed)) else selfAgility
        // Combine mass/size dominance with inverse agility:
        // agile small units take 0.75..0.90 lateral yield responsibility against heavy slow giants,
        // keeping giant hulls on smooth predictable trajectories without crush damage.
        val selfWeight = sqrt(max(1.0, selfMass) * selfArea) / sqrt(selfAgility)
        val peerWeight = sqrt(max(1.0, peerMass) * peerArea) / sqrt(peerAgility)
        if (selfWeight + peerWeight <= EPS) return 0.5
        val ratio = peerWeight / (selfWeight + peerWeight)
        return clamp(0.10 + 0.80 * ratio, 0.10, 0.90)
    }

    private fun frozenVelocity(snap: FrozenSpatialUnit?, unit: MutableUnit?, movement: MovementProfile? = null): Vec2 {
        val useSliding = movement?.slidingMode == true ||
            (unit != null && UnitLocomotion.movement(unit).slidingMode)
        if (snap != null) {
            if (useSliding) return Vec2(snap.flightVelocity.x, snap.flightVelocity.y)
            return Vec2.fromHeading(snap.heading, snap.speed)
        }
        if (unit != null) {
            if (useSliding) return Vec2(unit.flightVelocity.x, unit.flightVelocity.y)
            return Vec2.fromHeading(unit.heading, unit.speed)
        }
        return Vec2.ZERO
    }

    internal fun headingFromVector(v: Vec2): Double {
        val deg = Math.toDegrees(atan2(v.x, -v.y))
        return ((deg % 360.0) + 360.0) % 360.0
    }

    private fun passthrough(
        unit: MutableUnit,
        desiredTarget: Position,
        baseSpeedTarget: Double,
        maximumSpeed: Double,
        coordinates: CoordinatePolicy,
    ): SolveResult {
        val desiredHeading = coordinates.desired(unit.position, desiredTarget)
        val err = coordinates.difference(desiredHeading, unit.heading)
        val frac = if (maximumSpeed > EPS) baseSpeedTarget / maximumSpeed else 0.0
        return SolveResult(
            applied = false,
            solvedVelocity = Vec2.fromHeading(desiredHeading, baseSpeedTarget),
            targetHeadingDeg = desiredHeading,
            speedTarget = baseSpeedTarget,
            requestedFraction = frac,
            neighborCount = 0,
            wallConstraintCount = 0,
            used3dFallback = false,
            headingErrorDeg = err,
            nextAvoidHeadingDeg = null,
        )
    }


    /**
     * Computes a deterministic side-clearing staging aim outside a single-lane narrow mouth for a unit
     * waiting on PassageAdmission (ENG-P016-007).
     *
     * When a unit is denied entry because opposing traffic has priority inside the passage, if it sits
     * directly in front of the mouth outlet it would block exiting vehicles. This returns a terrain-legal
     * lateral staging position offset to the right (or fallback left) outside the mouth so exiting units
     * clear the throat cleanly, or null if the unit is already clear of the outlet lane or no legal bay exists.
     */
    internal fun computeNarrowMouthStagingAim(
        host: UnitMovementHost,
        unit: MutableUnit,
        terrain: PlanningTerrain,
    ): Position? {
        val entry = unit.passageEntry ?: return null
        if (entry.admitted || entry.entered) return null
        // Rectangular hulls manage narrow-mouth staging via HullRoute turnRoom & PassageAdmission;
        // lateral side-stepping off the planned maneuver leg applies to circular hulls that can pivot freely.
        if (unit.definition.movementCollision is MovementCollisionShape.Rectangle) return null
        // Maneuver-routed hulls (e.g. naval vessels following pre-planned hull corridors) rely on
        // HullRoute turnRoom & PassageAdmission; lateral off-corridor side-stepping applies to free grid movers.
        if (unit.followsManeuverRoute) return null
        val movement = UnitLocomotion.movement(unit)
        // Very slow-turning naval vessels (e.g. maxTurnSpeed < 2.5 deg/tick) cannot pivot 90 deg sideways and
        // back within the passage gate without losing hundreds of ticks; hold at gate instead.
        if (movement.type == MovementType.WATER && movement.maxTurnSpeed < 2.5) return null
        val mouth = entry.passage.mouths[entry.side]
        val radius = max(1.0, unit.definition.movementCollision.boundingRadius)
        val tile = max(terrain.tileWidth, terrain.tileHeight).toDouble()
        val depthOutside = mouth.depth(unit.position)
        val across = if (mouth.horizontal) unit.position.y else unit.position.x
        // Only intervene when outside the mouth and actually obstructing the mouth's exit corridor.
        val mouthClearBand = max(radius * 1.85, tile * 0.8)
        val outletReach = max(entry.passage.width * 2.5, radius * 4.5)
        if (depthOutside < radius * 0.25 || depthOutside > outletReach) return null
        val obstructingOutlet = across >= mouth.low - mouthClearBand && across <= mouth.high + mouthClearBand
        if (!obstructingOutlet) return null

        val passageTerrain = entry.terrain
        // When a unit uses a wider terrain mask than the shared passage (e.g. HOVER sharing a WATER strait),
        // stepping onto dry banks outside the water mouth would let it bypass the water mouth or cross the plane;
        // let PassageAdmission hold it cleanly at the gate instead.
        if (passageTerrain !== terrain) return null

        val mouthCenterAcross = (mouth.low + mouth.high) * 0.5
        val mouthOutsideAnchor = mouth.plane + mouth.outward * max(radius * 1.1, 6.0)
        val mouthApproachTarget = if (mouth.horizontal) Position(mouthOutsideAnchor, mouthCenterAcross)
            else Position(mouthCenterAcross, mouthOutsideAnchor)

        // Ensure the staging position stays strictly outside the mouth plane (within PassageAdmission's gate depth)
        // and maintains a direct unobstructed return segment back to the mouth entrance anchor.
        val gateCap = max(depthOutside, tile + radius * 0.85)
        val stageDepth = min(max(depthOutside, radius * 1.9 + 3.0), gateCap)
        val desiredPlane = mouth.plane + mouth.outward * stageDepth
        // Clear the entire PassageAdmission outlet box laterally so exiting traffic clears the throat cleanly.
        val margin = max(radius * 2.2 + 6.0, tile * 0.95 + radius)
        val preferHigh = (mouth.horizontal && mouth.outward < 0) || (!mouth.horizontal && mouth.outward > 0)
        val firstAcross = if (preferHigh) mouth.high + margin else mouth.low - margin
        val secondAcross = if (preferHigh) mouth.low - margin else mouth.high + margin

        fun makePos(planeCoord: Double, acrossCoord: Double): Position =
            if (mouth.horizontal) Position(planeCoord, acrossCoord) else Position(acrossCoord, planeCoord)

        val candidates = arrayOf(
            makePos(desiredPlane, firstAcross),
            makePos(desiredPlane, secondAcross),
            makePos(mouth.plane + mouth.outward * min(stageDepth + radius * 1.25, gateCap), firstAcross),
            makePos(mouth.plane + mouth.outward * min(stageDepth + radius * 1.25, gateCap), secondAcross),
        )
        val routeR = host.routeRadius(unit)
        for (cand in candidates) {
            if (mouth.depth(cand) < radius * 0.9) continue
            // Verify both clearance to the candidate and unobstructed return to the mouth entrance.
            if (isPointClearOnTerrain(terrain, cand, radius * 0.95) &&
                PlanningSegmentClearance.allows(terrain, unit.position, cand, routeR) &&
                PlanningSegmentClearance.allows(terrain, cand, mouthApproachTarget, routeR)
            ) {
                val distSq = ScreenPlane.distanceSquared(unit.position, cand)
                if (distSq > 9.0) return cand
            }
        }
        return null
    }

    /**
     * Aligns an admitted circular unit back in front of the narrow-passage mouth centerline before
     * diving into the throat (ENG-P016-007).
     *
     * When a waiting unit stepped laterally clear of the outlet outside a narrow mouth, its original
     * first waypoint inside the throat may sit behind the corner wall of the mouth. Returning the
     * mouth entrance anchor until the unit is laterally lined up with the throat ensures clean re-entry
     * for all ground/water/hover units (not just landingMember land squads).
     */
    internal fun alignNarrowMouthReentryAim(
        unit: MutableUnit,
        plannedAim: Position,
    ): Position {
        val entry = unit.passageEntry ?: return plannedAim
        if (!entry.admitted || entry.entered) return plannedAim
        val shape = unit.definition.movementCollision as? MovementCollisionShape.Circle ?: return plannedAim
        val mouth = entry.passage.mouths[entry.side]
        val depth = mouth.depth(unit.position)
        if (depth < 0.0) return plannedAim
        // Only intervene when the planned aim leads inside or closer to the throat than our current depth.
        if (mouth.depth(plannedAim) >= depth) return plannedAim
        val mid = (mouth.low + mouth.high) * 0.5
        val across = if (mouth.horizontal) unit.position.y else unit.position.x
        val halfSpan = (mouth.high - mouth.low) * 0.5
        val clearance = max(0.0, halfSpan - shape.radius * 0.85)
        if (abs(across - mid) <= clearance) return plannedAim
        val anchorDepth = max(shape.radius * 1.15, min(depth, shape.radius * 1.8 + 4.0))
        val anchorPlane = mouth.plane + mouth.outward * anchorDepth
        return if (mouth.horizontal) Position(anchorPlane, mid) else Position(mid, anchorPlane)
    }

    /**
     * Independent AIR-layer lightweight 2D Boids soft separation (ENG-P016-007).
     *
     * Operates strictly on the step-start frozen [SpatialLayer.AIR] bucket with fog-of-war isolation.
     * Prevents airborne squads cruising or rallying on the same click from collapsing onto a single pixel,
     * with zero hard-collision deadlock and without interfering with takeoff/landing or bomber attack runs.
     */
    internal fun solveAirSoftSeparation(
        host: UnitMovementHost,
        unit: MutableUnit,
        movement: MovementProfile,
        desiredTarget: Position?,
        maximumSpeed: Double,
        delta: Double,
    ): Vec2? {
        if (maximumSpeed <= EPS || delta <= 0.0) return null
        if (unit.carriedBy != null || unit.attachment != null || unit.birthRemaining > 0f) return null
        val flight = movement.flight
        // Passive airborne units that have never been given a move order remain stationary at spawn.
        if (desiredTarget == null && unit.moveArrival == null && unit.originMoveId == null) return null
        // Do not interfere when an aircraft is actively descending to land/idle-park on ground.
        if (flight != null && unit.moveTarget == null && (flight.landWhenStationary || unit.definition.attackMovement == AttackMovementProfile.BOMBER)) {
            if (unit.flightIdleTime > 2.5f || unit.height < flight.targetHeight * 0.6) return null
        }
        if (unit.definition.attackMovement == AttackMovementProfile.BOMBER && unit.bomberApproach != null) {
            return null
        }

        // Airborne units have movementCollision = None (boundingRadius = 0.0) by ADR-009, so use
        // max(definition.radius, movementCollision.boundingRadius) for visual/physical air spacing!
        val hardR = max(6.0, max(unit.definition.radius, unit.definition.movementCollision.boundingRadius))
        val sepRadius = max(hardR * 4.0, 56.0)
        val neighbors = host.spatialIndex.queryFrozenTopK(
            observer = unit,
            layer = SpatialLayer.AIR,
            radius = sepRadius,
            limit = 16,
            includeBufferPadding = false,
        ) { candidate ->
            // Lightweight soft separation applies among same-owner airborne squads; enemy/other-player
            // aircraft pass freely in air without forcing lateral deflection (ADR-009).
            val peerUnit = host.units[candidate.unitId]
            candidate.ownerPlayerId == unit.ownerPlayerId &&
                peerUnit != null && peerUnit.attachment == null && peerUnit.definition.building == null
        }
        if (neighbors.isEmpty()) return null

        val ownSnap = host.spatialIndex.frozenUnit(unit.unitId)
        val ownPos = ownSnap?.position ?: unit.position
        var pushX = 0.0
        var pushY = 0.0
        var maxOverlapRatio = 0.0
        var count = 0

        for (i in 0 until neighbors.size) {
            val peer = neighbors[i]
            val peerUnit = host.units[peer.unitId]
            // Only separate against airborne peers that are actually flying at cruise altitude
            val peerFlight = peerUnit?.definition?.movement?.flight
            if (peerFlight != null && peerUnit.height < peerFlight.targetHeight * 0.5) continue
            val peerR = max(6.0, max(peerUnit?.definition?.radius ?: 0.0, peer.hardRadius))
            val dx = ownPos.x - peer.position.x
            val dy = ownPos.y - peer.position.y
            val dist = hypot(dx, dy)
            val combinedHard = hardR + peerR
            // Comfort ring leaves a clean visual air gap (~1.25x diameter) with zero circle overlap.
            val comfort = max(combinedHard + 8.0, combinedHard * 1.25)
            if (dist >= comfort) continue
            val closeness = clamp((comfort - dist) / max(1.0, comfort), 0.0, 1.0)
            val overlapRatio = if (dist < combinedHard + 4.0) clamp((combinedHard + 4.0 - dist) / max(1.0, combinedHard), 0.0, 1.0) else 0.0
            if (overlapRatio > maxOverlapRatio) maxOverlapRatio = overlapRatio
            val dir = if (dist > 1e-4) {
                Vec2(dx / dist, dy / dist)
            } else {
                // Deterministic golden-angle fan break when two aircraft start on exact identical pixel
                val slot = (stableTieHash(unit.updateKey) * 137.508) % 360.0
                val rad = Math.toRadians(slot)
                Vec2(cos(rad), sin(rad))
            }
            val rallyFactor = if (desiredTarget != null) {
                val ownGoalDist = hypot(ownPos.x - desiredTarget.x, ownPos.y - desiredTarget.y)
                val peerGoalDist = hypot(peer.position.x - desiredTarget.x, peer.position.y - desiredTarget.y)
                if (ownGoalDist + hardR * 0.25 < peerGoalDist && overlapRatio <= 0.0) 0.35 else 1.0
            } else 1.0
            val w = (closeness * (0.55 + 0.75 * closeness) + overlapRatio * 2.4) * rallyFactor
            pushX += dir.x * w
            pushY += dir.y * w
            count++
        }
        if (count == 0) return null

        val pushLen = hypot(pushX, pushY)
        if (pushLen <= 1e-4) return null

        // When approaching a shared rally point, allow separation to overcome inbound attraction cleanly
        // (up to 1.35 * maxSpeed on tight overlap) so aircraft never pile into a compressed red ball.
        val maxSepSpeed = if (desiredTarget == null) {
            maximumSpeed * (0.65 + 0.55 * maxOverlapRatio)
        } else {
            maximumSpeed * (1.05 + 0.45 * maxOverlapRatio)
        }
        val gain = if (desiredTarget == null) 1.15 else 1.55
        val scaleMag = min(maxSepSpeed, pushLen * maximumSpeed * gain)
        return Vec2(pushX / pushLen * scaleMag, pushY / pushLen * scaleMag)
    }

    /**
     * Checks whether an airborne unit approaching a shared rally click is already comfortably positioned
     * in the spread formation around that click while another airborne peer occupies the center,
     * allowing the flock to settle smoothly into a ring without fighting the 7u point-capture radius.
     */
    internal fun isAirRallyRingArrived(
        host: UnitMovementHost,
        unit: MutableUnit,
        target: Position,
    ): Boolean {
        val hardR = max(6.0, max(unit.definition.radius, unit.definition.movementCollision.boundingRadius))
        val distToTarget = hypot(unit.position.x - target.x, unit.position.y - target.y)
        val outerRing = max(hardR * 12.0, 140.0)
        if (distToTarget > outerRing) return false
        val allPeers = host.spatialIndex.queryFrozenTopK(
            observer = unit,
            layer = SpatialLayer.AIR,
            radius = outerRing + hardR * 3.0,
            limit = 24,
            includeBufferPadding = false,
        ) { candidate ->
            val peerUnit = host.units[candidate.unitId]
            candidate.ownerPlayerId == unit.ownerPlayerId &&
                peerUnit != null && peerUnit.attachment == null && peerUnit.definition.building == null
        }
        if (allPeers.isEmpty()) return false
        var innerCount = 0
        var closestPeerDistToTarget = Double.POSITIVE_INFINITY
        var nearestPeerClearRatio = Double.POSITIVE_INFINITY
        var blockingPeerAhead = false
        val toTargetX = target.x - unit.position.x
        val toTargetY = target.y - unit.position.y
        val toTargetLen = max(1e-4, distToTarget)
        val maxSpeed = max(0.5, UnitLocomotion.effectiveMaximumSpeed(unit, UnitLocomotion.movement(unit)))
        val ownAirVel = frozenVelocity(host.spatialIndex.frozenUnit(unit.unitId), unit)
        val closingSpeed = (ownAirVel.x * toTargetX + ownAirVel.y * toTargetY) / toTargetLen
        for (i in 0 until allPeers.size) {
            val peer = allPeers[i]
            val peerUnit = host.units[peer.unitId]
            val peerFlight = peerUnit?.definition?.movement?.flight
            if (peerFlight != null && peerUnit.height < peerFlight.targetHeight * 0.5) continue
            val peerR = max(6.0, max(peerUnit?.definition?.radius ?: 0.0, peer.hardRadius))
            val peerDistToTarget = hypot(peer.position.x - target.x, peer.position.y - target.y)
            if (peerDistToTarget < closestPeerDistToTarget) closestPeerDistToTarget = peerDistToTarget
            val peerArrived = peerUnit == null || peerUnit.moveTarget == null
            if (peerDistToTarget < distToTarget - hardR * 0.15) {
                innerCount++
            }
            val pairDist = hypot(peer.position.x - unit.position.x, peer.position.y - unit.position.y)
            val combinedHard = hardR + peerR
            val ratio = pairDist / max(1.0, combinedHard)
            if (ratio < nearestPeerClearRatio) nearestPeerClearRatio = ratio
            // Check whether a settled or slow peer sits in our comfort zone between us and the rally click
            val comfort = max(combinedHard + 14.0, combinedHard * 1.50)
            val peerVel = frozenVelocity(peer, peerUnit)
            val peerSlow = peerArrived || peerVel.length() < maxSpeed * 0.55
            if (peerSlow && pairDist <= comfort) {
                val dot = ((peer.position.x - unit.position.x) * toTargetX +
                    (peer.position.y - unit.position.y) * toTargetY) / (max(1e-4, pairDist) * toTargetLen)
                if (dot > 0.15) blockingPeerAhead = true
            }
        }
        val peerOccupiesCore = closestPeerDistToTarget <= max(hardR * 2.0, 24.0)
        if (!peerOccupiesCore) return false
        // Also check whether separation push roughly cancels approach pull (near equilibrium around settled flock).
        val atEquilibrium = closingSpeed < maxSpeed * 0.45
        val leaderSettled = distToTarget <= max(hardR * 1.6, 18.0) && atEquilibrium && (blockingPeerAhead || nearestPeerClearRatio < 1.35)
        // Once inner aircraft occupy the rally core and block our radial approach, settle immediately
        // (even if snug at ratio >= 0.80) so idle soft separation smoothly expands the entire flock to full
        // non-overlapping comfort spacing without hovering in active-move stall!
        val ringSettled = innerCount > 0 && (distToTarget <= max(hardR * 2.8, 30.0) || blockingPeerAhead) &&
            (nearestPeerClearRatio >= 0.98 || (atEquilibrium && nearestPeerClearRatio >= 0.78))
        return leaderSettled || ringSettled
    }

    private fun isPointClearOnTerrain(terrain: PlanningTerrain, pos: Position, radius: Double): Boolean {
        val tw = terrain.tileWidth.toDouble()
        val th = terrain.tileHeight.toDouble()
        val worldW = terrain.widthTiles * tw
        val worldH = terrain.heightTiles * th
        if (pos.x - radius < 0.0 || pos.x + radius > worldW || pos.y - radius < 0.0 || pos.y + radius > worldH) {
            return false
        }
        val minCol = floor((pos.x - radius) / tw).toInt().coerceIn(0, terrain.widthTiles - 1)
        val maxCol = floor((pos.x + radius) / tw).toInt().coerceIn(0, terrain.widthTiles - 1)
        val minRow = floor((pos.y - radius) / th).toInt().coerceIn(0, terrain.heightTiles - 1)
        val maxRow = floor((pos.y + radius) / th).toInt().coerceIn(0, terrain.heightTiles - 1)
        for (r in minRow..maxRow) {
            for (c in minCol..maxCol) {
                if (terrain.isBlocked(c, r)) return false
            }
        }
        return true
    }

    /** Explicit deterministic 32-bit FNV-1a hash over stable simulation key (cross-JVM / replay safe). */
    private fun stableTieHash(key: String): Long {
        var h = 0x811c9dc5L
        for (i in 0 until key.length) {
            h = (h xor key[i].code.toLong()) and 0xffffffffL
            h = (h * 0x01000193L) and 0xffffffffL
        }
        return h and 0x7fffffffL
    }

    private fun clamp(v: Double, lo: Double, hi: Double): Double = max(lo, min(hi, v))
}
