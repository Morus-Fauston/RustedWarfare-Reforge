package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class HelicopterSessionRunnerTest {
    @Test fun actual_three_format_sessions_keep_complete_flight_samples_identical_and_fire_at_the_displayed_enemy() {
        val root = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val temporary = Files.createTempDirectory("helicopter-session-")
        val formats = mutableListOf<List<JsonElement?>>()
        for (format in listOf("ini", "json", "hjson")) {
            val output = temporary.resolve(format)
            PlatformSessionRunner.run(root.resolve("inputs/helicopter-production/$format"),
                root.resolve("platform/scenarios/helicopter-$format-session-short.json"), output)
            val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
            val samples = rows.filter { it["kind"] == s("observation") && it["phase"] == s("step") }
            assertEquals(320, samples.size)
            formats += samples.map { it["sample"] }
            val units = samples.map { it.objectAt("sample").arrayAt("units").single { it.jsonObject["unitId"] == s("a") }.jsonObject }
            assertTrue(units.all { it.number("height") == it.objectAt("flightPose").number("height") })
            assertTrue(units.any { it.objectAt("flightPose").number("height") >= 4.0 })
            assertTrue(units.drop(20).map { it.objectAt("flightPose")["rotorPhase"] }.distinct().size > 1)
            val final = samples.last().objectAt("sample")
            assertTrue(final.arrayAt("units").single { it.jsonObject["unitId"] == s("b") }.jsonObject.number("health") < 150.0)
            assertTrue(samples.flatMap { it.objectAt("sample").arrayAt("projectileEvents") }.any {
                val event = it.jsonObject
                event["kind"] == s("born") && event["sourceUnitId"] == s("a") && event["targetUnitId"] == s("b")
            })
        }
        assertEquals(formats[0], formats[1], "Complete INI/JSON Session samples, including flight, weapons and projectiles")
        assertEquals(formats[0], formats[2], "Complete INI/HJSON Session samples")
    }
}
