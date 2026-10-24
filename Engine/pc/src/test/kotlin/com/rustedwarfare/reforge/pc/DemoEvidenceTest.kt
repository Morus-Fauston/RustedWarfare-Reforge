package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import java.nio.file.Files
import kotlin.test.*

class DemoEvidenceTest {
    @Test fun demo_keeps_its_schema_sequence_and_immediate_flush_for_nested_values() {
        val root = Files.createTempDirectory("p004-demo-evidence-")
        val output = root.resolve("run")
        DemoEvidence(output).use { evidence ->
            evidence.record("values", linkedMapOf("text" to "中文\n\"\\", "nested" to listOf(null, true, 1, 1.5)))
            // Read before close: the existing flush contract must remain.
            val lines = Files.readAllLines(output.resolve("demo.jsonl"))
            assertEquals(2, lines.size)
            val manifest = JsonReader().parse(lines[0])
            assertEquals("reforge.windows-demo.v1", manifest.getString("format"))
            assertEquals("ENG-P002-006", manifest.getString("ticket"))
            val values = JsonReader().parse(lines[1])
            assertEquals(1, values.getLong("sequence"))
            assertEquals("中文\n\"\\", values.getString("text"))
            assertTrue(values.get("nested").get(0).isNull)
            assertEquals(4, values.get("nested").size)
            assertEquals(1.5, values.get("nested").get(3).asDouble())
        }
        assertFails { DemoEvidence(output) }
        assertEquals(2, Files.readAllLines(output.resolve("demo.jsonl")).size)
    }
}
