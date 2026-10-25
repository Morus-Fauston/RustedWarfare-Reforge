package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class TurretInputIdentityTest {
    private fun inputs(): Path {
        val repo = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        return repo.resolve(".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-003/20261025-subscription-e-contract-03/input")
    }

    @Test
    fun the_formal_pc_manifest_identifies_the_turret_scene_and_not_a_missing_legacy_combat_marker() {
        withDirectory { directory ->
            JsonlEvidence(directory.resolve("run"), "turret-identity", inputs()).use { }
            val manifest = JsonReader().parse(Files.readString(directory.resolve("run/manifest.json")))
            assertEquals("P005", manifest.getString("phase"))
            assertEquals("ENG-P005-003", manifest.getString("ticket"))
            assertEquals("turret-windows-client", manifest.getString("topic"))
            assertFalse(manifest.getBoolean("originalGameExecuted"))
        }
    }

    @Test
    fun load_ready_binds_all_twenty_actual_inputs_and_the_correct_scene_marker() {
        withDirectory { directory ->
            val root = inputs()
            JsonlEvidence(directory.resolve("run"), "turret-identity", root).use {
                it.record("load-request", mapOf("root" to root.toString(), "generation" to 1))
                it.record("load-ready", mapOf("generation" to 1))
            }
            val records = Files.readAllLines(directory.resolve("run/reforge/client-diagnostic.jsonl")).map { JsonReader().parse(it) }
            val identities = records.single { it.getString("kind") == "input-identities" }
            assertEquals("p005-turret", identities.getString("scenarioId"))
            assertEquals(root.resolve("turret.properties"), Path.of(identities.get("sandboxManifest").getString("path")))
            val files = identities.get("files").map { it }
            assertEquals(20, files.size)
            val expected = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList().toSet() }
            assertEquals(expected, files.map { Path.of(it.getString("path")) }.toSet())
            for (file in files) {
                val path = Path.of(file.getString("path"))
                assertEquals(Files.size(path), file.getLong("bytes"))
                assertEquals(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }, file.getString("sha256"))
            }
        }
    }

    private fun withDirectory(check: (Path) -> Unit) {
        val directory = Files.createTempDirectory("eng003-pc-identity-").toRealPath()
        try { check(directory) } finally {
            kotlin.check(directory.parent == Path.of(System.getProperty("java.io.tmpdir")).toRealPath())
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
                kotlin.check(it.toAbsolutePath().normalize().startsWith(directory)); Files.delete(it)
            } }
        }
    }
}
