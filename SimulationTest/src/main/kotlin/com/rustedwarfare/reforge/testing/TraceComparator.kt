package com.rustedwarfare.reforge.testing

import java.math.BigDecimal
import kotlinx.serialization.json.*

data class Judgment(val exitCode: Int, val document: JsonObject) {
    val status: String get() = document.getValue("result").jsonPrimitive.content
}

/** Evaluates immutable raw records; the expected trace, if supplied, is a separate fixed input. */
object TraceComparator {
    fun evaluate(scenario: JsonObject, contract: JsonObject, actual: List<JsonObject>,
                 expected: List<JsonObject>? = null): Judgment = try {
        validateContract(scenario, contract)
        val left = expected?.let { validateTrace(scenario, contract, it) }
        val right = validateTrace(scenario, contract, actual)
        val rules = contract.arrayAt("rules")
        if (rules.isEmpty()) throw Incomplete("empty-rules", "No self-behavior assertions declared")
        var evaluations = 0
        var actionIndex = 0
        var observations = 0
        val actionPlan = scenario.arrayAt("actions").map { it.jsonObject }.sortedBy { it.integer("atStep") }
        for ((index, row) in right.withIndex()) {
            if (row.text("kind") == "action-result") {
                val action = row.objectAt("action")
                val declared = actionPlan[actionIndex++]
                val boundary = scenario.objectAt("termination").arrayAt("steps")[observations - 1].jsonObject.required("stepIndex")
                val difference = difference(declared, action, "action")
                    ?: difference(declared.required("atStep"), row.required("atStep"), "atStep")
                    ?: difference(boundary, row.required("atStep"), "input.effectiveBoundary")
                if (difference != null) return divergence(contract, "input.schedule", difference, index, right, left, evaluations)
            }
            if (row.text("kind") == "observation") {
                observations++
                val view = normalize(row).objectAt("observation")
                if (row["phase"] == s("initial")) {
                    for (spawn in scenario.arrayAt("units").map { it.jsonObject }) {
                        val unit = view.arrayAt("units").map { it.jsonObject }.single { it["unitId"] == spawn["unitId"] }
                        val fields = obj("owner" to spawn.required("owner"), "type" to spawn.required("type"),
                            "position" to spawn.required("position"), "heading" to spawn.required("heading"),
                            "speed" to n(0), "angularVelocity" to n(0), "moveTarget" to JsonNull,
                            "pathPoints" to array(emptyList()), "movementState" to s("idle"))
                        for ((key, wanted) in fields) {
                            val mismatch = difference(wanted, unit.required(key), "observation.units.${spawn.id("unitId")}.$key")
                            if (mismatch != null) return divergence(contract, "input.initial-state", mismatch, index, right, left, evaluations)
                        }
                    }
                }
                for (rule in rules.map { it.jsonObject }) {
                    if (!matches(rule["when"] as? JsonObject, row, right, index)) continue
                    for (assertion in rule.arrayAt("assertions").map { it.jsonObject }) {
                        evaluations++
                        val path = assertion.text("path")
                        val value = lookup(view, path)
                        val wanted = assertion["value"] ?: JsonNull
                        val passed = when (assertion.text("operator")) {
                            "equals" -> value != null && difference(wanted, value, path) == null
                            "absent-or-null" -> value == null || value == JsonNull
                            "contains" -> value is JsonArray && value.any {
                                difference(wanted, if (it is JsonObject) it["kind"] ?: it else it, path) == null
                            }
                            else -> false // All operators were validated before collection.
                        }
                        if (value == null && assertion.text("operator") != "absent-or-null") {
                            throw Incomplete("missing-field", "Assertion field is missing: $path")
                        }
                        if (!passed) return divergence(contract, rule.id("ruleId"),
                            Difference("observation.$path", wanted, value ?: JsonNull), index, right, left, evaluations,
                            assertion.text("operator"))
                    }
                }
            }
            if (left != null) {
                val difference = difference(left.getOrNull(index)?.let(::normalize) ?: JsonNull, normalize(row), "")
                if (difference != null) return divergence(contract, "trace.exact", difference, index, right, left, evaluations)
            }
        }
        val untriggered = rules.map { it.jsonObject }.filter { rule -> right.withIndex().none { (i, row) ->
            row.text("kind") == "observation" && matches(rule["when"] as? JsonObject, row, right, i)
        } }
        if (untriggered.isNotEmpty()) throw Incomplete("untriggered-rule", "Rule never evaluated: ${untriggered.map { it.id("ruleId") }}")
        val result = judgment(0, "completed", contract, "exact-self-behavior", evaluations)
        result.copy(document = JsonObject(result.document + ("comparison" to JsonObject(result.document.objectAt("comparison") + mapOf(
            "traceCompared" to b(expected != null), "actualRecordCount" to n(actual.size),
            "expectedRecordCount" to (expected?.size?.let(::n) ?: JsonNull), "observationCount" to n(observations),
            "requiredSteps" to n(scenario.objectAt("termination").arrayAt("steps").size))))))
    } catch (failure: TraceFailure) {
        judgment(failure.code, if (failure.code == 4) "execution-failed" else "evidence-insufficient", contract,
            failure.category, reason = failure.message)
    } catch (failure: InputError) {
        judgment(64, "execution-failed", contract, "input-error", reason = failure.message)
    } catch (failure: IllegalArgumentException) {
        judgment(64, "execution-failed", contract, "input-error", reason = failure.message)
    }

