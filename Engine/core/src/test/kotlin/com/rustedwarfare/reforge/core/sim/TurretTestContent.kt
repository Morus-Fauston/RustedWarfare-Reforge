package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.assertIs

/** Real frozen tower assets; role/position variations are explicit trusted-host test scenarios. */
internal object TurretTestContent {
    fun tower(): UnitDefinition {
        val root = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent
        return assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadTurretUnit(root.resolve(
            ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-003/20261025-subscription-e-contract-03/input/units/turret_t1.ini"))).value
    }

    fun scenario(vararg units: UnitSpawn, tower: UnitDefinition = tower()): Scenario {
        val base = BuildingTestContent.scenario()
        val ids = mapOf("a" to 8L, "b" to 9L, "f" to 10L, "t" to 11L, "u" to 12L, "g" to 13L)
        return base.copy(scenarioId = "eng003-turret-test", unitDefinitions = base.unitDefinitions + (tower.id to tower),
            units = units.toList(), combatContract = CombatContract(12345, -1, units.associate { it.unitId to ids.getValue(it.unitId) }, 14L))
    }
}
