package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.ScreenPlane
import com.rustedwarfare.reforge.core.sim.MutableUnit
import com.rustedwarfare.reforge.core.sim.approach
import com.rustedwarfare.reforge.core.sim.immutable

import com.rustedwarfare.reforge.core.content.AttackMovementProfile
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementProfile
import com.rustedwarfare.reforge.core.content.MovementType
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
 * 2D local velocity avoidance and differential kinematics deep module (ADR-012 / ENG-P016-006 / ENG-P016-009C).
 *
 * Strictly enforces **zero upper-command special-casing**:
 * - Never reads `landingMember`, `groupMoveReservation`, group size, shared-target identity, or scene IDs.
 * - Never reads mutable peer `MutableUnit` state (`host.units[...]`) during step iteration; all neighbor poses,
 *   velocities, anchored/displaceable flags, and hull shapes come strictly from the step-start frozen snapshot
 *   ([FrozenSpatialUnit]), guaranteeing mathematical invariance to unit update order.
 * - Never uses blanket `passthrough` bypasses to disable static wall half-planes or ORCA car-following inside
 *   passages or near peers; upstream orchestration communicates only abstract kinematic/geometric parameters
 *   (`terminalDockingWeight`, `ignoredContactUnitId`, `engagementArcCenter`, `engagementArcRadius`).
 * - Uses reusable thread-local scratch buffers + `WorldSpatialIndex.queryFrozenTopKInto` for near-zero
 *   steady-state heap allocation at 60Hz.
 */