    internal fun validateContract(scenario: JsonObject, contract: JsonObject) {
        ScenarioDeclaration.validate(scenario); contract.header("behavior-contract")
        val products = ProductionDeclaration.products(scenario, contract)
        val buildings = ConstructionDeclaration.buildings(scenario, contract)
        UpgradeDeclaration.conversions(scenario, contract)
        ReclaimDeclaration.removals(scenario, contract)
        if (contract.text("scope") != "self-behavior" || scenario.required("scenarioId") !in contract.arrayAt("scenarioIds")) {
            throw InputError("Only the declared self-behavior scenario can be evaluated")
        }
        contract.id("contractId")
        contract.objectAt("approval").text("source")
        if (contract.objectAt("approval").text("status") !in setOf("tool-fixture-only", "accepted")) {
            throw InputError("Contract approval is pending or unsupported")
        }
        if (s("original-compatibility") !in contract.arrayAt("exclusions")) throw InputError("Self contract must exclude original-compatibility")
        val comparison = contract.objectAt("comparison")
        if (comparison.keys.any { it !in setOf("numberMode", "tolerances", "eventOrder") } ||
            (comparison["eventOrder"] != null && comparison["eventOrder"] != s("strict-record-and-array-order"))) {
            throw InputError("Unsupported comparison policy")
        }
        if (comparison.text("numberMode") != "declared-per-field" || comparison.objectAt("tolerances").isNotEmpty()) {
            throw InputError("Only exact numbers are supported; no numeric tolerance is approved by this tool")
        }
        val rules = contract.arrayAt("rules").map { it.jsonObject }
        if (rules.map { it.id("ruleId") }.distinct().size != rules.size) throw InputError("Duplicate rule ID")
        rules.forEach { rule ->
            if (rule.keys.any { it !in setOf("ruleId", "when", "assertions") }) throw InputError("Unsupported rule field")
            rule["when"]?.let { value ->
                val condition = value.jsonObject
                if (condition.keys.any { it !in setOf("event", "actionKind", "actionId", "unitId", "stepIndex", "phase") })
                    throw InputError("Unsupported rule condition")
                if (condition["event"] != null) {
                    if (condition.text("event") !in setOf("action-accepted", "action-rejected")) throw InputError("Unsupported rule event")
                } else if (condition["stepIndex"] == null || condition["phase"] == null ||
                    condition.keys.any { it in setOf("actionKind", "actionId", "unitId") }) {
                    throw InputError("A sample checkpoint requires phase and stepIndex; action filters require an event")
                }
                condition["actionKind"]?.let { if (condition.text("actionKind") !in ScenarioDeclaration.actionKinds) throw InputError("Unsupported action kind") }
                condition["actionId"]?.let {
                    if (scenario.arrayAt("actions").none { it.jsonObject.id("actionId") == condition.id("actionId") })
                        throw InputError("Checkpoint references an unknown action")
                }
                condition["unitId"]?.let {
                    if ((scenario.arrayAt("units").map { it.jsonObject } + products + buildings).none { it.id("unitId") == condition.id("unitId") })
                        throw InputError("Checkpoint references an unknown unit")
                }
                condition["stepIndex"]?.let {
                    if (condition.integer("stepIndex") !in 0L until scenario.objectAt("termination").arrayAt("steps").size.toLong())
                        throw InputError("Checkpoint outside declared schedule")
                }
                condition["phase"]?.let {
                    if (condition.text("phase") !in setOf("initial", "after-advance") ||
                        (condition["phase"] == s("initial") && condition["stepIndex"] != n(0)))
                        throw InputError("Invalid checkpoint phase or initial boundary")
                }
            }
            if (rule.arrayAt("assertions").isEmpty()) throw InputError("Empty assertion set")
            rule.arrayAt("assertions").forEach { value ->
                val assertion = value.jsonObject
                if (assertion.keys.any { it !in setOf("path", "operator", "value") }) throw InputError("Unsupported assertion field")
                if (!Regex("[A-Za-z0-9_.:-]+").matches(assertion.text("path"))) throw InputError("Invalid assertion path")
                when (assertion.text("operator")) {
                    "equals", "contains" -> assertion.required("value")
                    "absent-or-null" -> Unit
                    else -> throw InputError("Unsupported assertion operator")
                }
                finite(assertion)
            }
        }
    }

    private fun matches(condition: JsonObject?, row: JsonObject, rows: List<JsonObject>, index: Int): Boolean {
        if (condition == null) return true
        val observation = row.objectAt("observation")
        if (condition["phase"] != null && condition["phase"] != row["phase"]) return false
        if (condition["stepIndex"] != null && condition.integer("stepIndex") != observation.integer("stepIndex")) return false
        if (condition["event"] == null) return true
        val status = if (condition.text("event") == "action-accepted") "accepted" else "rejected"
        val actions = rows.take(index).takeLastWhile { it.text("kind") != "observation" }.filter { it.text("kind") == "action-result" }
        return actions.any { it.objectAt("result").text("status") == status &&
            (condition["actionKind"] == null || it.objectAt("action")["kind"] == condition["actionKind"]) &&
            (condition["actionId"] == null || it.objectAt("action")["actionId"] == condition["actionId"]) &&
            (condition["unitId"] == null || it.objectAt("action")["unitId"] == condition["unitId"]) } &&
            observation.arrayAt("actionResults").isNotEmpty()
    }

