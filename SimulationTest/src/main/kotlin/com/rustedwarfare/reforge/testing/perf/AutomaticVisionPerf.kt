package com.rustedwarfare.reforge.testing.perf

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementProfile
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.testing.json
import java.awt.geom.Line2D
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.math.hypot

data class AutomaticVisionPerfFixture(val id: String, val count: Int,
    val retention: TerrainRetention, val scenario: Scenario, val goal: Position,
    val scheduledStep: Long, val maxSteps: Int, val tailSteps: Int)

data class AutomaticVisionPerfRun(val fixture: AutomaticVisionPerfFixture,
    val diagnosticsEnabled: Boolean, val rows: List<JsonObject>)

class AutomaticVisionPerfCaptureException(message: String, val rows: List<JsonObject>, cause: Throwable) :
    IllegalStateException(message, cause)

/** Functional qualification only; no performance timing or throughput conclusions. */
object AutomaticVisionPerf {
    fun hiddenDiscoveryFixture(retention: TerrainRetention): AutomaticVisionPerfFixture {
        val blocked = BooleanArray(600) { it % 30 == 14 && it / 30 in 1..15 }
        val unit = UnitDefinition("tank", "tank", 11.0, 11.0, 1, Path.of("unused-tank.png"), 1.0,
            MovementProfile(MovementType.LAND, 1.1, 0.05, 0.02, 3.0, 0.01, false, false), emptySet(), 3)
        val scenario = Scenario("avp05-n1-${retention.name.lowercase()}",
            MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600) { 1 }, BlockingGrid(30, 20, blocked)),
            mapOf("tank" to unit), listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
            listOf(UnitSpawn("u0", "p1", "tank", Position(50.0, 70.0), 90.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"),
            MovementContract(16.0, 4.0), KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, false))
        return AutomaticVisionPerfFixture("AVP05", 1, retention, scenario, Position(430.0, 70.0), 0, 2500, 8)
    }

    fun capture(fixture: AutomaticVisionPerfFixture, diagnosticsEnabled: Boolean = true): AutomaticVisionPerfRun {
        checkFixture(fixture)
        require(diagnosticsEnabled) { "A1 only qualifies diagnostics enabled" }
        val rows = mutableListOf<JsonObject>()
        var context: SimulationContext? = null
        // Reuse identical immutable cell JSON values; full rows remain lossless.
        val cells = mutableMapOf<Triple<Boolean, Boolean?, Int?>, JsonObject>()
        try {
            val initialized = SimulationContext.initialize(fixture.scenario)
            context = (initialized as? InitResult.Accepted)?.context
                ?: error("Automatic fixture initialization rejected: $initialized")
            context.enablePathDiagnostics(context.world)
            fun sample(label: String, phase: String, actionResult: ActionResult? = null) {
                rows += buildJsonObject {
                    put("schemaVersion", 1); put("kind", "automatic-vision-functional-sample")
                    put("label", label); put("phase", phase)
                    put("observation", context.observe().json())
                    putJsonObject("players") {
                        for (player in fixture.scenario.players) {
                            val view = context.visionSnapshot(context.world, player.playerId)
                            val knowledge = context.knowledgeSnapshot(context.world, player.playerId)
                            val observed = context.observePlayer(context.world, player.playerId)
                            putJsonObject(player.playerId) {
                                put("playerId", player.playerId); put("worldId", context.world.id)
                                put("epoch", view.epoch); put("revision", knowledge.revision)
                                put("width", knowledge.width); put("height", knowledge.height)
                                put("basicCells", JsonArray(view.basicCells.map { JsonPrimitive(it.row * 30 + it.column) }))
                                put("effectiveCells", JsonArray(view.effectiveCells.map { JsonPrimitive(it.row * 30 + it.column) }))
                                put("cells", JsonArray(knowledge.cells.map { value ->
                                    cells.getOrPut(Triple(value.visible, value.lastKnownBlocked, value.lastKnownGroundGid)) {
                                        buildJsonObject {
                                            put("visible", value.visible)
                                            put("lastKnownBlocked", value.lastKnownBlocked?.let(::JsonPrimitive) ?: JsonNull)
                                            put("lastKnownGroundGid", value.lastKnownGroundGid?.let(::JsonPrimitive) ?: JsonNull)
                                        }
                                    }
                                }))
                                put("observation", Observation(observed.sampleId, observed.worldId, observed.scenarioId,
                                    observed.stepIndex, observed.simulationTime, observed.units, observed.events, observed.actionResults).json())
                            }
                        }
                    }
                    put("samples", JsonArray(context.drainPathDiagnostics(context.world).map(ReplanPerf::sampleJson)))
                    put("diagnosticsAvailable", true)
                    put("actionResult", actionResult?.json() ?: JsonNull)
                }
            }
            sample("initial", "initial")
            val result = context.submit(MoveAction("avp05-move-0", "p1", "u0", fixture.goal,
                context.world, fixture.scheduledStep))
            sample("submit", "submit", result)
            require(result is ActionResult.Accepted && result.queuedForStep == 0L) { "Fixed Move was not queued at step0" }
            var arrivedAt: Int? = null
            var advances = 0
            for (step in 0 until fixture.maxSteps + fixture.tailSteps) {
                if (arrivedAt == null && step >= fixture.maxSteps) break
                context.advance(Step(step.toLong(), 1.0, "sim-tick"))
                sample("advance:$step", "advance")
                advances++
                val unit = context.observe().units.single()
                if (arrivedAt == null && unit.moveTarget == null && unit.speed == 0.0 &&
                    hypot(unit.position.x - fixture.goal.x, unit.position.y - fixture.goal.y) <= 4.0) arrivedAt = step
                if (arrivedAt != null && step - arrivedAt >= fixture.tailSteps) break
            }
            rows += buildJsonObject {
                put("schemaVersion", 1); put("kind", "automatic-vision-functional-completion")
                put("label", "completion"); put("advanceCount", advances); put("requestCount", 1)
                put("status", if (arrivedAt != null) "arrived" else "budget-exhausted")
                put("arrivedStep", arrivedAt?.let(::JsonPrimitive) ?: JsonNull)
            }
            return AutomaticVisionPerfRun(fixture, true, rows.toList())
        } catch (failure: Exception) {
            throw AutomaticVisionPerfCaptureException("A1 capture failed after ${rows.size} rows", rows.toList(), failure)
        } finally {
            context?.close()
        }
    }

    /** D-084: actual swept motion and committed-knowledge route repair, not post-motion first-link clearance. */
    fun qualifyContinuousRoute(run: AutomaticVisionPerfRun) = validate(run, continuousRoute = true)

    /** Historical qualification retained for replaying its original route invariant. */
    fun validate(run: AutomaticVisionPerfRun) = validate(run, continuousRoute = false)

    private fun validate(run: AutomaticVisionPerfRun, continuousRoute: Boolean) {
        val fixture = run.fixture
        checkFixture(fixture)
        require(run.diagnosticsEnabled)
        val rows = run.rows
        require(rows.size >= 12) { "Missing functional rows" }
        require(rows.first().text("label") == "initial" && rows[1].text("label") == "submit")
        val completion = rows.last()
        require(completion.text("kind") == "automatic-vision-functional-completion" && completion.text("label") == "completion")
        val advanceCount = completion.integer("advanceCount")
        require(advanceCount == rows.size - 3 && advanceCount <= fixture.maxSteps + fixture.tailSteps)
        require(completion.integer("requestCount") == 1 && completion.text("status") == "arrived") { "Fixed move did not arrive" }
        val arrivedStep = completion.getValue("arrivedStep").jsonPrimitive.int
        require(arrivedStep in 0 until fixture.maxSteps && advanceCount == arrivedStep + 1 + fixture.tailSteps)
        val remembered = Array(2) { Array<Pair<Boolean?, Int?>>(600) { null to null } }
        val wall = (1..15).map { 14 to it }
        var previousPosition = Position(50.0, 70.0)
        var previousPath = emptyList<Position>()
        var previousKnowledge = List<Boolean?>(600) { null }
        var discovered = false
        var rerouted = false
        var knowledgeReplans = 0
        var userMoves = 0
        var arrivedEvent = false
        var moveEnded = false
        var world: String? = null
        for ((index, row) in rows.dropLast(1).withIndex()) {
            require(row.text("kind") == "automatic-vision-functional-sample" && row.integer("schemaVersion") == 1)
            val step = index - 2
            val phase = when (index) { 0 -> "initial"; 1 -> "submit"; else -> "advance" }
            require(row.text("phase") == phase && row.text("label") == if (index < 2) phase else "advance:$step")
            val observation = row.getValue("observation").jsonObject
            if (world == null) world = observation.text("worldId")
            require(observation.text("worldId") == world && observation.text("scenarioId") == fixture.scenario.scenarioId)
            require(observation.integer("stepIndex") == maxOf(step, 0))
            require(observation.getValue("simulationTime").jsonPrimitive.double == if (step < 0) 0.0 else step + 1.0)
            val unit = observation.getValue("units").jsonArray.single().jsonObject
            require(unit.text("unitId") == "u0" && unit.text("ownerPlayerId") == "p1" && unit.text("typeId") == "tank")
            val position = position(unit.getValue("position"))
            require(position.x.isFinite() && position.y.isFinite() && position.x in 11.0..589.0 && position.y in 11.0..389.0)
            if (continuousRoute) {
                for (field in listOf("heading", "speed", "angularVelocity")) require(unit.getValue(field).jsonPrimitive.double.isFinite()) { "Non-finite motion field $field" }
                require(unit.getValue("heading").jsonPrimitive.double in 0.0..<360.0)
                require(unit.getValue("speed").jsonPrimitive.double in 0.0..1.1)
                require(kotlin.math.abs(unit.getValue("angularVelocity").jsonPrimitive.double) <= 3.0)
            }
            if (step >= 0) for ((column, r) in wall) {
                require(clear(previousPosition, position, column, r)) { "Truth sweep collision at step$step wall($column,$r)" }
            }
            val players = row.getValue("players").jsonObject
            require(players.keys == setOf("p1", "p2"))
            var currentKnowledge = previousKnowledge
            for ((playerIndex, id) in listOf("p1", "p2").withIndex()) {
                val player = players.getValue(id).jsonObject
                require(player.text("playerId") == id && player.text("worldId") == world)
                require(player.integer("width") == 30 && player.integer("height") == 20)
                require(player.integer("epoch") == maxOf(step + 1, 0) && player.integer("revision") == maxOf(step + 2, 1))
                val visible = if (id == "p2") emptyList() else (0 until 600).filter { cell ->
                    val dx = cell % 30 + 0.5 - position.x / 20.0
                    val dy = cell / 30 + 0.5 - position.y / 20.0
                    dx * dx + dy * dy <= 9.0
                }
                require(player.getValue("basicCells").jsonArray.map { it.jsonPrimitive.int } == visible)
                require(player.getValue("effectiveCells").jsonArray.map { it.jsonPrimitive.int } == visible)
                val actualCells = player.getValue("cells").jsonArray
                require(actualCells.size == 600)
                for (cell in 0 until 600) {
                    val value = actualCells[cell].jsonObject
                    val isVisible = cell in visible
                    if (isVisible) remembered[playerIndex][cell] = (cell % 30 == 14 && cell / 30 in 1..15) to 1
                    val expected = remembered[playerIndex][cell]
                    require(value.getValue("visible").jsonPrimitive.boolean == isVisible) { "Vision differs $id cell$cell step$step" }
                    require(value.getValue("lastKnownBlocked") == (expected.first?.let(::JsonPrimitive) ?: JsonNull))
                    require(value.getValue("lastKnownGroundGid") == (expected.second?.let(::JsonPrimitive) ?: JsonNull))
                }
                val observed = player.getValue("observation").jsonObject
                require(observed.text("worldId") == world && observed.integer("stepIndex") == maxOf(step, 0))
                if (continuousRoute) {
                    require(observed.text("scenarioId") == fixture.scenario.scenarioId && observed.text("sampleId").isNotBlank())
                    require(observed.getValue("simulationTime") == observation.getValue("simulationTime"))
                    for (field in listOf("events", "actionResults")) require(observed.getValue(field) ==
                        if (id == "p1") observation.getValue(field) else JsonArray(emptyList())) { "Player $id $field differs from authorized output" }
                }
                if (id == "p1") {
                    require(observed.getValue("units") == observation.getValue("units"))
                    currentKnowledge = actualCells.map { it.jsonObject.getValue("lastKnownBlocked").let { value ->
                        if (value == JsonNull) null else value.jsonPrimitive.boolean
                    } }
                    discovered = discovered || actualCells.any { it.jsonObject.getValue("lastKnownBlocked") == JsonPrimitive(true) }
                } else require(observed.getValue("units").jsonArray.isEmpty())
            }
            if (index == 0) require(currentKnowledge[3 * 30 + 14] == null) { "Wall must initially be unknown" }
            val path = unit.getValue("pathPoints").jsonArray.map(::position)
            if (continuousRoute) {
                require(path.all { it.x.isFinite() && it.y.isFinite() && it.x in 11.0..589.0 && it.y in 11.0..389.0 }) { "Invalid path point" }
                val target = unit.getValue("moveTarget")
                if (target == JsonNull) require(path.isEmpty()) { "Inactive move has a path" }
                else require(position(target) == fixture.goal && path.isNotEmpty() && path.last() == fixture.goal) { "Path/target differs from exact command" }
                if (step >= 0) {
                    if (target == JsonNull) {
                        val endingEvents = observation.getValue("events").jsonArray.map { it.jsonObject }
                        require(moveEnded || (hypot(position.x - fixture.goal.x, position.y - fixture.goal.y) <= 4.0 &&
                            endingEvents.any { it["unitId"] == JsonPrimitive("u0") && it.text("kind") == "movement-arrived" })) {
                            "Move ended without successful arrival near the exact goal"
                        }
                        moveEnded = true
                    } else require(!moveEnded) { "Ended move resurrected without another command" }
                }
                val state = unit.text("movementState")
                if (target != JsonNull) require(state in setOf("MOVING", "BLOCKED"))
                else require(state in setOf("IDLE", "DECELERATING", "BLOCKED"))
            }
            rerouted = rerouted || path.any { it.y != 70.0 }
            // Searches and the movement phase use the preceding committed knowledge; discoveries
            // sampled at this advance's end apply on the following advance.
            val knownBeforeMovement = if (index < 2) currentKnowledge else previousKnowledge
            val remainingSegments = if (continuousRoute) path.zipWithNext() else (listOf(position) + path).zipWithNext()
            for ((a, b) in remainingSegments) {
                for ((column, r) in wall) if (knownBeforeMovement[r * 30 + column] == true) {
                    require(clear(a, b, column, r)) { "Known-terrain remaining route collision step$step wall($column,$r)" }
                }
            }
            require(row.getValue("diagnosticsAvailable") == JsonPrimitive(true))
            val samples = row.getValue("samples").jsonArray
            for (sampleValue in samples) {
                val sample = sampleValue.jsonObject
                require(step >= 0 && sample.text("unitId") == "u0" && sample.text("actionId") == "avp05-move-0")
                require(sample.getValue("actualSearchCalls").jsonPrimitive.long > 0 && sample.integer("executionStep") == step)
                require(sample.getValue("usedPlayerKnowledge") == JsonPrimitive(true))
                if (!continuousRoute) {
                    require(position(sample.getValue("searchTarget")) == fixture.goal)
                    require(position(sample.getValue("searchStart")) == previousPosition)
                }
                if (!continuousRoute) require(sample.integer("preservedPrefixPointCount") in 0..path.size)
                when (sample.text("reason")) {
                    "USER_MOVE" -> { require(step == 0); userMoves++ }
                    "KNOWLEDGE_REPLAN" -> { require(step > 0 && previousKnowledge.any { it == true }); knowledgeReplans++ }
                    "ROUTE_REPAIR" -> require(step > 0)
                    else -> error("Invalid actual search reason")
                }
            }
            if (continuousRoute) qualifySearches(samples, step, previousPosition, previousPath, path,
                knownBeforeMovement, fixture.goal, unit, observation)
            if (index == 1) {
                val accepted = row.getValue("actionResult").jsonObject
                require(accepted.text("status") == "accepted" && accepted.integer("queuedForStep") == 0)
            } else require(row.getValue("actionResult") == JsonNull)
            arrivedEvent = arrivedEvent || observation.getValue("events").jsonArray.any {
                it.jsonObject.text("kind") == "movement-arrived" && it.jsonObject.text("unitId") == "u0"
            }
            if (step >= arrivedStep) {
                require(hypot(position.x - fixture.goal.x, position.y - fixture.goal.y) <= 4.0 &&
                    unit.getValue("moveTarget") == JsonNull && unit.getValue("speed").jsonPrimitive.double == 0.0)
                if (step > arrivedStep) require(samples.isEmpty()) { "Search resurrected during terminal tail" }
            }
            previousPosition = position
            previousPath = path
            previousKnowledge = currentKnowledge
        }
        require(userMoves == 1 && knowledgeReplans > 0 && discovered && rerouted && arrivedEvent) {
            "Missing actual move/discovery/replan/reroute/arrival: $userMoves/$knowledgeReplans/$discovered/$rerouted/$arrivedEvent"
        }
    }

    private fun qualifySearches(values: JsonArray, step: Int, start: Position, oldPath: List<Position>,
        path: List<Position>, knowledge: List<Boolean?>, goal: Position, unit: JsonObject, observation: JsonObject) {
        fun safe(a: Position, b: Position) = knowledge.indices.filter { knowledge[it] == true }
            .all { clear(a, b, it % 30, it / 30) }
        val prefix = mutableListOf<Position>()
        var from = start
        for (point in oldPath) {
            if (!safe(from, point)) break
            prefix += point
            from = point
        }
        val samples = values.map { it.jsonObject }
        val needsRepair = oldPath.isNotEmpty() && !safe(start, oldPath.first())
        if (needsRepair) require(samples.any { it.text("reason") in setOf("KNOWLEDGE_REPLAN", "ROUTE_REPAIR") }) {
            "Missing required route repair at step$step"
        }
        // This fixed A1 capture has one search phase per step. Mixed phases cannot be
        // qualified from the available snapshots without an intermediate route snapshot.
        require(samples.map { it.text("reason") }.distinct().size <= 1) { "Mixed search phases require an intermediate route snapshot" }
        require(samples.size <= 2) { "Unexpected repeated diagnostic" }
        for ((index, sample) in samples.withIndex()) {
            val source = position(sample.getValue("searchStart"))
            val target = position(sample.getValue("searchTarget"))
            val preserved = sample.integer("preservedPrefixPointCount")
            val outcome = sample.text("outcome")
            require(sample.integer("scheduledStep") == 0) { "Search lost original scheduled command" }
            require(outcome in setOf("FOUND", "UNREACHABLE")) { "Uncommitted/failed search in successful capture" }
            require(if (outcome == "FOUND") sample.integer("pathPointCount") > 0 else sample.integer("pathPointCount") == 0)
            if (index > 0) require(samples[index - 1].text("outcome") == "UNREACHABLE") { "Duplicate search after success" }
            when (sample.text("reason")) {
                "USER_MOVE" -> require(samples.size == 1 && source == start && target == goal && preserved == 0)
                "KNOWLEDGE_REPLAN" -> {
                    require(target == goal)
                    val hasPrefix = prefix.isNotEmpty() && prefix.size < oldPath.size
                    val expectedStart = if (index == 0 && hasPrefix) prefix.last() else start
                    require(source == expectedStart) { "Invalid retained-prefix search anchor" }
                    require(preserved == if (outcome == "FOUND" && index == 0 && hasPrefix) prefix.size else 0)
                    if (index > 0) require(hasPrefix) { "Fallback without failed prefix" }
                }
                "ROUTE_REPAIR" -> {
                    require(source == start && preserved == 0)
                    val hasSafeTail = oldPath.size > 1 && oldPath.zipWithNext().all { (a, b) -> safe(a, b) }
                    require(target == if (index == 0 && hasSafeTail) oldPath.first() else goal) { "Repair target is not the first anchor or valid fallback" }
                    require(needsRepair) { "Repair has no invalid connection" }
                    if (index > 0) require(hasSafeTail) { "Fallback without failed connector" }
                }
                else -> error("Unknown search reason")
            }
            if (outcome == "UNREACHABLE") {
                val fallback = samples.getOrNull(index + 1)
                if (fallback != null) {
                    require(fallback.text("reason") == sample.text("reason") &&
                        position(fallback.getValue("searchStart")) == start && position(fallback.getValue("searchTarget")) == goal) { "Invalid failed-prefix/connector fallback" }
                } else {
                    val kinds = observation.getValue("events").jsonArray.map { it.jsonObject }.filter { it["unitId"] == JsonPrimitive("u0") }.map { it.text("kind") }
                    require(unit.getValue("moveTarget") == JsonNull && path.isEmpty() &&
                        "movement-cancelled" in kinds && "path-unreachable" in kinds) { "Unreachable route did not cancel" }
                }
            }
        }
    }

    private fun checkFixture(value: AutomaticVisionPerfFixture) {
        require(value.id == "AVP05" && value.count == 1 && value.retention == TerrainRetention.MEMORY)
        require(value.goal == Position(430.0, 70.0) && value.scheduledStep == 0L && value.maxSteps == 2500 && value.tailSteps == 8)
        val scenario = value.scenario
        require(scenario.map.widthTiles == 30 && scenario.map.heightTiles == 20 && scenario.map.tileWidth == 20 && scenario.map.tileHeight == 20)
        require(scenario.map.groundGids.contentEquals(IntArray(600) { 1 }))
        for (row in 0 until 20) for (column in 0 until 30) {
            require(scenario.map.blocking.isBlocked(column, row) == (column == 14 && row in 1..15))
        }
        require(scenario.players == listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)))
        require(scenario.units == listOf(UnitSpawn("u0", "p1", "tank", Position(50.0, 70.0), 90.0)))
        require(scenario.automaticVision == KnowledgeRules(value.retention, PathInformationSource.PLAYER_KNOWLEDGE, false))
        require(scenario.movementContract == MovementContract(16.0, 4.0))
        val unit = scenario.unitDefinitions.getValue("tank")
        require(scenario.unitDefinitions.keys == setOf("tank") && unit.id == "tank" && unit.radius == 11.0 && unit.fogOfWarSightRange == 3)
        require(unit.movement == MovementProfile(MovementType.LAND, 1.1, 0.05, 0.02, 3.0, 0.01, false, false))
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.integer(key: String) = getValue(key).jsonPrimitive.int
    private fun position(value: JsonElement): Position = value.jsonObject.let {
        Position(it.getValue("x").jsonPrimitive.double, it.getValue("y").jsonPrimitive.double)
    }

    private fun clear(a: Position, b: Position, column: Int, row: Int): Boolean {
        val left = column * 20.0; val right = left + 20.0
        val top = row * 20.0; val bottom = top + 20.0
        fun endpoint(p: Position) = hypot(maxOf(left - p.x, 0.0, p.x - right), maxOf(top - p.y, 0.0, p.y - bottom))
        if (endpoint(a) <= 11.0 || endpoint(b) <= 11.0) return false
        val corners = listOf(Position(left, top), Position(right, top), Position(right, bottom), Position(left, bottom))
        for (i in corners.indices) {
            val c = corners[i]; val d = corners[(i + 1) % corners.size]
            if (Line2D.linesIntersect(a.x, a.y, b.x, b.y, c.x, c.y, d.x, d.y)) return false
            if (Line2D.ptSegDist(a.x, a.y, b.x, b.y, c.x, c.y) <= 11.0) return false
        }
        return true
    }
}
