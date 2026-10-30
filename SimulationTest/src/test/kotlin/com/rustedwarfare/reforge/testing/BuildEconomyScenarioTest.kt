package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

class BuildEconomyScenarioTest {
    private val root = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")

    @Test fun `initial ordinary upgrade type remains the source until its declared completion`() {
        val source = root.resolve("scenarios/build-economy-short.json")
        val original = readObject(source)
        val temp = Files.createTempDirectory("initial-upgrade-boundary-")
        val content = original.objectAt("content")
        fun absolute(ref: JsonObject) = JsonObject(ref + ("path" to s(source.parent.resolve(ref.text("path")).normalize().toString())))
        val scenario = temp.resolve("scenario.json")
        Files.writeString(scenario, JsonObject(original + mapOf(
            "content" to JsonObject(content + mapOf("map" to absolute(content.objectAt("map")),
                "unitVariants" to array(content.arrayAt("unitVariants").map { absolute(it.jsonObject) }),
                "dependencies" to array(content.arrayAt("dependencies").map { absolute(it.jsonObject) }))),
            "actions" to array(original.arrayAt("actions").filter { it.jsonObject.id("actionId") == "upgrade" }),
            "termination" to JsonObject(original.objectAt("termination") + ("steps" to array((0..1667).map {
                obj("stepIndex" to n(it), "delta" to n(1), "deltaUnit" to s("sim-tick"))
            }))))).toString())
        val contract = temp.resolve("contract.json")
        val expected = readObject(root.resolve("contracts/build-economy-short.json"))
        Files.writeString(contract, JsonObject(expected + mapOf(
            "constructedUnits" to array(emptyList()),
            "convertedUnits" to array(listOf(obj("unitId" to s("e"), "upgradeActionId" to s("upgrade"),
                "sourceTypeId" to s("extractorT1"), "targetTypeId" to s("extractorT2"), "completionStepIndex" to n(1666)))),
            "rules" to array(listOf(obj("ruleId" to s("completed"),
                "when" to obj("phase" to s("after-advance"), "stepIndex" to n(1666)),
                "assertions" to array(listOf(obj("path" to s("units.e.type"), "operator" to s("equals"), "value" to s("extractorT2")))))))
        )).toString())
        val output = temp.resolve("run")
        assertEquals(0, Runner.run(RunRequest(scenario, contract, output, listOf("initial-upgrade"))).exitCode)
        val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val damaged = rows.map { row ->
            if (row["phase"] != s("after-advance") || row.objectAt("observation")["stepIndex"] != n(17)) row
            else {
                val view = row.objectAt("observation")
                JsonObject(row + ("observation" to JsonObject(view + ("units" to array(view.arrayAt("units").map {
                    if (it.jsonObject.id("unitId") == "e") JsonObject(it.jsonObject + ("typeId" to s("extractorT2"))) else it
                })))))
            }
        }
        assertEquals(3, TraceComparator.evaluate(readObject(scenario), readObject(contract), damaged).exitCode)
    }

    @Test fun `construction conversions must bind an existing ordinary upgrade action and exact boundary`() {
        val scenario = readObject(root.resolve("scenarios/build-economy-chain.json"))
        val contract = readObject(root.resolve("contracts/build-economy-chain.json"))
        val conversion = contract.arrayAt("convertedUnits").single().jsonObject
        val changes = listOf(
            "upgradeActionId" to s("produce-worker"), "sourceTypeId" to s("landFactory"),
            "targetTypeId" to s("fabricatorT3"), "completionStepIndex" to n(3399),
            "unitId" to s("h"))
        for ((field, value) in changes) {
            val bad = JsonObject(contract + ("convertedUnits" to array(listOf(JsonObject(conversion + (field to value))))))
            assertEquals(64, TraceComparator.evaluate(scenario, bad, emptyList()).exitCode, field)
        }
    }

    @Test fun `construction income and ordinary upgrade run through formal scene actions`() {
        val output = Files.createTempDirectory("build-economy-short-").resolve("run")
        val result = Runner.run(RunRequest(root.resolve("scenarios/build-economy-short.json"),
            root.resolve("contracts/build-economy-short.json"), output, listOf("build-economy-short")))
        assertEquals(0, result.exitCode, Files.readString(output.resolve("comparison/result.json")))
    }