    private fun validateTrace(scenario: JsonObject, contract: JsonObject, rows: List<JsonObject>): List<JsonObject> {
        if (rows.isEmpty()) throw Incomplete("missing-start", "Empty trace")
        try {
            rows.forEach { row ->
                if (row.integer("schemaVersion") != 1L) throw InputError("Unknown trace schema version")
                finite(row)
                if (row.keys.any { it in setOf("source", "layer", "coordinateModel", "timeModel", "angleUnit") }) {
                    throw Incomplete("reserved-field", "Raw record contains normalized-layer metadata")
                }
            }
            if (rows.first().text("kind") != "run-start") throw Incomplete("missing-start", "Missing run-start")
            rows.first().id("runId"); rows.first().text("startedAt")
            if (rows.last().text("kind") != "run-end") throw Incomplete("missing-completion", "Missing run-end or truncated trace")
            val end = rows.last()
            val status = end.text("status")
            val exit = end.integer("exitCode")
            if (status == "execution-failed" || exit == 4L) throw ExecutionFailure("Recorded execution failed")
            if (exit == 64L) throw InputError("Recorded input error")
            if (status !in setOf("completed", "assertion-failed", "evidence-insufficient", "cancelled") ||
                (status == "completed" && exit != 0L) || (status == "assertion-failed" && exit != 2L) ||
                (status in setOf("evidence-insufficient", "cancelled") && exit != 3L)) {
                throw Incomplete("invalid-completion", "Inconsistent completion status and exit code")
            }
            if (end.required("complete") != b(true) || status == "cancelled") throw Incomplete("incomplete", "Collection is incomplete")
            if (status == "evidence-insufficient") throw Incomplete("producer-evidence-insufficient", "Producer did not establish sufficient evidence")
            val steps = scenario.objectAt("termination").arrayAt("steps").map { it.jsonObject }
            if (steps.isEmpty() || end.integer("requiredSteps") != steps.size.toLong() || end.integer("observedSteps") != steps.size.toLong()) {
                throw Incomplete("missing-step", "Completion counters do not match the independent schedule")
            }
            val samples = mutableSetOf<String>()
            val eventIds = mutableSetOf<String>()
            val actionIds = mutableSetOf<String>()
            val declaredActions = scenario.arrayAt("actions").map { it.jsonObject }
            val unitIds = scenario.arrayAt("units").map { it.jsonObject.id("unitId") }.toSet()
            val products = ProductionDeclaration.products(scenario, contract)
            val buildings = ConstructionDeclaration.buildings(scenario, contract)
            val conversions = UpgradeDeclaration.conversions(scenario, contract)
            val removals = ReclaimDeclaration.removals(scenario, contract)
            val acceptedActions = mutableSetOf<String>()
            var sampleCount = 0
            var time = BigDecimal.ZERO
            var world: String? = null
            val pendingActions = mutableListOf<JsonObject>()
            var pendingEvents = emptyList<JsonObject>()
            var current: JsonObject? = null
            for (row in rows.drop(1).dropLast(1)) {
                if (row.text("kind") != "event" && pendingEvents.isNotEmpty()) throw Incomplete("missing-event", "Missing exported event")
                when (row.text("kind")) {
                    "action-result" -> {
                        if (sampleCount == 0 || sampleCount > steps.size) throw Incomplete("action-order", "Action outside sampling boundary")
                        row.integer("atStep")
                        val action = row.objectAt("action")
                        val id = action.id("actionId")
                        if (!actionIds.add(id) || declaredActions.none { it.id("actionId") == id }) throw Incomplete("action-identity", "Duplicate or unknown action")
                        action.integer("atStep"); action.id("actor"); action.id("unitId"); action.text("kind")
                        if (action.text("kind") == "move") point(action.objectAt("target"))
                        val result = row.objectAt("result")
                        actionResult(result)
                        if (result.text("status") == "accepted") acceptedActions += id
                        if (result.id("actionId") != id) throw Incomplete("action-identity", "Action/result ID mismatch")
                        pendingActions += result
                    }
                    "observation" -> {
                        val observation = row.objectAt("observation")
                        val initial = sampleCount == 0
                        if (row.text("phase") != if (initial) "initial" else "after-advance") throw Incomplete("sample-order", "Wrong sample phase")
                        if (!samples.add(observation.id("sampleId"))) throw Incomplete("duplicate-sample", "Duplicate sample ID")
                        val id = observation.id("worldId")
                        if (world != null && world != id) throw Incomplete("world-identity", "World changed within a run")
                        world = id
                        if (observation.id("scenarioId") != scenario.id("scenarioId")) throw InputError("Scenario identity mismatch in observation")
                        val step = if (initial) 0L else steps.getOrNull(sampleCount - 1)?.integer("stepIndex")
                            ?: throw Incomplete("extra-step", "Unexpected sample beyond schedule")
                        if (observation.integer("stepIndex") != step) throw Incomplete("missing-step", "Missing, duplicate or reordered step")
                        if (!initial) time += decimal(steps[sampleCount - 1].required("delta"))
                        if (decimal(observation.required("simulationTime")).compareTo(time) != 0) throw Incomplete("time-alignment", "Simulation time differs from explicit schedule")
                        val born = ProductionDeclaration.born(products, row)
                        val built = ProductionDeclaration.born(buildings, row)
                        val removed = ProductionDeclaration.born(removals, row, "removalStepIndex")
                        val removedIds = removed.map { it.id("unitId") }.toSet()
                        val expectedIds = (unitIds + born.map { it.id("unitId") } + built.map { it.id("unitId") }) - removedIds
                        if (scenario.containsKey("maintenanceContract")) {
                            val registered = observation.arrayAt("reclaimedUnitIds").map { it.jsonPrimitive.content }
                            if (registered.size != removedIds.size || registered.toSet() != removedIds ||
                                removed.any { it.id("reclaimActionId") !in acceptedActions })
                                throw Incomplete("reclaim-identity", "Missing, premature or unknown reclaim registration")
                            for (removal in removed.filter { it.integer("removalStepIndex") == step && !initial }) {
                                if (observation.arrayAt("events").none { it.jsonObject["kind"] == s("building-reclaimed") &&
                                        it.jsonObject["unitId"] == removal["unitId"] })
                                    throw Incomplete("reclaim-event", "Removal has no formal reclaim event at its declared boundary")
                            }
                        }
                        validatePlayerSamples(scenario, observation, expectedIds)
                        val units = observation.arrayAt("units").map { it.jsonObject }
                        if (units.map { it.id("unitId") }.toSet() != expectedIds || units.size != expectedIds.size) throw Incomplete("missing-object", "Missing, duplicate, early or unknown unit")
                        if (products.isNotEmpty()) {
                            val births = observation.objectAt("production").arrayAt("units").map { it.jsonObject }
                            if (births.map { it.id("unitId") }.toSet() != born.map { it.id("unitId") }.toSet() || births.size != born.size)
                                throw Incomplete("production-identity", "Missing or unexpected formal production result")
                            for (product in born) {
                                val birth = births.single { it.id("unitId") == product.id("unitId") }
                                if (product.id("productionActionId") !in acceptedActions || birth.id("producerId") != product.id("producerId"))
                                    throw Incomplete("production-identity", "Product differs from its accepted producer")
                                if (product.id("unitId") in removedIds) continue
                                val unit = units.single { it.id("unitId") == product.id("unitId") }
                                if (
                                    unit.id("ownerPlayerId") != product.id("owner") ||
                                    unit.id("typeId") != UpgradeDeclaration.typeAt(product.id("unitId"), product.id("typeId"), conversions, row))
                                    throw Incomplete("production-identity", "Product differs from its accepted producer, owner or type")
                            }
                        }
                        if (buildings.isNotEmpty()) {
                            for (building in built) {
                                if (building.id("unitId") in removedIds) continue
                                val unit = units.single { it.id("unitId") == building.id("unitId") }
                                if (building.id("buildActionId") !in acceptedActions || unit.id("ownerPlayerId") != building.id("owner") ||
                                    unit.id("typeId") != UpgradeDeclaration.typeAt(building.id("unitId"), building.id("typeId"), conversions, row))
                                    throw Incomplete("construction-identity", "Building differs from its accepted command")
                            }
                        }
                        for ((id, changes) in conversions.groupBy { it.id("unitId") }) {
                            val unit = units.singleOrNull { it.id("unitId") == id } ?: continue
                            val first = changes.minBy { it.integer("completionStepIndex") }
                            val completed = ProductionDeclaration.born(changes, row, "completionStepIndex")
                            if (completed.any { it.id("upgradeActionId") !in acceptedActions } || unit.id("typeId") !=
                                UpgradeDeclaration.typeAt(id, first.id("sourceTypeId"), conversions, row))
                                throw Incomplete("conversion-identity", "Converted unit differs from its accepted ordinary upgrade")
                        }
                        units.forEach { validateUnitSample(it, scenario) }
                        if (hasConstruction(scenario)) validateConstructionSample(scenario, observation, units, built)
                        val incomeTypes = typesLoadedBy(scenario, setOf("command-center", "economy", "economy-upgrades"))
                        if (incomeTypes.isNotEmpty()) {
                            val incomes = observation.arrayAt("incomeStates").map { it.jsonObject }
                            // Income phases belong to registered objects, including reclaimed ones.
                            // Derive their identities from the independent declarations, never the filtered view.
                            val registered = scenario.arrayAt("units").map { it.jsonObject } + born + built
                            val expectedIncome = registered.filter {
                                val type = (it["typeId"] ?: it.required("type")).jsonPrimitive.content
                                UpgradeDeclaration.typeAt(it.id("unitId"), type, conversions, row) in incomeTypes
                            }.map { it.id("unitId") }.toSet()
                            if (incomes.size != expectedIncome.size || incomes.map { it.id("unitId") }.toSet() != expectedIncome)
                                throw Incomplete("income-state", "Missing or extra income phase")
                            incomes.forEach { it.number("elapsed") }
                        }
                        val upgradeTypes = upgradeTypes(scenario)
                        if (upgradeTypes.isNotEmpty()) {
                            val queues = observation.objectAt("upgrades")
                            val registered = scenario.arrayAt("units").map { it.jsonObject }
                                .filter { it.id("type") in upgradeTypes }.map { it.id("unitId") }.toSet() +
                                built.filter { it.id("typeId") in upgradeTypes }.map { it.id("unitId") }
                            if (queues.keys != registered) throw Incomplete("missing-upgrades", "Missing or extra registered upgrade queue")
                            validateUpgradeItems(queues, true)
                        }
                        if (scenario.containsKey("economyContract")) {
                            val state = observation.objectAt("economy")
                            val balances = state.objectAt("balances")
                            if (balances.keys != scenario.objectAt("economyContract").objectAt("initialBalances").keys)
                                throw Incomplete("missing-economy", "Missing player balance")
                            balances.keys.forEach { balances.integer(it) }
                            if (incomeTypes.isNotEmpty()) {
                                val precise = state.objectAt("preciseBalances")
                                if (precise.keys != balances.keys) throw Incomplete("missing-economy", "Missing precise account")
                                precise.keys.forEach { precise.number(it) }
                            }
                            val queues = state.objectAt("queues")
                            val factoryTypes = typesLoadedBy(scenario, setOf("factory", "command-center"))
                            val registeredFactories = (scenario.arrayAt("units").map { it.jsonObject }.filter { it.id("type") in factoryTypes }.map { it.id("unitId") } +
                                (born + built).filter { it.id("typeId") in factoryTypes }.map { it.id("unitId") }).toSet()
                            if (queues.keys != registeredFactories) throw Incomplete("missing-economy", "Missing or extra registered factory queue")
                            queues.values.forEach { queue -> queue.jsonArray.forEach { element ->
                                val item = element.jsonObject
                                item.id("typeId"); item.id("unitNonce"); item.number("progress"); item.integer("cost"); item.number("rate")
                            } }
                        }
                        val results = observation.arrayAt("actionResults").map { it.jsonObject.also(::actionResult) }
                        if (results != pendingActions) throw Incomplete("action-order", "Observation action results differ from submitted records")
                        pendingActions.clear()
                        pendingEvents = observation.arrayAt("events").map { it.jsonObject.also(::event) }
                        current = observation
                        sampleCount++
                    }
                    "event" -> {
                        if (!eventIds.add(row.id("eventId"))) throw Incomplete("duplicate-event", "Duplicate event ID")
                        if (current == null || row.required("sampleId") != current.required("sampleId") || row.required("stepIndex") != current.required("stepIndex")) {
                            throw Incomplete("event-order", "Event does not reference the current sample")
                        }
                        val value = row.objectAt("event").also(::event)
                        if (pendingEvents.firstOrNull() != value) throw Incomplete("event-order", "Unexpected or reordered exported event")
                        pendingEvents = pendingEvents.drop(1)
                    }
                    else -> throw Incomplete("record-order", "Unknown, repeated or misplaced record kind")
                }
            }
            if (sampleCount != steps.size + 1 || pendingActions.isNotEmpty()) throw Incomplete("missing-step", "Sampling plan or action collection is incomplete")
            if (pendingEvents.isNotEmpty()) throw Incomplete("missing-event", "Missing exported event at end")
            if (actionIds != declaredActions.map { it.id("actionId") }.toSet()) throw Incomplete("missing-action", "Missing declared action result")
            return rows
        } catch (failure: InputError) {
            if (failure.message?.contains("schema version") == true || failure.message?.contains("identity mismatch") == true || failure.message == "Recorded input error") throw failure
            throw Incomplete("missing-or-invalid-field", failure.message ?: "Invalid trace field")
        } catch (failure: IllegalArgumentException) {
            throw Incomplete("missing-or-invalid-field", failure.message ?: "Invalid trace field")
        }
    }

