package com.rustedwarfare.reforge.testing

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
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
        val unitRef = identity(content.objectAt("unitVariant"))
        val identities = listOf(mapRef, unitRef) + content.arrayAt("dependencies").map { identity(it.jsonObject) }
        if (identities.map { it.id }.distinct().size != identities.size ||
            identities.map { it.path }.distinct().size != identities.size) throw InputError("Duplicate content identity/path")
        val paths = identities.map { it.path }.toSet()
        verifyXmlReferences(mapRef.path, paths, mutableSetOf())
        val map = unwrap(ContentLoader.loadMap(mapRef.path))
        val unit = unwrap(ContentLoader.loadUnitVariant(unitRef.path))
        if (unit.imagePath.toRealPath() !in paths || map.tileSets.any { it.imagePath.toRealPath() !in paths }) {
            throw InputError("A loaded image dependency has no declared identity")
        }
        val coordinate = input.objectAt("coordinateModel")
        val time = input.objectAt("timeModel")
        val movement = input.objectAt("movementContract")
        if (input.text("angleUnit") != "degree-clockwise-zero-up") throw InputError("Unsupported angleUnit")
        val core = Scenario(
            scenarioId, map, mapOf(unitRef.id to unit),
            input.arrayAt("players").map { value -> value.jsonObject.let {
                Player(it.id("playerId"), when (it.text("control")) {
                    "local" -> PlayerControl.LOCAL
                    "none" -> PlayerControl.NONE
                    else -> throw InputError("Unknown player control")
                })
            } },
            input.arrayAt("units").map { value -> value.jsonObject.let {
                UnitSpawn(it.id("unitId"), it.id("owner"), it.id("type"), position(it.objectAt("position")), it.number("heading"))
            } },
            CoordinateModel(coordinate.id("id"), coordinate.text("space"), coordinate.text("xAxis"), coordinate.text("yAxis"),
                coordinate.text("origin"), coordinate.text("unit"), coordinate.text("valueType"), coordinate.text("precision")),
            TimeModel(time.id("id"), time.text("deltaUnit"), time.text("precision"), time.text("stepRule")),
            MovementContract(movement.number("pathPointRadius"), movement.number("targetRadius")),
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
            when (action.text("kind")) {
                "move" -> position(action.objectAt("target"))
                "select", "stop" -> Unit
                else -> throw InputError("Unsupported action")
            }
        }
        return LoadedScenario(core, actions, steps, identities,
            InputIdentity(scenarioId, source, digest(scenarioText.toByteArray(Charsets.UTF_8))),
            InputIdentity(contract.id("contractId"), contractFile, digest(contractText.toByteArray(Charsets.UTF_8))), coordinate, time,
            input, contract)
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
