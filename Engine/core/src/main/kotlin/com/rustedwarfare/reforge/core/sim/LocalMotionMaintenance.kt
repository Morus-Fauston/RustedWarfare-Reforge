package com.rustedwarfare.reforge.core.sim

internal data class LocalMotionProgress(val segment: LocalMotionSegment?, val activeTime: Double)

/** Ordinary local-array maintenance, confined to one owner-approved global segment. */
internal object LocalMotionMaintenance {
    fun advance(segment: LocalMotionSegment, previousTime: Double, delta: Double, positivePropulsion: Boolean,
                oldDistanceSquared: Double, captureOldPoint: Boolean, previousCorrectionTime: Double,
                distanceSquared: (Position) -> Double, allows: (Position) -> Boolean): LocalMotionProgress {
        var current: LocalMotionSegment? = segment
        var time = previousTime + if (positivePropulsion) delta else 0.0
        fun consume(resetTime: Boolean) {
            val next = current?.next() ?: return
            if (!allows(next.aim)) return
            current = next
            if (resetTime) time = 0.0
        }
        if (positivePropulsion) {
            if (time > 200.0 && oldDistanceSquared < 3600.0) consume(false)
            if (time > 600.0 && current?.next() != null) { current = null; time = 0.0 }
            if (time > 80.0 && previousCorrectionTime > 30.0) { current = null; time = 0.0 }
            val next = current?.next()
            if (time > 40.0 && next != null && distanceSquared(next.aim) < oldDistanceSquared) consume(false)
        }
        // The predicate belongs to the old aim; do not recompute it after earlier consumption.
        if (captureOldPoint) consume(true)
        return LocalMotionProgress(current, time)
    }
}
