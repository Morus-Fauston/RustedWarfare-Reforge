package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.PlayerObservation
import java.util.Collections
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import com.rustedwarfare.reforge.core.content.MechKind

data class MechShaftFrame(val sourceY: Int, val sourceHeight: Int, val width: Float, val height: Float,
    val x: Float, val y: Float, val rotation: Float, val sourceImageHeight: Int)

data class MechLegFrame(val index: Int, val anchorX: Float, val anchorY: Float,
    val footX: Float, val footY: Float, val length: Float, val rotation: Float, val footRotation: Float,
    val shaft: MechShaftFrame? = null)

data class MechUnitFrame(val unitId: String, val rotation: Float, val legs: List<MechLegFrame>,
    val bodyX: Float, val bodyY: Float, val drawShadow: Boolean = false,
    val shadowX: Float = bodyX, val shadowY: Float = bodyY)

/** Geometry consumes only the caller's already-filtered observation; height is presentation-only. */
object MechPresentation {
    fun project(observation: PlayerObservation, projection: MapProjection,
                resources: SandboxRenderResources? = null): List<MechUnitFrame> {
        val mainBodies = observation.weapons.filter { it.weaponName == "mainBody" }.associateBy { it.unitId }
        return frozen(observation.units.filter { it.alive }.mapNotNull { unit -> unit.mechPose?.let { pose ->
            val appearance = resources?.units?.getValue(unit.typeId)
            val weapon = mainBodies[unit.unitId]
            val origin = weapon?.pivot ?: unit.position
            val bodyX = projection.x(origin.x); val bodyY = projection.y(origin.y - unit.height)
            require(bodyX.isFinite() && bodyY.isFinite()) { "Non-finite projected mech body" }
            MechUnitFrame(unit.unitId, finite(-(weapon?.turretHeading ?: pose.forwardHeading)), frozen(pose.legs.map { leg ->
                val ax = projection.x(leg.anchor.x); val ay = projection.y(leg.anchor.y - unit.height)
                val fx = projection.x(leg.foot.x); val fy = projection.y(leg.foot.y - unit.height - leg.height)
                require(listOf(ax, ay, fx, fy).all { it.isFinite() }) { "Non-finite projected mech geometry" }
                val dx = (fx - ax).toDouble(); val dy = (fy - ay).toDouble()
                val shaft = if (appearance?.mechKind == MechKind.FOUR_LEGGED) {
                    // Crop uses world shoulder/foot separation; visual foot height only offsets the draw center.
                    val sx = leg.anchor.x - leg.foot.x
                    val sy = leg.anchor.y - leg.foot.y
                    val squared = sx * sx + sy * sy
                    require(squared.isFinite()) { "Non-finite four-leg shaft separation" }
                    val size = requireNotNull(appearance.logicalImages?.leg ?: appearance.nativeLegSize)
                    val halfHeight = size.height / 2f
                    val threshold = (halfHeight - 2f) * (halfHeight - 2f)
                    val half = if (squared < threshold) Math.round(sqrt(squared.toInt().toDouble())).toInt() else halfHeight.toInt()
                    MechShaftFrame((halfHeight - half).toInt(), half * 2, finite((size.width / 2 * 2.0) * appearance.imageScale * projection.scale),
                        finite(half * 2.0 * appearance.imageScale * projection.scale), fx, fy,
                        finite(Math.toDegrees(atan2(-sy, sx)) - 90.0), size.height)
                } else null
                MechLegFrame(leg.index, ax, ay, fx, fy, finite(hypot(dx, dy)),
                    finite(Math.toDegrees(atan2(dy, dx))), finite(-leg.heading), shaft)
            }), bodyX, bodyY, unit.height > 0.0, projection.x(unit.position.x), projection.y(unit.position.y))
        } })
    }

    private fun finite(value: Double): Float = value.toFloat().also { require(it.isFinite()) }
    private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