    private fun upgradeTypes(scenario: JsonObject) = typesLoadedBy(scenario, setOf("turret-upgrades", "turret-construction")) +
        typesLoadedBy(scenario, setOf("economy-upgrades")).filter { it.endsWith("T1") || it.endsWith("T2") }

    private fun hasConstruction(scenario: JsonObject) = scenario.containsKey("maintenanceContract") ||
        scenario.arrayAt("actions").any { it.jsonObject.text("kind") == "build" }

    private fun validateConstructionSample(scenario: JsonObject, observation: JsonObject, units: List<JsonObject>, built: List<JsonObject>) {
        val construction = observation.objectAt("construction")
        construction.integer("clock")
        val buildings = construction.arrayAt("buildings").map { it.jsonObject }
        if (buildings.map { it.id("unitId") }.toSet() != built.map { it.id("unitId") }.toSet() || buildings.size != built.size)
            throw Incomplete("construction-identity", "Missing or unexpected constructed result")
        buildings.forEach { element ->
            val building = element.jsonObject
            building.id("unitId"); building.integer("objectId"); building.text("updateKey"); building.number("progress")
        }
        val builderTypes = typesLoadedBy(scenario, setOf("builder"))
        val expectedWorkers = units.filter { it.id("typeId") in builderTypes }.map { it.id("unitId") }.toSet()
        val workers = construction.arrayAt("workers").map { it.jsonObject }
        if (workers.size != expectedWorkers.size || workers.map { it.id("unitId") }.toSet() != expectedWorkers)
            throw Incomplete("construction-workers", "Incomplete worker diagnostics")
        workers.forEach { worker ->
            listOf("elapsed", "preparation").forEach { worker.number(it) }
            listOf("retryTime", "singleNodePathCount", "qBeforeDispatch").forEach { worker.integer(it) }
            listOf("working", "previousTargetActive", "movementRequested").forEach { booleanField(worker, it) }
            listOf("logicalRequestX", "logicalRequestY").forEach { field -> worker.required(field).let { if (it != JsonNull) worker.number(field) } }
            listOf("pendingPathNodeCounts", "consumedPathNodeCounts").forEach { field -> worker.arrayAt(field).forEach { value ->
                obj("value" to value).integer("value")
            } }
        }
        val nonces = construction.objectAt("nonces")
        val expectedNonces = built.groupBy { it.id("owner") }.mapValues { (_, declarations) -> declarations.map { it.id("buildingNonce") }.toSet() }
        if (nonces.keys != expectedNonces.keys) throw Incomplete("construction-nonces", "Missing construction nonce owner")
        nonces.forEach { (owner, values) ->
            val ids = values.jsonArray.map { obj("nonce" to it).id("nonce") }
            if (ids.size != ids.distinct().size || ids.toSet() != expectedNonces.getValue(owner))
                throw Incomplete("construction-nonces", "Incomplete or unknown construction nonce")
        }
    }

