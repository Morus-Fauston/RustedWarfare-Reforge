package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.client.TestScenarioChoice
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class TestScenarioSeedParityTest {
    @Test fun the_shared_dual_ship_seed_satisfies_its_independent_paid_upgrade_and_fifo_contract() {
        val public = Path.of(checkNotNull(System.getProperty("repo.root"))).resolve("SimulationTest/public")
        val output = Files.createTempDirectory("owned-shared-scene-core-").resolve("run")
        val result = Runner.run(RunRequest(public.resolve("scenarios/dual-ship-production.json"),
            public.resolve("contracts/dual-ship-production.json"), output, listOf("api:Runner.run/TestScenarioSeedParityTest")))
        assertEquals(0, result.exitCode, result.toString())
        assertEquals(0, TraceFiles.compare(public.resolve("scenarios/dual-ship-production.json"),
            public.resolve("contracts/dual-ship-production.json"), output).exitCode)
    }

    @Test fun core_menu_and_session_read_the_same_seed_initial_state_and_operation_schedule() {
        val public = Path.of(checkNotNull(System.getProperty("repo.root"))).resolve("SimulationTest/public")
        val seed = public.resolve("scenarios/dual-ship-production.json")
        val core = ScenarioReader.load(seed, public.resolve("contracts/dual-ship-production.json")).core
        val menu = TestScenarioChoice.open(seed)
        assertEquals(core.units, menu.scenario.units)
        assertEquals(core.players, menu.scenario.players)
        assertEquals(core.coordinateModel, menu.scenario.coordinateModel)
        assertEquals(core.timeModel, menu.scenario.timeModel)
        assertEquals(core.movementContract, menu.scenario.movementContract)
        assertEquals(core.combatContract, menu.scenario.combatContract)
        assertEquals(core.economyContract, menu.scenario.economyContract)
        assertEquals(core.unitDefinitions.keys, menu.scenario.unitDefinitions.keys)
        assertContentEquals(core.map.groundGids, menu.scenario.map.groundGids)
        for (row in 0 until core.map.heightTiles) for (col in 0 until core.map.widthTiles) {
            assertEquals(core.map.blocking.isBlocked(col, row), menu.scenario.map.blocking.isBlocked(col, row))
            assertEquals(core.map.waterBlocking.isBlocked(col, row), menu.scenario.map.waterBlocking.isBlocked(col, row))
        }
        val output = Files.createTempDirectory("owned-shared-scene-session-").resolve("run")
        PlatformSessionRunner.run(menu.root, seed, output)
        var scenarioId: String? = null
        var steps = 0
        Files.newBufferedReader(output.resolve("client-diagnostic.jsonl")).useLines { lines -> lines.forEach { line ->
            val row = json.parseToJsonElement(line) as kotlinx.serialization.json.JsonObject
            if (row["kind"] == s("observation")) {
                val sample = row.objectAt("sample")
                if (scenarioId == null) scenarioId = sample.text("scenarioId")
                if (row["phase"] == s("step")) steps++
            }
        } }
        assertEquals(core.scenarioId, scenarioId)
        assertEquals(3200, steps)
    }
}
