package com.rustedwarfare.reforge.core.content

/** The native experimental factory's current qualified menu; no extra technology purchase. */
internal object ExperimentalFactoryRules {
    fun supports(d: UnitDefinition): Boolean = d.id == "experimentalLandFactory" &&
        d.className == "NativeExperimentalFactoryFixture" && d.techLevel == 2 && d.radius == 55.0 &&
        d.displayRadius == 55.0 && d.totalFrames == 1 && d.imageScale == 1.0 && d.fogOfWarSightRange == 15 &&
        d.maximumHealth == 3200.0 && d.durability?.maxDamagePerHit == null && d.durability?.deadImagePath != null &&
        d.movement == MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false) &&
        d.build == BuildProfile(11000L, .00035f) && d.construction == ConstructionProfile(11000L, .00035f) &&
        d.production == ProductionProfile(listOf("nautilusSubmarineLand"), FactoryExitProfile.NATIVE_EXPERIMENTAL) &&
        d.building?.let { it.footprint == BuildingFootprint(-2, -2, 2, 2) &&
            it.placementFootprint == BuildingFootprint(-2, -2, 2, 4) && it.backImagePath != null &&
            it.anchor == null && !it.requiresResourcePool && it.foundationMovementType == null } == true &&
        d.directHitTargetKind == DirectHitTargetKind.NATIVE_BUILDING && d.upgrades == null &&
        d.combat == null && d.builder == null && d.income == null && d.pointDefense == null &&
        d.deathExplosion == null && d.mech == null && d.rotor == null && d.submarine == null &&
        d.amphibiousJet == null && d.transport == null && d.energy == null && d.customActions == null &&
        d.shield == null && d.maintenance == null && d.groundHeight == null && d.fixedUnderwaterTarget == null &&
        !d.authoredWaterCombat
}
