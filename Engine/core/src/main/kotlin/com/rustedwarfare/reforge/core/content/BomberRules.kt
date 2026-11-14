package com.rustedwarfare.reforge.core.content

import java.nio.file.Path

internal object BomberRules {
    const val ID = "bomber"
    val movement = MovementProfile(MovementType.AIR, .85, .01, .01, 1.2, .04, false, false,
        slidingDirection = 25, flight = FlightProfile(35.0, 1.5, -1.0, 0.0))
    val build = BuildProfile(4000L, .00025f)
    val energy = EnergyProfile(3f, .002f, .002f, true)
    private val bomb = ProjectileProfile(0.0, 250.0, .3, deflectionHealth = 3f,
        ground = GroundProjectileProfile(370f, 65f, 0f, 0f, 0f), acceleration = LinearProjectileAcceleration(1.7, .1))
    private val depthCharge = bomb.copy(directDamage = 70.0, ground = null)

    private fun weapon(name: String, projectile: ProjectileProfile, land: Boolean) = NamedWeaponDefinition(
        name, name, projectile, TurretMount(), 30.0, 1.1, 20.0, 0.0, null, 1.0,
        recoil = WeaponRecoil(0.0), canAttackLandUnits = land, canAttackUnderwaterUnits = !land,
        invisible = true, energyUsage = 1f)

    private val weapons = WeaponCatalog(listOf(weapon("1", bomb, true), weapon("2", depthCharge, false)))

    fun combat(dead: Path?) = CombatProfile(3000.0, 45.0, 30.0, 1.1, 20.0, 0.0, bomb, null, dead, 1.0,
        recoil = WeaponRecoil(0.0), namedWeapons = weapons)

    fun supports(unit: UnitDefinition): Boolean {
        val profile = unit.combat ?: return false
        return unit.attackMovement == AttackMovementProfile.BOMBER && unit.className == "CustomUnitMetadata" &&
            unit.radius == 16.0 && unit.displayRadius == 19.0 &&
            unit.totalFrames == 1 && unit.maximumHealth == 3000.0 && unit.techLevel == 2 && unit.selfRegenRate == .1f &&
            unit.movement == movement && unit.build == build && unit.energy == energy &&
            unit.rotor == null && unit.mech == null && unit.customActions == null && unit.transport == null &&
            unit.builder == null && unit.building == null && unit.shadowImagePath != null && profile.deadImagePath != null &&
            profile.namedWeapons?.ordered == weapons.ordered && profile.copy(namedWeapons = weapons) == combat(profile.deadImagePath)
    }
}
