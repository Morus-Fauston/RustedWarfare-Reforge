package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class HeavyMechProductionSessionTest {
    @Test fun three_format_formal_sessions_build_upgrade_and_produce_before_real_ground_and_air_combat() {
        val root = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val output = Files.createTempDirectory("heavy-production-session-$format-").resolve("run")
            val plan = root.resolve("platform/scenarios/heavy-mech-$format-production-chain.json")
            val steps = readObject(plan).arrayAt("intents").sumOf { it.jsonObject["waitSteps"]?.jsonPrimitive?.long ?: 0L }
            PlatformSessionRunner.run(root.resolve("inputs/heavy-mech-production/$format"), plan, output)
            var sample: JsonObject? = null
            var first: JsonObject? = null
            var count = 0L
            var factoryId: String? = null
            var heavyId: String? = null
            val fired = mutableSetOf<String>()
            val actions = mutableListOf<JsonObject>()
            val stages = mutableMapOf<String, JsonObject>()
            Files.newBufferedReader(output.resolve("client-diagnostic.jsonl")).useLines { lines -> lines.forEach { line ->
                val row = json.parseToJsonElement(line).jsonObject
                when (row["kind"]) {
                    s("observation") -> {
                        val current = row.objectAt("sample")
                        sample = current
                        if (first == null) first = current
                        if (row["phase"] == s("step")) count++
                        val units = current.arrayAt("units").map { it.jsonObject }
                        units.firstOrNull { it["typeId"] == s("mechFactory") && it["ownerPlayerId"] == s("p1") }?.let { unit ->
                            if (factoryId == null) factoryId = unit.id("unitId") else assertEquals(factoryId, unit.id("unitId"))
                        }
                        units.firstOrNull { it["typeId"] == s("mechHeavyMissile") && it["ownerPlayerId"] == s("p1") }?.let { unit ->
                            heavyId = unit.id("unitId")
                            assertEquals(4, unit.objectAt("mechPose").arrayAt("legs").size)
                            assertEquals(setOf("smallLaser", "1", "2", "3"), current.arrayAt("weapons").map { it.jsonObject }
                                .filter { it["unitId"] == s(heavyId!!) }.map { it.text("weaponName") }.toSet())
                        }
                        for (event in current.arrayAt("projectileEvents").map { it.jsonObject }) {
                            if (event["kind"] != s("born") || event["sourceUnitId"] != heavyId?.let(::s)) continue
                            if (event["sourceWeaponName"] == s("smallLaser") && event["targetUnitId"] == s("b")) fired += "ground"
                            if (event["sourceWeaponName"] in setOf(s("2"), s("3")) && event["targetUnitId"] == s("c")) fired += "air"
                        }
                    }
                    s("action") -> actions += row
                    s("intent-end") -> sample?.let { stages[row.text("intentId")] = it }
                    else -> Unit
                }
            } }
            val initial = assertNotNull(first)
            assertEquals(16000L, initial.integer("balance"))
            assertEquals(listOf("builder"), initial.arrayAt("units").map { it.jsonObject }.filter { it["ownerPlayerId"] == s("p1") }.map { it.id("typeId") })
            assertEquals(steps, count)
            fun own(stage: String, type: String) = stages.getValue(stage).arrayAt("units").map { it.jsonObject }
                .single { it["ownerPlayerId"] == s("p1") && it["typeId"] == s(type) }
            assertEquals(15000L, stages.getValue("finish-t1").integer("balance"))
            assertEquals(1.0, own("finish-t1", "mechFactory").number("constructionProgress"))
            assertEquals(11000L, stages.getValue("finish-upgrade").integer("balance"))
            assertEquals(factoryId, own("finish-upgrade", "mechFactoryT2").id("unitId"))
            assertEquals(3100.0, own("finish-upgrade", "mechFactoryT2").number("maxHealth"))
            assertEquals(2500L, stages.getValue("leave-factory").integer("balance"))
            assertTrue(own("leave-factory", "mechHeavyMissile").objectAt("position").number("y") > 240.0)
            assertEquals(JsonNull, own("stop-heavy", "mechHeavyMissile")["moveTarget"])
            for (type in listOf("BuildAction", "UpgradeAction", "ProduceAction", "MoveAction", "StopAction", "AttackAction"))
                assertTrue(actions.any { it["type"] == s(type) && it.objectAt("result")["accepted"] == b(true) }, "$format missing $type: $output")
            assertEquals(setOf("ground", "air"), fired)
            val final = assertNotNull(sample)
            val units = final.arrayAt("units").map { it.jsonObject }
            assertTrue(units.single { it["unitId"] == heavyId?.let(::s) }.required("alive").jsonPrimitive.boolean)
            for (id in listOf("b", "c")) assertFalse(units.single { it["unitId"] == s(id) }.required("alive").jsonPrimitive.boolean, "$format/$id: $output")
            assertEquals(2500L, final.integer("balance"))
        }
    }
}
