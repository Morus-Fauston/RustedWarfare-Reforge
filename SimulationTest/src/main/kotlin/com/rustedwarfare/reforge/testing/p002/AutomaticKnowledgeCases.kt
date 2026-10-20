package com.rustedwarfare.reforge.testing.p002

import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.testing.*
import com.rustedwarfare.reforge.testing.perf.ReplanPerf
import kotlinx.serialization.json.*

/** A bounded extension of the P002 archive format. Expected gameplay remains in frozen contracts. */
internal object AutomaticKnowledgeCases {
    val required = listOf("A01-memory", "A02-hardcore", "A03-sharing-off", "A04-sharing-on", "A05-no-fog", "A06-lifecycle")

    fun capture(model: JsonObject, fixture: JsonObject): List<JsonObject> {
        val scenario = P002KnowledgeSuite.scenario(model, fixture, automatic = true)
        val context = (SimulationContext.initialize(scenario) as? InitResult.Accepted)?.context
            ?: throw InputError("Automatic fixture initialization rejected")
        val rows = mutableListOf<JsonObject>()
        var old = context.world
        var closed = false
        var generation = 0
        var step = 0L
        fun sample(label: String, operation: String, result: String = "accepted") {
            val searches = if (closed) emptyList() else context.drainPathDiagnostics(context.world)
            val players = if (closed) JsonNull else JsonObject(scenario.players.associate { player ->
                val observed = context.observePlayer(context.world, player.playerId)
                player.playerId to obj("observation" to Observation(observed.sampleId, observed.worldId, observed.scenarioId,
                    observed.stepIndex, observed.simulationTime, observed.units, observed.events, observed.actionResults).json(),
                    "vision" to observed.vision?.let { view -> obj("worldId" to s(view.worldId), "playerId" to s(view.playerId),
                        "epoch" to n(view.epoch), "basicCells" to array(view.basicCells.map { n(it.row * scenario.map.widthTiles + it.column) }),
                        "effectiveCells" to array(view.effectiveCells.map { n(it.row * scenario.map.widthTiles + it.column) })) },
                    "knowledge" to observed.knowledge?.let(::knowledge))
            })
            val stores = if (closed || scenario.automaticVision == null) JsonNull else
                JsonObject(scenario.players.associate { it.playerId to knowledge(context.knowledgeSnapshot(context.world, it.playerId)) })
            rows += record("p002-sample", "caseId" to s(fixture.text("caseId")), "label" to s(label),
                "operation" to s(operation), "generation" to n(generation), "operationResult" to s(result), "error" to JsonNull,
                "observation" to if (closed) JsonNull else context.observe().json(), "knowledge" to stores, "players" to players,
                "searchCount" to n(searches.size), "searches" to array(searches.map(ReplanPerf::sampleJson)))
        }
        try {
            context.enablePathDiagnostics(context.world)
            sample("initial", "initialize")
            for (value in fixture.arrayAt("operations")) {
                val operation = value.jsonObject
                val label = operation.text("label")
                val kind = operation.text("kind")
                when (kind) {
                    "move" -> {
                        val target = operation.objectAt("target")
                        val result = context.submit(MoveAction(label, operation.text("actor"), "u1",
                            Position(target.number("x"), target.number("y")), context.world))
                        sample(label, kind, if (result is ActionResult.Rejected) result.error.code else "accepted")
                    }
                    "advance" -> repeat(operation.integer("count").toInt()) {
                        context.advance(Step(step++, 1.0, "sim-tick")); sample("$label:$it", kind)
                    }
                    "close" -> { old = context.world; context.close(); closed = true; sample(label, kind) }
                    "reopen" -> {
                        check(context.reopen(scenario) is InitResult.Accepted)
                        closed = false; generation++; step = 0
                        context.enablePathDiagnostics(context.world); sample(label, kind)
                    }
                    "stale-world" -> {
                        val result = try { context.observePlayer(old, "p1"); "unexpected-accepted" }
                            catch (failure: KnowledgeException) { failure.code }
                        sample(label, kind, result)
                    }
                    "project-rejected" -> {
                        val result = try { context.acceptVisibleTerrain(context.world, "p1", emptyList()); "unexpected-accepted" }
                            catch (failure: KnowledgeException) { failure.code }
                        sample(label, kind, result)
                    }
                    else -> throw InputError("Unsupported automatic fixture operation $kind")
                }
            }
            rows += record("p002-completion", "caseId" to s(fixture.text("caseId")), "label" to s("completion"), "status" to s("completed"))
            return rows
        } finally { context.close() }
    }

    private fun knowledge(value: KnowledgeSnapshot) = obj("worldId" to s(value.worldId), "playerId" to s(value.playerId),
        "revision" to n(value.revision), "width" to n(value.width), "height" to n(value.height),
        "cells" to array(value.cells.map { obj("visible" to b(it.visible), "lastKnownBlocked" to it.lastKnownBlocked?.let(::b),
            "lastKnownGroundGid" to it.lastKnownGroundGid?.let(::n)) }))

