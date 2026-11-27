package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.sim.Position

/**
 * Traversal coordination mode inside [MotionContract] (ADR-016 / ENG-P017-005).
 */
enum class TraversalMode {
    /** Default fastest-arrival independent/flow movement. */
    INDEPENDENT,

    /** Explicit cohort escort movement (Windows `Alt` ground move, ENG-P017-010). */
    COHORT_ESCORT,
}

/**
 * Orthogonal target geometry consumed by navigation, local avoidance, and terminal landing,
 * decoupled from upper-layer [UnitTaskKind] and task-queue length (ADR-016 / ENG-P017-005).
 */
sealed interface MotionGoalGeometry {
    val anchor: Position
    val completesOnCapture: Boolean

    /** Exact single-unit destination point (e.g. single Move, rally leg, patrol waypoint, mobile pursuit). */
    data class Point(
        override val anchor: Position,
        override val completesOnCapture: Boolean = true,
        val allowAirRallyRing: Boolean = true,
        val allowShortReverse: Boolean = true,
    ) : MotionGoalGeometry

    /** Shared terminal landing cluster (e.g. multi-unit Move, Attack-Move / Cohort destination). */
    data class SharedCluster(
        override val anchor: Position,
        override val completesOnCapture: Boolean = true,
    ) : MotionGoalGeometry

    /** Continuous interaction distance band around an anchor (e.g. build/repair/reclaim/siege/boarding). */
    data class RangeBand(
        override val anchor: Position,
        val range: Double,
        override val completesOnCapture: Boolean = false,
        val zeroStopOnFinalLeg: Boolean = true,
    ) : MotionGoalGeometry
}

/**
 * Task-agnostic motion contract governing how a unit's active route is steered, yielded,
 * avoided, and settled by the navigation subsystem.
 */
data class MotionContract(
    val goal: MotionGoalGeometry,
    val allowPassageYield: Boolean = true,
    val traversalMode: TraversalMode = TraversalMode.INDEPENDENT,
    val enableLocalAvoidance: Boolean = true,
    val allowsManeuverFollow: Boolean = true,
    val preparesRouteSuccessor: Boolean = false,
    val tracksRouteStall: Boolean = true,
    val tracksHardContactLimit: Boolean = true,
    val gatheringTarget: Position? = null,
    val isBoardingApproach: Boolean = false,
) {
    val isRangeBand: Boolean get() = goal is MotionGoalGeometry.RangeBand
    val isSharedCluster: Boolean get() = goal is MotionGoalGeometry.SharedCluster

    fun withAnchor(newAnchor: Position): MotionContract {
        val nextGoal = when (val g = goal) {
            is MotionGoalGeometry.Point -> if (g.anchor == newAnchor) g else g.copy(anchor = newAnchor)
            is MotionGoalGeometry.SharedCluster -> if (g.anchor == newAnchor) g else g.copy(anchor = newAnchor)
            is MotionGoalGeometry.RangeBand -> if (g.anchor == newAnchor) g else g.copy(anchor = newAnchor)
        }
        val nextGathering = if (gatheringTarget != null) newAnchor else null
        return if (nextGoal === goal && nextGathering == gatheringTarget) this
        else copy(goal = nextGoal, gatheringTarget = nextGathering)
    }

    companion object {
        fun factoryExit(
            target: Position,
            hasQueuedExitTask: Boolean,
            hasRally: Boolean,
        ): MotionContract = if (hasQueuedExitTask) {
            pointMove(
                target = target,
                allowShortReverse = false,
                gatheringTarget = if (hasRally) null else target,
            )
        } else {
            MotionContract(
                goal = MotionGoalGeometry.Point(
                    anchor = target,
                    completesOnCapture = true,
                    allowAirRallyRing = false,
                    allowShortReverse = false,
                ),
                allowPassageYield = false,
                enableLocalAvoidance = true,
                allowsManeuverFollow = false,
                preparesRouteSuccessor = false,
                tracksRouteStall = false,
                tracksHardContactLimit = false,
                gatheringTarget = null,
            )
        }

        fun pointMove(
            target: Position,
            allowPassageYield: Boolean = true,
            allowShortReverse: Boolean = true,
            allowAirRallyRing: Boolean = true,
            preparesRouteSuccessor: Boolean = true,
            gatheringTarget: Position? = target,
            traversalMode: TraversalMode = TraversalMode.INDEPENDENT,
        ): MotionContract = MotionContract(
            goal = MotionGoalGeometry.Point(
                anchor = target,
                completesOnCapture = true,
                allowAirRallyRing = allowAirRallyRing,
                allowShortReverse = allowShortReverse,
            ),
            allowPassageYield = allowPassageYield,
            traversalMode = traversalMode,
            enableLocalAvoidance = true,
            allowsManeuverFollow = true,
            preparesRouteSuccessor = preparesRouteSuccessor,
            tracksRouteStall = true,
            tracksHardContactLimit = true,
            gatheringTarget = gatheringTarget,
        )

        fun sharedCluster(
            center: Position,
            allowPassageYield: Boolean = true,
            preparesRouteSuccessor: Boolean = true,
            gatheringTarget: Position? = center,
            traversalMode: TraversalMode = TraversalMode.INDEPENDENT,
        ): MotionContract = MotionContract(
            goal = MotionGoalGeometry.SharedCluster(
                anchor = center,
                completesOnCapture = true,
            ),
            allowPassageYield = allowPassageYield,
            traversalMode = traversalMode,
            enableLocalAvoidance = true,
            allowsManeuverFollow = true,
            preparesRouteSuccessor = preparesRouteSuccessor,
            tracksRouteStall = true,
            tracksHardContactLimit = true,
            gatheringTarget = gatheringTarget,
        )

        fun patrolLeg(
            target: Position,
            allowPassageYield: Boolean = false,
        ): MotionContract = MotionContract(
            goal = MotionGoalGeometry.Point(
                anchor = target,
                completesOnCapture = false,
                allowAirRallyRing = false,
                allowShortReverse = false,
            ),
            allowPassageYield = allowPassageYield,
            enableLocalAvoidance = true,
            allowsManeuverFollow = false,
            preparesRouteSuccessor = false,
            tracksRouteStall = false,
            tracksHardContactLimit = false,
            gatheringTarget = null,
        )

        fun pursuitPoint(
            target: Position,
            allowPassageYield: Boolean = false,
        ): MotionContract = MotionContract(
            goal = MotionGoalGeometry.Point(
                anchor = target,
                completesOnCapture = true,
                allowAirRallyRing = false,
                allowShortReverse = false,
            ),
            allowPassageYield = allowPassageYield,
            enableLocalAvoidance = true,
            allowsManeuverFollow = false,
            preparesRouteSuccessor = false,
            tracksRouteStall = false,
            tracksHardContactLimit = false,
            gatheringTarget = null,
        )

        fun rangeApproach(
            center: Position,
            range: Double,
            tracksRouteStall: Boolean,
            enableLocalAvoidance: Boolean = true,
            allowPassageYield: Boolean = false,
            isBoardingApproach: Boolean = false,
        ): MotionContract = MotionContract(
            goal = MotionGoalGeometry.RangeBand(
                anchor = center,
                range = range,
                completesOnCapture = false,
                zeroStopOnFinalLeg = true,
            ),
            allowPassageYield = allowPassageYield,
            enableLocalAvoidance = enableLocalAvoidance,
            allowsManeuverFollow = false,
            preparesRouteSuccessor = false,
            tracksRouteStall = tracksRouteStall,
            tracksHardContactLimit = false,
            gatheringTarget = null,
            isBoardingApproach = isBoardingApproach,
        )
    }
}
