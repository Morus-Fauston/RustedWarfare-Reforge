package com.rustedwarfare.reforge.testing.p002

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.time.Instant
import java.util.UUID

/** Formal trusted-host projection suite; it deliberately does not manufacture vision geometry. */
object P002KnowledgeSuite {
    data class Result(val exitCode: Int, val output: Path, val caseCount: Int)
    data class Verdict(val exitCode: Int, val reason: String)
    private val required = listOf("K01-flat", "K01-hidden", "K02-memory", "K03-hardcore", "K04-owner",
        "K05-offpath", "K06-repeat", "K07-replan", "K08-unreachable", "K09-collision", "K10-reopen")

    fun run(repoRoot: Path, suiteRelative: String, outputRoot: Path, command: List<String>): Result {
        val root = repoRoot.toAbsolutePath().normalize()
        val output = outputRoot.resolve("run-${Instant.now().toString().replace(":", "")}-${UUID.randomUUID().toString().take(8)}")
        Files.createDirectories(outputRoot)
        Files.createDirectory(output)
        Files.createDirectory(output.resolve("inputs"))
        var count = 0
        var failure: String? = null
        var code = 0
        val artifacts = artifacts()
        try {
            val suitePath = relative(root, suiteRelative)
            val suite = readObject(suitePath).also { it.header("p002-knowledge-suite") }
            validateSuite(suite)
            val scenarioPath = checkedInput(root, suite.objectAt("scenario"))
            val contractPath = checkedInput(root, suite.objectAt("contract"))
            Files.copy(suitePath, output.resolve("inputs/suite.json"))
            Files.copy(scenarioPath, output.resolve("inputs/scenarios.json"))
            Files.copy(contractPath, output.resolve("inputs/contract.json"))
            val scenarios = readObject(scenarioPath)
            val contract = readObject(contractPath)
            validateInputs(scenarios, contract)
            for (case in scenarios.arrayAt("cases").map { it.jsonObject }) {
                val id = case.id("caseId")
                val directory = output.resolve(id)
                Files.createDirectory(directory)
                try {
                    val rows = capture(scenarios.objectAt("model"), case)
                    lines(directory.resolve("raw.jsonl"), rows)
                    lines(directory.resolve("normalized.jsonl"), rows.map(::normalize))
                    count++
                } catch (error: Exception) {
                    document(directory.resolve("execution-failure.json"), obj("message" to s("${error.javaClass.simpleName}: ${error.message}")))
                    code = 4
                    failure = error.message
                }
            }
            checkedInput(root, suite.objectAt("scenario"))
            checkedInput(root, suite.objectAt("contract"))
            require(sha256(suitePath) == sha256(output.resolve("inputs/suite.json"))) { "Suite changed during run" }
        } catch (error: InputError) { code = 64; failure = error.message }
          catch (error: Exception) { code = 4; failure = "${error.javaClass.simpleName}: ${error.message}" }
        document(output.resolve("execution.json"), record("p002-knowledge-execution", "exitCode" to n(code),
            "complete" to b(code == 0 && count == required.size), "caseCount" to n(count), "failure" to failure?.let(::s)))
        document(output.resolve("manifest.json"), record("p002-knowledge-manifest", "scope" to s("trusted-host-projection"),
            "suiteSha256" to shaOrNull(output.resolve("inputs/suite.json")), "command" to array(command.map(::s)),
            "createdAt" to s(Instant.now().toString()), "repoRoot" to s(root.toString()),
            "environment" to obj("os" to s(System.getProperty("os.name")), "java" to s(System.getProperty("java.runtime.version"))),
            "artifacts" to artifacts, "files" to inventory(output)))
        val verdict = verify(output)
        document(output.resolve("verdict.json"), obj("exitCode" to n(verdict.exitCode), "reason" to s(verdict.reason)))
        // Verdict is derived; keep it in inventory without making it the oracle.
        val manifest = readObject(output.resolve("manifest.json"))
        Files.writeString(output.resolve("manifest.json"), JsonObject(manifest + ("files" to inventory(output))).toString() + "\n")
        return Result(verdict.exitCode, output, count)
    }

