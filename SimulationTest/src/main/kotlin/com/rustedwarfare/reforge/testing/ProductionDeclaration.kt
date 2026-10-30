package com.rustedwarfare.reforge.testing

import kotlinx.serialization.json.*

/** Independent expected births bind a declared command and exact sampling boundary. */
internal object ProductionDeclaration {
    fun products(scenario: JsonObject, contract: JsonObject): List<JsonObject> {
        val products = contract["producedUnits"]?.jsonArray?.map { it.jsonObject } ?: return emptyList()
        val initial = scenario.arrayAt("units").map { it.jsonObject }
        val ids = mutableSetOf<String>()
        for (product in products) {
            if (product.keys != setOf("unitId", "productionActionId", "producerId", "owner", "typeId", "unitNonce", "birthStepIndex"))
                throw InputError("Invalid produced unit declaration")
            val nonce = product.id("unitNonce")
            if (!Regex("[a-f0-9]{32}").matches(nonce) || product.id("unitId") != "produced:${product.id("owner")}:$nonce" ||
                !ids.add(product.id("unitId")) || initial.any { it.id("unitId") == product.id("unitId") })
                throw InputError("Invalid or duplicate produced identity")
            val action = scenario.arrayAt("actions").map { it.jsonObject }.singleOrNull { it.id("actionId") == product.id("productionActionId") }
                ?: throw InputError("Missing production action")
            val producer = initial.singleOrNull { it.id("unitId") == product.id("producerId") }
                ?: throw InputError("Missing declared producer")
            if (action.text("kind") != "produce" || action.id("actor") != product.id("owner") ||
                action.id("unitId") != producer.id("unitId") || producer.id("owner") != product.id("owner") ||
                action.id("productionTypeId") != product.id("typeId") || action.id("unitNonce") != nonce ||
                product.integer("birthStepIndex") < action.integer("atStep") ||
                product.integer("birthStepIndex") >= scenario.objectAt("termination").arrayAt("steps").size)
                throw InputError("Produced identity differs from its declared command or boundary")
        }
        return products
    }

    fun born(products: List<JsonObject>, row: JsonObject): List<JsonObject> =
        if (row["phase"] == s("initial")) emptyList() else products.filter {
            it.integer("birthStepIndex") <= row.objectAt("observation").integer("stepIndex")
        }
}
