package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class AuthorNavalChainSessionTest {
    @Test fun complete_ordinary_three_format_paid_chains_stream_authorized_player_archives_from_initial_empty_weapons() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val root = System.getenv("RWR_AUTHOR_WATER_ARCHIVE_ROOT")?.let(Path::of)
            ?: Files.createTempDirectory("rwr-author-naval-session-")
        Files.createDirectories(root)
        for (format in listOf("ini", "json", "hjson")) {
            val output = root.resolve("naval-chain-session-$format")
            PlatformSessionRunner.run(public.resolve("inputs/author-naval-chain/$format"),
                public.resolve("platform/scenarios/author-naval-chain-$format.json"), output)
            var first: JsonObject? = null
            var current: JsonObject? = null
            var activeIntent = ""
            var steps = 0
            var closed = false
            var corvetteId: String? = null
            var frigateId: String? = null
            var normallyArrived = false
            var damagedBuilder = false
            val fired = mutableSetOf<String>()
            val stages = mutableMapOf<String, JsonObject>()
            val tail = ArrayDeque<JsonObject>()
            val actions = mutableSetOf<String>()
            Files.newBufferedReader(output.resolve("client-diagnostic.jsonl")).useLines { lines -> lines.forEach { line ->
                val row = json.parseToJsonElement(line).jsonObject
                when (row["kind"]) {
                    s("closed") -> closed = true
                    s("intent-start") -> activeIntent = row.text("intentId")
                    s("intent-end") -> current?.let { stages[row.text("intentId")] = it }
                    s("action") -> if (row.objectAt("result")["accepted"] == b(true)) actions += row.text("type")
                    s("observation") -> {
                        val sample = row.objectAt("sample")
                        current = sample
                        if (first == null) first = sample
                        val units = sample.arrayAt("units").map { it.jsonObject }
                        val ownedShips = units.filter { it["ownerPlayerId"] == s("p1") &&
                            it["typeId"] in setOf(s("spcorvette"), s("spfrigate")) }.map { it.id("unitId") }
                        val weapons = sample.arrayAt("weapons").map { it.jsonObject }
                        assertEquals(ownedShips.size * 2, weapons.size)
                        for (id in ownedShips)
                            assertEquals(setOf("port", "starboard"), weapons.filter { it["unitId"] == s(id) }
                                .map { it.text("weaponName") }.toSet())
                        units.firstOrNull { it["typeId"] == s("spcorvette") }?.let { corvetteId = it.id("unitId") }
                        val frigate = units.firstOrNull { it["typeId"] == s("spfrigate") }
                        frigate?.let { frigateId = it.id("unitId") }
                        if (row["phase"] == s("step")) {
                            steps++
                            if (frigate != null) {
                                tail.addLast(frigate.objectAt("position"))
                                if (tail.size > 30) tail.removeFirst()
                            }
                            if (activeIntent == "sail-frigate") normallyArrived = normallyArrived ||
                                sample.arrayAt("events").any { it.jsonObject["kind"] == s("movement-arrived") &&
                                    it.jsonObject["unitId"] == frigateId?.let(::s) }
                        }
                        for (event in sample.arrayAt("projectileEvents").map { it.jsonObject })
                            if (event["kind"] == s("born") && event["sourceUnitId"] == corvetteId?.let(::s) &&
                                event["targetUnitId"] == s("b")) fired += event.text("sourceWeaponName")
                        damagedBuilder = damagedBuilder || units.any { it["unitId"] == s("b") && it.number("health") < 170.0 }
                    }
                    else -> Unit
                }
            } }
            val initial = assertNotNull(first)
            assertTrue(initial.arrayAt("weapons").isEmpty())
            assertEquals(3000L, initial.integer("balance"))
            // The ordinary content-choice world uses its existing unrestricted player
            // projection. Preserve its explicit null knowledge, without enabling new rules.
            assertTrue(initial.containsKey("knowledge"))
            assertEquals(JsonNull, initial["knowledge"])
            assertEquals(3580, steps)
            assertTrue(closed)
            assertEquals(2300L, stages.getValue("build-dock").integer("balance"))
            assertEquals(1400L, stages.getValue("upgrade-t2").integer("balance"))
            assertEquals(1050L, stages.getValue("buy-corvette").integer("balance"))
            assertEquals(600L, stages.getValue("buy-frigate").integer("balance"))
            assertEquals(setOf("port", "starboard"), fired)
            assertTrue(damagedBuilder)
            assertTrue(normallyArrived, "$format normal completion must name the second ship during its actual move")
            assertEquals(30, tail.size)
            assertTrue(tail.all { it == tail.first() })
            val last = assertNotNull(current)
            assertEquals(600L, last.integer("balance"))
            val ship = last.arrayAt("units").map { it.jsonObject }.single { it["unitId"] == frigateId?.let(::s) }
            assertEquals(0.0, ship.number("speed"))
            assertEquals(JsonNull, ship["moveTarget"])
            assertTrue(ship.arrayAt("tasks").isEmpty())
            val weapons = last.arrayAt("weapons").map { it.jsonObject }
            assertEquals(4, weapons.size)
            for (id in listOf(assertNotNull(corvetteId), assertNotNull(frigateId)))
                assertEquals(setOf("port", "starboard"), weapons.filter { it["unitId"] == s(id) }.map { it.text("weaponName") }.toSet())
            assertTrue(actions.containsAll(setOf("BuildAction", "UpgradeAction", "ProduceAction", "MoveAction", "AttackAction")))
        }
    }
}