internal object LocomotionModule {
    private const val EPS = 1e-7
    private const val TOP_K_NEIGHBORS = 24
    private const val TOP_K_OPPOSING = 8
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
        fun length(): Double = sqrt(x * x + y * y)
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
        val terrainRadius: Double,
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
            fun from(
                unit: MutableUnit,
                movement: MovementProfile,
                maxSpeed: Double,
                tileWidth: Int = 20,
                tileHeight: Int = 20,
            ): KinematicScale {
                val hard = max(1.0, unit.definition.movementCollision.boundingRadius)
                val terrainR = max(0.0, UnitLocomotion.terrainPassabilityRadius(unit, tileWidth, tileHeight))
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
                    terrainRadius = terrainR,
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

    private class WallCand {
        var dist: Double = 0.0
        var plane: HalfPlane = DUMMY_PLANE
    }

    private class Scratch {
        val neighbors: Array<FrozenSpatialUnit?> = arrayOfNulls(TOP_K_NEIGHBORS)
        val neighborsDistSq: DoubleArray = DoubleArray(TOP_K_NEIGHBORS)
        val opposing: Array<FrozenSpatialUnit?> = arrayOfNulls(TOP_K_OPPOSING)
        val opposingDistSq: DoubleArray = DoubleArray(TOP_K_OPPOSING)
        val airPeers: Array<FrozenSpatialUnit?> = arrayOfNulls(24)
        val airDistSq: DoubleArray = DoubleArray(24)
        val wallCands: Array<WallCand> = Array(49) { WallCand() }
        val wallLines: ArrayList<HalfPlane> = ArrayList(16)
        val dynamicLines: ArrayList<HalfPlane> = ArrayList(TOP_K_NEIGHBORS)
        val dynamicPushable: BooleanArray = BooleanArray(TOP_K_NEIGHBORS)
        // Zero-allocation primitive dynamic ORCA plane buffers (eliminates Vec2 & HalfPlane boxing in hot loop)
        val dynPx: DoubleArray = DoubleArray(TOP_K_NEIGHBORS)
        val dynPy: DoubleArray = DoubleArray(TOP_K_NEIGHBORS)
        val dynNx: DoubleArray = DoubleArray(TOP_K_NEIGHBORS)
        val dynNy: DoubleArray = DoubleArray(TOP_K_NEIGHBORS)
        val dynDx: DoubleArray = DoubleArray(TOP_K_NEIGHBORS)
        val dynDy: DoubleArray = DoubleArray(TOP_K_NEIGHBORS)
        var dynCount: Int = 0
        val allLines: ArrayList<HalfPlane> = ArrayList(32)
        val projLines: ArrayList<HalfPlane> = ArrayList(32)
        // Zero-allocation primitive LP buffers
        val lpPx: DoubleArray = DoubleArray(64)
        val lpPy: DoubleArray = DoubleArray(64)
        val lpNx: DoubleArray = DoubleArray(64)
        val lpNy: DoubleArray = DoubleArray(64)
        val lpDx: DoubleArray = DoubleArray(64)
        val lpDy: DoubleArray = DoubleArray(64)
        val projPx: DoubleArray = DoubleArray(64)
        val projPy: DoubleArray = DoubleArray(64)
        val projNx: DoubleArray = DoubleArray(64)
        val projNy: DoubleArray = DoubleArray(64)
        val projDx: DoubleArray = DoubleArray(64)
        val projDy: DoubleArray = DoubleArray(64)
        val lpOutXY: DoubleArray = DoubleArray(2)
        val lpOut: Array<Vec2> = Array(1) { Vec2.ZERO }
        // Reusable scalar accumulators for split solveGroundLocomotion phases (zero allocation)
        var sepX: Double = 0.0
        var sepY: Double = 0.0
        var rightShift: Double = 0.0
        var overtakeShift: Double = 0.0
        var activeDynamicConstraints: Int = 0
        var hasExistingHardOverlap: Boolean = false
        var hasDirectImmovableContact: Boolean = false
        var hasClosePushableBlocker: Boolean = false
        var clusterOutwardSign: Double = 1.0
        var ownLeftClear: Double = 0.0
        var ownRightClear: Double = 0.0
        var pairSpanState: Int = 0
    }

    private val DUMMY_PLANE = HalfPlane(Vec2.ZERO, Vec2(1.0, 0.0))
    private val scratchPool: ThreadLocal<Scratch> = ThreadLocal.withInitial { Scratch() }

    /**
     * Solves 2D local velocity avoidance + differential steering intent for a single ground/water/hover
     * unit during active locomotion (`MOVE`, `PATROL`, approach legs of `ATTACK`/`BUILD`/`RECLAIM`).
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
        terminalDockingWeight: Double = 0.0,
        ignoredContactUnitId: String? = null,
        engagementArcCenter: Position? = null,
        engagementArcRadius: Double = 0.0,
        protectedClusterKey: Any? = null,
    ): SolveResult {
        if (maximumSpeed <= EPS || delta <= 0.0) {
            return passthrough(unit, desiredTarget, baseSpeedTarget, maximumSpeed)
        }
        val collision = unit.definition.movementCollision
        if (collision !is MovementCollisionShape.Circle || collision.boundingRadius <= 0.0) {
            return passthrough(unit, desiredTarget, baseSpeedTarget, maximumSpeed)
        }
        val contactLayer = UnitContact.layer(unit)
            ?: return passthrough(unit, desiredTarget, baseSpeedTarget, maximumSpeed)
        val spatialLayer = SpatialLayer.fromContact(contactLayer)
        val scale = KinematicScale.from(unit, movement, maximumSpeed, terrain.tileWidth, terrain.tileHeight)
        val dockingFade = clamp(1.0 - clamp(terminalDockingWeight, 0.0, 1.0), 0.0, 1.0)

        val toTarget = Vec2(desiredTarget.x - unit.position.x, desiredTarget.y - unit.position.y)
        val distToTarget = toTarget.length()
        if (distToTarget <= EPS) {
            return passthrough(unit, desiredTarget, 0.0, maximumSpeed)
        }
        val desiredDir = Vec2(toTarget.x / distToTarget, toTarget.y / distToTarget)

        // When PassageAdmission has granted single-lane right-of-way (`admitted == true` in a lane too narrow
        // for two hulls), never disable ORCA or static wall half-planes via `passthrough`; instead, filter out
        // opposing/waiting mouth peers per-neighbor while preserving full 2D ORCA car-following behind same-lane
        // front vehicles and all static terrain wall half-planes.
        val admittedEntry = unit.passageEntry
        val admittedOutletDepth = if (admittedEntry != null && admittedEntry.entered) {
            admittedEntry.passage.mouths[1 - admittedEntry.side].depth(unit.position)
        } else -1.0
        val isAdmittedSingleLane = admittedEntry != null &&
            admittedEntry.admitted &&
            admittedEntry.passage.width < 3.6 * scale.hardRadius &&
            admittedOutletDepth < scale.hardRadius * 0.5

        val scratch = scratchPool.get()
        val queryRadius = scale.perceptionRadius + scale.hardRadius * 2.0
        val neighborCount = queryVisibleNeighbors(host, unit, spatialLayer, queryRadius, scratch)

        // Bounded closing-time opposing-flow lookahead query (fog-of-war isolated, capped to avoid map-wide scans).
        val opposingLookahead = clamp(
            max(scale.perceptionRadius * 1.65, 2.0 * maximumSpeed * scale.timeHorizon * 1.85),
            scale.perceptionRadius,
            max(185.0, scale.hardRadius * 24.0),
        )
        val opposingCount = if (distToTarget > scale.hardRadius * 4.0 && dockingFade > 0.25) {
            queryOpposingLookahead(host, unit, spatialLayer, opposingLookahead, maximumSpeed, desiredDir, scale.hardRadius, scratch)
        } else 0

        if (neighborCount == 0 && opposingCount == 0) {
            return passthrough(unit, desiredTarget, baseSpeedTarget, maximumSpeed)
        }

        val ownSnap = host.spatialIndex.frozenUnit(unit.unitId)
        val ownPos = ownSnap?.position ?: unit.position
        val ownVel = frozenVelocity(ownSnap, unit, movement)
        val desiredSpeed = clamp(abs(baseSpeedTarget), 0.0, maximumSpeed)
        val basePrefVel = desiredDir * desiredSpeed
        val rightVec = Vec2(-desiredDir.y, desiredDir.x) // 90 deg CW (right-hand in ScreenPlane x-right, y-down)

        // Measure actual perpendicular passable span across our travel direction so 2-tile / 3-tile corridors
        // where hulls can pass side-by-side keep lateral Boids & 2D ORCA overtaking active even in outer lanes,
        // attenuating to 0.0 ONLY inside true single-lane 1-tile pinches.
        val localClearanceWorld = estimateTransverseClearanceWorldWithSides(terrain, ownPos, rightVec, scratch)
        val leftWallClear = scratch.ownLeftClear
        val rightWallClear = scratch.ownRightClear
        val selfHalfWidth = transverseHalfWidth(collision, unit.heading, rightVec)
        val singleLaneWidth = max(min(terrain.tileWidth, terrain.tileHeight).toDouble() * 1.15, 3.2 * selfHalfWidth)
        val wideOpenWidth = max(singleLaneWidth + 16.0, 5.8 * selfHalfWidth)
        val corridorOpenness = clamp(
            (localClearanceWorld - singleLaneWidth) / max(1.0, wideOpenWidth - singleLaneWidth),
            0.0,
            1.0,
        )

        evaluateNeighborsIntoScratch(
            unit = unit,
            terrain = terrain,
            movement = movement,
            contactLayer = contactLayer,
            scale = scale,
            desiredTarget = desiredTarget,
            desiredDir = desiredDir,
            rightVec = rightVec,
            distToTarget = distToTarget,
            desiredSpeed = desiredSpeed,
            maximumSpeed = maximumSpeed,
            finalPoint = finalPoint,
            delta = delta,
            terminalDockingWeight = terminalDockingWeight,
            dockingFade = dockingFade,
            ignoredContactUnitId = ignoredContactUnitId,
            engagementArcCenter = engagementArcCenter,
            engagementArcRadius = engagementArcRadius,
            protectedClusterKey = protectedClusterKey,
            isAdmittedSingleLane = isAdmittedSingleLane,
            ownPos = ownPos,
            ownVel = ownVel,
            localClearanceWorld = localClearanceWorld,
            leftWallClear = leftWallClear,
            rightWallClear = rightWallClear,
            selfHalfWidth = selfHalfWidth,
            corridorOpenness = corridorOpenness,
            neighborCount = neighborCount,
            scratch = scratch,
        )

        // Apply opposing-flow right-hand lane shift from bounded opposing query when corridor allows two lanes.
        if (!isAdmittedSingleLane && opposingCount > 0 && localClearanceWorld >= selfHalfWidth * 3.6) {
            val oppShift = computeOpposingRightShift(
                ownPos = ownPos,
                desiredDir = desiredDir,
                rightVec = rightVec,
                hardRadius = scale.hardRadius,
                opposingLookahead = opposingLookahead,
                corridorOpenness = corridorOpenness,
                maximumSpeed = maximumSpeed,
                ignoredContactUnitId = ignoredContactUnitId,
                opposingBuf = scratch.opposing,
                opposingCount = opposingCount,
            )
            if (oppShift > scratch.rightShift) scratch.rightShift = oppShift
        }

        val neighborsBuf = scratch.neighbors
        val opposingBuf = scratch.opposing
        for (i in 0 until neighborCount) neighborsBuf[i] = null
        for (i in 0 until opposingCount) opposingBuf[i] = null

        return finalizeGroundLocomotion(
            unit = unit,
            terrain = terrain,
            scale = scale,
            desiredTarget = desiredTarget,
            baseSpeedTarget = baseSpeedTarget,
            desiredDir = desiredDir,
            rightVec = rightVec,
            distToTarget = distToTarget,
            desiredSpeed = desiredSpeed,
            basePrefVel = basePrefVel,
            maximumSpeed = maximumSpeed,
            finalPoint = finalPoint,
            delta = delta,
            terminalDockingWeight = terminalDockingWeight,
            ownPos = ownPos,
            corridorOpenness = corridorOpenness,
            neighborCount = neighborCount,
            scratch = scratch,
        )
    }

    private fun queryVisibleNeighbors(
        host: UnitMovementHost,
        unit: MutableUnit,
        spatialLayer: SpatialLayer,
        queryRadius: Double,
        scratch: Scratch,
    ): Int = host.spatialIndex.queryFrozenTopKInto(
        observer = unit,
        layer = spatialLayer,
        radius = queryRadius,
        limit = TOP_K_NEIGHBORS,
        includeBufferPadding = true,
        outUnits = scratch.neighbors,
        outDistSq = scratch.neighborsDistSq,
    ) { candidate ->
        if (candidate.hardRadius <= 0.0) return@queryFrozenTopKInto false
        candidate.ownerPlayerId == unit.ownerPlayerId ||
            host.isVisibleTo(unit.ownerPlayerId, candidate.position, candidate.nativeTransportPlacement)
    }

    private fun queryOpposingLookahead(
        host: UnitMovementHost,
        unit: MutableUnit,
        spatialLayer: SpatialLayer,
        opposingLookahead: Double,
        maximumSpeed: Double,
        desiredDir: Vec2,
        hardRadius: Double,
        scratch: Scratch,
    ): Int {
        if (!host.spatialIndex.mightHaveOpposingMover(spatialLayer, desiredDir.x, desiredDir.y, -0.25)) {
            return 0
        }
        return host.spatialIndex.queryFrozenTopKInto(
            observer = unit,
            layer = spatialLayer,
            radius = opposingLookahead,
            limit = TOP_K_OPPOSING,
            includeBufferPadding = false,
            outUnits = scratch.opposing,
            outDistSq = scratch.opposingDistSq,
        ) { candidate ->
            if (candidate.hardRadius <= 0.0 || !candidate.moving || candidate.speed <= 0.12 * maximumSpeed) {
                return@queryFrozenTopKInto false
            }
            val spd = candidate.velSpeed
            if (spd <= 1e-7 || (desiredDir.x * candidate.velX + desiredDir.y * candidate.velY) / spd >= -0.25) {
                return@queryFrozenTopKInto false
            }
            val rx = candidate.position.x - unit.position.x
            val ry = candidate.position.y - unit.position.y
            val fwd = rx * desiredDir.x + ry * desiredDir.y
            if (fwd < -hardRadius * 2.0) return@queryFrozenTopKInto false
            candidate.ownerPlayerId == unit.ownerPlayerId ||
                host.isVisibleTo(unit.ownerPlayerId, candidate.position, candidate.nativeTransportPlacement)
        }
    }

    private fun computeOpposingRightShift(
        ownPos: Position,
        desiredDir: Vec2,
        rightVec: Vec2,
        hardRadius: Double,
        opposingLookahead: Double,
        corridorOpenness: Double,
        maximumSpeed: Double,
        ignoredContactUnitId: String?,
        opposingBuf: Array<FrozenSpatialUnit?>,
        opposingCount: Int,
    ): Double {
        var oppAccum = 0.0
        for (i in 0 until opposingCount) {
            val peer = checkNotNull(opposingBuf[i])
            if (ignoredContactUnitId != null && peer.unitId == ignoredContactUnitId) continue
            val relPos = Vec2(peer.position.x - ownPos.x, peer.position.y - ownPos.y)
            val forwardProj = relPos.dot(desiredDir)
            val lateralOffset = relPos.dot(rightVec)
            val combinedHard = hardRadius + peer.hardRadius
            val laneBand = max(combinedHard * 3.5, opposingLookahead * (0.28 + 0.30 * corridorOpenness))
            if (forwardProj > -combinedHard && forwardProj < opposingLookahead &&
                lateralOffset > -laneBand * 0.55 && lateralOffset < laneBand
            ) {
                val urgency = clamp(1.0 - max(0.0, forwardProj) / opposingLookahead, 0.25, 1.0)
                val sideFactor = if (lateralOffset >= -combinedHard * 1.2) 1.0 else {
                    clamp(1.0 - (-lateralOffset - combinedHard * 1.2) / max(1.0, laneBand * 0.55), 0.15, 1.0)
                }
                oppAccum += urgency * sideFactor
            }
        }
        return if (oppAccum > 0.0) {
            min(1.0, oppAccum / 1.5) * (0.65 + 0.55 * max(0.35, corridorOpenness)) * maximumSpeed
        } else 0.0
    }

    private fun evaluateNeighborsIntoScratch(
        unit: MutableUnit,
        terrain: PlanningTerrain,
        movement: MovementProfile,
        contactLayer: ContactLayer,
        scale: KinematicScale,
        desiredTarget: Position,
        desiredDir: Vec2,
        rightVec: Vec2,
        distToTarget: Double,
        desiredSpeed: Double,
        maximumSpeed: Double,
        finalPoint: Boolean,
        delta: Double,
        terminalDockingWeight: Double,
        dockingFade: Double,
        ignoredContactUnitId: String?,
        engagementArcCenter: Position?,
        engagementArcRadius: Double,
        protectedClusterKey: Any?,
        isAdmittedSingleLane: Boolean,
        ownPos: Position,
        ownVel: Vec2,
        localClearanceWorld: Double,
        leftWallClear: Double,
        rightWallClear: Double,
        selfHalfWidth: Double,
        corridorOpenness: Double,
        neighborCount: Int,
        scratch: Scratch,
    ) {
        val neighborsBuf = scratch.neighbors
        val isRangedPursuer = engagementArcCenter != null && engagementArcRadius > scale.hardRadius * 4.0

        var sepX = 0.0
        var sepY = 0.0
        var rightShift = 0.0
        var overtakeShift = 0.0
        var activeDynamicConstraints = 0
        var hasExistingHardOverlap = false
        var hasDirectImmovableContact = false
        var hasClosePushableBlocker = false

        val dynamicPushable = scratch.dynamicPushable
        scratch.dynCount = 0

        // Pure geometric centroid of nearby stationary obstacles from step-start frozen snapshot (zero allocation,
        // zero mutable peer reads, zero upper-command state) so tangential symmetry-breaking always peels outward
        // around the free perimeter of an obstacle cluster.
        var anchorSumX = 0.0
        var anchorSumY = 0.0
        var anchorCount = 0
        for (i in 0 until neighborCount) {
            val p = checkNotNull(neighborsBuf[i])
            if (ignoredContactUnitId != null && p.unitId == ignoredContactUnitId) continue
            if (p.anchored) {
                anchorSumX += p.position.x
                anchorSumY += p.position.y
                anchorCount++
            }
        }
        val outwardDot = if (anchorCount > 0) {
            val cx = anchorSumX / anchorCount
            val cy = anchorSumY / anchorCount
            (ownPos.x - cx) * rightVec.x + (ownPos.y - cy) * rightVec.y
        } else 0.0
        val clusterOutwardSign = when {
            outwardDot > 1.0 -> 1.0
            outwardDot < -1.0 -> -1.0
            else -> if ((stableTieHash(unit.updateKey) and 1L) == 0L) -1.0 else 1.0
        }

        val selfMass = unit.definition.mass

        for (i in 0 until neighborCount) {
            val peer = checkNotNull(neighborsBuf[i])

            if (ignoredContactUnitId != null && peer.unitId == ignoredContactUnitId) continue

            val relX = peer.position.x - ownPos.x
            val relY = peer.position.y - ownPos.y
            val distSq = relX * relX + relY * relY
            val dist = sqrt(distSq)
            val combinedHard = scale.hardRadius + peer.hardRadius
            if (dist < combinedHard - 1e-4) {
                hasExistingHardOverlap = true
            }
            val combinedComfort = max(
                scale.comfortSpacing + peer.hardRadius * 1.15,
                scale.bufferRadius + peer.bufferRadius + 0.15 * min(scale.hardRadius, peer.hardRadius),
            )
            val peerAnchored = peer.anchored
            val peerMoving = !peerAnchored
            val maxInteractDist = if (peerAnchored) {
                max(combinedComfort * 2.5, combinedHard * 3.8)
            } else {
                max(scale.perceptionRadius + peer.hardRadius, combinedComfort * 2.2)
            }
            if (dist > maxInteractDist) continue
            val peerVx = peer.velX
            val peerVy = peer.velY
            val peerSpeed = peer.velSpeed

            val fwdDot = relX * desiredDir.x + relY * desiredDir.y
            val latSigned = relX * rightVec.x + relY * rightVec.y
            val latAbs = abs(latSigned)
            val peerHalfWidth = transverseHalfWidth(peer.collisionShape, peer.heading, rightVec)
            val sideBySideSpan = (selfHalfWidth + peerHalfWidth) * 2.0 + 4.0
            scratch.pairSpanState = if (localClearanceWorld < sideBySideSpan) 1 else 0 // 0=lazy, 1=false, 2=true

            val ptgDx = peer.position.x - desiredTarget.x
            val ptgDy = peer.position.y - desiredTarget.y
            val peerToGoal = sqrt(ptgDx * ptgDx + ptgDy * ptgDy)
            val peerVerySlow = peerAnchored || peerSpeed < 0.05 * maximumSpeed

            // Single-lane admitted vehicle: ignore waiting opposing traffic outside the mouth and push opposing
            // stragglers inside the lane per ADR-009 (even while they are being displaced backward!), while preserving
            // full 2D ORCA car-following behind same-lane codirectional front vehicles and all static terrain wall half-planes.
            if (isAdmittedSingleLane) {
                val pRad = Math.toRadians(peer.heading)
                val peerOpposing = (sin(pRad) * desiredDir.x - cos(pRad) * desiredDir.y) < 0.15 ||
                    (peerMoving && (peerVx * desiredDir.x + peerVy * desiredDir.y) < -0.10)
                val skipPinnedAnchor = peerVerySlow &&
                    unit.hardContactLimitedTicks >= 18L &&
                    localClearanceWorld >= selfHalfWidth * 2.2
                if (!skipPinnedAnchor &&
                    (peerOpposing || fwdDot <= 0.0 || (peerAnchored && latAbs >= combinedHard * 1.05))
                ) {
                    continue
                }
            }

            // Pure geometric cluster-perimeter vs isolated-contact classification (identical for single & group moves!):
            val clusterPerimeterPeer = peerAnchored && !isRangedPursuer && (
                !peer.displaceableInContact ||
                    anchorCount >= 2 ||
                    (!finalPoint && dist > combinedHard * 1.03 && distToTarget <= scale.hardRadius * 12.0)
                )

            val deferAction = shouldDeferNeighborToContact(
                unit = unit,
                terrain = terrain,
                contactLayer = contactLayer,
                scale = scale,
                selfMass = selfMass,
                peer = peer,
                peerAnchored = peerAnchored,
                peerMoving = peerMoving,
                peerVerySlow = peerVerySlow,
                peerVx = peerVx,
                peerVy = peerVy,
                peerSpeed = peerSpeed,
                desiredDir = desiredDir,
                rightVec = rightVec,
                ownPos = ownPos,
                dist = dist,
                distToTarget = distToTarget,
                peerToGoal = peerToGoal,
                combinedHard = combinedHard,
                fwdDot = fwdDot,
                latAbs = latAbs,
                selfHalfWidth = selfHalfWidth,
                localClearanceWorld = localClearanceWorld,
                corridorOpenness = corridorOpenness,
                sideBySideSpan = sideBySideSpan,
                scratch = scratch,
                clusterPerimeterPeer = clusterPerimeterPeer,
                isRangedPursuer = isRangedPursuer,
                finalPoint = finalPoint,
                terminalDockingWeight = terminalDockingWeight,
                neighborCount = neighborCount,
            )
            if (deferAction != 0) {
                if (deferAction == 2) hasDirectImmovableContact = true
                continue
            }

            val ownWeight = computeResponsibility(selfMass, scale.hardRadius, movement, peer, peerAnchored)

            // --- Boids soft separation ---
            val pairOpenness = if (corridorOpenness >= 0.35) {
                corridorOpenness
            } else if (resolvePairCanPass(scratch, terrain, peer.position, rightVec, sideBySideSpan)) {
                0.35
            } else {
                corridorOpenness
            }
            val sameFlow = peerMoving && peerSpeed > 0.1 * maximumSpeed &&
                ((peerVx * desiredDir.x + peerVy * desiredDir.y) / peerSpeed) > 0.35
            val baseApproachFade = when {
                clusterPerimeterPeer && terminalDockingWeight > 0.05 -> 0.0
                clusterPerimeterPeer -> 0.35 * dockingFade
                finalPoint && !isRangedPursuer -> clamp((distToTarget - scale.hardRadius * 1.5) / (scale.hardRadius * 4.0), 0.12, 1.0) * dockingFade
                else -> dockingFade
            }

            var softRange = when {
                clusterPerimeterPeer -> combinedHard * 1.55
                sameFlow -> combinedComfort * (1.05 + 0.25 * pairOpenness)
                peerAnchored -> combinedComfort * (1.10 + 0.25 * pairOpenness)
                else -> combinedComfort * (1.20 + 0.40 * pairOpenness)
            }
            if (finalPoint && peerAnchored && !isRangedPursuer) {
                softRange = min(softRange, max(combinedHard * 1.005, peerToGoal - 0.5))
            }

            if (dist < softRange && baseApproachFade > 0.02) {
                val awayX: Double
                val awayY: Double
                if (dist > EPS) {
                    awayX = -relX / dist
                    awayY = -relY / dist
                } else {
                    val angle = if (unit.updateKey < peer.updateKey) 0.0 else PI
                    awayX = cos(angle)
                    awayY = sin(angle)
                }
                val closeness = clamp((softRange - dist) / max(1.0, softRange - combinedHard * 0.7), 0.0, 1.25)
                val along = awayX * desiredDir.x + awayY * desiredDir.y
                val lateral = awayX * rightVec.x + awayY * rightVec.y

                val lateralGain = if (clusterPerimeterPeer) max(0.08 + 0.92 * corridorOpenness, pairOpenness) else pairOpenness
                val flowScale = if (sameFlow) 0.55 + 0.12 * pairOpenness else 1.0
                val sepGain = (0.38 + 0.42 * ownWeight) * flowScale * closeness * closeness * maximumSpeed * baseApproachFade
                val alongWeight = if (sameFlow) 0.25 else 0.50
                val effAlong = if ((clusterPerimeterPeer || (peerAnchored && finalPoint && !isRangedPursuer && distToTarget < scale.hardRadius * 6.0)) && along < 0.0) {
                    0.0
                } else along * alongWeight
                val rawEffLateral = if (clusterPerimeterPeer && dist >= combinedHard && abs(lateral) < 0.25 &&
                    resolvePairCanPass(scratch, terrain, peer.position, rightVec, sideBySideSpan)
                ) {
                    clusterOutwardSign * 0.75
                } else lateral
                val effLateral = when {
                    rawEffLateral < 0.0 && leftWallClear < selfHalfWidth * 2.1 -> 0.0
                    rawEffLateral > 0.0 && rightWallClear < selfHalfWidth * 2.1 -> 0.0
                    else -> rawEffLateral
                }
                sepX += (desiredDir.x * effAlong + rightVec.x * (effLateral * lateralGain)) * sepGain
                sepY += (desiredDir.y * effAlong + rightVec.y * (effLateral * lateralGain)) * sepGain
            }

            // --- Preemptive right-hand bias for head-on / opposing traffic ---
            val rightShiftFade = if (dockingFade < 0.25) clamp(dockingFade / 0.25, 0.0, 1.0) else 1.0
            if (peerMoving && !isAdmittedSingleLane && rightShiftFade > 0.05) {
                if (peerSpeed > 0.10 * maximumSpeed) {
                    val headOnCos = (peerVx * desiredDir.x + peerVy * desiredDir.y) / peerSpeed
                    val headOnRange = max(combinedHard * 11.0, (maximumSpeed + peerSpeed) * scale.timeHorizon * 1.45)
                    if (headOnCos < -0.20 && fwdDot > -combinedHard * 0.8 && fwdDot < headOnRange &&
                        resolvePairCanPass(scratch, terrain, peer.position, rightVec, sideBySideSpan)
                    ) {
                        val laneMargin = combinedComfort * (1.85 + 2.15 * pairOpenness)
                        if (latSigned > -laneMargin && latSigned < laneMargin * 1.15) {
                            val urgency = clamp(1.0 - max(0.0, fwdDot) / headOnRange, 0.25, 1.0)
                            val alignment = clamp((-headOnCos - 0.20) / 0.80, 0.30, 1.0)
                            val sideBoost = if (latSigned >= -combinedHard * 0.6) 1.35 else {
                                clamp(1.0 - (-latSigned) / laneMargin, 0.20, 1.0)
                            }
                            rightShift += (0.75 + 0.65 * pairOpenness) * urgency * alignment * sideBoost * maximumSpeed * rightShiftFade
                        }
                    }
                }
            }

            // --- Wide-road overtaking & ranged-arc flanking ---
            overtakeShift += computeOvertakeAndFlankShift(
                unit = unit,
                terrain = terrain,
                scale = scale,
                ownPos = ownPos,
                peer = peer,
                peerVx = peerVx,
                peerVy = peerVy,
                peerSpeed = peerSpeed,
                peerAnchored = peerAnchored,
                peerVerySlow = peerVerySlow,
                sameFlow = sameFlow,
                desiredDir = desiredDir,
                rightVec = rightVec,
                desiredSpeed = desiredSpeed,
                maximumSpeed = maximumSpeed,
                distToTarget = distToTarget,
                combinedHard = combinedHard,
                combinedComfort = combinedComfort,
                fwdDot = fwdDot,
                latSigned = latSigned,
                latAbs = latAbs,
                selfHalfWidth = selfHalfWidth,
                peerHalfWidth = peerHalfWidth,
                sideBySideSpan = sideBySideSpan,
                scratch = scratch,
                pairOpenness = pairOpenness,
                clusterPerimeterPeer = clusterPerimeterPeer,
                dockingFade = dockingFade,
                isRangedPursuer = isRangedPursuer,
                clusterOutwardSign = clusterOutwardSign,
                engagementArcCenter = engagementArcCenter,
                engagementArcRadius = engagementArcRadius,
                neighborsBuf = neighborsBuf,
                neighborCount = neighborCount,
                ignoredContactUnitId = ignoredContactUnitId,
            )

            // --- 3. Full 2D ORCA half-plane for this peer ---
            if (terminalDockingWeight > 0.5 && dist > combinedHard * 1.35) continue
            if (clusterPerimeterPeer && dist > combinedHard * 1.85) continue
            if (corridorOpenness <= 0.05 && latAbs < combinedHard * 1.25 &&
                peerMoving && peerSpeed > EPS && abs((peerVx * desiredDir.x + peerVy * desiredDir.y) / peerSpeed) < 0.35 &&
                !resolvePairCanPass(scratch, terrain, peer.position, rightVec, sideBySideSpan)
            ) {
                continue
            }

            val skinFactor = when {
                clusterPerimeterPeer -> 1.002
                terminalDockingWeight > 0.25 -> 1.004
                peerAnchored && finalPoint && distToTarget < scale.hardRadius * 5.0 -> 1.008
                finalPoint && distToTarget < scale.hardRadius * 4.0 -> 1.01
                else -> 1.05
            }
            val lineIdx = scratch.dynCount
            val addedOrca = if (lineIdx < scratch.dynPx.size) {
                buildDynamicOrcaPlaneIntoPrim(
                    relX = relX,
                    relY = relY,
                    ownVx = ownVel.x,
                    ownVy = ownVel.y,
                    peerVx = peerVx,
                    peerVy = peerVy,
                    combinedRadius = combinedHard * skinFactor,
                    timeHorizon = when {
                        clusterPerimeterPeer || terminalDockingWeight > 0.25 -> scale.timeHorizon * 0.45
                        peerAnchored -> scale.timeHorizon * 0.65
                        peerMoving && peerSpeed > EPS &&
                            (peerVx * desiredDir.x + peerVy * desiredDir.y) / peerSpeed < -0.2 -> scale.timeHorizon * 1.25
                        else -> scale.timeHorizon * 0.85
                    },
                    delta = delta,
                    responsibility = ownWeight,
                    desDirX = desiredDir.x,
                    desDirY = desiredDir.y,
                    peerMoving = peerMoving,
                    tieBreakSign = if (unit.updateKey < peer.updateKey) 1.0 else -1.0,
                    outIdx = lineIdx,
                    scratch = scratch,
                )
            } else false
            if (addedOrca) {
                val sameClusterParked = protectedClusterKey != null &&
                    peer.parkedClusterKey === protectedClusterKey
                val canPushPeerBase = peerAnchored &&
                    peer.displaceableInContact &&
                    !sameClusterParked &&
                    canPushMassFrozen(selfMass, peer.mass)
                val peerWallPinned = if (canPushPeerBase) {
                    val probeDist = peer.hardRadius + 6.0
                    !isPointClearOnTerrain(
                        terrain,
                        peer.position.x + desiredDir.x * probeDist,
                        peer.position.y + desiredDir.y * probeDist,
                        max(1.0, peer.hardRadius * 0.85),
                    )
                } else false
                val isPushableStationary = canPushPeerBase && !peerWallPinned
                scratch.dynCount = lineIdx + 1
                if (lineIdx < dynamicPushable.size) dynamicPushable[lineIdx] = isPushableStationary
                if (isPushableStationary && dist <= combinedHard * 1.45 && fwdDot > -0.25) {
                    hasClosePushableBlocker = true
                }
                activeDynamicConstraints++
            }
        }

        scratch.sepX = sepX
        scratch.sepY = sepY
        scratch.rightShift = rightShift
        scratch.overtakeShift = overtakeShift
        scratch.activeDynamicConstraints = activeDynamicConstraints
        scratch.hasExistingHardOverlap = hasExistingHardOverlap
        scratch.hasDirectImmovableContact = hasDirectImmovableContact
        scratch.hasClosePushableBlocker = hasClosePushableBlocker
        scratch.clusterOutwardSign = clusterOutwardSign
    }

    private fun resolvePairCanPass(
        scratch: Scratch,
        terrain: PlanningTerrain,
        peerPos: Position,
        rightVec: Vec2,
        sideBySideSpan: Double,
    ): Boolean {
        val s = scratch.pairSpanState
        if (s != 0) return s == 2
        val ok = peerHasTransverseSpan(terrain, peerPos, rightVec, sideBySideSpan)
        scratch.pairSpanState = if (ok) 2 else 1
        return ok
    }

    private fun peerHasTransverseSpan(
        terrain: PlanningTerrain,
        peerPos: Position,
        rightVec: Vec2,
        sideBySideSpan: Double,
    ): Boolean {
        val tw = terrain.tileWidth.toDouble()
        val th = terrain.tileHeight.toDouble()
        val minTile = min(tw, th)
        val halfSpan = sideBySideSpan * 0.5
        val neededRings = kotlin.math.ceil(halfSpan / minTile).toInt() + 2
        val w = terrain.widthTiles
        val h = terrain.heightTiles
        val pCol = floor(peerPos.x / tw).toInt().coerceIn(0, w - 1)
        val pRow = floor(peerPos.y / th).toInt().coerceIn(0, h - 1)
        val margin = halfSpan + minTile
        val worldW = w * tw
        val worldH = h * th
        val cf = terrain.clearanceField()
        val isSub1 = cf.width == w && cf.height == h
        val centerRings = if (isSub1) cf.clearanceRingsAt(pRow * w + pCol) else terrain.clearanceRings(pCol, pRow)
        if (centerRings >= neededRings &&
            peerPos.x > margin && peerPos.x < worldW - margin &&
            peerPos.y > margin && peerPos.y < worldH - margin
        ) {
            return true
        }
        // Keep `capReach` an exact multiple of `step = minTile * 0.5` so walls near `sideBySideSpan`
        // are never stepped past by `estimateRayClearDistanceXY`.
        val capReach = minTile * 5.0
        val fwdX = rightVec.y
        val fwdY = -rightVec.x
        val bx = peerPos.x - fwdX * minTile
        val by = peerPos.y - fwdY * minTile
        val lHere = estimateSideClearAtXY(terrain, cf, isSub1, peerPos.x, peerPos.y, rightVec, -1.0, capReach)
        val rHere = estimateSideClearAtXY(terrain, cf, isSub1, peerPos.x, peerPos.y, rightVec, 1.0, capReach)
        val lBehind = estimateSideClearAtXY(terrain, cf, isSub1, bx, by, rightVec, -1.0, capReach)
        val rBehind = estimateSideClearAtXY(terrain, cf, isSub1, bx, by, rightVec, 1.0, capReach)
        if (min(lHere, lBehind) + min(rHere, rBehind) >= sideBySideSpan) return true
        if (max(lHere, lBehind) + max(rHere, rBehind) < sideBySideSpan) return false
        val ax = peerPos.x + fwdX * minTile
        val ay = peerPos.y + fwdY * minTile
        val lAhead = estimateSideClearAtXY(terrain, cf, isSub1, ax, ay, rightVec, -1.0, capReach)
        val rAhead = estimateSideClearAtXY(terrain, cf, isSub1, ax, ay, rightVec, 1.0, capReach)
        val medLeft = max(min(lBehind, lHere), min(max(lBehind, lHere), lAhead))
        val medRight = max(min(rBehind, rHere), min(max(rBehind, rHere), rAhead))
        return medLeft + medRight >= sideBySideSpan
    }

    /** Returns 0 = keep peer in ORCA/Boids, 1 = defer to UnitContact, 2 = defer + mark direct immovable contact. */
    private fun shouldDeferNeighborToContact(
        unit: MutableUnit,
        terrain: PlanningTerrain,
        contactLayer: ContactLayer,
        scale: KinematicScale,
        selfMass: Double?,
        peer: FrozenSpatialUnit,
        peerAnchored: Boolean,
        peerMoving: Boolean,
        peerVerySlow: Boolean,
        peerVx: Double,
        peerVy: Double,
        peerSpeed: Double,
        desiredDir: Vec2,
        rightVec: Vec2,
        ownPos: Position,
        dist: Double,
        distToTarget: Double,
        peerToGoal: Double,
        combinedHard: Double,
        fwdDot: Double,
        latAbs: Double,
        selfHalfWidth: Double,
        localClearanceWorld: Double,
        corridorOpenness: Double,
        sideBySideSpan: Double,
        scratch: Scratch,
        clusterPerimeterPeer: Boolean,
        isRangedPursuer: Boolean,
        finalPoint: Boolean,
        terminalDockingWeight: Double,
        neighborCount: Int,
    ): Int {
        if (peerAnchored && fwdDot > 0.0 && latAbs < combinedHard &&
            (unit.hardContactLimitedTicks < 18L || localClearanceWorld < selfHalfWidth * 2.2) &&
            !resolvePairCanPass(scratch, terrain, peer.position, rightVec, sideBySideSpan)
        ) {
            return 1
        }
        if (corridorOpenness <= 0.05 &&
            fwdDot > 0.0 && latAbs < combinedHard * 1.25 &&
            peerMoving && peerSpeed > EPS && abs((peerVx * desiredDir.x + peerVy * desiredDir.y) / peerSpeed) < 0.35 &&
            !resolvePairCanPass(scratch, terrain, peer.position, rightVec, sideBySideSpan)
        ) {
            return 1
        }
        if (peerAnchored && fwdDot > 0.0 && latAbs < combinedHard * 0.5 && dist < combinedHard * 3.0) {
            val leftCol = floor((ownPos.x - rightVec.x * combinedHard * 1.15) / terrain.tileWidth).toInt().coerceIn(0, terrain.widthTiles - 1)
            val leftRow = floor((ownPos.y - rightVec.y * combinedHard * 1.15) / terrain.tileHeight).toInt().coerceIn(0, terrain.heightTiles - 1)
            val rightCol = floor((ownPos.x + rightVec.x * combinedHard * 1.15) / terrain.tileWidth).toInt().coerceIn(0, terrain.widthTiles - 1)
            val rightRow = floor((ownPos.y + rightVec.y * combinedHard * 1.15) / terrain.tileHeight).toInt().coerceIn(0, terrain.heightTiles - 1)
            if (terrain.isBlocked(leftCol, leftRow) && terrain.isBlocked(rightCol, rightRow)) {
                return 1
            }
        }
        val peerMass = peer.mass
        val heavyDominance = selfMass != null && peerMass != null &&
            (selfMass >= peerMass * 8.0 || (dist <= combinedHard * 1.05 && selfMass >= peerMass * 3.0))
        if (heavyDominance && fwdDot > 0.0 && latAbs < combinedHard * 0.45 &&
            canPushMassFrozen(selfMass, peerMass) && peer.displaceableInContact
        ) {
            return 1
        }
        if (peerAnchored && contactLayer == ContactLayer.SUBMERGED && neighborCount == 1 &&
            fwdDot > 0.0 && latAbs < combinedHard * 0.25 &&
            canPushMassFrozen(selfMass, peerMass) && peer.displaceableInContact
        ) {
            return 1
        }
        if (peerVerySlow && fwdDot > 0.0 && latAbs <= scale.hardRadius * 0.12 && peer.hasAttachments) {
            return 1
        }
        // Direct bow-on contact (already touching at dist <= 1.005 * combinedHard): defer to ADR-009 UnitContact.
        if (peerAnchored && dist <= combinedHard * 1.005 && fwdDot > 0.0 && latAbs < combinedHard * 0.35 && !isRangedPursuer) {
            return if (!peer.displaceableInContact) 2 else 1
        }
        if (!clusterPerimeterPeer) {
            if (finalPoint && peerVerySlow && !isRangedPursuer && peerToGoal <= combinedHard + 6.0) {
                return 1
            }
            if (peerAnchored && dist <= combinedHard * 1.03 && fwdDot > 0.0 && latAbs < combinedHard * 0.35 &&
                !isRangedPursuer &&
                (unit.hardContactLimitedTicks < 18L || localClearanceWorld < selfHalfWidth * 2.2)
            ) {
                return 1
            }
        }
        if (finalPoint && peerAnchored && !isRangedPursuer && peerToGoal < combinedHard * 0.98 && dist <= combinedHard * 2.2 &&
            (!clusterPerimeterPeer || terminalDockingWeight > 0.05)
        ) {
            return 1
        }
        if (terminalDockingWeight > 0.05 && peerAnchored && distToTarget <= scale.hardRadius * 3.0) {
            if (fwdDot <= 0.0 || latAbs > combinedHard * 0.98) {
                return 1
            }
        }
        return 0
    }

    private fun computeOvertakeAndFlankShift(
        unit: MutableUnit,
        terrain: PlanningTerrain,
        scale: KinematicScale,
        ownPos: Position,
        peer: FrozenSpatialUnit,
        peerVx: Double,
        peerVy: Double,
        peerSpeed: Double,
        peerAnchored: Boolean,
        peerVerySlow: Boolean,
        sameFlow: Boolean,
        desiredDir: Vec2,
        rightVec: Vec2,
        desiredSpeed: Double,
        maximumSpeed: Double,
        distToTarget: Double,
        combinedHard: Double,
        combinedComfort: Double,
        fwdDot: Double,
        latSigned: Double,
        latAbs: Double,
        selfHalfWidth: Double,
        peerHalfWidth: Double,
        sideBySideSpan: Double,
        scratch: Scratch,
        pairOpenness: Double,
        clusterPerimeterPeer: Boolean,
        dockingFade: Double,
        isRangedPursuer: Boolean,
        clusterOutwardSign: Double,
        engagementArcCenter: Position?,
        engagementArcRadius: Double,
        neighborsBuf: Array<FrozenSpatialUnit?>,
        neighborCount: Int,
        ignoredContactUnitId: String?,
    ): Double {
        var shift = 0.0
        if (!clusterPerimeterPeer && dockingFade > 0.25) {
            val peerAlongSpeed = peerVx * desiredDir.x + peerVy * desiredDir.y
            val closingRate = desiredSpeed - peerAlongSpeed
            val wantsPass = peerVerySlow ||
                (sameFlow && closingRate > max(0.12 * maximumSpeed, 0.18 * desiredSpeed))
            val overtakeHorizon = min(distToTarget, max(combinedHard * 5.0, max(0.1, closingRate) * scale.timeHorizon * 1.65))
            val overtakeLatLimit = if (peerVerySlow) combinedHard * 1.15 else (selfHalfWidth + peerHalfWidth) * 1.35 + 4.0
            if (wantsPass && fwdDot > 0.0 && fwdDot < overtakeHorizon && latAbs < overtakeLatLimit &&
                resolvePairCanPass(scratch, terrain, peer.position, rightVec, sideBySideSpan)
            ) {
                val passClear = peerHalfWidth + selfHalfWidth + max(4.0, selfHalfWidth * 0.45)
                val rightProbe = Position(peer.position.x + rightVec.x * passClear, peer.position.y + rightVec.y * passClear)
                val leftProbe = Position(peer.position.x - rightVec.x * passClear, peer.position.y - rightVec.y * passClear)
                val checkR = max(0.0, scale.terrainRadius * 0.92)
                val rightTerrainClear = isPointClearOnTerrain(terrain, rightProbe, checkR) &&
                    PlanningSegmentClearance.allowsClearance(terrain, ownPos, rightProbe, checkR)
                val leftTerrainClear = isPointClearOnTerrain(terrain, leftProbe, checkR) &&
                    PlanningSegmentClearance.allowsClearance(terrain, ownPos, leftProbe, checkR)
                val rightFree = if (isRangedPursuer) {
                    rightTerrainClear && isProbeClearOfAnchoredPeers(
                        rightProbe, peer.unitId, selfHalfWidth, neighborsBuf, neighborCount, ignoredContactUnitId,
                    )
                } else rightTerrainClear
                val leftFree = if (isRangedPursuer) {
                    leftTerrainClear && isProbeClearOfAnchoredPeers(
                        leftProbe, peer.unitId, selfHalfWidth, neighborsBuf, neighborCount, ignoredContactUnitId,
                    )
                } else leftTerrainClear
                if (rightTerrainClear || leftTerrainClear) {
                    val sideSign = when {
                        rightFree && !leftFree -> 1.0
                        leftFree && !rightFree -> -1.0
                        !rightFree && !leftFree -> when {
                            rightTerrainClear && !leftTerrainClear -> 1.0
                            leftTerrainClear && !rightTerrainClear -> -1.0
                            else -> clusterOutwardSign
                        }
                        latSigned > selfHalfWidth * 0.25 -> -1.0
                        latSigned < -selfHalfWidth * 0.25 -> 1.0
                        else -> if ((stableTieHash(unit.updateKey) and 1L) == 0L) -1.0 else 1.0
                    }
                    val urgency = clamp(1.0 - max(0.0, fwdDot) / max(1.0, overtakeHorizon), 0.30, 1.0)
                    val overlapAlign = clamp(1.0 - latAbs / max(1.0, overtakeLatLimit), 0.25, 1.0)
                    shift += sideSign * (0.58 + 0.37 * pairOpenness) * urgency * overlapAlign * desiredSpeed
                }
            }
        }

        if (engagementArcCenter != null && engagementArcRadius > scale.hardRadius * 4.0 &&
            peer.ownerPlayerId == unit.ownerPlayerId
        ) {
            val dxE = engagementArcCenter.x - ownPos.x
            val dyE = engagementArcCenter.y - ownPos.y
            val distToEnemy = sqrt(dxE * dxE + dyE * dyE)
            if (distToEnemy > engagementArcRadius * 0.95 && distToEnemy < engagementArcRadius * 2.4 &&
                fwdDot > 0.0 && fwdDot < combinedComfort * 3.0 && latAbs < combinedComfort * 1.45 &&
                (peerAnchored || peerSpeed < 0.25 * maximumSpeed)
            ) {
                val passClear = peerHalfWidth + selfHalfWidth + max(4.0, selfHalfWidth * 0.45)
                val rightProbe = Position(peer.position.x + rightVec.x * passClear, peer.position.y + rightVec.y * passClear)
                val leftProbe = Position(peer.position.x - rightVec.x * passClear, peer.position.y - rightVec.y * passClear)
                val checkR = max(0.0, scale.terrainRadius * 0.92)
                val rightTerrainClear = isPointClearOnTerrain(terrain, rightProbe, checkR)
                val leftTerrainClear = isPointClearOnTerrain(terrain, leftProbe, checkR)
                val rightFree = rightTerrainClear && isProbeClearOfAnchoredPeers(
                    rightProbe, peer.unitId, selfHalfWidth, neighborsBuf, neighborCount, ignoredContactUnitId,
                )
                val leftFree = leftTerrainClear && isProbeClearOfAnchoredPeers(
                    leftProbe, peer.unitId, selfHalfWidth, neighborsBuf, neighborCount, ignoredContactUnitId,
                )
                val fanSign = when {
                    rightFree && !leftFree -> 1.0
                    leftFree && !rightFree -> -1.0
                    !rightFree && !leftFree -> when {
                        rightTerrainClear && !leftTerrainClear -> 1.0
                        leftTerrainClear && !rightTerrainClear -> -1.0
                        else -> clusterOutwardSign
                    }
                    latSigned > scale.hardRadius * 0.2 -> -1.0
                    latSigned < -scale.hardRadius * 0.2 -> 1.0
                    else -> if ((stableTieHash(unit.updateKey) and 1L) == 0L) -1.0 else 1.0
                }
                shift += fanSign * (0.78 * desiredSpeed) * clamp(1.0 - fwdDot / (combinedComfort * 3.0), 0.35, 1.0)
            }
        }
        return shift
    }

    private fun finalizeGroundLocomotion(
        unit: MutableUnit,
        terrain: PlanningTerrain,
        scale: KinematicScale,
        desiredTarget: Position,
        baseSpeedTarget: Double,
        desiredDir: Vec2,
        rightVec: Vec2,
        distToTarget: Double,
        desiredSpeed: Double,
        basePrefVel: Vec2,
        maximumSpeed: Double,
        finalPoint: Boolean,
        delta: Double,
        terminalDockingWeight: Double,
        ownPos: Position,
        corridorOpenness: Double,
        neighborCount: Int,
        scratch: Scratch,
    ): SolveResult {
        val sepX = scratch.sepX
        val sepY = scratch.sepY
        val rightShift = scratch.rightShift
        val overtakeShift = scratch.overtakeShift
        val activeDynamicConstraints = scratch.activeDynamicConstraints
        val hasExistingHardOverlap = scratch.hasExistingHardOverlap
        val hasDirectImmovableContact = scratch.hasDirectImmovableContact
        val hasClosePushableBlocker = scratch.hasClosePushableBlocker
        val clusterOutwardSign = scratch.clusterOutwardSign
        val dynCount = scratch.dynCount
        val dynPx = scratch.dynPx; val dynPy = scratch.dynPy
        val dynNx = scratch.dynNx; val dynNy = scratch.dynNy
        val dynDx = scratch.dynDx; val dynDy = scratch.dynDy
        val dynamicPushable = scratch.dynamicPushable

        val rawSepLen = sqrt(sepX * sepX + sepY * sepY)
        if (activeDynamicConstraints == 0 && rawSepLen <= EPS && rightShift <= EPS && abs(overtakeShift) <= EPS) {
            return passthrough(unit, desiredTarget, baseSpeedTarget, maximumSpeed)
        }

        val maxSepMag = maximumSpeed * (0.45 + 0.35 * corridorOpenness)
        var clampedSep = if (rawSepLen > maxSepMag && rawSepLen > EPS) {
            Vec2(sepX * (maxSepMag / rawSepLen), sepY * (maxSepMag / rawSepLen))
        } else Vec2(sepX, sepY)
        val clampedRight = min(rightShift, maximumSpeed * (0.65 + 0.55 * corridorOpenness))
        val maxOvertake = maximumSpeed * (0.55 + 0.35 * corridorOpenness)
        val clampedOvertake = if (clampedRight > 0.15 * maximumSpeed && overtakeShift < 0.0) 0.0
        else clamp(overtakeShift, -maxOvertake, maxOvertake)
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

        // 4. Static terrain wall half-planes using the unit's unified terrain passability radius.
        val wallLines = scratch.wallLines
        wallLines.clear()
        val wallR = if (scale.terrainRadius > 0.0) scale.terrainRadius else 0.5
        buildStaticWallHalfPlanesInto(
            terrain = terrain,
            pos = ownPos,
            wallRadius = wallR,
            boundaryRadius = max(wallR, UnitLocomotion.boundaryRadius(unit)),
            wallTimeHorizon = scale.wallTimeHorizon,
            delta = delta,
            maxSpeed = maximumSpeed,
            candsBuf = scratch.wallCands,
            outLines = wallLines,
        )

        val numFixed = wallLines.size
        val lpOut = solveLinearProgramWallAndDynPrim(
            wallLines = wallLines,
            dynCount = dynCount,
            skipPushable = false,
            dynamicPushable = dynamicPushable,
            maxSpeed = maximumSpeed,
            prefVel = prefVel,
            scratch = scratch,
        )
        val vSolved = lpOut.velocity

        // Tangential LP-feasible nudge around stationary blocker arcs (pure tie-break, zero upper-command reads).
        var vFinal = if (!hasExistingHardOverlap && vSolved.length() < 0.08 * maximumSpeed && desiredSpeed > 0.1 * maximumSpeed && terminalDockingWeight < 0.75) {
            val worstDyn = if (lpOut.used3dFallback) {
                var minM = Double.POSITIVE_INFINITY
                for (i in 0 until dynCount) {
                    val m = (vSolved.x - dynPx[i]) * dynNx[i] + (vSolved.y - dynPy[i]) * dynNy[i]
                    if (m < minM) minM = m
                }
                if (minM.isFinite()) minM else 0.0
            } else 0.0
            val prefSign = clusterOutwardSign
            val spd = min(desiredSpeed, maximumSpeed * 0.35)
            var chosen: Vec2? = null
            for (cIdx in 0 until 6) {
                val cand = when (cIdx) {
                    0 -> (desiredDir * 0.45 + rightVec * (0.89 * prefSign)).normalized() * spd
                    1 -> (rightVec * prefSign) * spd
                    2 -> (desiredDir * 0.45 - rightVec * (0.89 * prefSign)).normalized() * spd
                    3 -> (rightVec * -prefSign) * spd
                    4 -> (desiredDir * -0.25 + rightVec * (0.97 * prefSign)).normalized() * (spd * 0.8)
                    else -> (desiredDir * -0.25 - rightVec * (0.97 * prefSign)).normalized() * (spd * 0.8)
                }
                if (isCandidateLpFeasiblePrim(cand, wallLines, dynCount, dynPx, dynPy, dynNx, dynNy, worstDyn)) {
                    chosen = cand
                    break
                }
            }
            chosen ?: vSolved
        } else vSolved

        val stalledPushInTransit = (!finalPoint || distToTarget > scale.hardRadius * 6.0) &&
            unit.hardContactLimitedTicks >= 18L
        if (!hasDirectImmovableContact && hasClosePushableBlocker &&
            !stalledPushInTransit &&
            desiredSpeed > 0.1 * maximumSpeed &&
            terminalDockingWeight < 0.25 &&
            vFinal.dot(desiredDir) < 0.18 * desiredSpeed
        ) {
            val pushVel = solveLinearProgramWallAndDynPrim(
                wallLines = wallLines,
                dynCount = dynCount,
                skipPushable = true,
                dynamicPushable = dynamicPushable,
                maxSpeed = maximumSpeed,
                prefVel = basePrefVel,
                scratch = scratch,
            ).velocity
            if (pushVel.dot(desiredDir) > vFinal.dot(desiredDir) + 0.08 * maximumSpeed) {
                vFinal = pushVel
            }
        }

        if (hasDirectImmovableContact || (vFinal.length() <= 0.05 * maximumSpeed && lpOut.used3dFallback && hasExistingHardOverlap)) {
            vFinal = solveLinearProgramScratch(
                wallLines, 0, maximumSpeed, basePrefVel, scratch.projLines, scratch.lpOut,
            ).velocity
        }

        val solvedSpeed = vFinal.length()
        if (solvedSpeed <= EPS) {
            return SolveResult(
                applied = true,
                solvedVelocity = Vec2.ZERO,
                targetHeadingDeg = unit.heading,
                speedTarget = 0.0,
                requestedFraction = 0.0,
                neighborCount = neighborCount,
                wallConstraintCount = wallLines.size,
                used3dFallback = lpOut.used3dFallback,
                headingErrorDeg = 0.0,
                nextAvoidHeadingDeg = unit.lastAvoidHeadingDeg,
            )
        }

        val rawTargetHeading = headingFromVector(vFinal)
        val prevAvoid = unit.lastAvoidHeadingDeg
        val travelHeading = if (unit.reversing) ScreenPlane.normalize(unit.heading + 180.0) else unit.heading
        val rawDiffFromCurrent = ScreenPlane.difference(rawTargetHeading, travelHeading)

        val sectorClampedHeading = if (abs(rawDiffFromCurrent) > 165.0 && prevAvoid != null) {
            val prevSide = ScreenPlane.difference(prevAvoid, travelHeading)
            if (abs(prevSide) > 1.0) {
                val turnSign = if (prevSide < 0.0) -1.0 else 1.0
                ScreenPlane.normalize(travelHeading + turnSign * 165.0)
            } else rawTargetHeading
        } else rawTargetHeading

        val stabilizedHeading = if (prevAvoid != null) {
            val deltaFromPrev = ScreenPlane.difference(sectorClampedHeading, prevAvoid)
            when {
                abs(deltaFromPrev) < 2.5 -> prevAvoid
                abs(rawDiffFromCurrent) > 110.0 -> sectorClampedHeading
                abs(deltaFromPrev) > 35.0 && abs(rawDiffFromCurrent) > 20.0 -> {
                    ScreenPlane.normalize(prevAvoid + deltaFromPrev * 0.72)
                }
                else -> {
                    ScreenPlane.normalize(prevAvoid + deltaFromPrev * 0.85)
                }
            }
        } else sectorClampedHeading

        val headingErrorAfterTurn = ScreenPlane.difference(stabilizedHeading, travelHeading)
        val absErr = abs(headingErrorAfterTurn)
        val cosThrottle = when {
            absErr <= 12.0 -> 1.0
            absErr >= 95.0 -> 0.0
            else -> {
                val rad = Math.toRadians((absErr - 12.0) * (90.0 / 83.0))
                max(0.0, cos(rad))
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
            neighborCount = neighborCount,
            wallConstraintCount = wallLines.size,
            used3dFallback = lpOut.used3dFallback,
            headingErrorDeg = headingErrorAfterTurn,
            nextAvoidHeadingDeg = stabilizedHeading,
        )
    }

    private fun isCandidateLpFeasiblePrim(
        cand: Vec2,
        wallLines: ArrayList<HalfPlane>,
        dynCount: Int,
        dynPx: DoubleArray,
        dynPy: DoubleArray,
        dynNx: DoubleArray,
        dynNy: DoubleArray,
        worstDyn: Double,
    ): Boolean {
        for (i in 0 until wallLines.size) {
            if (wallLines[i].signedMargin(cand) < -1e-4) return false
        }
        for (i in 0 until dynCount) {
            if ((cand.x - dynPx[i]) * dynNx[i] + (cand.y - dynPy[i]) * dynNy[i] < worstDyn - 1e-3) return false
        }
        return true
    }

    private fun solveLinearProgramWallAndDynPrim(
        wallLines: ArrayList<HalfPlane>,
        dynCount: Int,
        skipPushable: Boolean,
        dynamicPushable: BooleanArray,
        maxSpeed: Double,
        prefVel: Vec2,
        scratch: Scratch,
    ): LpResult {
        val px = scratch.lpPx; val py = scratch.lpPy
        val nx = scratch.lpNx; val ny = scratch.lpNy
        val dx = scratch.lpDx; val dy = scratch.lpDy
        val numFixed = wallLines.size
        var n = 0
        for (i in 0 until numFixed) {
            if (n >= px.size) break
            val hp = wallLines[i]
            px[n] = hp.point.x; py[n] = hp.point.y
            nx[n] = hp.normal.x; ny[n] = hp.normal.y
            dx[n] = hp.direction.x; dy[n] = hp.direction.y
            n++
        }
        val dPx = scratch.dynPx; val dPy = scratch.dynPy
        val dNx = scratch.dynNx; val dNy = scratch.dynNy
        val dDx = scratch.dynDx; val dDy = scratch.dynDy
        for (i in 0 until dynCount) {
            if (skipPushable && i < dynamicPushable.size && dynamicPushable[i]) continue
            if (n >= px.size) break
            px[n] = dPx[i]; py[n] = dPy[i]
            nx[n] = dNx[i]; ny[n] = dNy[i]
            dx[n] = dDx[i]; dy[n] = dDy[i]
            n++
        }
        val res = scratch.lpOutXY
        val lineFail = linearProgram2Prim(
            px, py, nx, ny, dx, dy, n, maxSpeed,
            prefVel.x, prefVel.y, directionOpt = false,
            tieX = prefVel.x, tieY = prefVel.y, outXY = res,
        )
        if (lineFail < n) {
            linearProgram3Prim(
                px, py, nx, ny, dx, dy, n, numFixed, lineFail, maxSpeed,
                prefVel.x, prefVel.y,
                scratch.projPx, scratch.projPy, scratch.projNx, scratch.projNy, scratch.projDx, scratch.projDy,
                outXY = res,
            )
            return LpResult(Vec2(res[0], res[1]), used3dFallback = true)
        }
        return LpResult(Vec2(res[0], res[1]), used3dFallback = false)
    }

    /** Zero-allocation primitive ORCA half-plane writer mathematically identical to [buildDynamicOrcaPlane]. */
    private fun buildDynamicOrcaPlaneIntoPrim(
        relX: Double,
        relY: Double,
        ownVx: Double,
        ownVy: Double,
        peerVx: Double,
        peerVy: Double,
        combinedRadius: Double,
        timeHorizon: Double,
        delta: Double,
        responsibility: Double,
        desDirX: Double,
        desDirY: Double,
        peerMoving: Boolean,
        tieBreakSign: Double,
        outIdx: Int,
        scratch: Scratch,
    ): Boolean {
        val distSq = relX * relX + relY * relY
        val combinedRadiusSq = combinedRadius * combinedRadius
        val relVx = ownVx - peerVx
        val relVy = ownVy - peerVy
        val invTau = 1.0 / max(delta, timeHorizon)

        val ux: Double
        val uy: Double
        val normX: Double
        val normY: Double

        if (distSq > combinedRadiusSq) {
            val wx = relVx - relX * invTau
            val wy = relVy - relY * invTau
            val wLenSq = wx * wx + wy * wy
            val dotProduct1 = wx * relX + wy * relY

            if (dotProduct1 < 0.0 && dotProduct1 * dotProduct1 > combinedRadiusSq * wLenSq) {
                val wLen = sqrt(wLenSq)
                val unitWx: Double
                val unitWy: Double
                if (wLen > EPS) {
                    unitWx = wx / wLen; unitWy = wy / wLen
                } else {
                    unitWx = -desDirY; unitWy = desDirX
                }
                normX = unitWx; normY = unitWy
                val scaleU = combinedRadius * invTau - wLen
                ux = unitWx * scaleU; uy = unitWy * scaleU
            } else {
                val leg = sqrt(max(0.0, distSq - combinedRadiusSq))
                val cross = relX * wy - relY * wx
                val peerSpeed = sqrt(peerVx * peerVx + peerVy * peerVy)
                val opposing = peerMoving && peerSpeed > EPS &&
                    (peerVx * desDirX + peerVy * desDirY) / peerSpeed < -0.20
                val dist = sqrt(distSq)
                val wLen = sqrt(wLenSq)
                val biasThreshold = when {
                    opposing -> 0.42 * dist * max(0.25, wLen)
                    peerMoving -> 0.18 * dist * max(0.2, wLen)
                    else -> 0.04 * dist * max(0.1, wLen)
                }
                val chooseRightLeg = when {
                    cross > biasThreshold -> true
                    cross < -biasThreshold -> false
                    else -> if (opposing) true else tieBreakSign >= 0.0
                }
                val rawLx: Double
                val rawLy: Double
                if (chooseRightLeg) {
                    rawLx = (relX * leg - relY * combinedRadius) / distSq
                    rawLy = (relX * combinedRadius + relY * leg) / distSq
                } else {
                    rawLx = (relX * leg + relY * combinedRadius) / distSq
                    rawLy = (-relX * combinedRadius + relY * leg) / distSq
                }
                val lLen = sqrt(rawLx * rawLx + rawLy * rawLy)
                val legDirX = if (lLen > EPS) rawLx / lLen else 0.0
                val legDirY = if (lLen > EPS) rawLy / lLen else 0.0
                if (chooseRightLeg) {
                    normX = -legDirY; normY = legDirX
                } else {
                    normX = legDirY; normY = -legDirX
                }
                val proj = relVx * legDirX + relVy * legDirY
                ux = legDirX * proj - relVx
                uy = legDirY * proj - relVy
            }
        } else {
            val invStep = 1.0 / max(delta, 2.0)
            val wx = relVx - relX * invStep
            val wy = relVy - relY * invStep
            val wLen = sqrt(wx * wx + wy * wy)
            val unitWx: Double
            val unitWy: Double
            if (wLen > EPS) {
                unitWx = wx / wLen; unitWy = wy / wLen
            } else {
                val dist = sqrt(distSq)
                if (dist > EPS) {
                    unitWx = -relX / dist; unitWy = -relY / dist
                } else {
                    unitWx = -desDirY; unitWy = desDirX
                }
            }
            normX = unitWx; normY = unitWy
            val scaleU = combinedRadius * invStep - wLen
            ux = unitWx * scaleU; uy = unitWy * scaleU
        }

        val nLen = sqrt(normX * normX + normY * normY)
        if (nLen <= EPS) return false
        val unx = normX / nLen
        val uny = normY / nLen
        scratch.dynPx[outIdx] = ownVx + ux * responsibility
        scratch.dynPy[outIdx] = ownVy + uy * responsibility
        scratch.dynNx[outIdx] = unx
        scratch.dynNy[outIdx] = uny
        scratch.dynDx[outIdx] = uny
        scratch.dynDy[outIdx] = -unx
        return true
    }

    private fun isCandidateLpFeasible(
        cand: Vec2,
        wallLines: ArrayList<HalfPlane>,
        dynamicLines: ArrayList<HalfPlane>,
        worstDyn: Double,
    ): Boolean {
        for (i in 0 until wallLines.size) {
            if (wallLines[i].signedMargin(cand) < -1e-4) return false
        }
        for (i in 0 until dynamicLines.size) {
            if (dynamicLines[i].signedMargin(cand) < worstDyn - 1e-3) return false
        }
        return true
    }

    /** Half-width of [shape] projected onto the lateral axis [rightVec] perpendicular to travel. */
    internal fun transverseHalfWidth(shape: MovementCollisionShape, headingDeg: Double, rightVec: Vec2): Double =
        when (shape) {
            is MovementCollisionShape.Circle -> shape.radius
            is MovementCollisionShape.Rectangle -> {
                val fwd = Vec2.fromHeading(headingDeg, 1.0)
                val lat = Vec2(-fwd.y, fwd.x)
                abs(fwd.dot(rightVec)) * (shape.length * 0.5) + abs(lat.dot(rightVec)) * (shape.width * 0.5)
            }
        }

    internal fun estimateRayClearDistance(
        terrain: PlanningTerrain,
        pos: Position,
        rightVec: Vec2,
        sign: Double,
        maxReachOverride: Double = Double.NaN,
    ): Double {
        val cf = terrain.clearanceField()
        val isSub1 = cf.width == terrain.widthTiles && cf.height == terrain.heightTiles
        val minTile = min(terrain.tileWidth.toDouble(), terrain.tileHeight.toDouble())
        val maxReach = if (maxReachOverride.isFinite()) maxReachOverride else minTile * 5.0
        return estimateRayClearDistanceXY(terrain, cf, isSub1, pos.x, pos.y, rightVec, sign, maxReach)
    }

    private fun estimateRayClearDistanceXY(
        terrain: PlanningTerrain,
        cf: TerrainClearanceField,
        isSub1: Boolean,
        posX: Double,
        posY: Double,
        rightVec: Vec2,
        sign: Double,
        maxReach: Double,
    ): Double {
        val w = terrain.widthTiles
        val h = terrain.heightTiles
        val tw = terrain.tileWidth.toDouble()
        val th = terrain.tileHeight.toDouble()
        val worldW = w * tw
        val worldH = h * th
        val minTile = min(tw, th)
        val pCol = floor(posX / tw).toInt().coerceIn(0, w - 1)
        val pRow = floor(posY / th).toInt().coerceIn(0, h - 1)
        val startRings = if (isSub1) cf.clearanceRingsAt(pRow * w + pCol) else terrain.clearanceRings(pCol, pRow)
        val safeRings = kotlin.math.ceil(maxReach / minTile).toInt() + 2
        if (startRings >= safeRings &&
            posX > maxReach && posX < worldW - maxReach &&
            posY > maxReach && posY < worldH - maxReach
        ) {
            return maxReach
        }
        val step = minTile * 0.5
        val rdx = rightVec.x * sign
        val rdy = rightVec.y * sign
        var d = step
        while (d <= maxReach) {
            val px = posX + rdx * d
            val py = posY + rdy * d
            if (px <= 0.0 || px >= worldW || py <= 0.0 || py >= worldH) return d - step * 0.5
            val col = floor(px / tw).toInt().coerceIn(0, w - 1)
            val row = floor(py / th).toInt().coerceIn(0, h - 1)
            val blocked = if (isSub1) cf.clearanceRingsAt(row * w + col) == 0 else terrain.isBlocked(col, row)
            if (blocked) {
                val loX = col * tw
                val hiX = loX + tw
                val loY = row * th
                val hiY = loY + th
                val cx = posX.coerceIn(loX, hiX)
                val cy = posY.coerceIn(loY, hiY)
                val dx = posX - cx
                val dy = posY - cy
                return sqrt(dx * dx + dy * dy)
            }
            d += step
        }
        return maxReach
    }

    private fun estimateSideClearAtXY(
        terrain: PlanningTerrain,
        cf: TerrainClearanceField,
        isSub1: Boolean,
        posX: Double,
        posY: Double,
        rightVec: Vec2,
        sign: Double,
        maxReach: Double,
    ): Double {
        val w = terrain.widthTiles
        val h = terrain.heightTiles
        val tw = terrain.tileWidth.toDouble()
        val th = terrain.tileHeight.toDouble()
        val worldW = w * tw
        val worldH = h * th
        if (posX <= 0.0 || posX >= worldW || posY <= 0.0 || posY >= worldH) return 0.0
        val col = floor(posX / tw).toInt().coerceIn(0, w - 1)
        val row = floor(posY / th).toInt().coerceIn(0, h - 1)
        val blocked = if (isSub1) cf.clearanceRingsAt(row * w + col) == 0 else terrain.isBlocked(col, row)
        if (blocked) return 0.0
        return max(0.0, estimateRayClearDistanceXY(terrain, cf, isSub1, posX, posY, rightVec, sign, maxReach))
    }

    private fun estimateTransverseClearanceWorldWithSides(
        terrain: PlanningTerrain,
        pos: Position,
        rightVec: Vec2,
        scratch: Scratch,
    ): Double {
        val w = terrain.widthTiles
        val h = terrain.heightTiles
        val tw = terrain.tileWidth.toDouble()
        val th = terrain.tileHeight.toDouble()
        val minTile = min(tw, th)
        val maxReach = minTile * 5.0
        val margin = maxReach + minTile
        val worldW = w * tw
        val worldH = h * th
        val cf = terrain.clearanceField()
        val isSub1 = cf.width == w && cf.height == h
        if (pos.x > margin && pos.x < worldW - margin && pos.y > margin && pos.y < worldH - margin) {
            val pCol = floor(pos.x / tw).toInt().coerceIn(0, w - 1)
            val pRow = floor(pos.y / th).toInt().coerceIn(0, h - 1)
            val rings = if (isSub1) cf.clearanceRingsAt(pRow * w + pCol) else terrain.clearanceRings(pCol, pRow)
            if (rings >= 8) {
                scratch.ownLeftClear = maxReach
                scratch.ownRightClear = maxReach
                return 2.0 * maxReach
            }
        }
        val fwdX = rightVec.y
        val fwdY = -rightVec.x
        val lHere = estimateSideClearAtXY(terrain, cf, isSub1, pos.x, pos.y, rightVec, -1.0, maxReach)
        val rHere = estimateSideClearAtXY(terrain, cf, isSub1, pos.x, pos.y, rightVec, 1.0, maxReach)
        scratch.ownLeftClear = lHere
        scratch.ownRightClear = rHere
        val bx = pos.x - fwdX * minTile
        val by = pos.y - fwdY * minTile
        val ax = pos.x + fwdX * minTile
        val ay = pos.y + fwdY * minTile
        val lBehind = estimateSideClearAtXY(terrain, cf, isSub1, bx, by, rightVec, -1.0, maxReach)
        val lAhead = estimateSideClearAtXY(terrain, cf, isSub1, ax, ay, rightVec, -1.0, maxReach)
        val rBehind = estimateSideClearAtXY(terrain, cf, isSub1, bx, by, rightVec, 1.0, maxReach)
        val rAhead = estimateSideClearAtXY(terrain, cf, isSub1, ax, ay, rightVec, 1.0, maxReach)
        val medLeft = max(min(lBehind, lHere), min(max(lBehind, lHere), lAhead))
        val medRight = max(min(rBehind, rHere), min(max(rBehind, rHere), rAhead))
        return medLeft + medRight
    }

    /**
     * Measures the actual perpendicular passable width across [rightVec] around [pos] on [terrain] in O(1),
     * taking the per-side median across `(pos - fwd*tile, pos, pos + fwd*tile)` so 2-tile/3-tile lanes and
     * 1-tile-thick gates stay wide while single-tile T-junction pockets or staggered chicanes inside a 1-tile
     * canal do not masquerade as multi-lane corridors.
     */
    internal fun estimateTransverseClearanceWorld(terrain: PlanningTerrain, pos: Position, rightVec: Vec2): Double {
        val w = terrain.widthTiles
        val h = terrain.heightTiles
        val tw = terrain.tileWidth.toDouble()
        val th = terrain.tileHeight.toDouble()
        val minTile = min(tw, th)
        val maxReach = minTile * 5.0
        val margin = maxReach + minTile
        val worldW = w * tw
        val worldH = h * th
        val cf = terrain.clearanceField()
        val isSub1 = cf.width == w && cf.height == h
        if (pos.x > margin && pos.x < worldW - margin && pos.y > margin && pos.y < worldH - margin) {
            val pCol = floor(pos.x / tw).toInt().coerceIn(0, w - 1)
            val pRow = floor(pos.y / th).toInt().coerceIn(0, h - 1)
            val rings = if (isSub1) cf.clearanceRingsAt(pRow * w + pCol) else terrain.clearanceRings(pCol, pRow)
            if (rings >= 8) {
                return 2.0 * maxReach
            }
        }
        val fwdX = rightVec.y
        val fwdY = -rightVec.x
        val bx = pos.x - fwdX * minTile
        val by = pos.y - fwdY * minTile
        val ax = pos.x + fwdX * minTile
        val ay = pos.y + fwdY * minTile
        val lHere = estimateSideClearAtXY(terrain, cf, isSub1, pos.x, pos.y, rightVec, -1.0, maxReach)
        val lBehind = estimateSideClearAtXY(terrain, cf, isSub1, bx, by, rightVec, -1.0, maxReach)
        val lAhead = estimateSideClearAtXY(terrain, cf, isSub1, ax, ay, rightVec, -1.0, maxReach)
        val rHere = estimateSideClearAtXY(terrain, cf, isSub1, pos.x, pos.y, rightVec, 1.0, maxReach)
        val rBehind = estimateSideClearAtXY(terrain, cf, isSub1, bx, by, rightVec, 1.0, maxReach)
        val rAhead = estimateSideClearAtXY(terrain, cf, isSub1, ax, ay, rightVec, 1.0, maxReach)
        val medLeft = max(min(lBehind, lHere), min(max(lBehind, lHere), lAhead))
        val medRight = max(min(rBehind, rHere), min(max(rBehind, rHere), rAhead))
        return medLeft + medRight
    }

    internal fun estimateLocalClearanceWorld(terrain: PlanningTerrain, pos: Position): Double {
        val worldW = terrain.widthTiles * terrain.tileWidth.toDouble()
        val worldH = terrain.heightTiles * terrain.tileHeight.toDouble()
        val borderClear = 2.0 * min(min(pos.x, worldW - pos.x), min(pos.y, worldH - pos.y)).coerceAtLeast(0.0)
        val hSpan = estimateTransverseClearanceWorld(terrain, pos, Vec2(1.0, 0.0))
        val vSpan = estimateTransverseClearanceWorld(terrain, pos, Vec2(0.0, 1.0))
        return min(borderClear, min(hSpan, vSpan))
    }

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

    internal fun buildStaticWallHalfPlanes(
        terrain: PlanningTerrain,
        pos: Position,
        hardRadius: Double,
        wallTimeHorizon: Double,
        delta: Double,
        maxSpeed: Double,
    ): List<HalfPlane> {
        val out = ArrayList<HalfPlane>(12)
        val scratch = scratchPool.get()
        buildStaticWallHalfPlanesInto(
            terrain, pos, hardRadius, hardRadius, wallTimeHorizon, delta, maxSpeed, scratch.wallCands, out,
        )
        return out
    }

    private fun buildStaticWallHalfPlanesInto(
        terrain: PlanningTerrain,
        pos: Position,
        wallRadius: Double,
        boundaryRadius: Double,
        wallTimeHorizon: Double,
        delta: Double,
        maxSpeed: Double,
        candsBuf: Array<WallCand>,
        outLines: ArrayList<HalfPlane>,
    ) {
        val tw = terrain.tileWidth.toDouble()
        val th = terrain.tileHeight.toDouble()
        val mapW = terrain.widthTiles * tw
        val mapH = terrain.heightTiles * th
        val invWallTau = 1.0 / max(delta, wallTimeHorizon)
        val borderReach = boundaryRadius + maxSpeed * wallTimeHorizon * 0.85 + 3.0
        val wallReach = wallRadius + maxSpeed * wallTimeHorizon * 0.85 + 3.0

        val leftGap = pos.x - boundaryRadius
        if (leftGap < borderReach) outLines.add(HalfPlane(Vec2(-leftGap * invWallTau, 0.0), Vec2(1.0, 0.0)))
        val rightGap = (mapW - pos.x) - boundaryRadius
        if (rightGap < borderReach) outLines.add(HalfPlane(Vec2(rightGap * invWallTau, 0.0), Vec2(-1.0, 0.0)))
        val topGap = pos.y - boundaryRadius
        if (topGap < borderReach) outLines.add(HalfPlane(Vec2(0.0, -topGap * invWallTau), Vec2(0.0, 1.0)))
        val bottomGap = (mapH - pos.y) - boundaryRadius
        if (bottomGap < borderReach) outLines.add(HalfPlane(Vec2(0.0, bottomGap * invWallTau), Vec2(0.0, -1.0)))

        val centerCol = floor(pos.x / tw).toInt()
        val centerRow = floor(pos.y / th).toInt()
        val scanTiles = max(MAX_WALL_TILES_RADIUS, (wallReach / min(tw, th)).toInt().coerceAtMost(3))
        if (centerCol in 0 until terrain.widthTiles &&
            centerRow in 0 until terrain.heightTiles &&
            terrain.clearanceRings(centerCol, centerRow) > scanTiles
        ) {
            return
        }
        val c0 = max(0, centerCol - scanTiles)
        val c1 = min(terrain.widthTiles - 1, centerCol + scanTiles)
        val r0 = max(0, centerRow - scanTiles)
        val r1 = min(terrain.heightTiles - 1, centerRow + scanTiles)

        var candCount = 0
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
                val dist = sqrt(dx * dx + dy * dy)
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
                val gap = dist - wallRadius
                val minNormalVel = if (gap >= 0.0) -gap * invWallTau
                else (-gap / max(delta, 2.0)).coerceAtMost(maxSpeed * 0.5)
                val plane = HalfPlane(point = normal * minNormalVel, normal = normal)
                if (candCount < candsBuf.size) {
                    // Insertion sort by ascending `dist` (candCount <= 49, typically <= 8 -> 0 allocation!)
                    var ins = candCount++
                    val slot = candsBuf[ins]
                    while (ins > 0 && dist < candsBuf[ins - 1].dist) {
                        candsBuf[ins] = candsBuf[ins - 1]
                        ins--
                    }
                    slot.dist = dist
                    slot.plane = plane
                    candsBuf[ins] = slot
                }
            }
        }

        val maxTileWalls = 8
        var added = 0
        for (i in 0 until candCount) {
            val cand = candsBuf[i].plane
            var redundant = false
            for (j in 0 until outLines.size) {
                val existing = outLines[j]
                if (existing.normal.dot(cand.normal) > 0.985) {
                    if (existing.point.dot(existing.normal) >= cand.point.dot(cand.normal) - 1e-4) {
                        redundant = true
                        break
                    }
                }
            }
            if (!redundant) {
                outLines.add(cand)
                added++
                if (added >= maxTileWalls) break
            }
        }
    }

