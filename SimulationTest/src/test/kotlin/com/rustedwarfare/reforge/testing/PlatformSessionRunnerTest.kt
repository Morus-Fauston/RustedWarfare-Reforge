package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

class PlatformSessionRunnerTest {
    private val publicRoot = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")

    @Test fun `shared turret upgrade and cancel intentions use the real branch and exact refund`() {
        val output = Files.createTempDirectory("platform-upgrade-").resolve("run")
        PlatformSessionRunner.run(publicRoot.resolve("inputs/platform-basics/upgrade"),
            publicRoot.resolve("platform/scenarios/upgrade.json"), output)
        val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        for ((type, delta) in listOf("UpgradeAction" to -1000.0, "CancelUpgradeAction" to 1000.0)) {
            val index = rows.indexOfFirst { it["type"] == s(type) }
            assertTrue(index > 0)
            assertEquals(b(true), rows[index].objectAt("result")["accepted"])
            assertEquals(s("upgrade_gun"), rows[index]["upgradeId"])
            assertEquals(delta, rows[index+1].objectAt("sample").number("balance") - rows[index-1].objectAt("sample").number("balance"))
        }
    }

    @Test fun `shared build intent creates a real new extractor without rewriting its identity`() {
        val output = Files.createTempDirectory("platform-building-").resolve("run")
        PlatformSessionRunner.run(publicRoot.resolve("inputs/platform-basics/construction"),
            publicRoot.resolve("platform/scenarios/construction.json"), output)
        val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val build = rows.single { it["type"] == s("BuildAction") }
        assertEquals(b(true), build.objectAt("result")["accepted"])
        val creations = rows.filter { it["kind"] == s("observation") }.flatMap { row ->
            row.objectAt("sample").arrayAt("events").map { it.jsonObject }.filter { it["kind"] == s("construction-created") }.map { row to it }
        }
        assertEquals(1, creations.size)
        val (row, event) = creations.single()
        val unit = row.objectAt("sample").arrayAt("units").single { it.jsonObject["unitId"] == event["unitId"] }.jsonObject
        assertEquals(s("extractorT1"), unit["typeId"])
        assertEquals(obj("x" to n(190.0), "y" to n(290.0)), unit["position"])
        assertEquals(0.0, unit.number("constructionProgress"))
        assertTrue(event.text("unitId").isNotBlank())
    }

    @Test fun `client movement stop and attack intentions use the same selected actor`() {
        val folder = Files.createTempDirectory("platform-command-")
        val plan = folder.resolve("plan.json")
        Files.writeString(plan, """{"schemaVersion":1,"intents":[
            {"intentId":"choose-tank","kind":"select","unitId":"a","position":{"x":110,"y":210}},
            {"intentId":"move-tank","kind":"move","unitId":"a","position":{"x":232,"y":208},"waitSteps":20},
            {"intentId":"stop-tank","kind":"stop","unitId":"a","waitSteps":20},
            {"intentId":"attack-enemy","kind":"attack","unitId":"a","targetUnitId":"b","position":{"x":430,"y":210}}
        ]}""")
        val output = folder.resolve("run")
        PlatformSessionRunner.run(publicRoot.resolve("inputs/production-combat/production"), plan, output)
        val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val actions = rows.filter { it["kind"] == s("action") }
        assertEquals(listOf("SelectAction", "MoveAction", "StopAction", "AttackAction"), actions.map { it.text("type") })
        assertTrue(actions.all { it["unitId"] == s("a") && it.objectAt("result")["accepted"] == b(true) })
        val stop = rows.indexOfFirst { it["type"] == s("StopAction") }
        fun tank(index: Int) = rows[index].objectAt("sample").arrayAt("units").single { it.jsonObject["unitId"] == s("a") }.jsonObject
        assertTrue(tank(stop-1).number("speed") > 0.0)
        val beforeAttack = rows.indexOfFirst { it["type"] == s("AttackAction") }-1
        assertEquals(0.0, tank(beforeAttack).number("speed"))
        assertEquals(JsonNull, tank(beforeAttack)["moveTarget"])
        assertEquals(s("b"), actions.last()["targetUnitId"])
    }

    @Test fun `shared production and cancel intentions preserve immediate payment and refund`() {
        val folder = Files.createTempDirectory("platform-production-")
        val plan = folder.resolve("plan.json")
        Files.writeString(plan, """{"schemaVersion":1,"intents":[
            {"intentId":"choose-factory","kind":"select","unitId":"f","position":{"x":310,"y":210}},
            {"intentId":"make-tank","kind":"produce","unitId":"f","typeId":"c_tank"},
            {"intentId":"cancel-tank","kind":"cancelProduction","unitId":"f","typeId":"c_tank"}
        ]}""")
        val output = folder.resolve("run")
        PlatformSessionRunner.run(publicRoot.resolve("inputs/production-combat/production"), plan, output)
        val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val actions = rows.filter { it["kind"] == s("action") }
        assertEquals(listOf("SelectAction", "ProduceAction", "CancelProductionAction"), actions.map { it.text("type") })
        for ((type, delta) in listOf("ProduceAction" to -350, "CancelProductionAction" to 350)) {
            val index = rows.indexOfFirst { it["kind"] == s("action") && it["type"] == s(type) }
            assertEquals(b(true), rows[index].objectAt("result")["accepted"])
            val before = rows[index-1].objectAt("sample").number("balance")
            val after = rows[index+1].objectAt("sample").number("balance")
            assertEquals(delta.toDouble(), after-before)
        }
    }

    @Test fun `shared selection intent records a fresh real Session action and observation`() {
        val folder = Files.createTempDirectory("platform-session-")
        val plan = folder.resolve("plan.json")
        Files.writeString(plan, """{"schemaVersion":1,"intents":[
            {"intentId":"choose-factory","kind":"select","unitId":"f","position":{"x":310,"y":210}}
        ]}""")
        val output = folder.resolve("run")
        PlatformSessionRunner.run(publicRoot.resolve("inputs/production-combat/production"), plan, output)
        val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val action = rows.single { it["kind"] == s("action") }
        assertEquals(s("SelectAction"), action["type"])
        assertEquals(s("f"), action["unitId"])
        assertEquals(b(true), action.objectAt("result")["accepted"])
        val before = rows.last { it.integer("sequence") < action.integer("sequence") && it["phase"] == s("before-action") }
        val after = rows.first { it.integer("sequence") > action.integer("sequence") && it["phase"] == s("after-action") }
        assertEquals(before.objectAt("sample")["worldId"], after.objectAt("sample")["worldId"])
        assertEquals(s("closed"), rows.last()["kind"])
    }
}
