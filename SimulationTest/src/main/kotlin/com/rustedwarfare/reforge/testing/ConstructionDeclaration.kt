package com.rustedwarfare.reforge.testing

import kotlinx.serialization.json.*

/** Independent construction identities tied to formal commands and sampling boundaries. */
internal object ConstructionDeclaration {
    fun buildings(scenario: JsonObject, contract: JsonObject): List<JsonObject> {
        val buildings = contract["constructedUnits"]?.jsonArray?.map { it.jsonObject } ?: return emptyList()
        val products = ProductionDeclaration.products(scenario, contract)
        val initial = scenario.arrayAt("units").map { it.jsonObject }
        val ids = (initial + products).map { it.id("unitId") }.toMutableSet()
        for (building in buildings) {
            if (building.keys != setOf("unitId", "buildActionId", "builderId", "owner", "typeId", "buildingNonce", "birthStepIndex"))
                throw InputError("Invalid constructed unit declaration")
            val nonce = building.id("buildingNonce")
            if (!Regex("[a-f0-9]{32}").matches(nonce) || building.id("unitId") != "constructed:${building.id("owner")}:$nonce" ||
                !ids.add(building.id("unitId"))) throw InputError("Invalid or duplicate constructed identity")
            val action = scenario.arrayAt("actions").map { it.jsonObject }.singleOrNull { it.id("actionId") == building.id("buildActionId") }
                ?: throw InputError("Missing build action")
            val builder = (initial + products).singleOrNull { it.id("unitId") == building.id("builderId") }
                ?: throw InputError("Missing declared builder")
            if (action.text("kind") != "build" || action.id("actor") != building.id("owner") ||
                action.id("unitId") != builder.id("unitId") || builder.id("owner") != building.id("owner") ||
                action.id("buildingTypeId") != building.id("typeId") || action.id("buildingNonce") != nonce ||
                building.integer("birthStepIndex") < action.integer("atStep") ||
                building.integer("birthStepIndex") >= scenario.objectAt("termination").arrayAt("steps").size)
                throw InputError("Constructed identity differs from its command or boundary")
            if (builder in products && builder.integer("birthStepIndex") >= action.integer("atStep"))
                throw InputError("Builder must already exist before its build command")
        }
        return buildings
    }
}
