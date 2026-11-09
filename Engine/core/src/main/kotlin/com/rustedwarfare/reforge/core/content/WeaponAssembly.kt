package com.rustedwarfare.reforge.core.content

import java.util.Collections

/** Effective immutable configuration; every live unit keeps its own clocks and targets. */
class AssembledWeapon internal constructor(val definition: NamedWeaponDefinition, val profile: CombatProfile)

/** Declaration order and effective defaults compiled from one actual content definition. */
class WeaponAssembly private constructor(slots: List<AssembledWeapon>, val usesNamedState: Boolean) {
    val slots: List<AssembledWeapon> = Collections.unmodifiableList(ArrayList(slots))
    val byName: Map<String, AssembledWeapon> = Collections.unmodifiableMap(
        slots.associateByTo(linkedMapOf()) { it.definition.name })
    private val parents = this.slots.associate { slot ->
        slot.definition.name to slot.definition.attachedTo?.let(byName::getValue)
    }
    private val delayFollowers = this.slots.associate { leader ->
        leader.definition.name to Collections.unmodifiableList(this.slots.filter {
            it.definition.linkDelayWithTurret == leader.definition.name
        })
    }

    fun parent(name: String): AssembledWeapon? = parents.getValue(name)
    fun followers(name: String): List<AssembledWeapon> = delayFollowers.getValue(name)

    companion object {
        fun compile(base: CombatProfile): WeaponAssembly = WeaponAssembly(
            base.namedWeapons?.ordered.orEmpty().mapIndexed { index, weapon ->
                AssembledWeapon(weapon, base.copy(
                    projectile = weapon.projectile, shootDelay = weapon.shootDelay, turretSize = weapon.turretSize,
                    turretTurnSpeed = weapon.turnSpeed, turretTurnAcceleration = weapon.turnAcceleration,
                    turretTurnDeceleration = weapon.turnDeceleration ?: base.turretTurnDeceleration,
                    mount = weapon.mount, turretImagePath = weapon.imagePath, turretImageScale = weapon.imageScale,
                    recoil = weapon.recoil, fireSound = weapon.fireSound, namedWeapons = null,
                    barrelX = weapon.barrelX ?: if (index == 0) base.barrelX else 0.0,
                    oddShotOffset = weapon.oddShotOffset ?: if (index == 0) base.oddShotOffset else 0.0))
            }, requiresNamedState(base))

        /** Preserve the existing scalar route for ordinary single-weapon content. */
        internal fun requiresNamedState(combat: CombatProfile): Boolean = combat.namedWeapons?.let { catalog ->
            catalog.ordered.size > 1 || catalog.ordered.any { weapon ->
                weapon.minimumRange > 0.0 || weapon.maximumRange?.let { it < combat.attackRange } == true ||
                    !weapon.canAttackLandUnits || weapon.warmup != 0.0 || weapon.warmupNoReset ||
                    weapon.warmupShootDelayTransfer != 0.0 || !weapon.canShoot || weapon.aimMaxAngle != 5.0 ||
                    weapon.attachedTo != null || weapon.slave || weapon.linkDelayWithTurret != null ||
                    weapon.limitingAngle?.let { it != -1.0 } == true || weapon.idleDirection != 0.0 ||
                    weapon.barrelX != null || weapon.oddShotOffset != null
            }
        } == true
    }
}
