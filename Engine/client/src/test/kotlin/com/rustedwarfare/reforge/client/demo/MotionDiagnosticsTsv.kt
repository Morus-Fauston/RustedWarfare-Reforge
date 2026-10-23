package com.rustedwarfare.reforge.client.demo

import com.rustedwarfare.reforge.core.sim.MotionStepSample

internal const val MOTION_TSV_HEADER = "world\tstep\tunit\towner\ttype\ttimeBefore\tdelta\torigin\tbeforeX\tbeforeY\tbeforeHeading\tbeforeSpeed\tbeforeCg\tcommandX\tcommandY\tnormalCompletion\tsteeringInvoked\taimX\taimY\tfinalLocal\tdiff\tfraction\tintegratedHeading\tintegratedSpeed\tcandidateX\tcandidateY\tresolvedX\tresolvedY\tdisposition\tfinalX\tfinalY\tfinalHeading\tfinalSpeed\tfinalCg\tyBefore\tyAfter\tbBefore\tbAfter"

internal fun MotionStepSample.tsv(): String = listOf(worldId, stepIndex, unitId, ownerPlayerId, typeId, timeBefore, delta,
    originMoveId, positionBefore.x, positionBefore.y, headingBefore, speedBefore, angularVelocityBefore,
    commandTargetBefore?.x, commandTargetBefore?.y, normalCompletion, steeringInvoked, steeringAim?.x, steeringAim?.y,
    finalLocalPoint, steeringDifference, targetFraction, integratedHeading, integratedSpeed, candidate.x, candidate.y,
    terrainResolved?.x, terrainResolved?.y, terrainDisposition, finalPosition.x, finalPosition.y, finalHeading, finalSpeed,
    finalAngularVelocity, localProgressBefore, localProgressAfter, correctionTimeBefore, correctionTimeAfter).joinToString("\t") { it?.toString() ?: "null" }
