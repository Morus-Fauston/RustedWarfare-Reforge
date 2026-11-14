package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import kotlin.test.*

class DropshipArchiveTest {
    @Test fun rehashed_transport_archives_reject_incomplete_or_inconsistent_relations() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val scenario = public.resolve("scenarios/air-transport-json-short.json")
        val contract = public.resolve("contracts/air-transport-json-short.json")
        val root = TestArchives.directory("RWR_AIR_TRANSPORT_ARCHIVE_ROOT", javaClass, "relations")
        val original = root.resolve("original")
        assertEquals(0, Runner.run(RunRequest(scenario, contract, original, listOf("api:Runner.run/DropshipArchiveTest"))).exitCode)
        assertEquals(0, TraceFiles.compare(scenario, contract, original).exitCode)
        PublicArchiveAssertions.airTransport(scenario, contract, original, root)
    }
}
