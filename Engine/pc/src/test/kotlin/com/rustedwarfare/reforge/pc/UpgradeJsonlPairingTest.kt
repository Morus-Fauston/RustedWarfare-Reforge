package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonWriter
import com.rustedwarfare.reforge.client.*
import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class UpgradeJsonlPairingTest {
    private fun root(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
            ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-004/20261026-e-upgrade-input-01/input")

    @Test fun real_upgrade_evidence_identifies_all_twenty_five_inputs_and_preserves_public_queue_shape() {
        val root = root()
        val temporary = Files.createTempDirectory("p005-upgrade-jsonl-")
        try {
            val out = temporary.resolve("run")
            JsonlEvidence(out, "upgrade-run", root).use { sink ->
                sink.record("load-request", mapOf("generation" to 1L, "root" to root.toString()))
                sink.record("load-ready", mapOf("generation" to 1L))
                SandboxSession(ControlledScenario.load(root), sink).use { session ->
                    session.select(Position(230.0, 150.0))
                    assertIs<ActionResult.Accepted>(session.upgrade("upgrade_artillery"))
                    session.tick()
                }
            }
            val manifest = JsonReader().parse(Files.readString(out.resolve("manifest.json")))
            assertEquals("ENG-P005-004", manifest.getString("ticket"))
            val rows = Files.readAllLines(out.resolve("reforge/client-diagnostic.jsonl")).map { JsonReader().parse(it) }
            val identities = rows.single { it.getString("kind") == "input-identities" }
            assertEquals("p005-upgrade", identities.getString("scenarioId"))
            assertEquals(root.resolve("upgrade.properties").toString(), identities.get("sandboxManifest").getString("path"))
            val files = identities.get("files").toList()
            assertEquals(25, files.size)
            val expected = Files.walk(root).use { it.filter { path -> Files.isRegularFile(path) }.toList().toSet() }
            assertEquals(expected, files.map { Path.of(it.getString("path")) }.toSet())
            for (row in files) {
                val file = Path.of(row.getString("path"))
                assertEquals(Files.size(file), row.getLong("bytes"))
                assertEquals(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
                    .joinToString("") { "%02x".format(it) }, row.getString("sha256"))
            }
            val last = rows.last { it.getString("kind") == "observation" }.get("sample")
            assertEquals(150L, last.getLong("balance"))
            val item = last.get("upgradeQueues").first().get("items").first()
            assertEquals(setOf("actionKey", "targetTypeId", "progress"), item.map { it.name }.toSet())
            assertEquals("upgrade_artillery", item.getString("actionKey"))
            assertTrue(item.getFloat("progress") > 0f)
        } finally { temporary.toFile().deleteRecursively() }
    }

    @Test fun actual_jsonl_compares_every_nested_value_for_hidden_real_artillery_impacts_in_memory_and_hardcore() {
        val root = root()
        val base = ControlledScenario.load(root)
        val tank = base.unitDefinitions.getValue("c_tank")
        val observer = UnitDefinition("observer", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, 1)
        val dummy = UnitDefinition("dummy", tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, tank.retainedUnconsumed, 1,
            durability = DurabilityProfile(210.0, tank.combat!!.deadImagePath))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + mapOf(observer.id to observer, dummy.id to dummy),
            players = base.players + Player("p3", PlayerControl.NONE, "three"),
            units = listOf(UnitSpawn("a", "p1", "observer", Position(110.0, 310.0), 90.0),
                UnitSpawn("t", "p2", "c_turret_t1_artillery", Position(430.0, 90.0), 90.0),
                UnitSpawn("z", "p3", "dummy", Position(510.0, 90.0), 270.0),
                UnitSpawn("z2", "p3", "dummy", Position(510.0, 114.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "t" to 11L, "z" to 12L, "z2" to 13L), 15L),
            economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to 0L, "p3" to 0L)))
        val witness = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val observations = (0L..49L).map { witness.advance(Step(it, 1.0, "sim-tick")) }
            assertTrue(observations.any { row -> row.projectileEvents.any { it.kind == "impact" && it.projectile.ground != null } })
            assertTrue(observations.last().units.filter { it.unitId.startsWith("z") }.all { it.health!! < 210.0 })
        } finally { witness.close() }
        val absent = scene.copy(units = scene.units.take(1), combatContract = scene.combatContract!!.copy(unitObjectIds = mapOf("a" to 8L)))
        val temporary = Files.createTempDirectory("p005-upgrade-hidden-jsonl-")
        try {
            for (mode in listOf(SandboxMode.MEMORY, SandboxMode.HARDCORE)) {
                val normalized = listOf(scene, absent).mapIndexed { index, input ->
                    val directory = temporary.resolve("$mode-$index")
                    JsonlEvidence(directory, "paired-run", root).use { sink ->
                        SandboxSession(input, sink, SandboxOptions(mode)).use { session -> repeat(51) { session.tick() } }
                    }
                    Files.readAllLines(directory.resolve("reforge/client-diagnostic.jsonl")).map { line ->
                        val row = JsonReader().parse(line)
                        row.remove("wallTime")
                        row.toJson(JsonWriter.OutputType.json)
                    }
                }
                assertTrue(normalized.first().size >= 52)
                assertEquals(normalized[0], normalized[1], "Real JSONL: every key and nested value, only wallTime omitted: $mode")
            }
        } finally { temporary.toFile().deleteRecursively() }
    }
}
