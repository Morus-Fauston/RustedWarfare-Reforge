package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.AttackMovementProfile
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition

import com.rustedwarfare.reforge.core.path.PlanningSegmentClearance
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Narrow-seam host contract consumed by [UnitMovementOrchestrator].
 *
 * Isolates per-unit locomotion orchestration from [SimulationContext]'s combat, production,
 * construction, and knowledge-publication machinery while keeping deterministic step-state
 * ownership inside [SimulationContext].
 */
internal interface UnitMovementHost {
    val activeWorld: WorldHandle
    val simulationTime: Double
    val movementContract: MovementContract
    val policies: ExperimentalPolicies
    val trafficSeparationEnabled: Boolean
    val units: Map<String, MutableUnit>
    val spatialIndex: WorldSpatialIndex
    val buildingTerrain: BuildingTerrain?
    val pendingEvents: MutableList<Event>

    fun unitActionHead(unit: MutableUnit): PendingUnitAction?
    fun logicalRouteTarget(unit: MutableUnit, fallback: Position): Position
    fun planningTerrainFor(unit: MutableUnit, map: MapDefinition): PlanningTerrain
    fun routeRadius(unit: MutableUnit): Double
    fun routeOccupancy(unit: MutableUnit, includeMoving: Boolean = false): RouteOccupancy?
    fun continuationFits(
        terrain: PlanningTerrain,
        start: Position,
        target: Position,
        heading: Double,
        hull: HullRoute?,
        radius: Double,
        occupancy: RouteOccupancy? = null,
    ): Boolean
    fun scheduleManeuverRepair(unit: MutableUnit, target: Position, reason: ManeuverRepair)
    fun groupStopClearsSlots(unit: MutableUnit): Boolean
    fun preparesRouteSuccessor(unit: MutableUnit): Boolean
    fun requestRouteContinuation(
        unit: MutableUnit,
        step: Long,
        estimate: Double,
        samples: MutableList<RouteContinuationSample>?,
    ): RouteContinuation
    fun recordRouteContinuation(
        samples: MutableList<RouteContinuationSample>?,
        step: Long,
        unit: MutableUnit,
        continuation: RouteContinuation,
        remaining: Double,
        stage: RouteContinuationStage,
    )
    fun knowsRouteOccupant(unit: MutableUnit, other: MutableUnit): Boolean
    fun canDisplaceInContact(unit: MutableUnit): Boolean
    fun isVisibleTo(playerId: String, storedPosition: Position, nativeTransportPlacement: Boolean = false): Boolean
    fun isParkedPeer(mover: MutableUnit, other: MutableUnit): Boolean
    fun blockAtMapBoundary(unit: MutableUnit)
    fun approachSpeed(current: Double, target: Double, maxDelta: Double): Double
}

/**
 * Per-unit movement orchestrator extracted from [SimulationContext.updateUnit] (ENG-P016-003).
 *
 * Executes the four deterministic movement phases over [UnitNavigationState]:
 * 1. Admission/lock gating (construction readiness, blink/action lock, bay/publication/passage wait)
 * 2. Route follow, waypoint handoff, and atomic arrival transition
 * 3. Heading/steering integration, hull turn terrain/contact clamping, and velocity integration
 * 4. Terrain sweep, unit-contact resolution, spatial-index sync, and motion-sample recording
 */
internal object UnitMovementOrchestrator {
    private data class SteeringIntent(
        val target: Position?,
        val speedTarget: Double,
        val requestedFraction: Double,
        val maneuverLeg: ManeuverLeg?,
        val aimForSample: Position?,
        val finalForSample: Boolean,
        val differenceForSample: Double,
        val normalCompleted: Boolean,
        val abortedForContinuation: Boolean = false,
        val avoidanceApplied: Boolean = false,
    )

    private data class HeadingResolution(
        val requestedHeading: Double,
        val terrainHeading: Double,
        val boundedHeading: Double,
        val legalHeading: Double,
        val speedTarget: Double,
        val requestedFraction: Double,
        val nearShoreLeg: Boolean,
        val forwardTurnEscape: Boolean,
        val sailClearOfBank: Boolean,
        val shape: MovementCollisionShape?,
        val hull: MovementCollisionShape.Rectangle?,
        val circle: CircularTerrain?,
        val collisionMap: MapDefinition,
        val hullTerrain: HullTerrainGrid?,
    )

    fun advanceUnit(
        host: UnitMovementHost,
        unit: MutableUnit,
        map: MapDefinition,
        delta: Double,
        executionStep: Long,
        step: CandidateStep,
        passageWaiting: Boolean,
        departureWaiting: Boolean,
    ) {
        if (!BuildingWork.isReady(unit)) return
        val movement = UnitLocomotion.movement(unit)
        if (gateMovementLockOrWait(host, unit, movement, delta, executionStep, step, passageWaiting, departureWaiting)) {
            return
        }

        val maximumSpeed = step.translations.maximumSpeed(unit, movement)
        val air = movement.type == MovementType.AIR
        val sliding = movement.slidingMode
        val positionBefore = unit.position
        val headingBefore = unit.heading
        val speedBefore = unit.speed
        val angularVelocityBefore = unit.angularVelocity
        val commandBefore = unit.moveTarget
        val originBefore = unit.originMoveId
        val progressBefore = unit.localProgressTime
        val correctionBefore = unit.terrainCorrectionTime

        val steering = computeSteeringAndArrival(
            host, unit, map, movement, maximumSpeed, air, sliding, delta, executionStep, step,
        )
        if (steering.abortedForContinuation) return

        val heading = resolveHeadingAndTurn(
            host, unit, map, movement, maximumSpeed, delta, headingBefore, steering,
        )

        integrateAndResolveContact(
            host, unit, map, movement, maximumSpeed, air, sliding, delta, executionStep, step,
            positionBefore, headingBefore, speedBefore, angularVelocityBefore, commandBefore,
            originBefore, progressBefore, correctionBefore, steering, heading,
        )
    }

