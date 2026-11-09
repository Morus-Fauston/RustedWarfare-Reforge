package com.rustedwarfare.reforge.client

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/** The public core scenario is also the menu/session seed. Expectations live in a separate contract. */
class TestScenarioChoice private constructor(val seed: Path, val root: Path, val id: String,
    val name: String, val content: ContentPackChoice, val scenario: Scenario,
    private val identities: Map<Path, String>) {
    fun verifyStable() {
        for ((path, expected) in identities) require(ordinaryHash(path) == expected) {
            "TEST_SCENE_CHANGED: $path: Check the scenario again after repairing its files"
        }
        content.revalidate()
    }

    companion object {
        /** inputOverride is the bounded input snapshot made by the headless adapter. */
        fun open(path: Path, inputOverride: Path? = null): TestScenarioChoice {
            val seed = path.toAbsolutePath().normalize()
            val hash = ordinaryHash(seed)
            require(Files.size(seed) <= 2 * 1024 * 1024) { "TEST_SCENE_INVALID: Seed exceeds its input limit" }
            val document = JsonReader().parse(Files.readString(seed))
            require(document.getInt("schemaVersion") == 1 && document.getString("kind") == "scenario" &&
                document.get("testScene").getInt("formatVersion") == 1) { "TEST_SCENE_INVALID: Unsupported scenario seed" }
            verifyIntents(document)
            val prefix = document.getString("inputs").trimEnd('/') + "/"
            val root = (inputOverride ?: seed.parent.resolve(document.getString("inputs"))).toAbsolutePath().normalize()
            require(Files.isDirectory(root, NOFOLLOW_LINKS) && root.toRealPath() == root) { "TEST_SCENE_INVALID: Missing ordinary input root: $root" }
            fun file(name: String): Path {
                val relative = Path.of(name)
                require(!relative.isAbsolute && relative.none { it.toString() == ".." } && name.isNotBlank()) { "TEST_SCENE_INVALID: Input path escapes the paired inputs" }
                return root.resolve(relative).normalize().also {
                    require(it.startsWith(root) && it.toRealPath() == it) { "TEST_SCENE_INVALID: Missing or linked paired file: $it" }
                }
            }
            val identities = linkedMapOf(seed to hash)
            for (entry in document.get("testScene").get("files")) {
                require(Regex("[a-f0-9]{64}").matches(entry.asString())) { "TEST_SCENE_INVALID: Invalid paired file hash" }
                identities[file(entry.name)] = entry.asString()
            }
            require(identities.size > 1) { "TEST_SCENE_INVALID: Empty paired inputs" }
            for ((p, expected) in identities) require(ordinaryHash(p) == expected) { "TEST_SCENE_CHANGED: Paired input differs: $p" }
            val declared = document.get("content")
            val references = listOf(declared.get("map")) + declared.get("unitVariants").toList() + declared.get("dependencies").toList()
            for (reference in references) {
                val name = reference.getString("path")
                require(name.startsWith(prefix)) { "TEST_SCENE_INVALID: Content does not use the paired input root" }
                require(identities[file(name.removePrefix(prefix))] == reference.getString("sha256")) { "TEST_SCENE_INVALID: Content and paired identity differ" }
            }
            fun <T> read(value: ContentResult<T>): T = when (value) {
                is ContentResult.Success -> value.value
                is ContentResult.Failure -> error("TEST_SCENE_INVALID: " + value.error.describe())
            }
            val selected = document.get("contentPack")
            val pack = read(ContentLoader.loadContentPack(file(selected.getString("directory"))))
            val choice = ContentPackChoice(pack, selected.getString("unitId"))
            require(choice.content.units.keys == declared.get("unitVariants").map { it.getString("id") }.toSet()) { "TEST_SCENE_INVALID: Map and content package do not match the scenario" }
            for (reference in declared.get("unitVariants")) {
                require(pack.unitFiles[reference.getString("id")] == file(reference.getString("path").removePrefix(prefix))) {
                    "TEST_SCENE_INVALID: Scenario unit entry differs from the paired content package"
                }
            }
            for (source in choice.content.sources) require(identities[source.path] == source.sha256) { "TEST_SCENE_INVALID: Content has an undeclared source: ${source.path}" }
            val map = read(ContentLoader.loadMap(file(declared.get("map").getString("path").removePrefix(prefix))))
            val coordinate = document.get("coordinateModel"); val time = document.get("timeModel"); val movement = document.get("movementContract")
            val players = document.get("players").map { Player(it.getString("playerId"), PlayerControl.valueOf(it.getString("control").uppercase()), it.getString("teamId", null)) }
            val units = document.get("units").map { UnitSpawn(it.getString("unitId"), it.getString("owner"), it.getString("type"),
                Position(it.get("position").getDouble("x"), it.get("position").getDouble("y")), it.getDouble("heading"), it.getDouble("height", 0.0)) }
            require(players.map { it.playerId }.distinct().size == players.size && units.map { it.unitId }.distinct().size == units.size &&
                units.all { unit -> unit.typeId in choice.content.units && players.any { it.playerId == unit.ownerPlayerId } }) { "TEST_SCENE_INVALID: Invalid starting units or owners" }
            val combat = document.get("combatContract")
            val scene = Scenario(document.getString("scenarioId"), map, choice.content.units, players, units,
                CoordinateModel(coordinate.getString("id"), coordinate.getString("space"), coordinate.getString("xAxis"), coordinate.getString("yAxis"),
                    coordinate.getString("origin"), coordinate.getString("unit"), coordinate.getString("valueType"), coordinate.getString("precision")),
                TimeModel(time.getString("id"), time.getString("deltaUnit"), time.getString("precision"), time.getString("stepRule")),
                MovementContract(movement.getDouble("pathPointRadius"), movement.getDouble("targetRadius")),
                combatContract = CombatContract(combat.getInt("worldSeed"), combat.getInt("initialFrameCounter"),
                    combat.get("unitObjectIds").associate { it.name to it.asLong() }, combat.getLong("nextObjectId")),
                economyContract = EconomyContract(document.get("economyContract").get("initialBalances").associate { it.name to it.asLong() }))
            val result = TestScenarioChoice(seed, root, scene.scenarioId, document.get("testScene").getString("displayName"), choice, scene, identities)
            result.verifyStable()
            return result
        }

        private fun verifyIntents(document: JsonValue) {
            fun matching(value: Boolean) = require(value) { "TEST_SCENE_INTENT_MISMATCH: Client intentions and core actions must describe the same schedule" }
            val actions = document.get("actions").associateBy { it.getString("actionId") }
            val used = linkedSetOf<String>()
            val intents = document.get("intents").toList()
            matching(intents.isNotEmpty() && intents.map { it.getString("intentId") }.distinct().size == intents.size)
            var step = 0L
            for (intent in intents) {
                val kind = intent.getString("kind")
                if (kind != "select") {
                    val action = actions[intent.getString("coreActionId")]
                    matching(action != null)
                    requireNotNull(action)
                    matching(used.add(action.getString("actionId")) && action.getLong("atStep") == step &&
                        action.getString("kind") == kind && action.getString("actor") == intent.getString("actor", "p1"))
                    fun position() = intent.get("position")
                    when (kind) {
                        "load" -> matching(intent.getString("direction") in setOf("INTO", "UP") &&
                            intent.getString("direction") == action.getString("direction") &&
                            intent.getString("targetUnitId") == action.getString("targetUnitId"))
                        "unload" -> matching(intent.get("unloading")?.isBoolean == true && action.get("unloading")?.isBoolean == true &&
                            intent.getBoolean("unloading") == action.getBoolean("unloading"))
                        "submarine-depth" -> matching(intent.get("surface")?.isBoolean == true && action.get("surface")?.isBoolean == true &&
                            intent.getBoolean("surface") == action.getBoolean("surface"))
                        "move", "build" -> matching(position().getDouble("x") == action.get("target").getDouble("x") && position().getDouble("y") == action.get("target").getDouble("y"))
                        "attack" -> matching(intent.getString("targetUnitId") == action.getString("targetUnitId"))
                    }
                    when (kind) {
                        "build" -> matching(intent.getString("typeId") == action.getString("buildingTypeId"))
                        "produce" -> matching(intent.getString("typeId") == action.getString("productionTypeId"))
                        "upgrade" -> matching(intent.getString("upgradeId") == action.getString("upgradeId"))
                        "move", "attack", "stop", "submarine-depth", "load", "unload" -> Unit
                        else -> matching(false)
                    }
                }
                val wait = intent.getLong("waitSteps", 0L)
                matching(wait in 0..600)
                step += wait
            }
            matching(used == actions.keys && step == document.get("termination").get("steps").size.toLong())
        }

        private fun ordinaryHash(path: Path): String {
            require(Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath() == path) { "TEST_SCENE_INVALID: Missing or linked scenario file: $path" }
            return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
