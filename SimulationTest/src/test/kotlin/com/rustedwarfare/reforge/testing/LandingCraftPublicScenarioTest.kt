package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import kotlin.test.*

class LandingCraftPublicScenarioTest {
    @Test fun three_owned_transport_scenarios_pass_fixed_contracts_and_independent_archive_readers() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = public.resolve("scenarios/transport-$format-short.json")
            val contract = public.resolve("contracts/transport-$format-short.json")
            val output = TestArchives.directory("RWR_TRANSPORT_ARCHIVE_ROOT", javaClass, format).resolve("run")
            val result = Runner.run(RunRequest(scenario, contract, output, listOf("api:Runner.run/LandingCraftPublicScenarioTest")))
            assertEquals(0, result.exitCode, "$format $output: ${result.status}")
            val comparison = TraceFiles.compare(scenario, contract, output)
            assertEquals(0, comparison.exitCode, comparison.document.toString())
        }
    }
}
