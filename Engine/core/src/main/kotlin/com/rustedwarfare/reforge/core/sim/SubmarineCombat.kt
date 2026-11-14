package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*

/** One weapon clock; launch parameters are captured from actual depth at the firing boundary. */
internal object SubmarineCombat {
    fun supports(surface: CombatProfile): Boolean {
        if (surface.deadImagePath == null) return false
        val projectile = ProjectileProfile(42.0, 190.0, 2.0, ballistic = CustomBallisticProfile(40f, 60f, 2f))
        val weapon = NamedWeaponDefinition("1", "surface", projectile, TurretMount(), 170.0, 10.0, 2.5, .08,
            null, 1.0, recoil = WeaponRecoil(0.0), fireSound = FireSoundProfile.SUBMARINE_MISSILE,
            canAttackFlyingUnits = true, canAttackUnderwaterUnits = true, invisible = true)
        return surface == CombatProfile(260.0, 250.0, 170.0, 10.0, 2.5, .08, projectile, null,
            surface.deadImagePath, 1.0, recoil = WeaponRecoil(0.0), fireSound = FireSoundProfile.SUBMARINE_MISSILE,
            namedWeapons = WeaponCatalog(listOf(weapon)))
    }

    fun range(unit: MutableUnit): Double =
        if (unit.definition.submarine != null && UnitLocomotion.isSubmerged(unit)) 180.0 else checkNotNull(unit.definition.combat).attackRange

    fun profile(unit: MutableUnit): CombatProfile? {
        val surface = unit.definition.combat ?: return null
        return if (unit.definition.submarine != null && UnitLocomotion.isSubmerged(unit)) surface.copy(attackRange = 180.0,
            projectile = ProjectileProfile(42.0, 250.0, 1.3), fireSound = null) else surface
    }
}
