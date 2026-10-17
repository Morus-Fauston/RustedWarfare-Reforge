package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import java.util.Collections

class SimulationContext private constructor(private val policies: ExperimentalPolicies = ExperimentalPolicies()) {
    private var activeWorld: WorldHandle? = null
    val world: WorldHandle get() = checkNotNull(activeWorld) { "Simulation is closed" }
    private var scenario: Scenario? = null
    private var units: MutableMap<String, MutableUnit> = linkedMapOf()
    private var worldGeneration: Long = 0
    private var closed: Boolean = true
    private var stepIndex: Long = 0
    private var simulationTime: Double = 0.0
    private var hasAdvanced = false
    private var revision = 0L
    private var pendingEvents: MutableList<Event> = mutableListOf()
    private var pendingActionResults: MutableList<ActionResult> = mutableListOf()
    private var latestObservation: Observation? = null
    private val scheduledActions = mutableListOf<Action>()
    private val actionIds = mutableSetOf<String>()

    companion object {
        fun initialize(scenario: Scenario): InitResult = SimulationContext().initializeInternal(scenario)
    }

    fun initialize(scenario: Scenario): InitResult = initializeInternal(scenario)

    fun reopen(scenario: Scenario): InitResult = initializeInternal(scenario)

    fun close(): CloseResult {
        if (closed) return CloseResult.AlreadyClosed
        closed = true
        activeWorld = null
        this.scenario = null
        units = linkedMapOf()
        pendingEvents.clear()
        pendingActionResults.clear()
        latestObservation = null
        scheduledActions.clear()
        actionIds.clear()
        return CloseResult.Closed
    }

    fun submit(action: Action): ActionResult {
        if (closed) return ActionResult.Rejected(action.actionId, SimulationError("closed", "Simulation is closed"))
        if (action.world !== activeWorld) return reject(action, "stale-world", "Action belongs to a different world")
        if (!Regex("[A-Za-z0-9._:-]+").matches(action.actionId)) return reject(action, "invalid-id", "Invalid action ID")
        if (!actionIds.add(action.actionId)) return reject(action, "duplicate-action", "Action ID has already been submitted")
        val currentScenario = checkNotNull(scenario)
        if (currentScenario.players.none { it.playerId == action.actorPlayerId }) {
            return reject(action, "unknown-actor", "Actor is not a declared player")
        }
        val unit = units[action.unitId]
            ?: return reject(action, "unknown-unit", "Unknown unit '${action.unitId}'")
        if (action !is SelectAction && unit.ownerPlayerId != action.actorPlayerId) {
            return reject(action, "not-owner", "Player '${action.actorPlayerId}' does not own '${action.unitId}'")
        }
        if (action !is SelectAction && currentScenario.players.single { it.playerId == action.actorPlayerId }.control != PlayerControl.LOCAL) {
            return reject(action, "actor-not-controlled", "Player has no local control")
        }
        if (action is MoveAction && (!action.target.x.isFinite() || !action.target.y.isFinite())) {
            return reject(action, "invalid-target", "Target coordinates must be finite")
        }
        val scheduledStep = action.atStep
        if (scheduledStep != null) {
            if (scheduledStep < 0 || (hasAdvanced && scheduledStep <= stepIndex)) {
                return reject(action, "past-step", "Scheduled action must belong to a future step")
            }
            scheduledActions += action
            return ActionResult.Accepted(action.actionId, queuedForStep = scheduledStep)
        }
        return applyAction(action)
    }

