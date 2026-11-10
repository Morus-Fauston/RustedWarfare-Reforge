package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*

/** Qualification of the explicit native capability; class names and idle flags confer no work ability. */
internal object RepairBay {
    fun supports(unit: UnitDefinition): Boolean =
        unit.maintenance == MaintenanceProfile.NATIVE_REPAIR_BAY && unit.id == "repairbay" &&
            unit.radius == 30.0 && unit.displayRadius == 30.0 && unit.maximumHealth == 1000.0 &&
            unit.totalFrames == 1 && unit.imageScale == 1.0 &&
            unit.movement == MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false) &&
            unit.building == BuildingProfile(BuildingFootprint(-1, -1, 1, 1)) &&
            unit.build == BuildProfile(1500L, .001f) && unit.construction == ConstructionProfile(1500L, .001f) &&
            unit.directHitTargetKind == DirectHitTargetKind.NATIVE_BUILDING &&
            unit.combat == null && unit.production == null && unit.builder == null && unit.income == null &&
            unit.upgrades == null && unit.pointDefense == null && unit.deathExplosion == null &&
            unit.mech == null && unit.rotor == null && unit.fixedUnderwaterTarget == null &&
            !unit.authoredWaterCombat && unit.shield == null && unit.submarine == null && unit.amphibiousJet == null &&
            unit.transport == null && unit.energy == null && unit.customActions == null && unit.groundHeight == null &&
            unit.reclaimPrice == null
}
