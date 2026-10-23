package com.rustedwarfare.reforge.core.sim

import kotlin.math.hypot
import com.rustedwarfare.reforge.core.content.MovementProfile

/** Fixed local aims on one independently planned straight segment. Immutable for step rollback. */
internal class LocalMotionSegment private constructor(
    private val origin: Position,
    val anchor: Position,
    private val length: Double,
    private val intermediateCount: Long,
    private val index: Long,
    private val spacing: Double = 20.0,
) {
    val isLast: Boolean get() = index > intermediateCount
    val aim: Position get() {
        if (isLast) return anchor
        val fraction = (spacing * index.toDouble()) / length
        return Position(origin.x + (anchor.x - origin.x) * fraction,
            origin.y + (anchor.y - origin.y) * fraction)
    }

    fun next(): LocalMotionSegment? = if (isLast) null
        else LocalMotionSegment(origin, anchor, length, intermediateCount, index + 1L, spacing)

    fun snapshot(commandId: String?, activeTime: Double, correctionTime: Double): LocalMotionSnapshot =
        LocalMotionSnapshot(commandId, origin, anchor, aim, intermediateCount, index, activeTime, correctionTime)

    companion object {
        fun create(origin: Position, anchor: Position): LocalMotionSegment {
            val length = hypot(anchor.x - origin.x, anchor.y - origin.y)
            check(length.isFinite()) { "Non-finite local motion segment" }
            val count = (0.05 * length - 1.0).toLong().coerceAtLeast(0L)
            return LocalMotionSegment(origin, anchor, length, count, if (count >= 4L) 2L else 1L)
        }

        fun create(origin: Position, anchor: Position, movement: MovementProfile): LocalMotionSegment {
            val length = hypot(anchor.x - origin.x, anchor.y - origin.y)
            check(length.isFinite()) { "Non-finite local motion segment" }
            if (length == 0.0 || movement.moveSpeed == 0.0) return create(origin, anchor)
            if (movement.maxTurnSpeed == 0.0) return LocalMotionSegment(origin, anchor, length, 0L, 1L)
            val radius = cappedScale(movement.moveSpeed, 180.0 / Math.PI, movement.maxTurnSpeed, length)
            val ramp = if (movement.turnAcceleration == 0.0) 0.0 else
                cappedScale(movement.moveSpeed, movement.maxTurnSpeed, movement.turnAcceleration, length)
            val spacing = minOf(length, maxOf(20.0, radius, ramp))
            if (spacing == 20.0) return create(origin, anchor)
            val count = (length / spacing - 1.0).toLong().coerceAtLeast(0L)
            return LocalMotionSegment(origin, anchor, length, count, if (count >= 4L) 2L else 1L, spacing)
        }

        /** Positive finite factors; only the final result can overflow, where the segment caps it. */
        private fun cappedScale(x: Double, y: Double, z: Double, length: Double): Double {
            val ex = Math.getExponent(x); val ey = Math.getExponent(y); val ez = Math.getExponent(z)
            val mantissa = (Math.scalb(x, -ex) * Math.scalb(y, -ey)) / Math.scalb(z, -ez)
            return minOf(length, Math.scalb(mantissa, ex + ey - ez))
        }
    }
}

/** Module-internal read-only diagnostic; never included in host or player observation DTOs. */
internal data class LocalMotionSnapshot(val commandId: String?, val origin: Position, val anchor: Position,
    val aim: Position, val intermediateCount: Long, val index: Long, val activeTime: Double, val correctionTime: Double)
