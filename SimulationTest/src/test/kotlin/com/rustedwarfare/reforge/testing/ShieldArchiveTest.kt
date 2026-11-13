package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class ShieldArchiveTest {
    private val root = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")

    @Test fun malformed_intermediate_host_and_player_shields_cannot_pass_independent_trace_validation() {
        val source = root.resolve("scenarios/heavy-mech-ini-short.json")
        val contract = root.resolve("contracts/heavy-mech-ini-short.json")
        val output = Files.createTempDirectory("shield-integrity-").resolve("run")
        assertEquals(0, Runner.run(RunRequest(source, contract, output, listOf("shield-integrity"))).exitCode)
        val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        assertEquals(0, TraceComparator.evaluate(readObject(source), readObject(contract), rows).exitCode)
        val mutations: Map<String, (JsonObject) -> JsonObject> = linkedMapOf(
            "missing" to { JsonObject(it - "shield") },
            "null" to { JsonObject(it + ("shield" to JsonNull)) },
            "partial" to { JsonObject(it + ("shield" to JsonObject(it.objectAt("shield") - "displayDamage"))) },
            "wrong-type" to { JsonObject(it + ("shield" to JsonObject(it.objectAt("shield") + ("current" to s("500"))))) },
            "zero-capacity" to { JsonObject(it + ("shield" to JsonObject(it.objectAt("shield") + ("maximum" to n(0))))) },
            "negative-current" to { JsonObject(it + ("shield" to JsonObject(it.objectAt("shield") + ("current" to n(-1))))) },
            "over-capacity" to { JsonObject(it + ("shield" to JsonObject(it.objectAt("shield") + ("current" to n(501))))) },
            "overflow" to { JsonObject(it + ("shield" to JsonObject(it.objectAt("shield") + ("maximum" to n(1e100))))) },
            "negative-display" to { JsonObject(it + ("shield" to JsonObject(it.objectAt("shield") + ("displayDamage" to n(-1))))) },
            "extra-field" to { JsonObject(it + ("shield" to JsonObject(it.objectAt("shield") + ("privateTimer" to n(1))))) })
        val missed = mutableListOf<String>()
        for (view in listOf("host", "p1")) for ((name, mutate) in mutations) {
            var edited = false
            val changed = rows.map { row ->
                if (row["kind"] != s("observation") || row["phase"] != s("after-advance") ||
                    row.objectAt("observation")["stepIndex"] != n(5)) row
                else {
                    edited = true
                    val observation = row.objectAt("observation")
                    val sample = if (view == "host") observation else observation.objectAt("players").objectAt(view)
                    val damaged = JsonObject(sample + ("units" to array(sample.arrayAt("units").map { mutate(it.jsonObject) })))
                    JsonObject(row + ("observation" to if (view == "host") damaged else JsonObject(observation +
                        ("players" to JsonObject(observation.objectAt("players") + (view to damaged))))))
                }
            }
            assertTrue(edited)
            val result = TraceComparator.evaluate(readObject(source), readObject(contract), changed)
            if (result.exitCode != 3 || !result.document.toString().contains("shield-state")) missed += "$view/$name:${result.document}"
        }
        assertEquals(emptyList(), missed)
    }

    @Test fun three_format_host_and_authorized_archives_keep_shield_state_and_reopen_full() {
        for (format in listOf("ini", "json", "hjson")) {
            val source = root.resolve("scenarios/heavy-mech-$format-short.json")
            val contract = root.resolve("contracts/heavy-mech-$format-short.json")
            val output = Files.createTempDirectory("shield-archive-$format-").resolve("run")
            val result = Runner.run(RunRequest(source, contract, output, listOf("shield-archive")))
            assertEquals(0, result.exitCode, result.toString())
            val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl"))
                .map { json.parseToJsonElement(it).jsonObject }
            val full = obj("current" to n(500f), "maximum" to n(500f), "displayDamage" to n(0f))
            for (row in rows.filter { it["kind"] == s("observation") }) {
                val sample = row.objectAt("observation")
                assertEquals(full, sample.arrayAt("units").single().jsonObject["shield"], "$format host")
                assertEquals(full, sample.objectAt("players").objectAt("p1").arrayAt("units").single().jsonObject["shield"], "$format player")
                assertTrue(sample.objectAt("players").objectAt("p2").arrayAt("units").isEmpty())
            }
            CoreAdapter(ScenarioReader.load(source, contract)).use { core ->
                core.initial()
                core.close()
                assertEquals(full, core.reopen().json().arrayAt("units").single().jsonObject["shield"])
            }
        }
    }
}
