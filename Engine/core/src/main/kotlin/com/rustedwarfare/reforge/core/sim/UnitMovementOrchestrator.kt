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
    val landingOccupancy: LandingOccupancy
    val flowFields: ChunkedFlowFieldAdapter
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
        val airSeparationVel: LocomotionModule.Vec2? = null,
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
        val narrowMouthStagingAim = if (host.trafficSeparationEnabled && passageWaiting &&
            unit.passageEntry?.commandId == unit.originMoveId &&
            unit.passageEntry?.entered == false &&
            unit.moveTarget != null &&
            movement.type != MovementType.AIR && movement.type != MovementType.NONE
        ) {
            LocomotionModule.computeNarrowMouthStagingAim(host, unit, host.planningTerrainFor(unit, map))
        } else null
        if (gateMovementLockOrWait(host, unit, movement, delta, executionStep, step, passageWaiting && narrowMouthStagingAim == null, departureWaiting)) {
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
            host, unit, map, movement, maximumSpeed, air, sliding, delta, executionStep, step, narrowMouthStagingAim,
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
        val publicationWaiting = step.state.movePlanning.replacingDestination(unit.unitId)
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
            unit.nav = unit.nav.copy(stalledRouteTicks = 0L, nearTargetTime = 0.0, localMotion = null, localProgressTime = 0.0)
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
        narrowMouthStagingAim: Position?,
    ): SteeringIntent {
        val contract = host.movementContract
        val policies = host.policies
        var normalCompleted = false
        var aimForSample: Position? = null
        var finalForSample = false
        var differenceForSample = 0.0
        var requestedFraction = 0.0
        var target = unit.moveTarget
        var speedTarget = 0.0
        // Optimistic same-step turn while initial P0 planning runs (ENG-P016-005):
        // preserves speed and starts rotating toward the fresh right-click immediately on the command step.
        val optimisticTarget = if (target == null &&
            (step.state.movePlanning.pending(unit.unitId) || unit.unitId in step.state.pendingFlowStart)
        ) {
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
            (unit.groupMoveReservation != null || unit.landingMember?.claimed != null) && unit.pathPoints.size == 1) &&
            taskKind == UnitTaskKind.MOVE && !air && !sliding

        fun maneuverAim(): Position {
            val first = unit.pathPoints.first()
            if (unit.definition.movementCollision !is MovementCollisionShape.Circle || unit.pathPoints.size < 2) return first
            val next = unit.pathPoints[1]
            val dx = next.x - first.x
            val dy = next.y - first.y
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared == 0.0) return first
            val fraction = (((unit.position.x - first.x) * dx + (unit.position.y - first.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val projection = Position(first.x + fraction * dx, first.y + fraction * dy)
            return if (projection == first || host.continuationFits(
                    host.planningTerrainFor(unit, map),
                    unit.position, projection, unit.heading, HullRoute.forUnit(unit, map),
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
                val passedTurn = passed > radius || (followsManeuver && passed > 0.0 && lateral <= radius)
                if (passedTurn && passed + radius < length) {
                    val t = minOf(1.0, (passed + 2 * radius) / length)
                    val join = Position(first.x + t * dx, first.y + t * dy)
                    if (host.continuationFits(
                            host.planningTerrainFor(unit, map), unit.position, join, unit.heading,
                            HullRoute.forUnit(unit, map), host.routeRadius(unit), host.routeOccupancy(unit, includeMoving = true),
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
            val withinTurn = unit.landingMember != null &&
                policies.capture.inside(ScreenPlane.distanceSquared(unit.position, first), contract.pathPointRadius)
            val canJoin = (point != first || withinTurn) && host.continuationFits(
                host.planningTerrainFor(unit, map),
                unit.position, unit.pathPoints[1], unit.heading, HullRoute.forUnit(unit, map),
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
            val airRallyCaptured = air && host.trafficSeparationEnabled && taskKind == UnitTaskKind.MOVE &&
                unit.birthRemaining == 0f && LocomotionModule.isAirRallyRingArrived(host, unit, target)
            val captured = (unit.landingMember == null || unit.landingMember?.claimed != null) &&
                (policies.capture.inside(commandDistanceSquared, completionRadius) || airRallyCaptured)
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

            // ENG-P016-009B: Unified single terminal landing evaluation (continuous centripetal + tangential flank
            // sliding + O(1) anti-throat-plugging contagious settling).
            val terminalDecision = if (target != null && !air && taskKind == UnitTaskKind.MOVE &&
                unit.birthRemaining == 0f && unit.landingMember != null && !unit.landingMember!!.parked
            ) {
                val tTerrain = host.planningTerrainFor(unit, map)
                val gdx = target.x - unit.position.x
                val gdy = target.y - unit.position.y
                val gd = hypot(gdx, gdy)
                val dirX = if (gd > 1e-5) gdx / gd else policies.coordinates.displacement(unit.heading, 1.0).x
                val dirY = if (gd > 1e-5) gdy / gd else policies.coordinates.displacement(unit.heading, 1.0).y
                val td = TerminalLanding.evaluate(host, unit, map, tTerrain, target, dirX, dirY, maximumSpeed, delta)
                if (td != null) {
                    if (td.updatedMember != unit.landingMember) unit.landingMember = td.updatedMember
                    if (td.settleNow) {
                        return settleSaturationOverflow(host, unit, step, executionStep)
                    }
                }
                td
            } else null

        var airSepVec: LocomotionModule.Vec2? = null
        if (narrowMouthStagingAim != null && target != null) {
            unit.requestMovement(host.logicalRouteTarget(unit, target))
            unit.nav = unit.nav.copy(stalledRouteTicks = 0L, nearTargetTime = 0.0, localMotion = null, localProgressTime = 0.0)
            val terrainForStage = host.planningTerrainFor(unit, map)
            val stageDist = sqrt(policies.coordinates.distanceSquared(unit.position, narrowMouthStagingAim))
            val stageSpeedCap = minOf(maximumSpeed * 0.72, stageDist / maxOf(delta, 1.0))
            val stageSolve = LocomotionModule.solveGroundLocomotion(
                host = host,
                unit = unit,
                terrain = terrainForStage,
                movement = movement,
                desiredTarget = narrowMouthStagingAim,
                baseSpeedTarget = stageSpeedCap,
                maximumSpeed = maximumSpeed,
                finalPoint = true,
                delta = delta,
                coordinates = policies.coordinates,
                terminalDockingWeight = 0.35,
                ignoredContactUnitId = unit.attackCommand?.targetUnitId ?: unit.primaryCombatTargetId,
            )
            val effectiveAim = if (stageSolve.applied) {
                avoidanceApplied = true
                unit.nav = unit.nav.copy(lastAvoidHeadingDeg = stageSolve.nextAvoidHeadingDeg)
                val probe = policies.coordinates.displacement(stageSolve.targetHeadingDeg, maxOf(24.0, stageDist))
                Position(unit.position.x + probe.x, unit.position.y + probe.y)
            } else narrowMouthStagingAim
            val diff = BodyMotion.turnOrdinary(
                unit, effectiveAim, delta, policies.steering, policies.coordinates, allowReverse = false, alignHeading = false,
            )
            val desiredHeading = policies.coordinates.desired(unit.position, effectiveAim)
            val postErr = abs(policies.coordinates.difference(desiredHeading, unit.heading))
            val cosT = when {
                postErr <= 15.0 -> 1.0
                postErr >= 95.0 -> 0.0
                else -> maxOf(0.0, kotlin.math.cos(Math.toRadians((postErr - 15.0) * (90.0 / 80.0))))
            }
            val rawSpd = if (stageSolve.applied) minOf(stageSpeedCap, stageSolve.solvedVelocity.length()) else stageSpeedCap
            speedTarget = rawSpd * cosT
            requestedFraction = if (maximumSpeed > 1e-7) speedTarget / maximumSpeed else 0.0
            aimForSample = effectiveAim
            finalForSample = false
            differenceForSample = diff
            return SteeringIntent(
                target = target,
                speedTarget = speedTarget,
                requestedFraction = requestedFraction,
                maneuverLeg = null,
                aimForSample = aimForSample,
                finalForSample = finalForSample,
                differenceForSample = differenceForSample,
                normalCompleted = normalCompleted,
                avoidanceApplied = avoidanceApplied,
            )
        }

        if (air && host.trafficSeparationEnabled && !approachingLoad) {
            airSepVec = LocomotionModule.solveAirSoftSeparation(
                host = host,
                unit = unit,
                movement = movement,
                desiredTarget = target,
                maximumSpeed = maximumSpeed,
                delta = delta,
            )
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
            if (airSepVec != null && !ordinaryBody && sliding) {
                val baseDir = policies.coordinates.displacement(policies.coordinates.desired(unit.position, target), abs(speedTarget))
                val blendX = baseDir.x + airSepVec.x * 0.55
                val blendY = baseDir.y + airSepVec.y * 0.55
                val blendMag = hypot(blendX, blendY)
                if (blendMag > 1e-5) {
                    val capped = minOf(maximumSpeed, blendMag)
                    aimForSample = Position(unit.position.x + blendX / blendMag * maxOf(32.0, sqrt(d2)), unit.position.y + blendY / blendMag * maxOf(32.0, sqrt(d2)))
                    speedTarget = capped
                    requestedFraction = if (maximumSpeed > 1e-7) capped / maximumSpeed else 0.0
                    avoidanceApplied = true
                }
            }
        } else if (target == null && air && airSepVec != null && unit.birthRemaining == 0f) {
            // Idle airborne flock soft separation: drift cleanly via airSeparationVel in Phase 4
            // while FlightMotion decelerates residual flightVelocity toward zero (prevents inertia oscillation).
            avoidanceApplied = true
        }

        if (target != null && followsManeuver && unit.pathPoints.isNotEmpty()) {
            unit.requestMovement(host.logicalRouteTarget(unit, target))
            val rawManeuverTarget = maneuverAim()
            val alignedManeuverTarget = if (host.trafficSeparationEnabled) {
                LocomotionModule.alignNarrowMouthReentryAim(unit, rawManeuverTarget)
            } else rawManeuverTarget
            val reentryAlignedManeuver = alignedManeuverTarget != rawManeuverTarget
            val leg = ManeuverLeg(unit.position, alignedManeuverTarget, policies.coordinates)
            finalForSample = !reentryAlignedManeuver && unit.pathPoints.size == 1
            val hardRadius = maxOf(1.0, unit.definition.movementCollision.boundingRadius)
            val distSqToLeg = policies.coordinates.distanceSquared(unit.position, leg.target)
            val isRectManeuver = unit.definition.movementCollision is MovementCollisionShape.Rectangle
            val canRejoinRectManeuver = isRectManeuver && !approachingLoad && unit.nearestMoveTarget == null &&
                !(finalForSample && unit.groupMoveReservation != null && distSqToLeg <= (hardRadius * 2.5).let { it * it })
            val isExactManeuverGoal = unit.nearestMoveTarget != null ||
                unit.groupMoveReservation != null ||
                unit.landingMember?.claimed != null
            val preciseCircleApproach = (unit.landingMember?.claimed != null && !finalForSample) ||
                (finalForSample && isExactManeuverGoal && distSqToLeg <= (hardRadius * 2.2).let { it * it }) ||
                (finalForSample && distSqToLeg <= (hardRadius * 1.4).let { it * it })
            val maneuverDockingWeight = maxOf(
                if (finalForSample && isExactManeuverGoal) {
                    ((3.2 * hardRadius - sqrt(distSqToLeg)) / maxOf(1.0, 1.85 * hardRadius)).coerceIn(0.0, 1.0)
                } else 0.0,
                if (terminalDecision?.inClusterZone == true && terminalDecision.flankAim == null) 0.72 else 0.0,
            )
            val ignoredTargetId = unit.attackCommand?.targetUnitId ?: unit.primaryCombatTargetId
            val maneuverDesired = terminalDecision?.flankAim ?: leg.target
            val maneuverAvoid = if (host.trafficSeparationEnabled &&
                unit.definition.movementCollision is MovementCollisionShape.Circle && (!preciseCircleApproach || terminalDecision?.flankAim != null)
            ) LocomotionModule.solveGroundLocomotion(
                    host = host,
                    unit = unit,
                    terrain = host.planningTerrainFor(unit, map),
                    movement = movement,
                    desiredTarget = maneuverDesired,
                    baseSpeedTarget = if (terminalDecision?.flankAim != null) maximumSpeed * 0.85
                        else leg.approachSpeed(maximumSpeed, movement.moveDecelerationSpeed * maximumSpeed, finalForSample),
                    maximumSpeed = maximumSpeed,
                    finalPoint = finalForSample && terminalDecision?.flankAim == null,
                    delta = delta,
                    coordinates = policies.coordinates,
                    terminalDockingWeight = maneuverDockingWeight,
                    ignoredContactUnitId = ignoredTargetId,
                    protectedClusterKey = unit.landingMember?.group,
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
                val fallbackAim = terminalDecision?.flankAim ?: leg.target
                aimForSample = fallbackAim
                val wasAligned = leg.aligned(unit.heading)
                differenceForSample = BodyMotion.turnOrdinary(
                    unit, fallbackAim, delta, policies.steering,
                    policies.coordinates, allowReverse = false, alignHeading = terminalDecision?.flankAim == null,
                )
                if (unit.definition.movementCollision is MovementCollisionShape.Circle) {
                    val distanceSquared = policies.coordinates.distanceSquared(unit.position, leg.target)
                    val difference = policies.coordinates.difference(policies.coordinates.desired(unit.position, leg.target), unit.heading)
                    requestedFraction = policies.propulsion.targetFraction(
                        movement, unit.speed,
                        if (reentryAlignedManeuver) maxOf(distanceSquared, 25.0)
                        else if (reportsStop) maxOf(distanceSquared, 16.0)
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
            val turnDistance = if (unit.routeContinuation?.ready == true && unit.landingMember?.claimed == null &&
                unit.landingMember != null && unit.definition.movementCollision is MovementCollisionShape.Circle
            ) maxOf(contract.pathPointRadius, minOf(map.tileWidth, map.tileHeight) * 2.0) else contract.pathPointRadius
            val nearHandoff = !temporaryBirth && unit.pathPoints.size > 1 && policies.capture.inside(
                policies.coordinates.distanceSquared(unit.position, unit.pathPoints.first()), turnDistance,
            )
            val usesPreparedJoin = nearHandoff && host.preparesRouteSuccessor(unit)
            val canHandOff = nearHandoff &&
                host.continuationFits(
                    host.planningTerrainFor(unit, map), Transport.queryPosition(unit, map),
                    unit.pathPoints[1], unit.heading,
                    if (usesPreparedJoin || approachingWork) HullRoute.forUnit(unit, map) else null,
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
            val steeringTarget = if (host.trafficSeparationEnabled) {
                LocomotionModule.alignNarrowMouthReentryAim(unit, plannedAim)
            } else plannedAim
            val reentryAligned = steeringTarget != plannedAim
            val finalPoint = temporaryBirth || (!reentryAligned && unit.pathPoints.size == 1 && checkNotNull(local).isLast)
            val d2 = policies.coordinates.distanceSquared(unit.position, steeringTarget)
            val preciseSlide = sliding && finalPoint && unit.nearestMoveTarget != null && steeringTarget == target
            val closingAnchor = usesPreparedJoin && !canHandOff && local?.isLast == true &&
                unit.pathPoints.size > 1 && steeringTarget == unit.pathPoints.first()
            val propulsionDistance = when {
                reentryAligned -> maxOf(d2, 25.0)
                ((approachingBuilding || approachingWork || approachingLoad) && finalPoint) || preciseSlide -> maxOf(d2, 16.0)
                closingAnchor -> maxOf(d2, 9.0)
                else -> d2
            }
            val hardR = maxOf(1.0, unit.definition.movementCollision.boundingRadius)
            val isExactDockingGoal = unit.nearestMoveTarget != null ||
                unit.groupMoveReservation != null ||
                unit.landingMember?.claimed != null
            val closeExactSnap = terminalDecision?.flankAim == null && (
                (unit.nearestMoveTarget != null && finalPoint && d2 <= (hardR * 2.5).let { r -> r * r }) ||
                (isExactDockingGoal && unit.landingMember?.claimed != null && finalPoint && d2 <= (hardR * 2.2).let { r -> r * r }) ||
                (unit.groupMoveReservation != null && unit.landingMember == null && finalPoint && d2 <= (hardR * 2.2).let { r -> r * r })
            )
            val terminalDockingWeight = maxOf(
                if (finalPoint && isExactDockingGoal) {
                    ((3.2 * hardR - sqrt(d2)) / maxOf(1.0, 1.85 * hardR)).coerceIn(0.0, 1.0)
                } else 0.0,
                if (terminalDecision?.inClusterZone == true && terminalDecision.flankAim == null) 0.72 else 0.0,
            )
            val departingProducer = unit.productionOrigin?.let { origin ->
                val producer = host.units[origin.producerId]
                val reach = unit.definition.movementCollision.boundingRadius + (producer?.definition?.radius ?: 0.0) + maxOf(map.tileWidth, map.tileHeight) * 2.0
                host.buildingTerrain != null && producer != null &&
                    policies.coordinates.distanceSquared(unit.position, producer.position) <= reach * reach &&
                    FactoryExit.departingSeaFactoryCells(
                        map, unit, producer, target,
                        unit.definition.movementCollision, requireExitOrder = false,
                    ).isNotEmpty()
            } == true
            val useLocomotion2d = host.trafficSeparationEnabled && !temporaryBirth && !departingProducer && !approachingLoad &&
                movement.type != MovementType.AIR && !preciseSlide && !closeExactSnap &&
                (taskKind == null || taskKind == UnitTaskKind.MOVE || taskKind == UnitTaskKind.PATROL ||
                    taskKind == UnitTaskKind.ATTACK || taskKind == UnitTaskKind.BUILD ||
                    taskKind == UnitTaskKind.CONSTRUCT || taskKind == UnitTaskKind.RECLAIM)
            val routeWaypoint = unit.pathPoints.firstOrNull() ?: target
            val terrainForAvoid = host.planningTerrainFor(unit, map)
            val directWaypointClear = host.continuationFits(
                terrainForAvoid,
                Transport.queryPosition(unit, map),
                routeWaypoint,
                unit.heading,
                HullRoute.forUnit(unit, map),
                host.routeRadius(unit),
            )
            val baseAvoidTarget = if (directWaypointClear) routeWaypoint else steeringTarget
            val guidanceSample = if (host.trafficSeparationEnabled && movement.type != MovementType.AIR &&
                unit.landingMember != null &&
                (unit.landingMember?.claimed == null || unit.stalledRouteTicks >= 12L || unit.localProgressTime > 36.0 || unit.terrainCorrectionTime >= 18.0 || unit.nearTargetTime >= 120.0 || (unit.nav.progressAnchorStep >= 0L && (executionStep - unit.nav.progressAnchorStep) >= 75L))
            ) {
                host.flowFields.sampleGuidance(host, unit, terrainForAvoid, baseAvoidTarget, finalPoint, executionStep)
            } else null
            val guidedTarget = when {
                terminalDecision?.flankAim != null -> terminalDecision.flankAim
                useLocomotion2d && unit.landingMember?.claimed == null &&
                    guidanceSample != null && guidanceSample.fromFlowField -> guidanceSample.aimPosition
                else -> baseAvoidTarget
            }
            val avoidanceTarget = if (host.trafficSeparationEnabled && terminalDecision?.flankAim == null) {
                LocomotionModule.alignNarrowMouthReentryAim(unit, guidedTarget)
            } else guidedTarget
            // Compute pure distance-based speed ceiling (heading diff = 0.0).
            // Only apply final-stop braking when genuinely approaching a final stop (not sliding along a flank).
            val applyFinalStopCurve = terminalDecision?.flankAim == null && (closingAnchor || (finalPoint &&
                (unit.landingMember == null || unit.landingMember?.claimed != null || terminalDecision?.inClusterZone == true)))
            val distanceOnlyFraction = if (terminalDecision?.flankAim != null) {
                0.85
            } else {
                policies.propulsion.targetFraction(
                    movement, unit.speed, propulsionDistance,
                    0.0, applyFinalStopCurve, contract.targetRadius,
                )
            }
            val explicitAttackTargetId = unit.attackCommand?.targetUnitId
            val ignoredTargetId = explicitAttackTargetId ?: unit.primaryCombatTargetId
            val visibleCombatTarget = explicitAttackTargetId?.let { host.units[it] }?.takeIf {
                it.alive && host.isVisibleTo(unit.ownerPlayerId, it.position, it.nativeTransportPlacement)
            }
            val combatArcRadius = if (visibleCombatTarget != null) UnitCombat.profile(unit)?.attackRange ?: 0.0 else 0.0
            val locSolve = if (useLocomotion2d) {
                LocomotionModule.solveGroundLocomotion(
                    host = host,
                    unit = unit,
                    terrain = terrainForAvoid,
                    movement = movement,
                    desiredTarget = avoidanceTarget,
                    baseSpeedTarget = maximumSpeed * distanceOnlyFraction,
                    maximumSpeed = maximumSpeed,
                    finalPoint = applyFinalStopCurve,
                    delta = delta,
                    coordinates = policies.coordinates,
                    terminalDockingWeight = terminalDockingWeight,
                    ignoredContactUnitId = ignoredTargetId,
                    engagementArcCenter = visibleCombatTarget?.position,
                    engagementArcRadius = combatArcRadius,
                    protectedClusterKey = unit.landingMember?.group,
                )
            } else null
            val useFlowAimFallback = guidanceSample != null && guidanceSample.fromFlowField && unit.landingMember?.claimed == null
            val effectiveAim = if (locSolve != null && locSolve.applied) {
                val probeOffset = policies.coordinates.displacement(locSolve.targetHeadingDeg, maxOf(32.0, sqrt(d2)))
                Position(unit.position.x + probeOffset.x, unit.position.y + probeOffset.y)
            } else if (terminalDecision?.flankAim != null) {
                terminalDecision.flankAim
            } else if (useFlowAimFallback) {
                avoidanceTarget
            } else {
                steeringTarget
            }
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
                val effectiveThrottle = if (finalPoint && movement.maxTurnSpeed < 1.4 && distanceOnlyFraction > 1e-6) {
                    val nativeTurnFrac = policies.propulsion.targetFraction(
                        movement, unit.speed, propulsionDistance,
                        if (movement.ignoringBody) 0.0 else postTurnErr, applyFinalStopCurve, contract.targetRadius,
                    )
                    minOf(cosThrottle, nativeTurnFrac / distanceOnlyFraction)
                } else cosThrottle
                val rawMag = minOf(maximumSpeed, locSolve.solvedVelocity.length()) * effectiveThrottle
                val signedSpeed = if (unit.reversing) -rawMag * movement.reverseSpeedPercentage else rawMag
                speedTarget = if (applyFinalStopCurve && d2 < 9.0) 0.0 else signedSpeed
                fraction = if (maximumSpeed > 1e-7) abs(speedTarget) / maximumSpeed else 0.0
                requestedFraction = if (maximumSpeed > 1e-7) speedTarget / maximumSpeed else 0.0
                aimForSample = effectiveAim
                finalForSample = finalPoint
                differenceForSample = diff
                step.state.separationInterventions++
            } else {
                if (unit.nav.lastAvoidHeadingDeg != null) unit.nav = unit.nav.copy(lastAvoidHeadingDeg = null)
                val fallbackDistSq = if (terminalDecision?.flankAim != null || useFlowAimFallback) {
                    maxOf(propulsionDistance, 64.0)
                } else propulsionDistance
                val fallbackFinal = when {
                    terminalDecision?.flankAim != null -> false
                    useFlowAimFallback -> applyFinalStopCurve
                    else -> finalPoint
                }
                fraction = policies.propulsion.targetFraction(
                    movement, unit.speed, fallbackDistSq,
                    if (movement.ignoringBody) 0.0 else diff, fallbackFinal, contract.targetRadius,
                )
                aimForSample = effectiveAim
                finalForSample = fallbackFinal
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
                            if (occupancy == null && !approachingWork) null else HullRoute.forUnit(unit, map),
                            host.routeRadius(unit), occupancy,
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
            airSeparationVel = airSepVec,
        )
    }

    /**
     * Atomically settles a cul-de-sac / shoreline saturation-overflow unit: cancels any pending
     * async route/repair planning job, marks the landing member parked, updates landing occupancy,
     * and emits arrival events cleanly.
     */
    private fun settleSaturationOverflow(
        host: UnitMovementHost,
        unit: MutableUnit,
        step: CandidateStep,
        executionStep: Long,
    ): SteeringIntent {
        step.state.movePlanning.cancel(unit.unitId)
        val originalTarget = unit.nearestMoveTarget ?: unit.landingMember?.group?.click
        val parked = unit.landingMember?.copy(parked = true, stoppedOrder = unit.tasks.firstOrNull())
        val arrival = originalTarget?.let {
            MoveArrival(
                it, unit.position,
                MoveArrival.distanceToHull(unit.definition.movementCollision, unit.position, unit.heading, it),
                executionStep, true,
            )
        }
        unit.cancelRoute()
        unit.nav = unit.nav.markArrived(parked, null, arrival)
        step.state.landingOccupancy.update(unit)
        if (unit.tasks.firstOrNull()?.kind == UnitTaskKind.MOVE) unit.taskQueue = unit.taskQueue.pop()
        host.pendingEvents += Event("movement-arrived", unit.unitId)
        unit.speed = 0.0
        unit.angularVelocity = 0.0
        unit.flightVelocity = Position(0.0, 0.0)
        unit.movementState = MovementState.IDLE
        if (arrival != null) host.pendingEvents += Event("movement-nearest-reached", unit.unitId)
        return SteeringIntent(null, 0.0, 0.0, null, null, false, 0.0, normalCompleted = true)
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
        val shape = if (unit.birthRemaining > 0f) null else UnitLocomotion.terrainHull(unit, map)
        val hull = shape as? MovementCollisionShape.Rectangle
        val circle = (shape as? MovementCollisionShape.Circle)?.let {
            CircularTerrain(it.radius, maxOf(it.radius, Transport.boundaryRadius(unit)))
        }
        val rawCollisionMap = if (unit.birthRemaining > 0f) map else host.buildingTerrain?.collisionMap ?: map
        val curCol = MapBounds.cell(unit.position.x, map.tileWidth, map.widthTiles)
        val curRow = MapBounds.cell(unit.position.y, map.tileHeight, map.heightTiles)
        val escapingBuildingCell = unit.birthRemaining == 0f && host.buildingTerrain != null &&
            curCol in 0 until map.widthTiles && curRow in 0 until map.heightTiles &&
            rawCollisionMap.blockingFor(movement.type).isBlocked(curCol, curRow) &&
            !map.blockingFor(movement.type).isBlocked(curCol, curRow)
        val collisionMap = if (escapingBuildingCell) map else rawCollisionMap
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
        val sailClearOfBank = hull != null && maneuverLeg != null &&
            terrainHeading != requestedHeading && legalHeading == terrainHeading &&
            (nearShoreLeg || (unit.terrainCorrectionTime > 0.0 && abs(steering.differenceForSample) > 25.0))
        var speedTarget = steering.speedTarget
        var requestedFraction = steering.requestedFraction
        if (forwardTurnEscape || sailClearOfBank) {
            speedTarget = maximumSpeed
            requestedFraction = if (maximumSpeed > 0.0) 1.0 else 0.0
        }
        if (legalHeading != unit.heading) {
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
        val displacement = if (sliding) {
            val desiredVelocity = aimForSample?.let {
                policies.coordinates.displacement(policies.coordinates.desired(unit.position, it), abs(speedTarget))
            } ?: Position(0.0, 0.0)
            unit.flightVelocity = FlightMotion.velocity(movement, unit.flightVelocity, desiredVelocity, requestedFraction, delta, maximumSpeed)
            unit.speed = hypot(unit.flightVelocity.x, unit.flightVelocity.y)
            // Apply lightweight Boids soft-separation drift directly to airborne displacement (like non-sliding
            // aircraft) so low-acceleration helicopters (moveAccelerationSpeed=0.03) still spread apart smoothly
            // at shared rally points without overshooting or piling into overlapping clusters.
            val airSep = if (air) steering.airSeparationVel else null
            if (airSep != null) {
                val sepGain = if (steering.target == null) 0.75 else 0.55
                val dx = unit.flightVelocity.x * delta + airSep.x * delta * sepGain
                val dy = unit.flightVelocity.y * delta + airSep.y * delta * sepGain
                val maxStep = maximumSpeed * delta
                val len = hypot(dx, dy)
                if (len > maxStep && len > 1e-9) Position(dx * (maxStep / len), dy * (maxStep / len))
                else Position(dx, dy)
            } else {
                Position(unit.flightVelocity.x * delta, unit.flightVelocity.y * delta)
            }
        } else {
            val rate = if (unit.speed < speedTarget) movement.moveAccelerationSpeed else movement.moveDecelerationSpeed
            unit.speed = host.approachSpeed(unit.speed, speedTarget, rate * maximumSpeed * delta)
            val baseDisp = if (unit.definition.attackMovement == AttackMovementProfile.BOMBER) {
                unit.flightVelocity = policies.coordinates.displacement(unit.heading, unit.speed)
                Position(unit.flightVelocity.x * delta, unit.flightVelocity.y * delta)
            } else policies.coordinates.displacement(unit.heading, unit.speed * delta)
            val airSep = if (air) steering.airSeparationVel else null
            if (airSep != null && !sliding) {
                val dx = baseDisp.x + airSep.x * delta * 0.65
                val dy = baseDisp.y + airSep.y * delta * 0.65
                val maxStep = maximumSpeed * delta
                val len = hypot(dx, dy)
                if (len > maxStep && len > 1e-9) Position(dx * (maxStep / len), dy * (maxStep / len))
                else Position(dx, dy)
            } else baseDisp
        }

        val finishNearestWaterLeg = maneuverLeg != null && finalForSample && movement.type == MovementType.WATER &&
            unit.nearestMoveTarget != null && unit.landingMember == null &&
            unit.definition.movementCollision is MovementCollisionShape.Circle &&
            ScreenPlane.distanceSquared(unit.position, maneuverLeg.target) <= (maximumSpeed * delta) * (maximumSpeed * delta)
        val candidate = if (maneuverLeg != null && (finishNearestWaterLeg ||
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
        if (hull == null && !steering.avoidanceApplied &&
            movement.type != MovementType.WATER && !Transport.containsPosition(unit, map, candidate)
        ) {
            host.blockAtMapBoundary(unit)
            recordMotion(null, MotionTerrainDisposition.CANDIDATE_OUTSIDE_MAP, heading.legalHeading != heading.boundedHeading)
            return
        }
        val buildingTerrain = host.buildingTerrain
        val producerCells = if (shape != null && unit.birthRemaining == 0f && buildingTerrain != null)
            FactoryExit.departingOverlappedBuildingCells(map, buildingTerrain, host.units, unit, candidate, shape)
        else emptySet()
        val translationTerrain = if (producerCells.isEmpty()) hullTerrain
        else HullTerrainGrid.leavingProducer(heading.collisionMap, map, movement.type, producerCells)
        val terrainResolved = when {
            rectangle != null -> rectangle.resolve(checkNotNull(translationTerrain), unit.position, candidate)
            circle != null -> if (producerCells.isEmpty()) {
                circle.resolveWithSlide(checkNotNull(translationTerrain), unit.position, candidate)
            } else {
                circle.resolve(checkNotNull(translationTerrain), unit.position, candidate)
            }
            air -> if (steering.airSeparationVel != null) {
                val br = Transport.boundaryRadius(unit)
                Position(
                    candidate.x.coerceIn(br, map.widthTiles.toDouble() * map.tileWidth - br),
                    candidate.y.coerceIn(br, map.heightTiles.toDouble() * map.tileHeight - br),
                )
            } else candidate
            unit.birthRemaining > 0f -> TerrainMotion.resolve(map, unit.position, candidate, movementType = movement.type)
            else -> TerrainMotion.resolve(
                host.buildingTerrain?.collisionMap ?: map, unit.position, candidate,
                map.takeIf { host.buildingTerrain != null }, movement.type, unit.nativeTransportPlacement,
            )
        }
        val legalTerrainEnd = if (hull != null && producerCells.isEmpty()) UnitContact.lastLegalPoint(unit.position, terrainResolved) {
            UnitContact.poseFits(hull, it, unit.heading, checkNotNull(translationTerrain))
        } else terrainResolved
        val boundedPosition = UnitAttachments.limitTranslation(host.units, unit, map, legalTerrainEnd)
        fun insideMap(position: Position): Boolean = when {
            rectangle != null -> rectangle.contains(checkNotNull(hullTerrain), position)
            circle != null -> circle.contains(checkNotNull(hullTerrain), position)
            else -> Transport.containsPosition(unit, map, position)
        }
        val headTaskKind = unit.tasks.firstOrNull()?.kind
        val ordinaryMove = headTaskKind == UnitTaskKind.MOVE
        val workApproach = headTaskKind == UnitTaskKind.BUILD ||
            headTaskKind == UnitTaskKind.CONSTRUCT ||
            headTaskKind == UnitTaskKind.RECLAIM
        val contactMap = host.buildingTerrain?.collisionMap ?: map
        val canPush: ((MutableUnit, MutableUnit) -> Boolean)? = if (unit.birthRemaining == 0f && insideMap(boundedPosition)) {
            { pusher, blocker ->
                host.canDisplaceInContact(blocker) && !(unit.landingMember != null &&
                    blocker.ownerPlayerId == unit.ownerPlayerId &&
                    blocker.landingMember?.parked == true &&
                    blocker.landingMember?.group === unit.landingMember?.group) &&
                    !Transport.isLoadingPairAtContact(pusher, blocker, host.units)
            }
        } else null
        val translation = step.translations.resolve(
            unit, boundedPosition, contactMap, host.units, canPush, map, heading.forwardTurnEscape,
        ) { blocker ->
            host.isVisibleTo(unit.ownerPlayerId, blocker.position, blocker.nativeTransportPlacement)
        }
        val resolved = translation.position
        if (unit.moveTarget != null && insideMap(resolved)) {
            val anchor = unit.nav.progressAnchorPos
            if (anchor == null || ScreenPlane.distanceSquared(resolved, anchor) >= 100.0) {
                unit.nav = unit.nav.copy(progressAnchorPos = resolved, progressAnchorStep = executionStep)
            }
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
        if (unit.moveTarget != null && (nativeDeparture || ordinaryMove || workApproach)) {
            val clipped = resolved != candidate
            val attemptedPoseChange = candidate != positionBefore || heading.requestedHeading != headingBefore
            val heldAtPriorPose = resolved == positionBefore && heading.legalHeading == headingBefore
            val nearStaticAvoidance = workApproach && steering.avoidanceApplied && unit.pathPoints.isNotEmpty() &&
                ScreenPlane.distanceSquared(resolved, positionBefore) < 0.0025 &&
                unit.nav.progressAnchorStep >= 0L && (executionStep - unit.nav.progressAnchorStep) >= 45L
            val avoidanceBrakedStall = (steering.avoidanceApplied && heldAtPriorPose && unit.pathPoints.isNotEmpty()) ||
                nearStaticAvoidance
            val stalled = if ((attemptedPoseChange && heldAtPriorPose && (terrainCorrected || unitContactLimited)) || avoidanceBrakedStall)
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
