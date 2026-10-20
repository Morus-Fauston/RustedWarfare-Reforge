package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.sim.Position

class PathDiagnosticsException(val code: String, message: String) : IllegalStateException(message)

/** One real search attempt; never escapes into an observation or diagnostic sample. */
internal class PathSearchCounters {
    var actualSearchCalls = 0L
    var candidatesChecked = 0L
    var popped = 0L
    var expanded = 0L
    var enqueued = 0L
    var neighborsChecked = 0L
    var heuristicCalls = 0L
}

enum class PathSearchOutcome { FOUND, UNREACHABLE, FAILED }
enum class PathSearchReason { USER_MOVE, KNOWLEDGE_REPLAN, ROUTE_REPAIR }

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
