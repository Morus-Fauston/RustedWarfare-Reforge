package com.rustedwarfare.reforge.core.sim

import java.util.Collections

/**
 * Unified immutable group-reservation contract consolidating legacy slot reservations
 * ([GroupMoveReservation] / [GroupMoveRequest]) and shared landing reservations ([LandingMember])
 * into a single value object carried inside [UnitNavigationState].
 *
 * During the transition window before ENG-P016-009 removes the legacy slot system, a shared-landing
 * unit can carry both its active [landing] claim and a concrete final-bay [slot] reservation at the
 * same time; holding them in one immutable [GroupReservation] guarantees atomic clearing on cancel,
 * retarget, or arrival with zero leftover dirty state.
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
 * Consolidates the scattered mutable movement, local-segment, route-repair, stall-counter,
 * and group-reservation fields into a single value object so [StepState.fork] shares the
 * reference in O(1) without per-unit list copying, and command transitions (adopt / pause /
 * cancel / arrive) happen atomically in one place without leftover dirty fields.
 */
internal data class UnitNavigationState(
    val moveTarget: Position? = null,
    val nearestMoveTarget: Position? = null,
    val nearestMoveIsGroup: Boolean = false,
    val moveArrival: MoveArrival? = null,
    val pathPoints: List<Position> = emptyList(),
    val originMoveId: String? = null,
    val originAtStep: Long? = null,
    val lastPlannedKnowledgeRevision: Long = -1L,
    val lastPlannedBuildingRevision: Long = -1L,
    val nearTargetTime: Double = 0.0,
    val localMotion: LocalMotionSegment? = null,
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
    val lastRoadProgress: RoadProgress? = null,
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
) {
    /**
     * Atomically freezes a caller-provided waypoint list (or keeps emptyList) so external
     * mutations to the caller's list can never alias or corrupt [UnitNavigationState].
     */
    fun withPathPoints(points: List<Position>): UnitNavigationState =
        copy(pathPoints = immutableList(points))

    fun withRouteOccupantIds(ids: List<String>): UnitNavigationState =
        copy(routeOccupantIds = immutableList(ids))
    val groupMoveReservation: GroupMoveReservation?
        get() = groupReservation?.slot

    val parkedReservationOwner: GroupMoveRequest?
        get() = groupReservation?.parkedOwner

    val landingMember: LandingMember?
        get() = groupReservation?.landing

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

    /** Clears command origin, route-repair counters, and unified group reservation atomically. */
    fun clearMoveOrigin(): UnitNavigationState = copy(
        nearestMoveTarget = null,
        nearestMoveIsGroup = false,
        originMoveId = null,
        originAtStep = null,
        groupReservation = null,
        lastPlannedKnowledgeRevision = -1L,
        lastPlannedBuildingRevision = -1L,
        nearTargetTime = 0.0,
        localMotion = null,
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

    /** Pauses the physical route while keeping arrival history and terrain correction state. */
    fun pauseRoute(): UnitNavigationState = clearMoveOrigin().copy(
        moveTarget = null,
        pathPoints = emptyList(),
        passageEntry = null,
    )

    /** Adopts a freshly planned or repaired immutable waypoint list atomically. */
    fun adoptRoute(
        points: List<Position>,
        knowledgeRevision: Long,
        buildingRevision: Long = -1L,
        occupantIds: List<String> = emptyList(),
    ): UnitNavigationState = copy(
        adoptedRouteRevision = adoptedRouteRevision + 1L,
        pathPoints = immutableList(points),
        localMotion = null,
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
        lastAvoidHeadingDeg = null,
        progressAnchorPos = null,
        progressAnchorStep = -1L,
    )

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
                localMotion = null,
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
                localMotion = null,
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
                localMotion = null,
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

    companion object {
        val EMPTY = UnitNavigationState()

        private fun <T> immutableList(items: List<T>): List<T> =
            if (items.isEmpty()) emptyList() else Collections.unmodifiableList(ArrayList(items))
    }
}