    private fun applyAction(action: Action): ActionResult {
        val currentScenario = checkNotNull(scenario)
        val unit = units.getValue(action.unitId)
        val result = when (action) {
            is SelectAction -> ActionResult.Accepted(action.actionId)
            is StopAction -> {
                unit.moveTarget = null
                unit.pathPoints.clear()
                unit.movementState = if (unit.speed != 0.0 || unit.angularVelocity != 0.0) MovementState.DECELERATING else MovementState.IDLE
                ActionResult.Accepted(action.actionId, listOf(Event("movement-cancelled", unit.unitId)))
            }
            is MoveAction -> {
                if (!action.target.x.isFinite() || !action.target.y.isFinite()) {
                    return reject(action, "invalid-target", "Target coordinates must be finite")
                }
                val path = policies.path.findPath(currentScenario.map, unit.position, action.target, unit.definition.radius)
                if (path == null) {
                    unit.moveTarget = null
                    unit.pathPoints.clear()
                    unit.movementState = if (unit.speed != 0.0 || unit.angularVelocity != 0.0) MovementState.DECELERATING else MovementState.IDLE
                    ActionResult.Accepted(
                        action.actionId,
                        listOf(Event("movement-cancelled", unit.unitId), Event("path-unreachable", unit.unitId)),
                    )
                } else {
                    unit.moveTarget = action.target
                    unit.pathPoints = path.toMutableList()
                    unit.movementState = MovementState.MOVING
                    ActionResult.Accepted(action.actionId, listOf(Event("movement-started", unit.unitId)))
                }
            }
        }
        pendingActionResults += result
        pendingEvents += result.events
        invalidateObservation()
        return result
    }

    fun advance(step: Step): Observation {
        check(!closed) { "Simulation is closed" }
        val currentScenario = checkNotNull(scenario)
        require(step.stepIndex >= 0 && (!hasAdvanced || step.stepIndex > stepIndex)) {
            "stepIndex must strictly increase from $stepIndex: ${step.stepIndex}"
        }
        policies.time.validate(step, currentScenario.timeModel)
        require(scheduledActions.none { checkNotNull(it.atStep) < step.stepIndex }) {
            "advance cannot skip a scheduled action step"
        }
        val actions = scheduledActions.filter { it.atStep == step.stepIndex }
        val previousUnits = units
        val previousEvents = pendingEvents
        val previousResults = pendingActionResults
        val previousRevision = revision
        val previousObservation = latestObservation
        // Calculate on copies so a numerical failure cannot commit half of a multi-unit step.
        units = units.mapValuesTo(linkedMapOf()) { (_, unit) -> unit.copy(pathPoints = unit.pathPoints.toMutableList()) }
        pendingEvents = pendingEvents.toMutableList()
        pendingActionResults = pendingActionResults.toMutableList()
        try {
            actions.forEach { applyAction(it) }
            for (unit in units.values.sortedBy { it.unitId }) updateUnit(unit, currentScenario.map, step.delta)
        } catch (error: ArithmeticException) {
            units = previousUnits
            pendingEvents = previousEvents
            pendingActionResults = previousResults
            revision = previousRevision
            latestObservation = previousObservation
            throw error
        }
        scheduledActions.removeAll(actions.toSet())
        stepIndex = step.stepIndex
        hasAdvanced = true
        simulationTime += step.delta
        invalidateObservation()
        val observation = recordObservation()
        pendingEvents.clear()
        pendingActionResults.clear()
        return observation
    }

    fun observe(): Observation {
        check(!closed) { "Simulation is closed" }
        return latestObservation ?: recordObservation()
    }

