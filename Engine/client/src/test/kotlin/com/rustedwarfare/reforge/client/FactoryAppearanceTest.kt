package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class FactoryAppearanceTest {
    private fun input(): Scenario {
        val repo = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent
        val root = repo.resolve(".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-002/20261025-e-input-01/input")
        val base = ControlledScenario.load(repo.resolve(
            ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-001/20261025-e-pc-representative-01/input"))
        val factory = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadFactory(root.resolve("units/landFactory.properties"))).value
        return base.copy(unitDefinitions = base.unitDefinitions + (factory.id to factory))
    }

    @Test
    fun actual_native_factory_durability_corpse_reaches_formal_render_resources_without_a_fake_weapon() {
        val input = input()
        val factory = input.unitDefinitions.getValue("landFactory")
        assertNull(factory.combat)
        val appearance = SandboxRenderResources.from(input).units.getValue(factory.id)
        assertEquals(factory.imagePath, appearance.imagePath)
        assertEquals(factory.durability!!.deadImagePath, appearance.deadImagePath)
        assertEquals(factory.building!!.backImagePath, appearance.backImagePath)
        assertNull(appearance.turretImagePath)
        val tank = input.unitDefinitions.getValue("c_tank")
        val tankAppearance = SandboxRenderResources.from(input).units.getValue(tank.id)
        assertEquals(tank.combat!!.deadImagePath, tankAppearance.deadImagePath)
        assertEquals(tank.combat!!.turretImagePath, tankAppearance.turretImagePath)
        assertNull(tankAppearance.backImagePath)
    }
}
