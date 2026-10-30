package com.rustedwarfare.reforge.testing

import kotlinx.serialization.json.*

/** Structural checks shared by collection and archive comparison; never loads or simulates content. */
internal object ScenarioDeclaration {
    fun validate(value: JsonObject) {
        value.header("scenario"); value.id("scenarioId")
        val content = value.objectAt("content")
        val references = listOf(content.objectAt("map")) + unitReferences(content) +
            (content["dependencies"]?.jsonArray?.map { it.jsonObject } ?: emptyList())
        references.forEach {
            it.id("id")
            if (!Regex("[a-f0-9]{64}").matches(it.text("sha256"))) throw InputError("Invalid content identity hash")
        }
        unique(references.map { it.id("id") }, "content")
        val coordinate = value.objectAt("coordinateModel")
        coordinate.id("id")
        listOf("space", "xAxis", "yAxis", "origin", "unit", "valueType", "precision").forEach { coordinate.text(it) }
        val time = value.objectAt("timeModel")
        time.id("id"); time.text("deltaUnit"); time.text("precision")
        if (time.text("stepRule") != "fixed-explicit-delta") throw InputError("Unsupported step rule")
        if (value.text("angleUnit") != "degree-clockwise-zero-up") throw InputError("Unsupported angle unit")
        val players = value.arrayAt("players").map { it.jsonObject }
        unique(players.map { it.id("playerId") }, "player")
        if (players.isEmpty() || players.any { it.text("control") !in setOf("local", "none") }) throw InputError("Invalid players")
        value["automaticVision"]?.let { declaration ->
            val vision = declaration.jsonObject
            if (vision.keys != setOf("retention", "pathSource", "shareAlliedVision", "observers", "grid") ||
                vision.text("retention") !in setOf("MEMORY", "HARDCORE_FORGET") ||
                vision.text("pathSource") !in setOf("PLAYER_KNOWLEDGE", "ORIGINAL_COMPATIBILITY") ||
                vision.required("shareAlliedVision").jsonPrimitive.booleanOrNull == null ||
                (vision.text("retention") == "HARDCORE_FORGET" && vision.text("pathSource") != "PLAYER_KNOWLEDGE"))
                throw InputError("Invalid automatic vision declaration")
            val grid = vision.objectAt("grid")
            if (grid.keys != setOf("width", "height") || grid.integer("width") <= 0 || grid.integer("height") <= 0 ||
                grid.integer("width") > Int.MAX_VALUE || grid.integer("height") > Int.MAX_VALUE ||
                grid.integer("width") * grid.integer("height") > Int.MAX_VALUE)
                throw InputError("Invalid declared knowledge grid")
            val observers = vision.arrayAt("observers").map { it.jsonPrimitive.content }
            unique(observers, "observer")
            if (observers.isEmpty() || observers.any { id -> players.none { it.id("playerId") == id } })
                throw InputError("Unknown or empty player observer")
        }
        val units = value.arrayAt("units").map { it.jsonObject }
        unique(units.map { it.id("unitId") }, "unit")
        if (units.isEmpty()) throw InputError("Empty unit declaration")
        units.forEach { unit ->
            if (players.none { it.id("playerId") == unit.id("owner") }) throw InputError("Unknown owner")
            unit.id("type"); point(unit.objectAt("position")); unit.number("heading")
        }
        val termination = value.objectAt("termination")
        if (termination.text("kind") != "explicit-steps" || termination.required("required") != b(true)) throw InputError("Explicit required sampling schedule is required")
        val steps = termination.arrayAt("steps").map { it.jsonObject }
        if (steps.isEmpty() || steps.withIndex().any { (i, step) ->
            step.integer("stepIndex") != i.toLong() || step.number("delta") != 1.0 || step.text("deltaUnit") != time.text("deltaUnit")
        }) throw InputError("Expected contiguous steps from zero, delta=1 and matching deltaUnit")
        val actions = value.arrayAt("actions").map { it.jsonObject }
        unique(actions.map { it.id("actionId") }, "action")
        actions.forEach { action ->
            if (action.integer("atStep") !in steps.indices.map(Int::toLong)) throw InputError("Action outside declared schedule")
            action.id("actor"); action.id("unitId")
            validateAction(action)
        }
        value["combatContract"]?.jsonObject?.let { combat ->
            for (key in listOf("worldSeed", "initialFrameCounter"))
                if (combat.integer(key) !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw InputError("Combat integer out of range")
            val ids = combat.objectAt("unitObjectIds")
            if (ids.keys != units.map { it.id("unitId") }.toSet() ||
                ids.keys.any { ids.integer(it) <= 0 } || ids.values.distinct().size != ids.size ||
                combat.integer("nextObjectId") <= (ids.keys.maxOfOrNull { ids.integer(it) } ?: 0)) throw InputError("Invalid combat object identity")
        }
        value["economyContract"]?.jsonObject?.objectAt("initialBalances")?.let { balances ->
            if (balances.keys != players.map { it.id("playerId") }.toSet() || balances.keys.any { balances.integer(it) < 0 })
                throw InputError("Invalid initial balances")
        }
    }
    fun unitReferences(content: JsonObject): List<JsonObject> {
        if (content.containsKey("unitVariant") == content.containsKey("unitVariants")) throw InputError("Declare one unitVariant or unitVariants")
        val units = content["unitVariant"]?.let { listOf(it.jsonObject) }
            ?: content.arrayAt("unitVariants").map { it.jsonObject }
        if (units.isEmpty()) throw InputError("Empty content units")
        units.forEach { if ((it["loader"]?.jsonPrimitive?.content ?: "variant") !in
            setOf("variant", "combat", "factory", "builder", "command-center", "turret", "turret-upgrades", "point-defense", "economy", "economy-upgrades"))
            throw InputError("Unsupported content loader") }
        return units
    }
    val actionKinds = setOf("select", "move", "stop", "produce", "cancel-production", "attack", "patrol", "upgrade", "cancel-upgrade", "build")
    fun validateAction(action: JsonObject) {
        when (action.text("kind")) {
            "move" -> point(action.objectAt("target"))
            "build" -> {
                point(action.objectAt("target")); action.id("buildingTypeId"); action.id("buildingNonce")
                if (action.required("append") !in setOf(b(true), b(false))) throw InputError("Expected build append boolean")
            }
            "patrol" -> { point(action.objectAt("target")); if (action.required("append").jsonPrimitive.booleanOrNull == null) throw InputError("Expected patrol append boolean") }
            "attack" -> action.id("targetUnitId")
            "produce" -> { action.id("productionTypeId"); action.id("unitNonce") }
            "cancel-production" -> action.id("productionTypeId")
            "upgrade", "cancel-upgrade" -> action.id("upgradeId")
            "select", "stop" -> Unit
            else -> throw InputError("Unsupported action")
        }
    }
    private fun unique(values: List<String>, kind: String) {
        if (values.distinct().size != values.size) throw InputError("Duplicate $kind identity")
    }
    private fun point(value: JsonObject) { value.number("x"); value.number("y") }
}