    /** Validate identities before normalization strips host world/sample IDs. */
    fun validateRaw(rows: List<JsonObject>, model: JsonObject, fixture: JsonObject, issue: (Int, String) -> Unit) {
        val id = fixture.text("caseId")
        val worlds = mutableMapOf<Long, String>()
        val noFog = fixture.required("noFog") == b(true)
        val width = model.integer("width")
        val height = model.integer("height")
        for (row in rows.filter { it["kind"] == s("p002-sample") }) {
            try {
                val label = row.text("label")
                if (row.text("operation") == "close") {
                    if (listOf("players", "observation", "knowledge").any { row.required(it) != JsonNull }) issue(3, "$id closed world still exposes state")
                    continue
                }
                val host = row.objectAt("observation")
                val world = host.text("worldId")
                if (world.isBlank() || host.text("sampleId").isBlank()) issue(3, "$id empty raw identity")
                val generation = row.integer("generation")
                val previous = worlds.putIfAbsent(generation, world)
                if (previous != null && previous != world || worlds.any { it.key != generation && it.value == world }) issue(3, "$id stale or inconsistent raw world")
                if (host.text("scenarioId") != id) issue(3, "$id host scenario identity differs")
                val units = host.arrayAt("units")
                if (units.size != 1 || units.single().jsonObject.text("unitId") != "u1") issue(3, "$id host unit identity differs")
                val unit = units.single().jsonObject
                if (unit.text("ownerPlayerId") != "p1" || unit.text("typeId") != "tank") issue(3, "$id host ownership differs")
                for (key in listOf("heading", "speed", "angularVelocity")) unit.number(key)
                val position = unit.objectAt("position")
                val radius = model.number("radius")
                if (position.number("x") !in radius..(width * model.integer("tileWidth") - radius) ||
                    position.number("y") !in radius..(height * model.integer("tileHeight") - radius)) issue(2, "$id circle outside map at $label")
                unit.arrayAt("pathPoints").forEach { it.jsonObject.number("x"); it.jsonObject.number("y") }
                host.arrayAt("events"); host.arrayAt("actionResults")
                val players = row.objectAt("players")
                if (players.keys != setOf("p1", "p2")) issue(3, "$id incomplete player observations")
                for (player in listOf("p1", "p2")) {
                    val entry = players.objectAt(player)
                    val observed = entry.objectAt("observation")
                    if (observed.text("worldId") != world || observed.text("scenarioId") != id || observed.text("sampleId").isBlank() ||
                        observed.integer("stepIndex") != host.integer("stepIndex") || observed.number("simulationTime") != host.number("simulationTime")) issue(3, "$id mismatched player world/time")
                    observed.arrayAt("units"); observed.arrayAt("events"); observed.arrayAt("actionResults")
                    if (noFog) {
                        if (entry.required("vision") != JsonNull || entry.required("knowledge") != JsonNull || row.required("knowledge") != JsonNull)
                            issue(3, "$id NoFog has automatic state")
                    } else {
                        val view = entry.objectAt("vision")
                        val store = entry.objectAt("knowledge")
                        for (owned in listOf(view, store)) if (owned.text("worldId") != world || owned.text("playerId") != player) issue(3, "$id mismatched vision/knowledge world")
                        if (store != row.objectAt("knowledge").objectAt(player)) issue(3, "$id player/host knowledge differs")
                        if (store.integer("width") != width || store.integer("height") != height || store.arrayAt("cells").size.toLong() != width * height) issue(3, "$id incomplete knowledge cells")
                        view.integer("epoch"); store.integer("revision")
                        for (field in listOf("basicCells", "effectiveCells")) {
                            val cells = view.arrayAt(field).map { it.jsonPrimitive.long }
                            if (cells != cells.distinct().sorted() || cells.any { it !in 0 until width * height }) issue(3, "$id invalid visible cells")
                        }
                        store.arrayAt("cells").forEach { value ->
                            val cell = value.jsonObject
                            cell.required("visible").jsonPrimitive.boolean
                            if (cell.required("lastKnownBlocked") != JsonNull) cell.required("lastKnownBlocked").jsonPrimitive.boolean
                            if (cell.required("lastKnownGroundGid") != JsonNull) cell.required("lastKnownGroundGid").jsonPrimitive.int
                        }
                    }
                }
                val searches = row.arrayAt("searches")
                if (row.integer("searchCount") != searches.size.toLong()) issue(3, "$id search count differs")
                for (value in searches) {
                    val sample = value.jsonObject
                    for (field in listOf("actionId", "unitId", "reason", "outcome")) sample.text(field)
                    if (sample.integer("actualSearchCalls") <= 0 || sample.required("usedPlayerKnowledge") != b(!noFog)) issue(3, "$id search source/count differs")
                }
            } catch (failure: Exception) { issue(3, "$id invalid automatic raw sample: ${failure.message}") }
        }
    }
}
