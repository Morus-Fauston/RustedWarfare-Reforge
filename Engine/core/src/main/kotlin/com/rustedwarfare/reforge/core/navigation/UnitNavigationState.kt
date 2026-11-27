package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.MutableUnit
import com.rustedwarfare.reforge.core.sim.MoveArrival
import com.rustedwarfare.reforge.core.sim.immutable

import java.util.Collections

/**
 * Immutable host-only path-acceptance and logical-request audit counters carried inside
 * [UnitNavigationState] (ENG-P017-005).
 *
 * Consolidates the 6 node-count / logical-request fields plus [movementRequested] so
 * [StepState.fork] shares them in O(1) without mutable per-unit fields on [MutableUnit].
 */
internal data class NavigationRequestAudit(
    val movementRequested: Boolean = false,
    val singleNodePathCount: Byte = 0,
    val logicalRequestX: Float? = null,
    val logicalRequestY: Float? = null,
    val pendingPathNodeCounts: List<Int> = emptyList(),
    val consumedPathNodeCounts: List<Int> = emptyList(),
    val qBeforeDispatch: Byte = 0,
) {
    fun validate() {
        require(
            (logicalRequestX == null) == (logicalRequestY == null) &&
                logicalRequestX?.isFinite() != false &&
                logicalRequestY?.isFinite() != false,
        ) {
            "Logical movement coordinates must be a finite pair"
        }
        require(pendingPathNodeCounts.all { it >= 0 } && consumedPathNodeCounts.all { it >= 0 }) {
            "Accepted path node counts must remain nonnegative"
        }
    }

    fun beginMovementStep(): NavigationRequestAudit =
        if (!movementRequested) this else copy(movementRequested = false)

    fun snapshotDispatchWindow(): NavigationRequestAudit =
        if (qBeforeDispatch == singleNodePathCount && consumedPathNodeCounts.isEmpty()) this
        else copy(qBeforeDispatch = singleNodePathCount, consumedPathNodeCounts = emptyList())

    fun recordLogicalTarget(target: Position, markRequested: Boolean): NavigationRequestAudit {
        val x = target.x.toFloat()
        val y = target.y.toFloat()
        require(x.isFinite() && y.isFinite()) { "Logical movement target must remain finite" }
        val sameTarget = logicalRequestX != null && logicalRequestY != null &&
            logicalRequestX == x && logicalRequestY == y
        val nextSingle = if (sameTarget) singleNodePathCount else 0
        val nextPending = if (sameTarget) pendingPathNodeCounts else emptyList()
        val nextRequested = if (markRequested) true else movementRequested
        if (nextRequested == movementRequested &&
            nextSingle == singleNodePathCount &&
            logicalRequestX == x &&
            logicalRequestY == y &&
            nextPending === pendingPathNodeCounts
        ) {
            return this
        }
        return copy(
            movementRequested = nextRequested,
            singleNodePathCount = nextSingle,
            logicalRequestX = x,
            logicalRequestY = y,
            pendingPathNodeCounts = nextPending,
        )
    }

    fun recordAdoptedPath(logicalTarget: Position, nodeCount: Int): NavigationRequestAudit {
        val base = recordLogicalTarget(logicalTarget, markRequested = false)
        val combined = ArrayList<Int>(base.pendingPathNodeCounts.size + 1)
        combined.addAll(base.pendingPathNodeCounts)
        combined.add(nodeCount)
        return base.copy(pendingPathNodeCounts = Collections.unmodifiableList(combined))
    }

    fun resetAccepted(clearRequested: Boolean = false): NavigationRequestAudit {
        val nextReq = if (clearRequested) false else movementRequested
        if (nextReq == movementRequested &&
            singleNodePathCount == 0.toByte() &&
            logicalRequestX == null &&
            logicalRequestY == null &&
            pendingPathNodeCounts.isEmpty() &&
            consumedPathNodeCounts.isEmpty()
        ) {
            return this
        }
        return copy(
            movementRequested = nextReq,
            singleNodePathCount = 0,
            logicalRequestX = null,
            logicalRequestY = null,
            pendingPathNodeCounts = emptyList(),
            consumedPathNodeCounts = emptyList(),
        )
    }

    fun finishMovementStep(): NavigationRequestAudit {
        if (!movementRequested) {
            return resetAccepted(clearRequested = false)
        }
        val consumed = pendingPathNodeCounts
        var nextSingle = singleNodePathCount
        for (count in consumed) {
            if (count == 1) nextSingle = (nextSingle + 1).toByte()
        }
        return copy(
            singleNodePathCount = nextSingle,
            pendingPathNodeCounts = emptyList(),
            consumedPathNodeCounts = consumed,
        )
    }

    companion object {
        val EMPTY = NavigationRequestAudit()
    }
}

