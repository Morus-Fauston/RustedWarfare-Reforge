package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class HeavyMechSessionRunnerTest {
    @Test
    fun three_format_actual_sessions_publish_identical_four_leg_samples_and_real_small_laser_shots() {
        val root = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val temporary = Files.createTempDirectory("heavy-session-")
        val formats = mutableListOf<List<JsonElement?>>()
        for (format in listOf("ini", "json", "hjson")) {
            val output = temporary.resolve(format)
            PlatformSessionRunner.run(root.resolve("inputs/heavy-mech-production/$format"),
                root.resolve("platform/scenarios/heavy-mech-$format-session-short.json"), output)
            val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
            val samples = rows.filter { it["kind"] == s("observation") && it["phase"] == s("step") }
            assertEquals(220, samples.size)
            formats += samples.map { it["sample"] }
            val poses = samples.map { it.objectAt("sample").arrayAt("units")
                .single { it.jsonObject["unitId"] == s("a") }.jsonObject.objectAt("mechPose") }
            assertTrue(poses.all { it.arrayAt("legs").size == 4 })
            assertTrue(poses.take(20).any { pose -> pose.arrayAt("legs").any { it.jsonObject.number("height") > .1 } })
            val final = samples.last().objectAt("sample")
            assertTrue(final.arrayAt("units").single { it.jsonObject["unitId"] == s("b") }.jsonObject.number("health") < 2500.0)
            val births = samples.flatMap { it.objectAt("sample").arrayAt("projectileEvents") }.map { it.jsonObject }
                .filter { it["kind"] == s("born") && it["sourceUnitId"] == s("a") }
            assertTrue(births.any { it["sourceWeaponName"] == s("smallLaser") && it["targetUnitId"] == s("b") })
        }
        assertEquals(formats[0], formats[1], "All physical Session fields, not only the four-leg projection")
        assertEquals(formats[0], formats[2])
    }
}
