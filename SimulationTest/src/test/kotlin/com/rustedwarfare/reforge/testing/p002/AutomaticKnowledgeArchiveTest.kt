package com.rustedwarfare.reforge.testing.p002

import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class AutomaticKnowledgeArchiveTest {
    @Test fun `automatic semantic corruption is rejected after raw normalized and inventory agree`() {
        val root = Path.of(System.getProperty("repo.root"))
        val original = P002KnowledgeSuite.run(root, "TestLab/suites/P002-automatic-vision.v1.json",
            Files.createTempDirectory("automatic-archive-source-"), listOf("bad-control-source"))
        assertEquals(0, original.exitCode)
        fun refresh(directory: Path) {
            val manifest = readObject(directory.resolve("manifest.json"))
            val refs = Files.walk(directory).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString() != "manifest.json" }.sorted().map {
                obj("path" to s(directory.relativize(it).toString().replace('\\', '/')), "sha256" to s(sha256(it)))
            }.toList() }
            Files.writeString(directory.resolve("manifest.json"), JsonObject(manifest + ("files" to array(refs))).toString() + "\n")
        }
        fun bad(name: String, id: String, code: Int, transform: (List<JsonObject>) -> List<JsonObject>) {
            val directory = Files.createTempDirectory("automatic-$name-")
            Files.walk(original.output).use { paths -> paths.sorted().forEach { file ->
                val dest = directory.resolve(original.output.relativize(file))
                if (Files.isDirectory(file)) Files.createDirectories(dest) else Files.copy(file, dest)
            } }
            for (format in listOf("raw", "normalized")) {
                val path = directory.resolve("$id/$format.jsonl")
                val rows = Files.readAllLines(path).map { json.parseToJsonElement(it).jsonObject }
                Files.writeString(path, transform(rows).joinToString("\n", postfix = "\n"))
            }
            refresh(directory)
            val verdict = P002KnowledgeSuite.verify(directory)
            assertEquals(code, verdict.exitCode, "$name: ${verdict.reason}")
            println("automatic-bad-control=$name exit=${verdict.exitCode} path=$directory")
        }
        fun replace(value: JsonElement, path: List<String>, replacement: JsonElement): JsonElement {
            if (path.isEmpty()) return replacement
            val key = path.first()
            return when (value) {
                is JsonObject -> JsonObject(value + (key to replace(value.getValue(key), path.drop(1), replacement)))
                is JsonArray -> JsonArray(value.mapIndexed { index, entry -> if (index == key.toInt()) replace(entry, path.drop(1), replacement) else entry })
                else -> error("Invalid test mutation path")
            }
        }
        fun List<JsonObject>.at(label: String, transform: (JsonObject) -> JsonObject) = map { if (it["label"] == s(label)) transform(it) else it }
        bad("missing-vision-cell", "A01-memory", 2) { rows -> rows.at("initial") { row ->
            replace(row, listOf("players", "p1", "vision", "effectiveCells"), array(listOf(10,11,12,16).map(::n))).jsonObject
        } }
        bad("hardcore-retains-wall", "A02-hardcore", 2) { rows -> rows.at("advance:1") { row ->
            val cell = obj("visible" to b(false), "lastKnownBlocked" to b(true), "lastKnownGroundGid" to n(0))
            replace(replace(row, listOf("players", "p1", "knowledge", "cells", "6"), cell), listOf("knowledge", "p1", "cells", "6"), cell).jsonObject
        } }
        bad("sharing-off-leak", "A03-sharing-off", 2) { rows -> rows.at("initial") { row ->
            replace(row, listOf("players", "p2", "observation", "units"), row.objectAt("observation").arrayAt("units")).jsonObject
        } }
        bad("missing-step", "A01-memory", 3) { rows -> rows.filter { it["label"] != s("advance:1") } }
        bad("closed-still-visible", "A06-lifecycle", 3) { rows -> rows.at("close") { row ->
            JsonObject(row + ("players" to rows.first().getValue("players")))
        } }
        bad("stale-world-accepted", "A06-lifecycle", 2) { rows -> rows.at("stale") { row -> JsonObject(row + ("operationResult" to s("accepted"))) } }
        // Change every nested identity and both formats, preserving hash correspondence.
        // The verifier must still reject reusing generation one's world after reopening.
        bad("old-world-in-new-generation", "A06-lifecycle", 3) { rows ->
            val old = rows.first().objectAt("players").objectAt("p1").objectAt("observation").getValue("worldId")
            fun identities(value: JsonElement): JsonElement = when (value) {
                is JsonObject -> JsonObject(value.mapValues { (key, child) -> if (key == "worldId") old else identities(child) })
                is JsonArray -> JsonArray(value.map(::identities))
                else -> value
            }
            rows.map { if (it["generation"] == n(1)) identities(it).jsonObject else it }
        }
        val manifest = readObject(original.output.resolve("manifest.json"))
        Files.writeString(original.output.resolve("manifest.json"), JsonObject(manifest + ("scope" to s("trusted-host-projection"))).toString())
        assertEquals(64, P002KnowledgeSuite.verify(original.output).exitCode)
        println("automatic-bad-control=mixed-scope exit=64 path=${original.output}")
    }
}
