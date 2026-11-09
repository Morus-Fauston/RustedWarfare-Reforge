package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class SubmarineArchiveTest {
    @Test fun independent_archive_reader_rejects_missing_or_forged_depth_and_launch_fields_even_after_rehash() {
        val (scenario, contract) = submarineScenarioFixture(javaClass, "json")
        val original = scenario.parent.resolve("original")
        assertEquals(0, Runner.run(RunRequest(scenario, contract, original, listOf("api:Runner.run/SubmarineArchiveTest"))).exitCode)
        assertEquals(0, TraceFiles.compare(scenario, contract, original).exitCode)
        val document = readObject(scenario)
        val originalRows = Files.readAllLines(original.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val index = originalRows.indexOfFirst { it["kind"] == s("observation") && it["phase"] == s("after-advance") }
        assertTrue(index > 0)
        fun corrupt(name: String, change: (JsonObject) -> JsonObject) {
            val target = scenario.parent.resolve(name)
            Files.walk(original).use { paths -> paths.forEach { source ->
                val destination = target.resolve(original.relativize(source)).normalize()
                require(destination.startsWith(scenario.parent.toAbsolutePath().normalize()))
                if (Files.isDirectory(source)) Files.createDirectories(destination) else Files.copy(source, destination)
            } }
            val rows = originalRows.toMutableList()
            rows[index] = JsonObject(rows[index] + ("observation" to change(rows[index].objectAt("observation"))))
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
            Files.writeString(target.resolve("readonly-consumer-judgment.json"), result.document.toString())
            assertEquals(3, result.exitCode, "$name: ${result.document}")
            assertTrue(result.document.toString().contains("submarine-"), "$name: ${result.document}")
        }
        fun unit(name: String, change: (JsonObject) -> JsonObject) = corrupt(name) { observation ->
            JsonObject(observation + ("units" to array(observation.arrayAt("units").map {
                if (it.jsonObject["unitId"] == s("sub")) change(it.jsonObject) else it
            })))
        }
        fun depth(name: String, change: (JsonObject) -> JsonObject) = unit(name) {
            JsonObject(it + ("submarine" to change(it.objectAt("submarine"))))
        }
        unit("missing-depth") { JsonObject(it - "submarine") }
        unit("missing-height") { JsonObject(it - "height") }
        depth("missing-intent") { JsonObject(it - "surfaceIntent") }
        depth("unknown-field") { JsonObject(it + ("secretIntegrator" to n(0))) }
        depth("string-boolean") { JsonObject(it + ("underwater" to s("true"))) }
        depth("wrong-category") { JsonObject(it + ("underwater" to b(false))) }
        depth("wrong-presentation") { JsonObject(it + ("surfacePresentation" to b(true))) }
        depth("wrong-speed") { JsonObject(it + ("maximumSpeed" to n(.8))) }
        depth("wrong-range") { JsonObject(it + ("attackRange" to n(250))) }
        depth("null-host-intent") { JsonObject(it + ("surfaceIntent" to JsonNull)) }
        depth("negative-vertical-speed") { JsonObject(it + ("verticalSpeed" to n(-.001))) }
        depth("overflow-vertical-speed") { JsonObject(it + ("verticalSpeed" to n(1e100))) }
        fun launch(name: String, event: Boolean = false, change: (JsonObject) -> JsonObject) = corrupt(name) { observation ->
            val field = if (event) "projectileEvents" else "projectiles"
            JsonObject(observation + (field to array(observation.arrayAt(field).map {
                val value = it.jsonObject
                if (event) JsonObject(value + ("projectile" to change(value.objectAt("projectile")))) else change(value)
            })))
        }
        launch("missing-launch-style") { JsonObject(it - "appearance") }
        launch("unknown-launch-style") { JsonObject(it + ("appearance" to s("SUBMARINE_CUSTOM"))) }
        launch("standard-launch-style") { JsonObject(it + ("appearance" to s("STANDARD"))) }
        launch("missing-event-style", event = true) { JsonObject(it - "appearance") }
        launch("missile-without-ballistic") { JsonObject(it + ("appearance" to s("SUBMARINE_MISSILE"))) }
        println("SUBMARINE_ARCHIVE=${scenario.parent}")
    }
}