    fun verify(directory: Path): Verdict {
        val root = directory.toAbsolutePath().normalize()
        val issues = mutableListOf<Verdict>()
        fun issue(code: Int, message: String) { issues += Verdict(code, message) }
        try {
            val manifest = readObject(root.resolve("manifest.json")).also { it.header("p002-knowledge-manifest") }
            val refs = manifest.arrayAt("files").map { it.jsonObject }
            val names = refs.map { it.text("path") }
            val actual = Files.walk(root).use { stream -> stream.filter { Files.isRegularFile(it) && it.fileName.toString() != "manifest.json" }
                .map { root.relativize(it).toString().replace('\\', '/') }.toList().toSet() }
            if (names.size != names.distinct().size || names.toSet() != actual) issue(3, "Evidence inventory incomplete or duplicated")
            for (ref in refs) {
                val path = relative(root, ref.text("path"))
                if (!Files.isRegularFile(path) || sha256(path) != ref.text("sha256")) issue(if (ref.text("path").startsWith("inputs/")) 64 else 3, "Evidence identity mismatch: ${ref.text("path")}")
            }
            if (manifest.required("artifacts") != artifacts()) issue(64, "Actual core/tool/runtime artifact identity differs")
            val suitePath = root.resolve("inputs/suite.json")
            if (sha256(suitePath) != manifest.text("suiteSha256")) issue(64, "Suite snapshot identity differs")
            val suite = readObject(suitePath)
            validateSuite(suite)
            for ((key, name) in listOf("scenario" to "scenarios.json", "contract" to "contract.json")) {
                if (sha256(root.resolve("inputs/$name")) != suite.objectAt(key).text("sha256")) issue(64, "Frozen $key identity differs")
            }
            val scenarios = readObject(root.resolve("inputs/scenarios.json"))
            val contract = readObject(root.resolve("inputs/contract.json"))
            validateInputs(scenarios, contract)
            val execution = readObject(root.resolve("execution.json"))
            if (execution.integer("exitCode") != 0L || execution.required("complete") != b(true)) issue(4, "Execution did not complete successfully")
            if (execution.integer("caseCount") != required.size.toLong()) issue(3, "Missing case execution")
            val allRows = linkedMapOf<String, List<JsonObject>>()
            for (case in scenarios.arrayAt("cases").map { it.jsonObject }) {
                val id = case.id("caseId")
                try {
                    val raw = readLines(root.resolve("$id/raw.jsonl"))
                    val normalized = readLines(root.resolve("$id/normalized.jsonl"))
                    if (normalized != raw.map(::normalize)) issue(3, "$id raw/normalized correspondence differs")
                    val expected = expectedLabels(case)
                    if (normalized.map { it.text("label") } != expected) issue(3, "$id missing/reordered operation or simulation step")
                    if (normalized.lastOrNull()?.text("kind") != "p002-completion" || normalized.lastOrNull()?.get("status") != s("completed")) issue(4, "$id missing/failed completion")
                    val operations = expectedOperations(case)
                    var generation = 0L
                    var nextStep = 0L
                    for ((index, row) in normalized.withIndex()) {
                        try {
                            val completion = index == normalized.lastIndex && expected.getOrNull(index) == "completion"
                            row.header(if (completion) "p002-completion" else "p002-sample")
                            if (row.text("caseId") != id) issue(3, "$id row case identity differs")
                            if (completion) continue
                            val label = row.text("label")
                            if (row.text("operation") != operations[label]) issue(3, "$id operation differs from frozen plan at $label")
                            row.text("operationResult")
                            if (row.text("operation") == "reopen") { generation++; nextStep = 0L }
                            if (row.integer("generation") != generation) issue(3, "$id invalid world generation")
                            if (row.text("operation") == "advance") {
                                if (row.objectAt("observation").integer("stepIndex") != nextStep) issue(3, "$id noncontiguous simulation step")
                                nextStep++
                            }
                            if (row.text("operation") == "close") {
                                if (row.required("observation") != JsonNull || row.required("knowledge") != JsonNull ||
                                    row.integer("searchCount") != 0L || row.arrayAt("searches").isNotEmpty()) issue(3, "$id invalid closed sample")
                            } else {
                                if (row.objectAt("observation").number("simulationTime") != nextStep.toDouble()) issue(3, "$id simulation time differs from explicit steps")
                                validateSample(row, scenarios.objectAt("model"), case, ::issue)
                            }
                            if (row.required("error") != JsonNull) issue(4, "$id unexpected operation failure at $label")
                        } catch (error: Exception) { issue(3, "$id missing or invalid sample field: ${error.message}") }
                    }
                    allRows[id] = normalized
                    for (assertion in contract.objectAt("assertions").arrayAt(id).map { it.jsonObject }) {
                        val row = normalized.singleOrNull { it.text("label") == assertion.text("label") }
                        if (row == null) issue(3, "$id missing assertion sample ${assertion.text("label")}")
                        else try {
                            val value = pointer(row, assertion.text("pointer"))
                            if (value != assertion.required("expected")) issue(2, "$id ${assertion.text("label")}${assertion.text("pointer")}: expected=${assertion.required("expected")} actual=$value")
                        } catch (error: Exception) { issue(3, "$id missing asserted field: ${error.message}") }
                    }
                } catch (error: Exception) { issue(3, "$id unreadable/truncated evidence: ${error.message}") }
            }
            for (pair in contract.arrayAt("pairedPaths").map { it.jsonObject }) {
                fun path(key: String): JsonElement? = allRows[pair.text(key)]?.singleOrNull { it["label"] == pair["label"] }
                    ?.let { pointer(it, "/observation/units/0/pathPoints") }
                val left = path("leftCase"); val right = path("rightCase")
                if (left == null || right == null) issue(3, "Missing cross-truth pair evidence")
                else if (left != right) issue(2, "Same knowledge/different hidden truth produced different planning")
            }
        } catch (error: InputError) { issue(64, "Invalid frozen input/archive: ${error.message}") }
          catch (error: Exception) { issue(3, "Unreadable archive: ${error.javaClass.simpleName}: ${error.message}") }
        val priority = listOf(64, 4, 3, 2)
        return priority.firstNotNullOfOrNull { code -> issues.filter { it.exitCode == code }.takeIf { it.isNotEmpty() }
            ?.let { Verdict(code, it.take(8).joinToString("; ") { value -> value.reason }) } } ?: Verdict(0, "All eleven trusted-host cases and pair relation passed")
    }

