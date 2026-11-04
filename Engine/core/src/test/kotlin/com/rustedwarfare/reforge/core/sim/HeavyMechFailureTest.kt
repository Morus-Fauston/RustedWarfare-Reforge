package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class HeavyMechFailureTest {
    @Test fun invalid_ordinary_splash_numbers_in_a_secondary_weapon_are_rejected_before_opening_a_world() {
        val source = HeavyMechTestScene.declared()
        val combat = assertNotNull(source.combat)
        for (bad in listOf(OrdinarySplashProfile(Float.NaN, 35f), OrdinarySplashProfile(Float.POSITIVE_INFINITY, 35f),
            OrdinarySplashProfile(-1f, 35f), OrdinarySplashProfile(40f, 0f),
            OrdinarySplashProfile(40f, Float.NaN), OrdinarySplashProfile(40f, Float.MAX_VALUE))) {
            val profile = combat.copy(namedWeapons = WeaponCatalog(assertNotNull(combat.namedWeapons).ordered.map {
                if (it.name == "3") it.copy(projectile = it.projectile.copy(splash = bad)) else it
            }))
            val unit = UnitDefinition(source.id, source.className, source.radius, source.displayRadius,
                source.totalFrames, source.imagePath, source.imageScale, source.movement, source.retainedUnconsumed,
                source.fogOfWarSightRange, combat = profile, mech = source.mech)
            val input = HeavyMechTestScene.scenario(unit = unit).copy(
                combatContract = CombatContract(12345, -1, mapOf("heavy" to 8L), 9L))
            val result = SimulationContext.initialize(input)
            if (result is InitResult.Accepted) result.context.close()
            assertEquals("unsupported-combat", assertIs<InitResult.Rejected>(result, "splash=$bad").error.code)
        }
        val valid = HeavyMechTestScene.open(HeavyMechTestScene.scenario(unit = source).copy(
            combatContract = CombatContract(12345, -1, mapOf("heavy" to 8L), 9L)))
        try { assertEquals(2500.0, valid.observe().units.single().health) } finally { valid.close() }
    }
}