    private fun initializeInternal(nextScenario: Scenario): InitResult {
        if (!closed) return InitResult.Rejected(SimulationError("already-open", "Close the current world before initializing"))
        val error = validate(nextScenario)
        if (error != null) return InitResult.Rejected(error)
        worldGeneration += 1
        activeWorld = WorldHandle("${nextScenario.scenarioId}:$worldGeneration")
        scenario = nextScenario.copy(
            unitDefinitions = Collections.unmodifiableMap(LinkedHashMap(nextScenario.unitDefinitions)),
            players = immutable(nextScenario.players),
            units = immutable(nextScenario.units),
        )
        units = linkedMapOf()
        nextScenario.units.sortedBy { it.unitId }.forEach { spawn ->
            units[spawn.unitId] = MutableUnit(
                unitId = spawn.unitId,
                ownerPlayerId = spawn.ownerPlayerId,
                typeId = spawn.typeId,
                definition = checkNotNull(nextScenario.unitDefinitions[spawn.typeId]),
                position = spawn.position,
                heading = policies.coordinates.normalize(spawn.heading),
            )
        }
        closed = false
        stepIndex = 0
        simulationTime = 0.0
        hasAdvanced = false
        revision = 0
        pendingEvents = mutableListOf()
        pendingActionResults = mutableListOf()
        latestObservation = null
        scheduledActions.clear()
        actionIds.clear()
        return InitResult.Accepted(this)
    }

    private fun validate(input: Scenario): SimulationError? {
        val id = Regex("[A-Za-z0-9._:-]+")
        val ids = listOf(input.scenarioId, input.coordinateModel.id, input.timeModel.id) +
            input.players.map { it.playerId } + input.units.map { it.unitId } + input.unitDefinitions.keys
        if (ids.any { !id.matches(it) }) return SimulationError("invalid-id", "Identifiers must be nonempty ASCII IDs")
        if (input.players.map { it.playerId }.toSet().size != input.players.size) return SimulationError("duplicate-id", "Duplicate player id")
        if (input.units.map { it.unitId }.toSet().size != input.units.size) return SimulationError("duplicate-id", "Duplicate unit id")
        val players = input.players.map { it.playerId }.toSet()
        if (input.units.any { it.ownerPlayerId !in players }) return SimulationError("bad-reference", "Unit owner is not a declared player")
        if (input.units.any { it.typeId !in input.unitDefinitions }) return SimulationError("bad-reference", "Unit type is not declared")
        if (input.map.widthTiles <= 0 || input.map.heightTiles <= 0) return SimulationError("invalid-map", "Map dimensions must be positive")
        if (input.map.tileWidth != 20 || input.map.tileHeight != 20 ||
            input.map.blocking.widthTiles != input.map.widthTiles || input.map.blocking.heightTiles != input.map.heightTiles) {
            return SimulationError("unsupported-map", "Expected matching blocking dimensions and 20-unit square tiles")
        }
        if (!policies.coordinates.supports(input.coordinateModel)) {
            return SimulationError("unsupported-coordinate-model", "Coordinate model is not supported by this contract")
        }
        if (!policies.time.supports(input.timeModel)) return SimulationError("unsupported-time-model", "Unsupported time model")
        val contract = input.movementContract ?: return SimulationError("missing-contract", "Movement contract must be explicit")
        if (!contract.pathPointRadius.isFinite() || contract.pathPointRadius <= 0.0 ||
            !contract.targetRadius.isFinite() || contract.targetRadius <= 0.0) {
            return SimulationError("invalid-contract", "Capture radii must be finite and positive")
        }
        for (definition in input.unitDefinitions.values) {
            val m = definition.movement
            val numbers = listOf(definition.radius, m.moveSpeed, m.moveAccelerationSpeed, m.moveDecelerationSpeed,
                m.maxTurnSpeed, m.turnAcceleration)
            if (numbers.any { !it.isFinite() || it < 0.0 } || definition.radius >= input.map.tileWidth || m.slidingMode || m.ignoringBody) {
                return SimulationError("unsupported-unit", "Expected finite nonnegative, non-sliding movement and radius below tile size")
            }
        }
        for (spawn in input.units) {
            val p = spawn.position
            if (!spawn.heading.isFinite() || !p.x.isFinite() || !p.y.isFinite() || p.x < 0 || p.y < 0 ||
                p.x >= input.map.widthTiles.toDouble() * input.map.tileWidth || p.y >= input.map.heightTiles.toDouble() * input.map.tileHeight ||
                policies.terrain.intersects(input.map, p, checkNotNull(input.unitDefinitions[spawn.typeId]).radius)) {
                return SimulationError("invalid-spawn", "Spawn must be finite, within the map and outside blocked terrain")
            }
        }
        return null
    }

