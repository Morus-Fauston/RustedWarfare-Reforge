package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.CustomBallisticProfile
import com.rustedwarfare.reforge.core.content.LinearProjectileAcceleration
import com.rustedwarfare.reforge.core.content.NuclearSiloKind

/** Keeps the last target pose without sharing a mutable projectile across candidate steps. */
internal data class InterceptionCourse(
    val targetId: Long,
    val launchTarget: Position,
    val position: Position,
    val height: Float,
)

internal object ProjectileInterception {
    fun target(unit: MutableUnit, projectiles: List<MutableProjectile>, units: Map<String, MutableUnit>,
               players: List<Player>): MutableProjectile? {
        if (unit.definition.nuclearSilo?.kind != NuclearSiloKind.INTERCEPTOR || unit.ordnance.ammo == 0) return null
        val reservations = projectiles.mapNotNullTo(hashSetOf()) { it.interception?.targetId }
        val team = players.single { it.playerId == unit.ownerPlayerId }.teamId
        val x = unit.position.x.toFloat()
        val y = unit.position.y.toFloat()
        return projectiles.lastOrNull { shot ->
            val ground = shot.nuclearTarget ?: return@lastOrNull false
            if (!isInFlight(shot) || shot.height.toFloat() <= 50f || shot.objectId in reservations) return@lastOrNull false
            val source = units[shot.sourceUnitId]
            if (source != null && (source.ownerPlayerId == unit.ownerPlayerId ||
                    team != null && players.single { it.playerId == source.ownerPlayerId }.teamId == team))
                return@lastOrNull false
            GroundMath.square(x, y, shot.position.x.toFloat(), shot.position.y.toFloat()) < 2200f * 2200f &&
                GroundMath.square(x, y, ground.x.toFloat(), ground.y.toFloat()) < 1000f * 1000f
        }
    }

    fun isInFlight(shot: MutableProjectile): Boolean =
        shot.nukeTagged && !shot.hitApplied && !shot.intercepted && shot.remainingLife > 0.0

    fun launch(unit: MutableUnit, target: MutableProjectile, id: Long): MutableProjectile {
        val offset = ScreenPlane.displacement(unit.heading, 11.0)
        val muzzle = Position(unit.position.x + offset.x, unit.position.y + offset.y)
        val heading = ScreenPlane.desired(muzzle, target.position)
        val state = unit.ordnance
        unit.ordnance = state.copy(ammo = state.ammo - 1, interceptorFireAge = 0f, doorBlendBase = state.doors)
        return MutableProjectile(id, unit.unitId, null, muzzle, heading,
            99999.0, Position(0.0, 0.0), 0f, .2, 600.0, height = unit.height, nonDeflectable = true,
            sourceWeaponName = "silo", acceleration = LinearProjectileAcceleration(6.5, .05),
            ballistic = CustomBallisticProfile(80f, 110f), appearance = ProjectileAppearance.INTERCEPTOR,
            alwaysVisibleInFog = true, interception = InterceptionCourse(
                target.objectId, target.position, target.position, target.height.toFloat()))
    }
}