    internal data class LpResult(val velocity: Vec2, val used3dFallback: Boolean)

    internal fun solveLinearProgram(
        lines: List<HalfPlane>,
        numFixed: Int,
        maxSpeed: Double,
        prefVelocity: Vec2,
    ): LpResult {
        val scratch = scratchPool.get()
        return solveLinearProgramScratch(lines, numFixed, maxSpeed, prefVelocity, scratch.projLines, scratch.lpOut)
    }

    private fun solveLinearProgramScratch(
        lines: List<HalfPlane>,
        numFixed: Int,
        maxSpeed: Double,
        prefVelocity: Vec2,
        projLinesBuf: ArrayList<HalfPlane>,
        out: Array<Vec2>,
    ): LpResult {
        val n = lines.size
        val scratch = scratchPool.get()
        if (n <= scratch.lpPx.size) {
            val px = scratch.lpPx
            val py = scratch.lpPy
            val nx = scratch.lpNx
            val ny = scratch.lpNy
            val dx = scratch.lpDx
            val dy = scratch.lpDy
            for (i in 0 until n) {
                val hp = lines[i]
                px[i] = hp.point.x
                py[i] = hp.point.y
                nx[i] = hp.normal.x
                ny[i] = hp.normal.y
                dx[i] = hp.direction.x
                dy[i] = hp.direction.y
            }
            val res = scratch.lpOutXY
            val lineFail = linearProgram2Prim(
                px, py, nx, ny, dx, dy, n, maxSpeed,
                prefVelocity.x, prefVelocity.y, directionOpt = false,
                tieX = prefVelocity.x, tieY = prefVelocity.y, outXY = res,
            )
            if (lineFail < n) {
                linearProgram3Prim(
                    px, py, nx, ny, dx, dy, n, numFixed, lineFail, maxSpeed,
                    prefVelocity.x, prefVelocity.y,
                    scratch.projPx, scratch.projPy, scratch.projNx, scratch.projNy, scratch.projDx, scratch.projDy,
                    outXY = res,
                )
                return LpResult(Vec2(res[0], res[1]), used3dFallback = true)
            }
            return LpResult(Vec2(res[0], res[1]), used3dFallback = false)
        }
        val lineFail = linearProgram2(lines, maxSpeed, prefVelocity, directionOpt = false, tieBreakPref = prefVelocity, result = out)
        if (lineFail < lines.size) {
            linearProgram3(lines, numFixed, lineFail, maxSpeed, prefVelocity, projLinesBuf, out)
            return LpResult(out[0], used3dFallback = true)
        }
        return LpResult(out[0], used3dFallback = false)
    }

