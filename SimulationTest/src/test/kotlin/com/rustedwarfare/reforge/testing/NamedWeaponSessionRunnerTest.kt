package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NamedWeaponSessionRunnerTest {
    @Test
    fun `three real package formats use one Session Runner and produce equivalent dual-target gameplay`() {
        val publicRoot = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val temporary = Files.createTempDirectory("platform-named-weapons-").toAbsolutePath().normalize()
        try {
            val samples = mutableListOf<List<JsonElement?>>()
            for (format in listOf("ini", "json", "hjson")) {
                val input = publicRoot.resolve("inputs/named-weapons/$format")
                val plan = publicRoot.resolve("platform/scenarios/named-weapons-$format-short.json")
                val output = temporary.resolve(format)
                PlatformSessionRunner.run(input, plan, output)
                val rows = Files.readAllLines(output.resolve("client-diagnostic.jsonl")).map { json.parseToJsonElement(it).jsonObject }
                assertTrue(rows.any { it["kind"] == s("content-pack-ready") })
                val steps = rows.filter { it["kind"] == s("observation") && it["phase"] == s("step") }
                assertEquals(120, steps.size, format)
                samples += steps.map { it["sample"] }
                val last = steps.last().objectAt("sample")
                val weapons = last.arrayAt("weapons").map { it.jsonObject }.filter { it["unitId"] == s("a") }
                assertEquals(mapOf("near" to "b", "far" to "c"),
                    weapons.associate { it.text("weaponName") to it.text("targetUnitId") }, format)
                for (id in listOf("b", "c")) {
                    val unit = last.arrayAt("units").map { it.jsonObject }.single { it["unitId"] == s(id) }
                    assertTrue(unit.number("health") < 100000.0, "$format $id must receive actual damage")
                }
                val born = steps.flatMap { it.objectAt("sample").arrayAt("projectileEvents").map { value -> value.jsonObject } }
                    .filter { it["kind"] == s("born") && it["sourceUnitId"] == s("a") }
                assertEquals(setOf("near", "far"), born.map { it.text("sourceWeaponName") }.toSet())
                val stopped = rows.last { it["kind"] == s("observation") && it["phase"] == s("after-action") }.objectAt("sample")
                assertTrue(stopped.arrayAt("weapons").map { it.jsonObject }.filter { it["unitId"] == s("a") }
                    .all { it["targetUnitId"] == JsonNull })
            }
            assertEquals(samples[0], samples[1], "INI and JSON gameplay must match through the existing Runner")
            assertEquals(samples[0], samples[2], "INI and Hjson gameplay must match through the existing Runner")
        } finally {
            require(temporary.isAbsolute && temporary.fileName.toString().startsWith("platform-named-weapons-"))
            temporary.toFile().deleteRecursively()
        }
    }
}
