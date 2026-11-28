package com.rustedwarfare.reforge.testing

import kotlinx.serialization.json.*

/** Replays paid commands and a scalar queue clock, without loading or invoking the engine. */
internal object NuclearTraceValidator {
    private data class Work(val name: String, var progress: Float = 0f, val target: JsonElement = JsonNull)
    private data class Stock(
        var ammo: Int = 0,
        val queue: MutableList<Work> = mutableListOf(),
        var doors: Float = 0f,
        var opening: Boolean = false,
        var base: Float = 0f,
        var weight: Float = 0f,
    )

    private val navalForms = setOf("nautilusSubmarine", "nautilusSubmarineSurface", "nautilusSubmarineLand")

    internal class Session(
        private val scenario: JsonObject,
        contract: JsonObject,
        private val invalid: (String) -> Nothing,
    ) {
        private val launchers: Set<String>
        private val interceptors: Set<String>
        val ids: Set<String>
        private val states = mutableMapOf<String, Stock>()
        private val navalSources = mutableSetOf<String>()
        private val declaredNukeTargets: List<Pair<Double, Double>>

        init {
            val declarations = scenario.arrayAt("units").map { it.jsonObject } + ConstructionDeclaration.buildings(scenario, contract)
            fun declared(type: String) = declarations.filter { (it["typeId"] ?: it["type"]) == s(type) }.map { it.id("unitId") }.toSet()
            launchers = declared("nukeLauncherC")
            interceptors = declared("antiNukeLauncherC")
            ids = launchers + interceptors
            declaredNukeTargets = if (ids.isEmpty()) emptyList() else {
                scenario.arrayAt("actions").mapNotNull { elem ->
                    val a = elem.jsonObject
                    if (a["actionName"] != s("launchNuke")) null
                    else (a["target"] as? JsonObject)?.let { it.number("x") to it.number("y") }
                }
            }
        }

        fun accept(row: JsonObject) {
            if (ids.isEmpty()) return
            if (row["kind"] == s("action-result") && row.objectAt("result")["status"] == s("accepted")) {
                val action = row.objectAt("action")
                if (action["kind"] == s("unit-action") && action.id("unitId") in ids) {
                    val state = states[action.id("unitId")] ?: invalid("Action precedes silo registration")
                    val name = action.id("actionName")
                    val interceptor = action.id("unitId") in interceptors
                    val build = if (interceptor) "buildAntiNuke" else "buildNuke"
                    val limit = if (interceptor) 12 else 4
                    if (name != build && (interceptor || name != "launchNuke")) invalid("Unapproved nuclear action")
                    if (action["cancelQueued"] == b(true)) {
                        val index = state.queue.indexOfLast { it.name == name }
                        if (index < 0) invalid("Cancelled nuclear work was not queued")
                        state.queue.removeAt(index)
                        if (name == "launchNuke") state.ammo++
                    } else if (name == build) {
                        if (state.ammo + state.queue.count { it.name == name } >= limit) invalid("Accepted over-limit ammunition order")
                        state.queue += Work(name)
                    } else {
                        if (state.ammo <= 0) invalid("Accepted launch has no paid ammunition")
                        val target = action.objectAt("target")
                        target.number("x"); target.number("y")
                        state.ammo--
                        val index = state.queue.indexOfFirst { it.name != "launchNuke" }.takeIf { it >= 0 } ?: state.queue.size
                        state.queue.add(index, Work(name, target = obj("x" to n(target.number("x")), "y" to n(target.number("y")))))
                    }
                }
            }
            if (row["kind"] != s("observation")) return
            val sample = row.objectAt("observation")
            for (elem in sample.arrayAt("units")) {
                val u = elem.jsonObject
                if (u.id("typeId") in navalForms) navalSources += u.id("unitId")
            }
            val completions = mutableListOf<String>()
            val shots = sample.arrayAt("projectileEvents").map { it.jsonObject }
            for (elem in sample.arrayAt("units")) {
                val unit = elem.jsonObject
                val id = unit.id("unitId")
                if (id !in ids) continue
                val interceptor = id in interceptors
                val build = if (interceptor) "buildAntiNuke" else "buildNuke"
                val limit = if (interceptor) 12 else 4
                val state = states.getOrPut(id) { Stock() }
                val ready = unit["alive"] == b(true) && unit.number("constructionProgress") >= 1 && unit["carriedBy"] == JsonNull
                if (unit["alive"] == b(false)) {
                    state.ammo = 0; state.queue.clear(); state.doors = 0f
                } else if (row["phase"] != s("initial") && ready) {
                    val open = state.queue.firstOrNull()?.name == "launchNuke"
                    if (open != state.opening) { state.opening = open; state.base = state.doors; state.weight = 1f }
                    if (state.weight > 0f) state.weight -= if (open) 1f / 120f else 1f / 150f
                    val desired = if (open) 1f else 0f
                    state.doors = if (state.weight > 0f) desired * (1f - state.weight) + state.base * state.weight else desired
                    state.queue.firstOrNull()?.let { head ->
                        head.progress += when (head.name) {
                            "buildAntiNuke" -> .0007f
                            "buildNuke" -> .0003f
                            else -> 1f / 210f
                        }
                        if (head.progress >= 1f) {
                            state.queue.removeAt(0)
                            if (head.name == build) state.ammo++
                            completions += id
                        }
                    }
                    if (interceptor) {
                        state.ammo -= shots.count {
                            it["kind"] == s("born") && it.objectAt("projectile")["appearance"] == s("INTERCEPTOR") &&
                                it.objectAt("projectile")["sourceUnitId"] == s(id)
                        }
                        if (state.ammo < 0) invalid("Interceptor launched without ammunition")
                    }
                }
                val actual = unit.objectAt("unitActions")
                if (actual.keys != setOf("actions", "automaticCooldown", "stockpile", "queue") ||
                    actual["stockpile"] != n(state.ammo) || actual.number("automaticCooldown") != 0.0
                ) {
                    invalid("Stockpile differs from paid commands and queue completions")
                }
                val queue = array(state.queue.map { obj("name" to s(it.name), "progress" to JsonPrimitive(it.progress), "target" to it.target) })
                if (actual["queue"] != queue) {
                    invalid("Nuclear queue target, priority or independent progress differs at ${sample["stepIndex"]}: ${actual["queue"]} expected $queue")
                }
                if (!interceptor && unit["nuclearDoors"] != JsonPrimitive(state.doors)) {
                    invalid("Door pose differs from the launch queue clock")
                }
                val actions = actual.objectAt("actions")
                if (actions.keys != if (interceptor) setOf(build) else setOf(build, "launchNuke")) invalid("Missing nuclear controls")
                for ((name, value) in actions) {
                    val entry = value.jsonObject
                    val index = state.queue.indexOfFirst { it.name == name }
                    val available = ready && if (name == "launchNuke") state.ammo > 0 else state.ammo + state.queue.count { it.name == build } < limit
                    if (entry.keys != setOf("queueIndex", "progress", "cooldownRemaining", "available") ||
                        entry["queueIndex"] != (if (index < 0) JsonNull else n(index)) || entry["cooldownRemaining"] != n(0) ||
                        entry["progress"] != (if (index < 0) JsonNull else JsonPrimitive(state.queue[index].progress)) ||
                        entry["available"] != b(available)
                    ) {
                        invalid("Nuclear control feedback differs from stock and queue")
                    }
                }
            }
            val events = sample.arrayAt("events").map { it.jsonObject }.filter {
                it["unitId"]?.jsonPrimitive?.content in ids && it["kind"] == s("unit-action-completed")
            }.map { it.id("unitId") }
            if (events.sorted() != completions.sorted()) invalid("Missing or premature nuclear completion")
            fun projectile(shot: JsonObject) {
                if (shot["appearance"] == s("INTERCEPTOR")) {
                    val speed = shot.objectAt("ballistic").number("speed")
                    val source = shot["sourceUnitId"]?.jsonPrimitive?.content
                    val naval = source in navalSources
                    if (!naval && source !in interceptors || shot["sourceWeaponName"] != s(if (naval) "antiNuke" else "silo") ||
                        shot["targetUnitId"] != JsonNull || speed !in .2..6.5 ||
                        shot.number("height") !in (if (naval) -8.0 else 0.0)..110.0
                    ) {
                        invalid("Invalid interceptor identity or flight profile")
                    }
                    return
                }
                if (shot["appearance"] != s("NUCLEAR")) return
                if (shot["sourceUnitId"]?.jsonPrimitive?.content !in launchers || shot["sourceWeaponName"] != s("siloTop") ||
                    shot["targetUnitId"] != JsonNull || shot["missile"] != JsonNull
                ) {
                    invalid("Invalid nuclear projectile identity")
                }
                val flight = shot.objectAt("ballistic")
                if (flight.keys != setOf("speed", "reachedApex", "rawAz") || flight.number("speed") !in .1..2.700001 ||
                    flight["reachedApex"] !in setOf(b(true), b(false)) || shot.number("height") !in 0.0..110.0
                ) {
                    invalid("Invalid nuclear flight profile")
                }
                flight.number("rawAz")
                val ground = shot.objectAt("ground")
                if (ground.keys != setOf("targetPosition", "targetHeight", "verticalSpeed", "distanceSquared") ||
                    ground.number("targetHeight") != 0.0 || ground["distanceSquared"] != JsonNull ||
                    ground.number("verticalSpeed") != flight.number("speed")
                ) {
                    invalid("Invalid nuclear ground target")
                }
                val point = ground.objectAt("targetPosition")
                val px = point.number("x")
                val py = point.number("y")
                if (declaredNukeTargets.none { (tx, ty) -> tx == px && ty == py }) {
                    invalid("Projectile target has no declared ground launch")
                }
            }
            sample.arrayAt("projectiles").forEach { projectile(it.jsonObject) }
            shots.forEach { projectile(it.objectAt("projectile")) }
        }
    }

    fun validate(scenario: JsonObject, contract: JsonObject, rows: List<JsonObject>, invalid: (String) -> Nothing): Set<String> {
        val session = Session(scenario, contract, invalid)
        if (session.ids.isEmpty()) return emptySet()
        for (row in rows) session.accept(row)
        return session.ids
    }
}
