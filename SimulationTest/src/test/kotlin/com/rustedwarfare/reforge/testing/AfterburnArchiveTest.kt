package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class AfterburnArchiveTest {
    @Test fun the_public_action_scenario_uses_both_forms_the_integer_cooldown_and_automatic_recovery_in_three_formats() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = public.resolve("scenarios/afterburn-$format-short.json")
            val contract = public.resolve("contracts/afterburn-$format-short.json")
            val root = TestArchives.directory("RWR_AFTERBURN_ARCHIVE_ROOT", javaClass, format)
            val result = Runner.run(RunRequest(scenario, contract, root.resolve("original"), listOf("api:Runner.run/AfterburnArchiveTest")))
            assertEquals(0, result.exitCode, result.toString())
            assertEquals(0, TraceFiles.compare(scenario, contract, root.resolve("original")).exitCode)
            if (format == "json") {
                val rows = Files.readAllLines(root.resolve("original/reforge/raw.jsonl"))
                    .map { json.parseToJsonElement(it).jsonObject }
                val missing = JsonObject(readObject(contract) - "unitActionConversions")
                assertEquals(3, TraceComparator.evaluate(readObject(scenario), missing, rows).exitCode,
                    "A type change without its independent action conversion declaration must fail")
                val changed = rows.map { row ->
                    if (row["kind"] != s("observation") || row["phase"] != s("after-advance") ||
                        row.objectAt("observation")["stepIndex"] != n(20)) row else {
                        fun alter(sample: JsonObject): JsonObject = JsonObject(sample + ("units" to array(sample.arrayAt("units").map {
                            val unit = it.jsonObject
                            if (unit["unitId"] != s("aa")) unit else {
                                val state = unit.objectAt("unitActions"); val actions = state.objectAt("actions")
                                JsonObject(unit + ("unitActions" to JsonObject(state + ("actions" to JsonObject(actions +
                                    ("afterBurn" to JsonObject(actions.objectAt("afterBurn") + mapOf(
                                        "cooldownRemaining" to n(0), "available" to b(true)))))))))
                            }
                        })))
                        val host = alter(row.objectAt("observation")); val players = host.objectAt("players")
                        JsonObject(row + ("observation" to JsonObject(host + ("players" to JsonObject(players +
                            ("p1" to alter(players.objectAt("p1"))))))))
                    }
                }
                assertEquals(3, TraceComparator.evaluate(readObject(scenario), readObject(contract), changed).exitCode,
                    "Matching forged host/owner cooldown cannot override the independent action clock")
                val original = readObject(contract)
                for ((field, value) in listOf("targetTypeId" to s("builder"), "sourceTypeId" to s("aaBeamGunship_afterburn"),
                    "completionStepIndex" to n(-1), "completionStepIndex" to n(1), "actionId" to s("locked"), "unknown" to n(1))) {
                    val changes = original.arrayAt("unitActionConversions")
                    val broken = JsonObject(original + ("unitActionConversions" to array(listOf(
                        JsonObject(changes.first().jsonObject + (field to value))) + changes.drop(1))))
                    assertNotEquals(0, TraceComparator.evaluate(readObject(scenario), broken, rows).exitCode, field)
                }
                val autoCooldown = alterBoth(rows, 302, "automaticCooldown", n(0))
                assertEquals(3, TraceComparator.evaluate(readObject(scenario), original, autoCooldown).exitCode,
                    "The automatic conversion must set the new definition's 60-unit cooldown")
            }
        }
    }

    private fun alterBoth(rows: List<JsonObject>, step: Int, field: String, value: JsonElement): List<JsonObject> = rows.map { row ->
        if (row["kind"] != s("observation") || row["phase"] != s("after-advance") ||
            row.objectAt("observation")["stepIndex"] != n(step)) row else {
            fun alter(sample: JsonObject) = JsonObject(sample + ("units" to array(sample.arrayAt("units").map {
                val unit = it.jsonObject
                if (unit["unitId"] != s("aa")) unit else JsonObject(unit + ("unitActions" to
                    JsonObject(unit.objectAt("unitActions") + (field to value))))
            })))
            val host = alter(row.objectAt("observation")); val players = host.objectAt("players")
            JsonObject(row + ("observation" to JsonObject(host + ("players" to JsonObject(players +
                ("p1" to alter(players.objectAt("p1"))))))))
        }
    }
}
