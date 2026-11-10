package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*

/** Admission of the normal four-legged mobile bunker and its inherited fixed form. */
internal object Bunker {
    fun advanceHeight(unit: MutableUnit, delta: Double) {
        val target = unit.definition.groundHeight?.target ?: return
        val elapsed = delta.toFloat()
        var height = unit.height.toFloat()
        if (height < target) height = minOf(target, height + .2f * elapsed)
        val rate = minOf(kotlin.math.abs(height - 2f) * .02f + .2f,
            kotlin.math.abs(height - target) * .015f + .1f) * elapsed
        require(rate.isFinite()) { "Ground height step must remain finite" }
        height = if (height < target) minOf(target, height + rate) else maxOf(target, height - rate)
        require(height.isFinite()) { "Ground body height must remain finite" }
        unit.height = height.toDouble()
    }

    fun supports(unit: UnitDefinition): Boolean {
        val fixed = unit.id == "mechBunkerDeployed"
        if (!fixed && unit.id != "mechBunker") return false
        val actions = unit.customActions ?: return false
        val deploy = CustomActionDefinition("deploy", "mechBunkerDeployed_0", "mechBunkerDeployed", .006f, null,
            visible = !fixed, cannotMove = true, rotateTo = 0.0, rotateToOrBackwards = true)
        val expected = listOf(deploy) + if (fixed) listOf(
            CustomActionDefinition("undeploy", "mechBunker_1", "mechBunker", .010f, null)) else emptyList()
        val weapon = unit.combat ?: return false
        val shot = ProjectileProfile(0.0, 150.0, 4.0, ground = GroundProjectileProfile(110f, 45f, 0f, 0f, 0f))
        val delay = if (fixed) 50.0 else 70.0
        val catalog = weapon.namedWeapons ?: return false
        val turretImage = catalog.byName["2"]?.imagePath ?: return false
        val expectedWeapons = listOf(
            NamedWeaponDefinition("1", "1", shot, TurretMount(), delay, 20.0, 2.9, .07, null, 1.0,
                recoil = WeaponRecoil(0.0), canShoot = false),
            NamedWeaponDefinition("2", "1", shot, TurretMount(), delay, 25.0, 3.9, .2, turretImage, 1.0,
                recoil = WeaponRecoil(-3.0, 6.0, 7.0), fireSound = FireSoundProfile.NAVAL_MAIN,
                idleSweep = IdleSweepProfile(20.0, 210f, .2f, 50, onlyWhenStationary = true), resetTurret = !fixed))
        val expectedCombat = CombatProfile(800.0, if (fixed) 240.0 else 200.0, delay, 20.0, 2.9, .07, shot,
            null, null, 1.0, recoil = WeaponRecoil(0.0), namedWeapons = catalog, rotateWithBody = true)
        val mech = unit.mech ?: return false
        if (mech.kind != MechKind.FOUR_LEGGED || !mech.isValid() || mech.legs.size != 4 ||
            mech.body != MechBodyDefinition(1.0, .5, 0.0, 180.0, 0.0, 0.0, 0.0, 0)) return false
        for ((index, leg) in mech.legs.withIndex()) {
            val x = if (index % 2 == 0) -1 else 1
            val y = if (index < 2) 1 else -1
            if (leg != MechLegDefinition(x * (if (fixed) 3.0 else 22.0), y * (if (fixed) 3.0 else 15.0),
                    x * 5.0, y * 10.0, if (fixed) 1.0 else 1.9, 6.0, 7.0, if (fixed) .4 else .3,
                    5.0, if (fixed) 15.0 else 19.0, .7, leg.legImage, leg.footImage, 25.0,
                    favourOppositeSideNeighbours = true)) return false
        }
        return actions.actions == expected && actions.automaticCooldown == 60f &&
            unit.className == "CustomUnitMetadata" && unit.maximumHealth == 800.0 &&
            unit.radius == (if (fixed) 23.0 else 29.0) && unit.displayRadius == unit.radius &&
            unit.build == BuildProfile(4500, .0006f) && unit.fogOfWarSightRange == 20 &&
            unit.movement == MovementProfile(if (fixed) MovementType.NONE else MovementType.OVER_CLIFF,
                if (fixed) 0.0 else .4, .07, .17, 1.0, .5, !fixed, !fixed, 1.0, 181) &&
            unit.building == (if (fixed) BuildingProfile(BuildingFootprint(-1,-1,1,1)) else null) &&
            unit.shield == (if (fixed) ShieldProfile(500f, .15f, true) else null) &&
            unit.groundHeight == GroundHeightProfile(if (fixed) 0f else 3f) &&
            unit.mech?.let { it.kind == MechKind.FOUR_LEGGED && it.isValid() } == true &&
            unit.energy == null && unit.transport == null && unit.submarine == null && unit.amphibiousJet == null &&
            unit.builder == null && unit.production == null && unit.construction == null && unit.upgrades == null &&
            unit.income == null && unit.pointDefense == null && unit.deathExplosion == null &&
            unit.rotor == null && unit.durability == null && unit.fixedUnderwaterTarget == null &&
            !unit.authoredWaterCombat && unit.directHitTargetKind == DirectHitTargetKind.ORDINARY && unit.transportSlots == 1 &&
            unit.totalFrames == 1 && unit.imageScale == 1.0 && unit.afterburnTrailImagePath == null &&
            weapon == expectedCombat && catalog.ordered == expectedWeapons
    }
}
