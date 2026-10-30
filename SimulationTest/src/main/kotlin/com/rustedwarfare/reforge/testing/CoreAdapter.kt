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
            "produce" -> ProduceAction(id, actor, unit, value.id("productionTypeId"), value.id("unitNonce"), context.world)
            "cancel-production" -> CancelProductionAction(id, actor, unit, value.id("productionTypeId"), context.world)
            "attack" -> AttackAction(id, actor, unit, value.id("targetUnitId"), context.world)
            "patrol" -> PatrolAction(id, actor, unit, position(value.objectAt("target")), value.required("append").jsonPrimitive.boolean, context.world)
            "upgrade" -> UpgradeAction(id, actor, unit, value.id("upgradeId"), context.world)
            "cancel-upgrade" -> CancelUpgradeAction(id, actor, unit, value.id("upgradeId"), context.world)
            else -> throw InputError("Unsupported action")
        }
        return context.submit(action)
    }
    fun advance(step: Step) = context.advance(step)
    fun observe() = context.observe()
    // The host archive labels these as player views; all fields come from the authorized core DTO.
    fun playerViews(): JsonObject? = input.scenarioDocument["automaticVision"]?.jsonObject?.let { rules ->
        JsonObject(rules.arrayAt("observers").associate { observer ->
            val id = observer.jsonPrimitive.content
            val view = context.observePlayer(context.world, id)
            val capabilities = if (input.core.combatContract == null && input.core.economyContract == null) emptyMap() else mapOf(
                "units" to array(view.units.map { it.json(true) }), "balance" to view.balance?.let(::n),
                "credits" to view.credits?.let(::n),
                "factoryQueues" to JsonObject(view.factoryQueues.associate { it.factoryId to array(it.items.map { item -> obj("typeId" to s(item.typeId), "progress" to n(item.progress)) }) }),
                "upgradeQueues" to JsonObject(view.upgradeQueues.associate { it.unitId to array(it.items.map { item -> obj("actionKey" to s(item.actionKey), "targetTypeId" to s(item.targetTypeId), "progress" to n(item.progress)) }) }))
            id to JsonObject(obj("playerId" to s(view.playerId), "worldId" to s(view.worldId),
                "stepIndex" to n(view.stepIndex), "simulationTime" to n(view.simulationTime),
                "visibleUnitIds" to array(view.units.map { s(it.unitId) }),
                "actionResults" to array(view.actionResults.map { it.json() }),
                "events" to array(view.events.map { it.json() }),
                "knowledge" to view.knowledge?.let { knowledge -> obj(
                    "width" to n(knowledge.width), "height" to n(knowledge.height), "revision" to n(knowledge.revision),
                    "cells" to JsonObject(knowledge.cells.mapIndexed { index, cell ->
                        "c${index % knowledge.width}r${index / knowledge.width}" to obj("visible" to b(cell.visible),
                            "lastKnownBlocked" to cell.lastKnownBlocked?.let(::b),
                            "lastKnownGroundGid" to cell.lastKnownGroundGid?.let(::n))
                    }.toMap())) }).toMutableMap().apply { capabilities.forEach { (key, value) -> put(key, value ?: JsonNull) } })
        })
    }
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
internal fun Observation.json(): JsonObject = JsonObject(obj(
    "sampleId" to s(sampleId), "worldId" to s(worldId), "scenarioId" to s(scenarioId),
    "stepIndex" to n(stepIndex), "simulationTime" to n(simulationTime),
    "units" to array(units.map { it.json(combat != null || economy != null) }),
    "events" to array(events.map { it.json() }), "actionResults" to array(actionResults.map { it.json() }),
).toMutableMap().apply {
    economy?.let { state -> put("economy", obj("balances" to JsonObject(state.balances.mapValues { n(it.value) }),
        "queues" to JsonObject(state.queuedProduction.mapValues { (_, items) -> array(items.map { item -> obj(
            "typeId" to s(item.typeId), "unitNonce" to s(item.unitNonce), "progress" to n(item.progress), "cost" to n(item.cost), "rate" to n(item.rate)) }) }))) }
    production?.let { state -> put("production", obj("lastBirthSerial" to n(state.lastBirthSerial), "units" to array(state.units.map { item -> obj(
        "unitId" to s(item.unitId), "producerId" to s(item.producerId), "objectId" to n(item.objectId), "birthRemaining" to n(item.birthRemaining)) }))) }
    upgrades?.let { state -> put("upgrades", JsonObject(state.queues.mapValues { (_, items) -> array(items.map { item -> obj(
        "actionKey" to s(item.actionKey), "targetTypeId" to s(item.targetTypeId), "progress" to n(item.progress), "cost" to n(item.cost), "rate" to n(item.rate)) }) })) }
})
internal fun UnitObservation.json(capabilities: Boolean): JsonObject = JsonObject(obj(
    "unitId" to s(unitId), "ownerPlayerId" to s(ownerPlayerId), "typeId" to s(typeId),
    "position" to position.json(), "heading" to n(heading), "speed" to n(speed),
    "angularVelocity" to n(angularVelocity), "moveTarget" to moveTarget?.json(),
    "pathPoints" to array(pathPoints.map { it.json() }), "movementState" to s(movementState.name),
).toMutableMap().apply { if (capabilities) putAll(mapOf("health" to (health?.let(::n) ?: JsonNull),
    "maxHealth" to (maxHealth?.let(::n) ?: JsonNull), "alive" to b(alive), "attackTargetId" to (attackTargetId?.let(::s) ?: JsonNull),
    "pointDefense" to (pointDefense?.let { obj("energy" to n(it.energy), "exhausted" to b(it.exhausted)) } ?: JsonNull))) })
