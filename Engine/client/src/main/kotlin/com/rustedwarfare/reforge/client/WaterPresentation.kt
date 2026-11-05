package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.PlayerObservation
import java.util.Collections

data class WaterUnitFrame(val unitId: String, val bodyX: Float, val bodyY: Float, val bodyRotation: Float)

/** Current authorized ordinary water-body geometry, including its sinking corpse. */
object WaterPresentation {
    fun project(observation: PlayerObservation, projection: MapProjection): List<WaterUnitFrame> =
        Collections.unmodifiableList(observation.units.mapNotNull { unit ->
            if (unit.waterPose == null) return@mapNotNull null
            val center = unit.displayedBodyPosition()
            val x = projection.x(center.x)
            val y = projection.y(center.y)
            val rotation = -unit.heading.toFloat()
            if (listOf(x, y, rotation).any { !it.isFinite() }) null
            else WaterUnitFrame(unit.unitId, x, y, rotation)
        })
}
