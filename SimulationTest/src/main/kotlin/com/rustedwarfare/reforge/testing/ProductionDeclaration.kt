package com.rustedwarfare.reforge.testing

import kotlinx.serialization.json.*

/** Independent expected births bind a declared command and exact sampling boundary. */
internal object ProductionDeclaration {
    fun products(scenario: JsonObject, contract: JsonObject): List<JsonObject> {
        return DynamicUnitDeclaration.validate(scenario, contract).first
    }

    fun born(products: List<JsonObject>, row: JsonObject, boundary: String = "birthStepIndex"): List<JsonObject> =
        if (row["phase"] == s("initial")) emptyList() else products.filter {
            it.integer(boundary) <= row.objectAt("observation").integer("stepIndex")
        }
}
