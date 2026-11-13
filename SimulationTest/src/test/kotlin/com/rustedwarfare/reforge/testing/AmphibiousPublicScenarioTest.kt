package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import kotlin.test.*

class AmphibiousPublicScenarioTest {
    @Test fun three_owned_core_scenarios_pass_their_fixed_contracts_and_independent_readers() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = public.resolve("scenarios/amphibious-$format-short.json")
            val contract = public.resolve("contracts/amphibious-$format-short.json")
            val output = TestArchives.directory("RWR_AMPHIBIOUS_ARCHIVE_ROOT", javaClass, format).resolve("run")
            val result = Runner.run(RunRequest(scenario, contract, output, listOf("api:Runner.run/AmphibiousPublicScenarioTest")))
            assertEquals(0, result.exitCode, "$format $output")
            val comparison = TraceFiles.compare(scenario, contract, output)
            assertEquals(0, comparison.exitCode, comparison.document.toString())
        }
    }
}
