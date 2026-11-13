package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import kotlin.test.*

class LandingCraftArchiveTest {
    @Test fun rehashed_transport_archives_reject_incomplete_or_inconsistent_relations() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val scenario = public.resolve("scenarios/transport-json-short.json")
        val contract = public.resolve("contracts/transport-json-short.json")
        val root = TestArchives.directory("RWR_TRANSPORT_ARCHIVE_ROOT", javaClass, "relations")
        val original = root.resolve("original")
        assertEquals(0, Runner.run(RunRequest(scenario, contract, original, listOf("api:Runner.run/LandingCraftArchiveTest"))).exitCode)
        assertEquals(0, TraceFiles.compare(scenario, contract, original).exitCode)
        PublicArchiveAssertions.transport(scenario, contract, original, root)
    }
}
