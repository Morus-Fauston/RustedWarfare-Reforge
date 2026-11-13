package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.CombatProfile
import com.rustedwarfare.reforge.core.content.MovementType

/** Current weapon envelope, independent of the unit's requested form. */
internal object UnitCombat {
    fun range(unit: MutableUnit): Double = if (unit.amphibiousState != null) {
        if (UnitLocomotion.movement(unit).type == MovementType.WATER) 100.0 else 170.0
    } else SubmarineCombat.range(unit)

    fun profile(unit: MutableUnit): CombatProfile? =
        SubmarineCombat.profile(unit)?.let { if (unit.amphibiousState == null) it else it.copy(attackRange = range(unit)) }
}
