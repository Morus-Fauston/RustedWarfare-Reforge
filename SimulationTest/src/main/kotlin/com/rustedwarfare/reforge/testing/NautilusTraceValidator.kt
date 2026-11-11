package com.rustedwarfare.reforge.testing

import kotlinx.serialization.json.*

/** Independent queue clock and input-tile predicates; never runs the product simulation. */
internal object NautilusTraceValidator {
    private val forms = setOf("nautilusSubmarine", "nautilusSubmarineSurface", "nautilusSubmarineLand")

    fun validate(scenario: JsonObject, contract: JsonObject, rows: List<JsonObject>, invalid: (String) -> Nothing): Set<String> {
        val objects = scenario.arrayAt("units").map { it.jsonObject } + ProductionDeclaration.products(scenario, contract)
        val declared = objects.filter { (it["typeId"] ?: it["type"])?.jsonPrimitive?.content in forms }
        if (declared.isEmpty()) return emptySet()
        val terrain = contract["nautilusTerrain"] as? JsonObject
            ?: throw InputError("Nautilus histories require independent fixed terrain")
        if (terrain.keys != setOf("mapSha256", "tileWidth", "tileHeight", "width", "height", "waterCells") ||
            terrain["mapSha256"] != scenario.objectAt("content").objectAt("map")["sha256"])
            throw InputError("Nautilus terrain must bind the fixed map")
        val width = terrain.integer("width"); val height = terrain.integer("height")
        if (width <= 0 || height <= 0 || terrain.number("tileWidth") <= 0 || terrain.number("tileHeight") <= 0)
            throw InputError("Invalid Nautilus terrain dimensions")
        val water = terrain.arrayAt("waterCells").map { it.jsonPrimitive.long }
        if (water.distinct().size != water.size || water.any { it !in 0 until width * height })
            throw InputError("Invalid Nautilus terrain cells")
        data class State(var form: String, var timer: Float = 0f, var pending: String? = null, var progress: Float = 0f)
        val states = mutableMapOf<String, State>()
        val identities = declared.map { it.id("unitId") }.toSet()
        for (row in rows) {
            if (row["kind"] == s("action-result") && row.objectAt("result")["status"] == s("accepted")) {
                val action = row.objectAt("action")
                if (action["kind"] == s("unit-action") && action.id("unitId") in identities) {
                    val state = states[action.id("unitId")] ?: invalid("Action precedes registration")
                    val name = action.id("actionName")
                    val visible = when (state.form) { "nautilusSubmarine" -> "surface"; "nautilusSubmarineSurface" -> "dive"; else -> null }
                    if (name != visible) invalid("Accepted action is not exposed by the current form")
                    if (action["cancelQueued"] == b(true)) {
                        if (state.pending != name) invalid("Cancellation has no matching queue item")
                        state.pending = null; state.progress = 0f
                    } else {
                        if (state.pending != null) invalid("Duplicate queued conversion")
                        state.pending = name; state.progress = 0f
                    }
                }
            }
            if (row["kind"] != s("observation")) continue
            val observation = row.objectAt("observation")
            val expected = mutableListOf<Pair<String, String>>()
            for (unit in observation.arrayAt("units").map { it.jsonObject }.filter { it.id("unitId") in identities }) {
                val id = unit.id("unitId")
                val declaration = declared.single { it.id("unitId") == id }
                val first = id !in states
                val state = states.getOrPut(id) { State((declaration["typeId"] ?: declaration.required("type")).jsonPrimitive.content) }
                if (!first && row["phase"] != s("initial") && unit["alive"] == b(true) &&
                    unit.number("constructionProgress") >= 1 && (unit["carriedBy"] == null || unit["carriedBy"] == JsonNull)) {
                    state.pending?.let { name ->
                        state.progress += if (name == "surface") 2000f else (1f / 270f) * 2f
                        if (state.progress >= 1f) {
                            state.form = if (name == "surface") "nautilusSubmarineSurface" else "nautilusSubmarine"
                            state.pending = null; state.progress = 0f
                            expected += id to "unit-action-completed"
                        }
                    }
                    state.timer = maxOf(0f, state.timer - 1f)
                    val point = unit.objectAt("position")
                    val column = (point.number("x").toFloat() / terrain.number("tileWidth").toFloat()).toInt()
                    val line = (point.number("y").toFloat() / terrain.number("tileHeight").toFloat()).toInt()
                    if (column !in 0 until width || line !in 0 until height) invalid("Position outside declared map")
                    val overWater = line * width + column in water
                    if (state.timer == 0f && (!overWater || state.form == "nautilusSubmarineLand")) {
                        state.form = if (overWater) "nautilusSubmarineSurface" else "nautilusSubmarineLand"
                        state.timer = 60f; state.pending = null; state.progress = 0f
                        expected += id to "unit-action-automatic"
                    }
                }
                if (unit["typeId"] != s(state.form)) invalid("Form differs from accepted queue and fixed water predicates")
                val actions = unit.objectAt("unitActions")
                if (actions.number("automaticCooldown") != state.timer.toDouble()) invalid("Automatic clock differs")
                for ((name, value) in actions.objectAt("actions")) {
                    val entry = value.jsonObject
                    if (entry["cooldownRemaining"] != n(0) || entry["queueIndex"] != (if (state.pending == name) n(0) else JsonNull) ||
                        entry["progress"] != (if (state.pending == name) JsonPrimitive(state.progress) else JsonNull))
                        invalid("Queue differs from accepted action and independent float clock")
                }
            }
            val actual = observation.arrayAt("events").map { it.jsonObject }.filter {
                it["unitId"]?.jsonPrimitive?.content in identities && it["kind"] in setOf(s("unit-action-completed"), s("unit-action-automatic"))
            }.map { it.id("unitId") to it.text("kind") }
            if (actual.sortedWith(compareBy({ it.first }, { it.second })) != expected.sortedWith(compareBy({ it.first }, { it.second })))
                invalid("Missing, premature or extra conversion event")
        }
        return identities
    }
}
