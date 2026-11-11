package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.TransportProfile
import kotlin.math.abs

/** Finite non-air custom height branch, separate from weapon energy and native submarines. */
internal object NautilusMotion {
    fun advance(unit: MutableUnit, delta: Double) {
        if (unit.definition.transport != TransportProfile.NAUTILUS_SUBMERGED) return
        val elapsed = delta.toFloat()
        var height = unit.height.toFloat()
        if (height < -8.1f) height = minOf(-8.1f, height + .2f * elapsed)
        var phase = unit.heightPhase.toFloat() + 2f * elapsed
        if (phase > 360f) phase -= 360f
        val target = -8f + GroundMath.sin(phase) * .1f
        val change = minOf(.005f, abs(height - target) * .05f * .3f + .1f) * elapsed
        height = if (height < target) minOf(target, height + change) else maxOf(target, height - change)
        if (height > -6.9f) {
            unit.sinkSpeed += .03f * elapsed
            if (height < 0f) unit.sinkSpeed = minOf(unit.sinkSpeed, .2f)
            height -= unit.sinkSpeed * elapsed
            if (height <= -6.9f) {
                if (height < -7.9f) height = -7.9f
                unit.sinkSpeed = 0f
            }
        } else unit.sinkSpeed = 0f
        require(height.isFinite() && phase.isFinite() && unit.sinkSpeed.isFinite()) { "Custom water height must remain finite" }
        unit.height = height.toDouble()
        unit.heightPhase = phase.toDouble()
    }
}
