package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.UnitObservation

data class HoverUnitFrame(val bodyX: Float, val bodyY: Float, val rotation: Float,
    val shadowX: Float, val shadowY: Float, val drawShadow: Boolean)

/** The caller supplies an authorized visible unit; selection uses the same elevated center. */
object HoverPresentation {
    fun project(unit: UnitObservation, projection: MapProjection): HoverUnitFrame? {
        if (unit.hoverPose == null) return null
        val center = unit.displayedBodyPosition()
        val values = listOf(projection.x(center.x), projection.y(center.y), -unit.turretHeading.toFloat(),
            projection.x(unit.position.x), projection.y(unit.position.y))
        if (values.any { !it.isFinite() }) return null
        return HoverUnitFrame(values[0], values[1], values[2], values[3], values[4], unit.alive)
    }
}
