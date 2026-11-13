package com.rustedwarfare.reforge.core.sim

/** Custom weapon energyUsage only; resourceUsage and laser defense have different rules. */
internal object WeaponEnergy {
    fun canFire(unit: MutableUnit, cost: Float): Boolean = cost <= 0f || !unit.energyRecharging && unit.energy >= cost

    fun consume(unit: MutableUnit, cost: Float) {
        if (cost <= 0f) return
        check(canFire(unit, cost))
        unit.energy -= cost
        if (unit.energy < cost && unit.definition.energy?.needsFullRecharge == true) unit.energyRecharging = true
    }

    fun advance(unit: MutableUnit, delta: Double) {
        val profile = unit.definition.energy ?: return
        val rate = if (unit.energyRecharging) profile.rechargingRegeneration else profile.regeneration
        if (unit.energy < profile.maximum || rate < 0f) unit.energy += rate * delta.toFloat()
        require(unit.energy.isFinite()) { "Weapon energy must remain finite" }
        if (unit.energy >= profile.maximum) {
            unit.energy = profile.maximum
            unit.energyRecharging = false
        }
        if (unit.energy < 0f) unit.energy = 0f
    }
}
