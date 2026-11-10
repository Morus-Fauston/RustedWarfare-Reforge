package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*

/** Explicit qualified mobile work capability; names alone confer no new work permissions. */
internal object MechEngineer {
    fun supports(unit: UnitDefinition): Boolean {
        val builder = unit.builder ?: return false
        if (!MechEngineerRules.supportsConstructionTypes(builder.buildingTypeIds)) return false
        val combat = unit.combat ?: return false
        val weapons = combat.namedWeapons ?: return false
        if (weapons.ordered.map { it.name } != listOf("body", "nanoTurret")) return false
        val bodyImage = weapons.ordered[0].imagePath ?: return false
        val nanoImage = weapons.ordered[1].imagePath ?: return false
        val shot = ProjectileProfile(100.0, 240.0, 7.3, acceleration = LinearProjectileAcceleration(5.0, .1),
            splash = OrdinarySplashProfile(40f, 35f), retargetOnDeath = true)
        val expectedWeapons = listOf(
            NamedWeaponDefinition("body", "missile", shot, TurretMount(), 90.0, 7.0, 3.0, .2,
                bodyImage, 1.0, recoil = WeaponRecoil(0.0), fireSound = FireSoundProfile.ENGINEER_MISSILE,
                canAttackLandUnits = false, canAttackFlyingUnits = true, warmup = 2.0,
                barrelX = -14.0, idleDirectionReversing = 0.0),
            NamedWeaponDefinition("nanoTurret", "missile", shot, TurretMount(0.0, -5.0), 90.0, 4.0, 3.0, .2,
                nanoImage, 1.0, recoil = WeaponRecoil(0.0), canShoot = false,
                canAttackLandUnits = false, canAttackFlyingUnits = true, attachedTo = "body", idleDirectionReversing = 0.0))
        val expectedCombat = CombatProfile(2000.0, 220.0, 90.0, 7.0, 3.0, .2, shot, bodyImage,
            combat.deadImagePath, 1.0, recoil = WeaponRecoil(0.0), barrelX = -14.0,
            fireSound = FireSoundProfile.ENGINEER_MISSILE, namedWeapons = weapons, rotateWithBody = true)
        val mech = unit.mech ?: return false
        if (mech.kind != MechKind.FOUR_LEGGED || !mech.isValid() ||
            mech.body != MechBodyDefinition(3.0, .2, 0.0, 0.0, 0.0, 0.0, 0.0, 0)) return false
        for ((index, leg) in mech.legs.withIndex()) {
            val x = if (index % 2 == 0) -1 else 1
            val y = if (index < 2) 1 else -1
            if (leg != MechLegDefinition(x * 15.0, y * 15.0, x * 4.0, y * 4.0, 2.9, .5, 9.0, .8,
                    17.0, 50.0, .8, leg.legImage, leg.footImage, 25.0, hardLimit = 65.0,
                    favourOppositeSideNeighbours = true, maximumMovingLegs = 2,
                    endDirectionOffset = listOf(135.0, 225.0, 45.0, -45.0)[index])) return false
        }
        return unit.maintenance == MaintenanceProfile.MECH_ENGINEER && unit.id == "mechEngineer" &&
            unit.radius == 19.0 && unit.displayRadius == 19.0 && unit.maximumHealth == 2000.0 &&
            unit.build == BuildProfile(5000L, .0008f) && unit.selfRegenRate == .08f && unit.techLevel == 2 &&
            unit.income == IncomeProfile(40f, 1f) && unit.fogOfWarSightRange == 16 && unit.transportSlots == 3 &&
            unit.movement == MovementProfile(MovementType.OVER_CLIFF, .85, .02, .2, 1.5, 1.0, true, true, .2, 180) &&
            builder.range == 140f && builder.preparation == 0f && builder.decay == 4f &&
            builder.repairRate == .3f && builder.buildSpeedMultiplier == 2f && builder.mainNanoTurret == "nanoTurret" &&
            combat == expectedCombat && weapons.ordered == expectedWeapons &&
            unit.building == null && unit.production == null && unit.upgrades == null &&
            unit.shield == null && unit.energy == null && unit.customActions == null && unit.transport == null &&
            unit.durability == null && unit.submarine == null && unit.amphibiousJet == null && unit.rotor == null &&
            unit.pointDefense == null && unit.deathExplosion == null && unit.groundHeight == null &&
            unit.fixedUnderwaterTarget == null && !unit.authoredWaterCombat && unit.imageScale == 1.0 && unit.totalFrames == 1
    }
}
