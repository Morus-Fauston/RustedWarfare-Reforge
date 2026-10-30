package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.core.knowledge.*
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.*

internal data class InputIdentity(val id: String, val path: Path, val sha256: String) {
    fun json() = obj("id" to s(id), "path" to s(path.toString()), "sha256" to s(sha256))
}
internal data class LoadedScenario(
    val core: Scenario,
    val actions: List<JsonObject>,
    val steps: List<Step>,
    val identities: List<InputIdentity>,
    val scenarioIdentity: InputIdentity,
    val contractIdentity: InputIdentity,
    val coordinate: JsonObject,
    val time: JsonObject,
    val scenarioDocument: JsonObject,
    val contractDocument: JsonObject,
)

internal object ScenarioReader {
    fun load(scenarioPath: Path, contractPath: Path, scenarioSnapshot: Path = scenarioPath, contractSnapshot: Path = contractPath): LoadedScenario = try {
        read(scenarioPath, contractPath, scenarioSnapshot, contractSnapshot)
    } catch (failure: IllegalArgumentException) {
        throw InputError("Invalid scenario/contract field type: ${failure.message}")
    }

    private fun read(scenarioPath: Path, contractPath: Path, scenarioSnapshot: Path, contractSnapshot: Path): LoadedScenario {
        val source = scenarioPath.toAbsolutePath().normalize()
        val contractFile = contractPath.toAbsolutePath().normalize()
        if (!Files.isRegularFile(scenarioSnapshot) || !Files.isRegularFile(contractSnapshot)) throw InputError("Missing scenario or contract file")
        val scenarioText = Files.readString(scenarioSnapshot)
        val contractText = Files.readString(contractSnapshot)
        val input = (json.parseToJsonElement(scenarioText) as? JsonObject ?: throw InputError("Expected scenario object")).also { it.header("scenario") }
        ScenarioDeclaration.validate(input)
        val contract = (json.parseToJsonElement(contractText) as? JsonObject ?: throw InputError("Expected contract object")).also { it.header("behavior-contract") }
        val scenarioId = input.id("scenarioId")
        if (contract.text("scope") != "self-behavior" ||
            contract.arrayAt("scenarioIds").none { it is JsonPrimitive && it.isString && it.content == scenarioId }) {
            throw InputError("Contract does not cover this self-behavior scenario")
        }
        contract.id("contractId")
        contract.arrayAt("rules")
        contract.objectAt("comparison")
        contract.arrayAt("exclusions")
        contract.objectAt("approval")
        val content = input.objectAt("content")
        fun identity(value: JsonObject): InputIdentity {
            val hash = value.text("sha256").lowercase()
            if (!Regex("[a-f0-9]{64}").matches(hash)) throw InputError("Invalid SHA-256")
            val path = source.parent.resolve(value.text("path")).normalize().toAbsolutePath()
            if (!Files.isRegularFile(path) || sha256(path) != hash) throw InputError("Input identity mismatch or missing file: $path")
            return InputIdentity(value.id("id"), path.toRealPath(), hash)
        }
        val mapRef = identity(content.objectAt("map"))
        val unitDeclarations = ScenarioDeclaration.unitReferences(content)
        val unitRefs = unitDeclarations.map(::identity)
        val identities = listOf(mapRef) + unitRefs + content.arrayAt("dependencies").map { identity(it.jsonObject) }
        if (identities.map { it.id }.distinct().size != identities.size ||
            identities.map { it.path }.distinct().size != identities.size) throw InputError("Duplicate content identity/path")
        val paths = identities.map { it.path }.toSet()
        verifyXmlReferences(mapRef.path, paths, mutableSetOf())
        val map = unwrap(ContentLoader.loadMap(mapRef.path))
        input["automaticVision"]?.jsonObject?.objectAt("grid")?.let { grid ->
            if (grid.integer("width") != map.widthTiles.toLong() || grid.integer("height") != map.heightTiles.toLong())
                throw InputError("Declared knowledge grid differs from verified map dimensions")
        }
        val definitions = unitRefs.zip(unitDeclarations).associate { (ref, declaration) ->
            verifyTemplateReferences(ref.path, paths, mutableSetOf())
            val unit = unwrap(when (declaration["loader"]?.jsonPrimitive?.content ?: "variant") {
                "combat" -> ContentLoader.loadCombatUnit(ref.path)
                "factory" -> ContentLoader.loadFactory(ref.path)
                "builder" -> ContentLoader.loadBuilder(ref.path)
                "command-center" -> ContentLoader.loadCommandCenter(ref.path)
                "turret" -> ContentLoader.loadTurretUnit(ref.path)
                "turret-upgrades" -> ContentLoader.loadTurretUnit(ref.path, true)
                "turret-construction" -> ContentLoader.loadTurretUnit(ref.path, true, true)
                "point-defense" -> ContentLoader.loadPointDefense(ref.path)
                "point-defense-construction" -> ContentLoader.loadPointDefense(ref.path, true)
                "economy" -> ContentLoader.loadEconomyUnit(ref.path)
                "economy-upgrades" -> ContentLoader.loadEconomyUnit(ref.path, true)
                else -> ContentLoader.loadUnitVariant(ref.path)
            })
            if (content.containsKey("unitVariants") && ref.id != unit.id) throw InputError("Content type ID differs from loaded definition")
            val images = listOfNotNull(unit.imagePath, unit.combat?.turretImagePath, unit.combat?.deadImagePath,
                unit.durability?.deadImagePath, unit.building?.backImagePath)
            if (images.any { it.toRealPath() !in paths }) throw InputError("A loaded image dependency has no declared identity")
            ref.id to unit
        }
        if (map.tileSets.any { it.imagePath.toRealPath() !in paths }) {
            throw InputError("A loaded image dependency has no declared identity")
        }
        val coordinate = input.objectAt("coordinateModel")
        val time = input.objectAt("timeModel")
        val movement = input.objectAt("movementContract")
        if (input.text("angleUnit") != "degree-clockwise-zero-up") throw InputError("Unsupported angleUnit")
        val core = Scenario(
            scenarioId, map, definitions,
            input.arrayAt("players").map { value -> value.jsonObject.let {
                Player(it.id("playerId"), when (it.text("control")) {
                    "local" -> PlayerControl.LOCAL
                    "none" -> PlayerControl.NONE
                    else -> throw InputError("Unknown player control")
                }, it["teamId"]?.jsonPrimitive?.content)
            } },
            input.arrayAt("units").map { value -> value.jsonObject.let {
                UnitSpawn(it.id("unitId"), it.id("owner"), it.id("type"), position(it.objectAt("position")), it.number("heading"),
                    it["height"]?.let { _ -> it.number("height") } ?: 0.0)
            } },
            CoordinateModel(coordinate.id("id"), coordinate.text("space"), coordinate.text("xAxis"), coordinate.text("yAxis"),
                coordinate.text("origin"), coordinate.text("unit"), coordinate.text("valueType"), coordinate.text("precision")),
            TimeModel(time.id("id"), time.text("deltaUnit"), time.text("precision"), time.text("stepRule")),
            MovementContract(movement.number("pathPointRadius"), movement.number("targetRadius")),
            input["automaticVision"]?.jsonObject?.let { rules -> KnowledgeRules(
                TerrainRetention.valueOf(rules.text("retention")), PathInformationSource.valueOf(rules.text("pathSource")),
                rules.required("shareAlliedVision").jsonPrimitive.boolean) },
            input["combatContract"]?.jsonObject?.let { combat -> CombatContract(combat.integer("worldSeed").toInt(),
                combat.integer("initialFrameCounter").toInt(), combat.objectAt("unitObjectIds").let { ids -> ids.keys.associateWith { ids.integer(it) } },
                combat.integer("nextObjectId")) },
            input["economyContract"]?.jsonObject?.objectAt("initialBalances")?.let { balances -> EconomyContract(balances.keys.associateWith { balances.integer(it) }) },
            input["maintenanceContract"]?.jsonObject?.objectAt("basePrices")?.let { prices -> MaintenanceContract(prices.keys.associateWith { prices.integer(it) }) },
        )
        if (core.units.isEmpty() || core.players.isEmpty()) throw InputError("Empty initial world")
        val termination = input.objectAt("termination")
        if (termination.text("kind") != "explicit-steps" || termination.required("required") != JsonPrimitive(true)) {
            throw InputError("This runner requires an explicit, required step schedule")
        }
        val steps = termination.arrayAt("steps").map { value -> value.jsonObject.let {
            Step(it.integer("stepIndex"), it.number("delta"), it.text("deltaUnit"))
        } }
        if (steps.isEmpty() || steps.withIndex().any { (i, step) ->
            step.stepIndex != i.toLong() || step.delta != 1.0 || step.deltaUnit != core.timeModel.deltaUnit
        }) throw InputError("Expected contiguous steps from zero with explicit delta=1.0 and matching deltaUnit")
        val actions = input.arrayAt("actions").map { it.jsonObject }
        val ids = mutableSetOf<String>()
        actions.forEach { action ->
            if (!ids.add(action.id("actionId"))) throw InputError("Duplicate action ID")
            if (action.integer("atStep") !in 0L until steps.size.toLong()) throw InputError("Action outside schedule")
            action.id("actor"); action.id("unitId")
            ScenarioDeclaration.validateAction(action)
        }
        return LoadedScenario(core, actions, steps, identities,
            InputIdentity(scenarioId, source, digest(scenarioText.toByteArray(Charsets.UTF_8))),
            InputIdentity(contract.id("contractId"), contractFile, digest(contractText.toByteArray(Charsets.UTF_8))), coordinate, time,
            input, contract)
    }

