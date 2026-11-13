package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class HoverArchiveTest {
    @Test fun fixed_normal_hover_production_and_shore_orders_run_through_the_public_archive_consumer() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val scenario = public.resolve("scenarios/hover-production-json-chain.json")
        val contract = public.resolve("contracts/hover-production-json-chain.json")
        val archive = TestArchives.directory("RWR_HOVER_ARCHIVE_ROOT", javaClass, "normal-chain").resolve("core")
        val result = Runner.run(RunRequest(scenario, contract, archive, listOf("api:Runner.run/HoverArchiveTest"), timeoutMillis = 60_000))
        val judgment = archive.resolve("comparison/result.json")
        assertEquals(0, result.exitCode, if (Files.exists(judgment)) Files.readString(judgment) else result.toString())
        assertEquals(0, TraceFiles.compare(scenario, contract, archive).exitCode)
    }
}
