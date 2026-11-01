package com.rustedwarfare.reforge.client

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ObservationDiagnosticTest {
    private fun session(mode: SandboxMode = SandboxMode.MEMORY) = SandboxSession(
        ControlledScenario.load(Path.of(checkNotNull(System.getProperty("p001.inputs.dir")))),
        ClientEvidence.NONE, SandboxOptions(mode))

    @Test fun fixed_observation_reuses_immutable_knowledge_and_survives_advance_and_close() {
        val session = session(); val view = session.observation
        val expected = mapOf("phase" to "step", "sample" to view.fields(), "generation" to 7L)
        val time = AtomicInteger(17); val metadata = linkedMapOf<String, Any?>("at" to time)
        val fixed = FixedDiagnosticRecord.observation("step", view, 7, metadata)
        assertSame(view.knowledge, fixed.knowledgeSnapshot)
        time.set(23); metadata.clear()
        session.tick(); session.tick(); session.close()
        assertEquals(expected, fixed.values); assertEquals(expected, fixed.values)
        assertEquals("17", fixed.metadata["at"].toString())
    }

    @Test fun deferred_knowledge_values_and_entries_are_stable_read_only_values() {
        val session = session(); val fixed = FixedDiagnosticRecord.observation("step", session.observation)
        session.close()
        val values = fixed.values; val sample = values["sample"] as Map<*, *>
        val cells = sample["knowledge"] as List<*>; assertEquals(600, cells.size)
        val cell = cells[0] as Map<*, *>; val entries = cell.entries.toList()
        assertEquals(listOf("visible", "blocked", "gid"), entries.map { it.key })
        assertEquals(listOf(cell["visible"], cell["blocked"], cell["gid"]), entries.map { it.value })
        assertFails { (values as MutableMap<*, *>).clear() }
        assertFails { (sample as MutableMap<*, *>).clear() }
        assertFails { (cells as MutableList<*>).clear() }
        assertFails { (cell as MutableMap<*, *>).clear() }
        assertEquals(cell, (fixed.values["sample"] as Map<*, *>)["knowledge"].let { (it as List<*>)[0] })
    }

    @Test fun no_fog_observation_keeps_legacy_field_shape_and_null_knowledge() {
        val session = session(SandboxMode.NO_FOG); val view = session.observation
        val fixed = FixedDiagnosticRecord.observation("after-action", view)
        assertNull(fixed.knowledgeSnapshot)
        assertEquals(mapOf("phase" to "after-action", "sample" to view.fields()), fixed.values)
        session.close()
    }
}
