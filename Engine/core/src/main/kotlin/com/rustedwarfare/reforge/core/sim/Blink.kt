package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlinkProfile
import com.rustedwarfare.reforge.core.content.MapDefinition
import kotlin.math.ceil
import kotlin.math.floor

object BlinkTarget {
    fun cell(point: Position, map: MapDefinition): Pair<Int, Int> =
        floor(point.x.toFloat() / map.tileWidth).toInt() to floor(point.y.toFloat() / map.tileHeight).toInt()

    fun check(source: Position, point: Position, range: Double, map: MapDefinition): SimulationError? {
        val x = point.x.toFloat()
        val y = point.y.toFloat()
        if (!x.isFinite() || !y.isFinite()) return SimulationError("invalid-action-target", "Ground target must be finite")
        if (GroundMath.square(x, y, source.x.toFloat(), source.y.toFloat()) > range * range)
            return SimulationError("action-target-out-of-range", "Location too far")
        val (column, row) = cell(point, map)
        if (column !in 0 until map.widthTiles || row !in 0 until map.heightTiles || map.hoverBlocking.isBlocked(column, row))
            return SimulationError("action-target-impassable", "Invalid map location (must be passable by HOVER)")
        return null
    }
}

internal object Blink {
    fun validateTarget(source: Position, point: Position, profile: BlinkProfile, collision: MapDefinition): SimulationError? =
        BlinkTarget.check(source, point, profile.range, collision)

    fun uiCooldown(ticks: Double) = ceil(maxOf(0.0, ticks) * 1000.0 / 60.0).toInt()

    fun teleport(state: StepState, unit: MutableUnit, target: Position) {
        val source = unit.position
        val dx = target.x - source.x
        val dy = target.y - source.y
        unit.position = target
        unit.mechState = unit.mechState?.let { gait ->
            gait.copy(feet = immutable(gait.feet.map { foot ->
                foot.copy(position = Position(foot.position.x + dx, foot.position.y + dy))
            }), gaitPosition = unit.position)
        }
        for (id in unit.attachmentIds.values) {
            val child = state.units.getValue(id)
            UnitAttachments.synchronize(unit, child, checkNotNull(child.attachment).slot)
        }
        state.temporaryVision = state.temporaryVision + TemporaryVision(unit.ownerPlayerId, source)
        state.pendingEvents += Event("teleported", unit.unitId)
    }
}
