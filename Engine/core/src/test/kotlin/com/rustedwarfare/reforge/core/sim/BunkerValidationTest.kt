package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class BunkerValidationTest {
    @Test fun a_finite_but_unqualified_bunker_weapon_cannot_enter_the_simulation() = WaterTestScene.withMap { map ->
        val scene = BunkerScene.scenario(map)
        val source = scene.unitDefinitions.getValue("mechBunker")
        val weapon = source.combat!!
        val projectile = weapon.projectile.copy(ground = weapon.projectile.ground!!.copy(areaDamage = 111f))
        val changed = weapon.copy(projectile = projectile, namedWeapons = WeaponCatalog(weapon.namedWeapons!!.ordered.map {
            it.copy(projectile = projectile)
        }))
        val modified = UnitDefinition(source.id, source.className, source.radius, source.displayRadius, source.totalFrames,
            source.imagePath, source.imageScale, source.movement, source.retainedUnconsumed, source.fogOfWarSightRange,
            combat = changed, building = source.building, build = source.build, mech = source.mech,
            shadowImagePath = source.shadowImagePath, shield = source.shield, shieldImagePath = source.shieldImagePath,
            customActions = source.customActions, groundHeight = source.groundHeight)
        val result = SimulationContext.initialize(scene.copy(unitDefinitions = scene.unitDefinitions + (source.id to modified)))
        if (result is InitResult.Accepted) result.context.close()
        assertEquals("unsupported-unit-actions", assertIs<InitResult.Rejected>(result).error.code)
    }
}
