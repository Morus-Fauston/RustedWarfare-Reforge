package com.rustedwarfare.reforge.core.content

import java.nio.file.Path

/** The scout's two terrain forms share one lifetime and the same commands. */
internal object ScoutBotRules {
    const val LAND = "robotCrab"
    const val WATER = "robotCrabWater"
    const val OFFLINE_UNIT_CAP = 1000
    val ids = setOf(LAND, WATER)
    val energy = EnergyProfile(1f, -.0005f, -.0005f, false, dieOnZero = true)
    val build = BuildProfile(700L, .002f)
    val builder = BuilderProfile(emptyList(), range = 110f, repairRate = .4f, buildSpeedMultiplier = .4f, mainNanoTurret = "1")
    val height = GroundHeightProfile(.1f)

    fun movement(water: Boolean) = MovementProfile(MovementType.OVER_CLIFF_WATER, if (water) .6 else 1.0,
        1.0, 1.0, 4.0, if (water) .2 else .5, false, false, 0.0, if (water) 45 else 35)

    fun actions(water: Boolean): CustomActionsProfile {
        val target = if (water) LAND else WATER
        val condition = if (water) AutomaticActionCondition.NOT_OVER_WATER else AutomaticActionCondition.OVER_WATER
        val name = if (water) "notOverWater" else "overWater"
        return CustomActionsProfile(listOf(CustomActionDefinition(name, "${target}_0", target, 0f, null,
            automatic = condition, visible = false)))
    }

    fun combat(water: Boolean, turret: Path, dead: Path?): CombatProfile {
        val shot = ProjectileProfile(17.0, 70.0, 6.0)
        val recoil = WeaponRecoil(-2.0, 4.0, 6.0)
        val guns = listOf(
            NamedWeaponDefinition("1", "1", shot, TurretMount(), 50.0, 8.0, 4.0, 0.0, null, 1.0,
                recoil = WeaponRecoil(0.0), canShoot = false, canAttackLandUnits = false, invisible = true, warmup = 30.0),
            NamedWeaponDefinition("2", "1", shot, TurretMount(0.0, if (water) -.5 else -2.0), 50.0, 7.0, 4.0, 0.0,
                turret, 1.0, recoil = recoil, fireSound = FireSoundProfile.SCOUT_PLASMA, canAttackFlyingUnits = true))
        return CombatProfile(650.0, 110.0, 50.0, 7.0, 4.0, 0.0, shot, turret, dead, 1.0,
            recoil = recoil, namedWeapons = WeaponCatalog(guns), rotateWithBody = true)
    }

    fun mech(water: Boolean, leg: Path, foot: Path): MechDefinition {
        val points = if (water) listOf(-4 to -2, 4 to -2, -4 to 5, 4 to 5, -4 to -10, 4 to -10)
            else listOf(-13 to 0, 13 to 0, -13 to 4, 13 to 4, -13 to -6, 13 to -6)
        val mounts = listOf(-2 to 0, 2 to 0, -5 to 4, 5 to 4, -5 to -6, 5 to -6)
        return MechDefinition(MechBodyDefinition(0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0),
            points.zip(mounts).map { (position, mount) ->
                MechLegDefinition(position.first.toDouble(), position.second.toDouble(), mount.first.toDouble(), mount.second.toDouble(),
                    if (water) .4 else 3.7, 0.0, 7.0, if (water) .4 else 1.0, 5.0, 10.0, .4,
                    leg, foot, 0.0, hardLimit = if (water) 18.0 else 28.0, maximumMovingLegs = 6, lockMovement = water)
            }, MechKind.SIX_LEGGED)
    }

    fun supports(unit: UnitDefinition): Boolean {
        if (unit.id !in ids || unit.className != "CustomUnitMetadata") return false
        val water = unit.id == WATER
        val weapon = unit.combat ?: return false
        val turret = weapon.turretImagePath ?: return false
        val model = unit.mech ?: return false
        val leg = model.legs.firstOrNull() ?: return false
        val expected = mech(water, leg.legImage, leg.footImage)
        val work = unit.builder ?: return false
        return unit.radius == 11.0 && unit.displayRadius == 13.0 && unit.maximumHealth == 650.0 &&
            unit.totalFrames == 1 && unit.imageScale == 1.0 && unit.fogOfWarSightRange == 22 && unit.build == build &&
            unit.reclaimPrice == 0L && unit.energy == energy && unit.movement == movement(water) &&
            unit.groundHeight == height && unit.maintenance == MaintenanceProfile.SCOUT_BOT &&
            work.buildingTypeIds.isEmpty() && work.range == builder.range && work.preparation == builder.preparation &&
            work.decay == builder.decay && work.repairRate == builder.repairRate &&
            work.buildSpeedMultiplier == builder.buildSpeedMultiplier && work.mainNanoTurret == builder.mainNanoTurret &&
            unit.customActions?.let { it.actions == actions(water).actions && it.automaticCooldown == 60f } == true &&
            weapon == combat(water, turret, weapon.deadImagePath) && weapon.deadImagePath != null &&
            model.kind == expected.kind && model.body == expected.body && model.legs == expected.legs &&
            unit.building == null && unit.production == null && unit.construction == null && unit.upgrades == null &&
            unit.pointDefense == null && unit.shield == null && unit.income == null && unit.deathExplosion == null &&
            unit.transport == null && unit.rotor == null && unit.submarine == null && unit.amphibiousJet == null &&
            unit.durability == null && unit.fixedUnderwaterTarget == null && !unit.authoredWaterCombat &&
            unit.techLevel == 1 && unit.transportSlots == 1 && unit.selfRegenRate == 0f &&
            unit.directHitTargetKind == DirectHitTargetKind.ORDINARY && unit.nuclearSilo == null
    }
}