    private fun capture(model: JsonObject, fixture: JsonObject): List<JsonObject> {
        val scenario = scenario(model, fixture)
        val context = (SimulationContext.initialize(scenario) as? InitResult.Accepted)?.context ?: throw InputError("Core initialization rejected")
        val rows = mutableListOf<JsonObject>()
        var old = context.world
        var generation = 0
        var step = 0L
        var closed = false
        fun enable() {
            context.enableKnowledge(context.world, KnowledgeRules(TerrainRetention.valueOf(fixture.text("retention")), PathInformationSource.PLAYER_KNOWLEDGE, false))
            context.enablePathDiagnostics(context.world)
        }
        fun sample(label: String, operation: String, result: String = "accepted") {
            val searches = if (closed) emptyList() else context.drainPathDiagnostics(context.world)
            val knowledge = if (closed) JsonNull else JsonObject(listOf("p1", "p2").associateWith { player ->
                val value = context.knowledgeSnapshot(context.world, player)
                obj("revision" to n(value.revision), "width" to n(value.width), "height" to n(value.height),
                    "cells" to array(value.cells.map { obj("visible" to b(it.visible), "lastKnownBlocked" to it.lastKnownBlocked?.let(::b)) }))
            })
            rows += record("p002-sample", "caseId" to s(fixture.text("caseId")), "label" to s(label),
                "operation" to s(operation), "generation" to n(generation), "operationResult" to s(result), "error" to JsonNull,
                "observation" to if (closed) JsonNull else context.observe().json(), "knowledge" to knowledge,
                "searchCount" to n(searches.size), "searches" to array(searches.map { value -> obj(
                    "actionId" to s(value.actionId), "unitId" to s(value.unitId), "reason" to s(value.reason.name),
                    "outcome" to s(value.outcome.name), "actualSearchCalls" to n(value.actualSearchCalls),
                    "usedPlayerKnowledge" to b(value.usedPlayerKnowledge), "executionStep" to value.executionStep?.let(::n),
                    "preparationNanos" to n(value.inputPreparationNanos), "searchNanos" to n(value.searchNanos)) }))
        }
        try {
            enable(); sample("initial", "initialize")
            for (operation in fixture.arrayAt("operations").map { it.jsonObject }) {
                val label = operation.text("label")
                when (val kind = operation.text("kind")) {
                    "move" -> {
                        val target = operation.objectAt("target")
                        val result = context.submit(MoveAction(label, operation.text("actor"), "u1", Position(target.number("x"), target.number("y")), context.world))
                        sample(label, kind, if (result is ActionResult.Accepted) "accepted" else "rejected")
                    }
                    "project" -> {
                        context.acceptVisibleTerrain(context.world, operation.text("player"), operation.arrayAt("cells").map { cell -> cell.jsonObject.let {
                            VisibleTerrainCell(it.integer("column").toInt(), it.integer("row").toInt(), it.required("blocked").jsonPrimitive.boolean)
                        } })
                        sample(label, kind)
                    }
                    "advance" -> repeat(operation.integer("count").toInt()) {
                        context.advance(Step(step++, 1.0, "sim-tick")); sample("$label:$it", kind)
                    }
                    "close" -> { old = context.world; context.close(); closed = true; sample(label, kind) }
                    "reopen" -> {
                        if (context.reopen(scenario) !is InitResult.Accepted) throw InputError("Core reopen rejected")
                        closed = false; generation++; step = 0; enable(); sample(label, kind)
                    }
                    "stale-project" -> {
                        val result = try { context.acceptVisibleTerrain(old, "p1", emptyList()); "unexpected-accepted" }
                            catch (failure: KnowledgeException) { failure.code }
                        sample(label, kind, result)
                    }
                    else -> throw InputError("Unsupported fixture operation: $kind")
                }
            }
            rows += record("p002-completion", "caseId" to s(fixture.text("caseId")), "label" to s("completion"), "status" to s("completed"))
            return rows
        } finally { context.close() }
    }

