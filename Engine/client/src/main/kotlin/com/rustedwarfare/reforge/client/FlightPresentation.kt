package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.PlayerObservation
import com.rustedwarfare.reforge.core.sim.UnitObservation
import java.util.Collections

/** Caller supplies an authorized visible unit; this is also the click and box-selection center. */
internal fun UnitObservation.displayedBodyPosition(): Position =
    if (flightPose != null || waterPose != null || hoverPose != null || amphibiousJet != null || mechPose != null || attachment != null)
        Position(position.x, position.y - height) else position

data class FlightUnitFrame(val unitId: String, val bodyX: Float, val bodyY: Float, val bodyRotation: Float,
    val rotorX: Float, val rotorY: Float, val rotorRotation: Float, val drawShadow: Boolean)

/** Only presentation geometry from the caller's authorized view; the core retains ground coordinates. */
object FlightPresentation {
    fun project(observation: PlayerObservation, projection: MapProjection): List<FlightUnitFrame> =
        // A visible wreck with height still needs body projection. Cargo is owner-private.
        Collections.unmodifiableList(observation.units.filter { it.alive || it.height != 0.0 }.mapNotNull { unit ->
            unit.flightPose?.let { pose ->
                val center = unit.displayedBodyPosition()
                val x = projection.x(center.x)
                val y = projection.y(center.y)
                val rotorY = projection.y(center.y - pose.rotorHeightOffset)
                val bodyRotation = -unit.heading.toFloat()
                val rotorRotation = -pose.rotorHeading.toFloat()
                // A valid double-precision flight can be outside the float renderer's range.
                if (listOf(x, y, rotorY, bodyRotation, rotorRotation).any { !it.isFinite() }) null
                else FlightUnitFrame(unit.unitId, x, y, bodyRotation, x, rotorY, rotorRotation,
                    unit.alive && unit.height > 0.0)
            }
        })
}
