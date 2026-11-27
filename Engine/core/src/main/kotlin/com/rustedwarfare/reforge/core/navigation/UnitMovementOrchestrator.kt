package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.sim.StrictCapture

import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.ScreenPlane
import com.rustedwarfare.reforge.core.sim.MapBounds
import com.rustedwarfare.reforge.core.sim.MutableUnit
import com.rustedwarfare.reforge.core.sim.MovementState
import com.rustedwarfare.reforge.core.sim.MovementContract
import com.rustedwarfare.reforge.core.sim.MoveArrival
import com.rustedwarfare.reforge.core.sim.Event
import com.rustedwarfare.reforge.core.sim.WorldHandle
import com.rustedwarfare.reforge.core.sim.AcceleratedSteering
import com.rustedwarfare.reforge.core.sim.ReferencePropulsion
import com.rustedwarfare.reforge.core.sim.BodyMotion
import com.rustedwarfare.reforge.core.sim.FlightMotion
import com.rustedwarfare.reforge.core.sim.MechMotion
import com.rustedwarfare.reforge.core.sim.PendingUnitAction

import com.rustedwarfare.reforge.core.content.AttackMovementProfile
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Narrow-seam host contract consumed by [UnitMovementOrchestrator].
 *
 * Isolates per-unit locomotion orchestration from the simulation host's combat, production,
 * construction, and knowledge-publication machinery while keeping deterministic step-state
 * ownership inside the simulation host.
 */
internal interface UnitMovementHost {
    val activeWorld: WorldHandle
    val simulationTime: Double
    val movementContract: MovementContract
    val trafficSeparationEnabled: Boolean
    val units: Map<String, MutableUnit>
    val spatialIndex: WorldSpatialIndex
    val landingOccupancy: LandingOccupancy
    val navigationGuidance: NavigationGuidance
    val buildingTerrain: DynamicBuildingGrid?
    val pendingEvents: MutableList<Event>
    val steeringFaultHook: ((MutableUnit) -> Unit)? get() = null

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
    fun landingSitesFor(group: GroupMoveRequest, map: MapDefinition): SharedLandingSites?
    fun preparesRouteSuccessor(unit: MutableUnit): Boolean
    fun onMotionArrivalCompleted(unit: MutableUnit, completedMoveId: String?)
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
    fun isUnitReadyForMotion(unit: MutableUnit): Boolean
    fun isMovementActionLocked(unit: MutableUnit): Boolean
    fun isNativeSeaDeparture(unit: MutableUnit): Boolean
    fun combatWeaponRange(unit: MutableUnit): Double?
    fun recordMotionGate(unit: MutableUnit, active: Boolean)
    fun isLoadingPairAtContact(pusher: MutableUnit, blocker: MutableUnit): Boolean
    fun limitAttachmentTurn(unit: MutableUnit, map: MapDefinition, headingBefore: Double, terrainHeading: Double): Double
    fun limitAttachmentTranslation(unit: MutableUnit, map: MapDefinition, candidate: Position): Position
    fun synchronizeAttachmentChildren(unit: MutableUnit)
    fun recordSeparationIntervention()
}