    private fun scenario(model: JsonObject, fixture: JsonObject): Scenario {
        val w = model.integer("width").toInt(); val h = model.integer("height").toInt()
        val blocked = BooleanArray(w * h)
        fixture.arrayAt("blocked").forEach { cell -> val xy = cell.jsonArray; blocked[xy[1].jsonPrimitive.int * w + xy[0].jsonPrimitive.int] = true }
        val movement = model.objectAt("movement")
        val radius = model.number("radius")
        val unit = UnitDefinition("tank", "CustomUnitMetadata", radius, radius, 1, Path.of("fixture-not-rendered.png"), 1.0,
            MovementProfile(MovementType.LAND, movement.number("speed"), movement.number("acceleration"), movement.number("deceleration"),
                movement.number("turnSpeed"), movement.number("turnAcceleration"), false, false), emptySet())
        val start = model.objectAt("start")
        return Scenario(fixture.text("caseId"), MapDefinition(w, h, model.integer("tileWidth").toInt(), model.integer("tileHeight").toInt(),
            emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked)), mapOf("tank" to unit),
            listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
            listOf(UnitSpawn("u1", "p1", "tank", Position(start.number("x"), start.number("y")), model.number("heading"))),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"),
            MovementContract(model.number("pathPointRadius"), model.number("targetRadius")))
    }

    private fun validateSample(row: JsonObject, model: JsonObject, fixture: JsonObject, issue: (Int, String) -> Unit) {
        val id = fixture.text("caseId")
        val observation = row.objectAt("observation")
        val units = observation.arrayAt("units").map { it.jsonObject }
        if (observation.text("scenarioId") != id || units.map { it.text("unitId") } != listOf("u1")) issue(3, "$id missing/extra unit identity")
        val unit = units.single()
        if (unit.text("ownerPlayerId") != "p1" || unit.text("typeId") != "tank") issue(3, "$id unit identity/ownership differs")
        for (field in listOf("heading", "speed", "angularVelocity")) unit.number(field)
        unit.arrayAt("pathPoints").forEach { it.jsonObject.number("x"); it.jsonObject.number("y") }
        unit.required("moveTarget"); unit.text("movementState")
        observation.number("simulationTime"); observation.arrayAt("events"); observation.arrayAt("actionResults")
        val position = unit.objectAt("position")
        val x = position.number("x"); val y = position.number("y"); val radius = model.number("radius")
        if (row.text("operation") in listOf("initialize", "reopen") &&
            (position != model.objectAt("start") || unit.number("heading") != model.number("heading") ||
                unit.number("speed") != 0.0 || unit.number("angularVelocity") != 0.0 || unit.required("moveTarget") != JsonNull ||
                unit.arrayAt("pathPoints").isNotEmpty() || unit.text("movementState") != "IDLE")) issue(2, "$id initial/reopened state differs from declared fixture")
        for (cell in fixture.arrayAt("blocked")) {
            val xy = cell.jsonArray
            val left = xy[0].jsonPrimitive.int * model.integer("tileWidth").toDouble()
            val top = xy[1].jsonPrimitive.int * model.integer("tileHeight").toDouble()
            val dx = x - x.coerceIn(left, left + model.integer("tileWidth"))
            val dy = y - y.coerceIn(top, top + model.integer("tileHeight"))
            if (dx * dx + dy * dy <= radius * radius) issue(2, "$id truth collision penetration at ${row.text("label")}")
        }
        val knowledge = row.objectAt("knowledge")
        if (knowledge.keys != setOf("p1", "p2")) issue(3, "$id player knowledge incomplete")
        for (player in listOf("p1", "p2")) {
            val value = knowledge.objectAt(player)
            if (value.integer("width") != model.integer("width") || value.integer("height") != model.integer("height") ||
                value.arrayAt("cells").size.toLong() != model.integer("width") * model.integer("height") || value.integer("revision") < 0) issue(3, "$id knowledge dimensions/revision incomplete")
            value.arrayAt("cells").forEach { cell ->
                val c = cell.jsonObject
                c.required("visible").jsonPrimitive.boolean
                if (c.required("lastKnownBlocked") != JsonNull) c.required("lastKnownBlocked").jsonPrimitive.boolean
            }
        }
        if (row.integer("searchCount") != row.arrayAt("searches").size.toLong()) issue(3, "$id search count incomplete")
        row.arrayAt("searches").forEach { search -> val v = search.jsonObject
            v.text("actionId"); v.text("unitId"); v.text("reason"); v.text("outcome"); v.integer("actualSearchCalls"); v.required("usedPlayerKnowledge").jsonPrimitive.boolean; v.required("executionStep")
        }
    }

    private fun normalize(row: JsonObject): JsonObject {
        if (row["kind"] != s("p002-sample")) return row
        val observation = row["observation"]?.let { if (it == JsonNull) it else JsonObject(it.jsonObject - setOf("worldId", "sampleId")) } ?: throw InputError("Missing observation")
        val searches = row.arrayAt("searches").map { JsonObject(it.jsonObject - setOf("preparationNanos", "searchNanos")) }
        return JsonObject(row + mapOf("observation" to observation, "searches" to array(searches)))
    }

    private fun validateSuite(suite: JsonObject) {
        suite.header("p002-knowledge-suite")
        if (suite.text("scope") != "trusted-host-projection" || suite.arrayAt("requiredCases").map { it.jsonPrimitive.content } != required) throw InputError("Suite scope/coverage differs")
    }

    private fun validateInputs(scenarios: JsonObject, contract: JsonObject) {
        scenarios.header("p002-knowledge-fixtures"); contract.header("p002-knowledge-contract")
        if (scenarios.text("scope") != "trusted-host-projection" || contract.text("scope") != "trusted-host-projection" ||
            contract.required("originalCompatibility") != b(false) || contract.integer("numericTolerance") != 0L) throw InputError("Fixture scope/contract policy differs")
        if (scenarios.arrayAt("cases").map { it.jsonObject.text("caseId") } != required || contract.objectAt("assertions").keys != required.toSet()) throw InputError("Fixture/contract coverage differs")
        for (case in scenarios.arrayAt("cases").map { it.jsonObject }) {
            val labels = expectedLabels(case)
            if (labels.size != labels.distinct().size) throw InputError("Duplicate fixture operation label")
        }
    }

    private fun expectedLabels(case: JsonObject): List<String> = listOf("initial") + case.arrayAt("operations").flatMap { operation -> operation.jsonObject.let {
        if (it.text("kind") == "advance") {
            val count = it.integer("count")
            if (count !in 1..1000) throw InputError("Invalid bounded step count")
            (0 until count.toInt()).map { index -> "${it.text("label")}:$index" }
        } else listOf(it.text("label"))
    } } + "completion"

    private fun expectedOperations(case: JsonObject): Map<String, String> = buildMap {
        put("initial", "initialize")
        for (value in case.arrayAt("operations")) {
            val operation = value.jsonObject
            if (operation.text("kind") == "advance") repeat(operation.integer("count").toInt()) { put("${operation.text("label")}:$it", "advance") }
            else put(operation.text("label"), operation.text("kind"))
        }
    }

    private fun pointer(value: JsonElement, pointer: String): JsonElement {
        if (!pointer.startsWith('/')) throw InputError("Expected JSON pointer")
        return pointer.substring(1).split('/').fold(value) { current, token ->
            val key = token.replace("~1", "/").replace("~0", "~")
            when (current) { is JsonObject -> current.required(key); is JsonArray -> current[key.toInt()]; else -> throw InputError("Missing pointer $pointer") }
        }
    }

    private fun checkedInput(root: Path, ref: JsonObject): Path {
        val path = relative(root, ref.text("path"))
        if (!Regex("[a-f0-9]{64}").matches(ref.text("sha256")) || !Files.isRegularFile(path) || sha256(path) != ref.text("sha256")) throw InputError("Frozen input identity mismatch: $path")
        return path
    }
    private fun relative(root: Path, name: String): Path {
        val value = Path.of(name)
        if (value.isAbsolute) throw InputError("Expected relative path")
        return root.resolve(value).normalize().also { if (!it.startsWith(root)) throw InputError("Path escapes root") }
    }
    private fun readLines(path: Path): List<JsonObject> {
        val text = Files.readString(path)
        if (!text.endsWith("\n")) throw CaptureError("Truncated JSONL (missing final newline)")
        return text.lineSequence().filter { it.isNotEmpty() }.map { json.parseToJsonElement(it).jsonObject }.toList()
    }
    private fun artifacts(): JsonArray = array(listOf(P002KnowledgeSuite::class.java, SimulationContext::class.java, Json::class.java,
        kotlinx.serialization.KSerializer::class.java, kotlin.KotlinVersion::class.java)
        .map { Path.of(it.protectionDomain.codeSource.location.toURI()) }.distinct().map { path ->
            val hash = if (Files.isDirectory(path)) Files.walk(path).use { stream -> digest(stream.filter { Files.isRegularFile(it) }.sorted()
                .map { "${path.relativize(it)}:${sha256(it)}" }.toList().joinToString("\n").toByteArray(Charsets.UTF_8)) } else sha256(path)
            obj("path" to s(path.toString()), "sha256" to s(hash))
        })
    private fun inventory(root: Path) = Files.walk(root).use { stream -> array(stream.filter { Files.isRegularFile(it) && it.fileName.toString() != "manifest.json" }.sorted()
        .map { obj("path" to s(root.relativize(it).toString().replace('\\', '/')), "sha256" to s(sha256(it))) }.toList()) }
    private fun shaOrNull(path: Path): JsonElement = if (Files.isRegularFile(path)) s(sha256(path)) else JsonNull
    private fun document(path: Path, value: JsonElement) { Files.writeString(path, value.toString() + "\n", CREATE_NEW) }
    private fun lines(path: Path, rows: List<JsonObject>) { Files.writeString(path, rows.joinToString("\n", postfix = "\n"), CREATE_NEW) }
}