    private fun typesLoadedBy(scenario: JsonObject, loaders: Set<String>) =
        ScenarioDeclaration.unitReferences(scenario.objectAt("content")).filter { it["loader"]?.jsonPrimitive?.content in loaders }
            .map { it.id("id") }.toSet()

    private fun validateUnitSample(unit: JsonObject, scenario: JsonObject) {
        if (unit.keys.any { it in setOf("owner", "type", "rawMovementState") }) throw Incomplete("reserved-field", "Raw unit contains normalization aliases")
        unit.id("unitId"); unit.id("ownerPlayerId"); unit.id("typeId"); point(unit.objectAt("position"))
        listOf("heading", "speed", "angularVelocity").forEach { unit.number(it) }
        unit.required("moveTarget").let { if (it != JsonNull) point(it.jsonObject) }
        unit.arrayAt("pathPoints").forEach { point(it.jsonObject) }
        if (hasConstruction(scenario)) {
            unit.number("constructionProgress")
            unit.required("constructionTargetId").let { if (it != JsonNull) unit.id("constructionTargetId") }
        }
        if (scenario.containsKey("maintenanceContract")) {
            val tasks = unit.arrayAt("tasks").map { it.jsonObject }
            if (tasks.size > 29 || tasks.map { it.id("actionId") }.distinct().size != tasks.size)
                throw Incomplete("maintenance-tasks", "Invalid worker task queue")
            tasks.forEach { task ->
                if (task.keys != setOf("kind", "actionId", "target", "targetUnitId", "atStep", "buildingTypeId", "buildingNonce") ||
                    task.text("kind") !in setOf("MOVE", "ATTACK", "PATROL", "BUILD", "CONSTRUCT", "RECLAIM"))
                    throw Incomplete("maintenance-tasks", "Missing or invalid task fields")
                task.id("actionId")
                task.required("target").let { if (it != JsonNull) point(it.jsonObject) }
                task.required("atStep").let { if (it != JsonNull) task.integer("atStep") }
                listOf("targetUnitId", "buildingTypeId", "buildingNonce").forEach { field ->
                    task.required(field).let { if (it != JsonNull) task.id(field) }
                }
            }
        }
        if (unit.text("movementState") !in setOf("IDLE", "MOVING", "DECELERATING", "BLOCKED")) throw Incomplete("invalid-field", "Unknown movement state")
        if (scenario.containsKey("combatContract") || scenario.containsKey("economyContract")) {
            for (field in listOf("health", "maxHealth")) unit.required(field).let { if (it != JsonNull) unit.number(field) }
            if ((unit["health"] == JsonNull) != (unit["maxHealth"] == JsonNull)) throw Incomplete("invalid-field", "Partial durability sample")
            unit.required("attackTargetId").let { if (it != JsonNull) unit.id("attackTargetId") }
            booleanField(unit, "alive")
            unit.required("pointDefense").let { if (it != JsonNull) {
                val defense = it.jsonObject
                defense.number("energy"); booleanField(defense, "exhausted")
            } }
        }
    }