    /** Phase 1: Returns true if the unit is locked or waiting on bay/publication/passage admission. */
    private fun gateMovementLockOrWait(
        host: UnitMovementHost,
        unit: MutableUnit,
        movement: com.rustedwarfare.reforge.core.content.MovementProfile,
        delta: Double,
        executionStep: Long,
        step: CandidateStep,
        passageWaiting: Boolean,
        departureWaiting: Boolean,
    ): Boolean {
        if (unit.spiderBlink != null || CustomUnitActions.movementLocked(unit, host.unitActionHead(unit)) ||
            movement.type == MovementType.NONE
        ) {
            unit.speed = 0.0
            unit.flightVelocity = Position(0.0, 0.0)
            unit.movementState = MovementState.IDLE
            return true
        }
        val nativeDeparture = FactoryExit.isNativeSeaDeparture(unit)
        val passageTarget = unit.moveTarget
        unit.landingMember?.let { member ->
            member.waitingForBay?.let { id ->
                val front = host.units[id]
                if (front != null && front.alive && front.landingMember?.group === member.group && front.moveTarget != null) {
                    unit.speed = 0.0
                    unit.angularVelocity = 0.0
                    unit.flightVelocity = Position(0.0, 0.0)
                    step.translations.recordPlanningWait(unit)
                    return true
                }
                unit.landingMember = member.copy(waitingForBay = null)
            }
        }
        val publicationWaiting = step.state.movePlanning.preparingPublication(unit.unitId) ||
            step.state.movePlanning.replacingDestination(unit.unitId)
        if (passageTarget != null && (publicationWaiting || unit.followsManeuverRoute && step.state.movePlanning.holdsManeuverRoute(unit.unitId))) {
            if (publicationWaiting) step.translations.recordPlanningWait(unit)
            step.motionSamples?.add(
                MotionStepSample(
                    host.activeWorld.id, unit.unitId, unit.ownerPlayerId, unit.typeId,
                    executionStep, host.simulationTime, delta, unit.originMoveId, unit.position, unit.heading, unit.speed, unit.angularVelocity,
                    passageTarget, false, false, null, null, null, 0.0, unit.heading, 0.0, unit.position, unit.position,
                    MotionTerrainDisposition.DIRECT, unit.position, unit.heading, 0.0, 0.0,
                    unit.localProgressTime, unit.localProgressTime, unit.terrainCorrectionTime, unit.terrainCorrectionTime, false,
                )
            )
            unit.requestMovement(host.logicalRouteTarget(unit, passageTarget))
            unit.speed = 0.0
            unit.angularVelocity = 0.0
            unit.flightVelocity = Position(0.0, 0.0)
            return true
        }
        if (passageTarget != null && (departureWaiting && nativeDeparture ||
                passageWaiting && unit.passageEntry?.commandId == unit.originMoveId)
        ) {
            unit.requestMovement(host.logicalRouteTarget(unit, passageTarget))
            unit.speed = 0.0
            unit.angularVelocity = 0.0
            unit.flightVelocity = Position(0.0, 0.0)
            unit.nav = unit.nav.copy(stalledRouteTicks = 0L, nearTargetTime = 0.0)
            unit.movementState = MovementState.MOVING
            return true
        }
        return false
    }

