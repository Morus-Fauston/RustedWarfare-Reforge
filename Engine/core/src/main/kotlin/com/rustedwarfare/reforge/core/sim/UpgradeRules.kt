package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition

/** Registration is closed over already loaded definitions; no completion path reads content files. */
internal object UpgradeRules {
    private val targets = mapOf("upgrade_gun" to "c_turret_t2_gun", "upgrade_artillery" to "c_turret_t1_artillery")

    fun validate(input: Scenario): SimulationError? {
        fun invalid() = SimulationError("unsupported-upgrade", "Expected the complete finite two-branch turret upgrade registration")
        for ((key, source) in input.unitDefinitions) {
            val profile = source.upgrades ?: continue
            if (input.economyContract == null || key != source.id || source.id != "c_turret_t1" ||
                !eligible(source) || profile.actions.size != 2 ||
                profile.actions.map { it.actionKey }.toSet() != targets.keys) return invalid()
            for (action in profile.actions) {
                if (action.targetTypeId != targets[action.actionKey] || action.price < 0L ||
                    !action.rate.isFinite() || action.rate <= 0f) return invalid()
                val target = input.unitDefinitions[action.targetTypeId] ?: return invalid()
                if (target.id != action.targetTypeId || !eligible(target) || target.upgrades != null ||
                    target.radius != source.radius || target.building?.footprint != source.building?.footprint ||
                    target.building?.anchor != source.building?.anchor ||
                    (target.combat?.projectile?.ground != null) != (action.actionKey == "upgrade_artillery")) return invalid()
            }
        }
        return null
    }

    private fun eligible(unit: UnitDefinition): Boolean = unit.movement.type == MovementType.NONE &&
        unit.building != null && unit.combat != null && unit.durability != null && unit.production == null &&
        unit.pointDefense == null && unit.combat.maxHp == unit.durability.maxHp &&
        unit.combat.maxHp.toFloat().let { it.isFinite() && it > 0f }
}
