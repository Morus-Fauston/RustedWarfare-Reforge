package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

/** Native center flight uses binary32 caches; public positions only carry the resulting values. */
internal object NativeMissile {
    fun move(shot: MutableProjectile, target: MutableUnit, delta: Double): Boolean = BallisticFlight.move(shot, target, delta)
}
