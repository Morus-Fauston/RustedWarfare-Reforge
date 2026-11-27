package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScenarioCatalogTest {
    @Test
    fun dev_catalog_contains_all_required_p016_scenes_with_unique_ids_and_valid_initialization() {
        val entries = ScenarioCatalog.devBenchmarkEntries()
        val ids = entries.map { it.id }
        assertEquals(ids.distinct().size, ids.size, "Catalog scenario IDs must be unique")
        val required = listOf(
            "rocks-small-100",
            "rocks-mixed-100",
            "opposed-open-25v25",
            "opposed-corridor-8v8",
            "narrow-bridge-yield",
            "mixed-overtake-wide",
            "mixed-overtake-single-pull",
            "air-flock-rally",
            "open-50-217-166",
            "culdesac-overflow-100",
        )
        for (id in required) {
            assertNotNull(ScenarioCatalog.byId(id), "Missing required catalog scenario: $id")
        }
        // Strictly A/B dev groups only in default catalog (no C-F holdouts leaked into dev default)
        for (entry in entries) {
            assertTrue(entry.group in setOf("A", "B"), "Default dev catalog must only contain A/B groups, got ${entry.id}:${entry.group}")
            assertEquals(ScenarioCategory.BENCHMARK_P016, entry.category)
        }
        assertNotNull(ScenarioCatalog.byId("culdesac-overflow-100")?.knownLimitation)
        assertNull(ScenarioCatalog.byId("rocks-small-100")?.knownLimitation)

        val baseDefs = TestContentFactory.openScenario().unitDefinitions
        val derived = ScenarioCatalog.deriveBenchmarkDefinitions(baseDefs)
        assertTrue(setOf("c_tank", "fast_scout", "slow_heavy", "helicopter").all { it in derived })

        for (entry in entries) {
            val map = entry.buildMap()
            val hash1 = ScenarioCatalog.mapHash(map)
            val hash2 = ScenarioCatalog.mapHash(entry.buildMap())
            assertEquals(64, hash1.length)
            assertEquals(hash1, hash2, "mapHash must be deterministic for ${entry.id}")
            val scenario = entry.buildScenario(derived)
            assertEquals(entry.startingUnitCount, scenario.units.size, "Unit count mismatch for ${entry.id}")
            val init = SimulationContext.initialize(scenario)
            assertTrue(init is InitResult.Accepted, "Scenario ${entry.id} failed to initialize: $init")
            (init as InitResult.Accepted).context.close()
        }

        val pHash1 = MovementParameterFingerprint.sha256Hex()
        val pHash2 = MovementParameterFingerprint.sha256Hex()
        assertEquals(64, pHash1.length)
        assertEquals(pHash1, pHash2)
    }

    @Test
    fun mixed_overtake_single_pull_executes_midroute_pull_and_passes_assertions() {
        val entry = checkNotNull(ScenarioCatalog.byId("mixed-overtake-single-pull"))
        val defs = ScenarioCatalog.deriveBenchmarkDefinitions(TestContentFactory.openScenario().unitDefinitions)
        val scenario = entry.buildScenario(defs)
        val ctx = (SimulationContext.initialize(scenario) as InitResult.Accepted).context
        try {
            val obs = ArrayList<Observation>(entry.defaultSteps + 1)
            entry.issueOrders(ctx)
            obs += ctx.observe()
            for (s in 0L until entry.defaultSteps.toLong()) {
                entry.onStep(ctx, s)
                obs += ctx.advance(Step(s, 1.0, "sim-tick"))
            }
            val seq = DiagnosticSceneProjector.projectSequence(obs, scenario.unitDefinitions, gates = entry.gates)
            val failures = entry.assertOutcome(seq)
            assertTrue(failures.isEmpty(), "Expected mixed-overtake-single-pull assertions to pass, got: $failures")
        } finally {
            ctx.close()
        }
    }
}
