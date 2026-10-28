package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.UpgradeActionProfile

/** Registration is closed over already loaded definitions; no completion path reads content files. */
internal object UpgradeRules {
    private val targets = mapOf("upgrade_gun" to "c_turret_t2_gun", "upgrade_artillery" to "c_turret_t1_artillery")
    private val economicActions = mapOf(
        "extractorT1" to UpgradeActionProfile("upgrade_t2", "extractorT2", 1400L, .0006f),
        "extractorT2" to UpgradeActionProfile("upgrade_t3", "extractorT3", 4000L, .0003f),
        "fabricatorT1" to UpgradeActionProfile("upgrade_t2", "fabricatorT2", 4400L, 1f / (65.6f * 60f)),
        "fabricatorT2" to UpgradeActionProfile("upgrade_t3", "fabricatorT3", 7500L, 1f / (93.3f * 60f)),
    )

    fun validate(input: Scenario): SimulationError? {
        fun invalid() = SimulationError("unsupported-upgrade", "Expected a complete finite ordinary upgrade registration")
        for ((key, source) in input.unitDefinitions) {
            val profile = source.upgrades ?: continue
            val economicAction = economicActions[source.id]
            if (economicAction != null) {
                if (input.economyContract == null || key != source.id || !economicEligible(source) ||
                    profile.actions != listOf(economicAction)) return invalid()
                val target = input.unitDefinitions[economicAction.targetTypeId] ?: return invalid()
                if (target.id != economicAction.targetTypeId || !economicEligible(target) ||
                    target.upgrades?.actions.orEmpty() != listOfNotNull(economicActions[target.id]) ||
                    target.movement != source.movement || target.radius != source.radius ||
                    target.building?.footprint != source.building?.footprint ||
                    target.building?.placementFootprint != source.building?.placementFootprint ||
                    target.building?.anchor != source.building?.anchor ||
                    target.building?.requiresResourcePool != source.building?.requiresResourcePool) return invalid()
                continue
            }
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

    private fun economicEligible(unit: UnitDefinition): Boolean = unit.movement.type == MovementType.NONE &&
        unit.building != null && unit.construction != null && unit.income != null && unit.durability != null &&
        unit.combat == null && unit.production == null && unit.pointDefense == null && unit.builder == null &&
        unit.durability.maxHp.toFloat().let { it.isFinite() && it > 0f }
}
