package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.assertIs

internal object UpgradeTestContent {
    fun root(): Path = System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("production-combat/upgrade") }
        ?: Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-004/20261026-e-upgrade-input-01/input")
    fun tower(enabled: Boolean = true): UnitDefinition = assertIs<ContentResult.Success<UnitDefinition>>(
        ContentLoader.loadTurretUnit(root().resolve("units/turret_t1.ini"), enabled)).value
    fun target(name: String): UnitDefinition = assertIs<ContentResult.Success<UnitDefinition>>(
        ContentLoader.loadTurretUnit(root().resolve("units/$name.ini"))).value
    fun scenario(): Scenario {
        val base = BuildingTestContent.scenario()
        val tower = tower()
        val targets = listOf(target("turret_t2_gun"), target("turret_t1_artillery"))
        return base.copy(scenarioId = "p005-upgrade-contract", unitDefinitions = base.unitDefinitions +
            (tower.id to tower) + targets.associateBy { it.id },
            units = listOf(UnitSpawn("t", "p1", tower.id, Position(230.0, 150.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("t" to 11L), 15L))
    }
    fun variant(unit: UnitDefinition, id: String = unit.id, upgrades: UpgradeProfile? = unit.upgrades,
                combat: CombatProfile? = unit.combat, building: BuildingProfile? = unit.building,
                durability: DurabilityProfile? = unit.durability, movement: MovementProfile = unit.movement,
                radius: Double = unit.radius): UnitDefinition =
        UnitDefinition(id, unit.className, radius, unit.displayRadius, unit.totalFrames, unit.imagePath, unit.imageScale,
            movement, unit.retainedUnconsumed, unit.fogOfWarSightRange, combat, durability, building,
            unit.production, unit.build, unit.directHitTargetKind, unit.pointDefense, upgrades)
}
