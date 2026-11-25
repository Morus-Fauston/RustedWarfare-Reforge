package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementProfile
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.TransportProfile
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import kotlin.math.min

/** The current physical locomotion profile, shared by planning, steering and displacement. */
internal object UnitLocomotion {
    const val IMPLICIT_LAND_TERRAIN_TILE_FRACTION = 0.45

    fun routeTypes(definition: UnitDefinition): Set<MovementType> {
        val base = definition.movement.type
        val diving = definition.locomotionStates?.takeIf { it.divingChangesRoute }
        return if (diving == null) setOf(base) else setOf(base, MovementType.WATER)
    }

    const val SUBMERGED_DEPTH = -1.0

    fun isSubmerged(unit: MutableUnit, height: Double = unit.height): Boolean = isSubmerged(unit.definition, height)

    fun isSubmerged(definition: UnitDefinition, height: Double): Boolean =
        definition.locomotionStates != null && height < SUBMERGED_DEPTH

    /**
     * Unified single-unit and group-move terrain passability profile (ENG-P016-009C):
     * - Never branches on `landingMember`, `groupMoveReservation`, or `radius != definition.radius`.
     * - Explicit `movementCollision` (and naval `WATER` bodies) use their declared shape across the full chain.
     * - Implicit INI default circular land/hover units retain full `radius` for unit-to-unit contact while
     *   capping static terrain passability radius at `min(radius, 0.45 * minTile)`.
     */
    fun terrainHull(definition: UnitDefinition, moveType: MovementType = definition.movement.type,
                    tileWidth: Int = 20, tileHeight: Int = 20): MovementCollisionShape? {
        if (moveType == MovementType.AIR || moveType == MovementType.NONE) return null
        return when (val shape = definition.movementCollision) {
            is MovementCollisionShape.Rectangle -> shape
            is MovementCollisionShape.Circle -> {
                if (shape.radius <= 0.0) null
                else if (definition.explicitMovementCollision || moveType == MovementType.WATER) shape
                else {
                    val safeCap = IMPLICIT_LAND_TERRAIN_TILE_FRACTION * min(tileWidth, tileHeight).toDouble()
                    if (shape.radius <= safeCap) shape else MovementCollisionShape.Circle(safeCap)
                }
            }
        }
    }

    fun terrainHull(unit: MutableUnit, tileWidth: Int = 20, tileHeight: Int = 20): MovementCollisionShape? {
        if (unit.nativeTransportPlacement) return null
        return terrainHull(unit.definition, movement(unit).type, tileWidth, tileHeight)
    }

    fun terrainHull(unit: MutableUnit, map: MapDefinition): MovementCollisionShape? =
        terrainHull(unit, map.tileWidth, map.tileHeight)

    fun terrainHull(unit: MutableUnit, terrain: PlanningTerrain): MovementCollisionShape? =
        terrainHull(unit, terrain.tileWidth, terrain.tileHeight)

    fun terrainPassabilityRadius(unit: MutableUnit, tileWidth: Int = 20, tileHeight: Int = 20): Double =
        when (val shape = terrainHull(unit, tileWidth, tileHeight)) {
            is MovementCollisionShape.Rectangle -> min(shape.length, shape.width) * 0.5
            is MovementCollisionShape.Circle -> shape.radius
            null -> 0.0
        }

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
