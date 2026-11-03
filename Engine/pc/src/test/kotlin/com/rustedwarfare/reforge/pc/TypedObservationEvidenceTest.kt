package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.rustedwarfare.reforge.client.*
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import java.io.BufferedWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import kotlin.test.*

class TypedObservationEvidenceTest {
    @Test fun actual_named_session_typed_jsonl_hides_enemy_cooldowns_and_preserves_owner_values() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val inputs = fixtures.resolve("named-weapons/json")
        val content = when (val loaded = ContentLoader.loadContentPacks(listOf(inputs.resolve("package")))) {
            is ContentResult.Success -> loaded.value
            is ContentResult.Failure -> error(loaded.error.toString())
        }
        val scene = SandboxLoader().use { loader ->
            loader.requestPack(inputs, ContentPackChoice(content, "named_tank"))
            val deadline = System.nanoTime() + 5_000_000_000L
            var loaded = loader.pollPrepared()
            while (loaded == null && System.nanoTime() < deadline) { Thread.sleep(5); loaded = loader.pollPrepared() }
            checkNotNull(loaded) { "Content preparation timed out" }.getOrThrow().scenario
        }
        val root = Files.createTempDirectory("named-cooldown-jsonl-").toAbsolutePath().normalize()
        try {
            val expected = SandboxSession(scene).use { session ->
                repeat(3) { session.tick() }
                val view = session.observation
                JsonlEvidence(root.resolve("run"), "named-cooldown", inputs).use { evidence ->
                    evidence.recordObservation("step", view, 1)
                }
                view.weapons.filter { it.unitId == "a" }.associate { it.weaponName to it.cooldown }
            }
            val rows = Files.readAllLines(root.resolve("run/reforge/client-diagnostic.jsonl")).map { JsonReader().parse(it) }
            val sample = rows.single { it.getString("kind") == "observation" }.get("sample")
            val weapons = sample.get("weapons").toList()
            val owner = weapons.filter { it.getString("unitId") == "a" }
            val enemies = weapons.filter { it.getString("unitId") != "a" }
            assertEquals(setOf("near", "far"), owner.map { it.getString("weaponName") }.toSet())
            assertTrue(enemies.isNotEmpty())
            assertTrue(enemies.all { it.get("cooldown").isNull && it.get("warmup").isNull && it.get("targetUnitId").isNull })
            assertEquals(expected, owner.associate { it.getString("weaponName") to it.getDouble("cooldown") })
        } finally {
            require(root.isAbsolute && root.fileName.toString().startsWith("named-cooldown-jsonl-"))
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
                require(it.toAbsolutePath().normalize().startsWith(root)); Files.delete(it)
            } }
        }
    }

    private fun session(mode: SandboxMode) = SandboxSession(
        ControlledScenario.load(Path.of(checkNotNull(System.getProperty("p001.inputs.dir")))),
        ClientEvidence.NONE, SandboxOptions(mode))

    @Test fun typed_record_has_exact_legacy_json_order_and_safe_byte_bound_in_each_mode() {
        for (mode in SandboxMode.entries) {
            val session = session(mode); val view = session.observation
            var expected: Map<String, Any?>? = null
            ClientEvidence { _, values -> expected = values }.recordObservation("step", view, 7)
            val fixed = FixedDiagnosticRecord.observation("step", view, 7)
            session.tick(); session.close()
            val encoded = evidenceJson(fixed.values)
            assertEquals(evidenceJson(checkNotNull(expected)), encoded)
            assertTrue(encoded.toByteArray(Charsets.UTF_8).size.toLong() <= fixed.byteUpperBound)
        }
    }

    @Test fun queued_typed_observation_stays_at_capture_time_after_world_advance_and_close() {
        val session = session(SandboxMode.MEMORY); val view = session.observation
        var expected: Map<String, Any?>? = null
        ClientEvidence { _, values -> expected = values }.recordObservation("step", view, 7)
        val root = Files.createTempDirectory("p007-typed-evidence-")
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val text = StringWriter()
        val writer = object : BufferedWriter(text) {
            override fun write(value: String) {
                if (value.contains("\"label\":\"hold\"") || value == "\"hold\"") {
                    entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
                }
                super.write(value)
            }
        }
        val evidence = JsonlEvidence(root.resolve("run"), "typed-test", root.resolve("input")) { writer }
        val pool = Executors.newSingleThreadExecutor()
        try {
            evidence.record("observation", mapOf("phase" to "step", "label" to "hold"))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            pool.submit { evidence.recordObservation("step", view, 7) }.get(500, TimeUnit.MILLISECONDS)
            session.tick(); session.tick(); session.close()
            release.countDown(); evidence.record("pause", emptyMap()); evidence.close()
            val rows = text.toString().lineSequence().filter { it.isNotBlank() }.map { JsonReader().parse(it) }.toList()
            assertEquals(listOf(0L, 1L, 2L), rows.map { it.getLong("sequence") })
            val wanted = JsonReader().parse(evidenceJson(checkNotNull(expected))).get("sample")
            assertEquals(wanted.toString(), rows[1].get("sample").toString())
            assertEquals(7L, rows[1].getLong("generation"))
        } finally { release.countDown(); pool.shutdownNow(); session.close(); runCatching { evidence.close() } }
    }
}
