package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class RepairBayArchiveTest {
    @Test fun three_syntaxes_round_trip_native_mobile_reclaim_manual_repair_and_automatic_repair() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = public.resolve("scenarios/repairbay-$format-short.json")
            val contract = public.resolve("contracts/repairbay-$format-short.json")
            val output = TestArchives.directory("RWR_REPAIRBAY_ARCHIVE_ROOT", javaClass, format).resolve("run")
            val result = Runner.run(RunRequest(scenario, contract, output, listOf("api:Runner.run/RepairBayArchiveTest")))
            assertEquals(0, result.exitCode, result.toString())
            assertEquals(0, TraceFiles.compare(scenario, contract, output).exitCode)
            if (format == "json") {
                val declaration = readObject(scenario); val expected = readObject(contract)
                val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
                for (fault in listOf("auto-flag", "heal", "refund", "workers")) {
                    val step = if (fault == "refund") 181 else 223
                    val forged = rows.map { row ->
                        if (row["kind"] != s("observation") || row["phase"] != s("after-advance") ||
                            row.objectAt("observation")["stepIndex"] != n(step)) row else {
                            fun alter(sample: JsonObject): JsonObject {
                                if (fault == "workers") return JsonObject(sample + ("construction" to JsonObject(
                                    sample.objectAt("construction") + ("workers" to array(emptyList())))))
                                if (fault == "refund") {
                                    val economy = sample.objectAt("economy")
                                    return JsonObject(sample + ("economy" to JsonObject(economy + ("preciseBalances" to
                                        JsonObject(economy.objectAt("preciseBalances") + ("p1" to n(16400)))))))
                                }
                                return JsonObject(sample + ("units" to array(sample.arrayAt("units").map {
                                    val unit = it.jsonObject
                                    if (fault == "heal" && unit["unitId"] == s("worker2")) JsonObject(unit + ("health" to n(170)))
                                    else if (fault == "auto-flag" && unit["unitId"] == s("bay")) JsonObject(unit + ("tasks" to
                                        array(unit.arrayAt("tasks").map { task -> JsonObject(task.jsonObject + ("automaticRepair" to b(false))) })))
                                    else unit
                                })))
                            }
                            val host = alter(row.objectAt("observation"))
                            val players = host.objectAt("players")
                            val owner = if (fault in listOf("auto-flag", "heal")) alter(players.objectAt("p1")) else players.objectAt("p1")
                            JsonObject(row + ("observation" to JsonObject(host + ("players" to JsonObject(players + ("p1" to owner))))))
                        }
                    }
                    assertTrue(TraceComparator.evaluate(declaration, expected, forged).exitCode != 0, fault)
                }
            }
        }
    }
}
