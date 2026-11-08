package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class ShieldValidationTest {
    @Test fun invalid_host_shield_profiles_are_rejected_before_a_world_is_published() {
        val heavy = HeavyMechTestScene.declared()
        for (profile in listOf(ShieldProfile(0f, .08f), ShieldProfile(-1f, .08f),
            ShieldProfile(Float.POSITIVE_INFINITY, .08f), ShieldProfile(500f, -.1f),
            ShieldProfile(500f, Float.NaN))) {
            val unit = heavy.withShield(profile)
            val scenario = HeavyMechTestScene.scenario(unit).copy(
                combatContract = CombatContract(12345, -1, mapOf("heavy" to 8L), 9L))
            val result = assertIs<InitResult.Rejected>(SimulationContext.initialize(scenario))
            assertEquals("unsupported-shield", result.error.code)
        }
    }
}

internal fun UnitDefinition.withShield(profile: ShieldProfile) = UnitDefinition(id, className, radius,
    displayRadius, totalFrames, imagePath, imageScale, movement, retainedUnconsumed, fogOfWarSightRange,
    combat = combat, durability = durability, building = building, production = production, build = build,
    directHitTargetKind = directHitTargetKind, pointDefense = pointDefense, upgrades = upgrades, income = income,
    builder = builder, construction = construction, deathExplosion = deathExplosion, mech = mech, rotor = rotor,
    fixedUnderwaterTarget = fixedUnderwaterTarget, authoredWaterCombat = authoredWaterCombat,
    shadowImagePath = shadowImagePath, shield = profile, shieldImagePath = shieldImagePath)
