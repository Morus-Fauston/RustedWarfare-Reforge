package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.math.hypot
import kotlin.test.*

class AuthorShoreScenarioTest {
    @Test fun normal_shore_construction_birth_and_navigation_preserve_complete_three_format_archives() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val output = System.getenv("RWR_AUTHOR_WATER_ARCHIVE_ROOT")?.let(Path::of)
            ?: Files.createTempDirectory("rwr-author-shore-archive-")
        Files.createDirectories(output)
        var baseline: List<JsonObject>? = null
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = public.resolve("scenarios/author-shore-$format.json")
            val contract = public.resolve("contracts/author-shore-$format.json")
            val target = output.resolve("shore-$format")
            val result = Runner.run(RunRequest(scenario, contract, target, listOf("api:Runner.run/AuthorShoreScenarioTest")))
            assertEquals(0, result.exitCode, "$format $result")
            assertEquals(0, TraceFiles.compare(scenario, contract, target).exitCode)
            val rows = Files.readAllLines(target.resolve("reforge/raw.jsonl"))
                .map { json.parseToJsonElement(it).jsonObject }
            val normalized = rows.map(TraceComparator::normalize)
            if (baseline == null) baseline = normalized else assertEquals(baseline, normalized, "$format complete shore archive")
            val steps = rows.filter { it["kind"] == s("observation") && it["phase"] == s("after-advance") }
            assertEquals(1600, steps.size)
            val last = TraceComparator.normalize(steps.last()).objectAt("observation")
            val ship = last.arrayAt("units").single {
                it.jsonObject.id("unitId") == "produced:p1:" + "b".repeat(32)
            }.jsonObject
            val position = ship.objectAt("position")
            assertTrue(hypot(position.number("x") - 250.0, position.number("y") - 430.0) <= 4.0)
            val index = rows.indexOf(steps[180])
            val original = rows[index]
            val observation = original.objectAt("observation")
            val economy = observation.objectAt("economy")
            val mutant = rows.toMutableList()
            mutant[index] = JsonObject(original + ("observation" to JsonObject(observation +
                ("economy" to JsonObject(economy + ("queues" to JsonObject(emptyMap())))))))
            val rejected = TraceComparator.evaluate(readObject(scenario), readObject(contract), mutant)
            Files.writeString(output.resolve("$format-missing-constructed-factory-queue-mutant.json"), rejected.document.toString())
            assertEquals(3, rejected.exitCode)
            assertTrue(rejected.document.toString().contains("Missing or extra registered factory queue"))
        }
    }
}
