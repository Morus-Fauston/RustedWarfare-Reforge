package com.rustedwarfare.reforge.testing

import kotlinx.serialization.json.*

/** Independent construction identities tied to formal commands and prior births. */
internal object ConstructionDeclaration {
    fun buildings(scenario: JsonObject, contract: JsonObject): List<JsonObject> =
        DynamicUnitDeclaration.validate(scenario, contract).second
}
