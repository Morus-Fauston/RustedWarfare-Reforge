package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*

/** Admission of the one qualified AA representative, not arbitrary custom energy weapons. */
internal object AaBeamGunship {
    fun advanceCorpse(unit: MutableUnit, map: MapDefinition, delta: Double) {
        if (unit.definition.energy == null || unit.waterSettled) return
        val elapsed = delta.toFloat()
        if (unit.height > 0.0) {
            val moving = unit.speed != 0.0 || unit.flightVelocity.x != 0.0 || unit.flightVelocity.y != 0.0
            unit.sinkSpeed += (if (moving) .017f else .06f) * elapsed
            unit.height = (unit.height.toFloat() - unit.sinkSpeed * elapsed).toDouble()
            if (moving) unit.position = Position(unit.position.x + unit.flightVelocity.x * delta,
                unit.position.y + unit.flightVelocity.y * delta)
        } else if (!unit.flightLanded) {
            unit.flightLanded = true
            unit.flightLandedInWater = map.isLiquid(MapBounds.cell(unit.position.x, map.tileWidth, map.widthTiles),
                MapBounds.cell(unit.position.y, map.tileHeight, map.heightTiles))
            unit.sinkSpeed = 0f
        } else if (!unit.flightLandedInWater) {
            unit.height = 0.0
            unit.waterSettled = true
        } else if (unit.height > -10.0) {
            unit.sinkSpeed += .0008f * elapsed
            unit.height = (unit.height.toFloat() - unit.sinkSpeed * elapsed).toDouble()
        } else unit.waterSettled = true
        require(unit.sinkSpeed.isFinite() && unit.height.isFinite()) { "AA corpse must remain finite" }
    }

    fun supports(unit: UnitDefinition): Boolean {
        val weapon = unit.combat ?: return false
        val turret = weapon.namedWeapons?.ordered?.singleOrNull() ?: return false
        val shot = weapon.projectile
        val beam = shot.beam ?: return false
        val expectedShot = ProjectileProfile(22.0, 15.0, 5.0, instant = true,
            damageModifiers = ProjectileDamageModifiers(shieldDamage = .75), beam = beam)
        val expectedTurret = NamedWeaponDefinition("1", "beam", expectedShot, TurretMount(0.0, 2.0),
            5.0, 16.0, 4.2, .3, weapon.turretImagePath, 1.0, recoil = WeaponRecoil(0.0),
            canAttackFlyingUnits = true, imageOffsetY = 5.0, energyUsage = .5f)
        val expectedCombat = CombatProfile(2500.0, 280.0, 5.0, 16.0, 4.2, .3, expectedShot,
            weapon.turretImagePath, weapon.deadImagePath, 1.0, mount = TurretMount(0.0, 2.0),
            recoil = WeaponRecoil(0.0), idleSweep = IdleSweepProfile(20.0, 100f, .4f, 5),
            namedWeapons = WeaponCatalog(listOf(expectedTurret)))
        return unit.id == "aaBeamGunship" && unit.className == "CustomUnitMetadata" &&
            unit.radius == 22.0 && unit.displayRadius == 22.0 && unit.maximumHealth == 2500.0 &&
            unit.movement == MovementProfile(MovementType.AIR, 1.0, .015, .015, 1.2, .04, true, true, .1, 50,
                FlightProfile(35.0, 1.0, -1.0, 0.0)) && unit.rotor == null &&
            unit.energy == EnergyProfile(6f, .01f, .05f, true) && unit.build == BuildProfile(6000L, 1f / 2100f) &&
            unit.building == null && unit.production == null && unit.builder == null && unit.shield == null &&
            unit.transport == null && unit.submarine == null && unit.amphibiousJet == null && unit.pointDefense == null &&
            unit.upgrades == null && unit.income == null && unit.construction == null && unit.deathExplosion == null &&
            unit.mech == null && unit.fixedUnderwaterTarget == null && !unit.authoredWaterCombat &&
            unit.durability == null && unit.directHitTargetKind == DirectHitTargetKind.ORDINARY &&
            unit.transportSlots == 1 && unit.fogOfWarSightRange == 15 && unit.totalFrames == 1 &&
            weapon == expectedCombat && turret == expectedTurret && shot == expectedShot
    }
}
