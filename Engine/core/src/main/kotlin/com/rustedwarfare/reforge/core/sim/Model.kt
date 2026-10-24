package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.UnitDefinition
import java.util.Collections
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.KnowledgeSnapshot
import com.rustedwarfare.reforge.core.knowledge.VisionSnapshot

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

data class Player(val playerId: String, val control: PlayerControl, val teamId: String? = null)

/** Opaque local capability. Display IDs may repeat across independent runs; identity may not. */
class WorldHandle internal constructor(val id: String)

/**
 * [pathPointRadius] controls independent planner waypoint consumption.
 * [targetRadius] is retained as a validated legacy input for old scenario/DTO identities.
 * Ordinary Move completion now uses the near-target time rule (7/16/36); the final
 * propulsion waypoint's zero-target gate is independently fixed at squared distance 16.
 */
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
    val automaticVision: KnowledgeRules? = null,
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
    /** Steering-rate state (reference cg), applied only when body steering is invoked. */
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

class PlayerObservation internal constructor(
    val sampleId: String,
    val worldId: String,
    val scenarioId: String,
    val playerId: String,
    val stepIndex: Long,
    val simulationTime: Double,
    val vision: VisionSnapshot?,
    val knowledge: KnowledgeSnapshot?,
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
    /** Retained steering-rate state; an idle unit does not integrate it into heading. */
    var angularVelocity: Double = 0.0,
    var moveTarget: Position? = null,
    var pathPoints: MutableList<Position> = mutableListOf(),
    var movementState: MovementState = MovementState.IDLE,
    var originMoveId: String? = null,
    var originAtStep: Long? = null,
    var lastPlannedKnowledgeRevision: Long = -1L,
    var nearTargetTime: Double = 0.0,
    var localMotion: LocalMotionSegment? = null,
    var localProgressTime: Double = 0.0,
    var terrainCorrectionTime: Double = 0.0,
    var terrainClearSteps: Int = 0,
) {
    fun ensureLocalMotion(): LocalMotionSegment {
        val first = pathPoints.first()
        return localMotion?.takeIf { it.anchor == first }
            ?: LocalMotionSegment.create(position, first, definition.movement).also { localMotion = it; localProgressTime = 0.0 }
    }

    /** End the command while preserving linear/angular speed for the existing coasting rule. */
    fun cancelRoute() {
        moveTarget = null
        pathPoints.clear()
        clearMoveOrigin()
        movementState = if (speed != 0.0) MovementState.DECELERATING else MovementState.IDLE
    }

    /** Replanning and repair preserve command identity, completion time and terrain history. */
    fun adoptRoute(points: List<Position>, knowledgeRevision: Long) {
        pathPoints = points.toMutableList()
        localMotion = null
        localProgressTime = 0.0
        lastPlannedKnowledgeRevision = knowledgeRevision
        movementState = MovementState.MOVING
    }

    fun clearMoveOrigin() {
        originMoveId = null
        originAtStep = null
        lastPlannedKnowledgeRevision = -1L
        nearTargetTime = 0.0
        localMotion = null
        localProgressTime = 0.0
    }
}

internal fun <T> immutable(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
