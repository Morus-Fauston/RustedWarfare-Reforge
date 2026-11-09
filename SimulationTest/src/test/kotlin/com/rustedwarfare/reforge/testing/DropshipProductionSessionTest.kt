package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class DropshipProductionSessionTest {
    @Test fun three_formats_pay_for_worker_factory_and_craft_then_transport_and_release_the_worker() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val plan = public.resolve("platform/scenarios/air-transport-$format-production-chain.json")
            val output = TestArchives.directory("RWR_AIR_TRANSPORT_ARCHIVE_ROOT", javaClass, format).resolve("run")
            PlatformSessionRunner.run(public.resolve("inputs/air-transport-production/$format"), plan, output)
            val stages = mutableMapOf<String, JsonObject>()
            val actions = mutableMapOf<String, JsonObject>()
            var active = ""
            var sample: JsonObject? = null
            Files.newBufferedReader(output.resolve("client-diagnostic.jsonl")).useLines { lines -> lines.forEach { line ->
                val row = json.parseToJsonElement(line).jsonObject
                when (row["kind"]) {
                    s("observation") -> sample = row.objectAt("sample")
                    s("intent-start") -> active = row.text("intentId")
                    s("action") -> { assertEquals(b(true), row.objectAt("result")["accepted"], "$format/$active $output"); actions[active] = row }
                    s("intent-end") -> stages[row.text("intentId")] = assertNotNull(sample)
                    else -> Unit
                }
            } }
            assertEquals(readObject(plan).arrayAt("intents").map { it.jsonObject.text("intentId") }.toSet(), actions.keys)
            fun unit(stage: String, type: String) = stages.getValue(stage).arrayAt("units").map { it.jsonObject }
                .single { it["ownerPlayerId"] == s("p1") && it["typeId"] == s(type) }
            assertEquals(listOf("commandCenter"), stages.getValue("select-center").arrayAt("units").map { it.jsonObject }
                .filter { it["ownerPlayerId"] == s("p1") }.map { it.text("typeId") })
            assertEquals(1.0, unit("finish-t1", "airFactory").number("constructionProgress"))
            assertEquals(500.0, unit("finish-air", "dropship").number("maxHealth"))
            assertEquals(n(1), unit("board", "dropship").objectAt("transport")["passengerCount"])
            assertTrue(stages.getValue("board").arrayAt("units").none { it.jsonObject["typeId"] == s("builder") })
            assertTrue(unit("cross-water", "dropship").objectAt("position").number("x") > 400)
            assertTrue(unit("return-land", "dropship").objectAt("position").number("x") < 350)
            assertEquals(JsonNull, unit("stop-land", "dropship")["moveTarget"])
            assertEquals(n(1), unit("cancel-unload", "dropship").objectAt("transport")["passengerCount"])
            assertEquals(b(false), unit("cancel-unload", "dropship").objectAt("transport")["unloading"])
            assertEquals(n(0), unit("resume-unload", "dropship").objectAt("transport")["passengerCount"])
            assertEquals(170.0, unit("resume-unload", "builder").number("health"))
            assertEquals(unit("resume-unload", "builder")["unitId"], actions.getValue("move-released")["unitId"])
            val finalWorker = unit("stop-released", "builder")
            val departure = unit("resume-unload", "builder").objectAt("position")
            assertTrue(finalWorker.objectAt("position").number("x") < departure.number("x"))
            assertTrue(finalWorker.objectAt("position").number("y") > departure.number("y"))
            assertEquals(s("MoveAction"), actions.getValue("move-released")["type"])
            assertEquals(s("StopAction"), actions.getValue("stop-released")["type"])
            assertEquals(JsonNull, finalWorker["moveTarget"])
            assertEquals(s("LoadAction"), actions.getValue("board")["type"])
            assertEquals(s("UP"), actions.getValue("board")["direction"])
            assertEquals(listOf(b(true), b(false), b(true)), listOf("start-unload", "cancel-unload", "resume-unload").map { actions.getValue(it)["unloading"] })
        }
    }
}
