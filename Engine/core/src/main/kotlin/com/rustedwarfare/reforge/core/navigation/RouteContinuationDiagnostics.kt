package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.sim.Position

enum class RouteContinuationStage { REQUESTED, READY, WAITING, HANDED_OFF }

/** Route preparation evidence available only to the trusted simulation host. */
data class RouteContinuationSample(
    val stepIndex: Long,
    val unitId: String,
    val originMoveId: String,
    val anchor: Position,
    val successor: Position,
    val estimatedRemainingTicks: Double,
    val stage: RouteContinuationStage,
)

internal data class RouteContinuation(
    val anchor: Position,
    val successor: Position,
    val ready: Boolean = false,
    val waiting: Boolean = false,
)
