package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class MountedWeaponSessionTest {
    @Test fun real_mounted_content_keeps_independent_and_slave_targets_distinct_in_the_shared_runner() {
        val publicRoot = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val temporary = Files.createTempDirectory("mounted-weapon-session-").toAbsolutePath().normalize()
        try {
            val samples = mutableListOf<List<JsonElement?>>()
            for (format in listOf("json", "ini", "hjson")) {
                val out = temporary.resolve(format)
                PlatformSessionRunner.run(publicRoot.resolve("inputs/weapon-relations/mounted/$format"),
                    publicRoot.resolve("platform/scenarios/weapon-relations-mounted-$format-short.json"), out)
                val rows = Files.readAllLines(out.resolve("client-diagnostic.jsonl"))
                    .map { json.parseToJsonElement(it).jsonObject }
                val steps = rows.filter { it["kind"] == s("observation") && it["phase"] == s("step") }
                assertEquals(120, steps.size)
                samples += steps.map { it["sample"] }
                val sample = steps.last().objectAt("sample")
                val guns = sample.arrayAt("weapons").map { it.jsonObject }.filter { it["unitId"] == s("a") }
                    .associateBy { it.text("weaponName") }
                assertEquals(mapOf("base" to "c", "independent" to "b", "slave" to "c"),
                    guns.mapValues { it.value.text("targetUnitId") })
                assertEquals(guns.getValue("base")["turretHeading"], guns.getValue("slave")["turretHeading"])
                assertNotEquals(guns.getValue("base")["turretHeading"], guns.getValue("independent")["turretHeading"])
                assertEquals(3, guns.values.map { it["muzzle"] }.toSet().size)
                val births = steps.flatMap { it.objectAt("sample").arrayAt("projectileEvents").map { e -> e.jsonObject } }
                    .filter { it["kind"] == s("born") && it["sourceUnitId"] == s("a") }
                assertEquals(mapOf("independent" to "b", "slave" to "c"), births.associate {
                    it.text("sourceWeaponName") to it.text("targetUnitId") })
                for (id in listOf("b", "c")) assertTrue(sample.arrayAt("units").map { it.jsonObject }
                    .single { it["unitId"] == s(id) }.number("health") < 100000.0)
                val stopped = rows.last { it["kind"] == s("observation") && it["phase"] == s("after-action") }
                    .objectAt("sample").arrayAt("weapons").map { it.jsonObject }.filter { it["unitId"] == s("a") }
                assertEquals(3, stopped.size)
                assertTrue(stopped.all { it["targetUnitId"] == JsonNull })
            }
            assertEquals(samples[0], samples[1], "JSON and INI mounted gameplay must be equivalent")
            assertEquals(samples[0], samples[2], "JSON and Hjson mounted gameplay must be equivalent")
        } finally {
            require(temporary.isAbsolute && temporary.fileName.toString().startsWith("mounted-weapon-session-"))
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
                require(it.toAbsolutePath().normalize().startsWith(temporary)); Files.delete(it)
            } }
        }
    }
}
