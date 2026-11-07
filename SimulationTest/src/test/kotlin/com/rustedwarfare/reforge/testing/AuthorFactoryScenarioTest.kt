package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class AuthorFactoryScenarioTest {
    @Test fun capability_labels_cannot_hide_or_invent_a_factory_queue() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for ((name, type, wrongLoader) in listOf(
            Triple("author-production-json", "spfactory", "author-factory"),
            Triple("author-construction-json", "workshop", "author-producer"))) {
            val scenario = public.resolve("scenarios/$name.json")
            val contract = public.resolve("contracts/$name.json")
            ScenarioReader.load(scenario, contract)
            val doc = readObject(scenario)
            val content = doc.objectAt("content")
            val declarations = content.arrayAt("unitVariants").map { value ->
                val entry = value.jsonObject
                if (entry.id("id") == type) JsonObject(entry + ("loader" to s(wrongLoader))) else entry
            }
            val snapshot = Files.createTempFile("rwr-factory-capability-", ".json").toAbsolutePath().normalize()
            try {
                Files.writeString(snapshot, JsonObject(doc + ("content" to JsonObject(content +
                    ("unitVariants" to JsonArray(declarations))))).toString())
                val error = assertFailsWith<InputError> { ScenarioReader.load(scenario, contract, snapshot) }
                assertTrue(error.message.orEmpty().contains("actual production capability"))
            } finally {
                require(snapshot.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
                Files.deleteIfExists(snapshot)
            }
        }
    }

    @Test fun three_format_preplaced_author_factories_preserve_the_full_paid_two_product_archive() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val output = TestArchives.directory("RWR_AUTHOR_WATER_ARCHIVE_ROOT", javaClass, "rwr-author-factory-archive-")
        Files.createDirectories(output)
        var baseline: List<JsonObject>? = null
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = public.resolve("scenarios/author-production-$format.json")
            val contract = public.resolve("contracts/author-production-$format.json")
            val target = output.resolve("factory-$format")
            val result = Runner.run(RunRequest(scenario, contract, target, listOf("api:Runner.run/AuthorFactoryScenarioTest")))
            assertEquals(0, result.exitCode, "$format $result")
            assertEquals(0, TraceFiles.compare(scenario, contract, target).exitCode)
            val rows = Files.readAllLines(target.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
            val normalized = rows.map(TraceComparator::normalize)
            if (baseline == null) baseline = normalized else assertEquals(baseline, normalized, "$format all paid-production records")
            val index = rows.indexOfFirst { it["kind"] == s("observation") &&
                it["phase"] == s("after-advance") && it.objectAt("observation").integer("stepIndex") == 500L }
            assertTrue(index >= 0)
            val original = rows[index]
            val observation = original.objectAt("observation")
            val production = observation.objectAt("production")
            val births = production.arrayAt("units")
            assertEquals(1, births.size)
            val wrong = JsonObject(births.single().jsonObject + ("producerId" to s("wrong-factory")))
            val mutant = rows.toMutableList()
            val changedProduction = JsonObject(production + ("units" to JsonArray(listOf(wrong))))
            val changedObservation = JsonObject(observation + ("production" to changedProduction))
            mutant[index] = JsonObject(original + ("observation" to changedObservation))
            val rejected = TraceComparator.evaluate(readObject(scenario), readObject(contract), mutant)
            Files.writeString(output.resolve("$format-wrong-factory-mutant.json"), rejected.document.toString())
            assertEquals(3, rejected.exitCode)
            assertTrue(rejected.document.toString().contains("production-identity"))
        }
    }

    @Test fun inherited_factory_fields_require_the_declared_parent_identity() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = public.resolve("scenarios/author-production-$format.json")
            val doc = readObject(scenario)
            val content = doc.objectAt("content")
            val dependencies = content.arrayAt("dependencies")
            val shortened = dependencies.filterNot { it.jsonObject.text("path").endsWith("factory-base.ini") }
            assertEquals(dependencies.size - 1, shortened.size)
            val snapshot = Files.createTempFile("rwr-factory-unlisted-", ".json").toAbsolutePath().normalize()
            try {
                Files.writeString(snapshot, JsonObject(doc + ("content" to JsonObject(content +
                    ("dependencies" to JsonArray(shortened))))).toString())
                assertFailsWith<InputError> { ScenarioReader.load(scenario,
                    public.resolve("contracts/author-production-$format.json"), snapshot) }
            } finally {
                require(snapshot.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
                Files.deleteIfExists(snapshot)
            }
        }
    }
}
