package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementProfile
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.TransportProfile
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.sim.MapBounds
import com.rustedwarfare.reforge.core.sim.MutableUnit
import com.rustedwarfare.reforge.core.sim.Position
import kotlin.math.abs
import kotlin.math.min

/**
 * Pure data-driven physical locomotion authority shared by planning, guidance, steering,
 * contact, and spatial indexing (ADR-016 / ENG-P017-006). Zero unit-ID whitelists.
 */
internal object UnitLocomotion {
    const val IMPLICIT_LAND_TERRAIN_TILE_FRACTION = 0.45
    const val SUBMERGED_DEPTH = -1.0
    private const val STANDARD_LAND_CAP_20_RADIUS = IMPLICIT_LAND_TERRAIN_TILE_FRACTION * 20.0
    private val STANDARD_LAND_CAP_20 = MovementCollisionShape.Circle(STANDARD_LAND_CAP_20_RADIUS)

    fun routeTypes(definition: UnitDefinition): Set<MovementType> {
        val base = definition.movement.type
        val diving = definition.effectiveSubmersible?.takeIf { it.divingChangesRoute }
        return if (diving == null) setOf(base) else setOf(base, MovementType.WATER)
    }

    fun isSubmerged(unit: MutableUnit, height: Double = unit.height): Boolean = isSubmerged(unit.definition, height)

    fun isSubmerged(definition: UnitDefinition, height: Double): Boolean =
        definition.effectiveSubmersible != null && height < SUBMERGED_DEPTH

    fun isFlying(unit: MutableUnit): Boolean =
        if (unit.amphibiousState != null) movement(unit).type == MovementType.AIR
        else unit.definition.movement.type == MovementType.AIR && !isSubmerged(unit) && unit.height >= 4.0

    fun isUnderwater(unit: MutableUnit, height: Double = unit.height): Boolean = isUnderwater(unit.definition, height)

    fun isUnderwater(definition: UnitDefinition, height: Double): Boolean =
        isSubmerged(definition, height) ||
            definition.effectiveVerticalPose?.let { it.targetHeight < SUBMERGED_DEPTH && height < SUBMERGED_DEPTH } == true ||
            definition.transport == TransportProfile.NAUTILUS_SUBMERGED && height < SUBMERGED_DEPTH ||
            definition.fixedUnderwaterTarget != null && height <= SUBMERGED_DEPTH

    fun shortReverseDistance(definition: UnitDefinition): Double? = definition.effectiveShortReverseDistance

    fun boundaryRadius(unit: MutableUnit): Double = if (unit.nativeTransportPlacement) 0.0 else unit.definition.radius

    fun queryPosition(position: Position, nativePlacement: Boolean, map: MapDefinition): Position =
        if (nativePlacement) MapBounds.clamp(map, position) else position

    fun queryPosition(unit: MutableUnit, map: MapDefinition, position: Position = unit.position): Position =
        queryPosition(position, unit.nativeTransportPlacement, map)

    fun containsPosition(unit: MutableUnit, map: MapDefinition, position: Position): Boolean {
        val within = if (unit.nativeTransportPlacement) {
            MapBounds.contains(
                map.widthTiles.toDouble() * map.tileWidth,
                map.heightTiles.toDouble() * map.tileHeight,
                position,
                0.0,
            )
        } else when (val shape = unit.definition.movementCollision) {
            is MovementCollisionShape.Circle -> MapBounds.contains(
                map.widthTiles.toDouble() * map.tileWidth,
                map.heightTiles.toDouble() * map.tileHeight,
                position,
                maxOf(shape.radius, unit.definition.radius),
            )
            else -> MapBounds.containsHull(map, position, unit.heading, shape)
        }
        if (within) return true
        if (!unit.nativeTransportPlacement || !position.x.isFinite() || !position.y.isFinite()) return false
        if (unit.scoutOrigin != null) {
            val start = MapBounds.clamp(map, unit.position)
            val end = MapBounds.clamp(map, position)
            return abs(position.x - end.x) <= abs(unit.position.x - start.x) &&
                abs(position.y - end.y) <= abs(unit.position.y - start.y)
        }
        val column = (position.x.toFloat() / map.tileWidth).toInt()
        val row = (position.y.toFloat() / map.tileHeight).toInt()
        return column in 0 until map.widthTiles && row in 0 until map.heightTiles
    }

