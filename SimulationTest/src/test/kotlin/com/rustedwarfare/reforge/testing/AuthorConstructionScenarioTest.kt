package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class AuthorConstructionScenarioTest {
    @Test fun three_format_fixed_nonce_construction_archives_preserve_all_gameplay_records() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val output = System.getenv("RWR_AUTHOR_WATER_ARCHIVE_ROOT")?.let(Path::of)
            ?: Files.createTempDirectory("rwr-author-construction-archive-")
        Files.createDirectories(output)
        var baseline: List<JsonObject>? = null
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = public.resolve("scenarios/author-construction-$format.json")
            val contract = public.resolve("contracts/author-construction-$format.json")
            val target = output.resolve("construction-$format")
            val result = Runner.run(RunRequest(scenario, contract, target, listOf("api:Runner.run/AuthorConstructionScenarioTest")))
            assertEquals(0, result.exitCode, "$format $result")
            assertEquals(0, TraceFiles.compare(scenario, contract, target).exitCode)
            val rows = Files.readAllLines(target.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
            val normalized = rows.map(TraceComparator::normalize)
            if (baseline == null) baseline = normalized else assertEquals(baseline, normalized, "$format complete construction archive")
            val index = rows.indexOfFirst { it["kind"] == s("observation") && it["phase"] == s("after-advance") }
            assertTrue(index >= 0)
            val original = rows[index]
            val observation = original.objectAt("observation")
            val construction = observation.objectAt("construction")
            assertEquals(1, construction.arrayAt("workers").size)
            val mutant = rows.toMutableList()
            mutant[index] = JsonObject(original + ("observation" to JsonObject(observation +
                ("construction" to JsonObject(construction + ("workers" to JsonArray(emptyList())))))))
            val rejected = TraceComparator.evaluate(readObject(scenario), readObject(contract), mutant)
            Files.writeString(output.resolve("$format-missing-worker-mutant.json"), rejected.document.toString())
            assertEquals(3, rejected.exitCode)
            assertTrue(rejected.document.toString().contains("construction-workers"))
        }
    }

    @Test fun every_inherited_constructor_source_requires_a_declared_identity() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = public.resolve("scenarios/author-construction-$format.json")
            val doc = readObject(scenario)
            val content = doc.objectAt("content")
            val dependencies = content.arrayAt("dependencies")
            val shortened = dependencies.filterNot { it.jsonObject.text("path").endsWith("base-constructor.ini") }
            assertEquals(dependencies.size - 1, shortened.size)
            val snapshot = Files.createTempFile("rwr-constructor-unlisted-", ".json").toAbsolutePath().normalize()
            try {
                Files.writeString(snapshot, JsonObject(doc + ("content" to JsonObject(content +
                    ("dependencies" to JsonArray(shortened))))).toString())
                assertFailsWith<InputError> { ScenarioReader.load(scenario,
                    public.resolve("contracts/author-construction-$format.json"), snapshot) }
            } finally {
                require(snapshot.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
                Files.deleteIfExists(snapshot)
            }
        }
    }
}
