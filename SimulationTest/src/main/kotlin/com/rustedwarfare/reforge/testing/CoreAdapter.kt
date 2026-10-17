package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.core.sim.*
import kotlinx.serialization.json.*

internal class CoreAdapter(private val input: LoadedScenario) : AutoCloseable {
    private val context = when (val result = SimulationContext.initialize(input.core)) {
        is InitResult.Accepted -> result.context
        is InitResult.Rejected -> throw CoreFailure("Initialization rejected: ${result.error}")
    }
    fun initial(): Observation = context.observe().also { observation ->
        val expected = input.core.units.associateBy { it.unitId }
        if (observation.scenarioId != input.core.scenarioId || observation.simulationTime != 0.0 ||
            observation.units.size != expected.size || observation.events.isNotEmpty() || observation.actionResults.isNotEmpty() ||
            observation.units.any { actual -> expected[actual.unitId]?.let { spawn ->
                actual.ownerPlayerId != spawn.ownerPlayerId || actual.typeId != spawn.typeId ||
                    actual.position != spawn.position || actual.heading != spawn.heading || actual.speed != 0.0 ||
                    actual.angularVelocity != 0.0 || actual.moveTarget != null || actual.pathPoints.isNotEmpty() ||
                    actual.movementState != MovementState.IDLE
            } ?: true }) throw CoreFailure("Initial observation differs from declared scenario")
    }
    fun submit(value: JsonObject): ActionResult {
        val id = value.id("actionId")
        val actor = value.id("actor")
        val unit = value.id("unitId")
        // Submit at the current explicit boundary; preserve file order and the core's permission checks.
        val action = when (value.text("kind")) {
            "move" -> MoveAction(id, actor, unit, position(value.objectAt("target")), context.world)
            "stop" -> StopAction(id, actor, unit, context.world)
            "select" -> SelectAction(id, actor, unit, context.world)
            else -> throw InputError("Unsupported action")
        }
        return context.submit(action)
    }
    fun advance(step: Step) = context.advance(step)
    fun observe() = context.observe()
    fun reopen(): Observation {
        when (val result = context.reopen(input.core)) {
            is InitResult.Accepted -> Unit
            is InitResult.Rejected -> throw CoreFailure("Reopen rejected: ${result.error}")
        }
        return initial()
    }
    override fun close() { context.close() }
}

internal fun Position.json() = obj("x" to n(x), "y" to n(y))
internal fun Event.json() = obj("kind" to s(kind), "unitId" to unitId?.let(::s))
internal fun ActionResult.json(): JsonObject = when (this) {
    is ActionResult.Accepted -> obj("actionId" to s(actionId), "status" to s("accepted"),
        "queuedForStep" to queuedForStep?.let(::n), "events" to array(events.map { it.json() }))
    is ActionResult.Rejected -> obj("actionId" to s(actionId), "status" to s("rejected"),
        "error" to obj("code" to s(error.code), "message" to s(error.message)))
}
internal fun Observation.json() = obj(
    "sampleId" to s(sampleId), "worldId" to s(worldId), "scenarioId" to s(scenarioId),
    "stepIndex" to n(stepIndex), "simulationTime" to n(simulationTime),
    "units" to array(units.map { unit -> obj(
        "unitId" to s(unit.unitId), "ownerPlayerId" to s(unit.ownerPlayerId), "typeId" to s(unit.typeId),
        "position" to unit.position.json(), "heading" to n(unit.heading), "speed" to n(unit.speed),
        "angularVelocity" to n(unit.angularVelocity), "moveTarget" to unit.moveTarget?.json(),
        "pathPoints" to array(unit.pathPoints.map { it.json() }), "movementState" to s(unit.movementState.name),
    ) }),
    "events" to array(events.map { it.json() }), "actionResults" to array(actionResults.map { it.json() }),
)
