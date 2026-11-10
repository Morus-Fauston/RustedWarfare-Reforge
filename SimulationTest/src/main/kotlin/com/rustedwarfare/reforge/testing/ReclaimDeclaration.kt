package com.rustedwarfare.reforge.testing

import kotlinx.serialization.json.*

/** A removal is a separate, fixed contract bound to an existing reclaim action. */
internal object ReclaimDeclaration {
    fun removals(scenario: JsonObject, contract: JsonObject): List<JsonObject> {
        val removed = contract["reclaimedUnits"]?.jsonArray?.map { it.jsonObject } ?: return emptyList()
        val definitions = ScenarioDeclaration.unitReferences(scenario.objectAt("content")).associateBy { it.id("id") }
        if (!scenario.containsKey("maintenanceContract") && definitions.values.none { it["loader"] == s("repair-bay") } &&
            removed.isNotEmpty()) throw InputError("Reclaim requires maintenance")
        val (products, buildings) = DynamicUnitDeclaration.validate(scenario, contract)
        val all = scenario.arrayAt("units").map { it.jsonObject } + products + buildings
        val ids = mutableSetOf<String>()
        for (change in removed) {
            if (change.keys != setOf("unitId", "reclaimActionId", "workerId", "owner", "removalStepIndex") || !ids.add(change.id("unitId")))
                throw InputError("Invalid or duplicate reclaim declaration")
            val action = scenario.arrayAt("actions").map { it.jsonObject }.singleOrNull { it.id("actionId") == change.id("reclaimActionId") }
                ?: throw InputError("Missing reclaim action")
            val target = all.singleOrNull { it.id("unitId") == change.id("unitId") } ?: throw InputError("Unknown reclaimed target")
            val worker = all.singleOrNull { it.id("unitId") == change.id("workerId") } ?: throw InputError("Unknown reclaim worker")
            fun type(unit: JsonObject) = (unit["typeId"] ?: unit.required("type")).jsonPrimitive.content
            val native = definitions[type(worker)]?.get("loader") == s("repair-bay")
            val registered = scenario["maintenanceContract"]?.jsonObject?.objectAt("basePrices")?.containsKey(type(target)) == true
            val step = change.integer("removalStepIndex")
            if (action.text("kind") != "reclaim" || action.id("targetUnitId") != target.id("unitId") ||
                action.id("unitId") != worker.id("unitId") ||
                !native && definitions[type(worker)]?.get("loader") != s("builder") ||
                !registered && (!native || definitions[type(target)]?.get("loader") !in setOf(s("builder"), s("declared-tank"))) ||
                listOf(action.id("actor"), target.id("owner"), worker.id("owner")).any { it != change.id("owner") } ||
                listOf(target, worker).any { (it["birthStepIndex"]?.jsonPrimitive?.long ?: -1L) >= action.integer("atStep") } ||
                step < action.integer("atStep") || step >= scenario.objectAt("termination").arrayAt("steps").size)
                throw InputError("Reclaim differs from its command, owner, worker, target or boundary")
        }
        return removed
    }
}
