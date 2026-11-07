package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import java.nio.file.Files
import com.rustedwarfare.reforge.core.sim.ActionResult
import kotlinx.serialization.json.*
import kotlin.test.*

class AuthorWaterScenarioTest {
    @Test fun declared_three_format_author_content_runs_through_the_verified_core_reader_and_archives_both_weapons() {
        val root = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val input = ScenarioReader.load(root.resolve("scenarios/author-water-$format.json"),
                root.resolve("contracts/author-water-$format.json"))
            val actions = input.actions.groupBy { it.integer("atStep") }
            CoreAdapter(input).use { core ->
                core.initial()
                val fired = mutableSetOf<String>()
                for (step in input.steps) {
                    for (action in actions[step.stepIndex].orEmpty()) assertIs<ActionResult.Accepted>(core.submit(action))
                    core.advance(step)
                    val host = core.observe().json()
                    for (event in host.arrayAt("projectileEvents").map { it.jsonObject }) {
                        val shot = event.objectAt("projectile")
                        if (event.text("kind") == "born" && shot.text("sourceUnitId") == "a") fired += shot.text("sourceWeaponName")
                    }
                    assertEquals(listOf("port", "starboard"), host.objectAt("combat").arrayAt("weapons")
                        .map { it.jsonObject }.filter { it.text("unitId") == "a" }.map { it.text("weaponName") })
                }
                assertEquals(setOf("port", "starboard"), fired)
            }
        }
    }
    @Test fun structured_inherited_source_must_be_declared_in_the_verified_input_closure() {
        val root = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("json", "hjson")) {
            val scene = root.resolve("scenarios/author-water-$format.json")
            val doc = json.parseToJsonElement(Files.readString(scene)).jsonObject
            val content = doc.objectAt("content")
            val dependencies = content.arrayAt("dependencies")
            val shortened = dependencies.filterNot { it.jsonObject.text("path").endsWith("base.ini") }
            assertEquals(dependencies.size - 1, shortened.size)
            val snapshot = Files.createTempFile("rwr-author-unlisted-", ".json").toAbsolutePath().normalize()
            try {
                Files.writeString(snapshot, JsonObject(doc + ("content" to JsonObject(content +
                    ("dependencies" to JsonArray(shortened))))).toString())
                assertFailsWith<InputError> { ScenarioReader.load(scene,
                    root.resolve("contracts/author-water-$format.json"), snapshot) }
            } finally {
                require(snapshot.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
                Files.deleteIfExists(snapshot)
            }
        }
    }
    @Test fun complete_author_archives_match_across_formats_and_missing_water_pose_is_rejected() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val output = System.getenv("RWR_AUTHOR_WATER_ARCHIVE_ROOT")?.let(Path::of)
            ?: Files.createTempDirectory("rwr-author-water-archive-")
        Files.createDirectories(output)
        var baseline: List<JsonObject>? = null
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = public.resolve("scenarios/author-water-$format.json")
            val contract = public.resolve("contracts/author-water-$format.json")
            val target = output.resolve(format)
            val result = Runner.run(RunRequest(scenario, contract, target, listOf("api:Runner.run/AuthorWaterScenarioTest")))
            assertEquals(0, result.exitCode, "$format $result")
            val actual = TraceFiles.compare(scenario, contract, target)
            assertEquals(0, actual.exitCode, actual.document.toString())
            val raw = target.resolve("reforge/raw.jsonl")
            val rawHash = sha256(raw)
            val rows = Files.readAllLines(raw).map { json.parseToJsonElement(it).jsonObject }
            val normalized = rows.map(TraceComparator::normalize)
            if (baseline == null) baseline = normalized else assertEquals(baseline, normalized, "$format full gameplay archive")
            val index = rows.indexOfFirst { it["kind"] == s("observation") && it["phase"] == s("after-advance") }
            assertTrue(index >= 0)
            val original = rows[index]
            val observation = original.objectAt("observation")
            val units = observation.arrayAt("units").map { value ->
                val unit = value.jsonObject
                if (unit.text("unitId") == "a") JsonObject(unit - "waterPose") else unit
            }
            val mutant = rows.toMutableList()
            mutant[index] = JsonObject(original + ("observation" to JsonObject(observation + ("units" to JsonArray(units)))))
            val rejected = TraceComparator.evaluate(readObject(scenario), readObject(contract), mutant)
            Files.writeString(output.resolve("$format-water-pose-mutant.json"), rejected.document.toString())
            assertEquals(3, rejected.exitCode, rejected.document.toString())
            assertTrue(rejected.document.toString().contains("water-pose"))
            assertEquals(rawHash, sha256(raw), "Read-only mutant must not change source evidence")
        }
    }
    @Test fun normal_platform_sessions_keep_all_authored_weapon_births_and_close_in_all_formats() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val output = System.getenv("RWR_AUTHOR_WATER_ARCHIVE_ROOT")?.let(Path::of)
            ?: Files.createTempDirectory("rwr-author-water-session-")
        Files.createDirectories(output)
        for (format in listOf("ini", "json", "hjson")) {
            val target = output.resolve("session-$format")
            PlatformSessionRunner.run(public.resolve("inputs/author-water/$format"),
                public.resolve("platform/scenarios/author-water-$format.json"), target)
            var steps = 0
            var closed = false
            val fired = mutableSetOf<String>()
            Files.newBufferedReader(target.resolve("client-diagnostic.jsonl")).useLines { lines -> lines.forEach { line ->
                val row = json.parseToJsonElement(line).jsonObject
                if (row["kind"] == s("closed")) closed = true
                if (row["kind"] == s("observation") && row["phase"] == s("step")) {
                    steps++
                    fired += row.objectAt("sample").arrayAt("projectileEvents").map { it.jsonObject }
                        .filter { it["kind"] == s("born") && it["sourceUnitId"] == s("a") }.map { it.text("sourceWeaponName") }
                }
            } }
            assertEquals(420, steps)
            assertTrue(closed)
            assertEquals(setOf("port", "starboard"), fired, "$format normal Session")
        }
    }
}
