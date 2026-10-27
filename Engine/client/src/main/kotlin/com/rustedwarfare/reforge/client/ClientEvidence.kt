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
        "health" to unit.health, "maxHealth" to unit.maxHealth, "alive" to unit.alive,
        "constructionProgress" to unit.constructionProgress, "constructionTargetId" to unit.constructionTargetId,
        "attackTargetId" to unit.attackTargetId, "turretHeading" to unit.turretHeading, "cooldown" to unit.cooldown,
        "height" to unit.height,
        "pointDefense" to unit.pointDefense?.let { mapOf("energy" to it.energy, "exhausted" to it.exhausted) },
        "tasks" to unit.tasks.map { task -> mapOf("kind" to task.kind.name.lowercase(),
            "target" to task.target?.fields(), "targetUnitId" to task.targetUnitId) +
                (task.buildingTypeId?.let { mapOf("buildingTypeId" to it) } ?: emptyMap()) },
    ) },
    "events" to events.map { mapOf("kind" to it.kind, "unitId" to it.unitId) },
    "actionResults" to actionResults.map { it.fields() },
    "construction" to construction?.let { value -> mapOf("clock" to value.clock,
        "buildings" to value.buildings.map { mapOf("unitId" to it.unitId, "objectId" to it.objectId,
            "updateKey" to it.updateKey, "progress" to it.progress) },
        "workers" to value.workers.map { mapOf("unitId" to it.unitId, "elapsed" to it.elapsed,
            "retryTime" to it.retryTime, "preparation" to it.preparation, "working" to it.working,
            "previousTargetActive" to it.previousTargetActive) }, "nonces" to value.nonces) },
)

internal fun PlayerObservation.fields(): Map<String, Any?> =
    Observation(sampleId, worldId, scenarioId, stepIndex, simulationTime, units, events, actionResults).fields() + mapOf(
        "playerId" to playerId,
        "visionEpoch" to vision?.epoch,
        "projectiles" to projectiles.map { mapOf("token" to it.token, "multiplicity" to it.multiplicity,
            "position" to it.position.fields(), "heading" to it.heading, "height" to it.height, "parentToken" to it.parentToken) },
        "projectileEvents" to projectileEvents.map { mapOf("token" to it.token, "kind" to it.kind,
            "occurrences" to it.occurrences, "position" to it.position.fields(), "heading" to it.heading,
            "height" to it.height, "sourceUnitId" to it.sourceUnitId, "targetUnitId" to it.targetUnitId,
            "damage" to it.damage, "parentToken" to it.parentToken, "interceptorUnitId" to it.interceptorUnitId) },
        "knowledge" to knowledge?.cells?.map { mapOf("visible" to it.visible,
            "blocked" to it.lastKnownBlocked, "gid" to it.lastKnownGroundGid) },
    ) + (balance?.let { mapOf("balance" to it, "factoryQueues" to factoryQueues.map { factory ->
        mapOf("factoryId" to factory.factoryId, "items" to factory.items.map { item ->
            mapOf("typeId" to item.typeId, "progress" to item.progress) }) },
        "upgradeQueues" to upgradeQueues.map { queue -> mapOf("unitId" to queue.unitId, "items" to queue.items.map { item ->
            mapOf("actionKey" to item.actionKey, "targetTypeId" to item.targetTypeId, "progress" to item.progress) }) }) } ?: emptyMap()) +
        (credits?.let { mapOf("credits" to it) } ?: emptyMap())