/**
 * Unified immutable group-reservation contract consolidating legacy slot reservations
 * ([GroupMoveReservation] / [GroupMoveRequest]) and shared landing reservations ([LandingMember])
 * into a single value object carried inside [UnitNavigationState].
 */
internal data class GroupReservation(
    val slot: GroupMoveReservation? = null,
    val parkedOwner: GroupMoveRequest? = null,
    val landing: LandingMember? = null,
) {
    val active: Boolean get() = slot != null || parkedOwner != null || landing != null

    val group: GroupMoveRequest? get() = landing?.group ?: slot?.plan ?: parkedOwner

    companion object {
        fun of(
            slot: GroupMoveReservation? = null,
            parkedOwner: GroupMoveRequest? = null,
            landing: LandingMember? = null,
        ): GroupReservation? =
            if (slot == null && parkedOwner == null && landing == null) null
            else GroupReservation(slot, parkedOwner, landing)
    }
}

/**
 * Immutable navigation & movement-orchestration state for a [MutableUnit].
 *
 * Consolidates all route, maneuver, passage, group-reservation, orthogonal [MotionContract],
 * and [NavigationRequestAudit] fields into a single immutable value object so [StepState.fork]
 * shares the reference in O(1) and all mutations occur through semantic atomic transitions.
 */
