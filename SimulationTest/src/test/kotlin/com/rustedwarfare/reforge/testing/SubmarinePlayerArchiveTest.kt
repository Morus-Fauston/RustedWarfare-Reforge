package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class SubmarinePlayerArchiveTest {
    @Test fun archive_preserves_authorized_launch_style_and_rejects_enemy_control_leaks_or_missing_public_style() {
        val (scenario, contract) = submarineScenarioFixture(javaClass, "ini")
        val before = readObject(scenario)
        val document = JsonObject(before + mapOf("automaticVision" to obj("retention" to s("MEMORY"), "pathSource" to s("PLAYER_KNOWLEDGE"),
            "shareAlliedVision" to b(false), "observers" to array(listOf(s("p1"),s("p2"))), "grid" to obj("width" to n(40), "height" to n(30))),
            "players" to array(before.arrayAt("players").map { JsonObject(it.jsonObject + ("control" to s("local"))) }),
            "units" to array(before.arrayAt("units").map { JsonObject(it.jsonObject + ("type" to s("attackSubmarine"))) })))
        Files.writeString(scenario, document.toString())
        val output = scenario.parent.resolve("run")
        val run = Runner.run(RunRequest(scenario, contract, output, listOf("api:Runner.run/SubmarinePlayerArchiveTest")))
        assertEquals(0, run.exitCode, Files.readString(output.resolve("comparison/result.json")))
        assertEquals(0, TraceFiles.compare(scenario, contract, output).exitCode)
        val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val index = rows.indexOfFirst { it["kind"] == s("observation") && it["phase"] == s("after-advance") }
        val observation = rows[index].objectAt("observation")
        val players = observation.objectAt("players")
        for (id in listOf("p1", "p2")) {
            val view = players.objectAt(id)
            val sub = view.arrayAt("units").map { it.jsonObject }.single { it["unitId"] == s("sub") }
            val state = sub.objectAt("submarine")
            assertEquals(if (id == "p1") b(true) else JsonNull, state["surfaceIntent"])
            assertEquals(if (id == "p1") n(.003f) else JsonNull, state["verticalSpeed"])
            for (field in listOf("projectiles", "projectileEvents")) {
                assertEquals(1, view.arrayAt(field).size)
                assertTrue(view.arrayAt(field).all { it.jsonObject["appearance"] == s("SUBMARINE_TORPEDO") })
            }
        }
        fun corrupt(id: String, change: (JsonObject) -> JsonObject) {
            val values = rows.toMutableList()
            val changed = JsonObject(observation + ("players" to JsonObject(players + (id to change(players.objectAt(id))))))
            values[index] = JsonObject(rows[index] + ("observation" to changed))
            val result = TraceComparator.evaluate(document, readObject(contract), values)
            assertEquals(3, result.exitCode, result.document.toString())
        }
        fun depth(id: String, change: (JsonObject) -> JsonObject) = corrupt(id) { view -> JsonObject(view + ("units" to array(view.arrayAt("units").map {
            val unit = it.jsonObject
            if (unit["unitId"] == s("sub")) JsonObject(unit + ("submarine" to change(unit.objectAt("submarine")))) else unit
        }))) }
        depth("p2") { JsonObject(it + ("surfaceIntent" to b(true))) }
        depth("p2") { JsonObject(it + ("verticalSpeed" to n(.003f))) }
        depth("p1") { JsonObject(it + ("surfaceIntent" to JsonNull)) }
        for (field in listOf("projectiles", "projectileEvents")) {
            corrupt("p2") { view -> JsonObject(view + (field to array(view.arrayAt(field).map { JsonObject(it.jsonObject - "appearance") }))) }
            corrupt("p1") { view -> JsonObject(view + (field to array(view.arrayAt(field).map { JsonObject(it.jsonObject + ("appearance" to s("UNKNOWN"))) }))) }
        }
    }
}
