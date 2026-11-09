package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class SubmarineCoreAdapterTest {
    @Test fun formal_three_format_loader_depth_action_and_host_archive_use_the_playable_submarine() {
        for (format in listOf("ini", "json", "hjson")) {
            val fixture = submarineScenarioFixture(javaClass, format)
            val input = ScenarioReader.load(fixture.first, fixture.second)
            CoreAdapter(input).use { core ->
                val initial = core.initial().json().arrayAt("units").first().jsonObject
                assertEquals(obj("surfaceIntent" to b(false), "verticalSpeed" to n(0f), "underwater" to b(true),
                    "surfacePresentation" to b(true), "maximumSpeed" to n(.45), "attackRange" to n(180.0)), initial.objectAt("submarine"))
                val surface = core.submit(input.actions.single())
                assertIs<ActionResult.Accepted>(surface, "$format: $surface")
                core.advance(Step(0, 1.0, "sim-tick"))
                val sample = core.observe().json()
                val sub = sample.arrayAt("units").first().jsonObject
                assertEquals((-8f + .003f).toDouble(), sub.number("height"))
                assertEquals(true, sub.objectAt("submarine").required("surfaceIntent").jsonPrimitive.boolean)
                val shot = sample.arrayAt("projectiles").single().jsonObject
                assertEquals(s("SUBMARINE_TORPEDO"), shot["appearance"])
                assertEquals(s("SUBMARINE_TORPEDO"), sample.arrayAt("projectileEvents").single().jsonObject.objectAt("projectile")["appearance"])
                assertEquals(false, initial.objectAt("submarine").required("surfaceIntent").jsonPrimitive.boolean)
            }
            val document = readObject(fixture.first)
            for (invalid in listOf<JsonElement>(s("true"), n(1), JsonNull)) {
                val bad = JsonObject(document + ("actions" to array(listOf(JsonObject(document.arrayAt("actions").single().jsonObject + ("surface" to invalid))))))
                Files.writeString(fixture.first, bad.toString())
                assertFailsWith<InputError>("$format rejects non-boolean $invalid") { ScenarioReader.load(fixture.first, fixture.second) }
            }
        }
    }
}

/** Fixed owned inputs, explicit initial depth and independent contract; never edits a running world. */
internal fun submarineScenarioFixture(owner: Class<*>, format: String): Pair<Path, Path> {
    val repo = Path.of(System.getProperty("repo.root"))
    val public = repo.resolve("SimulationTest/public")
    val baseFile = public.resolve("scenarios/naval-production-$format-chain.json")
    val base = readObject(baseFile)
    val root = TestArchives.directory("RWR_SUBMARINE_ARCHIVE_ROOT", owner, "submarine-$format")
    fun reference(id: String, file: Path, loader: String? = null): JsonObject = JsonObject(
        obj("id" to s(id), "path" to s(file.toAbsolutePath().normalize().toString()), "sha256" to s(sha256(file))) +
            (loader?.let { mapOf("loader" to s(it)) } ?: emptyMap()))
    val oldContent = base.objectAt("content")
    val mapRef = oldContent.objectAt("map").let { reference("map", baseFile.parent.resolve(it.text("path"))) }
    val folder = public.resolve("inputs/naval-production/$format/production-package/units")
    val subRef = reference("attackSubmarine", folder.resolve("submarine/submarine.$format"), "submarine")
    val targetRef = reference("navalUnderwaterTarget", folder.resolve("naval/underwaterTarget.$format"), "underwater-target")
    val dependencies = (oldContent.arrayAt("dependencies").map { it.jsonObject }.map { reference(it.id("id"), baseFile.parent.resolve(it.text("path"))) } +
        listOf("body.png", "dead.png").mapIndexed { i, name -> reference("sub-image-$i", folder.resolve("submarine/$name")) }).distinctBy { it.text("path") }
    fun spawn(id: String, type: String, ownerId: String, x: Int) = obj("unitId" to s(id), "owner" to s(ownerId), "type" to s(type),
        "position" to obj("x" to n(x), "y" to n(300)), "heading" to n(90), "height" to n(-8))
    val scenarioId = "public.submarine-$format.archive.v1"
    val document = JsonObject(base.filterKeys { it !in setOf("economyContract", "automaticVision", "maintenanceContract") } + mapOf(
        "scenarioId" to s(scenarioId), "content" to obj("map" to mapRef, "unitVariants" to array(listOf(subRef, targetRef)), "dependencies" to array(dependencies)),
        "units" to array(listOf(spawn("sub", "attackSubmarine", "p1", 500), spawn("target", "navalUnderwaterTarget", "p2", 650))),
        "combatContract" to obj("worldSeed" to n(12345), "initialFrameCounter" to n(-1), "unitObjectIds" to obj("sub" to n(8), "target" to n(9)), "nextObjectId" to n(10)),
        "actions" to array(listOf(obj("actionId" to s("surface"), "atStep" to n(0), "actor" to s("p1"), "unitId" to s("sub"), "kind" to s("submarine-depth"), "surface" to b(true)))),
        "termination" to obj("kind" to s("explicit-steps"), "required" to b(true), "steps" to array(List(3) { obj("stepIndex" to n(it), "delta" to n(1), "deltaUnit" to s("sim-tick")) }))))
    val contract = obj("schemaVersion" to n(1), "kind" to s("behavior-contract"), "contractId" to s("public.submarine.archive.contract.v1"),
        "scope" to s("self-behavior"), "scenarioIds" to array(listOf(s(scenarioId))),
        "comparison" to obj("numberMode" to s("declared-per-field"), "tolerances" to obj()),
        "approval" to obj("status" to s("tool-fixture-only"), "source" to s("ENG-P009-005 fixed owned submarine archive; no original execution claim")),
        "exclusions" to array(listOf(s("original-compatibility"), s("platform-input"), s("performance"))),
        "rules" to array(listOf(obj("ruleId" to s("sub-lives"), "when" to obj("phase" to s("after-advance"), "stepIndex" to n(2)),
            "assertions" to array(listOf(obj("path" to s("units.sub.alive"), "operator" to s("equals"), "value" to b(true))))))))
    val scenarioFile = root.resolve("scenario.json"); val contractFile = root.resolve("contract.json")
    Files.writeString(scenarioFile, document.toString()); Files.writeString(contractFile, contract.toString())
    return scenarioFile to contractFile
}
