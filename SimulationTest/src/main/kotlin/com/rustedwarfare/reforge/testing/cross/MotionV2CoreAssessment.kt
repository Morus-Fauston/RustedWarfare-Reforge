package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*

/** Rule-projection record pairing only; not all 32 F fields, source qualification, or external PASS. */
internal object MotionV2CoreAssessment {
    data class Issue(val domain: String, val status: MotionV2Findings.Status, val code: String, val sample: Int?, val detail: String = code)
    data class InputAttempt(val sequence: Long, val action: String, val q: Double, val returned: JsonObject)
    data class PairedPrefix(val facts: MotionV2DiagnosticAdapter.Facts, val lastSample: Int)
    data class Result(val fullFacts: MotionV2DiagnosticAdapter.Facts?, val prefix: PairedPrefix?,
                      val issues: List<Issue>, val inputAttempts: List<InputAttempt>,
                      val complete: Boolean?, val exitCode: Long?, val requiredHorizonQ: Double,
                      val observedEndQ: Double?)

    private fun source(ok: Boolean, detail: String) { if (!ok) throw InputError(detail) }
    private fun flag(value: JsonObject, key: String): Boolean {
        val primitive = value.required(key) as? JsonPrimitive ?: throw InputError("Expected boolean: $key")
        source(!primitive.isString && primitive.booleanOrNull != null, "Expected boolean: $key")
        return primitive.boolean
    }
    private fun samePosition(a: JsonObject, b: JsonObject) = a.number("x").toBits() == b.number("x").toBits() &&
        a.number("y").toBits() == b.number("y").toBits()
    private fun sameTarget(a: JsonElement, b: JsonElement) = if (a == JsonNull || b == JsonNull) a == b
        else samePosition(a.jsonObject, b.jsonObject)
    private fun unit(observation: JsonObject): JsonObject {
        val units = observation.arrayAt("units"); source(units.size == 1, "Expected one unit")
        return units.single().jsonObject.also { u ->
            source(u.text("unitId") == "u1" && u.text("ownerPlayerId") == "p1" && u.text("typeId") == "tank", "Unit identity")
            u.objectAt("position").number("x"); u.objectAt("position").number("y")
            for (key in listOf("heading", "speed", "angularVelocity")) u.number(key)
            if (u.required("moveTarget") != JsonNull) { u.objectAt("moveTarget").number("x"); u.objectAt("moveTarget").number("y") }
            for (point in u.arrayAt("pathPoints")) { point.jsonObject.number("x"); point.jsonObject.number("y") }
            source(u.text("movementState") in setOf("IDLE", "MOVING", "DECELERATING", "BLOCKED"), "Movement state type")
        }
    }

