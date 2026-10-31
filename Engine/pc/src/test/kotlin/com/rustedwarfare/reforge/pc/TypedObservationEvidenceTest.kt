package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.rustedwarfare.reforge.client.*
import java.io.BufferedWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import kotlin.test.*

class TypedObservationEvidenceTest {
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