    private fun linearProgram1Prim(
        px: DoubleArray, py: DoubleArray, dx: DoubleArray, dy: DoubleArray,
        lineNo: Int, radius: Double,
        optX: Double, optY: Double, directionOpt: Boolean,
        tieX: Double, tieY: Double, outXY: DoubleArray,
    ): Boolean {
        val lpx = px[lineNo]; val lpy = py[lineNo]
        val ldx = dx[lineNo]; val ldy = dy[lineNo]
        val dotProduct = lpx * ldx + lpy * ldy
        val discriminant = dotProduct * dotProduct + radius * radius - (lpx * lpx + lpy * lpy)
        if (discriminant < 0.0) return false

        val sqrtDisc = sqrt(discriminant)
        var tLeft = -dotProduct - sqrtDisc
        var tRight = -dotProduct + sqrtDisc

        for (i in 0 until lineNo) {
            val odx = dx[i]; val ody = dy[i]
            val denominator = ldx * ody - ldy * odx
            val diffX = lpx - px[i]; val diffY = lpy - py[i]
            val numerator = odx * diffY - ody * diffX
            if (abs(denominator) <= EPS) {
                if (numerator < 0.0) return false
                continue
            }
            val t = numerator / denominator
            if (denominator >= 0.0) {
                if (t < tRight) tRight = t
            } else {
                if (t > tLeft) tLeft = t
            }
            if (tLeft > tRight) return false
        }

        val tChosen = if (directionOpt) {
            val dirProj = optX * ldx + optY * ldy
            when {
                dirProj > EPS -> tRight
                dirProj < -EPS -> tLeft
                else -> clamp(ldx * (tieX - lpx) + ldy * (tieY - lpy), tLeft, tRight)
            }
        } else {
            val t = ldx * (optX - lpx) + ldy * (optY - lpy)
            when {
                t < tLeft -> tLeft
                t > tRight -> tRight
                else -> t
            }
        }
        outXY[0] = lpx + ldx * tChosen
        outXY[1] = lpy + ldy * tChosen
        return true
    }

