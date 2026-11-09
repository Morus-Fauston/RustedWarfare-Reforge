package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class LandingCraftPlayerArchiveTest {
    @Test fun player_archives_hide_carried_units_and_enemy_manifests_but_preserve_owner_cargo() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val source = public.resolve("scenarios/transport-json-short.json")
        val before = readObject(source)
        val content = before.objectAt("content")
        fun absolute(ref: JsonObject) = JsonObject(ref + ("path" to s(source.parent.resolve(ref.text("path")).toAbsolutePath().normalize().toString())))
        val document = JsonObject(before + mapOf("content" to obj("map" to absolute(content.objectAt("map")),
            "unitVariants" to array(content.arrayAt("unitVariants").map { absolute(it.jsonObject) }),
            "dependencies" to array(content.arrayAt("dependencies").map { absolute(it.jsonObject) })),
            "automaticVision" to obj("retention" to s("MEMORY"), "pathSource" to s("PLAYER_KNOWLEDGE"),
                "shareAlliedVision" to b(false), "observers" to array(listOf(s("p1"), s("p2"))), "grid" to obj("width" to n(40), "height" to n(30))),
            "units" to array(before.arrayAt("units") + obj("unitId" to s("enemy-eye"), "owner" to s("p2"), "type" to s("builder"),
                "position" to obj("x" to n(330), "y" to n(180)), "heading" to n(90))),
            "combatContract" to obj("worldSeed" to n(12345), "initialFrameCounter" to n(-1),
                "unitObjectIds" to obj("a-passenger" to n(8), "craft" to n(9), "enemy-eye" to n(10)), "nextObjectId" to n(11))))
        val root = TestArchives.directory("RWR_TRANSPORT_ARCHIVE_ROOT", javaClass, "players")
        val scenario = root.resolve("scenario.json")
        Files.writeString(scenario, document.toString())
        val contract = public.resolve("contracts/transport-json-short.json")
        val output = root.resolve("run")
        val run = Runner.run(RunRequest(scenario, contract, output, listOf("api:Runner.run/LandingCraftPlayerArchiveTest")))
        assertEquals(0, run.exitCode, Files.readString(output.resolve("comparison/result.json")))
        val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val index = rows.indexOfFirst { it["kind"] == s("observation") && it.objectAt("observation")["stepIndex"] == n(2) }
        val host = rows[index].objectAt("observation")
        val players = host.objectAt("players")
        val carrier = host.arrayAt("units").map { it.jsonObject }.single { it["unitId"] == s("craft") }
        for (id in listOf("p1", "p2")) {
            val units = players.objectAt(id).arrayAt("units").map { it.jsonObject }
            assertTrue(units.none { it["unitId"] == s("a-passenger") })
            val visibleCarrier = units.single { it["unitId"] == s("craft") }
            assertEquals(if (id == "p1") carrier["transport"] else null, visibleCarrier["transport"])
        }
        fun corrupt(id: String, change: (JsonObject) -> JsonObject) {
            val changed = JsonObject(host + ("players" to JsonObject(players + (id to change(players.objectAt(id))))))
            val values = rows.toMutableList()
            values[index] = JsonObject(rows[index] + ("observation" to changed))
            val result = TraceComparator.evaluate(document, readObject(contract), values)
            assertEquals(3, result.exitCode, result.document.toString())
            assertTrue(result.document.toString().contains("transport-"), result.document.toString())
        }
        for (id in listOf("p1", "p2")) corrupt(id) { view ->
            val passenger = host.arrayAt("units").single { it.jsonObject["unitId"] == s("a-passenger") }
            JsonObject(view + mapOf("units" to array(view.arrayAt("units") + passenger),
                "visibleUnitIds" to array(view.arrayAt("visibleUnitIds") + s("a-passenger"))))
        }
        corrupt("p2") { view -> JsonObject(view + ("units" to array(view.arrayAt("units").map {
            if (it.jsonObject["unitId"] == s("craft")) JsonObject(it.jsonObject + ("transport" to carrier.required("transport"))) else it
        }))) }
        corrupt("p1") { view -> JsonObject(view + ("units" to array(view.arrayAt("units").map {
            if (it.jsonObject["unitId"] == s("craft")) JsonObject(it.jsonObject - "transport") else it
        }))) }
    }
}
