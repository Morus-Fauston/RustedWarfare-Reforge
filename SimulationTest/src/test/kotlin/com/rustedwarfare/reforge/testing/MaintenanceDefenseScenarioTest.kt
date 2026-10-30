package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

class MaintenanceDefenseScenarioTest {
    private val root = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")

    @Test fun `initial and formally constructed income registrations survive reclaim without further income`() {
        for (mode in listOf("initial", "unfinished", "completed")) {
            val dynamic = mode != "initial"
            val completed = mode == "completed"
            val source = root.resolve("scenarios/maintenance-queue-short.json")
            val original = readObject(source)
            val temp = Files.createTempDirectory("income-reclaim-")
            val target = if (dynamic) "constructed:p1:" + "a".repeat(32) else "e"
            // First active update decays preparation1 back to0 (previous target inactive).
            // Then preparation30 and 785 Float hits of .2f*5.1f remove 800HP at step815.
            // A zero-progress site instead waits elapsed100 (step100).
            val removalStep = if (completed) 2015 else if (dynamic) 100 else 815
            val finalStep = if (completed) 2149 else 949
            val content = original.objectAt("content")
            fun absolute(ref: JsonObject) = JsonObject(ref + ("path" to s(source.parent.resolve(ref.text("path")).normalize().toString())))
            val worker = JsonObject(original.arrayAt("units").single().jsonObject +
                ("position" to obj("x" to n(120), "y" to n(290))))
            val extractor = obj("unitId" to s(target), "owner" to s("p1"), "type" to s("extractorT1"),
                "position" to obj("x" to n(190), "y" to n(290)), "heading" to n(0))
            val actions = if (dynamic) original.arrayAt("actions").take(3).map { element ->
                val action = element.jsonObject
                if (action.text("kind") == "build") JsonObject(action + mapOf("buildingTypeId" to s("extractorT1"),
                    "target" to obj("x" to n(190), "y" to n(290))))
                else if (completed) JsonObject(action + ("atStep" to n(1200))) else action
            } else listOf(obj("actionId" to s("reclaim"), "atStep" to n(0), "actor" to s("p1"),
                "kind" to s("reclaim"), "unitId" to s("a"), "targetUnitId" to s(target), "append" to b(false)))
            val input = JsonObject((original - "automaticVision") + mapOf(
                "content" to JsonObject(content + mapOf("map" to absolute(content.objectAt("map")),
                    "unitVariants" to array(content.arrayAt("unitVariants").map { absolute(it.jsonObject) }),
                    "dependencies" to array(content.arrayAt("dependencies").map { absolute(it.jsonObject) }))),
                "units" to array(if (dynamic) listOf(worker) else listOf(worker, extractor)),
                "combatContract" to JsonObject(original.objectAt("combatContract") + mapOf(
                    "unitObjectIds" to (if (dynamic) obj("a" to n(1)) else obj("a" to n(1), target to n(2))),
                    "nextObjectId" to n(if (dynamic) 2 else 3))),
                "actions" to array(actions),
                "termination" to JsonObject(original.objectAt("termination") + ("steps" to array((0..finalStep).map {
                    obj("stepIndex" to n(it), "delta" to n(1), "deltaUnit" to s("sim-tick"))
                })))
            ))
            val baseContract = readObject(root.resolve("contracts/maintenance-queue-short.json"))
            val expected = JsonObject(baseContract + mapOf(
                "constructedUnits" to array(if (dynamic) listOf(JsonObject(baseContract.arrayAt("constructedUnits").first().jsonObject +
                    ("typeId" to s("extractorT1")))) else emptyList()),
                "reclaimedUnits" to array(listOf(obj("unitId" to s(target), "reclaimActionId" to s("reclaim"),
                    "workerId" to s("a"), "owner" to s("p1"), "removalStepIndex" to n(removalStep)))),
                "rules" to array(listOf(obj("ruleId" to s("reclaimed-income"),
                    "when" to obj("phase" to s("after-advance"), "stepIndex" to n(finalStep)),
                    "assertions" to array(listOf(obj("path" to s("reclaimedUnitIds"), "operator" to s("equals"),
                        "value" to array(listOf(s(target)))))))))
            ))
            val scenario = temp.resolve("scenario.json")
            val contract = temp.resolve("contract.json")
            Files.writeString(scenario, input.toString()); Files.writeString(contract, expected.toString())
            val output = temp.resolve("run")
            assertEquals(0, Runner.run(RunRequest(scenario, contract, output, listOf("income-reclaim"))).exitCode,
                "dynamic=$dynamic: " + Files.readString(output.resolve("comparison/result.json")))
            val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
            val terminal = rows.single { it["phase"] == s("after-advance") && it.objectAt("observation")["stepIndex"] == n(removalStep) }.objectAt("observation")
            val final = rows.single { it["phase"] == s("after-advance") && it.objectAt("observation")["stepIndex"] == n(finalStep) }.objectAt("observation")
            if (completed) {
                val before = rows.single { it["phase"] == s("after-advance") && it.objectAt("observation")["stepIndex"] == n(1199) }.objectAt("observation")
                assertEquals(1.0, before.arrayAt("units").single { it.jsonObject.id("unitId") == target }.jsonObject.number("constructionProgress"))
                assertTrue(before.objectAt("economy").objectAt("preciseBalances").number("p1") > 1050.0)
            }
            assertTrue(final.arrayAt("units").none { it.jsonObject.id("unitId") == target })
            assertEquals(listOf(s(target)), final.arrayAt("reclaimedUnitIds").toList())
            assertEquals(listOf(target), final.arrayAt("incomeStates").map { it.jsonObject.id("unitId") })
            assertEquals(terminal.arrayAt("incomeStates"), final.arrayAt("incomeStates"))
            assertEquals(terminal.objectAt("economy").objectAt("preciseBalances"), final.objectAt("economy").objectAt("preciseBalances"))
            if (mode == "unfinished") assertEquals(0.0, final.arrayAt("incomeStates").single().jsonObject.number("elapsed"))
            for (mutation in listOf("missing", "duplicate", "unknown")) {
                val damaged = rows.map { row ->
                    if (row["phase"] != s("after-advance") || row.objectAt("observation")["stepIndex"] != n(finalStep)) row
                    else {
                        val incomes = final.arrayAt("incomeStates")
                        val changed = when (mutation) {
                            "missing" -> array(emptyList())
                            "duplicate" -> array(incomes + incomes.single())
                            else -> array(listOf(JsonObject(incomes.single().jsonObject + ("unitId" to s("unknown")))))
                        }
                        JsonObject(row + ("observation" to JsonObject(final + ("incomeStates" to changed))))
                    }
                }
                assertEquals(3, TraceComparator.evaluate(input, expected, damaged).exitCode, "dynamic=$dynamic $mutation")
            }
        }
    }

