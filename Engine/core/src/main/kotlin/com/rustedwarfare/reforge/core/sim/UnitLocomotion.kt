package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementProfile
import com.rustedwarfare.reforge.core.content.MovementType

/** The current physical locomotion profile, shared by planning, steering and displacement. */
internal object UnitLocomotion {
    fun movement(unit: MutableUnit): MovementProfile {
        val state = unit.amphibiousState ?: return SubmarineMotion.movement(unit)
        val underwater = unit.height < -1.0
        return unit.definition.movement.copy(
            type = if (!state.flyingIntent || unit.height < 0.0) MovementType.WATER else MovementType.AIR,
            moveSpeed = if (underwater) .4 else 1.4,
            maxTurnSpeed = if (underwater) 1.5 else 3.8)
    }
}