    private fun verifyTemplateReferences(path: Path, allowed: Set<Path>, visited: MutableSet<Path>) {
        if (!visited.add(path)) throw InputError("Cyclic content template")
        var section = ""
        Files.readAllLines(path).forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) section = line.substring(1, line.length - 1).trim()
            if (section == "core" && ':' in line && line.substringBefore(':').trim() == "copyFrom") {
                val parent = path.parent.resolve(line.substringAfter(':').trim()).normalize().toAbsolutePath()
                if (!Files.isRegularFile(parent) || parent.toRealPath() !in allowed) throw InputError("Undeclared content template: $parent")
                verifyTemplateReferences(parent.toRealPath(), allowed, visited)
            }
        }
    }

    private fun verifyXmlReferences(path: Path, allowed: Set<Path>, visited: MutableSet<Path>) {
        if (!visited.add(path)) return
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = Files.newInputStream(path).use { factory.newDocumentBuilder().parse(it) }
        for (tag in listOf("tileset", "image")) {
            val elements = document.getElementsByTagName(tag)
            for (i in 0 until elements.length) {
                val element = elements.item(i) as org.w3c.dom.Element
                if (!element.hasAttribute("source")) continue
                val dependency = path.parent.resolve(element.getAttribute("source")).normalize().toAbsolutePath()
                if (!Files.isRegularFile(dependency) || dependency.toRealPath() !in allowed) {
                    throw InputError("Undeclared or missing XML dependency: $dependency")
                }
                if (tag == "tileset") verifyXmlReferences(dependency.toRealPath(), allowed, visited)
            }
        }
    }

    private fun <T> unwrap(result: ContentResult<T>): T = when (result) {
        is ContentResult.Success -> result.value
        is ContentResult.Failure -> throw CoreFailure("Content rejected: ${result.error}")
    }
}

internal fun position(value: JsonObject) = Position(value.number("x"), value.number("y"))
internal class CoreFailure(message: String) : RuntimeException(message)
