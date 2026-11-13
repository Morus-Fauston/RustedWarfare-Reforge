package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import kotlin.test.*

class AaBeamArchiveTest {
    @Test fun the_public_aa_scenario_archives_energy_beams_and_fall_state_in_all_formats() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = public.resolve("scenarios/aa-beam-$format-short.json")
            val contract = public.resolve("contracts/aa-beam-$format-short.json")
            val root = TestArchives.directory("RWR_AA_BEAM_ARCHIVE_ROOT", javaClass, format)
            val result = Runner.run(RunRequest(scenario, contract, root.resolve("original"), listOf("api:Runner.run/AaBeamArchiveTest")))
            assertEquals(0, result.exitCode, result.toString())
            assertEquals(0, TraceFiles.compare(scenario, contract, root.resolve("original")).exitCode)
            if (format == "json") PublicArchiveAssertions.aa(scenario, contract, root.resolve("original"), root)
        }
    }

}
