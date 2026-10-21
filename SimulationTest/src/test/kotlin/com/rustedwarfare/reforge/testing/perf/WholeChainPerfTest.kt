package com.rustedwarfare.reforge.testing.perf

import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import kotlin.test.*

class WholeChainPerfTest {
    @Test fun fifty_entities_execute_fifty_requests_on_one_boundary_and_reach_terminal() {
        val directory = java.nio.file.Files.createTempDirectory("s08-open50-")
        println("s08-raw=$directory")
        val run = java.nio.file.Files.newBufferedWriter(directory.resolve("raw.jsonl")).use { writer ->
            WholeChainPerf.capture(WholeChainPerf.fixture("OPEN", 50, TerrainRetention.MEMORY)) { writer.write(it.toString()); writer.newLine(); writer.flush() }
        }
        assertEquals(50, run.requests)
        assertEquals(50, run.searches.count { it.reason.name == "USER_MOVE" })
        assertEquals(setOf(0L), run.searches.map { it.executionStep }.toSet())
        assertEquals(50, run.arrived.size)
        assertTrue(run.reuseEntitySteps > 0)
        WholeChainPerf.qualify(run)
        assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(run.copy(requests = 49)) }
        assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(run.copy(steps = run.steps.dropLast(1))) }
        val steps = run.steps.toMutableList()
        steps[0] = steps[0].copy(samples = steps[0].samples.dropLast(1))
        assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(run.copy(steps = steps)) }
    }

    @Test fun empty_distributions_are_explicitly_unavailable_and_nearest_rank_is_fixed() {
        assertEquals("{\"n\":0,\"p50\":null,\"p95\":null,\"max\":null}", WholeChainPerf.stats(emptyList()).toString())
        assertEquals("{\"n\":5,\"p50\":30,\"p95\":50,\"max\":50}", WholeChainPerf.stats(listOf(50, 10, 30, 40, 20)).toString())
    }

    @Test fun qualification_rejects_nonfinite_motion_missing_repair_and_retention_corruption() {
        val run = WholeChainPerf.capture(WholeChainPerf.fixture("HIDDEN", 1, TerrainRetention.MEMORY))
        val repair = run.steps.indexOfFirst { step -> step.samples.any { it.reason.name == "ROUTE_REPAIR" } }
        assertTrue(repair > 0)
        val steps = run.steps.toMutableList()
        steps[repair] = steps[repair].copy(samples = emptyList())
        assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(run.copy(steps = steps)) }
        val corrupt = run.steps.toMutableList()
        corrupt[0] = corrupt[0].copy(known = List(2000) { true })
        assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(run.copy(steps = corrupt)) }
        assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(run.copy(arrived = emptySet())) }
        val first = run.steps.first()
        val observed = first.observation
        val invalid = com.rustedwarfare.reforge.core.sim.Observation(observed.sampleId, observed.worldId,
            observed.scenarioId, observed.stepIndex, observed.simulationTime,
            observed.units.map { it.copy(heading = Double.NaN) }, observed.events, observed.actionResults)
        assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(run.copy(steps = listOf(first.copy(observation = invalid)) + run.steps.drop(1))) }
    }

    @Test fun altered_non_user_search_anchor_and_prefix_are_rejected() {
        val run = WholeChainPerf.capture(WholeChainPerf.fixture("HIDDEN", 1, TerrainRetention.MEMORY))
        val index = run.steps.indexOfFirst { it.samples.any { s -> s.reason.name != "USER_MOVE" } }
        assertTrue(index > 0)
        for (badPrefix in listOf(false, true)) {
            val steps = run.steps.toMutableList(); val step = steps[index]
            steps[index] = step.copy(samples = step.samples.map {
                if (badPrefix) it.copy(preservedPrefixPointCount = 999)
                else it.copy(searchStart = com.rustedwarfare.reforge.core.sim.Position(250.0, 270.0))
            })
            assertFailsWith<IllegalStateException> { WholeChainPerf.qualify(run.copy(steps = steps)) }
        }
    }

    @Test fun loss_of_sight_counts_transitions_equally_for_identical_open_motion() {
        val memory = WholeChainPerf.summary(WholeChainPerf.capture(WholeChainPerf.fixture("OPEN", 1, TerrainRetention.MEMORY)))
        val hardcore = WholeChainPerf.summary(WholeChainPerf.capture(WholeChainPerf.fixture("OPEN", 1, TerrainRetention.HARDCORE_FORGET)))
        assertEquals(memory["lostSightCellTransitions"], hardcore["lostSightCellTransitions"])
    }
}
