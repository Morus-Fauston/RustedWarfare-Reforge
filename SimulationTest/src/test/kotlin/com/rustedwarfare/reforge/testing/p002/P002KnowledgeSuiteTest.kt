package com.rustedwarfare.reforge.testing.p002

import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class P002KnowledgeSuiteTest {
    @Test
    fun `automatic scope runs six real core scenarios including NoFog and reopen`() {
        val root = Path.of(System.getProperty("repo.root"))
        val result = P002KnowledgeSuite.run(root, "TestLab/suites/P002-automatic-vision.development.center.v2.json",
            Files.createTempDirectory("p002-automatic-"), listOf("automatic-test"))
        println("automatic-p002-output=${result.output}")
        assertEquals(0, result.exitCode, Files.readString(result.output.resolve("verdict.json")))
        assertEquals(6, result.caseCount)
        assertEquals(0, P002KnowledgeSuite.verify(result.output).exitCode)
    }

    @Test
    fun `development trusted host suite runs real core and verifies all eleven cases`() {
        val root = Path.of(System.getProperty("repo.root"))
        val output = Files.createTempDirectory("p002-knowledge-suite-")
        val result = P002KnowledgeSuite.run(root, "TestLab/suites/P002-knowledge-host.development.center.v2.json", output, listOf("test"))
        assertEquals(0, result.exitCode)
        assertEquals(11, result.caseCount)
        assertTrue(Files.isRegularFile(result.output.resolve("K09-collision/raw.jsonl")))
        assertEquals(0, P002KnowledgeSuite.verify(result.output).exitCode)
    }

    @Test
    fun `same verifier rejects semantic mutations even after inventory is refreshed`() {
        val root = Path.of(System.getProperty("repo.root"))
        val source = P002KnowledgeSuite.run(root, "TestLab/suites/P002-knowledge-host.development.center.v2.json", Files.createTempDirectory("p002-negative-source-"), listOf("negative-test")).output
        fun copy(): Path {
            val destination = Files.createTempDirectory("p002-negative-")
            Files.walk(source).use { paths -> paths.sorted().forEach { file ->
                val target = destination.resolve(source.relativize(file))
                if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target)
            } }
            return destination
        }
        fun inventory(directory: Path) {
            val manifest = readObject(directory.resolve("manifest.json"))
            val refs = Files.walk(directory).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString() != "manifest.json" }.sorted().map {
                obj("path" to s(directory.relativize(it).toString().replace('\\', '/')), "sha256" to s(sha256(it)))
            }.toList() }
            Files.writeString(directory.resolve("manifest.json"), JsonObject(manifest + ("files" to array(refs))).toString() + "\n")
        }
        fun mutate(directory: Path, id: String, transform: (List<JsonObject>) -> List<JsonObject>) {
            for (name in listOf("raw.jsonl", "normalized.jsonl")) {
                val path = directory.resolve("$id/$name")
                val rows = Files.readAllLines(path).map { json.parseToJsonElement(it).jsonObject }
                Files.writeString(path, transform(rows).joinToString("\n", postfix = "\n"))
            }
            inventory(directory)
        }
        val displaced = copy()
        mutate(displaced, "K01-flat") { rows -> rows.map { row ->
            if (row["label"] != s("initial")) row else {
                val observation = row.objectAt("observation")
                val unit = observation.arrayAt("units").single().jsonObject
                val changed = JsonObject(unit + ("position" to obj("x" to n(31.0), "y" to n(50.0))))
                val observed = JsonObject(observation + ("units" to array(listOf(changed))))
                JsonObject(row + ("observation" to observed))
            }
        } }
        assertEquals(2, P002KnowledgeSuite.verify(displaced).exitCode)

        val knowledge = copy()
        mutate(knowledge, "K02-memory") { rows -> rows.map { row ->
            if (row["label"] != s("leave")) row else {
                val players = row.objectAt("knowledge")
                val player = players.objectAt("p1")
                val cells = player.arrayAt("cells").toMutableList()
                cells[12] = obj("visible" to b(false), "lastKnownBlocked" to b(false))
                JsonObject(row + ("knowledge" to JsonObject(players + ("p1" to JsonObject(player + ("cells" to array(cells)))))))
            }
        } }
        assertEquals(2, P002KnowledgeSuite.verify(knowledge).exitCode)

        val missingStep = copy()
        mutate(missingStep, "K09-collision") { rows -> rows.filter { it["label"] != s("blocked:0") } }
        assertEquals(3, P002KnowledgeSuite.verify(missingStep).exitCode)

        val replacedSample = copy()
        mutate(replacedSample, "K01-flat") { rows -> rows.map { row ->
            if (row["label"] == s("initial")) obj("kind" to s("garbage"), "label" to s("initial")) else row
        } }
        assertEquals(3, P002KnowledgeSuite.verify(replacedSample).exitCode)

        val wrongSchema = copy()
        mutate(wrongSchema, "K01-flat") { rows -> rows.map { row ->
            if (row["label"] == s("initial")) JsonObject(row + ("schemaVersion" to n(99))) else row
        } }
        assertEquals(3, P002KnowledgeSuite.verify(wrongSchema).exitCode)

        val wrongCase = copy()
        mutate(wrongCase, "K01-flat") { rows -> rows.map { row ->
            if (row["label"] == s("initial")) JsonObject(row + ("caseId" to s("K01-hidden"))) else row
        } }
        assertEquals(3, P002KnowledgeSuite.verify(wrongCase).exitCode)

        val missingField = copy()
        mutate(missingField, "K07-replan") { rows -> rows.map { row ->
            if (row["label"] != s("initial")) row else {
                val observation = row.objectAt("observation")
                val unit = observation.arrayAt("units").single().jsonObject
                val observed = JsonObject(observation + ("units" to array(listOf(JsonObject(unit - "speed")))))
                JsonObject(row + ("observation" to observed))
            }
        } }
        assertEquals(3, P002KnowledgeSuite.verify(missingField).exitCode)

        val identity = copy()
        Files.writeString(identity.resolve("inputs/scenarios.json"), Files.readString(identity.resolve("inputs/scenarios.json")) + "\n")
        inventory(identity)
        assertEquals(64, P002KnowledgeSuite.verify(identity).exitCode)

        val truncated = copy()
        val raw = truncated.resolve("K07-replan/raw.jsonl")
        Files.writeString(raw, Files.readString(raw).trimEnd())
        inventory(truncated)
        assertEquals(3, P002KnowledgeSuite.verify(truncated).exitCode)

        val failed = copy()
        mutate(failed, "K07-replan") { rows -> rows.map { row -> if (row["label"] == s("completion")) JsonObject(row + ("status" to s("failed"))) else row } }
        assertEquals(4, P002KnowledgeSuite.verify(failed).exitCode)
        // A later execution failure outranks the earlier semantic displacement.
        mutate(displaced, "K07-replan") { rows -> rows.map { row -> if (row["label"] == s("completion")) JsonObject(row + ("status" to s("failed"))) else row } }
        assertEquals(4, P002KnowledgeSuite.verify(displaced).exitCode)
    }
}
