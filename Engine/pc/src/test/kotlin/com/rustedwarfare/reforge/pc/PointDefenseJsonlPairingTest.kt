package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonWriter
import com.rustedwarfare.reforge.client.*
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PointDefenseJsonlPairingTest {
    @Test fun actual_jsonl_preserves_full_hidden_defense_pairing_in_both_knowledge_modes() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val root = repo.resolve(".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-005/20261026-e-defense-input-01/input")
        val base = ControlledScenario.load(root)
        val tank = base.unitDefinitions.getValue("c_tank")
        val observer = UnitDefinition("observer", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, 1)
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (observer.id to observer),
            players = base.players + Player("p3", PlayerControl.NONE, "three"),
            units = listOf(UnitSpawn("a", "p1", observer.id, Position(110.0, 210.0), 90.0),
                UnitSpawn("d", "p2", "laserDefence", Position(430.0, 90.0), 0.0),
                UnitSpawn("z", "p3", "c_tank", Position(510.0, 90.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "d" to 11L, "z" to 12L), 24L),
            economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to 0L, "p3" to 0L)))
        val absent = scene.copy(units = scene.units.take(1), combatContract = scene.combatContract!!.copy(unitObjectIds = mapOf("a" to 8L)))
        val temporary = Files.createTempDirectory("p005-defense-jsonl-")
        try {
            for (mode in listOf(SandboxMode.MEMORY, SandboxMode.HARDCORE)) {
                val normalized = listOf(scene, absent).mapIndexed { index, input ->
                    val directory = temporary.resolve("$mode-$index")
                    JsonlEvidence(directory, "paired-run", root).use { evidence ->
                        SandboxSession(input, evidence, SandboxOptions(mode)).use { session -> repeat(26) { session.tick() } }
                    }
                    Files.readAllLines(directory.resolve("reforge/client-diagnostic.jsonl")).map { line ->
                        val row = JsonReader().parse(line)
                        row.remove("wallTime") // The sole deliberately non-deterministic field; compare every nested value.
                        row.toJson(JsonWriter.OutputType.json)
                    }
                }
                assertTrue(normalized.first().size >= 27)
                assertEquals(normalized[0], normalized[1], "Real JSONL rows, all keys and nested arrays: $mode")
            }
        } finally { temporary.toFile().deleteRecursively() }
    }
}
