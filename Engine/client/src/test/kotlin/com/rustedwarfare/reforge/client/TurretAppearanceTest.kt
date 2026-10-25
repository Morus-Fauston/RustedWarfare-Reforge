package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class TurretAppearanceTest {
    @Test
    fun static_resources_carry_the_real_mount_and_corpse_without_a_second_base_or_runtime_truth() {
        val root = TurretSandboxSessionTest().root()
        val tower = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadTurretUnit(root.resolve("units/turret_t1.ini"))).value
        val base = ControlledScenario.load(root)
        val resources = SandboxRenderResources.from(base.copy(unitDefinitions = base.unitDefinitions + (tower.id to tower)))
        val appearance = resources.units.getValue(tower.id)
        assertEquals(TurretMount(0.0, 6.0), appearance.turretMount)
        val combat = assertNotNull(tower.combat)
        assertEquals(combat.turretImagePath, appearance.turretImagePath)
        assertEquals(combat.deadImagePath, appearance.deadImagePath)
        assertNull(appearance.backImagePath)
        assertNotNull(resources.units.getValue("landFactory").backImagePath)
        assertEquals(TurretMount(), resources.units.getValue("c_tank").turretMount)
        assertFailsWith<UnsupportedOperationException> { (resources.units as MutableMap).clear() }
    }
}
