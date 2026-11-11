package com.rustedwarfare.reforge.core.content

/** Qualification for the initial mobile production slice, shared by direct-world admission. */
internal object NautilusRules {
    fun acceptsProduct(d: UnitDefinition): Boolean = d.id == "hoverTank" && d.build == BuildProfile(450L, .002f) &&
        d.movement.type == MovementType.HOVER && d.movement.hover != null && d.radius == 7.0 &&
        d.displayRadius == 9.0 && d.maximumHealth == 150.0 && d.combat != null && d.transportSlots == 1 &&
        d.building == null && d.transport == null

    fun supports(d: UnitDefinition): Boolean = d.id == "nautilusSubmarine" && d.className == "CustomUnitMetadata" &&
        d.radius == 36.0 && d.displayRadius == 36.0 && d.totalFrames == 1 && d.imageScale == 1.0 &&
        d.movement == MovementProfile(MovementType.WATER, .45, .015, .05, .8, .05, false, false, .6) &&
        d.durability?.let { it.maxHp == 4000.0 && it.deadImagePath != null && it.maxDamagePerHit == null } == true &&
        d.production == ProductionProfile(listOf("hoverTank"), FactoryExitProfile.CUSTOM_NAUTILUS, 2f) &&
        d.build == BuildProfile(18000L, .0003f) && d.energy == EnergyProfile(6f, .01f, .01f, true) &&
        d.transport == TransportProfile.NAUTILUS_SUBMERGED && d.transportSlots == 1 && d.selfRegenRate == .1f &&
        d.fogOfWarSightRange == 24 && d.techLevel == 2 && d.combat == null && d.building == null &&
        d.builder == null && d.construction == null && d.upgrades == null && d.income == null &&
        d.pointDefense == null && d.deathExplosion == null && d.mech == null && d.rotor == null &&
        d.fixedUnderwaterTarget == null && d.submarine == null && d.amphibiousJet == null && d.shield == null &&
        d.customActions == null && d.groundHeight == null && d.maintenance == null && d.reclaimPrice == null &&
        !d.authoredWaterCombat && d.directHitTargetKind == DirectHitTargetKind.ORDINARY
}