    private fun booleanField(value: JsonObject, field: String) {
        if (value.required(field) !in setOf(b(true), b(false))) throw Incomplete("invalid-field", "Expected boolean $field")
    }

    private fun validateUpgradeItems(queues: JsonObject, host: Boolean) {
        queues.values.forEach { queue -> queue.jsonArray.forEach { element ->
            val item = element.jsonObject
            item.id("actionKey"); item.id("targetTypeId"); item.number("progress")
            if (host) { item.integer("cost"); item.number("rate") }
        } }
    }

    private fun point(value: JsonObject) { value.number("x"); value.number("y") }
    private fun event(value: JsonObject) { value.text("kind"); value.required("unitId").let { if (it != JsonNull) value.id("unitId") } }
    private fun actionResult(value: JsonObject) {
        value.id("actionId")
        when (value.text("status")) {
            "accepted" -> { value.required("queuedForStep").let { if (it != JsonNull) value.integer("queuedForStep") }; value.arrayAt("events").forEach { event(it.jsonObject) } }
            "rejected" -> { value.objectAt("error").text("code"); value.objectAt("error").text("message") }
            else -> throw Incomplete("invalid-field", "Unknown action result status")
        }
    }

    private fun finite(value: JsonElement) {
        when (value) {
            is JsonObject -> value.values.forEach(::finite)
            is JsonArray -> value.forEach(::finite)
            is JsonPrimitive -> if (!value.isString && value != JsonNull && value.booleanOrNull == null) decimal(value)
        }
    }
    private fun decimal(value: JsonElement): BigDecimal {
        val primitive = value as? JsonPrimitive ?: throw InputError("Expected finite JSON number")
        if (primitive.isString || primitive.doubleOrNull?.isFinite() != true) throw InputError("Expected finite JSON number")
        return primitive.content.toBigDecimalOrNull() ?: throw InputError("Expected finite JSON number")
    }

