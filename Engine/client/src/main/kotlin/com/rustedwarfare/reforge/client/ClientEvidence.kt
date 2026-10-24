package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*

fun interface ClientEvidence {
    fun record(kind: String, values: Map<String, Any?>)

    companion object { val NONE = ClientEvidence { _, _ -> } }
}

/** Keep the public sink contract; the canonical disabled sink needs no payload preparation. */
internal inline fun ClientEvidence.recordWhenEnabled(kind: String, values: () -> Map<String, Any?>) {
    if (this !== ClientEvidence.NONE) record(kind, values())
}

internal inline fun preserveFailure(primary: Throwable, action: () -> Unit) {
    try { action() } catch (secondary: Throwable) {
        if (secondary !== primary) primary.addSuppressed(secondary)
    }
}

internal fun ClientEvidence.recordFailure(kind: String, primary: Throwable) {
    preserveFailure(primary) { recordWhenEnabled(kind) { mapOf("message" to primary.toString()) } }
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

internal fun PlayerObservation.fields(): Map<String, Any?> =
    Observation(sampleId, worldId, scenarioId, stepIndex, simulationTime, units, events, actionResults).fields() + mapOf(
        "playerId" to playerId,
        "visionEpoch" to vision?.epoch,
        "knowledge" to knowledge?.cells?.map { mapOf("visible" to it.visible,
            "blocked" to it.lastKnownBlocked, "gid" to it.lastKnownGroundGid) },
    )
