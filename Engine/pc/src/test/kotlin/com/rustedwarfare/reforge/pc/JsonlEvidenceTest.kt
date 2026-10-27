package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JsonlEvidenceTest {
    @Test fun construction_evidence_binds_only_loaded_inputs_and_uses_its_own_ticket() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val root = repo.resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-001/20261027-intake-01/scene-input-01")
        val temporary = Files.createTempDirectory("p006-construction-evidence-")
        try {
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "construction-run", root).use {
                it.record("load-request", mapOf("generation" to 1L, "root" to root.toString()))
                it.record("load-ready", mapOf("generation" to 1L))
            }
            val manifest = JsonReader().parse(Files.readString(directory.resolve("manifest.json")))
            assertEquals("ENG-P006-001", manifest.getString("ticket"))
            assertEquals("P006", manifest.getString("phase"))
            val identities = records(directory).single { it.getString("kind") == "input-identities" }
            assertEquals("p006-construction", identities.getString("scenarioId"))
            assertEquals(root.resolve("construction.properties").toString(), identities.get("sandboxManifest").getString("path"))
            val files = identities.get("files").toList()
            val unused = setOf("manifest.json", "p005-corridor.tmx", "p005-sealed.tmx")
            val expected = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString() !in unused }.toList().toSet() }
            assertEquals(expected, files.map { Path.of(it.getString("path")) }.toSet())
            for (row in files) {
                val file = Path.of(row.getString("path"))
                assertEquals(Files.size(file), row.getLong("bytes"))
                assertEquals(evidenceSha256(file), row.getString("sha256"))
            }
        } finally { temporary.toFile().deleteRecursively() }
    }

    @Test fun fixed_defense_evidence_labels_005_and_identifies_every_one_of_the_eighteen_real_inputs() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val root = repo.resolve(".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-005/20261026-e-defense-input-01/input")
        val temporary = Files.createTempDirectory("p005-defense-evidence-")
        try {
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "defense-run", root).use {
                it.record("load-request", mapOf("generation" to 1L, "root" to root.toString()))
                it.record("load-ready", mapOf("generation" to 1L))
            }
            val manifest = JsonReader().parse(Files.readString(directory.resolve("manifest.json")))
            assertEquals("ENG-P005-005", manifest.getString("ticket"))
            val identities = records(directory).single { it.getString("kind") == "input-identities" }
            assertEquals("p005-point-defense", identities.getString("scenarioId"))
            assertEquals(root.resolve("point-defense.properties").toString(), identities.get("sandboxManifest").getString("path"))
            val files = identities.get("files").toList()
            assertEquals(18, files.size)
            val expected = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.toList().toSet() }
            assertEquals(expected, files.map { Path.of(it.getString("path")) }.toSet())
            for (row in files) {
                val file = Path.of(row.getString("path"))
                assertEquals(Files.size(file), row.getLong("bytes"))
                assertEquals(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
                    .joinToString("") { "%02x".format(it) }, row.getString("sha256"))
            }
        } finally { temporary.toFile().deleteRecursively() }
    }

    @Test fun fixed_turret_evidence_labels_003_and_identifies_the_twenty_fixed_input_files() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val root = repo.resolve(".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-003/20261026-e-turret-contract-01/input")
        val temporary = Files.createTempDirectory("p005-turret-evidence-")
        try {
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "turret-run", root).use {
                it.record("load-request", mapOf("generation" to 1L, "root" to root.toString()))
                it.record("load-ready", mapOf("generation" to 1L))
            }
            val manifest = JsonReader().parse(Files.readString(directory.resolve("manifest.json")))
            assertEquals("P005", manifest.getString("phase"))
            assertEquals("ENG-P005-003", manifest.getString("ticket"))
            val identities = records(directory).single { it.getString("kind") == "input-identities" }
            assertEquals("p005-turret", identities.getString("scenarioId"))
            assertEquals(root.resolve("turret.properties").toString(), identities.get("sandboxManifest").getString("path"))
            val files = identities.get("files").toList()
            assertEquals(20, files.size)
            val expected = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.toList().toSet() }
            assertEquals(expected, files.map { Path.of(it.getString("path")) }.toSet())
            for (row in files) {
                val file = Path.of(row.getString("path"))
                assertEquals(Files.size(file), row.getLong("bytes"))
                assertEquals(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
                    .joinToString("") { "%02x".format(it) }, row.getString("sha256"))
            }
        } finally { temporary.toFile().deleteRecursively() }
    }

    private val properties = """
        format=rw-reforge.sandbox.v1
        p1.team=blue
        p1.x=110
        p1.y=110
        p1.heading=90
        p2.team=blue
        p2.x=510
        p2.y=310
        p2.heading=0
        p3.team=red
        p3.x=550
        p3.y=310
        p3.heading=0
    """.trimIndent()

    private fun fixture(root: Path, withProperties: Boolean = true): Path {
        Files.createDirectories(root.resolve("maps"))
        Files.createDirectories(root.resolve("tilesets"))
        Files.createDirectories(root.resolve("units"))
        DataOutputStream(Files.newOutputStream(root.resolve("atlas.png"))).use {
            it.writeLong(0x89504E470D0A1A0AuL.toLong())
            it.writeInt(13); it.writeInt(0x49484452); it.writeInt(20); it.writeInt(20)
        }
        val tileset = """
            <tileset name="own_test" tilewidth="20" tileheight="20" tilecount="1" columns="1">
              <image source="../atlas.png" width="20" height="20"/>
            </tileset>
        """.trimIndent()
        Files.writeString(root.resolve("tilesets/p001-ground.tsx"), tileset)
        Files.writeString(root.resolve("tilesets/p001-rock.tsx"), tileset)
        Files.writeString(root.resolve("maps/p001-controlled.tmx"), """
            <map orientation="orthogonal" renderorder="right-down" width="30" height="20" tilewidth="20" tileheight="20">
              <tileset firstgid="1" source="../tilesets/p001-ground.tsx"/>
              <tileset firstgid="2" source="../tilesets/p001-rock.tsx"/>
              <layer name="Ground" width="30" height="20"><data encoding="csv">${List(600) { "1" }.joinToString(",")}</data></layer>
            </map>
        """.trimIndent())
        Files.writeString(root.resolve("units/weaponless_tank.ini"), """
            [core]
            name: own_test_tank
            class: CustomUnitMetadata
            radius: 11
            displayRadius: 11
            fogOfWarSightRange: 3
            [graphics]
            total_frames: 1
            image: ../atlas.png
            [movement]
            movementType: LAND
            moveSpeed: 1.1
            moveAccelerationSpeed: 0.07
            moveDecelerationSpeed: 0.17
            maxTurnSpeed: 4.1
            turnAcceleration: 0.25
            moveSlidingMode: false
            moveIgnoringBody: false
        """.trimIndent())
        if (withProperties) Files.writeString(root.resolve("sandbox.properties"), properties)
        return root
    }

    private fun records(directory: Path): List<JsonValue> =
        Files.readAllLines(directory.resolve("reforge/client-diagnostic.jsonl")).map { JsonReader().parse(it) }

    @Test fun we02_new_windows_manifest_labels_the_current_phase_and_preserves_the_diagnostic_schema() {
        val temporary = Files.createTempDirectory("reforge-windows-evidence-test-")
        try {
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "test-run", temporary.resolve("inputs")).use { }
            val manifest = JsonReader().parse(Files.readString(directory.resolve("manifest.json")))
            assertEquals("p001.windows-diagnostic.v1", manifest.getString("format"))
            assertEquals("P002", manifest.getString("phase"))
            assertEquals("ENG-P002-009", manifest.getString("ticket"))
            assertEquals("03-windows-client", manifest.getString("topic"))
            assertEquals("test-run", manifest.getString("runId"))
            assertFalse(manifest.getBoolean("originalGameExecuted"))
            assertEquals("not-evaluated", manifest.getString("compatibilityVerdict"))
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    @Test fun we03_loaded_p002_records_the_configuration_bytes_after_the_legacy_file_list() {
        val temporary = Files.createTempDirectory("reforge-windows-evidence-test-")
        try {
            val root = fixture(temporary.resolve("p002"))
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "p002-run", temporary.resolve("unused-initial-inputs")).use {
                it.record("load-request", mapOf("generation" to 7L, "root" to root.toString()))
                it.record("load-ready", mapOf("generation" to 7L))
            }
            val identities = records(directory).single { it.getString("kind") == "input-identities" }
            assertEquals("p002-knowledge", identities.getString("scenarioId", null))
            assertEquals(root.toString(), identities.getString("root"))
            assertEquals(7L, identities.getLong("generation"))
            val files = identities.get("files").toList()
            assertEquals(root.resolve("maps/p001-controlled.tmx").toString(), files.first().getString("path"))
            val manifest = assertNotNull(identities.get("sandboxManifest"))
            assertEquals(root.resolve("sandbox.properties").toString(), manifest.getString("path"))
            assertEquals(Files.size(root.resolve("sandbox.properties")), manifest.getLong("bytes"))
            val digest = MessageDigest.getInstance("SHA-256").digest(properties.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals(digest, manifest.getString("sha256"))
            assertEquals(manifest.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json),
                files.last().toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json))
            assertEquals(1, files.count { it.getString("path") == root.resolve("sandbox.properties").toString() })
            assertTrue(files.all { Files.isRegularFile(Path.of(it.getString("path"))) && it.has("sha256") })
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    @Test fun we04_absent_p001_configuration_is_explicit_without_adding_a_missing_file_to_the_legacy_list() {
        val temporary = Files.createTempDirectory("reforge-windows-evidence-test-")
        try {
            val root = fixture(temporary.resolve("p001"), withProperties = false)
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "p001-run", root).use {
                it.record("load-request", mapOf("generation" to 3L, "root" to root.toString()))
                it.record("load-ready", mapOf("generation" to 3L))
            }
            val identities = records(directory).single { it.getString("kind") == "input-identities" }
            assertEquals("p001-windows", identities.getString("scenarioId"))
            val manifest = assertNotNull(identities.get("sandboxManifest"))
            assertFalse(manifest.isNull, "P001 must explicitly identify the absent optional configuration")
            assertEquals(root.resolve("sandbox.properties").toString(), manifest.getString("path"))
            assertFalse(manifest.getBoolean("exists"))
            val files = identities.get("files").toList()
            assertEquals(root.resolve("maps/p001-controlled.tmx").toString(), files.first().getString("path"))
            assertEquals(5, files.size)
            assertTrue(files.none { it.getString("path").endsWith("sandbox.properties") })
            assertTrue(files.all { Files.isRegularFile(Path.of(it.getString("path"))) && it.has("sha256") })
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    @Test fun we05_each_generation_uses_its_requested_root_and_keeps_a_single_event_sequence() {
        val temporary = Files.createTempDirectory("reforge-windows-evidence-test-")
        try {
            val p002 = fixture(temporary.resolve("p002"))
            val p001 = fixture(temporary.resolve("p001"), withProperties = false)
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "generations", temporary.resolve("unused")).use {
                for ((generation, root) in listOf(7L to p002, 9L to p001)) {
                    it.record("load-request", mapOf("generation" to generation, "root" to root.toString()))
                    it.record("load-ready", mapOf("generation" to generation))
                }
            }
            val rows = records(directory)
            assertEquals((0L..5L).toList(), rows.map { it.getLong("sequence") })
            val identities = rows.filter { it.getString("kind") == "input-identities" }
            assertEquals(listOf(7L, 9L), identities.map { it.getLong("generation") })
            assertEquals(listOf(p002.toString(), p001.toString()), identities.map { it.getString("root") })
            assertEquals(listOf("p002-knowledge", "p001-windows"), identities.map { it.getString("scenarioId") })
            assertTrue(identities[0].get("sandboxManifest").has("sha256"))
            assertFalse(identities[1].get("sandboxManifest").getBoolean("exists"))
            for ((index, root) in listOf(p002, p001).withIndex()) {
                assertTrue(identities[index].get("files").all { Path.of(it.getString("path")).startsWith(root) })
            }
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    @Test fun we06_invalid_configuration_cannot_emit_successful_identities_and_a_later_load_recovers() {
        val temporary = Files.createTempDirectory("reforge-windows-evidence-test-")
        try {
            val invalid = fixture(temporary.resolve("invalid"))
            Files.writeString(invalid.resolve("sandbox.properties"), properties + "\nunknown=1")
            val valid = fixture(temporary.resolve("valid"))
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "recovery", invalid).use {
                it.record("load-request", mapOf("generation" to 1L, "root" to invalid.toString()))
                assertFailsWith<IllegalArgumentException> { it.record("load-ready", mapOf("generation" to 1L)) }
                it.record("load-request", mapOf("generation" to 2L, "root" to valid.toString()))
                it.record("load-ready", mapOf("generation" to 2L))
            }
            val identities = records(directory).filter { it.getString("kind") == "input-identities" }
            assertEquals(1, identities.size)
            assertEquals(2L, identities.single().getLong("generation"))
            assertEquals(valid.toString(), identities.single().getString("root"))
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    @Test fun we02_reusing_an_existing_run_directory_is_rejected_without_overwriting_the_manifest() {
        val temporary = Files.createTempDirectory("reforge-windows-evidence-test-")
        try {
            val directory = temporary.resolve("run")
            JsonlEvidence(directory, "original-run", temporary.resolve("inputs")).use { }
            val original = Files.readAllBytes(directory.resolve("manifest.json"))
            assertFailsWith<java.nio.file.FileAlreadyExistsException> {
                JsonlEvidence(directory, "replacement", temporary.resolve("different-inputs"))
            }
            assertTrue(original.contentEquals(Files.readAllBytes(directory.resolve("manifest.json"))))
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }
}
