package com.rustedwarfare.reforge.core.content

internal object MechFlameRules {
    const val ID = "mechFlame"
    val build = BuildProfile(12000L, .0005f)
    val movement = MovementProfile(MovementType.OVER_CLIFF, .6, .07, .17, 5.0, .5, false, false, 1.0)
    val body = MechBodyDefinition(2.9, .17, 0.0, 180.0, 0.0, 0.0, 0.0, 0)
    private val projectile = ProjectileProfile(0.0, 70.0, 7.0, nonDeflectable = true,
        ground = GroundProjectileProfile(12f, 60f, 25f, 0f, 0f, speedSpread = 1f, resolveAtSnapshot = true),
        acceleration = LinearProjectileAcceleration(2.5, .3), flame = true)

    private val control = NamedWeaponDefinition("1", "fireProjectile1", projectile, TurretMount(), 5.0,
        20.0, 2.9, .17, null, 1.0, recoil = WeaponRecoil(0.0), canShoot = false,
        idleDirectionReversing = 180.0, turnDeceleration = .35)
    private val right = NamedWeaponDefinition("2", "fireProjectile1", projectile, TurretMount(15.0, 30.0), 5.0,
        0.0, 8.0, 1.0, null, 1.0, recoil = WeaponRecoil(0.0), attachedTo = "1", slave = true,
        limitingAngle = 45.0, invisible = true)
    private val weapons = WeaponCatalog(listOf(control, right, right.copy(name = "3", mount = TurretMount(-15.0, 30.0), warmup = 1.0)))
    val combat = CombatProfile(4500.0, 170.0, 5.0, 20.0, 2.9, .17, projectile, null, null, 1.0,
        recoil = WeaponRecoil(0.0), namedWeapons = weapons, turretTurnDeceleration = .35)

    fun supports(unit: UnitDefinition): Boolean = unit.className == "CustomUnitMetadata" &&
        unit.radius == 25.0 && unit.displayRadius == 25.0 && unit.totalFrames == 1 && unit.maximumHealth == 4500.0 &&
        unit.techLevel == 2 && unit.selfRegenRate == .06f && unit.transportSlots == 2 && unit.movement == movement &&
        unit.build == build && unit.combat == combat && unit.shadowImagePath != null &&
        unit.mech?.let { it.isValid() && it.kind == MechKind.TWO_LEGGED && it.body == body } == true
}
