package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.core.content.PngImages
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

class NavalBallisticArchiveTest {
    @Test
    fun runner_persists_ballistic_state_and_strict_reader_rejects_corrupted_intermediate_values_after_valid_rehash() {
        val repo = Path.of(System.getProperty("repo.root"))
        val root = TestArchives.directory("RWR_NAVAL_ARCHIVE_ROOT", javaClass, "ballistic-archive")
        val public = repo.resolve("SimulationTest/public")
        val base = readObject(public.resolve("scenarios/helicopter-ini-short.json"))
        val fixture = Files.createDirectory(root.resolve("fixture"))
        val terrain = public.resolve("inputs/helicopter-production/ini/assets/terrain.png").toRealPath()
        val png = PngImages.inspect(Files.readAllBytes(terrain), allowRgb = true)
        val map = fixture.resolve("water.tmx")
        Files.writeString(map, """<map orientation="orthogonal" renderorder="right-down" width="40" height="20" tilewidth="20" tileheight="20">
            <tileset firstgid="1" name="owned-water" tilewidth="20" tileheight="20">
              <image source="${terrain.toString().replace("&", "&amp;")}" width="${png.width}" height="${png.height}"/>
              <tile id="0"><properties><property name="water" value=""/></properties></tile>
            </tileset><layer name="Ground" width="40" height="20"><data encoding="csv">${List(800) { "1" }.joinToString(",")}</data></layer></map>""")
        val ship = public.resolve("inputs/naval-production/ini/production-package/units/naval/heavyBattleship.ini")
        val helicopter = public.resolve("inputs/helicopter-production/ini/package/units/helicopter.ini")
        val underwater = ship.parent.resolve("underwaterTarget.ini")
        val dependencies = listOf(terrain) + listOf("heavy_battleship.png", "heavy_battleship_dead.png", "large_turret.png").map { ship.parent.resolve(it) } +
            listOf("body.png", "dead.png", "rotor.png").map { helicopter.parent.resolve(it) } +
            listOf(ship.parent.resolve("underwater_target.png"))
        fun reference(id: String, path: Path, loader: String? = null): JsonObject {
            val entry = obj("id" to s(id), "path" to s(path.toAbsolutePath().toString()), "sha256" to s(sha256(path)))
            return if (loader == null) entry else JsonObject(entry + ("loader" to s(loader)))
        }
        val references = listOf(reference("map", map), reference("heavyBattleship", ship, "battleship"),
            reference("helicopter", helicopter, "helicopter"), reference("navalUnderwaterTarget", underwater, "underwater-target")) +
            dependencies.mapIndexed { i, path -> reference("dep$i", path) }
        val scenarioId = "public.naval-ballistic-archive.v1"
        val scenarioDocument = JsonObject(base.filterKeys { it != "automaticVision" } + mapOf(
            "scenarioId" to s(scenarioId), "content" to obj("map" to references[0],
                "unitVariants" to array(references.subList(1, 4)), "dependencies" to array(references.drop(4))),
            "units" to array(listOf(obj("unitId" to s("ship"), "owner" to s("p1"), "type" to s("heavyBattleship"),
                "position" to obj("x" to n(500), "y" to n(200)), "heading" to n(90)),
                obj("unitId" to s("air"), "owner" to s("p2"), "type" to s("helicopter"),
                    "position" to obj("x" to n(650), "y" to n(200)), "heading" to n(270)),
                obj("unitId" to s("sub"), "owner" to s("p2"), "type" to s("navalUnderwaterTarget"),
                    "position" to obj("x" to n(600), "y" to n(300)), "heading" to n(0), "height" to n(-8)))),
            "actions" to array(listOf(obj("actionId" to s("anti-air"), "kind" to s("attack"), "atStep" to n(20),
                "actor" to s("p1"), "unitId" to s("ship"), "targetUnitId" to s("air")))),
            "termination" to JsonObject(base.objectAt("termination") + ("steps" to array(List(170) {
                obj("stepIndex" to n(it), "delta" to n(1), "deltaUnit" to s("sim-tick")) }))),
            "combatContract" to obj("worldSeed" to n(12345), "initialFrameCounter" to n(-1),
                "unitObjectIds" to obj("ship" to n(8), "air" to n(9), "sub" to n(10)), "nextObjectId" to n(11))))
        val contractDocument = obj("schemaVersion" to n(1), "kind" to s("behavior-contract"), "contractId" to s("public.naval-ballistic-archive.contract.v1"),
            "scope" to s("self-behavior"), "scenarioIds" to array(listOf(s(scenarioId))),
            "comparison" to obj("numberMode" to s("declared-per-field"), "tolerances" to obj()),
            "approval" to obj("status" to s("tool-fixture-only"), "source" to s("ENG-P008-017 owned naval archive consumer; no original execution claim")),
            "exclusions" to array(listOf(s("original-compatibility"), s("platform-input"), s("performance"))),
            "rules" to array(listOf(obj("ruleId" to s("ship-survives"), "when" to obj("phase" to s("after-advance"), "stepIndex" to n(169)),
                "assertions" to array(listOf(obj("path" to s("units.ship.alive"), "operator" to s("equals"), "value" to b(true))))))))
        val scenario = fixture.resolve("scenario.json"); val contract = fixture.resolve("contract.json")
        Files.writeString(scenario, scenarioDocument.toString()); Files.writeString(contract, contractDocument.toString())
        val sealed = Files.createDirectory(root.resolve("sealed-inputs"))
        val snapshots = (references + listOf(reference("scenario", scenario), reference("contract", contract))).mapIndexed { i, ref ->
            val source = Path.of(ref.text("path")); val copy = sealed.resolve("$i-${source.fileName}")
            Files.copy(source, copy)
            JsonObject(ref + ("snapshot" to s(copy.fileName.toString())))
        }
        Files.writeString(sealed.resolve("manifest.json"), array(snapshots).toString())
        val original = root.resolve("original")
        val run = Runner.run(RunRequest(scenario, contract, original, listOf("api:Runner.run/NavalBallisticArchiveTest")))
        assertEquals(0, run.exitCode, run.toString())
        assertEquals(0, TraceFiles.compare(scenario, contract, original).exitCode)
        val originalRows = Files.readAllLines(original.resolve("reforge/raw.jsonl")).map { json.parseToJsonElement(it).jsonObject }
        val eligible = originalRows.indices.filter { index ->
            val row = originalRows[index]
            row["kind"] == s("observation") && row.objectAt("observation").arrayAt("projectiles").any { p ->
                p.jsonObject["sourceUnitId"] == s("ship") && p.jsonObject["sourceWeaponName"] in setOf(s("4"), s("5")) }
        }
        assertTrue(eligible.isNotEmpty(), "Actual custom ballistic projectiles must have been collected")
        val index = eligible[eligible.size / 2]
        val sample = originalRows[index].objectAt("observation")
        val shipSample = sample.arrayAt("units").single { it.jsonObject["unitId"] == s("ship") }.jsonObject
        assertEquals(n(0.0), shipSample["height"])
        assertEquals(obj("reversing" to b(false), "sinkSpeed" to n(0.0), "settled" to b(false)), shipSample["waterPose"])
        val subSample = sample.arrayAt("units").single { it.jsonObject["unitId"] == s("sub") }.jsonObject
        assertEquals(n(-8.0), subSample["height"])
        assertTrue(listOf("mechPose", "flightPose", "waterPose").all { it !in subSample })
        val last = originalRows.last { it["kind"] == s("observation") }.objectAt("observation")
        assertEquals(935.0, last.arrayAt("units").single { it.jsonObject["unitId"] == s("sub") }.jsonObject.number("health"))
        val shotIndex = sample.arrayAt("projectiles").indexOfFirst { it.jsonObject["sourceUnitId"] == s("ship") &&
            it.jsonObject["sourceWeaponName"] in setOf(s("4"), s("5")) }
        assertEquals(setOf("speed", "reachedApex", "rawAz"), sample.arrayAt("projectiles")[shotIndex].jsonObject.objectAt("ballistic").keys)
        fun corrupt(name: String, unit: Boolean = false, subject: String = "ship", change: (JsonObject) -> JsonObject) {
            val target = root.resolve(name)
            Files.walk(original).use { paths -> paths.forEach { source ->
                val destination = target.resolve(original.relativize(source)).normalize()
                require(destination.startsWith(root.toAbsolutePath().normalize()))
                if (Files.isDirectory(source)) Files.createDirectories(destination) else Files.copy(source, destination)
            } }
            val rows = originalRows.toMutableList(); val row = rows[index]
            val observation = row.objectAt("observation")
            val field = if (unit) "units" else "projectiles"
            val values = observation.arrayAt(field).toMutableList()
            val changedIndex = if (unit) values.indexOfFirst { it.jsonObject["unitId"] == s(subject) } else shotIndex
            values[changedIndex] = change(values[changedIndex].jsonObject)
            rows[index] = JsonObject(row + ("observation" to JsonObject(observation + (field to array(values)))))
            Files.writeString(target.resolve("reforge/raw.jsonl"), rows.joinToString("\n", postfix = "\n"))
            Files.writeString(target.resolve("reforge/normalized.jsonl"), rows.mapIndexed { i, value -> normalizeRecord(value, i + 1,
                if (i == 0) null else scenarioDocument.objectAt("coordinateModel").id("id"),
                if (i == 0) null else scenarioDocument.objectAt("timeModel").id("id")) }.joinToString("\n", postfix = "\n"))
            val manifest = readObject(target.resolve("manifest.json"))
            val traces = manifest.arrayAt("traceFiles").map {
                val file = it.jsonObject
                val digest = sha256(target.resolve(file.text("path")))
                JsonObject(file + ("sha256" to s(digest)))
            }
            val updatedManifest = JsonObject(manifest + ("traceFiles" to array(traces)))
            Files.writeString(target.resolve("manifest.json"), updatedManifest.toString())
            val failed = TraceFiles.compare(scenario, contract, target)
            Files.writeString(target.resolve("readonly-consumer-judgment.json"), failed.document.toString())
            assertEquals(3, failed.exitCode, "$name: ${failed.document}")
            val category = if (subject == "sub") "underwater-target" else if (unit) "water-pose" else "ballistic-state"
            assertTrue(failed.document.toString().contains(category), "$name: ${failed.document}")
        }
        corrupt("missing-state") { JsonObject(it - "ballistic") }
        fun badState(name: String, change: (JsonObject) -> JsonObject) = corrupt(name) { JsonObject(it + ("ballistic" to change(it.objectAt("ballistic")))) }
        badState("missing-apex") { JsonObject(it - "reachedApex") }
        badState("wrong-speed-type") { JsonObject(it + ("speed" to s("fast"))) }
        badState("wrong-apex-type") { JsonObject(it + ("reachedApex" to s("true"))) }
        badState("negative-speed") { JsonObject(it + ("speed" to n(-.1))) }
        badState("float-overflow-angle") { JsonObject(it + ("rawAz" to n(1e100))) }
        corrupt("missing-water-pose", unit = true) { JsonObject(it - "waterPose") }
        corrupt("missing-water-height", unit = true) { JsonObject(it - "height") }
        fun badWater(name: String, change: (JsonObject) -> JsonObject) = corrupt(name, unit = true) {
            JsonObject(it + ("waterPose" to change(it.objectAt("waterPose")))) }
        badWater("missing-water-reversing") { JsonObject(it - "reversing") }
        badWater("wrong-water-flag-type") { JsonObject(it + ("settled" to s("false"))) }
        badWater("negative-water-sink") { JsonObject(it + ("sinkSpeed" to n(-.1))) }
        badWater("overflow-water-sink") { JsonObject(it + ("sinkSpeed" to n(1e100))) }
        corrupt("missing-underwater-height", unit = true, subject = "sub") { JsonObject(it - "height") }
        corrupt("wrong-underwater-height-type", unit = true, subject = "sub") { JsonObject(it + ("height" to s("-8"))) }
        corrupt("wrong-underwater-depth", unit = true, subject = "sub") { JsonObject(it + ("height" to n(-1))) }
        println("NAVAL_BALLISTIC_ARCHIVE=$root")
    }
}
