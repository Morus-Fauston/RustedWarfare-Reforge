package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*

fun interface ClientEvidence {
    fun record(kind: String, values: Map<String, Any?>)

    companion object { val NONE = ClientEvidence { _, _ -> } }
}

internal fun Position.fields(): Map<String, Any?> = mapOf("x" to x, "y" to y)
internal fun ActionResult.fields(): Map<String, Any?> = when (this) {
    is ActionResult.Accepted -> mapOf("actionId" to actionId, "accepted" to true,
        "events" to events.map { mapOf("kind" to it.kind, "unitId" to it.unitId) })
    is ActionResult.Rejected -> mapOf("actionId" to actionId, "accepted" to false,
        "code" to error.code, "message" to error.message)
}

internal fun Observation.fields(): Map<String, Any?> = mapOf(
    "sampleId" to sampleId, "worldId" to worldId, "scenarioId" to scenarioId,
    "stepIndex" to stepIndex, "simulationTime" to simulationTime,
    "units" to units.map { unit -> mapOf(
        "unitId" to unit.unitId, "ownerPlayerId" to unit.ownerPlayerId, "typeId" to unit.typeId,
        "position" to unit.position.fields(), "heading" to unit.heading, "speed" to unit.speed,
        "angularVelocity" to unit.angularVelocity, "moveTarget" to unit.moveTarget?.fields(),
        "pathPoints" to unit.pathPoints.map { it.fields() }, "movementState" to unit.movementState.name.lowercase(),
    ) },
    "events" to events.map { mapOf("kind" to it.kind, "unitId" to it.unitId) },
    "actionResults" to actionResults.map { it.fields() },
)
