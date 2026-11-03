package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.PlayerObservation
import java.util.Collections
import kotlin.math.atan2
import kotlin.math.hypot

data class MechLegFrame(val index: Int, val anchorX: Float, val anchorY: Float,
    val footX: Float, val footY: Float, val length: Float, val rotation: Float, val footRotation: Float)

data class MechUnitFrame(val unitId: String, val rotation: Float, val legs: List<MechLegFrame>,
    val bodyX: Float, val bodyY: Float)

/** Geometry consumes only the caller's already-filtered observation; height is presentation-only. */
object MechPresentation {
    fun project(observation: PlayerObservation, projection: MapProjection): List<MechUnitFrame> {
        val mainBodies = observation.weapons.filter { it.weaponName == "mainBody" }.associateBy { it.unitId }
        return frozen(observation.units.filter { it.alive }.mapNotNull { unit -> unit.mechPose?.let { pose ->
            val weapon = mainBodies[unit.unitId]
            val origin = weapon?.pivot ?: unit.position
            val bodyX = projection.x(origin.x); val bodyY = projection.y(origin.y)
            require(bodyX.isFinite() && bodyY.isFinite()) { "Non-finite projected mech body" }
            MechUnitFrame(unit.unitId, finite(-(weapon?.turretHeading ?: pose.forwardHeading)), frozen(pose.legs.map { leg ->
                val ax = projection.x(leg.anchor.x); val ay = projection.y(leg.anchor.y)
                val fx = projection.x(leg.foot.x); val fy = projection.y(leg.foot.y - leg.height)
                require(listOf(ax, ay, fx, fy).all { it.isFinite() }) { "Non-finite projected mech geometry" }
                val dx = (fx - ax).toDouble(); val dy = (fy - ay).toDouble()
                MechLegFrame(leg.index, ax, ay, fx, fy, finite(hypot(dx, dy)),
                    finite(Math.toDegrees(atan2(dy, dx))), finite(-leg.heading))
            }), bodyX, bodyY)
        } })
    }

    private fun finite(value: Double): Float = value.toFloat().also { require(it.isFinite()) }
    private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
