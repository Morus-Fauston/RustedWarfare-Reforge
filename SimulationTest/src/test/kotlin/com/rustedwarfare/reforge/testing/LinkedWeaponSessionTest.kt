package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class LinkedWeaponSessionTest {
    @Test fun actual_link_clear_still_waits_for_the_followers_ordinary_warmup() {
        val publicRoot = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val temporary = Files.createTempDirectory("linked-weapon-session-").toAbsolutePath().normalize()
        try {
            val expected = linkedMapOf(
                "linked-forward" to (listOf(4.0, 34.0, 64.0, 94.0) to 8973.0),
                "linked-reverse" to (listOf(4.0, 35.0, 65.0, 95.0) to 8974.0),
                "unlinked" to (listOf(4.0) to 8883.0),
                "linked-disabled" to (emptyList<Double>() to 0.0))
            for ((family, outcome) in expected) {
                val samples = mutableListOf<List<JsonElement?>>()
                for (format in listOf("json", "ini", "hjson")) {
                    val output = temporary.resolve("$family-$format")
                    PlatformSessionRunner.run(publicRoot.resolve("inputs/weapon-relations/$family/$format"),
                        publicRoot.resolve("platform/scenarios/weapon-relations-$family-$format-short.json"), output)
                    val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl"))
                        .map { json.parseToJsonElement(it).jsonObject }
                    val steps = rows.filter { it["kind"] == s("observation") && it["phase"] == s("step") }
                    assertEquals(120, steps.size)
                    samples += steps.map { it["sample"] }
                    fun bornTimes(name: String) = steps.flatMap { row ->
                        val sample = row.objectAt("sample")
                        sample.arrayAt("projectileEvents").map { it.jsonObject }.filter {
                            it["kind"] == s("born") && it["sourceUnitId"] == s("a") && it["sourceWeaponName"] == s(name)
                        }.map { sample.number("simulationTime") }
                    }
                    assertEquals(listOf(1.0, 31.0, 61.0, 91.0), bornTimes("leader"))
                    assertEquals(outcome.first, bornTimes("follower"),
                        "$family/$format: link, order and canShoot do not bypass ordinary warmup")
                    val last = steps.last().objectAt("sample")
                    val weapons = last.arrayAt("weapons").map { it.jsonObject }.filter { it["unitId"] == s("a") }
                        .associateBy { it.text("weaponName") }
                    assertEquals("c", weapons.getValue("leader").text("targetUnitId"))
                    assertEquals("b", weapons.getValue("follower").text("targetUnitId"))
                    assertEquals(outcome.second, weapons.getValue("follower").number("cooldown"), "$family/$format")
                    val b = last.arrayAt("units").map { it.jsonObject }.single { it["unitId"] == s("b") }
                    if (family == "linked-disabled") assertEquals(100000.0, b.number("health"))
                    else assertTrue(b.number("health") < 100000.0)
                }
                assertEquals(samples[0], samples[1], "$family INI gameplay must match JSON")
                assertEquals(samples[0], samples[2], "$family Hjson gameplay must match JSON")
            }
        } finally {
            require(temporary.isAbsolute && temporary.fileName.toString().startsWith("linked-weapon-session-"))
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
                require(it.toAbsolutePath().normalize().startsWith(temporary)); Files.delete(it)
            } }
        }
    }
}
