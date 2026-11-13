package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.core.sim.Step
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class AmphibiousLightningArchiveTest {
    @Test fun submarine_and_jet_launches_coexist_in_strict_host_and_authorized_player_archives() {
        val (scenario, contract) = amphibiousLightningFixture(javaClass, "-mixed-submarine")
        val base = readObject(scenario)
        val folder = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public/inputs/naval-production/json/production-package/units/submarine")
        fun reference(id: String, file: Path, loader: String? = null): JsonObject = JsonObject(
            obj("id" to s(id), "path" to s(file.toAbsolutePath().normalize().toString()), "sha256" to s(sha256(file))) +
                (loader?.let { mapOf("loader" to s(it)) } ?: emptyMap()))
        val content = base.objectAt("content")
        val document = JsonObject(base + mapOf(
            "content" to JsonObject(content + mapOf(
                "unitVariants" to array(content.arrayAt("unitVariants") + reference("attackSubmarine", folder.resolve("submarine.json"), "submarine")),
                "dependencies" to array(content.arrayAt("dependencies") + listOf(
                    reference("mixed-sub-body", folder.resolve("body.png")), reference("mixed-sub-dead", folder.resolve("dead.png")))))),
            "units" to array(base.arrayAt("units") + listOf(
                obj("unitId" to s("sub"), "owner" to s("p1"), "type" to s("attackSubmarine"),
                    "position" to obj("x" to n(500), "y" to n(350)), "heading" to n(90), "height" to n(-8)),
                // Give p2 real sight of both endpoints; the fixed underwater target alone has no such sight.
                obj("unitId" to s("enemy-jet"), "owner" to s("p2"), "type" to s("amphibiousJet"),
                    "position" to obj("x" to n(600), "y" to n(350)), "heading" to n(270)))),
            "combatContract" to obj("worldSeed" to n(12345), "initialFrameCounter" to n(-1),
                "unitObjectIds" to obj("jet" to n(8), "target" to n(9), "sub" to n(10), "enemy-jet" to n(11)), "nextObjectId" to n(12)),
            "termination" to obj("kind" to s("explicit-steps"), "required" to b(true), "steps" to array(List(101) {
                obj("stepIndex" to n(it), "delta" to n(1), "deltaUnit" to s("sim-tick")) }))))
        Files.writeString(scenario, document.toString())
        val output = scenario.parent.resolve("mixed-launch-run")
        val run = Runner.run(RunRequest(scenario, contract, output, listOf("api:Runner.run/AmphibiousLightningArchiveTest/mixed")))
        val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val observations = rows.filter { it["kind"] == s("observation") }.map { it.objectAt("observation") }
        val expected = setOf(s("SUBMARINE_TORPEDO"), s("AMPHIBIOUS_LIGHTNING"))
        val births = observations.flatMap { it.arrayAt("projectileEvents") }.map { it.jsonObject }
            .filter { it["kind"] == s("born") }.map { it.objectAt("projectile")["appearance"] }.toSet()
        assertEquals(expected, births, "Both real supported units must fire before archive acceptance is tested")
        assertEquals(0, run.exitCode, run.toString())
        assertEquals(0, TraceFiles.compare(scenario, contract, output).exitCode)
        for (observer in listOf("p1", "p2")) {
            val seen = observations.flatMap { it.objectAt("players").objectAt(observer).arrayAt("projectileEvents") }
                .map { it.jsonObject["appearance"] }.toSet()
            assertTrue(seen.containsAll(expected), "$observer sees both authorized native appearances")
            observations.forEach { sample -> sample.objectAt("players").objectAt(observer).arrayAt("projectiles").forEach {
                assertFalse(it.jsonObject.containsKey("sourceUnitId"), "Anonymous visible projectiles must keep their source hidden")
            } }
        }
        val index = rows.indexOfFirst { it["kind"] == s("observation") && it.objectAt("observation").arrayAt("projectileEvents")
            .any { event -> event.jsonObject.objectAt("projectile")["appearance"] == s("AMPHIBIOUS_LIGHTNING") } }
        assertTrue(index > 0)
        fun forged(player: String?, submarine: Boolean) {
            fun change(shot: JsonObject): JsonObject {
                if (shot["appearance"] != s("AMPHIBIOUS_LIGHTNING")) return shot
                return if (submarine) JsonObject(shot + ("sourceUnitId" to s("sub")))
                else JsonObject(shot + ("appearance" to s("SUBMARINE_TORPEDO")) - "lightning")
            }
            val sample = rows[index].objectAt("observation")
            val changed = if (player == null) JsonObject(sample + ("projectileEvents" to array(sample.arrayAt("projectileEvents").map {
                val event = it.jsonObject
                JsonObject(event + ("projectile" to change(event.objectAt("projectile"))))
            }))) else {
                val players = sample.objectAt("players"); val view = players.objectAt(player)
                JsonObject(sample + ("players" to JsonObject(players + (player to JsonObject(view +
                    ("projectileEvents" to array(view.arrayAt("projectileEvents").map { change(it.jsonObject) })))))))
            }
            val bad = rows.toMutableList();bad[index] = JsonObject(rows[index] + ("observation" to changed))
            assertEquals(3, TraceComparator.evaluate(document, readObject(contract), bad).exitCode,
                "Forged source/appearance must be rejected in ${player ?: "host"}")
        }
        for (observer in listOf(null, "p1", "p2")) for (submarine in listOf(false, true)) forged(observer, submarine)
    }

    @Test fun independent_reader_rejects_missing_or_invalid_lightning_geometry_in_host_and_player_samples() {
        val (scenario, contract) = amphibiousLightningFixture(javaClass, "-strict")
        val document = readObject(scenario)
        Files.writeString(scenario, JsonObject(document + ("termination" to obj("kind" to s("explicit-steps"), "required" to b(true),
            "steps" to array(List(101) { obj("stepIndex" to n(it), "delta" to n(1), "deltaUnit" to s("sim-tick")) })))).toString())
        val output = scenario.parent.resolve("lightning-run")
        val run = Runner.run(RunRequest(scenario, contract, output, listOf("api:Runner.run/AmphibiousLightningArchiveTest")))
        assertEquals(0, run.exitCode, run.toString())
        assertEquals(0, TraceFiles.compare(scenario, contract, output).exitCode)
        val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val index = rows.indexOfFirst { it["kind"] == s("observation") && it.objectAt("observation").arrayAt("projectiles").isNotEmpty() }
        assertTrue(index > 0)
        val observation = rows[index].objectAt("observation")
        fun corrupt(change: (JsonObject) -> JsonObject) {
            val changed = rows.toMutableList()
            changed[index] = JsonObject(rows[index] + ("observation" to change(observation)))
            val result = TraceComparator.evaluate(readObject(scenario), readObject(contract), changed)
            assertEquals(3, result.exitCode, result.document.toString())
            assertTrue(result.document.toString().contains("amphibious-"), result.document.toString())
        }
        fun shots(field: String, change: (JsonObject) -> JsonObject) = corrupt { sample ->
            JsonObject(sample + (field to array(sample.arrayAt(field).map {
                val shot = it.jsonObject
                if (field == "projectileEvents") JsonObject(shot + ("projectile" to change(shot.objectAt("projectile")))) else change(shot)
            })))
        }
        shots("projectiles") { JsonObject(it - "lightning") }
        shots("projectileEvents") { JsonObject(it - "lightning") }
        shots("projectiles") { JsonObject(it + ("appearance" to s("STANDARD"))) }
        shots("projectiles") { JsonObject(it + ("lightning" to obj("end" to obj("x" to s("580"), "y" to n(300)), "endHeight" to n(-8)))) }
        shots("projectiles") { JsonObject(it + ("lightning" to obj("end" to obj("x" to n(580), "y" to n(300)), "endHeight" to n(1e100)))) }
        shots("projectiles") { JsonObject(it + ("lightning" to obj("end" to obj("x" to n(580), "y" to n(300)), "endHeight" to n(-8), "hostTargetId" to n(9)))) }
        corrupt { sample ->
            val players = sample.objectAt("players")
            val player = players.objectAt("p1")
            JsonObject(sample + ("players" to JsonObject(players + ("p1" to JsonObject(player +
                ("projectiles" to array(player.arrayAt("projectiles").map { JsonObject(it.jsonObject - "lightning") })))))))
        }
    }

    @Test fun host_archive_keeps_frozen_lightning_endpoints_and_authorized_player_views_keep_the_same_geometry() {
        val (scenario, contract) = amphibiousLightningFixture(javaClass)
        val input = ScenarioReader.load(scenario, contract)
        CoreAdapter(input).use { core ->
            core.initial()
            core.submit(input.actions.single())
            var found = false
            for (q in 0L..100L) {
                val row = core.advance(Step(q, 1.0, "sim-tick")).json()
                val shot = row.arrayAt("projectiles").map { it.jsonObject }.firstOrNull()
                if (shot != null) {
                    assertEquals(s("AMPHIBIOUS_LIGHTNING"), shot["appearance"])
                    assertEquals(obj("end" to obj("x" to n(580.0), "y" to n(300.0)), "endHeight" to n(-8.0)), shot["lightning"])
                    assertEquals(shot["lightning"], row.arrayAt("projectileEvents").first().jsonObject.objectAt("projectile")["lightning"])
                    val view = assertNotNull(core.playerViews()).objectAt("p1")
                    val visible = view.arrayAt("projectiles").single().jsonObject
                    assertEquals(shot["lightning"], visible["lightning"])
                    assertEquals(shot["lightning"], view.arrayAt("projectileEvents").first().jsonObject["lightning"])
                    found = true
                    break
                }
            }
            assertTrue(found, "Real submerged side weapon must fire")
        }
    }
}

