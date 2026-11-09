package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class SubmarineProductionSessionTest {
    @Test fun three_format_formal_sessions_pay_for_a_shipyard_and_submarine_before_depth_move_stop_and_combat() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val output = TestArchives.directory("RWR_SUBMARINE_ARCHIVE_ROOT", javaClass, "production-$format").resolve("run")
            val plan = public.resolve("platform/scenarios/submarine-$format-production-chain.json")
            PlatformSessionRunner.run(public.resolve("inputs/naval-production/$format"), plan, output)
            val stages = mutableMapOf<String, JsonObject>()
            val actions = mutableListOf<JsonObject>()
            val intentActions = mutableMapOf<String, JsonObject>()
            var activeIntent = ""
            val launchStyles = mutableSetOf<String>()
            var first: JsonObject? = null
            var sample: JsonObject? = null
            var count = 0
            Files.newBufferedReader(output.resolve("client-diagnostic.jsonl")).useLines { lines -> lines.forEach { line ->
                val row = json.parseToJsonElement(line).jsonObject
                when (row["kind"]) {
                    s("observation") -> {
                        val current = row.objectAt("sample"); sample = current
                        if (first == null) first = current
                        if (row["phase"] == s("step")) count++
                        for (event in current.arrayAt("projectileEvents").map { it.jsonObject }) {
                            if (event["kind"] == s("born") && event["appearance"] in setOf(s("SUBMARINE_MISSILE"), s("SUBMARINE_TORPEDO")))
                                launchStyles += event.text("appearance")
                        }
                    }
                    s("intent-start") -> activeIntent = row.text("intentId")
                    s("action") -> { actions += row; intentActions[activeIntent] = row }
                    s("intent-end") -> sample?.let { stages[row.text("intentId")] = it }
                    else -> Unit
                }
            } }
            assertEquals(12000L, assertNotNull(first).integer("balance"))
            assertEquals(listOf("builder"), first!!.arrayAt("units").map { it.jsonObject }.filter { it["ownerPlayerId"] == s("p1") }.map { it.text("typeId") })
            assertEquals(readObject(plan).arrayAt("intents").sumOf { it.jsonObject.integer("waitSteps") }.toInt(), count)
            fun own(stage: String, type: String = "attackSubmarine") = stages.getValue(stage).arrayAt("units").map { it.jsonObject }
                .single { it["ownerPlayerId"] == s("p1") && it["typeId"] == s(type) }
            assertEquals(11000L, stages.getValue("finish-t1").integer("balance"))
            assertEquals(1.0, own("finish-t1", "seaFactory").number("constructionProgress"))
            assertEquals(10200L, stages.getValue("leave-factory").integer("balance"))
            assertEquals(260.0, own("leave-factory").number("maxHealth"))
            assertEquals(-8.0, own("leave-factory").number("height"))
            assertTrue(own("move-underwater").objectAt("position").number("y") > 380.0)
            assertEquals(JsonNull, own("stop-underwater")["moveTarget"])
            assertEquals(1.0, own("surface").number("height"))
            assertEquals(false, own("surface").objectAt("submarine").required("underwater").jsonPrimitive.boolean)
            assertEquals(-8.0, own("dive").number("height"))
            assertEquals(true, own("dive").objectAt("submarine").required("underwater").jsonPrimitive.boolean)
            assertEquals(setOf("SUBMARINE_MISSILE", "SUBMARINE_TORPEDO"), launchStyles)
            for ((intent, target) in listOf("attack-air" to "c", "attack-underwater" to "d")) {
                val action = intentActions.getValue(intent)
                assertEquals(s("AttackAction"), action["type"], "$format/$intent must hit the visible body")
                assertEquals(s(target), action["targetUnitId"])
            }
            assertEquals(listOf(true, false), actions.filter { it["type"] == s("SubmarineDepthAction") }.map { it.required("surface").jsonPrimitive.boolean })
            for (type in listOf("BuildAction", "ProduceAction", "MoveAction", "StopAction", "AttackAction", "SubmarineDepthAction"))
                assertTrue(actions.any { it["type"] == s(type) && it.objectAt("result")["accepted"] == b(true) }, "$format/$type: $output")
            val final = assertNotNull(sample)
            assertEquals(10200L, final.integer("balance"))
            assertTrue(final.arrayAt("units").map { it.jsonObject }.single { it["typeId"] == s("attackSubmarine") }.required("alive").jsonPrimitive.boolean)
            for (id in listOf("c", "d")) assertTrue(final.arrayAt("units").map { it.jsonObject }.single { it["unitId"] == s(id) }.number("health") <
                (if (id == "c") 150.0 else 1000.0), "$format/$id must take normal weapon damage: $output")
        }
    }
}
