package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class AuthorNavalChainScenarioTest {
    @Test fun complete_three_format_paid_upgrade_dual_birth_and_normal_arrival_archives_match() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        val output = TestArchives.directory("RWR_AUTHOR_WATER_ARCHIVE_ROOT", javaClass, "rwr-author-naval-chain-")
        Files.createDirectories(output)
        var baseline: String? = null
        val secondId = "produced:p1:" + "c".repeat(32)
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = public.resolve("scenarios/author-naval-chain-$format.json")
            val contract = public.resolve("contracts/author-naval-chain-$format.json")
            val target = output.resolve("chain-$format")
            val result = Runner.run(RunRequest(scenario, contract, target, listOf("api:Runner.run/AuthorNavalChainScenarioTest")))
            assertEquals(0, result.exitCode, "$format $result")
            assertEquals(0, TraceFiles.compare(scenario, contract, target).exitCode)
            val rows = Files.readAllLines(target.resolve("reforge/raw.jsonl"))
                .map { json.parseToJsonElement(it).jsonObject }
            val normalized = rows.map(TraceComparator::normalize)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            normalized.forEach { digest.update(it.toString().toByteArray(Charsets.UTF_8)); digest.update(10.toByte()) }
            val identity = digest.digest().joinToString("") { "%02x".format(it) }
            if (baseline == null) baseline = identity else assertEquals(baseline, identity, "$format complete archive")
            val steps = normalized.filter { it["kind"] == s("observation") && it["phase"] == s("after-advance") }
            assertEquals(3200, steps.size)
            val hostSteps = steps.map { it.objectAt("observation") }
            assertTrue(hostSteps.first().objectAt("combat").arrayAt("weapons").isEmpty())
            val arrivals = hostSteps.drop(2400).flatMap { it.arrayAt("events") }.map { it.jsonObject }
                .filter { it.id("kind") == "movement-arrived" && it.id("unitId") == secondId }
            assertEquals(1, arrivals.size, "Normal completion must be an actual core event for the second ship")
            val last = steps.last().objectAt("observation")
            assertEquals(600.0, last.objectAt("economy").objectAt("balances").number("p1"))
            val final = last.arrayAt("units").single { it.jsonObject.id("unitId") == secondId }.jsonObject
            assertEquals(0.0, final.number("speed"))
            assertEquals(JsonNull, final["moveTarget"])
            val tail = steps.takeLast(30).map { step -> step.objectAt("observation").arrayAt("units")
                .single { it.jsonObject.id("unitId") == secondId }.jsonObject.objectAt("position") }
            assertTrue(tail.all { it == tail.first() }, "Actual stopped position must remain stable, without introducing a new distance tolerance")
            val weapons = hostSteps.last().objectAt("combat").arrayAt("weapons").map { it.jsonObject }
            assertEquals(setOf("produced:p1:" + "b".repeat(32), secondId), weapons.map { it.id("unitId") }.toSet())
            assertEquals(setOf("port", "starboard"), weapons.filter { it.id("unitId") == secondId }.map { it.id("weaponName") }.toSet())
            assertEquals(4, weapons.size)
        }
    }
}
