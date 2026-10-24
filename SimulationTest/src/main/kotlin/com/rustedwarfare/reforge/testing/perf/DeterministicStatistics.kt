package com.rustedwarfare.reforge.testing.perf

import kotlin.math.ceil

/** Shared formula, independent of any fixture, geometry or archive protocol. */
internal object DeterministicStatistics {
    fun nearestRank(values: List<Long>, q: Double): Long {
        require(values.isNotEmpty()) { "nearestRank requires a non-empty sample" }
        require(q.isFinite() && q > 0.0 && q <= 1.0) { "q must be finite and in (0, 1]" }
        val sorted = values.sorted()
        val index = ceil(q * sorted.size).toInt() - 1
        return sorted[index.coerceIn(0, sorted.lastIndex)]
    }
}
