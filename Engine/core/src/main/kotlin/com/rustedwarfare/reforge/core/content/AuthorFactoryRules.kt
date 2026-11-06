package com.rustedwarfare.reforge.core.content

/** The author entry selects existing exits; it does not grant another unit's native qualification. */
internal object AuthorFactoryRules {
    fun body(definition: UnitDefinition): Boolean {
        val production = definition.production ?: return false
        val building = definition.building ?: return false
        val construction = definition.construction ?: return false
        val foundation = if (production.exitProfile == FactoryExitProfile.NATIVE_SEA) MovementType.WATER else MovementType.LAND
        return definition.className == "AuthoredBuilding" &&
            definition.movement == MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false) &&
            definition.radius.isFinite() && definition.radius > 0.0 && definition.radius <= 30.0 &&
            definition.maximumHealth?.let { it.isFinite() && it > 0.0 } == true &&
            building.footprint == BuildingFootprint(-1, -1, 1, 1) &&
            building.placementFootprint == BuildingFootprint(-1, -1, 1, 3) &&
            building.foundationMovementType == foundation && building.anchor == null && !building.requiresResourcePool &&
            construction.price >= 0L && construction.rate.isFinite() && construction.rate > 0f &&
            definition.combat == null && definition.builder == null && definition.upgrades == null &&
            definition.income == null && definition.pointDefense == null && definition.mech == null &&
            definition.rotor == null && definition.fixedUnderwaterTarget == null && definition.deathExplosion == null
    }

    fun accepts(exit: FactoryExitProfile, product: UnitDefinition): Boolean {
        val build = product.build ?: return false
        if (build.price < 0L || !build.rate.isFinite() || build.rate <= 0f) return false
        return when (exit) {
            FactoryExitProfile.NATIVE_LAND, FactoryExitProfile.CUSTOM_MECH -> product.movement.type == MovementType.LAND
            FactoryExitProfile.NATIVE_AIR -> product.movement.type == MovementType.AIR && product.rotor != null && product.combat != null
            FactoryExitProfile.NATIVE_SEA -> product.movement.type == MovementType.WATER && product.combat != null
        }
    }

    fun supports(definition: UnitDefinition, definitions: Map<String, UnitDefinition>): Boolean {
        if (!body(definition)) return false
        val production = checkNotNull(definition.production)
        return production.unitTypeIds.isNotEmpty() && production.unitTypeIds.distinct().size == production.unitTypeIds.size &&
            production.unitTypeIds.all { id -> definitions[id]?.let { accepts(production.exitProfile, it) } == true }
    }
}
