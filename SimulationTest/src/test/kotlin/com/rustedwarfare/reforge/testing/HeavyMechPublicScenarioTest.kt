package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class HeavyMechPublicScenarioTest {
    private val root = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")

    @Test
    fun full_production_chain_contract_accepts_only_the_fixed_factory_conversion_before_reading_an_archive() {
        for (format in listOf("ini", "json", "hjson")) {
            val source = root.resolve("scenarios/heavy-mech-production-$format-chain.json")
            val contract = root.resolve("contracts/heavy-mech-production-$format-chain.json")
            val temporary = Files.createTempDirectory("heavy-long-contract-$format-")
            val missing = temporary.resolve("missing-archive")
            val acceptedInput = TraceFiles.compare(source, contract, missing)
            assertEquals(3, acceptedInput.exitCode, acceptedInput.document.toString())
            val original = readObject(contract)
            val conversion = original.arrayAt("convertedUnits").single().jsonObject
            val wrong = JsonObject(original + ("convertedUnits" to JsonArray(listOf(
                JsonObject(conversion + ("targetTypeId" to s("mechGun")))))))
            val bad = temporary.resolve("wrong-target.json")
            Files.writeString(bad, wrong.toString())
            assertEquals(64, TraceFiles.compare(source, bad, missing).exitCode,
                "An existing but unrelated unit cannot become the fixed T2 conversion")
        }
    }

    @Test
    fun all_five_images_including_the_independent_small_turret_require_declared_identities() {
        for (format in listOf("ini", "json", "hjson")) {
            val source = root.resolve("scenarios/heavy-mech-$format-short.json")
            val original = readObject(source)
            val content = original.objectAt("content")
            fun absolute(ref: JsonObject) = JsonObject(ref + ("path" to s(source.parent.resolve(ref.text("path")).normalize().toString())))
            val dependencies = content.arrayAt("dependencies").map { absolute(it.jsonObject) }
            val temporary = Files.createTempDirectory("heavy-input-identity-")
            for (name in listOf("body.png", "turret.png", "small_turret.png", "leg.png", "foot.png")) {
                val scenario = temporary.resolve("$name.json")
                Files.writeString(scenario, JsonObject(original + ("content" to obj(
                    "map" to absolute(content.objectAt("map")),
                    "unitVariants" to array(content.arrayAt("unitVariants").map { absolute(it.jsonObject) }),
                    "dependencies" to array(dependencies.filter { !it.text("path").replace('\\', '/').endsWith("/$name") })
                ))).toString())
                val output = temporary.resolve("$name-run")
                assertEquals(64, Runner.run(RunRequest(scenario, root.resolve("contracts/heavy-mech-$format-short.json"), output,
                    listOf("missing-heavy-$name"))).exitCode)
                assertTrue(readObject(output.resolve("comparison/result.json")).text("reason").contains("no declared identity"))
            }
        }
    }

    @Test
    fun three_format_real_loaders_archive_four_legs_and_keep_hidden_players_empty() {
        val formats = mutableListOf<List<JsonElement?>>()
        for (format in listOf("ini", "json", "hjson")) {
            val source = root.resolve("scenarios/heavy-mech-$format-short.json")
            val contract = root.resolve("contracts/heavy-mech-$format-short.json")
            val output = Files.createTempDirectory("heavy-public-$format-").resolve("run")
            assertEquals(0, Runner.run(RunRequest(source, contract, output, listOf("heavy-public"))).exitCode,
                Files.readString(output.resolve("comparison/result.json")))
            assertEquals(0, TraceFiles.compare(source, contract, output, output).exitCode)
            val samples = Files.readAllLines(output.resolve("reforge/raw.jsonl"))
                .map { json.parseToJsonElement(it).jsonObject }.filter { it["kind"] == s("observation") }
                .map { it.objectAt("observation") }
            assertEquals(13, samples.size)
            val poses = samples.map { it.arrayAt("units").single().jsonObject.objectAt("mechPose") }
            assertTrue(poses.all { it.arrayAt("legs").map { leg -> leg.jsonObject.integer("index") } == listOf(0L, 1L, 2L, 3L) })
            assertTrue(poses.drop(1).any { it.arrayAt("legs") != poses.first().arrayAt("legs") })
            for (sample in samples) {
                val players = sample.objectAt("players")
                assertEquals(sample.arrayAt("units").single().jsonObject["mechPose"],
                    players.objectAt("p1").arrayAt("units").single().jsonObject["mechPose"])
                assertTrue(players.objectAt("p2").arrayAt("units").isEmpty())
            }
            formats += poses
        }
        assertEquals(formats[0], formats[1])
        assertEquals(formats[0], formats[2])
    }

    @Test
    fun malformed_intermediate_host_and_player_four_leg_samples_invalidate_the_archive() {
        val source = root.resolve("scenarios/heavy-mech-ini-short.json")
        val contractPath = root.resolve("contracts/heavy-mech-ini-short.json")
        val output = Files.createTempDirectory("heavy-integrity-").resolve("run")
        assertEquals(0, Runner.run(RunRequest(source, contractPath, output, listOf("heavy-integrity"))).exitCode)
        val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val variants = listOf("missing-pose", "null-pose", "two-legs", "extra-leg", "duplicate-index", "private-target",
            "missing-foot", "wrong-phase", "string-height", "nonfinite-height", "extra-vector-field", "missing-body-spin", "string-reverse")
        fun damage(unit: JsonObject, variant: String): JsonObject {
            if (variant == "missing-pose") return JsonObject(unit - "mechPose")
            if (variant == "null-pose") return JsonObject(unit + ("mechPose" to JsonNull))
            val pose = unit.objectAt("mechPose")
            val legs = pose.arrayAt("legs")
            val first = legs.first().jsonObject
            val changed = when (variant) {
                "two-legs" -> JsonObject(pose + ("legs" to array(legs.take(2))))
                "extra-leg" -> JsonObject(pose + ("legs" to array(legs + legs.last())))
                "duplicate-index" -> JsonObject(pose + ("legs" to array(listOf(legs.first(), legs.first()) + legs.drop(2))))
                "private-target" -> JsonObject(pose + ("predictedFootTarget" to obj("x" to n(1), "y" to n(2))))
                "missing-body-spin" -> JsonObject(pose - "angularVelocity")
                "string-reverse" -> JsonObject(pose + ("reversing" to s("false")))
                else -> {
                    val leg = when (variant) {
                        "missing-foot" -> JsonObject(first - "foot")
                        "wrong-phase" -> JsonObject(first + ("phase" to s("HOVERING")))
                        "string-height" -> JsonObject(first + ("height" to s("0.1")))
                        "nonfinite-height" -> JsonObject(first + ("height" to JsonPrimitive(Double.POSITIVE_INFINITY)))
                        else -> JsonObject(first + ("foot" to JsonObject(first.objectAt("foot") + ("private" to n(1)))))
                    }
                    JsonObject(pose + ("legs" to array(listOf(leg) + legs.drop(1))))
                }
            }
            return JsonObject(unit + ("mechPose" to changed))
        }
        val missed = mutableListOf<String>()
        for (view in listOf("host", "p1")) for (variant in variants) {
            var edited = false
            val damaged = rows.map { row ->
                if (row["kind"] != s("observation") || row["phase"] != s("after-advance") ||
                    row.objectAt("observation")["stepIndex"] != n(5)) row
                else {
                    edited = true
                    val observation = row.objectAt("observation")
                    val sample = if (view == "host") observation else observation.objectAt("players").objectAt(view)
                    val changed = JsonObject(sample + ("units" to array(sample.arrayAt("units").map { damage(it.jsonObject, variant) })))
                    JsonObject(row + ("observation" to if (view == "host") changed else JsonObject(observation +
                        ("players" to JsonObject(observation.objectAt("players") + (view to changed))))))
                }
            }
            assertTrue(edited)
            val result = TraceComparator.evaluate(readObject(source), readObject(contractPath), damaged)
            if (result.exitCode != 3) missed += "$view/$variant:${result.exitCode}"
        }
        assertEquals(emptyList(), missed)
    }
}