    @Test fun `illegal resource placement and unpaid construction keep the account and world intact`() {
        val output = Files.createTempDirectory("build-failure-short-").resolve("run")
        val result = Runner.run(RunRequest(root.resolve("scenarios/build-failure-short.json"),
            root.resolve("contracts/build-failure-short.json"), output, listOf("build-failure-short")))
        assertEquals(0, result.exitCode, Files.readString(output.resolve("comparison/result.json")))
    }

    @Test fun `empty construction declaration still rejects an unknown intermediate building diagnostic`() {
        val scenario = root.resolve("scenarios/build-failure-short.json")
        val contract = root.resolve("contracts/build-failure-short.json")
        val output = Files.createTempDirectory("empty-construction-negative-").resolve("run")
        assertEquals(0, Runner.run(RunRequest(scenario, contract, output, listOf("empty-construction"))).exitCode)
        val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val damaged = rows.map { row ->
            if (row["phase"] != s("after-advance") || row.objectAt("observation")["stepIndex"] != n(2)) row
            else {
                val view = row.objectAt("observation")
                val construction = view.objectAt("construction")
                assertTrue(construction.arrayAt("buildings").isEmpty())
                val unexpected = obj("unitId" to s("constructed:p1:" + "c".repeat(32)),
                    "objectId" to n(999), "updateKey" to s("unexpected-building"), "progress" to n(0))
                JsonObject(row + ("observation" to JsonObject(view + ("construction" to
                    JsonObject(construction + ("buildings" to array(listOf(unexpected))))))))
            }
        }
        assertEquals(3, TraceComparator.evaluate(readObject(scenario), readObject(contract), damaged).exitCode)
    }

    @Test fun `intermediate construction and precise income samples cannot be partially dropped`() {
        val scenario = root.resolve("scenarios/build-economy-short.json")
        val contract = root.resolve("contracts/build-economy-short.json")
        val output = Files.createTempDirectory("build-economy-negative-").resolve("run")
        assertEquals(0, Runner.run(RunRequest(scenario, contract, output, listOf("construction-negative"))).exitCode)
        val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val changes = listOf("precise-player", "construction", "worker", "building-progress", "income-unit", "income-elapsed",
            "host-progress", "host-target", "player-progress", "player-target", "nonce")
        val missed = changes.mapNotNull { change ->
            val damaged = rows.map { row ->
                if (row["phase"] != s("after-advance") || row.objectAt("observation")["stepIndex"] != n(17)) row
                else {
                    val view = row.objectAt("observation")
                    fun construction(field: String, value: JsonElement) = JsonObject(view +
                        ("construction" to JsonObject(view.objectAt("construction") + (field to value))))
                    val next = when (change) {
                        "precise-player" -> JsonObject(view + ("economy" to JsonObject(view.objectAt("economy") +
                            ("preciseBalances" to JsonObject(view.objectAt("economy").objectAt("preciseBalances") - "p1")))))
                        "construction" -> JsonObject(view - "construction")
                        "worker" -> construction("workers", array(emptyList()))
                        "building-progress" -> construction("buildings", array(view.objectAt("construction").arrayAt("buildings").map { JsonObject(it.jsonObject - "progress") }))
                        "nonce" -> construction("nonces", obj())
                        "income-unit" -> JsonObject(view + ("incomeStates" to array(view.arrayAt("incomeStates").filter { it.jsonObject.id("unitId") != "e" })))
                        "income-elapsed" -> JsonObject(view + ("incomeStates" to array(view.arrayAt("incomeStates").map { JsonObject(it.jsonObject - "elapsed") })))
                        else -> {
                            val field = if (change.endsWith("progress")) "constructionProgress" else "constructionTargetId"
                            fun remove(units: JsonArray) = array(units.map { if (it.jsonObject.id("unitId") == "w") JsonObject(it.jsonObject - field) else it })
                            if (change.startsWith("host")) JsonObject(view + ("units" to remove(view.arrayAt("units"))))
                            else {
                                val player = view.objectAt("players").objectAt("p1")
                                JsonObject(view + ("players" to JsonObject(view.objectAt("players") +
                                    ("p1" to JsonObject(player + ("units" to remove(player.arrayAt("units"))))))))
                            }
                        }
                    }
                    JsonObject(row + ("observation" to next))
                }
            }
            val result = TraceComparator.evaluate(readObject(scenario), readObject(contract), damaged)
            if (result.exitCode == 3) null else "$change: ${result.exitCode}"
        }
        assertEquals(emptyList(), missed)
    }
}