    @Test fun `hidden and missing maintenance targets preserve the same rejection boundary`() {
        val output = Files.createTempDirectory("maintenance-privacy-short-").resolve("run")
        val result = Runner.run(RunRequest(root.resolve("scenarios/maintenance-privacy-short.json"),
            root.resolve("contracts/maintenance-privacy-short.json"), output, listOf("privacy")))
        assertEquals(0, result.exitCode, Files.readString(output.resolve("comparison/result.json")))
    }

    @Test fun `run costs partition measured monotonic time without double counting construction or comparison`() {
        val output = Files.createTempDirectory("maintenance-costs-").resolve("run")
        assertEquals(0, Runner.run(RunRequest(root.resolve("scenarios/maintenance-repair-short.json"),
            root.resolve("contracts/maintenance-repair-short.json"), output, listOf("costs"))).exitCode)
        val costs = readObject(output.resolve("manifest.json")).objectAt("costs")
        val stages = costs.objectAt("stagesNanos")
        assertEquals(setOf("loadInitialize", "coreSimulation", "captureArchive", "comparison"), stages.keys)
        assertTrue(stages.keys.all { stages.integer(it) > 0 })
        assertEquals(costs.integer("measuredNanos"), stages.keys.sumOf { stages.integer(it) } + costs.integer("otherNanos"))
        assertTrue(costs.integer("otherNanos") >= 0)
        assertEquals("external-command-only", costs.text("buildCost"))
    }

    @Test fun `removal contract binds a formal worker and exact target before collection`() {
        val scenario = readObject(root.resolve("scenarios/maintenance-queue-short.json"))
        val contract = readObject(root.resolve("contracts/maintenance-queue-short.json"))
        val removal = contract.arrayAt("reclaimedUnits").single().jsonObject
        for ((field, value) in listOf("workerId" to s("missing"), "owner" to s("p2"),
            "reclaimActionId" to s("stop"), "unitId" to s("a"), "removalStepIndex" to n(0))) {
            val changed = JsonObject(contract + ("reclaimedUnits" to array(listOf(JsonObject(removal + (field to value))))))
            assertEquals(64, TraceComparator.evaluate(scenario, changed, emptyList()).exitCode, field)
        }
    }

    @Test fun `unfinished reclaim preserves next update queue and persistent construction nonce`() {
        val output = Files.createTempDirectory("maintenance-queue-short-").resolve("run")
        val result = Runner.run(RunRequest(root.resolve("scenarios/maintenance-queue-short.json"),
            root.resolve("contracts/maintenance-queue-short.json"), output, listOf("maintenance-queue-short")))
        assertEquals(0, result.exitCode, Files.readString(output.resolve("comparison/result.json")))

    }

