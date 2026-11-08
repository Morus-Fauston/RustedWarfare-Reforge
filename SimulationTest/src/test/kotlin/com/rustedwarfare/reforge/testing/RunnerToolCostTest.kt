package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RunnerToolCostTest {
    @Test fun fixed_short_archive_separates_loading_validation_sampling_encoding_and_writing_without_changing_judgment() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val root = TestArchives.directory("RWRE_P005_COST_ARCHIVE_ROOT", javaClass, "rwr-tool-cost-")
        Files.createDirectories(root)
        val output = root.resolve("short-cost")
        val scenario = public.resolve("scenarios/select-move-stop.json")
        val contract = public.resolve("contracts/select-move-stop.json")
        assertEquals(0, Runner.run(RunRequest(scenario, contract, output, listOf("RunnerToolCostTest"))).exitCode)
        assertEquals(0, TraceFiles.compare(scenario, contract, output).exitCode)
        val costs = readObject(output.resolve("manifest.json")).objectAt("costs")
        val details = costs.objectAt("detailsNanos")
        for (stage in listOf("sourceLoad", "inputValidation", "initialization", "coreAdvanceIncludingHostSample",
            "samplingIncludingPlayerJson", "serialization", "writing", "comparison"))
            assertTrue(details.integer(stage) >= 0, stage)
        assertTrue(details.keys.sumOf { details.integer(it) } <= costs.integer("measuredNanos"))
        val legacy = costs.objectAt("stagesNanos")
        assertEquals(costs.integer("measuredNanos"), legacy.keys.sumOf { legacy.integer(it) } + costs.integer("otherNanos"))
        assertTrue(costs.text("detailBoundary").contains("trace"))
    }
}
