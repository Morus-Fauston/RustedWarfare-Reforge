package com.rustedwarfare.reforge.testing.perf

import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AutomaticVisionPerfTest {
    @Test
    fun a1_memory_one_move_discovers_hidden_wall_and_arrives_with_full_raw_steps() {
        val directory = Files.createTempDirectory("avp05-a1-memory-")
        println("avp05-a1-output=$directory")
        val fixture = AutomaticVisionPerf.hiddenDiscoveryFixture(TerrainRetention.MEMORY)
        assertEquals(600, fixture.scenario.map.groundGids.size)
        assertEquals(15, fixture.scenario.map.blocking.blockedCount)
        assertEquals(2500, fixture.maxSteps)
        assertEquals(8, fixture.tailSteps)
        val run = try {
            AutomaticVisionPerf.capture(fixture)
        } catch (error: AutomaticVisionPerfCaptureException) {
            Files.write(directory.resolve("partial-raw.jsonl"), error.rows.map { it.toString() })
            throw error
        }
        Files.write(directory.resolve("raw.jsonl"), run.rows.map { it.toString() })
        val legacy = assertFailsWith<IllegalArgumentException> { AutomaticVisionPerf.validate(run) }
        assertTrue(legacy.message.orEmpty().contains("Known-terrain remaining route collision step184"))
        AutomaticVisionPerf.qualifyContinuousRoute(run)
        fun rejects(name: String, rows: List<JsonObject>, reason: String? = null) {
            val failure = assertFailsWith<IllegalArgumentException>(name) { AutomaticVisionPerf.qualifyContinuousRoute(run.copy(rows = rows)) }
            if (reason != null) assertTrue(failure.message.orEmpty().contains(reason), "$name: ${failure.message}")
            println("avp05-bad-control=$name rejected")
        }
        val repairRow = run.rows.indexOfFirst { row -> row["samples"]?.jsonArray.orEmpty().any { it.jsonObject["reason"] == JsonPrimitive("ROUTE_REPAIR") } }
        assertTrue(repairRow > 2)
        fun changeRepair(transform: (JsonObject) -> JsonObject): List<JsonObject> = run.rows.mapIndexed { index, row ->
            if (index != repairRow) row else transform(row)
        }
        rejects("missing-required-repair", changeRepair { JsonObject(it + ("samples" to JsonArray(emptyList()))) })
        rejects("duplicate-repair", changeRepair { row -> JsonObject(row + ("samples" to JsonArray(row.getValue("samples").jsonArray.toList() + row.getValue("samples").jsonArray.toList()))) })
        val anotherRealPoint = run.rows[repairRow - 1].getValue("observation").jsonObject.getValue("units").jsonArray.single().jsonObject.getValue("pathPoints").jsonArray.last()
        rejects("wrong-real-anchor", changeRepair { row -> JsonObject(row + ("samples" to JsonArray(row.getValue("samples").jsonArray.map {
            JsonObject(it.jsonObject + ("searchTarget" to anotherRealPoint))
        }))) }, "Repair target")
        for ((name, field, value) in listOf(
            Triple("forged-outcome", "outcome", JsonPrimitive("UNREACHABLE")),
            Triple("wrong-command", "actionId", JsonPrimitive("other-command")),
            Triple("wrong-anchor", "searchTarget", buildJsonObject { put("x", 123.0); put("y", 123.0) }))) {
            rejects(name, changeRepair { row -> JsonObject(row + ("samples" to JsonArray(row.getValue("samples").jsonArray.map {
                JsonObject(it.jsonObject + (field to value))
            }))) })
        }
        fun changeUnit(field: String, value: JsonElement, rowIndex: Int = repairRow, extra: Map<String, JsonElement> = emptyMap()): List<JsonObject> = run.rows.mapIndexed { index, row ->
            if (index != rowIndex) return@mapIndexed row
            val observed = row.getValue("observation").jsonObject
            val unit = observed.getValue("units").jsonArray.single().jsonObject
            val changed = JsonArray(listOf(JsonObject(unit + extra + (field to value))))
            val players = row.getValue("players").jsonObject
            val owner = players.getValue("p1").jsonObject
            JsonObject(row + mapOf("observation" to JsonObject(observed + ("units" to changed)),
                "players" to JsonObject(players + ("p1" to JsonObject(owner + ("observation" to
                    JsonObject(owner.getValue("observation").jsonObject + ("units" to changed))))))))
        }
        // Both endpoints are clear; the segment crosses x=280..300 through the wall interior.
        rejects("actual-truth-sweep", changeUnit("position", buildJsonObject { put("x", 330.0); put("y", 90.0) }), "Truth sweep collision")
        rejects("outside-single-path", changeUnit("pathPoints", JsonArray(listOf(buildJsonObject { put("x", 430.0); put("y", -20.0) })), 2), "Invalid path point")
        rejects("wrong-exact-target", changeUnit("moveTarget", buildJsonObject { put("x", 431.0); put("y", 70.0) }, 2), "Path/target")
        rejects("nonfinite-heading", changeUnit("heading", JsonPrimitive("NaN"), 2), "Non-finite motion")
        rejects("vanishing-move", changeUnit("moveTarget", JsonNull, 12, mapOf("pathPoints" to JsonArray(emptyList()),
            "movementState" to JsonPrimitive("IDLE"), "speed" to JsonPrimitive(0.0))), "Move ended without")
        for (field in listOf("events", "actionResults")) {
            rejects("hidden-player-$field", run.rows.mapIndexed { index, row ->
                if (index != 2) row else {
                    val players = row.getValue("players").jsonObject
                    val hidden = players.getValue("p2").jsonObject
                    val observed = hidden.getValue("observation").jsonObject
                    JsonObject(row + ("players" to JsonObject(players + ("p2" to JsonObject(hidden + ("observation" to
                        JsonObject(observed + (field to row.getValue("observation").jsonObject.getValue(field)))))))))
                }
            }, "Player p2 $field")
        }
        rejects("known-wall-tail", changeUnit("pathPoints", JsonArray(listOf(
            buildJsonObject { put("x", 270.0); put("y", 90.0) },
            buildJsonObject { put("x", 310.0); put("y", 90.0) }))))
        rejects("missing-terminal-tail", run.rows.dropLast(2) + run.rows.last())
        rejects("early-completion", run.rows.dropLast(1) + JsonObject(run.rows.last() + ("arrivedStep" to JsonPrimitive(1))))
        assertEquals("initial", run.rows.first().getValue("label").jsonPrimitive.content)
        assertEquals("completion", run.rows.last().getValue("label").jsonPrimitive.content)
        val reasons = run.rows.flatMap { it["samples"]?.jsonArray.orEmpty() }
            .map { it.jsonObject.getValue("reason").jsonPrimitive.content }
        assertTrue("USER_MOVE" in reasons)
        assertTrue("KNOWLEDGE_REPLAN" in reasons)
        println("avp05-a1-rows=${run.rows.size}")
        println("avp05-a1-raw-bytes=${Files.size(directory.resolve("raw.jsonl"))}")
    }
}
