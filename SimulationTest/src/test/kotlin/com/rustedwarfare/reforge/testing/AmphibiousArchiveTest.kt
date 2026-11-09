package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class AmphibiousArchiveTest {
    @Test fun rehashed_archives_cannot_omit_or_forge_native_flight_state() {
        val (scenario, contract) = amphibiousScenarioFixture(javaClass, "json")
        val original = scenario.parent.resolve("original")
        assertEquals(0, Runner.run(RunRequest(scenario, contract, original, listOf("api:Runner.run/AmphibiousArchiveTest"))).exitCode)
        assertEquals(0, TraceFiles.compare(scenario, contract, original).exitCode)
        val document = readObject(scenario)
        val source = original.resolve("reforge/raw.jsonl")
        val originalRows = Files.readAllLines(source).map { json.parseToJsonElement(it).jsonObject }
        val index = originalRows.indexOfFirst { it["kind"] == s("observation") && it["phase"] == s("after-advance") }
        assertTrue(index > 0)
        fun corrupt(name: String, change: (JsonObject) -> JsonObject) {
            val target = scenario.parent.resolve(name)
            Files.walk(original).use { paths -> paths.forEach { from ->
                val to = target.resolve(original.relativize(from)).normalize()
                require(to.startsWith(scenario.parent.toAbsolutePath().normalize()))
                if (Files.isDirectory(from)) Files.createDirectories(to) else Files.copy(from, to)
            } }
            val rows = originalRows.toMutableList()
            val observation = rows[index].objectAt("observation")
            val units = observation.arrayAt("units").map { if (it.jsonObject["unitId"] == s("jet")) change(it.jsonObject) else it }
            rows[index] = JsonObject(rows[index] + ("observation" to JsonObject(observation + ("units" to array(units)))))
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
            assertTrue(result.document.toString().contains("amphibious-"), "$name: ${result.document}")
        }
        fun state(name: String, change: (JsonObject) -> JsonObject) = corrupt(name) {
            JsonObject(it + ("amphibiousJet" to change(it.objectAt("amphibiousJet"))))
        }
        corrupt("missing-state") { JsonObject(it - "amphibiousJet") }
        corrupt("missing-height") { JsonObject(it - "height") }
        state("missing-intent") { JsonObject(it - "flyingIntent") }
        state("unknown-field") { JsonObject(it + ("hostCounter" to n(0))) }
        state("wrong-category") { JsonObject(it + ("underwater" to b(true))) }
        state("wrong-movement") { JsonObject(it + ("movementType" to s("AIR"))) }
        state("wrong-speed") { JsonObject(it + ("maximumSpeed" to n(.4))) }
        state("wrong-range") { JsonObject(it + ("attackRange" to n(170))) }
        state("invalid-wing") { JsonObject(it + ("wingFold" to n(1.01))) }
        state("invalid-phase") { JsonObject(it + ("phase" to n(361))) }
        state("invalid-vertical-speed") { JsonObject(it + ("verticalSpeed" to n(-.001))) }
        state("string-dive") { JsonObject(it + ("canDive" to s("true"))) }
        println("AMPHIBIOUS_ARCHIVE=${scenario.parent}")
    }
}