internal fun amphibiousLightningFixture(owner: Class<*>, batch: String = ""): Pair<Path, Path> {
    val pair = amphibiousScenarioFixture(owner, "json", batch)
    val document = readObject(pair.first)
    val file = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public/inputs/amphibious-production/json/production-package/units/naval/underwaterTarget.json").toAbsolutePath().normalize()
    val content = document.objectAt("content")
    val target = obj("id" to s("navalUnderwaterTarget"), "path" to s(file.toString()), "sha256" to s(sha256(file)), "loader" to s("underwater-target"))
    Files.writeString(pair.first, JsonObject(document + mapOf(
        "content" to JsonObject(content + mapOf("unitVariants" to array(content.arrayAt("unitVariants") + target),
            "dependencies" to array(content.arrayAt("dependencies").filter { it.jsonObject.text("path") != file.toString() }))),
        "units" to array(document.arrayAt("units") + obj("unitId" to s("target"), "owner" to s("p2"), "type" to s("navalUnderwaterTarget"),
            "position" to obj("x" to n(580), "y" to n(300)), "heading" to n(0), "height" to n(-8))),
        "combatContract" to obj("worldSeed" to n(12345), "initialFrameCounter" to n(-1), "unitObjectIds" to obj("jet" to n(8), "target" to n(9)), "nextObjectId" to n(10)),
        "automaticVision" to obj("retention" to s("MEMORY"), "pathSource" to s("PLAYER_KNOWLEDGE"),
            "shareAlliedVision" to b(false), "observers" to array(listOf(s("p1"), s("p2"))),
            "grid" to obj("width" to n(40), "height" to n(30))))).toString())
    return pair
}
