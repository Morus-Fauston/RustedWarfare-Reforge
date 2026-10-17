package com.rustedwarfare.reforge.core.sim

/** Test-only observation export; no production serializer or TestLab runner is introduced. */
internal fun traceObservation(caseId: String, phase: String, sample: Observation) {
    fun position(value: Position?): Any? = value?.let { mapOf("x" to it.x, "y" to it.y) }
    fun event(value: Event): Any = mapOf("kind" to value.kind, "unitId" to value.unitId)
    val fields = mapOf(
        "caseId" to caseId, "phase" to phase,
        "sampleId" to sample.sampleId, "worldId" to sample.worldId, "scenarioId" to sample.scenarioId,
        "stepIndex" to sample.stepIndex, "simulationTime" to sample.simulationTime,
        "units" to sample.units.map { unit -> mapOf(
            "unitId" to unit.unitId, "ownerPlayerId" to unit.ownerPlayerId, "typeId" to unit.typeId,
            "position" to position(unit.position), "heading" to unit.heading, "speed" to unit.speed,
            "angularVelocity" to unit.angularVelocity, "moveTarget" to position(unit.moveTarget),
            "pathPoints" to unit.pathPoints.map(::position), "movementState" to unit.movementState.name.lowercase(),
        ) },
        "events" to sample.events.map(::event),
        "actionResults" to sample.actionResults.map { result -> when (result) {
            is ActionResult.Accepted -> mapOf("actionId" to result.actionId, "status" to "accepted",
                "queuedForStep" to result.queuedForStep, "events" to result.events.map(::event))
            is ActionResult.Rejected -> mapOf("actionId" to result.actionId, "status" to "rejected",
                "error" to mapOf("code" to result.error.code, "message" to result.error.message))
        } },
    )
    println("TRACE " + evidenceJson(fields))
}

private fun evidenceJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> buildString {
        append('"')
        value.forEach { character -> when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> if (character.code < 32) append("\\u" + character.code.toString(16).padStart(4, '0')) else append(character)
        } }
        append('"')
    }
    is Double -> { check(value.isFinite()); value.toString() }
    is Number, is Boolean -> value.toString()
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (key, item) -> evidenceJson(key) + ":" + evidenceJson(item) }
    is Collection<*> -> value.joinToString(",", "[", "]", transform = ::evidenceJson)
    else -> error("Unsupported evidence value: ${value.javaClass.name}")
}
