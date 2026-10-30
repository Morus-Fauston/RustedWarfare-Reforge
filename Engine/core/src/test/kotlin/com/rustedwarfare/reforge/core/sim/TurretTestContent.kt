package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.CombatProfile
import com.rustedwarfare.reforge.core.content.DurabilityProfile
import com.rustedwarfare.reforge.core.content.BuildingProfile
import com.rustedwarfare.reforge.core.content.MovementProfile
import java.nio.file.Path
import kotlin.test.assertIs

internal object TurretTestContent {
    fun root(): Path = System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("production-combat/upgrade") }
        ?: Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-003/20261026-e-turret-contract-01/input")
    fun tower(): UnitDefinition = assertIs<ContentResult.Success<UnitDefinition>>(
        ContentLoader.loadTurretUnit(root().resolve("units/turret_t1.ini"))).value
    fun variant(unit: UnitDefinition, id: String = unit.id, radius: Double = unit.radius,
                combat: CombatProfile? = unit.combat, durability: DurabilityProfile? = unit.durability,
                building: BuildingProfile? = unit.building, sight: Int = unit.fogOfWarSightRange,
                movement: MovementProfile = unit.movement): UnitDefinition =
        UnitDefinition(id, unit.className, radius, unit.displayRadius, unit.totalFrames, unit.imagePath, unit.imageScale,
            movement, unit.retainedUnconsumed, sight, combat, durability, building, unit.production, unit.build, unit.directHitTargetKind)
    fun scenario(position: Position = Position(320.0, 220.0)): Scenario {
        val base = BuildingTestContent.scenario()
        val tower = tower()
        return base.copy(scenarioId = "p005-turret-contract", unitDefinitions = base.unitDefinitions + (tower.id to tower),
            units = listOf(UnitSpawn("t", "p1", tower.id, position, 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("t" to 11L), 12L))
    }
}
