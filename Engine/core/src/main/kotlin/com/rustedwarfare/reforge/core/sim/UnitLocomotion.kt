package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementProfile
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.TransportProfile
import com.rustedwarfare.reforge.core.content.UnitDefinition

/** The current physical locomotion profile, shared by planning, steering and displacement. */
internal object UnitLocomotion {
    fun routeTypes(definition: UnitDefinition): Set<MovementType> {
        val base = definition.movement.type
        val diving = definition.locomotionStates?.takeIf { it.divingChangesRoute }
        return if (diving == null) setOf(base) else setOf(base, MovementType.WATER)
    }

    const val SUBMERGED_DEPTH = -1.0

    fun isSubmerged(unit: MutableUnit): Boolean =
        unit.definition.locomotionStates != null && unit.height < SUBMERGED_DEPTH

    fun effectiveMaximumSpeed(unit: MutableUnit, movement: MovementProfile): Double =
        movement.moveSpeed * unit.terrainSpeedMultiplier

    fun movement(unit: MutableUnit): MovementProfile {
        val base = unit.definition.movement
        unit.attachmentMoveSpeed?.let { return base.copy(moveSpeed = it) }
        unit.transportState?.let { state ->
            if (unit.definition.transport == TransportProfile.NATIVE_HOVERCRAFT)
                return base.copy(moveSpeed = if (state.liquid) 1.2 else .9,
                    maxTurnSpeed = if (state.liquid) 1.8 else 1.4)
        }
        val mobility = unit.definition.locomotionStates ?: return base
        val diving = unit.amphibiousState?.flyingIntent == false
        val underwater = isSubmerged(unit)
        val waterRoute = mobility.divingChangesRoute && (diving || unit.height < 0.0)
        return base.copy(type = if (waterRoute) MovementType.WATER else base.type,
            moveSpeed = if (underwater) mobility.submergedSpeed else base.moveSpeed,
            maxTurnSpeed = if (underwater) mobility.submergedTurnSpeed ?: base.maxTurnSpeed else base.maxTurnSpeed)
    }
}
