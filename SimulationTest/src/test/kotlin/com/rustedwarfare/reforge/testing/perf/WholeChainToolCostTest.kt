package com.rustedwarfare.reforge.testing.perf

import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import com.rustedwarfare.reforge.core.sim.Observation
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*
import kotlin.test.*

class WholeChainToolCostTest {
    @Test fun captured_cost_metadata_never_exempts_an_edited_middle_step_from_independent_qualification() {
        val run = WholeChainPerf.capture(WholeChainPerf.fixture("OPEN", 1, TerrainRetention.MEMORY))
        val index = run.steps.size / 2
        val steps = run.steps.toMutableList()
        val old = steps[index]
        val observation = old.observation
        steps[index] = old.copy(observation = Observation(observation.sampleId, observation.worldId,
            observation.scenarioId, observation.stepIndex, observation.simulationTime,
            observation.units.map { it.copy(position = Position(-100.0, -100.0)) },
            observation.events, observation.actionResults))
        assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(run.copy(steps = steps)) }
    }

    @Test fun complete_public_capture_reports_disjoint_loop_costs_and_its_final_validation_boundary() {
        val rows = mutableListOf<JsonObject>()
        val run = WholeChainPerf.capture(WholeChainPerf.fixture("OPEN", 1, TerrainRetention.MEMORY), raw = rows::add)
        val summary = WholeChainPerf.summary(run)
        val costs = summary.objectAt("toolCosts")
        assertEquals(s("System.nanoTime"), costs["clock"])
        for (field in listOf("initializationNanos", "advanceNanos", "samplingNanos", "onlineValidationNanos",
            "traceEnvelopeNanos", "completionValidationNanos"))
            assertTrue(costs.integer(field) >= 0, field)
        assertTrue(costs.text("boundary").contains("callback"))
        assertEquals(run.steps.size + 2, rows.size)
        assertEquals(summary, rows.last().objectAt("summary"))
        assertEquals((0 until run.steps.size).toList(), rows.drop(1).dropLast(1).map { it.integer("step").toInt() })
        WholeChainPerf.qualify(run)
    }
}