    fun assessRecords(body: JsonObject, expectedActionIdPrefix: String, requiredHorizonQ: Double): Result {
        require(requiredHorizonQ.isFinite() && requiredHorizonQ in 0.0..600.0 && expectedActionIdPrefix.isNotBlank())
        val issues = mutableListOf<Issue>(); val attempts = mutableListOf<InputAttempt>()
        val points = mutableListOf<MotionV2Sampling.Point>(); val calls = mutableListOf<MotionV2Events.Call>()
        val clears = mutableListOf<MotionV2DiagnosticAdapter.Clear>()
        val consumptions = mutableListOf<MotionV2DiagnosticAdapter.InputConsumption>()
        var complete: Boolean? = null; var exit: Long? = null; var index: Int? = null; var allPaired = false
        var inputIndex = 0; var motionIndex = 0; var previous: JsonObject? = null; var sequence = -1L
        var activeMove: Pair<Long, JsonElement>? = null
        var commandAxis = -1L
        var clearedWithoutNewMove = false
        fun facts() = MotionV2DiagnosticAdapter.Facts(points.toList(), calls.toList(), clears.toList(),
            attempts.map { it.q }, consumptions.toList())
        try {
            complete = flag(body, "complete"); exit = body.integer("exitCode")
            source(body.integer("motionInputSchemaVersion") == 1L, "Motion record version")
            val samples = body.arrayAt("samples"); val inputs = body.arrayAt("inputs")
            source(body.integer("recordCount") == samples.size.toLong(), "Record count copy")
            val motions = body.arrayAt("motionInputs")
            for ((i, element) in samples.withIndex()) {
                index = i
                val row = element.jsonObject; val raw = row.objectAt("raw"); val observation = raw.objectAt("observation")
                source(row.integer("index") == i.toLong() && row.integer("phase") == 0L, "Sample index/phase")
                val now = unit(observation); val state = row.objectAt("state")
                val q = observation.number("simulationTime"); source(q >= 0 && row.number("q") == q, "Sample time copy")
                source(state.text("id") == "u1" && state.text("owner") == "p1" && state.text("definition") == "tank" &&
                    samePosition(now.objectAt("position"), state) && now.number("heading").toBits() == state.number("heading").toBits() &&
                    sameTarget(now.required("moveTarget"), state.required("target")), "State/actual observation copy")
                val eventKinds = observation.arrayAt("events").map { e ->
                    source(e.jsonObject.text("unitId") == "u1", "Event unit copy"); e.jsonObject.text("kind")
                }
                val pending = mutableListOf<Issue>()
                fun behavior(ok: Boolean, code: String) {
                    if (!ok) pending += Issue("behavior", MotionV2Findings.Status.FAIL, code, i)
                }
                if (i == 0) {
                    source(raw.text("kind") == "initial" && q == 0.0 && observation.integer("stepIndex") == 0L &&
                        row.integer("inputSequence") == -1L && observation.text("worldId").isNotBlank(), "Initial pairing")
                    behavior(now.required("moveTarget") == JsonNull && now.arrayAt("pathPoints").isEmpty(), "INITIAL_COMMAND_PRESENT")
                } else {
                    val before = requireNotNull(previous); val old = unit(before)
                    source(observation.text("worldId") == before.text("worldId") && q >= before.number("simulationTime"), "World/time order")
                    var pendingCause: MotionV2DiagnosticAdapter.Cause? = null
                    var pendingAttempt: InputAttempt? = null
                    var pendingConsumption: MotionV2DiagnosticAdapter.InputConsumption? = null
                    var nextSequence = sequence
                    var nextAxis = commandAxis
                    var nextCleared = clearedWithoutNewMove
                    val nextCallIndex = calls.size
                    val event = MotionV2Events.Event(MotionV2Math.Interval(before.number("simulationTime"), q), nextCallIndex)
                    if (raw.text("kind") == "input-post") {
                    source(inputIndex < inputs.size, "Unpaired input sample")
                    val input = inputs[inputIndex].jsonObject; val inputRaw = input.objectAt("raw")
                    source(input.integer("sequence") == inputIndex.toLong() && input.number("q") == before.number("simulationTime") &&
                        inputRaw.required("before") == before && inputRaw.required("after") == observation, "Input raw pairing")
                    val action = input.text("action"); source(action in setOf("move", "stop"), "Input action")
                    if (action == "move") {
                        source(input.required("target") is JsonObject, "Move goal must be a finite position")
                        input.objectAt("target").number("x"); input.objectAt("target").number("y")
                    } else source(input.required("target") == JsonNull, "Stop must not have a goal")
                    source(row.integer("inputSequence") == inputIndex.toLong(), "Input sequence copy")
                    val returned = inputRaw.objectAt("submitResult"); val status = returned.text("status")
                    source(status in setOf("accepted", "rejected"), "Return status type")
                    val immediate = status == "accepted" && returned.required("queuedForStep") == JsonNull
                    if (status == "accepted" && returned.required("queuedForStep") != JsonNull)
                        source(returned.integer("queuedForStep") >= 0L, "Queued step type")
                    val correctId = returned.text("actionId") == "$expectedActionIdPrefix$inputIndex"
                    behavior(immediate, "INPUT_NOT_IMMEDIATE"); behavior(correctId, "INPUT_RETURN_ID")
                    val noAdvance = q == before.number("simulationTime") && observation.integer("stepIndex") == before.integer("stepIndex")
                    behavior(noAdvance, "INPUT_ADVANCED_SIMULATION")
                    behavior(samePosition(old.objectAt("position"), now.objectAt("position")) &&
                        listOf("heading", "speed", "angularVelocity").all { old.number(it).toBits() == now.number(it).toBits() }, "INPUT_CHANGED_MOTION")
                    behavior("movement-arrived" !in eventKinds, "INPUT_ARRIVAL_EVENT")
                    val route = now.arrayAt("pathPoints")
                    val moveEffective = action == "move" && now.required("moveTarget") != JsonNull &&
                        sameTarget(input.required("target"), now.required("moveTarget")) && route.isNotEmpty() &&
                        sameTarget(route.last(), input.required("target"))
                    val clear = now.required("moveTarget") == JsonNull && route.isEmpty()
                    val unreachable = clear && eventKinds.count { it == "path-unreachable" } == 1 &&
                        eventKinds.count { it == "movement-cancelled" } == 1 && "movement-arrived" !in eventKinds
                    if (immediate && correctId) {
                        if (action == "move") behavior(moveEffective || unreachable, "MOVE_NOT_EFFECTIVE")
                        else behavior(now.required("moveTarget") == JsonNull && now.arrayAt("pathPoints").isEmpty() &&
                            "movement-cancelled" in eventKinds && "path-unreachable" !in eventKinds, "STOP_NOT_CLEARED")
                    }
                    pendingAttempt = InputAttempt(inputIndex.toLong(), action, before.number("simulationTime"), returned)
                    if (immediate && correctId && noAdvance) pendingConsumption = MotionV2DiagnosticAdapter.InputConsumption(
                        inputIndex.toLong(), action, before.number("simulationTime"), event, inputRaw)
                    val cleared = now.required("moveTarget") == JsonNull && now.arrayAt("pathPoints").isEmpty()
                    if (immediate && correctId && noAdvance && "movement-arrived" !in eventKinds) {
                        if (action == "move" && cleared && eventKinds.count { it == "path-unreachable" } == 1 &&
                            eventKinds.count { it == "movement-cancelled" } == 1) pendingCause = MotionV2DiagnosticAdapter.Cause.UNREACHABLE
                        if (action == "stop" && cleared && old.required("moveTarget") != JsonNull &&
                            eventKinds.count { it == "movement-cancelled" } == 1 && "path-unreachable" !in eventKinds)
                            pendingCause = MotionV2DiagnosticAdapter.Cause.EXTERNAL_STOP
                    }
                    val unchangedCommand = sameTarget(old.required("moveTarget"), now.required("moveTarget")) &&
                        old.required("pathPoints") == now.required("pathPoints")
                    if (action == "move" && immediate && correctId && noAdvance && moveEffective && "movement-arrived" !in eventKinds) {
                        activeMove = inputIndex.toLong() to input.required("target")
                        nextAxis = inputIndex.toLong(); nextCleared = false
                    } else if (pendingCause != null || action == "stop" && immediate && correctId && noAdvance && clear &&
                        eventKinds.count { it == "movement-cancelled" } == 1 && "movement-arrived" !in eventKinds && "path-unreachable" !in eventKinds) {
                        activeMove = null; nextAxis = inputIndex.toLong()
                    } else if (!unchangedCommand) {
                        activeMove = null
                        if (!immediate || !correctId) behavior(false, "UNCONSUMED_INPUT_CHANGED_COMMAND")
                        pending += Issue("behavior", MotionV2Findings.Status.INSUFFICIENT, "COMMAND_ASSOCIATION_UNKNOWN", i)
                    }
                    if (pendingCause == null && old.required("moveTarget") != JsonNull && now.required("moveTarget") == JsonNull) {
                        pendingCause = MotionV2DiagnosticAdapter.Cause.UNKNOWN
                        if (immediate && correctId && noAdvance) nextAxis = inputIndex.toLong()
                    }
                    nextSequence = inputIndex.toLong(); inputIndex++
                    } else {
                        source(raw.text("kind") == "advance" && motionIndex < motions.size, "Unpaired advance sample")
                        val motion = motions[motionIndex].jsonObject
                        val origin = motion.required("originMoveId")
                        source(origin == JsonNull || origin is JsonPrimitive && origin.isString && origin.content.isNotBlank(), "Origin Move ID type")
                        source(motion.integer("stepIndex") == motionIndex.toLong() && observation.integer("stepIndex") == motion.integer("stepIndex") &&
                            before.integer("stepIndex") == maxOf(0L, motion.integer("stepIndex") - 1L), "Advance step pairing")
                        source(motion.number("timeBefore") == before.number("simulationTime") && motion.number("delta") > 0 &&
                            motion.number("timeBefore") + motion.number("delta") == q && motion.text("worldId") == observation.text("worldId") &&
                            motion.text("unitId") == "u1" && motion.text("ownerPlayerId") == "p1" && motion.text("typeId") == "tank", "Advance invocation pairing")
                        source(samePosition(motion.objectAt("positionBefore"), old.objectAt("position")) &&
                            samePosition(motion.objectAt("finalPosition"), now.objectAt("position")) &&
                            sameTarget(motion.required("commandTargetBefore"), old.required("moveTarget")), "Advance position/target copy")
                        for ((field, name) in listOf("heading" to "Heading", "speed" to "Speed", "angularVelocity" to "AngularVelocity")) {
                            val beforeKey = if(field == "angularVelocity") "angularVelocityBefore" else field + "Before"
                            source(motion.number(beforeKey).toBits() == old.number(field).toBits() &&
                                motion.number("final$name").toBits() == now.number(field).toBits(), "Advance $field copy")
                        }
                        val normal = flag(motion, "normalCompletion")
                        val arrived = eventKinds.count { it == "movement-arrived" }
                        behavior(normal == (arrived == 1) && arrived <= 1, "NORMAL_EVENT_MISMATCH")
                        val cleared = now.required("moveTarget") == JsonNull && now.arrayAt("pathPoints").isEmpty()
                        val bound = activeMove?.let { (id, target) -> motion.required("originMoveId") == s("$expectedActionIdPrefix$id") &&
                            old.required("moveTarget") != JsonNull && sameTarget(old.required("moveTarget"), target) } == true
                        if (normal || arrived > 0) {
                            if (activeMove != null) behavior(bound, "NORMAL_MOVE_BINDING")
                            else pending += Issue("behavior", MotionV2Findings.Status.INSUFFICIENT, "NORMAL_MOVE_ASSOCIATION_UNKNOWN", i)
                            behavior(!clearedWithoutNewMove, "REPEATED_CLEAR")
                            behavior(cleared, "NORMAL_NOT_CLEARED")
                            behavior("movement-cancelled" !in eventKinds && "path-unreachable" !in eventKinds, "NORMAL_CONFLICTING_EVENT")
                        }
                        if (normal && arrived == 1 && bound && cleared &&
                            "movement-cancelled" !in eventKinds && "path-unreachable" !in eventKinds)
                            pendingCause = MotionV2DiagnosticAdapter.Cause.NORMAL
                        if (!normal && arrived == 0 && bound && cleared &&
                            eventKinds.count { it == "path-unreachable" } == 1 && eventKinds.count { it == "movement-cancelled" } == 1)
                            pendingCause = MotionV2DiagnosticAdapter.Cause.UNREACHABLE
                        if (old.required("moveTarget") != JsonNull && now.required("moveTarget") == JsonNull &&
                            pendingCause == null) {
                            behavior("movement-cancelled" in eventKinds, "CLEAR_CANCEL_EVENT_MISSING")
                            pendingCause = MotionV2DiagnosticAdapter.Cause.UNKNOWN
                        }
                        if (pendingCause != null) activeMove = null
                        if (clearedWithoutNewMove && old.required("moveTarget") == JsonNull && now.required("moveTarget") != JsonNull) {
                            behavior(false, "COMMAND_RESURRECTED"); activeMove = null
                        }
                        motionIndex++
                    }
                    source(row.integer("inputSequence") == nextSequence, "Sample input sequence copy")
                    if (old.required("moveTarget") != JsonNull && now.required("moveTarget") == JsonNull ||
                        pendingCause == MotionV2DiagnosticAdapter.Cause.UNREACHABLE) nextCleared = true
                    if (now.required("moveTarget") == JsonNull && now.arrayAt("pathPoints").isNotEmpty()) {
                        behavior(false, "CLEARED_PATH_RETAINED")
                        pending += Issue("behavior", MotionV2Findings.Status.INSUFFICIENT, "COMMAND_STATE_UNKNOWN", i)
                    }
                    // Commit only after every source pairing in this call has passed.
                    val call = MotionV2Events.Call(before.number("simulationTime"), q, 0, nextAxis,
                        old.objectAt("position").number("x"), old.objectAt("position").number("y"),
                        now.objectAt("position").number("x"), now.objectAt("position").number("y"),
                        old.required("moveTarget") != JsonNull, now.required("moveTarget") != JsonNull)
                    calls += call
                    if (pendingAttempt != null) attempts += pendingAttempt
                    if (pendingConsumption != null) consumptions += pendingConsumption
                    if (pendingCause != null) clears += MotionV2DiagnosticAdapter.Clear(pendingCause, nextAxis, event)
                    if (pendingCause == MotionV2DiagnosticAdapter.Cause.UNKNOWN)
                        pending += Issue("behavior", MotionV2Findings.Status.INSUFFICIENT, "CLEAR_REASON_UNKNOWN", i)
                    sequence = nextSequence
                    commandAxis = nextAxis; clearedWithoutNewMove = nextCleared
                }
                source(row.integer("inputSequence") == sequence, "Sample input sequence copy")
                points += MotionV2Sampling.Point(q, 0, commandAxis, now.objectAt("position").number("x"),
                    now.objectAt("position").number("y"), now.number("heading"))
                issues += pending; previous = observation
            }
            source(inputIndex == inputs.size && motionIndex == motions.size, "Unpaired extra inputs/motions")
            allPaired = true
        } catch (error: RuntimeException) {
            if (error !is InputError && error !is IllegalArgumentException && error !is IllegalStateException && error !is IndexOutOfBoundsException) throw error
            issues += Issue("source", MotionV2Findings.Status.EXECUTION_FAILED, "RECORD_PAIRING", index, error.message ?: error.javaClass.name)
        }
        if (exit != null && exit != 0L) issues += Issue("execution", MotionV2Findings.Status.EXECUTION_FAILED, "NONZERO_EXIT", null)
        if (complete == false) issues += Issue("coverage", MotionV2Findings.Status.INSUFFICIENT, "RAW_INCOMPLETE", null)
        val observed = calls.lastOrNull()?.exitQ
        if (observed == null || observed < requiredHorizonQ) issues += Issue("coverage", MotionV2Findings.Status.INSUFFICIENT, "REQUIRED_HORIZON_MISSING", null)
        val full = allPaired && complete == true && exit == 0L && calls.isNotEmpty()
        return Result(if (full) facts() else null, if (!full && calls.isNotEmpty()) PairedPrefix(facts(), points.lastIndex) else null,
            issues.toList(), attempts.toList(), complete, exit, requiredHorizonQ, observed)
    }
}
