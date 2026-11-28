package com.rustedwarfare.reforge.testing

import kotlinx.serialization.json.*

/** Independent queue clock and input-tile predicates; never runs the product simulation. */
internal object NautilusTraceValidator {
    private val forms = setOf("nautilusSubmarine", "nautilusSubmarineSurface", "nautilusSubmarineLand")

    internal class Session(
        scenario: JsonObject,
        contract: JsonObject,
        private val invalid: (String) -> Nothing,
    ) {
        private data class State(var form: String, var timer: Float = 0f, var pending: String? = null, var progress: Float = 0f)

        private val declared: Map<String, JsonObject>
        val identities: Set<String>
        private val width: Long
        private val height: Long
        private val tileWidth: Float
        private val tileHeight: Float
        private val waterSet: Set<Long>
        private val states = mutableMapOf<String, State>()

        init {
            val objects = scenario.arrayAt("units").map { it.jsonObject } + ProductionDeclaration.products(scenario, contract)
            val filtered = objects.filter { (it["typeId"] ?: it["type"])?.jsonPrimitive?.content in forms }
            if (filtered.isEmpty()) {
                declared = emptyMap()
                identities = emptySet()
                width = 0L
                height = 0L
                tileWidth = 1f
                tileHeight = 1f
                waterSet = emptySet()
            } else {
                val terrain = contract["nautilusTerrain"] as? JsonObject
                    ?: throw InputError("Nautilus histories require independent fixed terrain")
                if (terrain.keys != setOf("mapSha256", "tileWidth", "tileHeight", "width", "height", "waterCells") ||
                    terrain["mapSha256"] != scenario.objectAt("content").objectAt("map")["sha256"]
                ) {
                    throw InputError("Nautilus terrain must bind the fixed map")
                }
                val w = terrain.integer("width")
                val h = terrain.integer("height")
                if (w <= 0 || h <= 0 || terrain.number("tileWidth") <= 0 || terrain.number("tileHeight") <= 0) {
                    throw InputError("Invalid Nautilus terrain dimensions")
                }
                val water = terrain.arrayAt("waterCells").map { it.jsonPrimitive.long }
                val waterUnique = water.toSet()
                if (waterUnique.size != water.size || water.any { it !in 0 until w * h }) {
                    throw InputError("Invalid Nautilus terrain cells")
                }
                declared = filtered.associateBy { it.id("unitId") }
                identities = declared.keys
                width = w
                height = h
                tileWidth = terrain.number("tileWidth").toFloat()
                tileHeight = terrain.number("tileHeight").toFloat()
                waterSet = waterUnique
            }
        }

        fun accept(row: JsonObject) {
            if (identities.isEmpty()) return
            if (row["kind"] == s("action-result") && row.objectAt("result")["status"] == s("accepted")) {
                val action = row.objectAt("action")
                if (action["kind"] == s("unit-action") && action.id("unitId") in identities) {
                    val state = states[action.id("unitId")] ?: invalid("Action precedes registration")
                    val name = action.id("actionName")
                    val visible = when (state.form) {
                        "nautilusSubmarine" -> "surface"
                        "nautilusSubmarineSurface" -> "dive"
                        else -> null
                    }
                    if (name != visible) invalid("Accepted action is not exposed by the current form")
                    if (action["cancelQueued"] == b(true)) {
                        if (state.pending != name) invalid("Cancellation has no matching queue item")
                        state.pending = null
                        state.progress = 0f
                    } else {
                        if (state.pending != null) invalid("Duplicate queued conversion")
                        state.pending = name
                        state.progress = 0f
                    }
                }
            }
            if (row["kind"] != s("observation")) return
            val observation = row.objectAt("observation")
            val expected = mutableListOf<Pair<String, String>>()
            for (element in observation.arrayAt("units")) {
                val unit = element.jsonObject
                val id = unit.id("unitId")
                if (id !in identities) continue
                val declaration = declared.getValue(id)
                val first = id !in states
                val state = states.getOrPut(id) {
                    State((declaration["typeId"] ?: declaration.required("type")).jsonPrimitive.content)
                }
                if (!first && row["phase"] != s("initial") && unit["alive"] == b(true) &&
                    unit.number("constructionProgress") >= 1 && (unit["carriedBy"] == null || unit["carriedBy"] == JsonNull)
                ) {
                    state.pending?.let { name ->
                        state.progress += if (name == "surface") 2000f else (1f / 270f) * 2f
                        if (state.progress >= 1f) {
                            state.form = if (name == "surface") "nautilusSubmarineSurface" else "nautilusSubmarine"
                            state.pending = null
                            state.progress = 0f
                            expected += id to "unit-action-completed"
                        }
                    }
                    state.timer = maxOf(0f, state.timer - 1f)
                    val point = unit.objectAt("position")
                    val column = (point.number("x").toFloat() / tileWidth).toInt()
                    val line = (point.number("y").toFloat() / tileHeight).toInt()
                    if (column !in 0 until width || line !in 0 until height) invalid("Position outside declared map")
                    val overWater = line * width + column in waterSet
                    if (state.timer == 0f && (!overWater || state.form == "nautilusSubmarineLand")) {
                        state.form = if (overWater) "nautilusSubmarineSurface" else "nautilusSubmarineLand"
                        state.timer = 60f
                        state.pending = null
                        state.progress = 0f
                        expected += id to "unit-action-automatic"
                    }
                }
                if (unit["typeId"] != s(state.form)) invalid("Form differs from accepted queue and fixed water predicates")
                val actions = unit.objectAt("unitActions")
                if (actions.number("automaticCooldown") != state.timer.toDouble()) invalid("Automatic clock differs")
                for ((name, value) in actions.objectAt("actions")) {
                    val entry = value.jsonObject
                    if (entry["cooldownRemaining"] != n(0) ||
                        entry["queueIndex"] != (if (state.pending == name) n(0) else JsonNull) ||
                        entry["progress"] != (if (state.pending == name) JsonPrimitive(state.progress) else JsonNull)
                    ) {
                        invalid("Queue differs from accepted action and independent float clock")
                    }
                }
            }
            val actual = observation.arrayAt("events").map { it.jsonObject }.filter {
                it["unitId"]?.jsonPrimitive?.content in identities &&
                    it["kind"] in setOf(s("unit-action-completed"), s("unit-action-automatic"))
            }.map { it.id("unitId") to it.text("kind") }
            if (actual.sortedWith(compareBy({ it.first }, { it.second })) != expected.sortedWith(compareBy({ it.first }, { it.second }))) {
                invalid("Missing, premature or extra conversion event")
            }
        }
    }

    fun validate(scenario: JsonObject, contract: JsonObject, rows: List<JsonObject>, invalid: (String) -> Nothing): Set<String> {
        val session = Session(scenario, contract, invalid)
        if (session.identities.isEmpty()) return emptySet()
        for (row in rows) session.accept(row)
        return session.identities
    }
}
