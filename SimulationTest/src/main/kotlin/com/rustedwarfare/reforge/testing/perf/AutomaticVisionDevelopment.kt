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
import com.rustedwarfare.reforge.testing.MotionCenterGeometry
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.math.hypot

data class AutomaticVisionDevelopmentRun(val fixture:AutomaticVisionPerfFixture, val diagnosticsEnabled:Boolean, val rows:List<JsonObject>)

/** Functional qualification only; no performance timing or throughput conclusions. */
object AutomaticVisionDevelopment {
    fun captureDevelopment(fixture: AutomaticVisionPerfFixture, diagnosticsEnabled: Boolean = true): AutomaticVisionDevelopmentRun {
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
                    put("schemaVersion", 2); developmentIdentity(this); put("kind", "automatic-vision-development-sample")
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
            var normalAt: Int? = null
            var settledAt: Int? = null
            var advances = 0
            for (step in 0 until fixture.maxSteps + fixture.tailSteps) {
                if (settledAt == null && step >= fixture.maxSteps) break
                val observed=context.advance(Step(step.toLong(), 1.0, "sim-tick"))
                sample("advance:$step", "advance")
                advances++
                val unit = context.observe().units.single()
                if(normalAt==null && observed.events.any { it.kind=="movement-arrived" && it.unitId=="u0" })normalAt=step
                if(normalAt!=null && settledAt==null && unit.moveTarget==null && unit.speed==0.0)settledAt=step
                if(settledAt!=null && step-settledAt>=fixture.tailSteps)break
            }
            rows += buildJsonObject {
                put("schemaVersion", 2); developmentIdentity(this); put("kind", "automatic-vision-development-completion")
                put("label", "completion"); put("advanceCount", advances); put("requestCount", 1)
                put("status", if (settledAt != null) "normal-cleared-and-settled" else "budget-exhausted")
                put("normalClearStep",normalAt?.let(::JsonPrimitive) ?: JsonNull)
                put("settleStep",settledAt?.let(::JsonPrimitive) ?: JsonNull)
            }
            return AutomaticVisionDevelopmentRun(fixture, true, rows.toList())
        } catch (failure: Exception) {
            throw AutomaticVisionPerfCaptureException("A1 capture failed after ${rows.size} rows", rows.toList(), failure)
        } finally {
            context?.close()
        }
    }

    /** Explicit development-only protocol; not historical AVP or full terrain writeback qualification. */
    fun qualifyDevelopment(run:AutomaticVisionDevelopmentRun) {
        val continuousRoute=true
        val fixture = run.fixture
        checkFixture(fixture)
        require(run.diagnosticsEnabled)
        val rows = run.rows
        require(rows.size >= 12) { "Missing functional rows" }
        require(rows.first().text("label") == "initial" && rows[1].text("label") == "submit")
        val completion = rows.last()
        rows.forEach { row->
            require(row.integer("schemaVersion")==2 && row["motionRulesVersion"]==JsonPrimitive("p002.dev.center-motion.v2") &&
                row["developmentOnly"]==JsonPrimitive(true) && row["qualificationScope"]==JsonPrimitive("development-automatic-discovery-only") &&
                row["fullTerrainWritebackQualified"]==JsonPrimitive(false)) { "Historical or unknown development protocol" }
        }
        require(completion.text("kind") == "automatic-vision-development-completion" && completion.text("label") == "completion")
        val advanceCount = completion.integer("advanceCount")
        require(advanceCount == rows.size - 3 && advanceCount <= fixture.maxSteps + fixture.tailSteps)
        require(completion.integer("requestCount") == 1 && completion.text("status") == "normal-cleared-and-settled") { "Fixed move did not normally clear and settle" }
        val arrivedStep = completion.integer("settleStep")
        val normalStep=completion.integer("normalClearStep")
        require(normalStep in 0 until fixture.maxSteps && arrivedStep in normalStep until fixture.maxSteps && advanceCount == arrivedStep + 1 + fixture.tailSteps)
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
        var previousUnit:JsonObject?=null
        var normalCount=0
        var nearTime=0.0
        var stableUnit:JsonObject?=null
        var world: String? = null
        val initialUnit=buildJsonObject {
            put("unitId","u0");put("ownerPlayerId","p1");put("typeId","tank")
            putJsonObject("position") { put("x",50.0);put("y",70.0) }
            put("heading",90.0);put("speed",0.0);put("angularVelocity",0.0)
            put("moveTarget",JsonNull);put("pathPoints",JsonArray(emptyList()));put("movementState","IDLE")
        }
        fun started()=buildJsonObject { put("kind","movement-started");put("unitId","u0") }
        fun accepted(queued:JsonElement,events:JsonArray)=buildJsonObject {
            put("actionId","avp05-move-0");put("status","accepted");put("queuedForStep",queued);put("events",events)
        }
        for ((index, row) in rows.dropLast(1).withIndex()) {
            require(row.text("kind") == "automatic-vision-development-sample" && row.integer("schemaVersion") == 2)
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
            if(index<2)require(unit==initialUnit) { "Initial or queued-submit unit differs from fixed spawn" }
            val old=previousUnit
            val kinds=observation.getValue("events").jsonArray.map { it.jsonObject }.also { events->
                require(events.all { it["unitId"]==JsonPrimitive("u0") && it.text("kind") in setOf("movement-started","movement-arrived","movement-blocked") }) { "Unexpected lifecycle event" }
            }.map { it.text("kind") }
            require(kinds.distinct().size==kinds.size) { "Duplicate per-unit lifecycle event" }
            when {
                step<0 -> require(kinds.isEmpty()) { "Initial/submit fabricated a lifecycle event" }
                step==0 -> require(observation["events"]==JsonArray(listOf(started()))) { "Scheduled Move did not start exactly once" }
                else -> require("movement-started" !in kinds && kinds.count { it=="movement-arrived" }==if(step==normalStep)1 else 0) { "Late start or mismatched normal event" }
            }
            require(observation["actionResults"]==if(step==0)JsonArray(listOf(accepted(JsonNull,JsonArray(listOf(started())))))else JsonArray(emptyList())) { "Host request results differ from scheduled Move" }
            if(step>=0 && old!=null) {
                val oldTarget=old.getValue("moveTarget")
                if(oldTarget!=JsonNull) {
                    val dx=previousPosition.x-fixture.goal.x;val dy=previousPosition.y-fixture.goal.y
                    if(dx*dx+dy*dy<1681.0)nearTime+=1.0
                    if("movement-arrived" in kinds) {
                        val radius=if(nearTime>340.0)36.0 else if(nearTime>240.0)16.0 else 7.0
                        require(!moveEnded && step==normalStep && dx*dx+dy*dy<radius*radius && unit["moveTarget"]==JsonNull) { "Illegal pre-state normal clear" }
                        normalCount++
                    }
                }else require("movement-arrived" !in kinds) { "Duplicate normal arrival" }
                if(unit["moveTarget"]==JsonNull) {
                    require(oldTarget==JsonNull || "movement-arrived" in kinds) { "Move ended without normal arrival" }
                    require(unit["heading"]==old["heading"] && unit["angularVelocity"]==old["angularVelocity"]) { "Clear/braking changed heading or retained cg" }
                    val speed=maxOf(0.0,old.getValue("speed").jsonPrimitive.double-.02*1.1)
                    require(kotlin.math.abs(unit.getValue("speed").jsonPrimitive.double-speed)<=1e-12) { "Wrong proportional braking" }
                    val angle=Math.toRadians(old.getValue("heading").jsonPrimitive.double)
                    val candidate=Position(previousPosition.x+kotlin.math.sin(angle)*speed,previousPosition.y-kotlin.math.cos(angle)*speed)
                    require(MotionCenterGeometry.centerPath(listOf(candidate),wall.map { intArrayOf(it.first,it.second) },30,20,20,20,11.0) &&
                        hypot(candidate.x-position.x,candidate.y-position.y)<=1e-9) { "Invalid open post-clear displacement" }
                }
            }
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
                        require(moveEnded || (
                            endingEvents.any { it["unitId"] == JsonPrimitive("u0") && it.text("kind") == "movement-arrived" })) {
                            "Move ended without normal arrival"
                        }
                        moveEnded = true
                    } else require(!moveEnded) { "Ended move resurrected without another command" }
                }
                val state = unit.text("movementState")
                require(state==if(target!=JsonNull)"MOVING"else if(unit.getValue("speed").jsonPrimitive.double>0.0)"DECELERATING"else "IDLE") { "Wrong dynamic movement state" }
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
                require(sample.getValue("actualSearchCalls").jsonPrimitive.long == 1L && sample.integer("executionStep") == step)
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
                require(row["actionResult"]==accepted(JsonPrimitive(0),JsonArray(emptyList()))) { "Submission result differs from queued Move" }
            } else require(row.getValue("actionResult") == JsonNull)
            arrivedEvent = arrivedEvent || observation.getValue("events").jsonArray.any {
                it.jsonObject.text("kind") == "movement-arrived" && it.jsonObject.text("unitId") == "u0"
            }
            if (step >= arrivedStep) {
                require(unit.getValue("moveTarget") == JsonNull && unit.getValue("speed").jsonPrimitive.double == 0.0)
                if(stableUnit==null)stableUnit=unit else require(unit==stableUnit) { "Terminal unit DTO drifted" }
                if (step > arrivedStep) require(samples.isEmpty()) { "Search resurrected during terminal tail" }
            }else if(step>=0 && unit["moveTarget"]==JsonNull)require(unit.getValue("speed").jsonPrimitive.double>0.0) { "Completion skipped the earliest settled step" }
            previousPosition = position
            previousPath = path
            previousKnowledge = currentKnowledge
            previousUnit=unit
        }
        require(userMoves == 1 && knowledgeReplans > 0 && discovered && rerouted && arrivedEvent && normalCount==1) {
            "Missing actual move/discovery/replan/reroute/arrival: $userMoves/$knowledgeReplans/$discovered/$rerouted/$arrivedEvent"
        }
    }

    internal fun qualifySearches(values: JsonArray, step: Int, start: Position, oldPath: List<Position>,
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
        val nearCornerStall=oldPath.size>1 && (start.x-oldPath.first().x).let { dx->
            val dy=start.y-oldPath.first().y;dx*dx+dy*dy<9.0 } && !safe(start,oldPath[1])
        if (needsRepair || nearCornerStall) require(samples.any { it.text("reason") in setOf("KNOWLEDGE_REPLAN", "ROUTE_REPAIR") }) {
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
                    val directGoal=index==0 && samples.size==1 && target==goal
                    val connector=index==0 && needsRepair && !nearCornerStall && hasSafeTail && target==oldPath.first()
                    val fallback=index>0 && needsRepair && hasSafeTail && target==goal
                    require(directGoal || connector || fallback) { "Repair target is not the first anchor or valid fallback" }
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

    private fun clear(a:Position,b:Position,column:Int,row:Int):Boolean =
        MotionCenterGeometry.centerPath(listOf(a,b),listOf(intArrayOf(column,row)),30,20,20,20,11.0)

    private fun developmentIdentity(value:JsonObjectBuilder) = with(value) {
        put("motionRulesVersion","p002.dev.center-motion.v2");put("developmentOnly",true)
        put("qualificationScope","development-automatic-discovery-only");put("fullTerrainWritebackQualified",false)
    }
}