    private fun linearProgram2Prim(
        px: DoubleArray, py: DoubleArray, nx: DoubleArray, ny: DoubleArray, dx: DoubleArray, dy: DoubleArray,
        count: Int, radius: Double,
        optX: Double, optY: Double, directionOpt: Boolean,
        tieX: Double, tieY: Double, outXY: DoubleArray,
    ): Int {
        if (directionOpt) {
            outXY[0] = optX * radius
            outXY[1] = optY * radius
        } else {
            val optSq = optX * optX + optY * optY
            if (optSq > radius * radius) {
                val len = sqrt(optSq)
                val s = if (len > EPS) radius / len else 0.0
                outXY[0] = optX * s
                outXY[1] = optY * s
            } else {
                outXY[0] = optX
                outXY[1] = optY
            }
        }
        for (i in 0 until count) {
            val margin = (outXY[0] - px[i]) * nx[i] + (outXY[1] - py[i]) * ny[i]
            if (margin < -EPS) {
                val saveX = outXY[0]
                val saveY = outXY[1]
                if (!linearProgram1Prim(px, py, dx, dy, i, radius, optX, optY, directionOpt, tieX, tieY, outXY)) {
                    outXY[0] = saveX
                    outXY[1] = saveY
                    return i
                }
            }
        }
        return count
    }

