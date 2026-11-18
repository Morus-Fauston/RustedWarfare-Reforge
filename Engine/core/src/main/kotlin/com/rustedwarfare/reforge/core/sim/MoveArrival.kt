package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import kotlin.math.abs
import kotlin.math.hypot

/** Player-authorized result of one completed ordinary movement command. */
data class MoveArrival(val originalTarget: Position, val stoppedAt: Position, val distance: Double,
                       val stepIndex: Long, val group: Boolean) {
    internal companion object {
        fun distanceToHull(shape: MovementCollisionShape, position: Position, heading: Double, target: Position): Double {
            val dx = target.x - position.x
            val dy = target.y - position.y
            val distance = when (shape) {
                is MovementCollisionShape.Circle -> maxOf(0.0, hypot(dx, dy) - shape.radius)
                is MovementCollisionShape.Rectangle -> {
                    val forward = ScreenPlane.displacement(heading, 1.0)
                    val along = abs(dx * forward.x + dy * forward.y) - shape.length / 2.0
                    val across = abs(-dx * forward.y + dy * forward.x) - shape.width / 2.0
                    hypot(maxOf(0.0, along), maxOf(0.0, across))
                }
            }
            val roundoff = 64.0 * Math.ulp(maxOf(abs(target.x), abs(target.y), abs(position.x), abs(position.y)))
            return if (distance <= roundoff) 0.0 else distance
        }
    }
}
