package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.UnitDefinition
import java.util.Collections

data class Position(val x: Double, val y: Double)

data class CoordinateModel(
    val id: String,
    val space: String,
    val xAxis: String,
    val yAxis: String,
    val origin: String,
    val unit: String,
    val valueType: String,
    val precision: String,
)

data class TimeModel(val id: String, val deltaUnit: String, val precision: String, val stepRule: String)

enum class PlayerControl { LOCAL, NONE }

data class Player(val playerId: String, val control: PlayerControl)

/** Opaque local capability. Display IDs may repeat across independent runs; identity may not. */
class WorldHandle internal constructor(val id: String)

data class MovementContract(val pathPointRadius: Double, val targetRadius: Double)

data class UnitSpawn(
    val unitId: String,
    val ownerPlayerId: String,
    val typeId: String,
    val position: Position,
    val heading: Double,
)

data class Scenario(
    val scenarioId: String,
    val map: MapDefinition,
    val unitDefinitions: Map<String, UnitDefinition>,
    val players: List<Player>,
    val units: List<UnitSpawn>,
    val coordinateModel: CoordinateModel,
    val timeModel: TimeModel,
    val movementContract: MovementContract?,
)

data class Step(val stepIndex: Long, val delta: Double, val deltaUnit: String)

enum class MovementState { IDLE, MOVING, DECELERATING, BLOCKED }

data class UnitObservation(
    val unitId: String,
    val ownerPlayerId: String,
    val typeId: String,
    val position: Position,
    val heading: Double,
    val speed: Double,
    val angularVelocity: Double,
    val moveTarget: Position?,
    val pathPoints: List<Position>,
    val movementState: MovementState,
)

data class Event(val kind: String, val unitId: String? = null)

class Observation(
    val sampleId: String,
    val worldId: String,
    val scenarioId: String,
    val stepIndex: Long,
    val simulationTime: Double,
    units: List<UnitObservation>,
    events: List<Event>,
    actionResults: List<ActionResult>,
) {
    val units: List<UnitObservation> = immutable(units)
    val events: List<Event> = immutable(events)
    val actionResults: List<ActionResult> = immutable(actionResults)
}

data class SimulationError(val code: String, val message: String)

sealed interface InitResult {
    data class Accepted(val context: SimulationContext) : InitResult
    data class Rejected(val error: SimulationError) : InitResult
}

sealed interface ActionResult {
    val actionId: String

    data class Accepted(
        override val actionId: String,
        private val rawEvents: List<Event> = emptyList(),
        val queuedForStep: Long? = null,
    ) : ActionResult {
        val events: List<Event> = immutable(rawEvents)
    }

    data class Rejected(
        override val actionId: String,
        val error: SimulationError,
    ) : ActionResult
}

sealed interface CloseResult {
    data object Closed : CloseResult
    data object AlreadyClosed : CloseResult
}

internal data class MutableUnit(
    val unitId: String,
    val ownerPlayerId: String,
    val typeId: String,
    val definition: UnitDefinition,
    var position: Position,
    var heading: Double,
    var speed: Double = 0.0,
    var angularVelocity: Double = 0.0,
    var moveTarget: Position? = null,
    var pathPoints: MutableList<Position> = mutableListOf(),
    var movementState: MovementState = MovementState.IDLE,
)

internal fun <T> immutable(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
