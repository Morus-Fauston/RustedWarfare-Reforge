package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.PlayerObservation
import java.nio.file.Path
import kotlin.test.*

class ObservationEvidenceRoutingTest {
    private fun scenario() = ControlledScenario.load(Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))))

    @Test fun legacy_lambda_gets_the_same_fields_and_generation_override() {
        val session = SandboxSession(scenario(), options = SandboxOptions(SandboxMode.MEMORY))
        val view = session.observation; val records = mutableListOf<Pair<String, Map<String, Any?>>>()
        val sink = ClientEvidence { kind, values -> records.add(kind to values) }.withGeneration(7)
        sink.recordObservation("step", view, 99)
        assertEquals(listOf<Pair<String, Map<String, Any?>>>("observation" to mapOf("phase" to "step", "sample" to view.fields(), "generation" to 7L)), records)
        session.close()
    }

    @Test fun generation_wrapper_forwards_typed_snapshot_without_calling_generic_observation() {
        val session = SandboxSession(scenario()); val view = session.observation
        val seen = mutableListOf<Triple<String, PlayerObservation, Long?>>()
        val generic = mutableListOf<Map<String, Any?>>()
        val sink = object : ClientEvidence {
            override fun record(kind: String, values: Map<String, Any?>) {
                check(kind != "observation"); generic.add(values)
            }
            override fun recordObservation(phase: String, observation: PlayerObservation, generation: Long?) {
                seen.add(Triple(phase, observation, generation))
            }
        }.withGeneration(7)
        sink.recordObservation("step", view, 99)
        assertEquals("step", seen.single().first); assertSame(view, seen.single().second); assertEquals(7L, seen.single().third)
        sink.record("pause", mapOf("generation" to 99L)); assertEquals(7L, generic.single()["generation"])
        session.close()
    }

    @Test fun disabled_generation_wrapper_remains_the_canonical_no_preparation_sink() {
        val sink = ClientEvidence.NONE.withGeneration(7)
        assertSame(ClientEvidence.NONE, sink)
        sink.recordWhenEnabled("observation") { error("disabled fields prepared") }
    }

    @Test fun formal_sandbox_capture_uses_typed_path_for_initial_step_and_close() {
        val seen = mutableListOf<Triple<String, PlayerObservation, Long?>>()
        val sink = object : ClientEvidence {
            override fun record(kind: String, values: Map<String, Any?>) { check(kind != "observation") }
            override fun recordObservation(phase: String, observation: PlayerObservation, generation: Long?) {
                seen.add(Triple(phase, observation, generation))
            }
        }.withGeneration(7)
        val session = SandboxSession(scenario(), sink, SandboxOptions(SandboxMode.MEMORY))
        assertSame(session.observation, seen.single().second)
        session.tick(); assertSame(session.observation, seen.last().second); session.close()
        assertEquals(listOf("initial", "step", "before-close"), seen.map { it.first })
        assertTrue(seen.all { it.third == 7L })
    }
}
