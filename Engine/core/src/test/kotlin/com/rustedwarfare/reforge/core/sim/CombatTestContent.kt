package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import java.nio.file.Path
import kotlin.test.assertIs

/** Actual frozen content through public loaders; scenario variations are explicit trusted-host inputs. */
internal object CombatTestContent {
    private fun root(): Path = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-001/20261025-e-input-01/input")

    fun scenario(mapName: String = "p001-controlled.tmx", sight: Int = 15, rules: KnowledgeRules? = null): Scenario {
        val original = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(root().resolve("units/tank.ini"))).value
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, sight, original.combat)
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(root().resolve("maps/$mapName"))).value
        return Scenario("p005-combat", map, mapOf("c_tank" to tank),
            listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two")),
            listOf(UnitSpawn("a", "p1", "c_tank", Position(110.0, 210.0), 90.0),
                UnitSpawn("b", "p2", "c_tank", Position(510.0, 210.0), 270.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0), rules,
            CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L), 11L))
    }
}