    internal fun normalize(row: JsonObject): JsonObject {
        if (row.text("kind") in setOf("run-start", "run-end")) return obj("kind" to row.required("kind"))
        val observation = row["observation"] as? JsonObject ?: return row
        return JsonObject(row + ("observation" to JsonObject(observation + ("units" to array(observation.arrayAt("units").map {
            val unit = it.jsonObject
            JsonObject(unit - "ownerPlayerId" - "typeId" + mapOf("owner" to unit.required("ownerPlayerId"),
                "type" to unit.required("typeId"), "movementState" to s(unit.text("movementState").lowercase())))
        })))))
    }

    private fun validatePlayerSamples(scenario: JsonObject, observation: JsonObject, knownIds: Set<String>) {
        val declaration = scenario["automaticVision"] as? JsonObject ?: return
        val expected = declaration.arrayAt("observers").map { it.jsonPrimitive.content }.toSet()
        val players = observation["players"] as? JsonObject
            ?: throw Incomplete("missing-player-sample", "Missing declared player observation")
        if (players.keys != expected) throw Incomplete("missing-player-sample", "Missing or extra player observation")
        val grid = declaration.objectAt("grid")
        val expectedWidth = grid.integer("width"); val expectedHeight = grid.integer("height")
        for ((id, element) in players) {
            val player = element.jsonObject
            if (player.text("playerId") != id || listOf("worldId", "stepIndex", "simulationTime").any { player[it] != observation[it] })
                throw Incomplete("player-boundary", "Player observation is from another world or boundary")
            val visible = player.arrayAt("visibleUnitIds").map { it.jsonPrimitive.content }
            if (visible.distinct().size != visible.size || visible.any { it !in knownIds })
                throw Incomplete("player-units", "Unknown or duplicate visible unit")
            player.arrayAt("events"); player.arrayAt("actionResults")
            if (scenario.containsKey("combatContract") || scenario.containsKey("economyContract")) {
                player.required("balance").let { if (scenario.containsKey("economyContract") || it != JsonNull) player.integer("balance") }
                player.required("credits").let { if (it != JsonNull) player.number("credits") }
                val factoryQueues = player.objectAt("factoryQueues")
                val upgradeQueues = player.objectAt("upgradeQueues")
                val units = player.arrayAt("units").map { it.jsonObject }
                if (units.map { it.id("unitId") }.toSet() != visible.toSet() || units.size != visible.size)
                    throw Incomplete("player-units", "Incomplete visible unit samples")
                units.forEach { validateUnitSample(it, scenario) }
                val local = scenario.arrayAt("players").map { it.jsonObject }.single { it.id("playerId") == id }.text("control") == "local"
                val factoryTypes = typesLoadedBy(scenario, setOf("factory", "command-center"))
                val factories = units.filter { local && it.id("ownerPlayerId") == id && it["alive"] == b(true) && it.id("typeId") in factoryTypes }
                    .map { it.id("unitId") }.toSet()
                if (factoryQueues.keys != factories) throw Incomplete("player-queues", "Missing or extra authorized factory queue")
                factoryQueues.values.forEach { queue -> queue.jsonArray.forEach { element ->
                    val item = element.jsonObject
                    item.id("typeId"); item.number("progress")
                } }
                val upgradeTypes = upgradeTypes(scenario)
                val upgrades = units.filter { local && it.id("ownerPlayerId") == id && it["alive"] == b(true) && it.id("typeId") in upgradeTypes }
                    .map { it.id("unitId") }.toSet()
                if (upgradeQueues.keys != upgrades) throw Incomplete("player-queues", "Missing or extra authorized upgrade queue")
                validateUpgradeItems(upgradeQueues, false)
            }
            val knowledge = player.objectAt("knowledge")
            val width = knowledge.integer("width"); val height = knowledge.integer("height")
            val cells = knowledge.objectAt("cells")
            if (width != expectedWidth || height != expectedHeight || width * height != cells.size.toLong() ||
                cells.keys != (0 until height).flatMap { r -> (0 until width).map { c -> "c${c}r${r}" } }.toSet())
                throw Incomplete("player-knowledge", "Incomplete player knowledge grid")
            cells.values.forEach { cell ->
                val value = cell.jsonObject
                if (value.required("visible").jsonPrimitive.booleanOrNull == null ||
                    value.required("lastKnownBlocked").let { it != JsonNull && it.jsonPrimitive.booleanOrNull == null })
                    throw Incomplete("player-knowledge", "Invalid player knowledge cell")
                value.required("lastKnownGroundGid")
            }
        }
    }

