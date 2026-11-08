package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PhaseFiveArchiveTest {
    @Test fun paired_five_class_packages_run_through_the_public_core_runner_in_every_format() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val root = TestArchives.directory("RWR_PHASE_FIVE_ARCHIVE_ROOT", javaClass, "start")
        for (format in listOf("ini", "json", "hjson", "mixed")) {
            val scenario = public.resolve("scenarios/phase-five-$format-start-short.json")
            val contract = public.resolve("contracts/phase-five-$format-start-short.json")
            val archive = root.resolve(format)
            val result = Runner.run(RunRequest(scenario, contract, archive,
                listOf("api:Runner.run/PhaseFiveArchiveTest"), timeoutMillis = 60_000))
            val judgment = archive.resolve("comparison/result.json")
            assertEquals(0, result.exitCode, "$format: ${if (Files.exists(judgment)) Files.readString(judgment) else result}")
            assertEquals(0, TraceFiles.compare(scenario, contract, archive).exitCode)
        }
    }
}
