package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class SubmarineDefinitionValidationTest {
    @Test fun the_native_marker_does_not_authorize_other_damage_ranges_or_weapon_capabilities() =
        WaterTestScene.withMap { map -> withSubmarinePackage { root ->
            val sub = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value.units.getValue("attackSubmarine")
            val combat = assertNotNull(sub.combat)
            val named = assertNotNull(combat.namedWeapons).ordered.single()
            val invalid = listOf(combat.copy(attackRange = 251.0), combat.copy(shootDelay = 169.0),
                combat.copy(projectile = combat.projectile.copy(directDamage = 43.0)),
                combat.copy(namedWeapons = WeaponCatalog(listOf(named.copy(canAttackUnderwaterUnits = false)))))
            for ((index, changed) in invalid.withIndex()) {
                val definition = UnitDefinition(sub.id, sub.className, sub.radius, sub.displayRadius, sub.totalFrames,
                    sub.imagePath, sub.imageScale, sub.movement, sub.retainedUnconsumed, sub.fogOfWarSightRange,
                    combat = changed, build = sub.build, submarine = sub.submarine)
                val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
                    unitDefinitions = mapOf(sub.id to definition),
                    units = listOf(UnitSpawn("sub", "p1", sub.id, Position(500.0, 200.0), 90.0)),
                    combatContract = CombatContract(12345, -1, mapOf("sub" to 8L), 9L))
                val result = SimulationContext.initialize(input)
                if (result is InitResult.Accepted) result.context.close()
                assertEquals("unsupported-submarine", assertIs<InitResult.Rejected>(result, "variant $index").error.code)
            }
        } }
}
