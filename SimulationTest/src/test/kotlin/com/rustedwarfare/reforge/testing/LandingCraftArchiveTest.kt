package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class LandingCraftArchiveTest {
    @Test fun rehashed_transport_archives_reject_incomplete_or_inconsistent_relations() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val scenario = public.resolve("scenarios/transport-json-short.json")
        val contract = public.resolve("contracts/transport-json-short.json")
        val root = TestArchives.directory("RWR_TRANSPORT_ARCHIVE_ROOT", javaClass, "relations")
        val original = root.resolve("original")
        assertEquals(0, Runner.run(RunRequest(scenario, contract, original, listOf("api:Runner.run/LandingCraftArchiveTest"))).exitCode)
        assertEquals(0, TraceFiles.compare(scenario, contract, original).exitCode)
        val document = readObject(scenario)
        val originalRows = Files.readAllLines(original.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        // An unasserted intermediate boundary must still satisfy the archive protocol.
        val index = originalRows.indexOfFirst { it["kind"] == s("observation") && it.objectAt("observation")["stepIndex"] == n(2) }
        assertTrue(index > 0)
        fun corrupt(name: String, id: String, change: (JsonObject) -> JsonObject) {
            val target = root.resolve(name)
            Files.walk(original).use { paths -> paths.forEach { from ->
                val to = target.resolve(original.relativize(from)).normalize()
                require(to.startsWith(root.toAbsolutePath().normalize()))
                if (Files.isDirectory(from)) Files.createDirectories(to) else Files.copy(from, to)
            } }
            val rows = originalRows.toMutableList()
            val view = rows[index].objectAt("observation")
            val units = view.arrayAt("units").map { if (it.jsonObject["unitId"] == s(id)) change(it.jsonObject) else it }
            rows[index] = JsonObject(rows[index] + ("observation" to JsonObject(view + ("units" to array(units)))))
            Files.writeString(target.resolve("reforge/raw.jsonl"), rows.joinToString("\n", postfix = "\n"))
            Files.writeString(target.resolve("reforge/normalized.jsonl"), rows.mapIndexed { i, row -> normalizeRecord(row, i + 1,
                if (i == 0) null else document.objectAt("coordinateModel").id("id"),
                if (i == 0) null else document.objectAt("timeModel").id("id")) }.joinToString("\n", postfix = "\n"))
            val manifest = readObject(target.resolve("manifest.json"))
            Files.writeString(target.resolve("manifest.json"), JsonObject(manifest + ("traceFiles" to array(manifest.arrayAt("traceFiles").map {
                val ref = it.jsonObject
                JsonObject(ref + ("sha256" to s(sha256(target.resolve(ref.text("path"))))))
            }))).toString())
            val result = TraceFiles.compare(scenario, contract, target)
            assertEquals(3, result.exitCode, "$name: ${result.document}")
            assertTrue(result.document.toString().contains("transport-"), "$name: ${result.document}")
        }
        fun state(name: String, change: (JsonObject) -> JsonObject) = corrupt(name, "craft") {
            JsonObject(it + ("transport" to change(it.objectAt("transport"))))
        }
        corrupt("missing-state", "craft") { JsonObject(it - "transport") }
        state("unknown-field") { JsonObject(it + ("hostCounter" to n(0))) }
        state("missing-manifest") { JsonObject(it - "passengerIds") }
        state("duplicate-passenger") { JsonObject(it + ("passengerIds" to array(listOf(s("a-passenger"), s("a-passenger"))))) }
        state("false-count") { JsonObject(it + ("passengerCount" to n(0))) }
        state("over-capacity") { JsonObject(it + ("occupiedSlots" to n(5))) }
        state("wrong-capacity") { JsonObject(it + ("capacity" to n(5))) }
        state("bad-clock") { JsonObject(it + ("unloadRemaining" to n(31))) }
        state("string-intent") { JsonObject(it + ("unloading" to s("true"))) }
        corrupt("missing-parent", "a-passenger") { JsonObject(it - "carriedBy") }
        corrupt("orphan-passenger", "a-passenger") { JsonObject(it + ("carriedBy" to JsonNull)) }
        corrupt("wrong-parent", "a-passenger") { JsonObject(it + ("carriedBy" to s("missing"))) }
        corrupt("foreign-passenger", "a-passenger") { JsonObject(it + ("ownerPlayerId" to s("p2"))) }
        corrupt("invalid-exit", "a-passenger") { JsonObject(it + ("transportExit" to obj("previousCarrierId" to s("craft"), "remaining" to n(46)))) }
    }
}
