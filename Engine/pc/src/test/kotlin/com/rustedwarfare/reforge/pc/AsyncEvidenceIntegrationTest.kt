package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import java.io.BufferedWriter
import java.io.StringWriter
import java.nio.file.Files
import java.util.concurrent.*
import kotlin.test.*

class AsyncEvidenceIntegrationTest {
    @Test fun step_observation_accepts_before_slow_write_then_barrier_persists_detached_records_in_order() {
        val root = Files.createTempDirectory("p007-evidence-integration-")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val text = StringWriter()
        val writer = object : BufferedWriter(text) {
            override fun write(value: String) {
                if (value.contains("\"kind\":\"observation\"") || value == "\"observation\"") {
                    entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
                }
                super.write(value)
            }
        }
        val evidence = JsonlEvidence(root.resolve("run"), "test-run", root.resolve("input")) { writer }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit { evidence.record("observation", mapOf("phase" to "step", "label" to "hold")) }
            first.get(500, TimeUnit.MILLISECONDS)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val mutable = linkedMapOf<String, Any?>("text" to "before")
            evidence.record("observation", mapOf("phase" to "step", "nested" to listOf(mutable)))
            mutable["text"] = "after"
            release.countDown()
            evidence.record("pause", emptyMap())
            val rows = text.toString().lineSequence().filter { it.isNotBlank() }.map { JsonReader().parse(it) }.toList()
            assertEquals(listOf(0L, 1L, 2L), rows.map { it.getLong("sequence") })
            assertEquals("before", rows[1].get("nested").get(0).getString("text"))
            assertEquals("pause", rows.last().getString("kind"))
        } finally { release.countDown(); pool.shutdownNow(); evidence.close() }
    }
}
