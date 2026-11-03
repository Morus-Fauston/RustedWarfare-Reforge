package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class HelicopterPublicScenarioTest {
    private val root = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")

    private fun absoluteDocument(source: Path): JsonObject {
        val original = readObject(source)
        val content = original.objectAt("content")
        fun absolute(ref: JsonObject) = JsonObject(ref +
            ("path" to s(source.parent.resolve(ref.text("path")).normalize().toString())))
        return JsonObject(original + ("content" to obj("map" to absolute(content.objectAt("map")),
            "unitVariants" to array(content.arrayAt("unitVariants").map { absolute(it.jsonObject) }),
            "dependencies" to array(content.arrayAt("dependencies").map { absolute(it.jsonObject) }))))
    }

    @Test
    fun every_intermediate_host_and_authorized_player_sample_requires_the_complete_finite_flight_pose() {
        val document = absoluteDocument(root.resolve("scenarios/helicopter-ini-short.json"))
        val temporary = Files.createTempDirectory("helicopter-pose-integrity-")
        val scenario = temporary.resolve("scenario.json")
        Files.writeString(scenario, document.toString())
        val contractPath = root.resolve("contracts/helicopter-ini-short.json")
        val output = temporary.resolve("run")
        assertEquals(0, Runner.run(RunRequest(scenario, contractPath, output, listOf("helicopter-pose-integrity"))).exitCode)
        val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val contract = readObject(contractPath)
        assertEquals(0, TraceComparator.evaluate(document, contract, rows).exitCode)
        val fields = listOf("velocity", "height", "heightPhase", "rotorPhase", "rotorHeading", "rotorHeightOffset")
        val variants = listOf("missing-pose", "null-pose", "missing-unit-height", "string-unit-height",
            "height-mismatch", "private-target", "velocity-array", "velocity-private") +
            fields.map { "missing-$it" } + fields.drop(1).flatMap { listOf("string-$it", "nonfinite-$it") } +
            listOf("missing-x", "missing-y", "string-x", "nonfinite-y")
        fun damage(unit: JsonObject, variant: String): JsonObject {
            when (variant) {
                "missing-pose" -> return JsonObject(unit - "flightPose")
                "null-pose" -> return JsonObject(unit + ("flightPose" to JsonNull))
                "missing-unit-height" -> return JsonObject(unit - "height")
                "string-unit-height" -> return JsonObject(unit + ("height" to s(unit.number("height").toString())))
            }
            val pose = unit.objectAt("flightPose")
            val velocity = pose.objectAt("velocity")
            val changed = when {
                variant == "height-mismatch" -> JsonObject(pose + ("height" to n(unit.number("height") + 1)))
                variant == "private-target" -> JsonObject(pose + ("futureTarget" to obj("x" to n(1), "y" to n(2))))
                variant == "velocity-array" -> JsonObject(pose + ("velocity" to array(listOf(n(0), n(0)))))
                variant == "velocity-private" -> JsonObject(pose + ("velocity" to JsonObject(velocity + ("target" to n(1)))))
                variant in listOf("missing-x", "missing-y", "string-x", "nonfinite-y") -> {
                    val vector = when (variant) {
                        "missing-x" -> JsonObject(velocity - "x")
                        "missing-y" -> JsonObject(velocity - "y")
                        "string-x" -> JsonObject(velocity + ("x" to s("0")))
                        else -> JsonObject(velocity + ("y" to json.parseToJsonElement("1e999")))
                    }
                    JsonObject(pose + ("velocity" to vector))
                }
                variant.startsWith("missing-") -> JsonObject(pose - variant.removePrefix("missing-"))
                variant.startsWith("string-") -> JsonObject(pose + (variant.removePrefix("string-") to s("0")))
                else -> JsonObject(pose + (variant.removePrefix("nonfinite-") to json.parseToJsonElement("1e999")))
            }
            return JsonObject(unit + ("flightPose" to changed))
        }
        val missed = mutableListOf<String>()
        for (view in listOf("host", "p1")) for (variant in variants) {
            var mutations = 0
            val damaged = rows.map { row ->
                if (row["kind"] != s("observation") || row["phase"] != s("after-advance") ||
                    row.objectAt("observation")["stepIndex"] != n(5)) row
                else {
                    val observation = row.objectAt("observation")
                    val sample = if (view == "host") observation else observation.objectAt("players").objectAt(view)
                    val changed = JsonObject(sample + ("units" to array(sample.arrayAt("units").map {
                        if (it.jsonObject["unitId"] == s("a")) { mutations++; damage(it.jsonObject, variant) } else it
                    })))
                    val updated = if (view == "host") changed else JsonObject(observation +
                        ("players" to JsonObject(observation.objectAt("players") + (view to changed))))
                    JsonObject(row + ("observation" to updated))
                }
            }
            assertEquals(1, mutations, "$view/$variant must damage an actual intermediate sample")
            val result = TraceComparator.evaluate(document, contract, damaged)
            if (result.exitCode != 3) missed += "$view/$variant:${result.exitCode}"
        }
        assertEquals(emptyList(), missed, "Every partial flight sample must invalidate its public archive")
    }

    @Test
    fun three_formats_archive_real_flight_pose_through_move_and_stop() {
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = root.resolve("scenarios/helicopter-$format-short.json")
            val contract = root.resolve("contracts/helicopter-$format-short.json")
            val output = Files.createTempDirectory("helicopter-public-$format-").resolve("run")
            val result = Runner.run(RunRequest(scenario, contract, output, listOf("helicopter-public-$format")))
            assertEquals(0, result.exitCode, Files.readString(output.resolve("comparison/result.json")))
            assertEquals(0, TraceFiles.compare(scenario, contract, output, output).exitCode)
            val samples = Files.readAllLines(output.resolve("reforge/raw.jsonl"))
                .map { json.parseToJsonElement(it).jsonObject }.filter { it["kind"] == s("observation") }
            assertEquals(13, samples.size)
            val units = samples.map { it.objectAt("observation").arrayAt("units").single().jsonObject }
            assertTrue(units.all { it.number("height") == it.objectAt("flightPose").number("height") })
            assertTrue(units.drop(1).any { it.objectAt("flightPose") != units.first().objectAt("flightPose") })
        }
    }

    @Test
    fun rotor_dependencies_are_mandatory_in_every_formal_input_identity() {
        for (format in listOf("ini", "json", "hjson")) {
            val document = absoluteDocument(root.resolve("scenarios/helicopter-$format-short.json"))
            val content = document.objectAt("content")
            val dependencies = content.arrayAt("dependencies")
            val kept = dependencies.filter { !it.jsonObject.text("path").replace('\\', '/').endsWith("/rotor.png") }
            assertEquals(dependencies.size - 1, kept.size)
            val temporary = Files.createTempDirectory("helicopter-input-identity-")
            val scenario = temporary.resolve("scenario.json")
            Files.writeString(scenario, JsonObject(document + ("content" to JsonObject(content +
                ("dependencies" to array(kept))))).toString())
            val output = temporary.resolve("run")
            val result = Runner.run(RunRequest(scenario, root.resolve("contracts/helicopter-$format-short.json"),
                output, listOf("missing-rotor")))
            assertEquals(64, result.exitCode)
            assertTrue(readObject(output.resolve("comparison/result.json")).text("reason").contains("no declared identity"))
        }
    }

    @Test
    fun three_formats_build_pay_spawn_fly_stop_and_fight_with_identical_complete_core_observations() {
        val samplesByFormat = mutableListOf<List<JsonElement?>>()
        val scenarioId = "public.helicopter-production-three-formats-chain.v1"
        for (format in listOf("ini", "json", "hjson")) {
            val temporary = Files.createTempDirectory("helicopter-production-$format-")
            val scenario = temporary.resolve("scenario.json")
            val contract = temporary.resolve("contract.json")
            // Fix shared physical identity in the input before running; never rewrite collected rows.
            Files.writeString(scenario, JsonObject(absoluteDocument(root.resolve("scenarios/helicopter-production-$format-chain.json")) +
                ("scenarioId" to s(scenarioId))).toString())
            Files.writeString(contract, JsonObject(readObject(root.resolve("contracts/helicopter-production-$format-chain.json")) +
                ("scenarioIds" to array(listOf(s(scenarioId))))).toString())
            val output = temporary.resolve("run")
            val result = Runner.run(RunRequest(scenario, contract, output, listOf("helicopter-production-$format")))
            assertEquals(0, result.exitCode, Files.readString(output.resolve("comparison/result.json")))
            assertEquals(0, TraceFiles.compare(scenario, contract, output, output).exitCode)
            val observations = Files.readAllLines(output.resolve("reforge/raw.jsonl"))
                .map { json.parseToJsonElement(it).jsonObject }.filter { it["kind"] == s("observation") }
                .map { it["observation"] }
            assertEquals(3201, observations.size)
            samplesByFormat += observations
            val product = "produced:p1:${"b".repeat(32)}"
            val final = observations.last()!!.jsonObject
            assertTrue(final.arrayAt("units").single { it.jsonObject["unitId"] == s("target") }.jsonObject.number("health") < 150.0)
            assertTrue(observations.flatMap { it!!.jsonObject.arrayAt("projectileEvents") }.any {
                it.jsonObject["kind"] == s("born") && it.jsonObject.objectAt("projectile")["sourceUnitId"] == s(product)
            }, "The actual paid helicopter must emit projectiles")
        }
        assertEquals(samplesByFormat[0], samplesByFormat[1], "Complete INI/JSON observations without dropped fields or tolerance")
        assertEquals(samplesByFormat[0], samplesByFormat[2], "Complete INI/HJSON observations")
    }
}
