package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class MechPublicScenarioTest {
    private val root = Path.of(System.getProperty("repo.root")).resolve("SimulationTest/public")

    @Test
    fun every_intermediate_host_and_authorized_player_mech_sample_requires_a_complete_finite_pose() {
        val source = root.resolve("scenarios/mech-ini-short.json")
        val original = readObject(source)
        val content = original.objectAt("content")
        fun absolute(ref: JsonObject) = JsonObject(ref + ("path" to s(source.parent.resolve(ref.text("path")).normalize().toString())))
        val document = JsonObject(original + mapOf(
            "content" to obj("map" to absolute(content.objectAt("map")),
                "unitVariants" to array(content.arrayAt("unitVariants").map { absolute(it.jsonObject) }),
                "dependencies" to array(content.arrayAt("dependencies").map { absolute(it.jsonObject) })),
            "automaticVision" to obj("retention" to s("MEMORY"), "pathSource" to s("PLAYER_KNOWLEDGE"),
                "shareAlliedVision" to b(false), "observers" to array(listOf(s("p1"), s("p2"))),
                "grid" to obj("width" to n(30), "height" to n(20)))))
        val temporary = System.getenv("RWRE_P005_ARCHIVE_ROOT")?.let {
            Files.createDirectories(Path.of(it)).resolve("mech-qualification").also { target -> Files.createDirectory(target) }
        } ?: Files.createTempDirectory("mech-pose-integrity-")
        val scenario = temporary.resolve("scenario.json")
        Files.writeString(scenario, document.toString())
        val contractPath = root.resolve("contracts/mech-ini-short.json")
        val output = temporary.resolve("run")
        assertEquals(0, Runner.run(RunRequest(scenario, contractPath, output, listOf("mech-pose-integrity"))).exitCode)
        val rows = Files.readAllLines(output.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val contract = readObject(contractPath)
        assertEquals(0, TraceComparator.evaluate(document, contract, rows).exitCode)
        assertEquals(0, TraceFiles.compare(scenario, contractPath, output).exitCode)
        val qualifications = mutableListOf(obj("case" to s("good"), "path" to s("run"), "expectedExitCode" to n(0)))
        fun persistVariant(name: String, changed: List<JsonObject>): Judgment {
            val target = temporary.resolve(name)
            Files.walk(output).use { paths -> paths.forEach { file ->
                val copy = target.resolve(output.relativize(file))
                if (Files.isDirectory(file)) Files.createDirectories(copy) else Files.copy(file, copy)
            } }
            Files.writeString(target.resolve("reforge/raw.jsonl"), changed.joinToString("\n", postfix="\n"))
            val normalized = changed.mapIndexed { index, row -> normalizeRecord(row, index + 1,
                if (index == 0) null else document.objectAt("coordinateModel").id("id"),
                if (index == 0) null else document.objectAt("timeModel").id("id")) }
            Files.writeString(target.resolve("reforge/normalized.jsonl"), normalized.joinToString("\n", postfix="\n"))
            val manifest = readObject(target.resolve("manifest.json"))
            Files.writeString(target.resolve("manifest.json"), JsonObject(manifest + ("traceFiles" to array(
                listOf("reforge/raw.jsonl", "reforge/normalized.jsonl").map { path ->
                    obj("path" to s(path), "sha256" to s(sha256(target.resolve(path)))) }))).toString())
            return TraceFiles.compare(scenario, contractPath, target)
        }
        val variants = listOf("missing-pose", "null-pose", "missing-leg", "duplicate-index", "wrong-phase",
            "missing-foot", "string-height", "missing-body-spin", "string-reverse", "private-target")
        fun damage(unit: JsonObject, variant: String): JsonObject {
            if (variant == "missing-pose") return JsonObject(unit - "mechPose")
            if (variant == "null-pose") return JsonObject(unit + ("mechPose" to JsonNull))
            val pose = unit.objectAt("mechPose")
            val legs = pose.arrayAt("legs")
            val first = legs.first().jsonObject
            val changed = when (variant) {
                "missing-leg" -> JsonObject(pose + ("legs" to array(legs.take(1))))
                "duplicate-index" -> JsonObject(pose + ("legs" to array(listOf(legs.first(), legs.first()))))
                "wrong-phase", "missing-foot", "string-height" -> {
                    val leg = when (variant) {
                        "wrong-phase" -> JsonObject(first + ("phase" to s("HOVERING")))
                        "missing-foot" -> JsonObject(first - "foot")
                        else -> JsonObject(first + ("height" to s("0.1")))
                    }
                    JsonObject(pose + ("legs" to array(listOf(leg, legs.last()))))
                }
                "missing-body-spin" -> JsonObject(pose - "angularVelocity")
                "string-reverse" -> JsonObject(pose + ("reversing" to s("false")))
                else -> JsonObject(pose + ("predictedFootTarget" to obj("x" to n(1), "y" to n(2))))
            }
            return JsonObject(unit + ("mechPose" to changed))
        }
        val missed = mutableListOf<String>()
        for (view in listOf("host", "p1")) for (variant in variants) {
            val damaged = rows.map { row ->
                if (row["kind"] != s("observation") || row["phase"] != s("after-advance") ||
                    row.objectAt("observation")["stepIndex"] != n(5)) row
                else {
                    val observation = row.objectAt("observation")
                    val sample = if (view == "host") observation else observation.objectAt("players").objectAt(view)
                    val units = sample.arrayAt("units")
                    assertTrue(units.any { it.jsonObject["unitId"] == s("a") })
                    val changed = JsonObject(sample + ("units" to array(units.map {
                        if (it.jsonObject["unitId"] == s("a")) damage(it.jsonObject, variant) else it
                    })))
                    val updated = if (view == "host") changed else JsonObject(observation +
                        ("players" to JsonObject(observation.objectAt("players") + (view to changed))))
                    JsonObject(row + ("observation" to updated))
                }
            }
            val result = TraceComparator.evaluate(document, contract, damaged)
            if (result.exitCode != 3) missed += "$view/$variant:${result.exitCode}"
            val name = "bad-$view-$variant"
            val formal = persistVariant(name, damaged)
            assertEquals(3, formal.exitCode, name)
            assertEquals(result.document.objectAt("comparison")["category"],
                formal.document.objectAt("comparison")["category"], "Semantic rejection, not digest rejection: $name")
            qualifications += obj("case" to s(name), "path" to s(name), "expectedExitCode" to n(3),
                "category" to formal.document.objectAt("comparison").required("category"), "judgment" to formal.document)
        }
        assertEquals(emptyList(), missed, "Every partial mech sample must invalidate its public archive")
        Files.copy(contractPath, temporary.resolve("contract.json"))
        Files.writeString(temporary.resolve("qualification.json"), obj("schemaVersion" to n(1),
            "scenario" to s("scenario.json"), "contract" to s("contract.json"),
            "scope" to s("Derived semantic bad archives; one actual good Core run; original raw is preserved"),
            "cases" to array(qualifications)).toString())
        assertTrue(Files.isRegularFile(temporary.resolve("qualification.json")), "Formal good/bad archive qualifications must be retained")
    }

    @Test
    fun three_formats_run_the_real_mech_loader_and_archive_two_legs_through_move_and_stop() {
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = root.resolve("scenarios/mech-$format-short.json")
            val contract = root.resolve("contracts/mech-$format-short.json")
            val output = Files.createTempDirectory("mech-public-$format-").resolve("run")
            val result = Runner.run(RunRequest(scenario, contract, output, listOf("mech-public-$format")))
            assertEquals(0, result.exitCode, Files.readString(output.resolve("comparison/result.json")))
            assertEquals(0, TraceFiles.compare(scenario, contract, output, output).exitCode)
            val samples = Files.readAllLines(output.resolve("reforge/raw.jsonl"))
                .map { json.parseToJsonElement(it).jsonObject }.filter { it["kind"] == s("observation") }
            assertEquals(13, samples.size)
            val poses = samples.map { it.objectAt("observation").arrayAt("units").single().jsonObject.objectAt("mechPose") }
            assertTrue(poses.all { it.arrayAt("legs").size == 2 })
            assertEquals(2.05, poses[10].arrayAt("legs").first().jsonObject.number("height"), 1e-5)
            assertTrue(poses.drop(1).any { it.arrayAt("legs") != poses.first().arrayAt("legs") })
        }
    }

    @Test
    fun every_format_keeps_leg_and_foot_dependencies_mandatory_in_formal_input_identities() {
        for (format in listOf("ini", "json", "hjson")) {
            val source = root.resolve("scenarios/mech-$format-short.json")
            val original = readObject(source)
            val content = original.objectAt("content")
            fun absolute(ref: JsonObject) = JsonObject(ref + ("path" to s(source.parent.resolve(ref.text("path")).normalize().toString())))
            val dependencies = content.arrayAt("dependencies").map { absolute(it.jsonObject) }
            val temporary = Files.createTempDirectory("mech-input-identity-")
            for (name in listOf("leg.png", "foot.png")) {
                val scenario = temporary.resolve("$name.json")
                Files.writeString(scenario, JsonObject(original + ("content" to obj(
                    "map" to absolute(content.objectAt("map")),
                    "unitVariants" to array(content.arrayAt("unitVariants").map { absolute(it.jsonObject) }),
                    "dependencies" to array(dependencies.filter { !it.text("path").replace('\\', '/').endsWith("/$name") })
                ))).toString())
                val output = temporary.resolve("$name-run")
                val result = Runner.run(RunRequest(scenario, root.resolve("contracts/mech-$format-short.json"), output, listOf("missing-$name")))
                assertEquals(64, result.exitCode)
                assertTrue(readObject(output.resolve("comparison/result.json")).text("reason").contains("no declared identity"))
            }
        }
    }

    @Test
    fun three_formats_build_a_real_factory_and_consume_its_paid_mech_through_exit_orders_and_death() {
        for (format in listOf("ini", "json", "hjson")) {
            val scenario = root.resolve("scenarios/mech-production-$format-chain.json")
            val contract = root.resolve("contracts/mech-production-$format-chain.json")
            val output = Files.createTempDirectory("mech-production-$format-").resolve("run")
            val result = Runner.run(RunRequest(scenario, contract, output, listOf("mech-production-$format")))
            assertEquals(0, result.exitCode, Files.readString(output.resolve("comparison/result.json")))
            assertEquals(0, TraceFiles.compare(scenario, contract, output, output).exitCode)
        }
    }
}
