package com.rustedwarfare.reforge.testing.cross

import kotlin.math.*

/** Pure v2 rule arithmetic only; it accepts no approval and performs no formal comparison. */
internal object MotionV2Math {
    enum class Status { PASS, FAIL, INSUFFICIENT }
    data class Interval(val low: Double, val high: Double)
    data class IntervalResult(val status: Status, val minimumDifference: Double, val maximumDifference: Double)
    data class Vector(val inputSequence: Int, val entryQ: Double, val exitQ: Double, val dx: Double, val dy: Double)
    data class NumericResult(val status: Status, val rmse: Double?, val maximum: Double?, val count: Int,
                             val fullRmseLowerBound: Double)
    fun interval(a: Interval, b: Interval, tolerance: Double): IntervalResult {
        for (v in listOf(a,b)) require(v.low.isFinite() && v.high.isFinite() && v.low >= 0 && v.low <= v.high)
        require(tolerance.isFinite() && tolerance >= 0)
        val minimum = maxOf(0.0, b.low-a.high, a.low-b.high)
        val maximum = max(abs(a.low-b.high),abs(a.high-b.low))
        val status = when { maximum <= tolerance -> Status.PASS; minimum > tolerance -> Status.FAIL; else -> Status.INSUFFICIENT }
        return IntervalResult(status,minimum,maximum)
    }
    fun numeric(errors: List<Double>, expectedCount: Int, rmseLimit: Double, maximumLimit: Double): NumericResult {
        require(expectedCount > 0 && errors.size <= expectedCount)
        require(errors.all { it.isFinite() && it >= 0 })
        require(rmseLimit.isFinite() && rmseLimit >= 0 && maximumLimit.isFinite() && maximumLimit >= 0)
        val maximum = errors.maxOrNull()
        // Scale before squaring, so a finite large error cannot turn its arithmetic into NaN.
        val scale = maximum ?: 0.0
        val normalizedSum = if (scale == 0.0) 0.0 else errors.sumOf { (it/scale)*(it/scale) }
        val lowerBound = scale * sqrt(normalizedSum/expectedCount)
        val rmse = lowerBound.takeIf { errors.size == expectedCount }
        val status = when {
            maximum != null && maximum > maximumLimit || lowerBound > rmseLimit -> Status.FAIL
            errors.size != expectedCount -> Status.INSUFFICIENT
            else -> Status.PASS
        }
        return NumericResult(status,rmse,maximum,errors.size,lowerBound)
    }
    fun oneWayRatio(core: Double, original: Double): Pair<Status, Double?> {
        require(core.isFinite() && original.isFinite() && core >= 0 && original >= 0)
        if (original == 0.0) return Status.INSUFFICIENT to null
        val ratio = core/original
        return (if (ratio > 1.5) Status.FAIL else Status.PASS) to ratio
    }
    fun firstReversal(vectors: List<Vector>): Int? {
        // Every raw call, including zero-I, must be supplied in its actual order.
        // Reject gaps before returning any finding, including gaps after a trigger.
        for (v in vectors) {
            require(v.inputSequence >= 0 && listOf(v.entryQ,v.exitQ,v.dx,v.dy).all { it.isFinite() })
            require(v.entryQ >= 0 && v.exitQ >= v.entryQ)
            require(hypot(v.dx,v.dy).isFinite())
        }
        require(vectors.zipWithNext().all { (a,b)->a.exitQ == b.entryQ }) { "Missing or reordered raw calls" }
        var previous: Vector? = null; var previousSize = 0.0; var reversed = 0
        for ((index,v) in vectors.withIndex()) {
            val size = hypot(v.dx,v.dy)
            if (v.exitQ == v.entryQ || size < .05) { previous=null; reversed=0; continue }
            val prior = previous
            if (prior != null && prior.inputSequence == v.inputSequence) {
                val cosine = ((v.dx/size)*(prior.dx/previousSize)+(v.dy/size)*(prior.dy/previousSize)).coerceIn(-1.0,1.0)
                val angle = Math.toDegrees(acos(cosine))
                reversed = if (angle >= 120.0) reversed+1 else 0
                if (reversed >= 3) return index
            } else reversed=0
            previous=v; previousSize=size
        }
        return null
    }
}
