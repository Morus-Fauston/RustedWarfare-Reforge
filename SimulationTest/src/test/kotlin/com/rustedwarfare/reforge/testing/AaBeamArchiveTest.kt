package com.rustedwarfare.reforge.testing

import java.nio.file.Path
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class AaBeamArchiveTest {
    @Test fun the_public_aa_scenario_archives_energy_beams_and_fall_state_in_all_formats() {
        val public = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = public.resolve("scenarios/aa-beam-$format-short.json")
            val contract = public.resolve("contracts/aa-beam-$format-short.json")
            val root = TestArchives.directory("RWR_AA_BEAM_ARCHIVE_ROOT", javaClass, format)
            val result = Runner.run(RunRequest(scenario, contract, root.resolve("original"), listOf("api:Runner.run/AaBeamArchiveTest")))
            assertEquals(0, result.exitCode, result.toString())
            assertEquals(0, TraceFiles.compare(scenario, contract, root.resolve("original")).exitCode)
            if (format == "json") rejectCorruptedStates(scenario, contract, root)
        }
    }

    private fun rejectCorruptedStates(scenario: Path, contract: Path, root: Path) {
        val original = root.resolve("original")
        val document = readObject(scenario)
        val rows = Files.readAllLines(original.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val early = rows.indexOfFirst { it["kind"] == s("observation") && it["phase"] == s("after-advance") &&
            it.objectAt("observation")["stepIndex"] == n(2) }
        val beam = rows.indexOfFirst { it["kind"] == s("observation") && it.objectAt("observation").arrayAt("projectiles").isNotEmpty() }
        assertTrue(early > 0 && beam > 0)
        fun corrupt(name: String, index: Int, player: Boolean = false, change: (JsonObject) -> JsonObject) {
            val target = root.resolve(name)
            Files.walk(original).use { paths -> paths.forEach { from ->
                val to = target.resolve(original.relativize(from)).normalize()
                require(to.startsWith(root.toAbsolutePath().normalize()))
                if (Files.isDirectory(from)) Files.createDirectories(to) else Files.copy(from, to)
            } }
            val changed = rows.toMutableList()
            val observation = rows[index].objectAt("observation")
            val mutated = if (player) {
                val players = observation.objectAt("players")
                JsonObject(observation + ("players" to JsonObject(players + ("p1" to change(players.objectAt("p1"))))))
            } else change(observation)
            changed[index] = JsonObject(rows[index] + ("observation" to mutated))
            Files.writeString(target.resolve("reforge/raw.jsonl"), changed.joinToString("\n", postfix = "\n"))
            Files.writeString(target.resolve("reforge/normalized.jsonl"), changed.mapIndexed { i, row -> normalizeRecord(row, i + 1,
                if (i == 0) null else document.objectAt("coordinateModel").id("id"),
                if (i == 0) null else document.objectAt("timeModel").id("id")) }.joinToString("\n", postfix = "\n"))
            val manifest = readObject(target.resolve("manifest.json"))
            Files.writeString(target.resolve("manifest.json"), JsonObject(manifest + ("traceFiles" to array(manifest.arrayAt("traceFiles").map {
                val ref = it.jsonObject
                JsonObject(ref + ("sha256" to s(sha256(target.resolve(ref.text("path"))))))
            }))).toString())
            val result = TraceFiles.compare(scenario, contract, target)
            assertEquals(3, result.exitCode, "$name: ${result.document}")
            assertTrue(result.document.toString().contains("aa-beam"), "$name: ${result.document}")
        }
        for (player in listOf(false, true)) {
            fun unit(name: String, change: (JsonObject) -> JsonObject) = corrupt("$player-$name", early, player) { sample ->
                JsonObject(sample + ("units" to array(sample.arrayAt("units").map {
                    if (it.jsonObject["unitId"] == s("aa")) change(it.jsonObject) else it
                })))
            }
            unit("missing-energy") { JsonObject(it - "energy") }
            unit("over-energy") { JsonObject(it + ("energy" to JsonObject(it.objectAt("energy") + ("current" to n(7))))) }
            unit("string-lock") { JsonObject(it + ("energy" to JsonObject(it.objectAt("energy") + ("recharging" to s("false"))))) }
            unit("missing-fall") { JsonObject(it + ("flightPose" to JsonObject(it.objectAt("flightPose") - "fall"))) }
            corrupt("$player-missing-beam", beam, player) { sample ->
                JsonObject(sample + ("projectiles" to array(sample.arrayAt("projectiles").map { JsonObject(it.jsonObject - "beam") })))
            }
            corrupt("$player-invalid-scroll", beam, player) { sample ->
                JsonObject(sample + ("projectiles" to array(sample.arrayAt("projectiles").map {
                    JsonObject(it.jsonObject + ("beam" to JsonObject(it.jsonObject.objectAt("beam") + ("textureOffset" to n(-1)))))
                })))
            }
        }
    }
}