    private fun reject(action: Action, code: String, message: String): ActionResult.Rejected {
        val result = ActionResult.Rejected(action.actionId, SimulationError(code, message))
        pendingActionResults += result
        invalidateObservation()
        return result
    }

    private fun invalidateObservation() {
        revision += 1
        latestObservation = null
    }

    private fun updateUnit(unit: MutableUnit, map: MapDefinition, delta: Double) {
        val movement = unit.definition.movement
        val contract = checkNotNull(scenario?.movementContract)
        val target = unit.moveTarget
        var speedTarget = 0.0
        if (target != null) {
            val steeringTarget = unit.pathPoints.first()
            val finalPoint = unit.pathPoints.size == 1
            val d2 = policies.coordinates.distanceSquared(unit.position, steeringTarget)
            val diff = policies.steering.turn(unit, steeringTarget, delta, policies.coordinates)
            speedTarget = policies.propulsion.targetSpeed(movement, unit.speed, d2, diff, finalPoint, contract.targetRadius)
            if (!finalPoint && policies.capture.inside(d2, contract.pathPointRadius)) {
                unit.pathPoints.removeAt(0)
            } else if (finalPoint && policies.capture.inside(d2, contract.targetRadius)) {
                unit.moveTarget = null
                unit.pathPoints.clear()
                speedTarget = 0.0
                pendingEvents += Event("movement-arrived", unit.unitId)
            }
        } else {
            unit.angularVelocity = approach(unit.angularVelocity, 0.0, movement.turnAcceleration * delta)
        }

        val rate = if (unit.speed < speedTarget) movement.moveAccelerationSpeed else movement.moveDecelerationSpeed
        unit.speed = approach(unit.speed, speedTarget, rate * delta)

        val displacement = policies.coordinates.displacement(unit.heading, unit.speed * delta)
        val candidate = Position(unit.position.x + displacement.x, unit.position.y + displacement.y)
        if (!candidate.x.isFinite() || !candidate.y.isFinite()) {
            throw ArithmeticException("Non-finite movement candidate for ${unit.unitId}")
        }
        if (policies.terrain.intersects(map, candidate, unit.definition.radius)) {
            unit.speed = 0.0
            unit.angularVelocity = 0.0
            unit.movementState = MovementState.BLOCKED
            pendingEvents += Event("movement-blocked", unit.unitId)
            return
        }
        unit.position = candidate
        unit.movementState = when {
            unit.moveTarget != null -> MovementState.MOVING
            unit.speed != 0.0 || unit.angularVelocity != 0.0 -> MovementState.DECELERATING
            else -> MovementState.IDLE
        }
    }

    private fun recordObservation(): Observation {
        val currentScenario = checkNotNull(scenario)
        val observation = Observation(
            sampleId = "${currentScenario.scenarioId}:$worldGeneration:${if (hasAdvanced) "step$stepIndex" else "initial"}:$revision",
            worldId = "${currentScenario.scenarioId}:$worldGeneration",
            scenarioId = currentScenario.scenarioId,
            stepIndex = stepIndex,
            simulationTime = simulationTime,
            units = units.values.sortedBy { it.unitId }.map { unit ->
                UnitObservation(
                    unitId = unit.unitId,
                    ownerPlayerId = unit.ownerPlayerId,
                    typeId = unit.typeId,
                    position = unit.position,
                    heading = unit.heading,
                    speed = unit.speed,
                    angularVelocity = unit.angularVelocity,
                    moveTarget = unit.moveTarget,
                    pathPoints = immutable(unit.pathPoints),
                    movementState = unit.movementState,
                )
            },
            events = pendingEvents,
            actionResults = pendingActionResults,
        )
        latestObservation = observation
        return observation
    }

}

