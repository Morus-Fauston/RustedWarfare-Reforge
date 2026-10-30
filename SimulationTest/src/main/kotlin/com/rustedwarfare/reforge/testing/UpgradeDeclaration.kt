package com.rustedwarfare.reforge.testing

import kotlinx.serialization.json.*

/** Fixed expected ordinary conversions, independent of runtime observations. */
internal object UpgradeDeclaration {
    fun conversions(scenario: JsonObject, contract: JsonObject): List<JsonObject> {
        val declarations = contract["convertedUnits"]?.jsonArray?.map { it.jsonObject } ?: return emptyList()
        val objects = scenario.arrayAt("units").map { it.jsonObject } +
            ProductionDeclaration.products(scenario, contract) + ConstructionDeclaration.buildings(scenario, contract)
        val types = objects.associate { it.id("unitId") to (it["typeId"] ?: it.required("type")).jsonPrimitive.content }.toMutableMap()
        val boundaries = objects.associate { it.id("unitId") to (it["birthStepIndex"]?.jsonPrimitive?.long ?: -1L) }.toMutableMap()
        val usedActions = mutableSetOf<String>()
        val definitions = ScenarioDeclaration.unitReferences(scenario.objectAt("content")).associateBy { it.id("id") }
        for (change in declarations.sortedBy { it.integer("completionStepIndex") }) {
            if (change.keys != setOf("unitId", "upgradeActionId", "sourceTypeId", "targetTypeId", "completionStepIndex"))
                throw InputError("Invalid conversion declaration")
            val unitId = change.id("unitId")
            val unit = objects.singleOrNull { it.id("unitId") == unitId } ?: throw InputError("Unknown converted unit")
            val action = scenario.arrayAt("actions").map { it.jsonObject }.singleOrNull { it.id("actionId") == change.id("upgradeActionId") }
                ?: throw InputError("Missing upgrade action")
            val source = change.id("sourceTypeId")
            val target = change.id("targetTypeId")
            val supported = when (source) {
                "extractorT1", "extractorT2", "fabricatorT1", "fabricatorT2" ->
                    definitions[source]?.get("loader") == s("economy-upgrades") &&
                        target == source.dropLast(1) + (source.last().digitToInt() + 1) &&
                        action.id("upgradeId") == "upgrade_t${target.last()}"
                "c_turret_t1" -> definitions[source]?.get("loader") in setOf(s("turret-upgrades"), s("turret-construction")) &&
                    (action.id("upgradeId") to target) in setOf("upgrade_gun" to "c_turret_t2_gun", "upgrade_artillery" to "c_turret_t1_artillery")
                else -> false
            }
            val completion = change.integer("completionStepIndex")
            if (!usedActions.add(change.id("upgradeActionId")) || !supported || target !in definitions ||
                types[unitId] != source || action.text("kind") != "upgrade" || action.id("unitId") != unitId ||
                action.id("actor") != unit.id("owner") || action.integer("atStep") <= boundaries.getValue(unitId) ||
                completion < action.integer("atStep") || completion >= scenario.objectAt("termination").arrayAt("steps").size)
                throw InputError("Conversion differs from its ordinary action or boundary")
            types[unitId] = target
            boundaries[unitId] = completion
        }
        return declarations
    }

    fun typeAt(unitId: String, initial: String, changes: List<JsonObject>, row: JsonObject): String =
        ProductionDeclaration.born(changes, row, "completionStepIndex").filter { it.id("unitId") == unitId }
            .maxByOrNull { it.integer("completionStepIndex") }?.id("targetTypeId") ?: initial
}
