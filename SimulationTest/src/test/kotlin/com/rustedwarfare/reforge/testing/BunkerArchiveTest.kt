package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class BunkerArchiveTest {
    @Test fun public_three_format_queue_boundaries_and_rejections_round_trip_the_strict_archive() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = public.resolve("scenarios/bunker-$format-short.json")
            val contract = public.resolve("contracts/bunker-$format-short.json")
            val output = TestArchives.directory("RWR_BUNKER_ARCHIVE_ROOT", javaClass, format).resolve("run")
            val result = Runner.run(RunRequest(scenario, contract, output, listOf("api:Runner.run/BunkerArchiveTest")))
            assertEquals(0, result.exitCode, result.toString())
            assertEquals(0, TraceFiles.compare(scenario, contract, output).exitCode)
            if (format == "json") {
                val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
                assertTrue(rows.filter { it["kind"] == s("observation") }.all {
                    "height" in it.objectAt("observation").arrayAt("units").first().jsonObject
                }, "Physical bunker height must survive the public archive")
                val declaration = readObject(scenario)
                val expected = readObject(contract)
                for (forgery in listOf("progress", "early-form", "completion", "cooldown")) {
                    val step = if (forgery == "completion") 166 else 20
                    val changed = rows.map { row ->
                        if (row["kind"] != s("observation") || row["phase"] != s("after-advance") ||
                            row.objectAt("observation")["stepIndex"] != n(step)) row else {
                            fun alter(sample: JsonObject): JsonObject {
                                if (forgery == "completion") return JsonObject(sample + ("events" to array(emptyList())))
                                return JsonObject(sample + ("units" to array(sample.arrayAt("units").map {
                                    val unit = it.jsonObject
                                    if (unit["unitId"] != s("a")) unit
                                    else if (forgery == "early-form") JsonObject(unit + ("typeId" to s("mechBunkerDeployed")))
                                    else {
                                        val state = unit.objectAt("unitActions")
                                        val actions = state.objectAt("actions")
                                        val action = actions.objectAt("deploy")
                                        val field = if (forgery == "progress") "progress" else "cooldownRemaining"
                                        val forgedAction = JsonObject(action + (field to n(1)))
                                        val forgedActions = JsonObject(actions + ("deploy" to forgedAction))
                                        val forgedState = JsonObject(state + ("actions" to forgedActions))
                                        JsonObject(unit + ("unitActions" to forgedState))
                                    }
                                })))
                            }
                            val host = alter(row.objectAt("observation"))
                            val players = host.objectAt("players")
                            JsonObject(row + ("observation" to JsonObject(host + ("players" to JsonObject(players +
                                ("p1" to alter(players.objectAt("p1"))))))))
                        }
                    }
                    assertEquals(3, TraceComparator.evaluate(declaration, expected, changed).exitCode,
                        "Matching host/owner forgery must fail the independent $forgery contract")
                }
            }
        }
    }
}
