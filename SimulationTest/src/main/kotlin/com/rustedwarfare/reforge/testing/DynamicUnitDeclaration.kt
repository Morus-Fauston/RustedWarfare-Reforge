package com.rustedwarfare.reforge.testing

import kotlinx.serialization.json.*

/** Validate the declared birth graph without consulting observations or recursing. */
internal object DynamicUnitDeclaration {
    fun validate(scenario: JsonObject, contract: JsonObject): Pair<List<JsonObject>, List<JsonObject>> {
        val products = contract["producedUnits"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        val buildings = contract["constructedUnits"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        val initial = scenario.arrayAt("units").map { it.jsonObject }
        val all = initial + products + buildings
        val ids = all.map { it.id("unitId") }
        if (ids.distinct().size != ids.size) throw InputError("Duplicate dynamic or initial unit identity")
        val actions = scenario.arrayAt("actions").map { it.jsonObject }
        for ((declarations, production) in listOf(products to true, buildings to false)) {
            val command = if (production) "productionActionId" else "buildActionId"
            val parentField = if (production) "producerId" else "builderId"
            val nonceField = if (production) "unitNonce" else "buildingNonce"
            val typeField = if (production) "productionTypeId" else "buildingTypeId"
            val prefix = if (production) "produced" else "constructed"
            for (unit in declarations) {
                if (unit.keys != setOf("unitId", command, parentField, "owner", "typeId", nonceField, "birthStepIndex"))
                    throw InputError("Invalid $prefix unit declaration")
                val nonce = unit.id(nonceField)
                if (!Regex("[a-f0-9]{32}").matches(nonce) || unit.id("unitId") != "$prefix:${unit.id("owner")}:$nonce")
                    throw InputError("Invalid $prefix identity")
                val action = actions.singleOrNull { it.id("actionId") == unit.id(command) }
                    ?: throw InputError("Missing $prefix action")
                val parent = all.singleOrNull { it.id("unitId") == unit.id(parentField) }
                    ?: throw InputError("Missing declared $parentField")
                val parentBirth = parent["birthStepIndex"]?.jsonPrimitive?.long ?: -1L
                val birth = unit.integer("birthStepIndex")
                if (action.text("kind") != (if (production) "produce" else "build"))
                    throw InputError("Dynamic identity command kind differs")
                if (action.id("actor") != unit.id("owner") || action.id("unitId") != parent.id("unitId") ||
                    parent.id("owner") != unit.id("owner") || action.id(typeField) != unit.id("typeId") ||
                    action.id(nonceField) != nonce || parentBirth >= action.integer("atStep") ||
                    birth < action.integer("atStep") || birth >= scenario.objectAt("termination").arrayAt("steps").size)
                    throw InputError("Dynamic identity differs from its command or prior parent birth")
            }
        }
        return products to buildings
    }
}
