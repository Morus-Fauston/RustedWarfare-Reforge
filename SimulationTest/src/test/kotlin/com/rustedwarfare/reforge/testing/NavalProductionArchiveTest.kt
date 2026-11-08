package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class NavalProductionArchiveTest {
    @Test fun normal_three_format_production_archives_preserve_and_compare_every_intermediate_gameplay_field() {
        val repo = Path.of(System.getProperty("repo.root"))
        val public = repo.resolve("SimulationTest/public")
        val root = TestArchives.directory("RWR_NAVAL_ARCHIVE_ROOT", javaClass, "production-core").resolve("archives")
        Files.createDirectory(root)
        var expected: Path? = null
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = public.resolve("scenarios/naval-production-$format-chain.json")
            val contract = public.resolve("contracts/naval-production-$format-chain.json")
            val document = readObject(scenario)
            val seal = Files.createDirectory(root.resolve("sealed-$format"))
            val content = document.objectAt("content")
            val files = listOf(scenario, contract) + (listOf(content.objectAt("map")) +
                content.arrayAt("unitVariants").map { it.jsonObject } + content.arrayAt("dependencies").map { it.jsonObject })
                .map { scenario.parent.resolve(it.text("path")).normalize() }
            val manifest = files.distinct().mapIndexed { i, source ->
                val destination = seal.resolve("$i-${source.fileName}")
                Files.copy(source, destination)
                obj("source" to s(source.toString()), "snapshot" to s(destination.fileName.toString()), "sha256" to s(sha256(source)))
            }
            Files.writeString(seal.resolve("manifest.json"), array(manifest).toString())
            val output = root.resolve(format)
            val result = Runner.run(RunRequest(scenario, contract, output, listOf("api:Runner.run/NavalProductionArchiveTest"), timeoutMillis = 600_000))
            assertEquals(0, result.exitCode, "$format: $result; $output")
            val compared = TraceFiles.compare(scenario, contract, output)
            Files.writeString(root.resolve("$format-readonly-consumption.json"), compared.document.toString())
            assertEquals(0, compared.exitCode, compared.document.toString())
            val raw = output.resolve("reforge/raw.jsonl")
            val baseline = expected ?: raw
            var count = 0
            Files.newBufferedReader(raw).use { actual -> Files.newBufferedReader(baseline).use { wanted ->
                while (true) {
                    val left = wanted.readLine()
                    val right = actual.readLine()
                    if (left == null || right == null) { assertEquals(left, right, "Archive length"); break }
                    assertEquals(TraceComparator.normalize(json.parseToJsonElement(left).jsonObject),
                        TraceComparator.normalize(json.parseToJsonElement(right).jsonObject), "$format record $count")
                    count++
                }
            } }
            Files.writeString(root.resolve("$format-full-exact-comparison.json"), obj("passed" to b(true),
                "recordCount" to n(count), "actualSha256" to s(sha256(raw)), "expectedSha256" to s(sha256(baseline)),
                "policy" to s("Whole JSON values after unchanged TraceComparator.normalize; raw start/end separately validated by TraceFiles; no gameplay fields dropped or tolerances added")).toString())
            if (expected == null) expected = raw
            val ship = "produced:p1:${"b".repeat(32)}"
            val fired = mutableListOf<JsonObject>()
            var accepted = 0
            Files.newBufferedReader(raw).useLines { lines -> lines.forEach { line ->
                val row = json.parseToJsonElement(line).jsonObject
                if (row["kind"] == s("action-result")) {
                    assertEquals(s("accepted"), row.objectAt("result")["status"]); accepted++
                }
                if (row["kind"] == s("observation")) fired += row.objectAt("observation").arrayAt("projectileEvents")
                    .map { it.jsonObject }.filter { it["kind"] == s("born") }.map { it.objectAt("projectile") }
                    .filter { it["sourceUnitId"] == s(ship) }
            } }
            assertEquals(setOf("1", "2", "3", "4", "5"), fired.map { it.text("sourceWeaponName") }.toSet(), "$format all five stock weapons")
            assertEquals(setOf("c", "d"), fired.filter { it["targetUnitId"] != JsonNull }.map { it.text("targetUnitId") }.toSet())
            for (shot in fired.filter { it["sourceWeaponName"] in setOf(s("1"), s("2")) }) {
                assertEquals(JsonNull, shot["targetUnitId"])
                assertEquals(obj("x" to n(330.0), "y" to n(550.0)), shot.objectAt("ground").required("targetPosition"))
            }
            assertEquals(document.arrayAt("actions").size, accepted)
        }
    }
}