    /**
     * Unified single-unit and group-move terrain passability profile (ENG-P016-009C / ADR-016):
     * - Explicit `movementCollision` (and naval `WATER` bodies) use their declared shape across the full chain.
     * - Implicit circular land/hover/cliff units retain full `radius` for unit-to-unit contact while
     *   capping static terrain passability radius at `min(radius, 0.45 * minTile)`.
     */
    fun terrainHull(
        definition: UnitDefinition,
        moveType: MovementType = definition.movement.type,
        tileWidth: Int = 20,
        tileHeight: Int = 20,
    ): MovementCollisionShape? {
        if (moveType == MovementType.AIR || moveType == MovementType.NONE) return null
        return when (val shape = definition.movementCollision) {
            is MovementCollisionShape.Rectangle -> shape
            is MovementCollisionShape.Circle -> {
                if (shape.radius <= 0.0) null
                else if (definition.explicitMovementCollision || moveType == MovementType.WATER) shape
                else {
                    val safeCap = IMPLICIT_LAND_TERRAIN_TILE_FRACTION * min(tileWidth, tileHeight).toDouble()
                    when {
                        shape.radius <= safeCap -> shape
                        safeCap == STANDARD_LAND_CAP_20_RADIUS -> STANDARD_LAND_CAP_20
                        else -> MovementCollisionShape.Circle(safeCap)
                    }
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

    fun terrainHull(unit: MutableUnit, terrain: AuthorizedNavigationTerrain): MovementCollisionShape? =
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
        val submersible = unit.definition.effectiveSubmersible
        val underwater = isSubmerged(unit)
        val diving = if (submersible != null) {
            if (unit.amphibiousState != null) unit.amphibiousState?.flyingIntent == false
            else !unit.submarineSurfaceIntent
        } else false
        val waterRoute = submersible != null && submersible.divingChangesRoute && (diving || unit.height < 0.0)
        val resolvedType = if (waterRoute) MovementType.WATER else base.type
        if (underwater && submersible != null) {
            return base.copy(
                type = resolvedType,
                moveSpeed = submersible.submergedSpeed,
                maxTurnSpeed = submersible.submergedTurnSpeed ?: base.maxTurnSpeed,
            )
        }
        unit.definition.effectiveWaterLandSpeed?.let { dual ->
            val onWater = unit.transportState?.liquid ?: unit.overLiquid
            return base.copy(
                type = resolvedType,
                moveSpeed = if (onWater) dual.waterMoveSpeed else dual.landMoveSpeed,
                maxTurnSpeed = (if (onWater) dual.waterTurnSpeed else dual.landTurnSpeed) ?: base.maxTurnSpeed,
            )
        }
        return if (resolvedType != base.type) base.copy(type = resolvedType) else base
    }

    fun describe(unit: MutableUnit, tileWidth: Int = 20, tileHeight: Int = 20): LocomotionDescriptor {
        val m = movement(unit)
        val def = unit.definition
        return LocomotionDescriptor(
            movementType = m.type,
            terrainHull = terrainHull(unit, tileWidth, tileHeight),
            contactShape = def.movementCollision,
            mass = def.mass?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0,
            moveSpeed = effectiveMaximumSpeed(unit, m),
            moveAccelerationSpeed = m.moveAccelerationSpeed,
            moveDecelerationSpeed = m.moveDecelerationSpeed,
            maxTurnSpeed = m.maxTurnSpeed,
            turnAcceleration = m.turnAcceleration,
            reverseSpeedPercentage = m.reverseSpeedPercentage,
            shortReverseDistance = def.effectiveShortReverseDistance,
            slidingMode = m.slidingMode,
            ignoringBody = m.ignoringBody,
            waterLandSpeed = def.effectiveWaterLandSpeed,
            submersible = def.effectiveSubmersible,
            verticalPose = def.effectiveVerticalPose,
            sinksInWaterOnDeath = def.sinksInWaterOnDeath,
        )
    }
}
