package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class SubmarinePublicScenarioTest {
    @Test fun three_format_fixed_core_and_paired_session_depth_chains_are_independently_readable_and_identical_within_each_seam() {
        val public=Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val hostFormats=mutableListOf<List<JsonElement?>>()
        val playerFormats=mutableListOf<List<JsonElement?>>()
        for (format in listOf("ini","json","hjson")) {
            val base=TestArchives.directory("RWR_SUBMARINE_ARCHIVE_ROOT",javaClass,"public-$format")
            val scenario=public.resolve("scenarios/submarine-$format-short.json")
            val contract=public.resolve("contracts/submarine-$format-short.json")
            val run=Runner.run(RunRequest(scenario,contract,base.resolve("core"),listOf("api:Runner.run/SubmarinePublicScenarioTest")))
            assertEquals(0,run.exitCode,Files.readString(base.resolve("core/comparison/result.json")))
            assertEquals(0,TraceFiles.compare(scenario,contract,base.resolve("core")).exitCode)
            val host=Files.readAllLines(base.resolve("core/reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
                .filter { it["kind"]==s("observation") }.map { it.required("observation") }
            assertEquals(861,host.size)
            hostFormats+=host
            PlatformSessionRunner.run(public.resolve("inputs/naval-production/$format"),scenario,base.resolve("session"))
            val session=Files.readAllLines(base.resolve("session/client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
                .filter { it["kind"]==s("observation") && it["phase"]==s("step") }.map { it.required("sample") }
            assertEquals(860,session.size)
            playerFormats+=session
            for ((samples,offset) in listOf(host to 1,session to 0)) {
                fun submarine(index:Int)=samples[index+offset].jsonObject.arrayAt("units").map { it.jsonObject }.single { it["unitId"]==s("sub") }
                assertEquals(1.0,submarine(259).number("height"))
                assertEquals(-8.0,submarine(519).number("height"))
                assertEquals(JsonNull,submarine(859)["moveTarget"])
                assertEquals(false,submarine(859).objectAt("submarine").required("surfaceIntent").jsonPrimitive.boolean)
            }
        }
        assertEquals(hostFormats[0],hostFormats[1]);assertEquals(hostFormats[0],hostFormats[2])
        assertEquals(playerFormats[0],playerFormats[1]);assertEquals(playerFormats[0],playerFormats[2])
    }
}