    /** Phase 2: Waypoint maintenance, arrival capture, and steering/propulsion target computation. */
    private fun computeSteeringAndArrival(
        host: UnitMovementHost,
        unit: MutableUnit,
        map: MapDefinition,
        movement: com.rustedwarfare.reforge.core.content.MovementProfile,
        maximumSpeed: Double,
        air: Boolean,
        sliding: Boolean,
        delta: Double,
        executionStep: Long,
        step: CandidateStep,
    ): SteeringIntent {
        val contract = host.movementContract
        val policies = host.policies
        var normalCompleted = false
        var aimForSample: Position? = null
        var finalForSample = false
        var differenceForSample = 0.0
        var requestedFraction = 0.0
        val settlingFromPush = unit.landingMember?.restingSite != null
        if (settlingFromPush && unit.speed == 0.0) unit.angularVelocity = 0.0
        var target = unit.moveTarget.takeUnless { settlingFromPush }
        var speedTarget = 0.0
        // Optimistic same-step turn while initial P0 planning runs (ENG-P016-005):
        // preserves speed and starts rotating toward the fresh right-click immediately on the command step.
        val optimisticTarget = if (target == null && !settlingFromPush && step.state.movePlanning.pending(unit.unitId)) {
            unit.optimisticSteerTarget
        } else null
        var maneuverLeg: ManeuverLeg? = null
        var avoidanceApplied = false

        val attackTarget = unit.attackCommand?.targetUnitId?.let { host.units[it] }
        val approachingBuilding = attackTarget?.alive == true && attackTarget.definition.building != null &&
            UnitCombat.profile(unit)?.let { weapon ->
                policies.coordinates.distanceSquared(unit.position, attackTarget.position) >= weapon.attackRange * weapon.attackRange
            } == true
        val workTask = unit.tasks.firstOrNull()?.takeIf {
            it.kind == UnitTaskKind.BUILD || it.kind == UnitTaskKind.CONSTRUCT || it.kind == UnitTaskKind.RECLAIM
        }
        val workTarget = if (workTask?.kind == UnitTaskKind.BUILD) workTask.target
        else workTask?.targetUnitId?.let { host.units[it]?.takeIf { targetUnit -> targetUnit.alive }?.position }
        val approachingWork = workTarget != null && unit.definition.builder?.let { builder ->
            policies.coordinates.distanceSquared(unit.position, workTarget) > builder.range.toDouble() * builder.range
        } == true
        val taskKind = unit.tasks.firstOrNull()?.kind
        val reportsStop = unit.nearestMoveTarget != null || unit.groupMoveReservation != null || unit.landingMember?.claimed != null
        val followsManeuver = (unit.followsManeuverRoute ||
            unit.groupMoveReservation != null && unit.pathPoints.size == 1) &&
            taskKind == UnitTaskKind.MOVE && !air && !sliding

        if (taskKind == UnitTaskKind.MOVE && !followsManeuver && movement.type == MovementType.LAND &&
            unit.definition.movementCollision is MovementCollisionShape.Circle && unit.pathPoints.isNotEmpty()
        ) {
            val bypass = step.parkedNeighbors?.bypass(unit, unit.pathPoints.first()) { from, to ->
                host.continuationFits(
                    host.planningTerrainFor(unit, map), from, to, unit.heading, HullRoute.forUnit(unit),
                    host.routeRadius(unit), host.routeOccupancy(unit),
                )
            }.orEmpty()
            if (bypass.isNotEmpty()) {
                unit.nav = unit.nav.prependWaypoints(bypass, resetLocalSegment = true)
            }
        }

        fun maneuverAim(): Position {
            val first = unit.pathPoints.first()
            if (unit.definition.movementCollision !is MovementCollisionShape.Circle || unit.pathPoints.size < 2 || unit.landingMember?.preciseApproach == true) return first
            val next = unit.pathPoints[1]
            val dx = next.x - first.x
            val dy = next.y - first.y
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared == 0.0) return first
            val fraction = (((unit.position.x - first.x) * dx + (unit.position.y - first.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val projection = Position(first.x + fraction * dx, first.y + fraction * dy)
            return if (projection == first || host.continuationFits(
                    host.planningTerrainFor(unit, map),
                    unit.position, projection, unit.heading, HullRoute.forUnit(unit),
                    host.routeRadius(unit), host.routeOccupancy(unit, includeMoving = true),
                )
            ) projection else first
        }

        // Rejoin a forward point on the existing leg if a push carried the body past its start.
        if (host.trafficSeparationEnabled && unit.landingMember != null && unit.pathPoints.size >= 2 &&
            unit.definition.movementCollision is MovementCollisionShape.Circle
        ) {
            val first = unit.pathPoints[0]
            val next = unit.pathPoints[1]
            val dx = next.x - first.x
            val dy = next.y - first.y
            val length = hypot(dx, dy)
            val radius = unit.definition.movementCollision.boundingRadius
            if (length > radius) {
                val passed = ((unit.position.x - first.x) * dx + (unit.position.y - first.y) * dy) / length
                val lateral = abs((unit.position.x - first.x) * dy - (unit.position.y - first.y) * dx) / length
                val passedTurn = passed > radius || (followsManeuver && passed > 0.0 && lateral <= radius &&
                    unit.landingMember?.preciseApproach != true)
                if (passedTurn && passed + radius < length) {
                    val t = minOf(1.0, (passed + 2 * radius) / length)
                    val join = Position(first.x + t * dx, first.y + t * dy)
                    if (host.continuationFits(
                            host.planningTerrainFor(unit, map), unit.position, join, unit.heading,
                            HullRoute.forUnit(unit), host.routeRadius(unit), host.routeOccupancy(unit, includeMoving = true),
                        )
                    ) {
                        unit.nav = unit.nav.replaceFrontWaypoint(join, resetLocalSegment = true)
                    }
                }
            }
        }

        val roundoffWaypoint = if (followsManeuver && unit.pathPoints.size > 1 &&
            unit.definition.movementCollision is MovementCollisionShape.Circle
        ) {
            val point = maneuverAim()
            val roundoff = 128.0 * Math.ulp(maxOf(abs(unit.position.x), abs(unit.position.y), abs(point.x), abs(point.y)))
            val onSegment = ScreenPlane.distanceSquared(unit.position, point) <= roundoff * roundoff
            val first = unit.pathPoints.first()
            val withinTurn = unit.landingMember?.let { !it.preciseApproach } == true &&
                policies.capture.inside(ScreenPlane.distanceSquared(unit.position, first), contract.pathPointRadius)
            val canJoin = (point != first || withinTurn) && host.continuationFits(
                host.planningTerrainFor(unit, map),
                unit.position, unit.pathPoints[1], unit.heading, HullRoute.forUnit(unit),
                host.routeRadius(unit), host.routeOccupancy(unit, includeMoving = true),
            )
            onSegment || canJoin
        } else false

        if (followsManeuver && target != null && unit.nearestMoveTarget != null && unit.terrainCorrectionTime > 0.0 &&
            executionStep % ROUTE_RETRY_TICKS == 0L &&
            unit.definition.movementCollision is MovementCollisionShape.Rectangle && unit.pathPoints.size > 1
        ) {
            host.scheduleManeuverRepair(unit, target, ManeuverRepair.SHORE_REJOIN)
        }
        if (followsManeuver && unit.pathPoints.size > 1 && (unit.position == unit.pathPoints.first() || roundoffWaypoint)) {
            unit.nav = unit.nav.popFrontWaypoint(clearLocalSegment = false, maneuverTurnWaypoint = unit.position)
        }

        val approachingLoad = taskKind == UnitTaskKind.LOAD_INTO || taskKind == UnitTaskKind.LOAD_UP
        if (target != null && !approachingLoad && taskKind != UnitTaskKind.PATROL) {
            val commandDistanceSquared = policies.coordinates.distanceSquared(unit.position, target)
            if (commandDistanceSquared < 1681.0) unit.nearTargetTime += delta
            val completionRadius = when {
                unit.landingMember != null -> 0.000001
                unit.nearestMoveTarget != null -> 0.000001
                followsManeuver -> 7.0
                unit.nearTargetTime > 340.0 -> 36.0
                unit.nearTargetTime > 240.0 -> 16.0
                else -> 7.0
            }
            val captured = (unit.landingMember == null || unit.landingMember?.claimed != null) &&
                policies.capture.inside(commandDistanceSquared, completionRadius)
            val clearsSlots = captured && host.groupStopClearsSlots(unit)
            val capturedGroupPose = unit.groupMoveReservation != null && clearsSlots
            if (!approachingBuilding && (!unit.routeTranslationClipped || capturedGroupPose) &&
                (!followsManeuver || unit.pathPoints.size == 1) &&
                clearsSlots
            ) {
                val originalTarget = unit.nearestMoveTarget ?: unit.landingMember?.group?.click ?: unit.groupMoveReservation?.plan?.click
                val wasGroup = unit.nearestMoveIsGroup || unit.landingMember != null || unit.groupMoveReservation != null
                val parked = unit.landingMember?.copy(parked = true, stoppedOrder = unit.tasks.firstOrNull())
                val parkedOwner = if (parked == null) unit.groupMoveReservation?.plan else null
                val arrival = originalTarget?.let {
                    MoveArrival(
                        it, unit.position,
                        MoveArrival.distanceToHull(unit.definition.movementCollision, unit.position, unit.heading, it),
                        executionStep, wasGroup,
                    )
                }
                unit.cancelRoute()
                unit.nav = unit.nav.markArrived(parked, parkedOwner, arrival)
                if (unit.tasks.firstOrNull()?.kind == UnitTaskKind.MOVE) unit.taskQueue = unit.taskQueue.pop()
                host.pendingEvents += Event("movement-arrived", unit.unitId)
                unit.speed = 0.0
                unit.angularVelocity = 0.0
                unit.flightVelocity = Position(0.0, 0.0)
                unit.movementState = MovementState.IDLE
                if (arrival != null) {
                    host.pendingEvents += Event("movement-nearest-reached", unit.unitId)
                }
                target = null
                normalCompleted = true
            }
        }

        if (target != null && air) {
            unit.requestMovement(host.logicalRouteTarget(unit, target))
            val d2 = policies.coordinates.distanceSquared(unit.position, target)
            val ordinaryBody = unit.definition.attackMovement == AttackMovementProfile.BOMBER ||
                unit.amphibiousState != null ||
                unit.definition.transport == com.rustedwarfare.reforge.core.content.TransportProfile.NATIVE_DROPSHIP
            val difference = if (ordinaryBody)
                BodyMotion.turnOrdinary(unit, target, delta, policies.steering, policies.coordinates)
            else policies.steering.turn(unit, target, delta, policies.coordinates)
            val fraction = policies.propulsion.targetFraction(
                movement, unit.speed, d2,
                if (ordinaryBody) difference else 0.0, true, contract.targetRadius,
            )
            requestedFraction = if (ordinaryBody && unit.reversing) -fraction * movement.reverseSpeedPercentage else fraction
            speedTarget = maximumSpeed * requestedFraction
            aimForSample = target
            finalForSample = true
            differenceForSample = difference
        }

        if (target != null && followsManeuver && unit.pathPoints.isNotEmpty()) {
            unit.requestMovement(host.logicalRouteTarget(unit, target))
            val leg = ManeuverLeg(unit.position, maneuverAim(), policies.coordinates)
            finalForSample = unit.pathPoints.size == 1
            val preciseCircleApproach = unit.landingMember?.let {
                it.preciseApproach || it.ingress != null || !finalForSample
            } == true ||
                (unit.landingMember == null && (unit.nearestMoveTarget != null || unit.groupMoveReservation != null)) ||
                (finalForSample && policies.coordinates.distanceSquared(unit.position, leg.target) <=
                    (unit.definition.movementCollision.boundingRadius * 1.4).let { it * it })
            val maneuverAvoid = if (host.trafficSeparationEnabled &&
                unit.definition.movementCollision is MovementCollisionShape.Circle &&
                !preciseCircleApproach
            ) LocomotionModule.solveGroundLocomotion(
                    host = host,
                    unit = unit,
                    terrain = host.planningTerrainFor(unit, map),
                    movement = movement,
                    desiredTarget = leg.target,
                    baseSpeedTarget = leg.approachSpeed(maximumSpeed, movement.moveDecelerationSpeed * maximumSpeed, finalForSample),
                    maximumSpeed = maximumSpeed,
                    finalPoint = finalForSample,
                    delta = delta,
                    coordinates = policies.coordinates,
                ).takeIf { it.applied } else null
            if (maneuverAvoid != null) {
                avoidanceApplied = true
                unit.nav = unit.nav.copy(lastAvoidHeadingDeg = maneuverAvoid.nextAvoidHeadingDeg)
                val probeDist = maxOf(32.0, sqrt(policies.coordinates.distanceSquared(unit.position, leg.target)))
                val probeOff = policies.coordinates.displacement(maneuverAvoid.targetHeadingDeg, probeDist)
                val effectiveAim = Position(unit.position.x + probeOff.x, unit.position.y + probeOff.y)
                aimForSample = effectiveAim
                differenceForSample = BodyMotion.turnOrdinary(
                    unit, effectiveAim, delta, policies.steering,
                    policies.coordinates, allowReverse = false, alignHeading = false,
                )
                val postErr = abs(policies.coordinates.difference(maneuverAvoid.targetHeadingDeg, unit.heading))
                val cosThrottle = when {
                    postErr <= 12.0 -> 1.0
                    postErr >= 95.0 -> 0.0
                    else -> maxOf(0.0, kotlin.math.cos(Math.toRadians((postErr - 12.0) * (90.0 / 83.0))))
                }
                speedTarget = minOf(maximumSpeed, maneuverAvoid.solvedVelocity.length()) * cosThrottle
                requestedFraction = if (maximumSpeed > 1e-7) speedTarget / maximumSpeed else 0.0
                step.state.separationInterventions++
            } else {
                if (unit.nav.lastAvoidHeadingDeg != null) unit.nav = unit.nav.copy(lastAvoidHeadingDeg = null)
                maneuverLeg = leg
                aimForSample = leg.target
                val wasAligned = leg.aligned(unit.heading)
                differenceForSample = BodyMotion.turnOrdinary(
                    unit, leg.target, delta, policies.steering,
                    policies.coordinates, allowReverse = false, alignHeading = true,
                )
                if (unit.definition.movementCollision is MovementCollisionShape.Circle) {
                    val distanceSquared = policies.coordinates.distanceSquared(unit.position, leg.target)
                    val difference = policies.coordinates.difference(policies.coordinates.desired(unit.position, leg.target), unit.heading)
                    requestedFraction = policies.propulsion.targetFraction(
                        movement, unit.speed,
                        if (reportsStop || unit.landingMember?.ingress == leg.target) maxOf(distanceSquared, 16.0)
                        else if (finalForSample) distanceSquared else maxOf(distanceSquared, 9.0),
                        difference, finalForSample, contract.targetRadius,
                    )
                    speedTarget = leg.approachSpeed(maximumSpeed, movement.moveDecelerationSpeed * maximumSpeed, finalForSample) * requestedFraction
                } else if (wasAligned) {
                    speedTarget = leg.approachSpeed(maximumSpeed, movement.moveDecelerationSpeed * maximumSpeed, finalForSample)
                    if (maximumSpeed > 0.0) requestedFraction = speedTarget / maximumSpeed
                }
            }
        } else if (target != null && !air && (unit.birthRemaining > 0f || unit.pathPoints.isNotEmpty())) {
            unit.requestMovement(host.logicalRouteTarget(unit, target))
            val temporaryBirth = unit.birthRemaining > 0f
            val local = if (temporaryBirth) null else unit.ensureLocalMotion()
            val turnDistance = if (unit.routeContinuation?.ready == true && unit.landingMember?.let {
                    !it.preciseApproach && it.claimed == null && it.ingress == null
                } == true && unit.definition.movementCollision is MovementCollisionShape.Circle
            ) maxOf(contract.pathPointRadius, minOf(map.tileWidth, map.tileHeight) * 2.0) else contract.pathPointRadius
            val nearHandoff = !temporaryBirth && unit.pathPoints.size > 1 && policies.capture.inside(
                policies.coordinates.distanceSquared(unit.position, unit.pathPoints.first()), turnDistance,
            )
            val usesPreparedJoin = nearHandoff && host.preparesRouteSuccessor(unit)
            val canHandOff = nearHandoff &&
                host.continuationFits(
                    host.planningTerrainFor(unit, map), Transport.queryPosition(unit, map),
                    unit.pathPoints[1], unit.heading, if (usesPreparedJoin) HullRoute.forUnit(unit) else null,
                    host.routeRadius(unit), host.routeOccupancy(unit),
                )
            if (canHandOff && usesPreparedJoin) {
                val routeSamples = step.routeContinuationSamples
                val continuation = host.requestRouteContinuation(unit, executionStep, 0.0, routeSamples)
                if (!continuation.ready) {
                    if (!continuation.waiting) {
                        unit.routeContinuation = continuation.copy(waiting = true)
                        host.recordRouteContinuation(routeSamples, executionStep, unit, continuation, 0.0, RouteContinuationStage.WAITING)
                    }
                    unit.speed = 0.0
                    unit.flightVelocity = Position(0.0, 0.0)
                    unit.stalledRouteTicks = 0L
                    unit.movementState = MovementState.MOVING
                    return SteeringIntent(target, 0.0, 0.0, null, null, false, 0.0, normalCompleted, abortedForContinuation = true)
                }
                host.recordRouteContinuation(routeSamples, executionStep, unit, continuation, 0.0, RouteContinuationStage.HANDED_OFF)
            }
            val plannedAim = local?.aim ?: target
            val steeringTarget = if (host.trafficSeparationEnabled) TrafficSeparation.mouthAim(unit, plannedAim) else plannedAim
            val finalPoint = temporaryBirth || (unit.pathPoints.size == 1 && checkNotNull(local).isLast)
            val d2 = policies.coordinates.distanceSquared(unit.position, steeringTarget)
            val preciseSlide = sliding && finalPoint && unit.nearestMoveTarget != null && steeringTarget == target
            val closingAnchor = usesPreparedJoin && !canHandOff && local?.isLast == true &&
                unit.pathPoints.size > 1 && steeringTarget == unit.pathPoints.first()
            val propulsionDistance = when {
                ((approachingBuilding || approachingWork || approachingLoad) && finalPoint) || preciseSlide -> maxOf(d2, 16.0)
                closingAnchor -> maxOf(d2, 9.0)
                else -> d2
            }
            val exactLegacySlotOrNearest = unit.landingMember == null &&
                (unit.nearestMoveTarget != null || unit.groupMoveReservation != null)
            val closeClaimedBay = unit.landingMember?.let {
                it.preciseApproach || (it.claimed != null && finalPoint &&
                    d2 <= (unit.definition.movementCollision.boundingRadius * 2.2).let { r -> r * r })
            } == true
            val useLocomotion2d = host.trafficSeparationEnabled && !temporaryBirth && !approachingLoad &&
                movement.type != MovementType.AIR && !preciseSlide && !exactLegacySlotOrNearest && !closeClaimedBay &&
                (taskKind == null || taskKind == UnitTaskKind.MOVE || taskKind == UnitTaskKind.PATROL ||
                    taskKind == UnitTaskKind.ATTACK || taskKind == UnitTaskKind.BUILD ||
                    taskKind == UnitTaskKind.CONSTRUCT || taskKind == UnitTaskKind.RECLAIM)
            val routeWaypoint = unit.pathPoints.firstOrNull() ?: target
            val terrainForAvoid = host.planningTerrainFor(unit, map)
            val directWaypointClear = PlanningSegmentClearance.allows(terrainForAvoid, Transport.queryPosition(unit, map), routeWaypoint, host.routeRadius(unit))
            val baseAvoidTarget = if (directWaypointClear) routeWaypoint else steeringTarget
            val avoidanceTarget = if (host.trafficSeparationEnabled) TrafficSeparation.mouthAim(unit, baseAvoidTarget) else baseAvoidTarget
            // Compute pure distance-based speed ceiling (heading diff = 0.0) so LocomotionModule preserves
            // final-approach braking and anchor-closing deceleration while applying its own cosine turn throttle.
            // Compute pure distance-based speed ceiling (heading diff = 0.0).
            // Only apply final-stop braking when genuinely approaching a final stop (finalPoint without
            // an unclaimed shared-landing group click) or a prepared hull closing anchor; intermediate
            // waypoints and unclaimed group-click approaches keep cruise speed into LocomotionModule.
            val applyFinalStopCurve = closingAnchor || (finalPoint &&
                (unit.landingMember == null || unit.landingMember?.claimed != null))
            val distanceOnlyFraction = policies.propulsion.targetFraction(
                movement, unit.speed, propulsionDistance,
                0.0, applyFinalStopCurve, contract.targetRadius,
            )
            val locSolve = if (useLocomotion2d) {
                LocomotionModule.solveGroundLocomotion(
                    host = host,
                    unit = unit,
                    terrain = terrainForAvoid,
                    movement = movement,
                    desiredTarget = avoidanceTarget,
                    baseSpeedTarget = maximumSpeed * distanceOnlyFraction,
                    maximumSpeed = maximumSpeed,
                    finalPoint = finalPoint,
                    delta = delta,
                    coordinates = policies.coordinates,
                )
            } else null
            val effectiveAim = if (locSolve != null && locSolve.applied) {
                val probeOffset = policies.coordinates.displacement(locSolve.targetHeadingDeg, maxOf(32.0, sqrt(d2)))
                Position(unit.position.x + probeOffset.x, unit.position.y + probeOffset.y)
            } else steeringTarget
            val diff = when {
                unit.mechState != null -> MechMotion.turn(unit, effectiveAim, delta, policies.steering, policies.coordinates)
                movement.type == MovementType.WATER -> BodyMotion.turnOrdinary(unit, effectiveAim, delta, policies.steering, policies.coordinates)
                else -> policies.steering.turn(unit, effectiveAim, delta, policies.coordinates)
            }
            val fraction: Double
            if (locSolve != null && locSolve.applied) {
                avoidanceApplied = true
                unit.nav = unit.nav.copy(lastAvoidHeadingDeg = locSolve.nextAvoidHeadingDeg)
                val effectiveHeading = if (unit.reversing) policies.coordinates.normalize(unit.heading + 180.0) else unit.heading
                val postTurnErr = abs(policies.coordinates.difference(locSolve.targetHeadingDeg, effectiveHeading))
                val isRect = unit.definition.movementCollision is MovementCollisionShape.Rectangle
                val cosThrottle = when {
                    movement.ignoringBody || postTurnErr <= 12.0 -> 1.0
                    postTurnErr >= 95.0 -> 0.0
                    else -> {
                        val c = kotlin.math.cos(Math.toRadians((postTurnErr - 12.0) * (90.0 / 83.0)))
                        if (isRect) c * c else maxOf(0.0, c)
                    }
                }
                val rawMag = minOf(maximumSpeed, locSolve.solvedVelocity.length()) * cosThrottle
                val signedSpeed = if (unit.reversing) -rawMag * movement.reverseSpeedPercentage else rawMag
                speedTarget = if (finalPoint && d2 < 9.0) 0.0 else signedSpeed
                fraction = if (maximumSpeed > 1e-7) abs(speedTarget) / maximumSpeed else 0.0
                requestedFraction = if (maximumSpeed > 1e-7) speedTarget / maximumSpeed else 0.0
                aimForSample = effectiveAim
                finalForSample = finalPoint
                differenceForSample = diff
                step.state.separationInterventions++
            } else {
                if (unit.nav.lastAvoidHeadingDeg != null) unit.nav = unit.nav.copy(lastAvoidHeadingDeg = null)
                fraction = policies.propulsion.targetFraction(
                    movement, unit.speed, propulsionDistance,
                    if (movement.ignoringBody) 0.0 else diff, finalPoint, contract.targetRadius,
                )
                aimForSample = steeringTarget
                finalForSample = finalPoint
                differenceForSample = diff
                requestedFraction = if (unit.reversing) -fraction * movement.reverseSpeedPercentage else fraction
                speedTarget = maximumSpeed * requestedFraction
            }
            if (preciseSlide && delta > 0.0) {
                val remaining = sqrt(d2)
                val rate = movement.moveAccelerationSpeed * 1.41
                speedTarget = sign(speedTarget) * minOf(abs(speedTarget), remaining / delta, sqrt(rate * remaining))
            }
            if (temporaryBirth) {
                // cn supplies the command aim directly, without normal route-node lookahead.
            } else if (canHandOff) {
                unit.nav = unit.nav.popFrontWaypoint(clearLocalSegment = true)
            } else {
                val driftedPastBreadcrumb = avoidanceApplied &&
                    policies.coordinates.distanceSquared(unit.position, checkNotNull(local).aim) > 784.0
                if (driftedPastBreadcrumb) {
                    unit.nav = unit.nav.copy(localMotion = null, localProgressTime = 0.0)
                } else {
                val progress = LocalMotionMaintenance.advance(
                    checkNotNull(local), unit.localProgressTime, delta, fraction > 0.0,
                    d2, !finalPoint && policies.capture.inside(d2, 16.0), unit.terrainCorrectionTime,
                    { policies.coordinates.distanceSquared(unit.position, it) },
                    { next ->
                        val occupancy = host.routeOccupancy(unit)
                        host.continuationFits(
                            host.planningTerrainFor(unit, map), Transport.queryPosition(unit, map), next, unit.heading,
                            if (occupancy == null) null else HullRoute.forUnit(unit), host.routeRadius(unit), occupancy,
                        )
                    },
                )
                unit.nav = unit.nav.copy(localMotion = progress.segment, localProgressTime = progress.activeTime)
                }
            }
        }

        if (target == null && optimisticTarget != null) {
            differenceForSample = policies.steering.turn(unit, optimisticTarget, delta, policies.coordinates)
            val cosFactor = kotlin.math.cos(Math.toRadians(kotlin.math.abs(differenceForSample).coerceAtMost(90.0)))
            // Preserve momentum if already moving AND the actual forward heading segment for this step is
            // terrain-clear; otherwise turn in place (speedTarget = 0) so we never coast into a wall while planning.
            val forwardProbeDist = maxOf(unit.speed * delta * 1.5, host.routeRadius(unit) * 0.5)
            val forwardOffset = policies.coordinates.displacement(unit.heading, forwardProbeDist)
            val forwardProbe = Position(
                unit.position.x + forwardOffset.x,
                unit.position.y + forwardOffset.y,
            )
            val forwardClear = movement.type == MovementType.AIR ||
                PlanningSegmentClearance.allows(
                    host.planningTerrainFor(unit, map),
                    Transport.queryPosition(unit, map),
                    forwardProbe,
                    host.routeRadius(unit),
                )
            speedTarget = if (unit.speed > 0.0 && forwardClear) maxOf(unit.speed * cosFactor, maximumSpeed * 0.25 * cosFactor)
                else 0.0
            requestedFraction = if (maximumSpeed > 0.0) (speedTarget / maximumSpeed).coerceIn(0.0, 1.0) else 0.0
            aimForSample = optimisticTarget
        }
        return SteeringIntent(
            target = target,
            speedTarget = speedTarget,
            requestedFraction = requestedFraction,
            maneuverLeg = maneuverLeg,
            aimForSample = aimForSample,
            finalForSample = finalForSample,
            differenceForSample = differenceForSample,
            normalCompleted = normalCompleted,
            avoidanceApplied = avoidanceApplied,
        )
    }

    /** Phase 3: Hull turn terrain/attachment/contact clamping and escape probing. */
    private fun resolveHeadingAndTurn(
        host: UnitMovementHost,
        unit: MutableUnit,
        map: MapDefinition,
        movement: com.rustedwarfare.reforge.core.content.MovementProfile,
        maximumSpeed: Double,
        delta: Double,
        headingBefore: Double,
        steering: SteeringIntent,
    ): HeadingResolution {
        val policies = host.policies
        val maneuverLeg = steering.maneuverLeg
        val shape = UnitLocomotion.terrainHull(unit)
        val hull = shape as? MovementCollisionShape.Rectangle
        val circle = (shape as? MovementCollisionShape.Circle)?.let { CircularTerrain(it.radius) }
        val collisionMap = if (unit.birthRemaining > 0f) map else host.buildingTerrain?.collisionMap ?: map
        val hullTerrain = shape?.let { HullTerrainGrid.authoritative(collisionMap, movement.type) }
        val requestedHeading = unit.heading
        val nearShoreLeg = maneuverLeg != null && unit.nearestMoveTarget != null && hull != null && movement.type == MovementType.WATER
        val terrainHeading = if (hull != null && requestedHeading != headingBefore) {
            val grid = checkNotNull(hullTerrain)
            val limit = RectangularTurn.resolve(grid, hull, unit.position, headingBefore, requestedHeading)
            if (!nearShoreLeg || UnitContact.poseFits(hull, unit.position, limit, grid)) limit else {
                val angle = policies.coordinates.difference(limit, headingBefore)
                var safe = headingBefore
                var low = 0.0
                var high = 1.0
                repeat(48) {
                    val mid = (low + high) * .5
                    val pose = policies.coordinates.normalize(headingBefore + angle * mid)
                    if (UnitContact.poseFits(hull, unit.position, pose, grid)) { low = mid; safe = pose } else high = mid
                }
                safe
            }
        } else requestedHeading
        val boundedHeading = UnitAttachments.limitTurn(host.units, unit, map, headingBefore, terrainHeading)
        val legalHeading = if (hull != null && boundedHeading != headingBefore) {
            UnitContact.turn(unit, hull, headingBefore, boundedHeading, host.spatialIndex)
        } else boundedHeading
        val turnProbe = if (nearShoreLeg && unit.terrainCorrectionTime > 0.0 && boundedHeading == requestedHeading) {
            val wanted = policies.coordinates.desired(unit.position, maneuverLeg.target)
            val arc = movement.maxTurnSpeed * delta
            policies.coordinates.normalize(headingBefore + policies.coordinates.difference(wanted, headingBefore).coerceIn(-arc, arc))
        } else boundedHeading
        val forwardTurnEscape = nearShoreLeg && (legalHeading != boundedHeading || turnProbe != boundedHeading) &&
            UnitContact.canAdvanceAgainstTurn(
                unit, hull, headingBefore, turnProbe, host.spatialIndex,
                { host.knowsRouteOccupant(unit, it) }, host::canDisplaceInContact,
            )
        val sailClearOfBank = nearShoreLeg && terrainHeading != requestedHeading && legalHeading == terrainHeading
        var speedTarget = steering.speedTarget
        var requestedFraction = steering.requestedFraction
        if (forwardTurnEscape || sailClearOfBank) {
            speedTarget = maximumSpeed
            requestedFraction = if (maximumSpeed > 0.0) 1.0 else 0.0
        }
        if (legalHeading != requestedHeading) {
            unit.heading = legalHeading
            unit.angularVelocity = 0.0
            val adjustment = policies.coordinates.difference(legalHeading, requestedHeading)
            if (boundedHeading != terrainHeading) unit.mechState = unit.mechState?.let { state ->
                state.copy(motionAngle = MechMotion.angleDifference(state.motionAngle + adjustment, 0.0))
            }
            if (movement.type == MovementType.WATER && unit.definition.combat?.rotateWithBody == true) {
                for (state in unit.namedWeapons?.values ?: listOfNotNull(unit.combat)) {
                    state.turretHeading = policies.coordinates.normalize(state.turretHeading + adjustment)
                }
            }
        }
        return HeadingResolution(
            requestedHeading = requestedHeading,
            terrainHeading = terrainHeading,
            boundedHeading = boundedHeading,
            legalHeading = legalHeading,
            speedTarget = speedTarget,
            requestedFraction = requestedFraction,
            nearShoreLeg = nearShoreLeg,
            forwardTurnEscape = forwardTurnEscape,
            sailClearOfBank = sailClearOfBank,
            shape = shape,
            hull = hull,
            circle = circle,
            collisionMap = collisionMap,
            hullTerrain = hullTerrain,
        )
    }

    /** Phase 4: Velocity integration, terrain sweep, contact push settlement, spatial-index sync, and sample capture. */
    private fun integrateAndResolveContact(
        host: UnitMovementHost,
        unit: MutableUnit,
        map: MapDefinition,
        movement: com.rustedwarfare.reforge.core.content.MovementProfile,
        maximumSpeed: Double,
        air: Boolean,
        sliding: Boolean,
        delta: Double,
        executionStep: Long,
        step: CandidateStep,
        positionBefore: Position,
        headingBefore: Double,
        speedBefore: Double,
        angularVelocityBefore: Double,
        commandBefore: Position?,
        originBefore: String?,
        progressBefore: Double,
        correctionBefore: Double,
        steering: SteeringIntent,
        heading: HeadingResolution,
    ) {
        val policies = host.policies
        val nativeDeparture = FactoryExit.isNativeSeaDeparture(unit)
        val maneuverLeg = steering.maneuverLeg
        val aimForSample = steering.aimForSample
        val finalForSample = steering.finalForSample
        val differenceForSample = steering.differenceForSample
        val normalCompleted = steering.normalCompleted
        var speedTarget = heading.speedTarget
        var requestedFraction = heading.requestedFraction

        unit.mechState = unit.mechState?.copy(hadMotionAim = aimForSample != null)
        Transport.recordMotionGate(unit, aimForSample != null)
        if (!steering.avoidanceApplied) {
            val followingCap = step.separationCaps[unit.unitId]
            if (followingCap != null && speedTarget > followingCap) {
                speedTarget = followingCap
                requestedFraction = if (maximumSpeed > 0.0) speedTarget / maximumSpeed else 0.0
                step.state.separationInterventions++
            }
        }
        val displacement = if (sliding) {
            val desiredVelocity = aimForSample?.let {
                policies.coordinates.displacement(policies.coordinates.desired(unit.position, it), abs(speedTarget))
            } ?: Position(0.0, 0.0)
            unit.flightVelocity = FlightMotion.velocity(movement, unit.flightVelocity, desiredVelocity, requestedFraction, delta, maximumSpeed)
            unit.speed = hypot(unit.flightVelocity.x, unit.flightVelocity.y)
            Position(unit.flightVelocity.x * delta, unit.flightVelocity.y * delta)
        } else {
            val rate = if (unit.speed < speedTarget) movement.moveAccelerationSpeed else movement.moveDecelerationSpeed
            unit.speed = host.approachSpeed(unit.speed, speedTarget, rate * maximumSpeed * delta)
            if (unit.definition.attackMovement == AttackMovementProfile.BOMBER) {
                unit.flightVelocity = policies.coordinates.displacement(unit.heading, unit.speed)
                Position(unit.flightVelocity.x * delta, unit.flightVelocity.y * delta)
            } else policies.coordinates.displacement(unit.heading, unit.speed * delta)
        }

        val finishNearestWaterLeg = maneuverLeg != null && finalForSample && movement.type == MovementType.WATER &&
            unit.nearestMoveTarget != null && unit.landingMember == null &&
            unit.definition.movementCollision is MovementCollisionShape.Circle &&
            ScreenPlane.distanceSquared(unit.position, maneuverLeg.target) <= (maximumSpeed * delta) * (maximumSpeed * delta)
        val candidate = if (maneuverLeg != null && (unit.landingMember?.preciseApproach == true ||
                finishNearestWaterLeg ||
                unit.landingMember?.claimed == maneuverLeg.target && ScreenPlane.distanceSquared(unit.position, maneuverLeg.target) <=
                unit.definition.movementCollision.boundingRadius * unit.definition.movementCollision.boundingRadius)
        ) maneuverLeg.position(unit.heading, unit.speed * delta)
        else if (maneuverLeg != null && unit.definition.movementCollision is MovementCollisionShape.Circle) {
            if (maneuverLeg.aligned(unit.heading) &&
                policies.coordinates.distanceSquared(unit.position, maneuverLeg.target) <= unit.speed * delta * unit.speed * delta
            ) maneuverLeg.target else Position(unit.position.x + displacement.x, unit.position.y + displacement.y)
        } else if (heading.forwardTurnEscape || heading.sailClearOfBank) Position(unit.position.x + displacement.x, unit.position.y + displacement.y)
        else maneuverLeg?.position(unit.heading, unit.speed * delta)
            ?: Position(unit.position.x + displacement.x, unit.position.y + displacement.y)

        val integratedHeading = unit.heading
        val integratedSpeed = unit.speed
        fun recordMotion(resolved: Position?, disposition: MotionTerrainDisposition, unitContactLimited: Boolean) {
            step.motionSamples?.add(
                MotionStepSample(
                    host.activeWorld.id, unit.unitId, unit.ownerPlayerId, unit.typeId,
                    executionStep, host.simulationTime, delta, originBefore, positionBefore, headingBefore, speedBefore, angularVelocityBefore,
                    commandBefore, normalCompleted, aimForSample != null, aimForSample,
                    finalForSample.takeIf { aimForSample != null }, differenceForSample.takeIf { aimForSample != null },
                    requestedFraction, integratedHeading, integratedSpeed, candidate, resolved, disposition,
                    unit.position, unit.heading, unit.speed, unit.angularVelocity,
                    progressBefore, unit.localProgressTime, correctionBefore, unit.terrainCorrectionTime, unitContactLimited,
                )
            )
        }
        if (!candidate.x.isFinite() || !candidate.y.isFinite()) {
            throw ArithmeticException("Non-finite movement candidate for ${unit.unitId}")
        }

        val shape = heading.shape
        val hull = heading.hull
        val circle = heading.circle
        val hullTerrain = heading.hullTerrain
        val rectangle = hull?.let { RectangularTerrain(it, unit.heading) }
        if (shape == null && !Transport.containsPosition(unit, map, candidate)) {
            host.blockAtMapBoundary(unit)
            recordMotion(null, MotionTerrainDisposition.CANDIDATE_OUTSIDE_MAP, heading.legalHeading != heading.boundedHeading)
            return
        }
        val producerCells = if (shape != null && unit.birthRemaining == 0f && host.buildingTerrain != null)
            FactoryExit.departingSeaFactoryCells(map, unit, unit.productionOrigin?.let { host.units[it.producerId] }, candidate, shape)
        else emptySet()
        val translationTerrain = if (producerCells.isEmpty()) hullTerrain
        else HullTerrainGrid.leavingProducer(heading.collisionMap, map, movement.type, producerCells)
        val terrainResolved = when {
            rectangle != null -> rectangle.resolve(checkNotNull(translationTerrain), unit.position, candidate)
            circle != null -> circle.resolve(checkNotNull(translationTerrain), unit.position, candidate)
            air -> candidate
            unit.birthRemaining > 0f -> TerrainMotion.resolve(map, unit.position, candidate, movementType = movement.type)
            else -> TerrainMotion.resolve(
                host.buildingTerrain?.collisionMap ?: map, unit.position, candidate,
                map.takeIf { host.buildingTerrain != null }, movement.type, unit.nativeTransportPlacement,
            )
        }
        val legalTerrainEnd = if (heading.nearShoreLeg) UnitContact.lastLegalPoint(unit.position, terrainResolved) {
            UnitContact.poseFits(checkNotNull(hull), it, unit.heading, checkNotNull(translationTerrain))
        } else terrainResolved
        val boundedPosition = UnitAttachments.limitTranslation(host.units, unit, map, legalTerrainEnd)
        fun insideMap(position: Position): Boolean = when {
            rectangle != null -> rectangle.contains(checkNotNull(hullTerrain), position)
            circle != null -> circle.contains(checkNotNull(hullTerrain), position)
            else -> Transport.containsPosition(unit, map, position)
        }
        val ordinaryMove = unit.tasks.firstOrNull()?.kind == UnitTaskKind.MOVE
        val contactMap = host.buildingTerrain?.collisionMap ?: map
        val canPush: ((MutableUnit, MutableUnit) -> Boolean)? = if (unit.birthRemaining == 0f && insideMap(boundedPosition)) {
            { pusher, blocker ->
                host.canDisplaceInContact(blocker) && !(pusher.landingMember != null &&
                    blocker.ownerPlayerId == pusher.ownerPlayerId && blocker.landingMember?.parked == true) &&
                    !Transport.isLoadingPairAtContact(pusher, blocker, host.units)
            }
        } else null
        val translation = step.translations.resolve(
            unit, boundedPosition, contactMap, host.units, canPush, map, heading.forwardTurnEscape,
        ) { blocker ->
            host.isVisibleTo(unit.ownerPlayerId, blocker.position, blocker.nativeTransportPlacement) &&
                !(unit.landingMember?.preciseApproach == true && host.isParkedPeer(unit, blocker))
        }
        val resolved = translation.position
        val drivingGroup = unit.landingMember?.group
        val roadAim = unit.pathPoints.firstOrNull() ?: unit.moveTarget
        if (drivingGroup != null && roadAim != null && insideMap(resolved) &&
            ScreenPlane.distanceSquared(positionBefore, resolved) > 1e-8 &&
            ScreenPlane.distanceSquared(resolved, roadAim) < ScreenPlane.distanceSquared(positionBefore, roadAim)
        ) {
            unit.lastRoadProgress = RoadProgress(drivingGroup, executionStep, resolved.x - positionBefore.x, resolved.y - positionBefore.y)
        }
        step.translations.recordOwnMotion(
            unit, positionBefore, resolved, heading.legalHeading != headingBefore,
            heading.legalHeading != heading.boundedHeading,
        )
        if (translation.supported) {
            unit.heading = headingBefore
            unit.speed = 0.0
            unit.angularVelocity = 0.0
            unit.flightVelocity = Position(0.0, 0.0)
        }
        val unitContactLimited = translation.supported || heading.legalHeading != heading.boundedHeading || resolved != boundedPosition
        if (!insideMap(resolved)) {
            host.blockAtMapBoundary(unit)
            recordMotion(boundedPosition, MotionTerrainDisposition.RESOLVED_OUTSIDE_MAP, unitContactLimited)
            return
        }
        for (shift in translation.displaced) {
            step.translations.recordPush(shift.unit, shift.position)
            shift.unit.position = shift.position
            host.spatialIndex.relocate(shift.unit)
            UnitAttachments.synchronizeAdjustedChildren(host.units, shift.unit)
            for (cid in shift.unit.attachmentIds.values) host.units[cid]?.let(host.spatialIndex::relocate)
        }
        val terrainCorrected = heading.boundedHeading != heading.requestedHeading || boundedPosition != candidate
        if (unit.moveTarget != null && (nativeDeparture || ordinaryMove)) {
            val clipped = resolved != candidate
            val attemptedPoseChange = candidate != positionBefore || heading.requestedHeading != headingBefore
            val heldAtPriorPose = resolved == positionBefore && heading.legalHeading == headingBefore
            val stalled = if (attemptedPoseChange && heldAtPriorPose && (terrainCorrected || unitContactLimited))
                unit.stalledRouteTicks + 1L else 0L
            unit.nav = unit.nav.copy(
                routeTranslationClipped = clipped,
                nearTargetTime = if (clipped) 0.0 else unit.nearTargetTime,
                stalledRouteTicks = stalled,
            )
        }
        if (terrainCorrected || unitContactLimited) {
            host.pendingEvents += Event("movement-blocked", unit.unitId)
            unit.nav = unit.nav.copy(
                terrainCorrectionTime = unit.terrainCorrectionTime + delta,
                terrainClearSteps = 0,
                terrainOnlyStall = terrainCorrected && !unitContactLimited,
            )
        } else if (delta > 0.0) {
            val clearSteps = (unit.terrainClearSteps + 1).coerceAtMost(3)
            val cleared = clearSteps >= 3
            unit.nav = unit.nav.copy(
                terrainClearSteps = clearSteps,
                terrainCorrectionTime = if (cleared) 0.0 else unit.terrainCorrectionTime,
                terrainOnlyStall = if (cleared) false else unit.terrainOnlyStall,
                repairBackoffStep = if (cleared) 0L else unit.repairBackoffStep,
                repairConsecutiveFailures = if (cleared) 0 else unit.repairConsecutiveFailures,
            )
        }
        unit.position = resolved
        host.spatialIndex.relocate(unit)
        if (unit.attachmentOffsets.isNotEmpty()) {
            UnitAttachments.synchronizeAdjustedChildren(host.units, unit)
            for (number in unit.attachmentOffsets.keys) {
                unit.attachmentIds[number]?.let(host.units::get)?.let(host.spatialIndex::relocate)
            }
        }
        if (unit.maneuverTurnWaypoint != null && resolved != unit.maneuverTurnWaypoint) unit.maneuverTurnWaypoint = null
        if (maneuverLeg != null && !finalForSample && resolved == maneuverLeg.target) unit.speed = 0.0
        if (movement.type == MovementType.OVER_CLIFF && aimForSample != null) {
            val column = MapBounds.cell(unit.position.x, map.tileWidth, map.widthTiles)
            val row = MapBounds.cell(unit.position.y, map.tileHeight, map.heightTiles)
            unit.terrainSpeedMultiplier = if (map.isCliff(column, row)) .7 else 1.0
        }
        unit.movementState = when {
            translation.supported -> MovementState.BLOCKED
            unit.moveTarget != null -> MovementState.MOVING
            unit.speed != 0.0 -> MovementState.DECELERATING
            else -> MovementState.IDLE
        }
        recordMotion(
            boundedPosition,
            if (terrainCorrected) MotionTerrainDisposition.CORRECTED else MotionTerrainDisposition.DIRECT,
            unitContactLimited,
        )
    }
}