    @Test fun `every maintenance sample requires tasks and reclaimed registration even away from checkpoints`() {
        val scenario = root.resolve("scenarios/maintenance-repair-short.json")
        val contract = root.resolve("contracts/maintenance-repair-short.json")
        val output = Files.createTempDirectory("maintenance-fields-negative-").resolve("run")
        assertEquals(0, Runner.run(RunRequest(scenario, contract, output, listOf("negative"))).exitCode)
        val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val missed = listOf("tasks", "task-target", "task-kind", "reclaimed", "worker-preparation", "player-tasks").mapNotNull { change ->
            val damaged = rows.map { row ->
                if (row["phase"] != s("after-advance") || row.objectAt("observation")["stepIndex"] != n(17)) row
                else {
                    val view = row.objectAt("observation")
                    fun units(values: JsonArray) = array(values.map {
                        val unit = it.jsonObject
                        if (unit.id("unitId") != "a") unit
                        else if (change == "tasks" || change == "player-tasks") JsonObject(unit - "tasks")
                        else JsonObject(unit + ("tasks" to array(unit.arrayAt("tasks").map { task ->
                            JsonObject(task.jsonObject - if (change == "task-target") "targetUnitId" else "kind")
                        })))
                    })
                    val next = when (change) {
                        "reclaimed" -> JsonObject(view - "reclaimedUnitIds")
                        "worker-preparation" -> {
                            val construction = view.objectAt("construction")
                            val workers = array(construction.arrayAt("workers").map { JsonObject(it.jsonObject - "preparation") })
                            JsonObject(view + ("construction" to JsonObject(construction + ("workers" to workers))))
                        }
                        "player-tasks" -> {
                            val player = view.objectAt("players").objectAt("p1")
                            JsonObject(view + ("players" to JsonObject(view.objectAt("players") +
                                ("p1" to JsonObject(player + ("units" to units(player.arrayAt("units"))))))))
                        }
                        else -> JsonObject(view + ("units" to units(view.arrayAt("units"))))
                    }
                    JsonObject(row + ("observation" to next))
                }
            }
            val result = TraceComparator.evaluate(readObject(scenario), readObject(contract), damaged)
            if (result.exitCode == 3) null else "$change: ${result.exitCode}"
        }
        assertEquals(emptyList(), missed)
    }

    @Test fun `two formal reclaim workers switch to free repair in the same world`() {
        val output = Files.createTempDirectory("maintenance-repair-short-").resolve("run")
        val result = Runner.run(RunRequest(root.resolve("scenarios/maintenance-repair-short.json"),
            root.resolve("contracts/maintenance-repair-short.json"), output, listOf("maintenance-repair-short")))
        assertEquals(0, result.exitCode, Files.readString(output.resolve("comparison/result.json")))
        val checkpoint = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
            .single { it["phase"] == s("after-advance") && it.objectAt("observation")["stepIndex"] == n(79) }.objectAt("observation")
        val injured = checkpoint.arrayAt("units").map { it.jsonObject }.single { it.id("unitId") == "f" }.number("health")
        assertTrue(injured > 0.0 && injured < 1200.0, "Repair must consume actual prior reclaim damage")
    }

    @Test fun `a declared constructed factory may produce only after its independently declared birth`() {
        val scenario = readObject(root.resolve("scenarios/build-economy-chain.json"))
        val contract = readObject(root.resolve("contracts/build-economy-chain.json"))
        val factory = contract.arrayAt("constructedUnits").map { it.jsonObject }.single { it.id("typeId") == "landFactory" }
        val nonce = "e".repeat(32)
        val action = obj("actionId" to s("factory-tank"), "atStep" to n(2801), "kind" to s("produce"),
            "actor" to s("p1"), "unitId" to factory.required("unitId"), "productionTypeId" to s("c_tank"), "unitNonce" to s(nonce))
        val product = obj("unitId" to s("produced:p1:$nonce"), "productionActionId" to s("factory-tank"),
            "producerId" to factory.required("unitId"), "owner" to s("p1"), "typeId" to s("c_tank"),
            "unitNonce" to s(nonce), "birthStepIndex" to n(3301))
        val input = JsonObject(scenario + ("actions" to array(scenario.arrayAt("actions") + action)))
        val expected = JsonObject(contract + ("producedUnits" to array(contract.arrayAt("producedUnits") + product)))
        assertEquals(2, ProductionDeclaration.products(input, expected).size)
        assertEquals(2, ConstructionDeclaration.buildings(input, expected).size)
        val tooEarly = JsonObject(input + ("actions" to array(scenario.arrayAt("actions") +
            JsonObject(action + ("atStep" to factory.required("birthStepIndex"))))))
        assertFailsWith<InputError> { ProductionDeclaration.products(tooEarly, expected) }
    }
}
