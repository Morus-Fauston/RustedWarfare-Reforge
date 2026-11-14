package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.OrdinaryUpgradeContent

/** Registration is closed over already loaded definitions; no completion path reads content files. */
internal object UpgradeRules {
    private val economicActions = OrdinaryUpgradeContent.economic.mapValues { it.value.action }

    fun validate(input: Scenario, registration: UpgradeRegistration = UpgradeRegistration()): SimulationError? {
        fun invalid() = SimulationError("unsupported-upgrade", "Expected a complete finite ordinary upgrade registration")
        for ((key, source) in input.unitDefinitions) {
            if (source.className == "AuthoredEconomicBuilding") {
                if (input.economyContract == null || key != source.id ||
                    com.rustedwarfare.reforge.core.content.AuthorEconomicRules.invalidAction(source, input.unitDefinitions) != null)
                    return invalid()
                continue
            }
            val profile = source.upgrades ?: continue
            if (profile.actions.any { it.completionCredits != 0L }) return invalid()
            if (source.className == "AuthoredBuilding") {
                val target = profile.actions.singleOrNull()?.targetTypeId?.let(input.unitDefinitions::get)
                    ?: return invalid()
                if (input.economyContract == null || key != source.id ||
                    !com.rustedwarfare.reforge.core.content.AuthorFactoryRules.supports(source, input.unitDefinitions) ||
                    !com.rustedwarfare.reforge.core.content.AuthorFactoryRules.supports(target, input.unitDefinitions) ||
                    !com.rustedwarfare.reforge.core.content.AuthorFactoryRules.upgrade(source, target)) return invalid()
                continue
            }
            if (source.id == "seaFactory") {
                val target = input.unitDefinitions["seaFactoryT2"] ?: return invalid()
                if (input.economyContract == null || key != source.id || !FactoryExit.supportsSeaFactory(source) ||
                    !FactoryExit.supportsSeaFactory(target) || target.id != "seaFactoryT2") return invalid()
                continue
            }
            if (source.id == "airFactory") {
                val target = input.unitDefinitions["airFactoryT2"] ?: return invalid()
                if (input.economyContract == null || key != source.id || !FactoryExit.supportsAirFactory(source) ||
                    !FactoryExit.supportsAirFactory(target) || target.id != "airFactoryT2") return invalid()
                continue
            }
            if (source.id == "mechFactory") {
                val target = input.unitDefinitions["mechFactoryT2"] ?: return invalid()
                if (input.economyContract == null || key != source.id || !FactoryExit.supportsMechFactory(source) ||
                    profile.actions != listOf(com.rustedwarfare.reforge.core.content.UpgradeActionProfile("upgrade_t2", "mechFactoryT2", 4000L, .0004f)) ||
                    !FactoryExit.supportsMechFactory(target) || target.id != "mechFactoryT2" || target.upgrades != null)
                    return invalid()
                continue
            }
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
            val targets = registration.turrets[source.id] ?: return invalid()
            if (input.economyContract == null || key != source.id ||
                !eligible(source) || profile.actions.size != targets.size || targets.isEmpty() ||
                profile.actions.map { it.actionKey }.toSet() != targets.keys) return invalid()
            for (action in profile.actions) {
                if (action.targetTypeId != targets[action.actionKey]?.targetTypeId || action.price < 0L ||
                    !action.rate.isFinite() || action.rate <= 0f) return invalid()
                val target = input.unitDefinitions[action.targetTypeId] ?: return invalid()
                if (target.id != action.targetTypeId || !eligible(target) || target.upgrades != null ||
                    target.radius != source.radius || target.building?.footprint != source.building?.footprint ||
                    target.building?.anchor != source.building?.anchor ||
                    (target.combat?.projectile?.ground != null) != targets.getValue(action.actionKey).groundProjectile) return invalid()
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
