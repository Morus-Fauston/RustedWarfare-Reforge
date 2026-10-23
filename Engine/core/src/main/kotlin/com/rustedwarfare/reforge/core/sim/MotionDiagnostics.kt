package com.rustedwarfare.reforge.core.sim

enum class MotionTerrainDisposition { DIRECT, CORRECTED, CANDIDATE_OUTSIDE_MAP, RESOLVED_OUTSIDE_MAP }

/** Exact already-computed inputs for trusted-host verification; never included in player DTOs. */
data class MotionStepSample(
    val worldId: String, val unitId: String, val ownerPlayerId: String, val typeId: String,
    val stepIndex: Long, val timeBefore: Double, val delta: Double, val originMoveId: String?,
    val positionBefore: Position, val headingBefore: Double, val speedBefore: Double, val angularVelocityBefore: Double,
    val commandTargetBefore: Position?, val normalCompletion: Boolean,
    val steeringInvoked: Boolean, val steeringAim: Position?, val finalLocalPoint: Boolean?, val steeringDifference: Double?,
    val targetFraction: Double, val integratedHeading: Double, val integratedSpeed: Double,
    val candidate: Position, val terrainResolved: Position?, val terrainDisposition: MotionTerrainDisposition,
    val finalPosition: Position, val finalHeading: Double, val finalSpeed: Double, val finalAngularVelocity: Double,
    val localProgressBefore: Double, val localProgressAfter: Double,
    val correctionTimeBefore: Double, val correctionTimeAfter: Double,
)

class MotionDiagnosticsException(val code: String, message: String) : IllegalStateException(message)
