package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*

/** Called only for a separately validated archive freshly produced by the public scenario runner. */
fun main(args: Array<String>) {
    require(args.size == 5) { "Expected family, scenario, contract, current archive and new output" }
    val (family, scenario, contract, original, root) = args
    val seed = Path.of(scenario)
    val rules = Path.of(contract)
    val archive = Path.of(original).toRealPath()
    val output = Path.of(root).toAbsolutePath().normalize()
    require(!output.startsWith(archive) && !Files.exists(output)) { "Archive checks require a fresh output outside the input archive" }
    val baseline = TraceFiles.compare(seed, rules, archive)
    check(baseline.exitCode == 0) { baseline.document.toString() }
    Files.createDirectory(output)
    when (family) {
        "aa-beam" -> PublicArchiveAssertions.aa(seed, rules, archive, output)
        "transport" -> PublicArchiveAssertions.transport(seed, rules, archive, output)
        "air-transport" -> PublicArchiveAssertions.airTransport(seed, rules, archive, output)
        else -> error("Unknown public archive assertion family")
    }
    println("Passed current Core archive checks: $family")
}

/** Replays the existing behavior-level archive negatives against copies of a current Core run. */
internal object PublicArchiveAssertions {
    fun aa(scenario: Path, contract: Path, original: Path, root: Path) {
        val document = readObject(scenario)
        val rows = Files.readAllLines(original.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val early = rows.indexOfFirst { it["kind"] == s("observation") && it["phase"] == s("after-advance") &&
            it.objectAt("observation")["stepIndex"] == n(2) }
        val beam = rows.indexOfFirst { it["kind"] == s("observation") && it.objectAt("observation").arrayAt("projectiles").isNotEmpty() }
        check(early > 0 && beam > 0)
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
            check(result.exitCode == 3) { "$name: ${result.document}" }
            check(result.document.toString().contains("aa-beam")) { "$name: ${result.document}" }
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

    fun transport(scenario: Path, contract: Path, original: Path, root: Path, air: Boolean = false) {
        val document = readObject(scenario)
        val originalRows = Files.readAllLines(original.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        // An unasserted intermediate boundary must still satisfy the archive protocol.
        val index = originalRows.indexOfFirst { it["kind"] == s("observation") && it.objectAt("observation")["stepIndex"] == n(2) }
        check(index > 0)
        fun corrupt(name: String, id: String, change: (JsonObject) -> JsonObject) {
            val target = root.resolve(name)
            Files.walk(original).use { paths -> paths.forEach { from ->
                val to = target.resolve(original.relativize(from)).normalize()
                require(to.startsWith(root.toAbsolutePath().normalize()))
                if (Files.isDirectory(from)) Files.createDirectories(to) else Files.copy(from, to)
            } }
            val rows = originalRows.toMutableList()
            val view = rows[index].objectAt("observation")
            val units = view.arrayAt("units").map { if (it.jsonObject["unitId"] == s(id)) change(it.jsonObject) else it }
            rows[index] = JsonObject(rows[index] + ("observation" to JsonObject(view + ("units" to array(units)))))
            Files.writeString(target.resolve("reforge/raw.jsonl"), rows.joinToString("\n", postfix = "\n"))
            Files.writeString(target.resolve("reforge/normalized.jsonl"), rows.mapIndexed { i, row -> normalizeRecord(row, i + 1,
                if (i == 0) null else document.objectAt("coordinateModel").id("id"),
                if (i == 0) null else document.objectAt("timeModel").id("id")) }.joinToString("\n", postfix = "\n"))
            val manifest = readObject(target.resolve("manifest.json"))
            Files.writeString(target.resolve("manifest.json"), JsonObject(manifest + ("traceFiles" to array(manifest.arrayAt("traceFiles").map {
                val ref = it.jsonObject
                JsonObject(ref + ("sha256" to s(sha256(target.resolve(ref.text("path"))))))
            }))).toString())
            val result = TraceFiles.compare(scenario, contract, target)
            check(result.exitCode == 3) { "$name: ${result.document}" }
            check(result.document.toString().contains(if (name.startsWith("flight-")) "flight-pose" else "transport-")) { "$name: ${result.document}" }
        }
        fun state(name: String, change: (JsonObject) -> JsonObject) = corrupt(name, "craft") {
            JsonObject(it + ("transport" to change(it.objectAt("transport"))))
        }
        if (air) corrupt("flight-missing", "craft") { JsonObject(it - "flightPose") }
        corrupt("missing-state", "craft") { JsonObject(it - "transport") }
        state("unknown-field") { JsonObject(it + ("hostCounter" to n(0))) }
        state("missing-manifest") { JsonObject(it - "passengerIds") }
        state("duplicate-passenger") { JsonObject(it + ("passengerIds" to array(listOf(s("a-passenger"), s("a-passenger"))))) }
        state("false-count") { JsonObject(it + ("passengerCount" to n(0))) }
        state("over-capacity") { JsonObject(it + ("occupiedSlots" to n(5))) }
        state("wrong-capacity") { JsonObject(it + ("capacity" to n(5))) }
        state("bad-clock") { JsonObject(it + ("unloadRemaining" to n(31))) }
        state("string-intent") { JsonObject(it + ("unloading" to s("true"))) }
        corrupt("missing-parent", "a-passenger") { JsonObject(it - "carriedBy") }
        corrupt("orphan-passenger", "a-passenger") { JsonObject(it + ("carriedBy" to JsonNull)) }
        corrupt("wrong-parent", "a-passenger") { JsonObject(it + ("carriedBy" to s("missing"))) }
        corrupt("foreign-passenger", "a-passenger") { JsonObject(it + ("ownerPlayerId" to s("p2"))) }
        corrupt("invalid-exit", "a-passenger") { JsonObject(it + ("transportExit" to obj("previousCarrierId" to s("craft"), "remaining" to n(46)))) }
    }

    fun airTransport(scenario: Path, contract: Path, original: Path, root: Path) =
        transport(scenario, contract, original, root, air = true)
}
