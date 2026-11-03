package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class MechSessionRunnerTest {
    @Test
    fun actual_three_format_sessions_keep_full_physical_samples_identical_and_publish_real_mech_shots() {
        val root = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val temporary = Files.createTempDirectory("mech-session-")
        val formats = mutableListOf<List<JsonElement?>>()
        for (format in listOf("ini", "json", "hjson")) {
            val output = temporary.resolve(format)
            PlatformSessionRunner.run(root.resolve("inputs/mech-production/$format"),
                root.resolve("platform/scenarios/mech-$format-session-short.json"), output)
            val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl"))
                .map { json.parseToJsonElement(it).jsonObject }
            val samples = rows.filter { it["kind"] == s("observation") && it["phase"] == s("step") }
            assertEquals(220, samples.size)
            formats += samples.map { it["sample"] }
            val walking = samples.take(20).map { row -> row.objectAt("sample").arrayAt("units")
                .single { it.jsonObject["unitId"] == s("a") }.jsonObject.objectAt("mechPose") }
            assertTrue(walking.all { it.arrayAt("legs").size == 2 })
            assertTrue(walking.any { pose -> pose.arrayAt("legs").any { it.jsonObject.number("height") > .1 } })
            val final = samples.last().objectAt("sample")
            assertTrue(final.arrayAt("units").single { it.jsonObject["unitId"] == s("b") }.jsonObject.number("health") < 500.0)
            val births = samples.flatMap { it.objectAt("sample").arrayAt("projectileEvents") }.map { it.jsonObject }
                .filter { it["kind"] == s("born") && it["sourceUnitId"] == s("a") }
            assertTrue(births.any { it["sourceWeaponName"] == s("mainBody") && it["targetUnitId"] == s("b") })
        }
        assertEquals(formats[0], formats[1], "Full INI/JSON Session samples, including pose, weapons, projectiles and ledger")
        assertEquals(formats[0], formats[2], "Full INI/HJSON Session samples")
    }
}
