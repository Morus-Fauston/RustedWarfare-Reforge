package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class NavalProductionSessionTest {
    @Test fun formal_sessions_persist_normal_three_format_builder_factory_ship_and_three_category_combat_from_the_first_record() {
        val repo = Path.of(System.getProperty("repo.root"))
        val public = repo.resolve("SimulationTest/public")
        val root = System.getenv("RWR_NAVAL_ARCHIVE_ROOT")?.let { Files.createDirectories(Path.of(it)).resolve("production-session") }
            ?: Files.createTempDirectory("naval-production-session-").resolve("archives")
        Files.createDirectory(root)
        val seal = Files.createDirectory(root.resolve("sealed-inputs"))
        val entries = mutableListOf<JsonObject>()
        for (format in listOf("ini", "json", "hjson")) {
            val inputs = public.resolve("inputs/naval-production/$format")
            Files.walk(inputs).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().forEach { source ->
                val destination = seal.resolve("$format").resolve(inputs.relativize(source))
                Files.createDirectories(destination.parent); Files.copy(source, destination)
                entries += obj("source" to s(source.toString()), "snapshot" to s(seal.relativize(destination).toString()), "sha256" to s(sha256(source)))
            } }
            val plan = public.resolve("platform/scenarios/naval-$format-production-chain.json")
            Files.copy(plan, seal.resolve("plan-$format.json"))
            entries += obj("source" to s(plan.toString()), "snapshot" to s("plan-$format.json"), "sha256" to s(sha256(plan)))
        }
        Files.writeString(seal.resolve("manifest.json"), array(entries).toString())
        for (format in listOf("ini", "json", "hjson")) {
            val output = root.resolve(format)
            PlatformSessionRunner.run(seal.resolve(format), seal.resolve("plan-$format.json"), output)
            var steps = 0
            var ship: String? = null
            var closed = false
            val fired = mutableSetOf<String>()
            Files.newBufferedReader(output.resolve("client-diagnostic.jsonl")).useLines { lines -> lines.forEach { line ->
                val row = json.parseToJsonElement(line).jsonObject
                if (row["kind"] == s("closed")) closed = true
                if (row["kind"] != s("observation")) return@forEach
                val sample = row.objectAt("sample")
                if (row["phase"] == s("step")) steps++
                sample.arrayAt("units").map { it.jsonObject }.firstOrNull { it["typeId"] == s("heavyBattleship") }?.let { ship = it.id("unitId") }
                sample.arrayAt("projectileEvents").map { it.jsonObject }.filter { it["kind"] == s("born") && it["sourceUnitId"] == ship?.let(::s) }
                    .forEach { fired += it.text("sourceWeaponName") }
            } }
            assertEquals(8200, steps)
            assertTrue(closed)
            assertEquals(setOf("1", "2", "3", "4", "5"), fired, "$format: $output")
        }
    }
}
