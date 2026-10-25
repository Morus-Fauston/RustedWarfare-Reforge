package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.assertIs

internal object BuildingTestContent {
    fun variant(unit: UnitDefinition, sight: Int = unit.fogOfWarSightRange,
                footprint: BuildingFootprint? = unit.building?.footprint): UnitDefinition =
        UnitDefinition(unit.id, unit.className, unit.radius, unit.displayRadius, unit.totalFrames,
            unit.imagePath, unit.imageScale, unit.movement, unit.retainedUnconsumed, sight, unit.combat,
            unit.durability, unit.building?.copy(footprint = checkNotNull(footprint)), unit.production, unit.build, unit.directHitTargetKind)

    private fun root(): Path = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-002/20261025-e-input-01/input")

    fun scenario(): Scenario {
        val base = CombatTestContent.scenario()
        val tank = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(root().resolve("units/tank.ini"))).value
        val factory = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadFactory(root().resolve("units/landFactory.properties"))).value
        return base.copy(unitDefinitions = mapOf(tank.id to tank, factory.id to factory),
            units = listOf(UnitSpawn("a", "p1", tank.id, Position(110.0, 210.0), 90.0),
                UnitSpawn("f", "p1", factory.id, Position(310.0, 210.0), 0.0),
                UnitSpawn("b", "p2", tank.id, Position(510.0, 330.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "f" to 10L), 11L),
            economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to 0L)))
    }
}
