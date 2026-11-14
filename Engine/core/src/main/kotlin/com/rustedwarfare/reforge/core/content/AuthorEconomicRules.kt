package com.rustedwarfare.reforge.core.content

/** An authored conversion changes the installed resource-pool form while retaining its site. */
internal object AuthorEconomicRules {
    data class InvalidAction(val sourceId: String, val key: String?, val field: String, val targetId: String)
    fun body(unit: UnitDefinition): Boolean {
        val building = unit.building ?: return false
        val construction = unit.construction ?: return false
        val income = unit.income ?: return false
        return unit.className == "AuthoredEconomicBuilding" &&
            unit.movement == MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false) &&
            building.requiresResourcePool && building.anchor == FootprintAnchor(10f, 10f) &&
            building.footprint == BuildingFootprint(0, 0, 0, 0) && building.placementFootprint == building.footprint &&
            unit.maximumHealth?.toFloat()?.let { it.isFinite() && it > 0f } == true &&
            unit.radius in 1.0..30.0 && construction.price in 0..999_999_999L && construction.rate in .000001f..1f &&
            income.period in 1f..IncomeProfile.MAX_PERIOD && income.credits in 0f..1_000_000f &&
            unit.combat == null && unit.production == null && unit.builder == null && unit.pointDefense == null &&
            unit.customActions == null && unit.attachmentLayout == null && unit.energy == null && unit.shield?.isValid() != false
    }

    fun invalidAction(source: UnitDefinition, definitions: Map<String, UnitDefinition>,
                      checkReturn: Boolean = true): InvalidAction? {
        if (!body(source)) return InvalidAction(source.id, null, "name", source.id)
        val actions = source.upgrades?.actions.orEmpty()
        if (actions.isEmpty() || actions.size != actions.map { it.actionKey }.toSet().size ||
            actions.size != actions.map { it.targetTypeId }.toSet().size)
            return InvalidAction(source.id, null, "actions", source.id)
        for (action in actions) {
            val invalid = InvalidAction(source.id, action.actionKey, "convertTo", action.targetTypeId)
            val target = definitions[action.targetTypeId]
            if (target == null || target.id != action.targetTypeId || !body(target) || target.id == source.id ||
                source.radius != target.radius || source.building?.foundationMovementType != target.building?.foundationMovementType ||
                source.income?.period != target.income?.period) return invalid
            if (action.rate !in .000001f..1f) return invalid.copy(field = "buildSpeed")
            if (action.price !in 0..999_999_999L) return invalid.copy(field = "price")
            if (action.completionCredits !in 0..999_999_999L) return invalid.copy(field = "addCredits")
            if (!checkReturn) continue
            val reverse = target.upgrades?.actions?.singleOrNull { it.targetTypeId == source.id }
            if (reverse == null) return InvalidAction(target.id, null, "actions", source.id)
            if (action.completionCredits == 0L) {
                if (action.price == 0L) return invalid.copy(field = "price")
                if (reverse.price != 0L) return InvalidAction(target.id, reverse.actionKey, "price", source.id)
                if (reverse.completionCredits !in 1..action.price)
                    return InvalidAction(target.id, reverse.actionKey, "addCredits", source.id)
            } else {
                if (action.price != 0L) return invalid.copy(field = "price")
                if (reverse.completionCredits != 0L || reverse.price < action.completionCredits)
                    return invalid.copy(field = "addCredits")
            }
        }
        return null
    }
}
