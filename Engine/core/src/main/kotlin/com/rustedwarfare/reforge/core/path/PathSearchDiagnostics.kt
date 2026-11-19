package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.sim.Position

class PathDiagnosticsException(val code: String, message: String) : IllegalStateException(message)

/** One real search attempt; never escapes into an observation or diagnostic sample. */
internal data class PathSearchCounters(
    var actualSearchCalls: Long = 0,
    var candidatesChecked: Long = 0,
    var popped: Long = 0,
    var expanded: Long = 0,
    var enqueued: Long = 0,
    var neighborsChecked: Long = 0,
    var heuristicCalls: Long = 0,
)

enum class PathSearchOutcome { FOUND, UNREACHABLE, FAILED }
enum class PathSearchReason { USER_MOVE, KNOWLEDGE_REPLAN, ROUTE_REPAIR, ATTACK_PURSUIT, CONSTRUCTION_APPROACH }

data class FirstSegmentDecision(val actionId: String, val unitId: String, val start: Position, val end: Position,
                                val blockers: List<String>, val outcome: String)

/** Trusted-host accounting of the shared move queue, separate from wall-clock duration. */
data class PlanningWorkSlice(
    val unitIds: List<String>,
    val reason: PathSearchReason,
    val workUnits: Int,
    val completed: Boolean,
    val restarted: Boolean,
    val publicationWorkUnits: Int = 0,
    val firstSegment: FirstSegmentDecision? = null,
)

data class PlanningWorkSample(
    val executionStep: Long,
    val limit: Int,
    val used: Int,
    val slices: List<PlanningWorkSlice>,
)

data class PathSearchSample(
    val actionId: String,
    val unitId: String,
    val scheduledStep: Long?,
    val usedPlayerKnowledge: Boolean,
    val outcome: PathSearchOutcome,
    val inputPreparationNanos: Long,
    val searchNanos: Long,
    val actualSearchCalls: Long,
    val candidatesChecked: Long,
    val popped: Long,
    val expanded: Long,
    val enqueued: Long,
    val neighborsChecked: Long,
    val heuristicCalls: Long,
    val pathPointCount: Int,
    val reason: PathSearchReason = PathSearchReason.USER_MOVE,
    val executionStep: Long? = null,
    val searchTarget: Position? = null,
    val searchStart: Position? = null,
    val preservedPrefixPointCount: Int = 0,
)
