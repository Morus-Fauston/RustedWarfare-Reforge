package com.rustedwarfare.reforge.testing

import kotlinx.serialization.json.*

/** Independent finite AA action transitions, never inferred from a recorded type. */
internal object UnitActionDeclaration {
    val types = setOf("aaBeamGunship", "aaBeamGunship_afterburn")

    fun conversions(scenario: JsonObject, contract: JsonObject): List<JsonObject> {
        val declarations = contract["unitActionConversions"]?.jsonArray?.map { it.jsonObject } ?: return emptyList()
        val objects = scenario.arrayAt("units").map { it.jsonObject } +
            ProductionDeclaration.products(scenario, contract) + ConstructionDeclaration.buildings(scenario, contract)
        val current = objects.associate { it.id("unitId") to (it["typeId"] ?: it.required("type")).jsonPrimitive.content }.toMutableMap()
        val boundaries = objects.associate { it.id("unitId") to (it["birthStepIndex"]?.jsonPrimitive?.long ?: -1L) }.toMutableMap()
        val definitions = ScenarioDeclaration.unitReferences(scenario.objectAt("content")).associateBy { it.id("id") }
        val used = mutableSetOf<String>()
        for (change in declarations.sortedBy { it.integer("completionStepIndex") }) {
            if (change.keys != setOf("unitId", "actionId", "sourceTypeId", "targetTypeId", "completionStepIndex"))
                throw InputError("Invalid unit action conversion declaration")
            val id = change.id("unitId")
            val unit = objects.singleOrNull { it.id("unitId") == id } ?: throw InputError("Unknown unit action object")
            val source = change.id("sourceTypeId"); val target = change.id("targetTypeId")
            val completion = change.integer("completionStepIndex")
            if (source !in types || target !in types || current[id] != source ||
                listOf(source, target).any { definitions[it]?.get("loader") != s("aa-beam-gunship") } ||
                completion <= boundaries.getValue(id) || completion >= scenario.objectAt("termination").arrayAt("steps").size)
                throw InputError("Unit action conversion differs from its finite type or boundary")
            if (change.required("actionId") == JsonNull) {
                if (source != "aaBeamGunship_afterburn" || target != "aaBeamGunship")
                    throw InputError("Unsupported automatic unit action conversion")
            } else {
                val actionId = change.id("actionId")
                val action = scenario.arrayAt("actions").map { it.jsonObject }.singleOrNull { it.id("actionId") == actionId }
                    ?: throw InputError("Missing unit action command")
                if (!used.add(actionId) || action.text("kind") != "unit-action" || action.id("unitId") != id ||
                    action.id("actor") != unit.id("owner") || action["cancelQueued"] != b(false) ||
                    action.integer("atStep") <= boundaries.getValue(id) || completion < action.integer("atStep") ||
                    when (action.id("actionName")) {
                        "afterBurn" -> target != "aaBeamGunship_afterburn"
                        "cancelBurn" -> source != "aaBeamGunship_afterburn" || target != "aaBeamGunship"
                        else -> true
                    }) throw InputError("Unit conversion differs from its normal action")
            }
            current[id] = target; boundaries[id] = completion
        }
        return declarations
    }
}
