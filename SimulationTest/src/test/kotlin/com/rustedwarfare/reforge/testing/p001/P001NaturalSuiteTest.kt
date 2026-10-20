package com.rustedwarfare.reforge.testing.p001

import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class P001NaturalSuiteTest {
    @Test fun `natural suite verifies and rejects mismatched archived contract even with refreshed inventory`() {
        val root = Path.of(System.getProperty("repo.root"))
        val directory = Files.createTempDirectory("p001-natural-")
        // P001 requires a root-relative output; a relative path within this temporary copy is used.
        val suiteName = "TestLab/suites/P001-self-behavior.natural.v2.json"
        val suite = readObject(root.resolve(suiteName))
        val paths = mutableSetOf(suiteName)
        fun references(value: JsonElement) {
            when (value) {
                is JsonObject -> {
                    if (value["path"] is JsonPrimitive && value["sha256"] is JsonPrimitive) paths += value.text("path")
                    value.values.forEach(::references)
                }
                is JsonArray -> value.forEach(::references)
                else -> Unit
            }
        }
        references(suite)
        for (name in paths) {
            val dest = directory.resolve(name)
            Files.createDirectories(dest.parent); Files.copy(root.resolve(name), dest)
        }
        val result = P001Suite.run(directory, suiteName, "results", listOf("natural-suite-test"))
        println("p001-natural-output=${result.output}")
        assertEquals(0, result.exitCode)
        assertEquals(0, P001Suite.verifyArchive(result.output))
        val relationPath = result.output.resolve("inputs/M02.relations.json")
        val relation = readObject(relationPath)
        for (marker in listOf(null, s("unknown"))) {
            val changed = if (marker == null) JsonObject(relation - "pathContract") else JsonObject(relation + ("pathContract" to marker))
            Files.writeString(relationPath, changed.toString())
            val manifestPath = result.output.resolve("manifest.json")
            val manifest = readObject(manifestPath)
            val files = manifest.arrayAt("files").map { ref ->
                val item = ref.jsonObject
                if (item.text("path") == "inputs/M02.relations.json") JsonObject(item + ("sha256" to s(sha256(relationPath)))) else item
            }
            Files.writeString(manifestPath, JsonObject(manifest + ("files" to array(files))).toString())
            assertEquals(64, P001Suite.verifyArchive(result.output))
        }
        // Run-time mismatch: use the legacy relation with a natural suite, retaining valid hashes.
        val m02 = suite.arrayAt("cases").map { it.jsonObject }.single { it.text("caseId") == "M02" }
        val liveRelation = directory.resolve(m02.objectAt("relations").text("path"))
        Files.writeString(liveRelation, JsonObject(readObject(liveRelation) - "pathContract").toString())
        val cases = suite.arrayAt("cases").map { entry ->
            if (entry.jsonObject.text("caseId") != "M02") entry else JsonObject(m02 + ("relations" to
                JsonObject(m02.objectAt("relations") + ("sha256" to s(sha256(liveRelation))))))
        }
        Files.writeString(directory.resolve(suiteName), JsonObject(suite + ("cases" to array(cases))).toString())
        assertEquals(64, P001Suite.run(directory, suiteName, "mixed", listOf("mixed-contract")).exitCode)
        Files.writeString(directory.resolve(suiteName), JsonObject(suite + ("pathContract" to s("unknown"))).toString())
        assertEquals(64, P001Suite.run(directory, suiteName, "unknown", listOf("unknown-contract")).exitCode)
    }
}