    private fun linearProgram3Prim(
        px: DoubleArray, py: DoubleArray, nx: DoubleArray, ny: DoubleArray, dx: DoubleArray, dy: DoubleArray,
        count: Int, numFixed: Int, beginLine: Int, radius: Double,
        prefX: Double, prefY: Double,
        projPx: DoubleArray, projPy: DoubleArray, projNx: DoubleArray, projNy: DoubleArray, projDx: DoubleArray, projDy: DoubleArray,
        outXY: DoubleArray,
    ) {
        var distance = 0.0
        val start = max(numFixed, beginLine)
        for (i in start until count) {
            val marginI = (outXY[0] - px[i]) * nx[i] + (outXY[1] - py[i]) * ny[i]
            if (marginI < -distance) {
                var pCount = 0
                for (k in 0 until numFixed) {
                    projPx[pCount] = px[k]; projPy[pCount] = py[k]
                    projNx[pCount] = nx[k]; projNy[pCount] = ny[k]
                    projDx[pCount] = dx[k]; projDy[pCount] = dy[k]
                    pCount++
                }
                val idxI = dx[i]; val idyI = dy[i]
                val ipxI = px[i]; val ipyI = py[i]
                for (j in numFixed until i) {
                    val jdx = dx[j]; val jdy = dy[j]
                    val determinant = idxI * jdy - idyI * jdx
                    val ptX: Double
                    val ptY: Double
                    if (abs(determinant) <= EPS) {
                        if (idxI * jdx + idyI * jdy > 0.0) {
                            continue
                        } else {
                            ptX = (ipxI + px[j]) * 0.5
                            ptY = (ipyI + py[j]) * 0.5
                        }
                    } else {
                        val factor = (jdx * (ipyI - py[j]) - jdy * (ipxI - px[j])) / determinant
                        ptX = ipxI + idxI * factor
                        ptY = ipyI + idyI * factor
                    }
                    val rx = jdx - idxI
                    val ry = jdy - idyI
                    val rLen = sqrt(rx * rx + ry * ry)
                    if (rLen > EPS && pCount < projPx.size) {
                        val ndx = rx / rLen
                        val ndy = ry / rLen
                        projPx[pCount] = ptX; projPy[pCount] = ptY
                        projDx[pCount] = ndx; projDy[pCount] = ndy
                        projNx[pCount] = -ndy; projNy[pCount] = ndx
                        pCount++
                    }
                }
                val saveX = outXY[0]
                val saveY = outXY[1]
                if (linearProgram2Prim(
                        projPx, projPy, projNx, projNy, projDx, projDy, pCount, radius,
                        nx[i], ny[i], directionOpt = true, tieX = prefX, tieY = prefY, outXY = outXY,
                    ) < pCount
                ) {
                    outXY[0] = saveX
                    outXY[1] = saveY
                }
                distance = -((outXY[0] - ipxI) * nx[i] + (outXY[1] - ipyI) * ny[i])
            }
        }
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
        projLines: ArrayList<HalfPlane>,
        result: Array<Vec2>,
    ) {
        var distance = 0.0
        val start = max(numFixed, beginLine)
        for (i in start until lines.size) {
            if (lines[i].signedMargin(result[0]) < -distance) {
                projLines.clear()
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

    private fun canPushMassFrozen(selfMass: Double?, peerMass: Double?): Boolean {
        if (selfMass == null || !selfMass.isFinite() || selfMass <= 0.0) return false
        if (peerMass == null || !peerMass.isFinite() || peerMass <= 0.0) return false
        return peerMass / selfMass <= 8.0
    }

    private fun isProbeClearOfAnchoredPeers(
        probe: Position,
        currentPeerId: String,
        selfHalfWidth: Double,
        neighborsBuf: Array<FrozenSpatialUnit?>,
        neighborCount: Int,
        ignoredContactUnitId: String?,
    ): Boolean {
        for (i in 0 until neighborCount) {
            val other = neighborsBuf[i] ?: continue
            if (other.unitId == currentPeerId) continue
            if (ignoredContactUnitId != null && other.unitId == ignoredContactUnitId) continue
            if (!other.anchored) continue
            val dx = probe.x - other.position.x
            val dy = probe.y - other.position.y
            val minSep = selfHalfWidth + other.hardRadius * 0.95
            if (dx * dx + dy * dy < minSep * minSep) return false
        }
        return true
    }

    internal fun computeResponsibility(
        self: MutableUnit,
        selfRadius: Double,
        peer: FrozenSpatialUnit,
        peerUnit: MutableUnit?,
        peerAnchored: Boolean,
    ): Double = computeResponsibility(
        selfMassOpt = self.definition.mass,
        selfRadius = selfRadius,
        selfMove = UnitLocomotion.movement(self),
        peer = peer,
        peerAnchored = peerAnchored,
    )

    internal fun computeResponsibility(
        selfMassOpt: Double?,
        selfRadius: Double,
        selfMove: MovementProfile,
        peer: FrozenSpatialUnit,
        peerAnchored: Boolean,
    ): Double {
        if (peerAnchored) return 1.0
        val selfMass = selfMassOpt ?: (selfRadius * selfRadius * 50.0)
        val peerMass = peer.mass ?: (peer.hardRadius * peer.hardRadius * 50.0)
        val selfArea = max(1.0, selfRadius * selfRadius)
        val peerArea = max(1.0, peer.hardRadius * peer.hardRadius)
        val selfAgility = max(0.2, selfMove.moveSpeed * max(0.5, selfMove.maxTurnSpeed))
        val peerAgility = max(0.2, peer.moveSpeed * max(0.5, peer.maxTurnSpeed))
        val selfWeight = sqrt(max(1.0, selfMass) * selfArea) / sqrt(selfAgility)
        val peerWeight = sqrt(max(1.0, peerMass) * peerArea) / sqrt(peerAgility)
        if (selfWeight + peerWeight <= EPS) return 0.5
        val ratio = peerWeight / (selfWeight + peerWeight)
        return clamp(0.10 + 0.80 * ratio, 0.10, 0.90)
    }

    private fun frozenVelocity(snap: FrozenSpatialUnit): Vec2 {
        return if (snap.slidingMode) Vec2(snap.flightVelocity.x, snap.flightVelocity.y)
        else Vec2.fromHeading(snap.heading, snap.speed)
    }

    private fun frozenVelocity(snap: FrozenSpatialUnit?, unit: MutableUnit?, movement: MovementProfile? = null): Vec2 {
        if (snap != null) return frozenVelocity(snap)
        val useSliding = movement?.slidingMode == true ||
            (unit != null && UnitLocomotion.movement(unit).slidingMode)
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
    ): SolveResult {
        val desiredHeading = ScreenPlane.desired(unit.position, desiredTarget)
        val err = ScreenPlane.difference(desiredHeading, unit.heading)
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
     * denied entry by opposing traffic (`PassageAdmission`).
     */
    internal fun computeNarrowMouthStagingAim(
        host: UnitMovementHost,
        unit: MutableUnit,
        terrain: PlanningTerrain,
    ): Position? {
        val entry = unit.passageEntry ?: return null
        if (entry.admitted || entry.entered) return null
        if (unit.definition.movementCollision is MovementCollisionShape.Rectangle) return null
        if (unit.followsManeuverRoute) return null
        val movement = UnitLocomotion.movement(unit)
        if (movement.type == MovementType.WATER && movement.maxTurnSpeed < 2.5) return null
        val mouth = entry.passage.mouths[entry.side]
        val radius = max(1.0, unit.definition.movementCollision.boundingRadius)
        val terrainR = max(0.0, UnitLocomotion.terrainPassabilityRadius(unit, terrain.tileWidth, terrain.tileHeight))
        val tile = max(terrain.tileWidth, terrain.tileHeight).toDouble()
        val depthOutside = mouth.depth(unit.position)
        val across = if (mouth.horizontal) unit.position.y else unit.position.x
        val mouthClearBand = max(radius * 1.85, tile * 0.8)
        val outletReach = max(entry.passage.width * 2.5, radius * 4.5)
        if (depthOutside < radius * 0.25 || depthOutside > outletReach) return null
        val obstructingOutlet = across >= mouth.low - mouthClearBand && across <= mouth.high + mouthClearBand
        if (!obstructingOutlet) return null

        val passageTerrain = entry.terrain
        if (passageTerrain !== terrain) return null

        val mouthCenterAcross = (mouth.low + mouth.high) * 0.5
        val mouthOutsideAnchor = mouth.plane + mouth.outward * max(radius * 1.1, 6.0)
        val mouthApproachTarget = if (mouth.horizontal) Position(mouthOutsideAnchor, mouthCenterAcross)
        else Position(mouthCenterAcross, mouthOutsideAnchor)

        val gateCap = max(depthOutside, tile + radius * 0.85)
        val stageDepth = min(max(depthOutside, radius * 1.9 + 3.0), gateCap)
        val desiredPlane = mouth.plane + mouth.outward * stageDepth
        val margin = max(radius * 2.2 + 6.0, tile * 0.95 + radius)
        val preferHigh = (mouth.horizontal && mouth.outward < 0) || (!mouth.horizontal && mouth.outward > 0)
        val firstAcross = if (preferHigh) mouth.high + margin else mouth.low - margin
        val secondAcross = if (preferHigh) mouth.low - margin else mouth.high + margin

        fun makePos(planeCoord: Double, acrossCoord: Double): Position =
            if (mouth.horizontal) Position(planeCoord, acrossCoord) else Position(acrossCoord, planeCoord)

        val routeR = host.routeRadius(unit)
        val checkR = if (terrainR > 0.0) terrainR * 0.98 else radius * 0.95
        for (cIdx in 0 until 4) {
            val cand = when (cIdx) {
                0 -> makePos(desiredPlane, firstAcross)
                1 -> makePos(desiredPlane, secondAcross)
                2 -> makePos(mouth.plane + mouth.outward * min(stageDepth + radius * 1.25, gateCap), firstAcross)
                else -> makePos(mouth.plane + mouth.outward * min(stageDepth + radius * 1.25, gateCap), secondAcross)
            }
            if (mouth.depth(cand) < radius * 0.9) continue
            if (isPointClearOnTerrain(terrain, cand, checkR) &&
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
     * Re-aligns an admitted circular unit onto the centerline of a contended single-lane mouth
     * before entry (whether it previously side-stepped while waiting or was nudged off-center
     * by exiting opposing traffic). Unopposed / same-direction traffic has `passageEntry == null`
     * and never receives any mouth-pulling override.
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
        if (mouth.depth(plannedAim) >= depth) return plannedAim
        val mid = (mouth.low + mouth.high) * 0.5
        val across = if (mouth.horizontal) unit.position.y else unit.position.x
        val halfSpan = (mouth.high - mouth.low) * 0.5
        val baseClearance = max(1.0, halfSpan - shape.radius * 0.95)
        val turnLead = abs(unit.speed) * 1.65
        val clearance = min(halfSpan * 0.45, baseClearance + turnLead)
        if (abs(across - mid) <= clearance) return plannedAim
        val anchorDepth = max(shape.radius * 1.15, min(depth, shape.radius * 1.8 + 4.0))
        val anchorPlane = mouth.plane + mouth.outward * anchorDepth
        return if (mouth.horizontal) Position(anchorPlane, mid) else Position(mid, anchorPlane)
    }

    /** Independent AIR-layer lightweight 2D Boids soft separation (allocation-free via scratch buffer). */
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
        if (desiredTarget == null && unit.moveArrival == null && unit.originMoveId == null) return null
        if (flight != null && unit.moveTarget == null && (flight.landWhenStationary || unit.definition.attackMovement == AttackMovementProfile.BOMBER)) {
            if (unit.flightIdleTime > 2.5f || unit.height < flight.targetHeight * 0.6) return null
        }
        if (unit.definition.attackMovement == AttackMovementProfile.BOMBER && unit.bomberApproach != null) {
            return null
        }

        val hardR = max(6.0, max(unit.definition.radius, unit.definition.movementCollision.boundingRadius))
        val sepRadius = max(hardR * 4.0, 56.0)
        val scratch = scratchPool.get()
        val airBuf = scratch.airPeers
        val neighborCount = host.spatialIndex.queryFrozenTopKInto(
            observer = unit,
            layer = SpatialLayer.AIR,
            radius = sepRadius,
            limit = 16,
            includeBufferPadding = false,
            outUnits = airBuf,
            outDistSq = scratch.airDistSq,
        ) { candidate ->
            candidate.ownerPlayerId == unit.ownerPlayerId
        }
        if (neighborCount == 0) return null

        val ownSnap = host.spatialIndex.frozenUnit(unit.unitId)
        val ownPos = ownSnap?.position ?: unit.position
        var pushX = 0.0
        var pushY = 0.0
        var maxOverlapRatio = 0.0
        var count = 0

        for (i in 0 until neighborCount) {
            val peer = checkNotNull(airBuf[i])
            airBuf[i] = null
            if (peer.flightTargetHeight > 0.0 && peer.height < peer.flightTargetHeight * 0.5) continue
            val peerR = max(6.0, max(peer.definitionRadius, peer.hardRadius))
            val dx = ownPos.x - peer.position.x
            val dy = ownPos.y - peer.position.y
            val dist = hypot(dx, dy)
            val combinedHard = hardR + peerR
            val comfort = max(combinedHard + 8.0, combinedHard * 1.25)
            if (dist >= comfort) continue
            val closeness = clamp((comfort - dist) / max(1.0, comfort), 0.0, 1.0)
            val overlapRatio = if (dist < combinedHard + 4.0) clamp((combinedHard + 4.0 - dist) / max(1.0, combinedHard), 0.0, 1.0) else 0.0
            if (overlapRatio > maxOverlapRatio) maxOverlapRatio = overlapRatio
            val dir = if (dist > 1e-4) {
                Vec2(dx / dist, dy / dist)
            } else {
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

        val maxSepSpeed = if (desiredTarget == null) {
            maximumSpeed * (0.65 + 0.55 * maxOverlapRatio)
        } else {
            maximumSpeed * (1.05 + 0.45 * maxOverlapRatio)
        }
        val gain = if (desiredTarget == null) 1.15 else 1.55
        val scaleMag = min(maxSepSpeed, pushLen * maximumSpeed * gain)
        return Vec2(pushX / pushLen * scaleMag, pushY / pushLen * scaleMag)
    }

    internal fun isAirRallyRingArrived(
        host: UnitMovementHost,
        unit: MutableUnit,
        target: Position,
    ): Boolean {
        val hardR = max(6.0, max(unit.definition.radius, unit.definition.movementCollision.boundingRadius))
        val distToTarget = hypot(unit.position.x - target.x, unit.position.y - target.y)
        val outerRing = max(hardR * 12.0, 140.0)
        if (distToTarget > outerRing) return false
        val scratch = scratchPool.get()
        val airBuf = scratch.airPeers
        val peerCount = host.spatialIndex.queryFrozenTopKInto(
            observer = unit,
            layer = SpatialLayer.AIR,
            radius = outerRing + hardR * 3.0,
            limit = 24,
            includeBufferPadding = false,
            outUnits = airBuf,
            outDistSq = scratch.airDistSq,
        ) { candidate ->
            candidate.ownerPlayerId == unit.ownerPlayerId
        }
        if (peerCount == 0) return false
        var innerCount = 0
        var closestPeerDistToTarget = Double.POSITIVE_INFINITY
        var nearestPeerClearRatio = Double.POSITIVE_INFINITY
        var blockingPeerAhead = false
        val ownSnap = host.spatialIndex.frozenUnit(unit.unitId)
        val ownPos = ownSnap?.position ?: unit.position
        val toTargetX = target.x - ownPos.x
        val toTargetY = target.y - ownPos.y
        val toTargetLen = max(1e-4, hypot(toTargetX, toTargetY))
        val maxSpeed = max(0.5, UnitLocomotion.effectiveMaximumSpeed(unit, UnitLocomotion.movement(unit)))
        val ownAirVel = frozenVelocity(ownSnap, unit)
        val closingSpeed = (ownAirVel.x * toTargetX + ownAirVel.y * toTargetY) / toTargetLen
        for (i in 0 until peerCount) {
            val peer = checkNotNull(airBuf[i])
            airBuf[i] = null
            if (peer.flightTargetHeight > 0.0 && peer.height < peer.flightTargetHeight * 0.5) continue
            val peerR = max(6.0, max(peer.definitionRadius, peer.hardRadius))
            val peerDistToTarget = hypot(peer.position.x - target.x, peer.position.y - target.y)
            if (peerDistToTarget < closestPeerDistToTarget) closestPeerDistToTarget = peerDistToTarget
            val peerArrived = !peer.hasMoveOrder
            if (peerDistToTarget < distToTarget - hardR * 0.15) {
                innerCount++
            }
            val pairDist = hypot(peer.position.x - ownPos.x, peer.position.y - ownPos.y)
            val combinedHard = hardR + peerR
            val ratio = pairDist / max(1.0, combinedHard)
            if (ratio < nearestPeerClearRatio) nearestPeerClearRatio = ratio
            val comfort = max(combinedHard + 14.0, combinedHard * 1.50)
            if (pairDist < comfort) {
                val fwd = ((peer.position.x - ownPos.x) * toTargetX + (peer.position.y - ownPos.y) * toTargetY) / toTargetLen
                val peerSpd = hypot(peer.flightVelocity.x, peer.flightVelocity.y)
                if (fwd > 0.0 && (peerArrived || peerSpd < 0.35 * maxSpeed)) {
                    blockingPeerAhead = true
                }
            }
        }
        val centerClaimed = closestPeerDistToTarget <= max(hardR * 1.8, 22.0)
        if (!centerClaimed) return false
        val ringCap = when {
            innerCount >= 12 -> max(hardR * 8.5, 128.0)
            innerCount >= 6 -> max(hardR * 6.5, 98.0)
            innerCount >= 2 -> max(hardR * 4.6, 70.0)
            innerCount >= 1 -> max(hardR * 3.2, 48.0)
            else -> return false
        }
        return distToTarget <= ringCap &&
            nearestPeerClearRatio >= 0.90 &&
            (blockingPeerAhead || closingSpeed < 0.30 * maxSpeed)
    }

    private fun isPointClearOnTerrain(terrain: PlanningTerrain, p: Position, radius: Double): Boolean =
        isPointClearOnTerrain(terrain, p.x, p.y, radius)

    private fun isPointClearOnTerrain(terrain: PlanningTerrain, px: Double, py: Double, radius: Double): Boolean {
        val tw = terrain.tileWidth.toDouble()
        val th = terrain.tileHeight.toDouble()
        val mapW = terrain.widthTiles * tw
        val mapH = terrain.heightTiles * th
        if (px < radius || px > mapW - radius || py < radius || py > mapH - radius) return false
        val c0 = floor((px - radius) / tw).toInt().coerceIn(0, terrain.widthTiles - 1)
        val c1 = floor((px + radius) / tw).toInt().coerceIn(0, terrain.widthTiles - 1)
        val r0 = floor((py - radius) / th).toInt().coerceIn(0, terrain.heightTiles - 1)
        val r1 = floor((py + radius) / th).toInt().coerceIn(0, terrain.heightTiles - 1)
        for (r in r0..r1) {
            val minY = r * th
            val maxY = minY + th
            for (c in c0..c1) {
                if (!terrain.isBlocked(c, r)) continue
                val minX = c * tw
                val maxX = minX + tw
                val cx = px.coerceIn(minX, maxX)
                val cy = py.coerceIn(minY, maxY)
                val dx = px - cx
                val dy = py - cy
                if (dx * dx + dy * dy < radius * radius) return false
            }
        }
        return true
    }

    private fun stableTieHash(key: String): Long {
        var h = 1125899906842597L
        for (i in 0 until key.length) {
            h = 31L * h + key[i].code.toLong()
        }
        return h xor (h ushr 32)
    }

    private fun clamp(v: Double, lo: Double, hi: Double): Double = when {
        v < lo -> lo
        v > hi -> hi
        else -> v
    }
}
