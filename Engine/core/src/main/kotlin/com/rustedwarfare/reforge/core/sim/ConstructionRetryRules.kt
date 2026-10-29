package com.rustedwarfare.reforge.core.sim

/** Preserve the original strict comparisons and signed Int arithmetic. */
internal object ConstructionRetryRules {
    fun isDue(retryTime: Int, clock: Int): Boolean = retryTime + 200 < clock || clock < retryTime - 1000
}
