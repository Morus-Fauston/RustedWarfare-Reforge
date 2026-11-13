package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class AmphibiousProductionSessionTest {
    @Test fun three_formats_complete_normal_paid_production_before_flight_dive_shore_stop_and_combat() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val output = TestArchives.directory("RWR_AMPHIBIOUS_ARCHIVE_ROOT", javaClass, "production-$format").resolve("run")
            val plan = public.resolve("platform/scenarios/amphibious-$format-production-chain.json")
            PlatformSessionRunner.run(public.resolve("inputs/amphibious-production/$format"), plan, output)
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
            assertEquals(readObject(plan).arrayAt("intents").map { it.jsonObject.text("intentId") }.toSet(), actions.keys,
                "$format each intention must produce an actual accepted action: $output")
            fun own(stage: String, type: String = "amphibiousJet") = stages.getValue(stage).arrayAt("units").map { it.jsonObject }
                .single { it["ownerPlayerId"] == s("p1") && it["typeId"] == s(type) }
            assertEquals(listOf("commandCenter"), stages.getValue("select-center").arrayAt("units").map { it.jsonObject }
                .filter { it["ownerPlayerId"] == s("p1") }.map { it.text("typeId") })
            assertEquals(1.0, own("finish-t1", "airFactory").number("constructionProgress"))
            assertEquals(own("finish-t1", "airFactory")["unitId"], own("finish-upgrade", "airFactoryT2")["unitId"])
            assertEquals(530.0, own("finish-jet").number("maxHealth"))
            assertTrue(own("fly-water").number("height") > 18)
            assertEquals(-8.0, own("dive").number("height"))
            assertEquals(s("WATER"), own("move-shore").objectAt("amphibiousJet")["movementType"])
            assertTrue(own("move-shore").objectAt("position").number("x") > 350, "Water route must stop at shore: $output")
            assertTrue(own("fly").number("height") > 18)
            assertTrue(own("cross-shore").objectAt("position").number("x") < 350, "New AIR move must cross shore: $output")
            assertEquals(JsonNull, own("stop-flight")["moveTarget"])
            assertEquals(listOf(false,true,false), listOf("dive","fly","dive-again").map {
                assertEquals(s("AmphibiousFlightAction"), actions.getValue(it)["type"])
                actions.getValue(it).required("flying").jsonPrimitive.boolean })
            assertEquals(-8.0, own("stop-final").number("height"))
            assertEquals(JsonNull, own("stop-final")["moveTarget"])
            val final = stages.getValue("stop-final")
            for ((id, health) in listOf("c" to 150.0, "d" to 1000.0))
                assertTrue(final.arrayAt("units").map { it.jsonObject }.single { it["unitId"] == s(id) }.number("health") < health,
                    "$format/$id must take actual weapon damage: $output")
        }
    }
}
