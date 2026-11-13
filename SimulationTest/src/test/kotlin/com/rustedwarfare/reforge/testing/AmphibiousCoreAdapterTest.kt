package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class AmphibiousCoreAdapterTest {
    @Test fun three_formats_load_the_native_jet_and_archive_actual_height_and_intent_from_formal_actions() {
        for (format in listOf("ini", "json", "hjson")) {
            val (scenario, contract) = amphibiousScenarioFixture(javaClass, format)
            val input = ScenarioReader.load(scenario, contract)
            CoreAdapter(input).use { core ->
                val initial = core.initial().json().arrayAt("units").first().jsonObject
                assertEquals(b(true), initial.objectAt("amphibiousJet")["flyingIntent"])
                assertEquals(n(0.0), initial["height"])
                assertIs<ActionResult.Accepted>(core.submit(input.actions.single()))
                val sample = core.advance(Step(0, 1.0, "sim-tick")).json().arrayAt("units").first().jsonObject
                assertEquals(b(false), sample.objectAt("amphibiousJet")["flyingIntent"])
                assertEquals((-.006f).toDouble(), sample.number("height"))
                assertEquals(s("WATER"), sample.objectAt("amphibiousJet")["movementType"])
                assertEquals(b(true), initial.objectAt("amphibiousJet")["flyingIntent"])
            }
            val document = readObject(scenario)
            for (invalid in listOf<JsonElement>(s("false"), n(0), JsonNull)) {
                val action = JsonObject(document.arrayAt("actions").single().jsonObject + ("flying" to invalid))
                Files.writeString(scenario, JsonObject(document + ("actions" to array(listOf(action)))).toString())
                assertFailsWith<InputError> { ScenarioReader.load(scenario, contract) }
            }
        }
    }
}

internal fun amphibiousScenarioFixture(owner: Class<*>, format: String, batch: String = ""): Pair<Path, Path> {
    val repo = Path.of(System.getProperty("repo.root"))
    val public = repo.resolve("SimulationTest/public")
    val baseFile = public.resolve("scenarios/naval-production-$format-chain.json")
    val base = readObject(baseFile)
    val root = TestArchives.directory("RWR_AMPHIBIOUS_ARCHIVE_ROOT", owner, "amphibious-$format$batch")
    fun ref(id: String, file: Path, loader: String? = null): JsonObject = JsonObject(
        obj("id" to s(id), "path" to s(file.toAbsolutePath().normalize().toString()), "sha256" to s(sha256(file))) +
            (loader?.let { mapOf("loader" to s(it)) } ?: emptyMap()))
    val inputs = public.resolve("inputs/amphibious-production/$format")
    val folder = inputs.resolve("production-package")
    val map = inputs.resolve("maps/naval-shore.tmx")
    val jet = folder.resolve("units/amphibiousJet.$format")
    val deps = Files.walk(inputs).use { paths -> paths.filter { Files.isRegularFile(it) && it != map && it != jet }
        .sorted().toList().mapIndexed { i, file -> ref("dependency-$i", file) } }
    val scenarioId = "public.amphibious-$format.archive.v1"
    val document = JsonObject(base.filterKeys { it !in setOf("economyContract", "automaticVision", "maintenanceContract") } + mapOf(
        "scenarioId" to s(scenarioId), "content" to obj("map" to ref("map", map),
            "unitVariants" to array(listOf(ref("amphibiousJet", jet, "amphibious-jet"))), "dependencies" to array(deps)),
        "units" to array(listOf(obj("unitId" to s("jet"), "owner" to s("p1"), "type" to s("amphibiousJet"),
            "position" to obj("x" to n(500), "y" to n(300)), "heading" to n(90)))),
        "combatContract" to obj("worldSeed" to n(12345), "initialFrameCounter" to n(-1),
            "unitObjectIds" to obj("jet" to n(8)), "nextObjectId" to n(9)),
        "actions" to array(listOf(obj("actionId" to s("dive"), "atStep" to n(0), "actor" to s("p1"),
            "unitId" to s("jet"), "kind" to s("amphibious-flight"), "flying" to b(false)))),
        "termination" to obj("kind" to s("explicit-steps"), "required" to b(true), "steps" to array(List(3) {
            obj("stepIndex" to n(it), "delta" to n(1), "deltaUnit" to s("sim-tick")) }))))
    val contract = obj("schemaVersion" to n(1), "kind" to s("behavior-contract"), "contractId" to s("public.amphibious.archive.contract.v1"),
        "scope" to s("self-behavior"), "scenarioIds" to array(listOf(s(scenarioId))),
        "comparison" to obj("numberMode" to s("declared-per-field"), "tolerances" to obj()),
        "approval" to obj("status" to s("tool-fixture-only"), "source" to s("ENG-P009-006 fixed owned archive; no original execution claim")),
        "exclusions" to array(listOf(s("original-compatibility"), s("platform-input"), s("performance"))),
        "rules" to array(listOf(obj("ruleId" to s("jet-lives"), "when" to obj("phase" to s("after-advance"), "stepIndex" to n(2)),
            "assertions" to array(listOf(obj("path" to s("units.jet.alive"), "operator" to s("equals"), "value" to b(true))))))))
    val scenario = root.resolve("scenario.json"); val contractFile = root.resolve("contract.json")
    Files.writeString(scenario, document.toString()); Files.writeString(contractFile, contract.toString())
    return scenario to contractFile
}