internal data class UnitNavigationState(
    val moveTarget: Position? = null,
    val motionContract: MotionContract? = null,
    val nearestMoveTarget: Position? = null,
    val nearestMoveIsGroup: Boolean = false,
    val moveArrival: MoveArrival? = null,
    val pathPoints: List<Position> = emptyList(),
    val originMoveId: String? = null,
    val originAtStep: Long? = null,
    val lastPlannedKnowledgeRevision: Long = -1L,
    val lastPlannedBuildingRevision: Long = -1L,
    val nearTargetTime: Double = 0.0,
    val routeContinuation: RouteContinuation? = null,
    val stalledRouteTicks: Long = 0L,
    val hardContactLimitedTicks: Long = 0L,
    val routeTranslationClipped: Boolean = false,
    val followsManeuverRoute: Boolean = false,
    val maneuverTurnWaypoint: Position? = null,
    val lastManeuverContactRepair: Long? = null,
    val routeOccupantIds: List<String> = emptyList(),
    val passageEntry: PassageEntry? = null,
    val groupReservation: GroupReservation? = null,
    val indexedLandingBody: LandingOccupancy.IndexedBody? = null,
    val adoptedRouteRevision: Long = 0L,
    val localProgressTime: Double = 0.0,
    val terrainCorrectionTime: Double = 0.0,
    val terrainClearSteps: Int = 0,
    val terrainOnlyStall: Boolean = false,
    val optimisticSteerTarget: Position? = null,
    val repairBackoffStep: Long = 0L,
    val repairConsecutiveFailures: Int = 0,
    val lastAvoidHeadingDeg: Double? = null,
    val progressAnchorPos: Position? = null,
    val progressAnchorStep: Long = -1L,
    val requestAudit: NavigationRequestAudit = NavigationRequestAudit.EMPTY,
) {
    val groupMoveReservation: GroupMoveReservation?
        get() = groupReservation?.slot

    val parkedReservationOwner: GroupMoveRequest?
        get() = groupReservation?.parkedOwner

    val landingMember: LandingMember?
        get() = groupReservation?.landing

    fun withPathPoints(points: List<Position>): UnitNavigationState =
        copy(pathPoints = immutableList(points))

    fun withRouteOccupantIds(ids: List<String>): UnitNavigationState =
        copy(routeOccupantIds = immutableList(ids))

    fun withMoveTarget(
        target: Position?,
        contract: MotionContract? = if (target == null) null else (motionContract?.withAnchor(target) ?: MotionContract.pointMove(target)),
    ): UnitNavigationState = copy(moveTarget = target, motionContract = contract)

    fun withMotionContract(contract: MotionContract?): UnitNavigationState =
        if (motionContract == contract) this else copy(motionContract = contract)

    fun withMoveArrival(arrival: MoveArrival?): UnitNavigationState =
        if (moveArrival == arrival) this else copy(moveArrival = arrival)

    fun withOptimisticSteerTarget(target: Position?): UnitNavigationState =
        if (optimisticSteerTarget == target) this else copy(optimisticSteerTarget = target)

    fun withPassageEntry(entry: PassageEntry?): UnitNavigationState =
        if (passageEntry == entry) this else copy(passageEntry = entry)

    fun withIndexedLandingBody(indexed: LandingOccupancy.IndexedBody?): UnitNavigationState =
        if (indexedLandingBody === indexed) this else copy(indexedLandingBody = indexed)

    fun withRouteContinuation(continuation: RouteContinuation?): UnitNavigationState =
        if (routeContinuation == continuation) this else copy(routeContinuation = continuation)

    fun withNearTargetTime(time: Double): UnitNavigationState =
        if (nearTargetTime == time) this else copy(nearTargetTime = time)

    fun withStalledRouteTicks(ticks: Long): UnitNavigationState =
        if (stalledRouteTicks == ticks) this else copy(stalledRouteTicks = ticks)

    fun withHardContactLimitedTicks(ticks: Long): UnitNavigationState =
        if (hardContactLimitedTicks == ticks) this else copy(hardContactLimitedTicks = ticks)

    fun withFollowsManeuverRoute(enabled: Boolean): UnitNavigationState =
        if (followsManeuverRoute == enabled) this else copy(followsManeuverRoute = enabled)

    fun withManeuverTurnWaypoint(waypoint: Position?): UnitNavigationState =
        if (maneuverTurnWaypoint == waypoint) this else copy(maneuverTurnWaypoint = waypoint)

    fun withLastManeuverContactRepair(step: Long?): UnitNavigationState =
        if (lastManeuverContactRepair == step) this else copy(lastManeuverContactRepair = step)

    fun withTerrainHistory(correctionTime: Double, clearSteps: Int = terrainClearSteps, terrainOnly: Boolean = terrainOnlyStall): UnitNavigationState =
        copy(terrainCorrectionTime = correctionTime, terrainClearSteps = clearSteps, terrainOnlyStall = terrainOnly)

    fun withGroupMoveReservation(value: GroupMoveReservation?): UnitNavigationState {
        val next = GroupReservation.of(slot = value, parkedOwner = parkedReservationOwner, landing = landingMember)
        return if (next == groupReservation) this else copy(groupReservation = next)
    }

    fun withParkedReservationOwner(value: GroupMoveRequest?): UnitNavigationState {
        val next = GroupReservation.of(slot = groupMoveReservation, parkedOwner = value, landing = landingMember)
        return if (next == groupReservation) this else copy(groupReservation = next)
    }

    fun withLandingMember(value: LandingMember?): UnitNavigationState {
        val next = GroupReservation.of(slot = groupMoveReservation, parkedOwner = parkedReservationOwner, landing = value)
        return if (next == groupReservation) this else copy(groupReservation = next)
    }

    /** Atomically binds command origin, target, reservation state, and orthogonal [MotionContract] prior to route adoption. */
    fun bindCommandTarget(
        target: Position,
        originMoveId: String? = this.originMoveId,
        originAtStep: Long? = this.originAtStep,
        contract: MotionContract,
        followsManeuverRoute: Boolean = this.followsManeuverRoute,
        nearestMoveTarget: Position? = this.nearestMoveTarget,
        nearestMoveIsGroup: Boolean = this.nearestMoveIsGroup,
        nearTargetTime: Double = this.nearTargetTime,
        lastManeuverContactRepair: Long? = this.lastManeuverContactRepair,
        groupReservation: GroupReservation? = this.groupReservation,
    ): UnitNavigationState = copy(
        moveTarget = target,
        motionContract = contract,
        originMoveId = originMoveId,
        originAtStep = originAtStep,
        followsManeuverRoute = followsManeuverRoute,
        nearestMoveTarget = nearestMoveTarget,
        nearestMoveIsGroup = nearestMoveIsGroup,
        nearTargetTime = nearTargetTime,
        lastManeuverContactRepair = lastManeuverContactRepair,
        groupReservation = groupReservation,
    )

    /** Clears command origin, route-repair counters, motion contract, and unified group reservation atomically. */
    fun clearMoveOrigin(): UnitNavigationState = copy(
        motionContract = null,
        nearestMoveTarget = null,
        nearestMoveIsGroup = false,
        originMoveId = null,
        originAtStep = null,
        groupReservation = null,
        lastPlannedKnowledgeRevision = -1L,
        lastPlannedBuildingRevision = -1L,
        nearTargetTime = 0.0,
        routeContinuation = null,
        stalledRouteTicks = 0L,
        hardContactLimitedTicks = 0L,
        routeTranslationClipped = false,
        followsManeuverRoute = false,
        maneuverTurnWaypoint = null,
        lastManeuverContactRepair = null,
        routeOccupantIds = emptyList(),
        localProgressTime = 0.0,
        terrainOnlyStall = false,
        optimisticSteerTarget = null,
        repairBackoffStep = 0L,
        repairConsecutiveFailures = 0,
        lastAvoidHeadingDeg = null,
        progressAnchorPos = null,
        progressAnchorStep = -1L,
    )

    /** Pauses the physical route while keeping arrival history, terrain correction state, and request audit. */
    fun pauseRoute(): UnitNavigationState = clearMoveOrigin().copy(
        moveTarget = null,
        pathPoints = emptyList(),
        passageEntry = null,
    )

    /** Ends the active command route and resets accepted path-node audit counters atomically. */
    fun cancelRoute(clearRequested: Boolean = false): UnitNavigationState =
        pauseRoute().copy(requestAudit = requestAudit.resetAccepted(clearRequested = clearRequested))

    /** Adopts a freshly planned or repaired immutable waypoint list atomically (without touching requestAudit). */
    fun adoptRoute(
        points: List<Position>,
        knowledgeRevision: Long,
        buildingRevision: Long = -1L,
        occupantIds: List<String> = emptyList(),
        repairReset: Boolean = false,
    ): UnitNavigationState = copy(
        adoptedRouteRevision = adoptedRouteRevision + 1L,
        pathPoints = immutableList(points),
        routeContinuation = null,
        stalledRouteTicks = 0L,
        hardContactLimitedTicks = 0L,
        routeTranslationClipped = false,
        maneuverTurnWaypoint = null,
        routeOccupantIds = immutableList(occupantIds),
        localProgressTime = 0.0,
        lastPlannedKnowledgeRevision = knowledgeRevision,
        lastPlannedBuildingRevision = buildingRevision,
        optimisticSteerTarget = null,
        repairBackoffStep = if (repairReset) 0L else repairBackoffStep,
        repairConsecutiveFailures = if (repairReset) 0 else repairConsecutiveFailures,
        lastAvoidHeadingDeg = null,
        progressAnchorPos = null,
        progressAnchorStep = -1L,
    )

    /** Adopts a route AND records the logical acceptance in [requestAudit] in one atomic transition. */
    fun adoptTrackedRoute(
        points: List<Position>,
        logicalTarget: Position,
        knowledgeRevision: Long,
        buildingRevision: Long = -1L,
        occupantIds: List<String> = emptyList(),
        repairReset: Boolean = false,
    ): UnitNavigationState = adoptRoute(
        points = points,
        knowledgeRevision = knowledgeRevision,
        buildingRevision = buildingRevision,
        occupantIds = occupantIds,
        repairReset = repairReset,
    ).copy(
        requestAudit = requestAudit.recordAdoptedPath(logicalTarget, points.size),
    )

    /**
     * Splices the terminal waypoint in O(1) when tracking a moving target with clear line-of-sight
     * from the preceding waypoint/unit position, without triggering a full A* replan or node-acceptance increment.
     */
    fun spliceTailTarget(newDestination: Position): UnitNavigationState {
        if (pathPoints.isEmpty()) return this
        val nextPoints = if (pathPoints.size == 1) {
            Collections.singletonList(newDestination)
        } else {
            val updated = ArrayList<Position>(pathPoints.size)
            for (i in 0 until pathPoints.lastIndex) updated.add(pathPoints[i])
            updated.add(newDestination)
            Collections.unmodifiableList(updated)
        }
        return copy(
            moveTarget = newDestination,
            motionContract = motionContract?.withAnchor(newDestination),
            pathPoints = nextPoints,
            nearTargetTime = 0.0,
        )
    }

    /** Pops the front waypoint when a leg completes or hands off. */
    fun popFrontWaypoint(
        clearLocalSegment: Boolean = false,
        maneuverTurnWaypoint: Position? = this.maneuverTurnWaypoint,
    ): UnitNavigationState {
        if (pathPoints.isEmpty()) return this
        val remaining = if (pathPoints.size == 1) emptyList() else immutableList(pathPoints.subList(1, pathPoints.size))
        return if (clearLocalSegment) {
            copy(
                pathPoints = remaining,
                routeContinuation = null,
                localProgressTime = 0.0,
                maneuverTurnWaypoint = maneuverTurnWaypoint,
            )
        } else {
            copy(
                pathPoints = remaining,
                maneuverTurnWaypoint = maneuverTurnWaypoint,
            )
        }
    }

    /** Prepends bypass or bend waypoints in front of the current route and resets local segment state. */
    fun prependWaypoints(prefix: List<Position>, resetLocalSegment: Boolean = true): UnitNavigationState {
        if (prefix.isEmpty()) return this
        val combined = ArrayList<Position>(prefix.size + pathPoints.size)
        combined.addAll(prefix)
        combined.addAll(pathPoints)
        val frozen = Collections.unmodifiableList(combined)
        return if (resetLocalSegment) {
            copy(
                pathPoints = frozen,
                localProgressTime = 0.0,
                routeContinuation = null,
                lastAvoidHeadingDeg = null,
            )
        } else {
            copy(pathPoints = frozen)
        }
    }

    /** Replaces the front waypoint (e.g. rejoining a forward point on the active leg after a push). */
    fun replaceFrontWaypoint(point: Position, resetLocalSegment: Boolean = true): UnitNavigationState {
        if (pathPoints.isEmpty()) return this
        val next = ArrayList<Position>(pathPoints.size)
        next.add(point)
        for (i in 1 until pathPoints.size) next.add(pathPoints[i])
        val frozen = Collections.unmodifiableList(next)
        return if (resetLocalSegment) {
            copy(
                pathPoints = frozen,
                routeContinuation = null,
                localProgressTime = 0.0,
                lastAvoidHeadingDeg = null,
            )
        } else {
            copy(pathPoints = frozen)
        }
    }

    /** Transitions atomically to an arrived/parked state while preserving arrival metadata. */
    fun markArrived(
        parkedLanding: LandingMember?,
        parkedOwner: GroupMoveRequest?,
        arrival: MoveArrival?,
    ): UnitNavigationState = pauseRoute().copy(
        groupReservation = GroupReservation.of(slot = null, parkedOwner = parkedOwner, landing = parkedLanding),
        moveArrival = arrival ?: moveArrival,
    )

    fun recordRepairBackoff(backoffUntilStep: Long, nextConsecutiveFailures: Int): UnitNavigationState =
        copy(
            repairBackoffStep = backoffUntilStep,
            repairConsecutiveFailures = nextConsecutiveFailures,
        )

    companion object {
        val EMPTY = UnitNavigationState()

        private fun <T> immutableList(items: List<T>): List<T> =
            if (items.isEmpty()) emptyList() else Collections.unmodifiableList(ArrayList(items))
    }
}