/**
 * Per-unit movement orchestrator extracted from the simulation loop (ENG-P016-003).
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
        val cohortEscortYielding: Boolean = false,
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
        movePlanning: MovePlanning,
        pendingFlowStart: Set<String>,
        translations: UnitContact.Translations,
        motionSamples: MutableList<MotionStepSample>?,
        routeContinuationSamples: MutableList<RouteContinuationSample>?,
        passageWaiting: Boolean,
        departureWaiting: Boolean,
    ) {
        if (!host.isUnitReadyForMotion(unit)) return
        val movement = UnitLocomotion.movement(unit)
        val narrowMouthStagingAim = if (host.trafficSeparationEnabled && passageWaiting &&
            unit.passageEntry?.commandId == unit.originMoveId &&
            unit.passageEntry?.entered == false &&
            unit.moveTarget != null &&
            movement.type != MovementType.AIR && movement.type != MovementType.NONE
        ) {
            LocomotionModule.computeNarrowMouthStagingAim(host, unit, host.planningTerrainFor(unit, map))
        } else null
        if (gateMovementLockOrWait(host, unit, movement, delta, executionStep, movePlanning, translations, motionSamples, passageWaiting && narrowMouthStagingAim == null, departureWaiting)) {
            if (unit.nav.cohortEscortYielding) unit.updateNavigation { it.withCohortEscortYielding(false) }
            return
        }

        val maximumSpeed = translations.maximumSpeed(unit, movement)
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
            host, unit, map, movement, maximumSpeed, air, sliding, delta, executionStep, movePlanning, pendingFlowStart, routeContinuationSamples, narrowMouthStagingAim,
        )
        if (steering.abortedForContinuation) {
            if (unit.nav.cohortEscortYielding) unit.updateNavigation { it.withCohortEscortYielding(false) }
            return
        }

        val heading = resolveHeadingAndTurn(
            host, unit, map, movement, maximumSpeed, delta, headingBefore, steering,
        )

        integrateAndResolveContact(
            host, unit, map, movement, maximumSpeed, air, sliding, delta, executionStep, translations, motionSamples,
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
        movePlanning: MovePlanning,
        translations: UnitContact.Translations,
        motionSamples: MutableList<MotionStepSample>?,
        passageWaiting: Boolean,
        departureWaiting: Boolean,
    ): Boolean {
        if (unit.spiderBlink != null || host.isMovementActionLocked(unit) ||
            movement.type == MovementType.NONE
        ) {
            unit.speed = 0.0
            unit.flightVelocity = Position(0.0, 0.0)
            unit.movementState = MovementState.IDLE
            return true
        }
        val nativeDeparture = host.isNativeSeaDeparture(unit)
        val passageTarget = unit.moveTarget
        if (passageTarget != null && unit.followsManeuverRoute && movePlanning.holdsManeuverRoute(unit.unitId)) {
            motionSamples?.add(
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
            unit.nav = unit.nav.copy(stalledRouteTicks = 0L, nearTargetTime = 0.0, localProgressTime = 0.0)
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
        movePlanning: MovePlanning,
        pendingFlowStart: Set<String>,
        routeContinuationSamples: MutableList<RouteContinuationSample>?,
        narrowMouthStagingAim: Position?,
    ): SteeringIntent {
        val contract = host.movementContract
        val faultHook = host.steeringFaultHook
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
            (movePlanning.pending(unit.unitId) || unit.unitId in pendingFlowStart)
        ) {
            unit.optimisticSteerTarget
        } else null
        var maneuverLeg: ManeuverLeg? = null
        var avoidanceApplied = false

        val motionContract = unit.motionContract
        val goalGeometry = motionContract?.goal
        val rangeGoal = goalGeometry as? MotionGoalGeometry.RangeBand
        val pointGoal = goalGeometry as? MotionGoalGeometry.Point
        val attackTarget = unit.attackCommand?.targetUnitId?.let { host.units[it] }
        val approachingBuilding = attackTarget?.alive == true && attackTarget.definition.building != null &&
            host.combatWeaponRange(unit)?.let { range ->
                ScreenPlane.distanceSquared(unit.position, attackTarget.position) >= range * range
            } == true
        val approachingRangeBand = rangeGoal != null &&
            ScreenPlane.distanceSquared(unit.position, rangeGoal.anchor) > rangeGoal.range * rangeGoal.range
        val reportsStop = unit.nearestMoveTarget != null || unit.landingMember?.claimed != null
        val followsManeuver = (unit.followsManeuverRoute ||
            unit.landingMember?.claimed != null && unit.pathPoints.size == 1) &&
            motionContract?.allowsManeuverFollow == true && !air && !sliding

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
                StrictCapture.inside(ScreenPlane.distanceSquared(unit.position, first), contract.pathPointRadius)
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

        val approachingBoarding = motionContract?.isBoardingApproach == true
        val allowsArrivalCapture = !approachingBoarding && (goalGeometry == null || goalGeometry.completesOnCapture || rangeGoal != null)
        if (target != null && allowsArrivalCapture) {
            val commandDistanceSquared = ScreenPlane.distanceSquared(unit.position, target)
            if (commandDistanceSquared < 1681.0) {
                unit.updateNavigation { it.withNearTargetTime(it.nearTargetTime + delta) }
            }
            val completionRadius = when {
                !air && unit.landingMember != null -> 0.000001
                unit.nearestMoveTarget != null -> 0.000001
                followsManeuver -> 7.0
                unit.nearTargetTime > 340.0 -> 36.0
                unit.nearTargetTime > 240.0 -> 16.0
                else -> 7.0
            }
            val airRallyCaptured = air && host.trafficSeparationEnabled && pointGoal?.allowAirRallyRing == true &&
                unit.birthRemaining == 0f && LocomotionModule.isAirRallyRingArrived(host, unit, target)
            val captured = (air || unit.landingMember == null || unit.landingMember?.claimed != null) &&
                (StrictCapture.inside(commandDistanceSquared, completionRadius) || airRallyCaptured)
            if (!approachingBuilding && !approachingRangeBand && !unit.routeTranslationClipped &&
                (!followsManeuver || unit.pathPoints.size == 1) &&
                captured
            ) {
                val completedMoveId = unit.originMoveId
                val originalTarget = unit.nearestMoveTarget ?: unit.landingMember?.group?.click
                val wasGroup = unit.nearestMoveIsGroup || unit.landingMember != null
                val parked = unit.landingMember?.copy(parked = true)
                val arrival = originalTarget?.let {
                    MoveArrival(
                        it, unit.position,
                        MoveArrival.distanceToHull(unit.definition.movementCollision, unit.position, unit.heading, it),
                        executionStep, wasGroup,
                    )
                }
                unit.cancelRoute()
                unit.nav = unit.nav.markArrived(parked, arrival)
                host.onMotionArrivalCompleted(unit, completedMoveId)
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

        // ENG-P016-009B / ENG-P017-007 / ENG-P017-008 / ENG-P017-009:
        // Cohort convergence coordination + unified single terminal landing evaluation.
        // Skip terminal landing flank steering while a departing hull still physically overlaps its producer footprint.
        val stillInsideProducerDock = unit.productionOrigin?.let { origin ->
            BuildingOverlapEscape.overlapsProducerFootprint(map, host.buildingTerrain, unit, host.units[origin.producerId])
        } == true
        var convergenceAdvice = CohortCoordinator.ConvergenceAdvice.NONE
        val terminalDecision = if (target != null && !air && !stillInsideProducerDock &&
            (motionContract == null || motionContract.isSharedCluster) &&
            unit.birthRemaining == 0f && unit.landingMember != null && !unit.landingMember!!.parked
        ) {
            val tTerrain = host.planningTerrainFor(unit, map)
            val tHardR = maxOf(1.0, unit.definition.movementCollision.boundingRadius)
            val gdx = target.x - unit.position.x
            val gdy = target.y - unit.position.y
            val gd = hypot(gdx, gdy)
            val tPool = host.landingSitesFor(unit.landingMember!!.group, map)
            val tInPocket = tPool?.pocketTopology(tTerrain)?.hasPocket == true
            val tHasLos: Boolean? = if (!tInPocket && gd <= 220.0 && unit.landingMember!!.claimed == null &&
                unit.landingMember!!.group.noParkingCorridor == null
            ) {
                val tTerrainShape = UnitLocomotion.terrainHull(unit, map)
                val tTerrainR = UnitLocomotion.terrainPassabilityRadius(unit, map.tileWidth, map.tileHeight)
                val tHullGrid = HullTerrainGrid.authorized(tTerrain)
                PlanningSegmentClearance.allowsHull(
                    terrain = tTerrain,
                    hullGrid = tHullGrid,
                    terrainHull = tTerrainShape,
                    from = unit.position,
                    to = target,
                    heading = unit.heading,
                    boundaryRadius = tTerrainR,
                    terrainRadius = tTerrainR,
                )
            } else null
            if (tHasLos == true) {
                convergenceAdvice = CohortCoordinator.evaluateConvergence(
                    host = host,
                    unit = unit,
                    goal = target,
                    hardR = tHardR,
                    maxSpeed = maximumSpeed,
                    hasTerrainLosToGoal = true,
                    inConfinedPocket = false,
                )
            }
            val flowDir = host.navigationGuidance.sampleFlowDirection(host, unit, tTerrain, target)
            val dirX = flowDir?.first ?: if (gd > 1e-5) gdx / gd else ScreenPlane.displacement(unit.heading, 1.0).x
            val dirY = flowDir?.second ?: if (gd > 1e-5) gdy / gd else ScreenPlane.displacement(unit.heading, 1.0).y
            val td = TerminalLanding.evaluate(
                host, unit, map, tTerrain, target, dirX, dirY, maximumSpeed, delta, convergenceAdvice,
                precomputedHasTerrainLos = tHasLos,
            )
            if (td != null) {
                if (td.updatedMember != unit.landingMember) {
                    unit.updateNavigation { it.withLandingMember(td.updatedMember) }
                }
                if (td.settleNow) {
                    return settleSaturationOverflow(host, unit, movePlanning, executionStep)
                }
            }
            td
        } else null

        var escortPacing = CohortCoordinator.EscortPacingAdvice.NONE

        var airSepVec: LocomotionModule.Vec2? = null
        if (narrowMouthStagingAim != null && target != null) {
            unit.requestMovement(host.logicalRouteTarget(unit, target))
            unit.nav = unit.nav.copy(stalledRouteTicks = 0L, nearTargetTime = 0.0, localProgressTime = 0.0)
            val terrainForStage = host.planningTerrainFor(unit, map)
            val stageDist = sqrt(ScreenPlane.distanceSquared(unit.position, narrowMouthStagingAim))
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
                terminalDockingWeight = 0.35,
                ignoredContactUnitId = unit.attackCommand?.targetUnitId ?: unit.primaryCombatTargetId,
            )
            val effectiveAim = if (stageSolve.applied) {
                avoidanceApplied = true
                unit.nav = unit.nav.copy(lastAvoidHeadingDeg = stageSolve.nextAvoidHeadingDeg)
                val probe = ScreenPlane.displacement(stageSolve.targetHeadingDeg, maxOf(24.0, stageDist))
                Position(unit.position.x + probe.x, unit.position.y + probe.y)
            } else narrowMouthStagingAim
            val diff = BodyMotion.turnOrdinary(
                unit, effectiveAim, delta, allowReverse = false, alignHeading = false, faultHook = faultHook,
            )
            val desiredHeading = ScreenPlane.desired(unit.position, effectiveAim)
            val postErr = abs(ScreenPlane.difference(desiredHeading, unit.heading))
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

        if (air && host.trafficSeparationEnabled && !approachingBoarding) {
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
            val d2 = ScreenPlane.distanceSquared(unit.position, target)
            val ordinaryBody = unit.definition.attackMovement == AttackMovementProfile.BOMBER ||
                unit.amphibiousState != null ||
                UnitLocomotion.shortReverseDistance(unit.definition) != null
            val difference = if (ordinaryBody)
                BodyMotion.turnOrdinary(unit, target, delta, faultHook = faultHook)
            else AcceleratedSteering.turn(unit, target, delta, faultHook)
            val fraction = ReferencePropulsion.targetFraction(
                movement, unit.speed, d2,
                if (ordinaryBody) difference else 0.0, true, contract.targetRadius,
            )
            requestedFraction = if (ordinaryBody && unit.reversing) -fraction * movement.reverseSpeedPercentage else fraction
            speedTarget = maximumSpeed * requestedFraction
            aimForSample = target
            finalForSample = true
            differenceForSample = difference
            if (airSepVec != null && !ordinaryBody && sliding) {
                val baseDir = ScreenPlane.displacement(ScreenPlane.desired(unit.position, target), abs(speedTarget))
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
            if (motionContract?.traversalMode == TraversalMode.COHORT_ESCORT && unit.birthRemaining == 0f) {
                escortPacing = CohortCoordinator.evaluateEscortPacing(
                    host = host,
                    unit = unit,
                    terrain = host.planningTerrainFor(unit, map),
                    goal = target,
                    maxSpeed = maximumSpeed,
                    remainingDistance = sqrt(d2),
                    isAir = true,
                )
                if (escortPacing.active && abs(speedTarget) > escortPacing.speedCap) {
                    speedTarget = sign(speedTarget) * escortPacing.speedCap
                    requestedFraction = if (maximumSpeed > 1e-7) speedTarget / maximumSpeed else 0.0
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
            val leg = ManeuverLeg(unit.position, alignedManeuverTarget)
            finalForSample = !reentryAlignedManeuver && unit.pathPoints.size == 1
            val hardRadius = maxOf(1.0, unit.definition.movementCollision.boundingRadius)
            val distSqToLeg = ScreenPlane.distanceSquared(unit.position, leg.target)
            val isExactManeuverGoal = unit.nearestMoveTarget != null ||
                unit.landingMember?.claimed != null
            val preciseCircleApproach = (unit.landingMember?.claimed != null && !finalForSample) ||
                (finalForSample && isExactManeuverGoal && distSqToLeg <= (hardRadius * 2.2).let { it * it }) ||
                (finalForSample && distSqToLeg <= (hardRadius * 1.4).let { it * it })
            val maneuverDockingWeight = maxOf(
                if (finalForSample && isExactManeuverGoal) {
                    ((3.2 * hardRadius - sqrt(distSqToLeg)) / maxOf(1.0, 1.85 * hardRadius)).coerceIn(0.0, 1.0)
                } else 0.0,
                if (terminalDecision?.inClusterZone == true && terminalDecision.flankAim == null) 0.72 else 0.0,
                if (terminalDecision?.flankAim == null) convergenceAdvice.dockingWeightFloor else 0.0,
            )
            val ignoredTargetId = unit.attackCommand?.targetUnitId ?: unit.primaryCombatTargetId
            val maneuverDesired = terminalDecision?.flankAim ?: leg.target
            if (motionContract.traversalMode == TraversalMode.COHORT_ESCORT && unit.birthRemaining == 0f) {
                escortPacing = CohortCoordinator.evaluateEscortPacing(
                    host = host,
                    unit = unit,
                    terrain = host.planningTerrainFor(unit, map),
                    goal = target,
                    maxSpeed = maximumSpeed,
                    remainingDistance = Double.NaN,
                    isAir = false,
                )
            }
            val maneuverBaseSpeed = minOf(
                if (terminalDecision?.flankAim != null) maximumSpeed * 0.85
                else leg.approachSpeed(maximumSpeed, movement.moveDecelerationSpeed * maximumSpeed, finalForSample),
                escortPacing.speedCap,
            ) * convergenceAdvice.speedScale
            val maneuverAvoid = if (host.trafficSeparationEnabled &&
                unit.definition.movementCollision is MovementCollisionShape.Circle && (!preciseCircleApproach || terminalDecision?.flankAim != null)
            ) LocomotionModule.solveGroundLocomotion(
                    host = host,
                    unit = unit,
                    terrain = host.planningTerrainFor(unit, map),
                    movement = movement,
                    desiredTarget = maneuverDesired,
                    baseSpeedTarget = maneuverBaseSpeed,
                    maximumSpeed = maximumSpeed,
                    finalPoint = finalForSample && terminalDecision?.flankAim == null,
                    delta = delta,
                    terminalDockingWeight = maneuverDockingWeight,
                    ignoredContactUnitId = ignoredTargetId,
                    protectedClusterKey = unit.landingMember?.group,
                ).takeIf { it.applied } else null
            if (maneuverAvoid != null) {
                avoidanceApplied = true
                unit.nav = unit.nav.copy(lastAvoidHeadingDeg = maneuverAvoid.nextAvoidHeadingDeg)
                val probeDist = maxOf(32.0, sqrt(ScreenPlane.distanceSquared(unit.position, leg.target)))
                val probeOff = ScreenPlane.displacement(maneuverAvoid.targetHeadingDeg, probeDist)
                val effectiveAim = Position(unit.position.x + probeOff.x, unit.position.y + probeOff.y)
                aimForSample = effectiveAim
                differenceForSample = BodyMotion.turnOrdinary(
                    unit, effectiveAim, delta, allowReverse = false, alignHeading = false, faultHook = faultHook,
                )
                val postErr = abs(ScreenPlane.difference(maneuverAvoid.targetHeadingDeg, unit.heading))
                val cosThrottle = when {
                    postErr <= 12.0 -> 1.0
                    postErr >= 95.0 -> 0.0
                    else -> maxOf(0.0, kotlin.math.cos(Math.toRadians((postErr - 12.0) * (90.0 / 83.0))))
                }
                speedTarget = minOf(maximumSpeed, maneuverAvoid.solvedVelocity.length()) * cosThrottle
                requestedFraction = if (maximumSpeed > 1e-7) speedTarget / maximumSpeed else 0.0
                host.recordSeparationIntervention()
            } else {
                if (unit.nav.lastAvoidHeadingDeg != null) unit.nav = unit.nav.copy(lastAvoidHeadingDeg = null)
                maneuverLeg = leg
                val fallbackAim = terminalDecision?.flankAim ?: leg.target
                aimForSample = fallbackAim
                val wasAligned = leg.aligned(unit.heading)
                differenceForSample = BodyMotion.turnOrdinary(
                    unit, fallbackAim, delta, allowReverse = false,
                    alignHeading = terminalDecision?.flankAim == null, faultHook = faultHook,
                )
                if (unit.definition.movementCollision is MovementCollisionShape.Circle) {
                    val distanceSquared = ScreenPlane.distanceSquared(unit.position, leg.target)
                    val difference = ScreenPlane.difference(ScreenPlane.desired(unit.position, leg.target), unit.heading)
                    val maneuverStopRadius = if (reportsStop) 0.0 else contract.targetRadius
                    requestedFraction = ReferencePropulsion.targetFraction(
                        movement, unit.speed, distanceSquared,
                        difference, finalForSample, maneuverStopRadius,
                    )
                    speedTarget = minOf(
                        leg.approachSpeed(maximumSpeed, movement.moveDecelerationSpeed * maximumSpeed, finalForSample) * requestedFraction * convergenceAdvice.speedScale,
                        escortPacing.speedCap,
                    )
                    if (maximumSpeed > 0.0) requestedFraction = speedTarget / maximumSpeed
                } else if (wasAligned) {
                    val rectYield = if (unit.definition.movementCollision is MovementCollisionShape.Rectangle &&
                        unit.landingMember != null
                    ) {
                        rectangularPeerYieldFactor(host, unit, target)
                    } else 1.0
                    speedTarget = minOf(
                        leg.approachSpeed(maximumSpeed, movement.moveDecelerationSpeed * maximumSpeed, finalForSample) * rectYield,
                        escortPacing.speedCap,
                    )
                    if (maximumSpeed > 0.0) requestedFraction = speedTarget / maximumSpeed
                }
            }
        } else if (target != null && !air && (unit.birthRemaining > 0f || unit.pathPoints.isNotEmpty())) {
            unit.requestMovement(host.logicalRouteTarget(unit, target))
            val temporaryBirth = unit.birthRemaining > 0f
            val terrainForAvoid = host.planningTerrainFor(unit, map)
            val turnDistance = if (unit.routeContinuation?.ready == true && unit.landingMember?.claimed == null &&
                unit.landingMember != null && unit.definition.movementCollision is MovementCollisionShape.Circle
            ) maxOf(contract.pathPointRadius, minOf(map.tileWidth, map.tileHeight) * 2.0) else contract.pathPointRadius
            val nearHandoff = !temporaryBirth && unit.pathPoints.size > 1 && StrictCapture.inside(
                ScreenPlane.distanceSquared(unit.position, unit.pathPoints.first()), turnDistance,
            )
            val usesPreparedJoin = nearHandoff && host.preparesRouteSuccessor(unit)
            val canHandOff = nearHandoff &&
                host.continuationFits(
                    terrainForAvoid, UnitLocomotion.queryPosition(unit, map),
                    unit.pathPoints[1], unit.heading,
                    if (usesPreparedJoin || approachingRangeBand) HullRoute.forUnit(unit, map) else null,
                    host.routeRadius(unit), host.routeOccupancy(unit),
                )
            if (canHandOff && usesPreparedJoin) {
                val routeSamples = routeContinuationSamples
                val continuation = host.requestRouteContinuation(unit, executionStep, 0.0, routeSamples)
                if (!continuation.ready) {
                    if (!continuation.waiting) {
                        unit.updateNavigation { it.withRouteContinuation(continuation.copy(waiting = true)) }
                        host.recordRouteContinuation(routeSamples, executionStep, unit, continuation, 0.0, RouteContinuationStage.WAITING)
                    }
                    unit.speed = 0.0
                    unit.flightVelocity = Position(0.0, 0.0)
                    unit.updateNavigation { it.withStalledRouteTicks(0L) }
                    unit.movementState = MovementState.MOVING
                    return SteeringIntent(target, 0.0, 0.0, null, null, false, 0.0, normalCompleted, abortedForContinuation = true)
                }
                host.recordRouteContinuation(routeSamples, executionStep, unit, continuation, 0.0, RouteContinuationStage.HANDED_OFF)
            }
            val routeWaypoint = unit.pathPoints.firstOrNull() ?: target
            val guidanceSample = if (temporaryBirth) {
                null
            } else {
                host.navigationGuidance.sampleGuidance(
                    host, unit, terrainForAvoid, routeWaypoint, unit.pathPoints.size <= 1, executionStep,
                )
            }
            if (guidanceSample != null && guidanceSample.waypointIndex > 0 && !usesPreparedJoin) {
                var nextNav = unit.nav
                repeat(guidanceSample.waypointIndex) {
                    nextNav = nextNav.popFrontWaypoint(clearLocalSegment = true)
                }
                unit.nav = nextNav
            }
            val plannedAim = guidanceSample?.aimPosition ?: target
            val steeringTarget = if (host.trafficSeparationEnabled) {
                LocomotionModule.alignNarrowMouthReentryAim(unit, plannedAim)
            } else plannedAim
            val reentryAligned = steeringTarget != plannedAim
            val finalPoint = temporaryBirth || (!reentryAligned && (guidanceSample?.finalApproach ?: (unit.pathPoints.size <= 1)))
            val d2 = ScreenPlane.distanceSquared(unit.position, steeringTarget)
            val preciseSlide = sliding && finalPoint && unit.nearestMoveTarget != null && steeringTarget == target
            val closingAnchor = usesPreparedJoin && !canHandOff &&
                unit.pathPoints.size > 1 && steeringTarget == unit.pathPoints.first()
            val zeroStopGate = ((approachingBuilding || approachingRangeBand || approachingBoarding || rangeGoal?.zeroStopOnFinalLeg == true) && finalPoint) ||
                preciseSlide || closingAnchor || unit.landingMember != null
            val effectiveStopRadius = if (zeroStopGate) 0.0 else contract.targetRadius
            val hardR = maxOf(1.0, unit.definition.movementCollision.boundingRadius)
            val isExactDockingGoal = unit.nearestMoveTarget != null ||
                unit.landingMember?.claimed != null
            val closeExactSnap = terminalDecision?.flankAim == null && (
                (unit.nearestMoveTarget != null && finalPoint && d2 <= (hardR * 2.5).let { r -> r * r }) ||
                (isExactDockingGoal && unit.landingMember?.claimed != null && finalPoint && d2 <= (hardR * 2.2).let { r -> r * r })
            )
            val terminalDockingWeight = maxOf(
                if (finalPoint && isExactDockingGoal) {
                    ((3.2 * hardR - sqrt(d2)) / maxOf(1.0, 1.85 * hardR)).coerceIn(0.0, 1.0)
                } else 0.0,
                if (terminalDecision?.inClusterZone == true && terminalDecision.flankAim == null) 0.72 else 0.0,
                if (terminalDecision?.flankAim == null) convergenceAdvice.dockingWeightFloor else 0.0,
            )
            val departingProducer = unit.productionOrigin?.let { origin ->
                val producer = host.units[origin.producerId]
                val reach = unit.definition.movementCollision.boundingRadius + (producer?.definition?.radius ?: 0.0) + maxOf(map.tileWidth, map.tileHeight) * 2.0
                host.buildingTerrain != null && producer != null &&
                    ScreenPlane.distanceSquared(unit.position, producer.position) <= reach * reach &&
                    BuildingOverlapEscape.departingSeaFactoryCells(
                        map, unit, producer, target,
                        unit.definition.movementCollision, requireExitOrder = false,
                    ).isNotEmpty()
            } == true
            val useLocomotion2d = host.trafficSeparationEnabled && !temporaryBirth && !departingProducer && !approachingBoarding &&
                movement.type != MovementType.AIR && !preciseSlide && !closeExactSnap &&
                (motionContract?.enableLocalAvoidance != false)
            val guidedTarget = terminalDecision?.flankAim ?: plannedAim
            val avoidanceTarget = if (host.trafficSeparationEnabled && terminalDecision?.flankAim == null) {
                LocomotionModule.alignNarrowMouthReentryAim(unit, guidedTarget)
            } else guidedTarget
            // Compute pure distance-based speed ceiling (heading diff = 0.0).
            // Only apply final-stop braking when genuinely approaching a final stop (not sliding along a flank).
            val applyFinalStopCurve = terminalDecision?.flankAim == null && (closingAnchor || (finalPoint &&
                (pointGoal?.brakesAtAnchor != false) &&
                (unit.landingMember == null || unit.landingMember?.claimed != null || terminalDecision?.inClusterZone == true)))
            val distanceOnlyFraction = if (terminalDecision?.flankAim != null) {
                0.85
            } else {
                ReferencePropulsion.targetFraction(
                    movement, unit.speed, d2,
                    0.0, applyFinalStopCurve, effectiveStopRadius,
                )
            }
            if (motionContract?.traversalMode == TraversalMode.COHORT_ESCORT && !temporaryBirth && !departingProducer) {
                escortPacing = CohortCoordinator.evaluateEscortPacing(
                    host = host,
                    unit = unit,
                    terrain = terrainForAvoid,
                    goal = target,
                    maxSpeed = maximumSpeed,
                    remainingDistance = guidanceSample?.remainingDistance ?: hypot(target.x - unit.position.x, target.y - unit.position.y),
                    isAir = false,
                )
            }
            val effectiveBaseSpeed = minOf(
                maximumSpeed * distanceOnlyFraction * convergenceAdvice.speedScale,
                escortPacing.speedCap,
            )
            val explicitAttackTargetId = unit.attackCommand?.targetUnitId
            val ignoredTargetId = explicitAttackTargetId ?: unit.primaryCombatTargetId
            val visibleCombatTarget = explicitAttackTargetId?.let { host.units[it] }?.takeIf {
                it.alive && host.isVisibleTo(unit.ownerPlayerId, it.position, it.nativeTransportPlacement)
            }
            val combatArcRadius = if (visibleCombatTarget != null) host.combatWeaponRange(unit) ?: 0.0 else 0.0
            val locSolve = if (useLocomotion2d) {
                LocomotionModule.solveGroundLocomotion(
                    host = host,
                    unit = unit,
                    terrain = terrainForAvoid,
                    movement = movement,
                    desiredTarget = avoidanceTarget,
                    baseSpeedTarget = effectiveBaseSpeed,
                    maximumSpeed = maximumSpeed,
                    finalPoint = applyFinalStopCurve,
                    delta = delta,
                    terminalDockingWeight = terminalDockingWeight,
                    ignoredContactUnitId = ignoredTargetId,
                    engagementArcCenter = visibleCombatTarget?.position,
                    engagementArcRadius = combatArcRadius,
                    protectedClusterKey = unit.landingMember?.group,
                )
            } else null
            val useFlowAimFallback = guidanceSample?.fromFlowField == true && unit.landingMember?.claimed == null
            val effectiveAim = if (locSolve != null && locSolve.applied) {
                val probeOffset = ScreenPlane.displacement(locSolve.targetHeadingDeg, maxOf(32.0, sqrt(d2)))
                Position(unit.position.x + probeOffset.x, unit.position.y + probeOffset.y)
            } else if (terminalDecision?.flankAim != null) {
                terminalDecision.flankAim
            } else if (useFlowAimFallback) {
                avoidanceTarget
            } else {
                steeringTarget
            }
            val diff = when {
                unit.mechState != null -> MechMotion.turn(unit, effectiveAim, delta, faultHook)
                movement.type == MovementType.WATER -> BodyMotion.turnOrdinary(unit, effectiveAim, delta, faultHook = faultHook)
                else -> AcceleratedSteering.turn(unit, effectiveAim, delta, faultHook)
            }
            val fraction: Double
            if (locSolve != null && locSolve.applied) {
                avoidanceApplied = true
                unit.nav = unit.nav.copy(lastAvoidHeadingDeg = locSolve.nextAvoidHeadingDeg)
                val effectiveHeading = if (unit.reversing) ScreenPlane.normalize(unit.heading + 180.0) else unit.heading
                val postTurnErr = abs(ScreenPlane.difference(locSolve.targetHeadingDeg, effectiveHeading))
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
                    val nativeTurnFrac = ReferencePropulsion.targetFraction(
                        movement, unit.speed, d2,
                        if (movement.ignoringBody) 0.0 else postTurnErr, applyFinalStopCurve, effectiveStopRadius,
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
                host.recordSeparationIntervention()
            } else {
                if (unit.nav.lastAvoidHeadingDeg != null) unit.nav = unit.nav.copy(lastAvoidHeadingDeg = null)
                val fallbackFinal = when {
                    terminalDecision?.flankAim != null -> false
                    useFlowAimFallback -> applyFinalStopCurve
                    else -> finalPoint
                }
                val fallbackDistSq = ScreenPlane.distanceSquared(unit.position, effectiveAim)
                val propulsionDistSq = minOf(fallbackDistSq, 16900.0)
                val forwardTurnBlocked = !sliding && !movement.ignoringBody && unit.speed > 0.0 && abs(diff) > 20.0 && run {
                    val forwardProbeDist = maxOf(unit.speed * delta * 1.5, host.routeRadius(unit) * 0.5)
                    val forwardOffset = ScreenPlane.displacement(unit.heading, forwardProbeDist)
                    val forwardProbe = Position(unit.position.x + forwardOffset.x, unit.position.y + forwardOffset.y)
                    val clearanceRadius = UnitLocomotion.terrainPassabilityRadius(unit, map.tileWidth, map.tileHeight)
                    !PlanningSegmentClearance.allows(
                        terrainForAvoid,
                        UnitLocomotion.queryPosition(unit, map),
                        forwardProbe,
                        host.routeRadius(unit),
                        clearanceRadius,
                    )
                }
                fraction = if (forwardTurnBlocked) 0.0 else ReferencePropulsion.targetFraction(
                    movement, unit.speed, propulsionDistSq,
                    if (movement.ignoringBody) 0.0 else diff, fallbackFinal, effectiveStopRadius,
                )
                aimForSample = effectiveAim
                finalForSample = fallbackFinal
                differenceForSample = diff
                val rectYield = if (unit.definition.movementCollision is MovementCollisionShape.Rectangle &&
                    unit.landingMember != null
                ) {
                    rectangularPeerYieldFactor(host, unit, target)
                } else 1.0
                requestedFraction = (if (unit.reversing) -fraction * movement.reverseSpeedPercentage else fraction) * rectYield * convergenceAdvice.speedScale
                val rawFallbackSpeed = maximumSpeed * requestedFraction
                speedTarget = if (escortPacing.active && abs(rawFallbackSpeed) > escortPacing.speedCap) {
                    sign(rawFallbackSpeed) * escortPacing.speedCap
                } else rawFallbackSpeed
                if (escortPacing.active && maximumSpeed > 1e-7) {
                    requestedFraction = speedTarget / maximumSpeed
                }
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
            } else if (fraction > 0.0) {
                unit.nav = unit.nav.copy(localProgressTime = unit.localProgressTime + delta)
            }
        }

        if (target == null && optimisticTarget != null) {
            differenceForSample = AcceleratedSteering.turn(unit, optimisticTarget, delta, faultHook)
            val cosFactor = kotlin.math.cos(Math.toRadians(kotlin.math.abs(differenceForSample).coerceAtMost(90.0)))
            // Preserve momentum if already moving AND the actual forward heading segment for this step is
            // terrain-clear; otherwise turn in place (speedTarget = 0) so we never coast into a wall while planning.
            val forwardProbeDist = maxOf(unit.speed * delta * 1.5, host.routeRadius(unit) * 0.5)
            val forwardOffset = ScreenPlane.displacement(unit.heading, forwardProbeDist)
            val forwardProbe = Position(
                unit.position.x + forwardOffset.x,
                unit.position.y + forwardOffset.y,
            )
            val clearanceRadius = UnitLocomotion.terrainPassabilityRadius(unit, map.tileWidth, map.tileHeight)
            val forwardClear = movement.type == MovementType.AIR ||
                PlanningSegmentClearance.allows(
                    host.planningTerrainFor(unit, map),
                    UnitLocomotion.queryPosition(unit, map),
                    forwardProbe,
                    host.routeRadius(unit),
                    clearanceRadius,
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
            cohortEscortYielding = escortPacing.activelyYielding,
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
        movePlanning: MovePlanning,
        executionStep: Long,
    ): SteeringIntent {
        movePlanning.cancel(unit.unitId)
        val completedMoveId = unit.originMoveId
        val originalTarget = unit.nearestMoveTarget ?: unit.landingMember?.group?.click
        val parked = unit.landingMember?.copy(parked = true)
        val arrival = originalTarget?.let {
            MoveArrival(
                it, unit.position,
                MoveArrival.distanceToHull(unit.definition.movementCollision, unit.position, unit.heading, it),
                executionStep, true,
            )
        }
        unit.cancelRoute()
        unit.nav = unit.nav.markArrived(parked, arrival)
        host.landingOccupancy.update(unit)
        host.onMotionArrivalCompleted(unit, completedMoveId)
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
        val maneuverLeg = steering.maneuverLeg
        val shape = if (unit.birthRemaining > 0f) null else UnitLocomotion.terrainHull(unit, map)
        val hull = shape as? MovementCollisionShape.Rectangle
        val circle = (shape as? MovementCollisionShape.Circle)?.let {
            CircularTerrain(it.radius, maxOf(it.radius, UnitLocomotion.boundaryRadius(unit)))
        }
        val rawCollisionMap = if (unit.birthRemaining > 0f) map else host.buildingTerrain?.collisionMap ?: map
        val escapingBuildingCell = BuildingOverlapEscape.isCenterEscapingBuildingCell(
            unit, map, host.buildingTerrain, movement.type,
        )
        val collisionMap = if (escapingBuildingCell) map else rawCollisionMap
        val hullTerrain = shape?.let { HullTerrainGrid.authoritative(collisionMap, movement.type) }
        val requestedHeading = unit.heading
        val nearShoreLeg = maneuverLeg != null && unit.nearestMoveTarget != null && hull != null && movement.type == MovementType.WATER
        val terrainHeading = if (hull != null && requestedHeading != headingBefore) {
            val grid = checkNotNull(hullTerrain)
            val limit = RectangularTurn.resolve(grid, hull, unit.position, headingBefore, requestedHeading)
            if (!nearShoreLeg || UnitContact.poseFits(hull, unit.position, limit, grid)) limit else {
                val angle = ScreenPlane.difference(limit, headingBefore)
                var safe = headingBefore
                var low = 0.0
                var high = 1.0
                repeat(48) {
                    val mid = (low + high) * .5
                    val pose = ScreenPlane.normalize(headingBefore + angle * mid)
                    if (UnitContact.poseFits(hull, unit.position, pose, grid)) { low = mid; safe = pose } else high = mid
                }
                safe
            }
        } else requestedHeading
        val boundedHeading = host.limitAttachmentTurn(unit, map, headingBefore, terrainHeading)
        val legalHeading = if (hull != null && boundedHeading != headingBefore) {
            UnitContact.turn(unit, hull, headingBefore, boundedHeading, host.spatialIndex)
        } else boundedHeading
        val turnProbe = if (nearShoreLeg && unit.terrainCorrectionTime > 0.0 && boundedHeading == requestedHeading) {
            val wanted = ScreenPlane.desired(unit.position, maneuverLeg.target)
            val arc = movement.maxTurnSpeed * delta
            ScreenPlane.normalize(headingBefore + ScreenPlane.difference(wanted, headingBefore).coerceIn(-arc, arc))
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
            val adjustment = ScreenPlane.difference(legalHeading, requestedHeading)
            if (boundedHeading != terrainHeading) unit.mechState = unit.mechState?.let { state ->
                state.copy(motionAngle = MechMotion.angleDifference(state.motionAngle + adjustment, 0.0))
            }
            if (movement.type == MovementType.WATER && unit.definition.combat?.rotateWithBody == true) {
                for (state in unit.namedWeapons?.values ?: listOfNotNull(unit.combat)) {
                    state.turretHeading = ScreenPlane.normalize(state.turretHeading + adjustment)
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
        translations: UnitContact.Translations,
        motionSamples: MutableList<MotionStepSample>?,
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
        val nativeDeparture = host.isNativeSeaDeparture(unit)
        val maneuverLeg = steering.maneuverLeg
        val aimForSample = steering.aimForSample
        val finalForSample = steering.finalForSample
        val differenceForSample = steering.differenceForSample
        val normalCompleted = steering.normalCompleted
        var speedTarget = heading.speedTarget
        var requestedFraction = heading.requestedFraction

        unit.mechState = unit.mechState?.copy(hadMotionAim = aimForSample != null)
        host.recordMotionGate(unit, aimForSample != null)
        val displacement = if (sliding) {
            val desiredVelocity = aimForSample?.let {
                ScreenPlane.displacement(ScreenPlane.desired(unit.position, it), abs(speedTarget))
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
                unit.flightVelocity = ScreenPlane.displacement(unit.heading, unit.speed)
                Position(unit.flightVelocity.x * delta, unit.flightVelocity.y * delta)
            } else ScreenPlane.displacement(unit.heading, unit.speed * delta)
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
                ScreenPlane.distanceSquared(unit.position, maneuverLeg.target) <= unit.speed * delta * unit.speed * delta
            ) maneuverLeg.target else Position(unit.position.x + displacement.x, unit.position.y + displacement.y)
        } else if (heading.forwardTurnEscape || heading.sailClearOfBank) Position(unit.position.x + displacement.x, unit.position.y + displacement.y)
        else maneuverLeg?.position(unit.heading, unit.speed * delta)
            ?: Position(unit.position.x + displacement.x, unit.position.y + displacement.y)

        val integratedHeading = unit.heading
        val integratedSpeed = unit.speed
        fun recordMotion(resolved: Position?, disposition: MotionTerrainDisposition, unitContactLimited: Boolean) {
            motionSamples?.add(
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
            movement.type != MovementType.WATER && !UnitLocomotion.containsPosition(unit, map, candidate)
        ) {
            host.blockAtMapBoundary(unit)
            recordMotion(null, MotionTerrainDisposition.CANDIDATE_OUTSIDE_MAP, heading.legalHeading != heading.boundedHeading)
            return
        }
        val buildingTerrain = host.buildingTerrain
        val producerCells = if (shape != null && unit.birthRemaining == 0f && buildingTerrain != null)
            BuildingOverlapEscape.departingOverlappedBuildingCells(
                map, buildingTerrain, host.spatialIndex, host.units, unit, candidate, shape,
            )
        else emptySet()
        val translationTerrain = BuildingOverlapEscape.leavingGrid(
            heading.collisionMap, map, movement.type, producerCells, hullTerrain,
        )
        val terrainResolved = when {
            rectangle != null -> rectangle.resolve(checkNotNull(translationTerrain), unit.position, candidate)
            circle != null -> BuildingOverlapEscape.resolveCircleTranslation(
                circle = circle,
                collisionMap = heading.collisionMap,
                hardGroundMap = map,
                buildingTerrain = buildingTerrain,
                spatialIndex = host.spatialIndex,
                units = host.units,
                unit = unit,
                movementType = movement.type,
                ordinaryGrid = checkNotNull(hullTerrain),
                candidate = candidate,
                shape = shape as MovementCollisionShape.Circle,
                producerCells = producerCells,
            )
            air -> if (steering.airSeparationVel != null) {
                val br = UnitLocomotion.boundaryRadius(unit)
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
        val boundedPosition = host.limitAttachmentTranslation(unit, map, legalTerrainEnd)
        fun insideMap(position: Position): Boolean = when {
            rectangle != null -> rectangle.contains(checkNotNull(hullTerrain), position)
            circle != null -> circle.contains(checkNotNull(hullTerrain), position)
            else -> UnitLocomotion.containsPosition(unit, map, position)
        }
        val tracksStall = unit.motionContract?.tracksRouteStall == true
        val rangeApproach = unit.motionContract?.isRangeBand == true
        val contactMap = host.buildingTerrain?.collisionMap ?: map
        val canPush: ((MutableUnit, MutableUnit) -> Boolean)? = if (unit.birthRemaining == 0f && insideMap(boundedPosition)) {
            { pusher, blocker ->
                host.canDisplaceInContact(blocker) && !(unit.landingMember != null &&
                    blocker.ownerPlayerId == unit.ownerPlayerId &&
                    blocker.landingMember?.parked == true &&
                    blocker.landingMember?.group === unit.landingMember?.group) &&
                    !host.isLoadingPairAtContact(pusher, blocker)
            }
        } else null
        val translation = translations.resolve(
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
        translations.recordOwnMotion(
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
            translations.recordPush(shift.unit, shift.position)
            shift.unit.position = shift.position
            host.spatialIndex.relocate(shift.unit)
            host.synchronizeAttachmentChildren(shift.unit)
            for (cid in shift.unit.attachmentIds.values) host.units[cid]?.let(host.spatialIndex::relocate)
        }
        val terrainCorrected = heading.boundedHeading != heading.requestedHeading || boundedPosition != candidate
        val activeEscortYield = steering.cohortEscortYielding && !terrainCorrected && !unitContactLimited
        if (unit.nav.cohortEscortYielding != activeEscortYield) {
            unit.updateNavigation { it.withCohortEscortYielding(activeEscortYield) }
        }
        if (unit.moveTarget != null && (nativeDeparture || tracksStall)) {
            val clipped = resolved != candidate
            val attemptedPoseChange = candidate != positionBefore || heading.requestedHeading != headingBefore
            val heldAtPriorPose = resolved == positionBefore && heading.legalHeading == headingBefore
            val nearStaticAvoidance = rangeApproach && steering.avoidanceApplied && unit.pathPoints.isNotEmpty() &&
                ScreenPlane.distanceSquared(resolved, positionBefore) < 0.0025 &&
                unit.nav.progressAnchorStep >= 0L && (executionStep - unit.nav.progressAnchorStep) >= 45L
            val avoidanceBrakedStall = (steering.avoidanceApplied && heldAtPriorPose && unit.pathPoints.isNotEmpty()) ||
                nearStaticAvoidance
            val stalled = if (activeEscortYield) {
                0L
            } else if ((attemptedPoseChange && heldAtPriorPose && (terrainCorrected || unitContactLimited)) || avoidanceBrakedStall) {
                unit.stalledRouteTicks + 1L
            } else 0L
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
            host.synchronizeAttachmentChildren(unit)
            for (number in unit.attachmentOffsets.keys) {
                unit.attachmentIds[number]?.let(host.units::get)?.let(host.spatialIndex::relocate)
            }
        }
        if (unit.maneuverTurnWaypoint != null && resolved != unit.maneuverTurnWaypoint) {
            unit.updateNavigation { it.withManeuverTurnWaypoint(null) }
        }
        if (maneuverLeg != null && !finalForSample && resolved == maneuverLeg.target) unit.speed = 0.0
        if ((movement.type == MovementType.OVER_CLIFF || movement.type == MovementType.OVER_CLIFF_WATER) && aimForSample != null) {
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

    /**
     * Geometric right-of-way yielding for non-circular (rectangular) group hulls converging on a
     * shared landing goal: prevents equal-speed parallel hulls from wedging bows together before
     * any hull reaches the target core.
     */
    private fun rectangularPeerYieldFactor(
        host: UnitMovementHost,
        unit: MutableUnit,
        goal: Position,
    ): Double {
        val group = unit.landingMember?.group ?: return 1.0
        val layer = UnitContact.layer(unit) ?: return 1.0
        val ownR = maxOf(1.0, unit.definition.movementCollision.boundingRadius)
        val ownDistSq = ScreenPlane.distanceSquared(unit.position, goal)
        val fwd = ScreenPlane.displacement(unit.heading, 1.0)
        val queryR = ownR * 2.8
        val nearby = host.spatialIndex.queryBounds(
            SpatialLayer.fromContact(layer),
            unit.position.x - queryR, unit.position.x + queryR,
            unit.position.y - queryR, unit.position.y + queryR,
            useBufferRadius = false,
        )
        var yieldFactor = 1.0
        for (i in 0 until nearby.size) {
            val other = nearby[i]
            if (other === unit || !other.alive || other.moveTarget == null) continue
            val ol = other.landingMember ?: continue
            if (ol.group !== group || ol.parked) continue
            val otherR = maxOf(1.0, other.definition.movementCollision.boundingRadius)
            val dx = other.position.x - unit.position.x
            val dy = other.position.y - unit.position.y
            val dist = hypot(dx, dy)
            val touchR = ownR + otherR
            if (dist > touchR + 8.0) continue
            val ahead = dx * fwd.x + dy * fwd.y
            if (ahead < -ownR * 0.35) continue
            val otherDistSq = ScreenPlane.distanceSquared(other.position, goal)
            val otherHasPriority = otherDistSq < ownDistSq - 4.0 ||
                (abs(otherDistSq - ownDistSq) <= 4.0 && other.unitId < unit.unitId)
            if (!otherHasPriority) continue
            yieldFactor = minOf(yieldFactor, if (dist <= touchR + 1.5) 0.0 else 0.15)
        }
        return yieldFactor
    }
}