    private fun lookup(value: JsonElement, path: String): JsonElement? = when (value) {
        is JsonObject -> {
            val key = path.substringBefore('.')
            val next = value[key]
            if ('.' !in path) next
            else lookup(next ?: throw Incomplete("unresolved-path", "Missing path parent: $path"), path.substringAfter('.'))
        }
        is JsonArray -> {
            // IDs may contain dots. Bind the longest complete ID before resolving its field path.
            val candidates = value.filterIsInstance<JsonObject>().mapNotNull { unit ->
                (unit["unitId"] as? JsonPrimitive ?: unit["actionId"] as? JsonPrimitive)?.content?.let { id ->
                    if (path == id || path.startsWith("$id.")) id to unit else null
                }
            }
            val (id, unit) = candidates.maxByOrNull { it.first.length }
                ?: throw Incomplete("unresolved-path", "No declared object resolves: $path")
            if (path == id) unit else lookup(unit, path.removePrefix("$id."))
        }
        else -> throw Incomplete("unresolved-path", "Cannot traverse scalar or null: $path")
    }
    private data class Difference(val path: String, val expected: JsonElement, val actual: JsonElement)
    private fun difference(expected: JsonElement, actual: JsonElement, path: String): Difference? {
        if (expected is JsonObject && actual is JsonObject) {
            for (key in (expected.keys + actual.keys).sorted()) {
                val a = expected[key]; val b = actual[key]
                if (a == null || b == null) return Difference("$path.$key".trimStart('.'), a ?: JsonNull, b ?: JsonNull)
                difference(a, b, "$path.$key".trimStart('.'))?.let { return it }
            }
            return null
        }
        if (expected is JsonArray && actual is JsonArray) {
            for (index in 0 until maxOf(expected.size, actual.size)) {
                val a = expected.getOrNull(index); val b = actual.getOrNull(index)
                if (a == null || b == null) return Difference("$path.$index", a ?: JsonNull, b ?: JsonNull)
                difference(a, b, "$path.$index")?.let { return it }
            }
            return null
        }
        if (expected is JsonPrimitive && actual is JsonPrimitive && !expected.isString && !actual.isString &&
            expected.doubleOrNull != null && actual.doubleOrNull != null) {
            return if (decimal(expected).compareTo(decimal(actual)) == 0) null else Difference(path, expected, actual)
        }
        return if (expected == actual) null else Difference(path, expected, actual)
    }

    private fun divergence(contract: JsonObject, rule: String, difference: Difference, index: Int,
                           actual: List<JsonObject>, expected: List<JsonObject>?, count: Int, operator: String = "exact"): Judgment {
        fun context(rows: List<JsonObject>): JsonArray {
            val before = (0 until minOf(index, rows.size)).lastOrNull { rows[it]["kind"] == s("observation") } ?: 0
            val after = ((index + 1) until rows.size).firstOrNull { rows[it]["kind"] == s("observation") } ?: rows.lastIndex
            val end = ((after + 1) until rows.size).firstOrNull { rows[it]["kind"] != s("event") }?.minus(1) ?: rows.lastIndex
            return array((before..end).map { i ->
            obj("line" to n(i + 1), "raw" to rows[i], "normalized" to normalize(rows[i]))
            })
        }
        val first = obj("line" to n(index + 1), "rawActual" to actual[index], "normalizedActual" to normalize(actual[index]),
            "rawExpected" to expected?.getOrNull(index), "normalizedExpected" to expected?.getOrNull(index)?.let(::normalize),
            "actualContext" to context(actual), "expectedContext" to expected?.let(::context), "operator" to s(operator))
        val base = judgment(2, "assertion-failed", contract, "divergence", count)
        return base.copy(document = JsonObject(base.document + mapOf("firstDivergence" to first, "ruleId" to s(rule),
            "path" to s(difference.path), "expected" to difference.expected, "actual" to difference.actual)))
    }

    internal fun judgment(code: Int, status: String, contract: JsonObject?, category: String, count: Int = 0, reason: String? = null): Judgment =
        Judgment(code, record("judgment", "result" to s(status), "exitCode" to n(code), "firstDivergence" to JsonNull,
            "ruleId" to JsonNull, "path" to JsonNull, "expected" to JsonNull, "actual" to JsonNull,
            "comparison" to obj("category" to s(category), "scope" to s("self-behavior"), "schemaVersion" to n(1),
                "contractId" to contract?.get("contractId"), "numericMode" to s("exact-decimal-value"),
                "identityPolicy" to s("strict-stable-ids-no-remapping"),
                "traceScope" to s("all-action-observation-event-fields-in-record-order"),
                "excludedTraceFields" to array(listOf(s("run-start payload"), s("run-end payload (validated separately)"))),
                "eventOrder" to s("strict-record-and-array-order"), "ruleEvaluations" to n(count), "originalCompatibility" to b(false)),
            "evidenceRefs" to array(listOf(s("reforge/raw.jsonl"), s("reforge/normalized.jsonl"))), "reason" to reason?.let(::s)))
}

private open class TraceFailure(val code: Int, val category: String, message: String) : RuntimeException(message)
private class Incomplete(category: String, message: String) : TraceFailure(3, category, message)
private class ExecutionFailure(message: String) : TraceFailure(4, "execution-failed", message)
