package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.CustomBallisticProfile
import com.rustedwarfare.reforge.core.content.LinearProjectileAcceleration
import com.rustedwarfare.reforge.core.content.NuclearSiloKind
import com.rustedwarfare.reforge.core.content.ProjectileInterceptionProfile
import com.rustedwarfare.reforge.core.content.ProjectileProfile

/** Keeps the last target pose without sharing a mutable projectile across candidate steps. */
internal data class InterceptionCourse(
    val targetId: Long,
    val launchTarget: Position,
    val position: Position,
    val height: Float,
)

internal object ProjectileInterception {
    private val groundRanges = ProjectileInterceptionProfile(1000f, 2200f, 50f)
    private val groundRound = ProjectileProfile(600.0, 99999.0, .2, nonDeflectable = true,
        acceleration = LinearProjectileAcceleration(6.5, .05), ballistic = CustomBallisticProfile(80f, 110f))

    private fun navalWeapon(unit: MutableUnit) =
        unit.definition.combat?.namedWeapons?.ordered?.singleOrNull { it.interception != null }

    fun target(unit: MutableUnit, projectiles: List<MutableProjectile>, units: Map<String, MutableUnit>,
               players: List<Player>): MutableProjectile? {
        val module = unit.definition.attachmentModule?.interception
        val stock = if (module != null) unit.attachmentInterceptor.ammo else unit.ordnance.ammo
        if (stock == 0 || module != null && unit.attachmentInterceptor.offline) return null
        val profile = when {
            module != null -> module
            unit.definition.nuclearSilo?.kind == NuclearSiloKind.INTERCEPTOR -> groundRanges
            else -> navalWeapon(unit)?.interception ?: return null
        }
        val reservations = projectiles.mapNotNullTo(hashSetOf()) { it.interception?.targetId }
        val team = players.single { it.playerId == unit.ownerPlayerId }.teamId
        val x = unit.position.x.toFloat()
        val y = unit.position.y.toFloat()
        return projectiles.lastOrNull { shot ->
            val ground = shot.nuclearTarget ?: return@lastOrNull false
            if (!isInFlight(shot) || shot.height.toFloat() <= profile.minimumHeight || shot.objectId in reservations) return@lastOrNull false
            val source = units[shot.sourceUnitId]
            if (source != null && (source.ownerPlayerId == unit.ownerPlayerId ||
                    team != null && players.single { it.playerId == source.ownerPlayerId }.teamId == team))
                return@lastOrNull false
            GroundMath.square(x, y, shot.position.x.toFloat(), shot.position.y.toFloat()) < profile.trackingRange * profile.trackingRange &&
                GroundMath.square(x, y, ground.x.toFloat(), ground.y.toFloat()) < profile.landingRange * profile.landingRange
        }
    }

    fun isInFlight(shot: MutableProjectile): Boolean =
        shot.nukeTagged && !shot.hitApplied && !shot.intercepted && shot.remainingLife > 0.0

    fun launch(unit: MutableUnit, target: MutableProjectile, id: Long): MutableProjectile {
        val module = unit.definition.attachmentModule?.interception != null
        val naval = navalWeapon(unit)
        val round = naval?.projectile ?: groundRound
        val muzzle = if (naval == null) {
            val offset = ScreenPlane.displacement(unit.heading, if (module) 1.0 else 11.0)
            Position(unit.position.x + offset.x, unit.position.y + offset.y)
        } else NamedWeaponRuntime.muzzle(unit, naval, checkNotNull(unit.namedWeapons).getValue(naval.name))
        val heading = ScreenPlane.desired(muzzle, target.position)
        val state = unit.ordnance
        if (module) unit.attachmentInterceptor = unit.attachmentInterceptor.copy(ammo = unit.attachmentInterceptor.ammo - 1)
        else unit.ordnance = if (naval == null)
            state.copy(ammo = state.ammo - 1, interceptorFireAge = 0f, doorBlendBase = state.doors)
        else state.copy(ammo = state.ammo - 1)
        return MutableProjectile(id, unit.unitId, null, muzzle, heading,
            round.life, Position(0.0, 0.0), 0f, round.speed, round.directDamage, height = unit.height,
            nonDeflectable = round.nonDeflectable, sourceWeaponName = naval?.name ?: "silo", acceleration = round.acceleration,
            ballistic = round.ballistic, appearance = ProjectileAppearance.INTERCEPTOR,
            alwaysVisibleInFog = true, interception = InterceptionCourse(
                target.objectId, target.position, target.position, target.height.toFloat()),
            sourceOwnerId = unit.ownerPlayerId)
    }
}
