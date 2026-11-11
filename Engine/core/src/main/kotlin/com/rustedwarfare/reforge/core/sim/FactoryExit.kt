package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.math.cos
import kotlin.math.sin

internal data class FactoryBirth(val position: Position, val heading: Double, val target: Position,
    val protection: Float, val fallback: Boolean = false)

/** Finite supported factory exits; no unit collision search or new spawn-file interpreter. */
internal object FactoryExit {
    fun supportsSeaFactory(definition: UnitDefinition): Boolean {
        val building = definition.building ?: return false
        val t2 = definition.id == "seaFactoryT2"
        return definition.id in setOf("seaFactory", "seaFactoryT2") &&
            definition.production?.let { it.exitProfile == FactoryExitProfile.NATIVE_SEA &&
                it.unitTypeIds.isNotEmpty() && it.unitTypeIds.distinct().size == it.unitTypeIds.size &&
                it.unitTypeIds.all { id -> id in (if (t2) setOf("heavyBattleship", "attackSubmarine", "hovercraft", "nautilusSubmarine") else setOf("attackSubmarine", "hovercraft")) } } == true &&
            definition.build == BuildProfile(if (t2) 3000L else 1000L, .0007f) &&
            definition.construction == ConstructionProfile(if (t2) 3000L else 1000L, .0007f) &&
            definition.radius == 45.0 && definition.maximumHealth == 1000.0 &&
            definition.movement == MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false) &&
            definition.fogOfWarSightRange == 15 && definition.combat == null && definition.mech == null &&
            definition.builder == null && definition.income == null && definition.pointDefense == null &&
            definition.rotor == null && definition.fixedUnderwaterTarget == null && definition.deathExplosion == null &&
            definition.upgrades?.actions.orEmpty() == (if (t2) emptyList() else
                listOf(UpgradeActionProfile("upgrade_t2", "seaFactoryT2", 2000L, .0004f))) &&
            (if (t2) definition.upgrades == null else definition.upgrades != null) &&
            definition.directHitTargetKind == DirectHitTargetKind.NATIVE_BUILDING && definition.durability?.maxDamagePerHit == null &&
            building == BuildingProfile(BuildingFootprint(-1, -1, 1, 2), placementFootprint = BuildingFootprint(-2, -1, 2, 4))
    }

    fun supportsAirFactory(definition: UnitDefinition): Boolean {
        val building = definition.building ?: return false
        val t2 = definition.id == "airFactoryT2"
        return definition.id in setOf("airFactory", "airFactoryT2") && definition.production?.exitProfile == FactoryExitProfile.NATIVE_AIR &&
            (if (t2) definition.production.unitTypeIds.isNotEmpty() &&
                definition.production.unitTypeIds.distinct().size == definition.production.unitTypeIds.size &&
                definition.production.unitTypeIds.all { it in setOf("helicopter", "amphibiousJet", "dropship", "aaBeamGunship") }
                else definition.production.unitTypeIds == listOf("helicopter")) &&
            definition.build == BuildProfile(if (t2) 2500L else 1000L, .001f) &&
            definition.construction == ConstructionProfile(if (t2) 2500L else 1000L, .001f) &&
            definition.radius == 30.0 && definition.maximumHealth == 1000.0 &&
            definition.movement.type == MovementType.NONE && definition.fogOfWarSightRange == 15 &&
            definition.combat == null && definition.mech == null && definition.builder == null && definition.income == null &&
            definition.pointDefense == null && (definition.upgrades == null || !t2 && definition.upgrades.actions ==
                listOf(UpgradeActionProfile("upgrade_t2", "airFactoryT2", 1500L, .0004f))) &&
            definition.deathExplosion == null && definition.rotor == null &&
            definition.directHitTargetKind == DirectHitTargetKind.NATIVE_BUILDING && definition.durability?.maxDamagePerHit == null &&
            building.footprint == BuildingFootprint(-1, -1, 1, 1) &&
            building.placementFootprint == BuildingFootprint(-1, -1, 1, 2) && building.backImagePath == null &&
            building.anchor == null && !building.requiresResourcePool
    }

    fun supportsMechFactory(definition: UnitDefinition): Boolean {
        val building = definition.building ?: return false
        val t2 = definition.id == "mechFactoryT2"
        val upgrades = definition.upgrades?.actions
        return definition.id in setOf("mechFactory", "mechFactoryT2") && definition.production?.exitProfile == FactoryExitProfile.CUSTOM_MECH &&
            definition.production.unitTypeIds.let { products ->
                val base = if (t2) listOf("builder", "mechGun", "mechHeavyMissile") else listOf("builder", "mechGun")
                val additions = (if ("mechBunker" in products) listOf("mechBunker") else emptyList()) +
                    (if (t2 && "mechEngineer" in products) listOf("mechEngineer") else emptyList())
                products == base + additions
            } &&
            definition.build == BuildProfile(if (t2) 5000L else 1000L, if (t2) .0005f else .001f) &&
            definition.construction == ConstructionProfile(if (t2) 5000L else 1000L, if (t2) .0005f else .001f) &&
            definition.radius == 35.0 && definition.maximumHealth == (if (t2) 3100.0 else 1800.0) &&
            definition.movement.type == MovementType.NONE && definition.fogOfWarSightRange == 15 &&
            definition.combat == null && definition.mech == null && definition.builder == null && definition.income == null &&
            definition.pointDefense == null &&
            (upgrades == null || !t2 && upgrades == listOf(UpgradeActionProfile("upgrade_t2", "mechFactoryT2", 4000L, .0004f))) &&
            definition.deathExplosion == null && definition.rotor == null &&
            definition.directHitTargetKind == DirectHitTargetKind.ORDINARY && definition.durability?.maxDamagePerHit == null &&
            building.footprint == BuildingFootprint(-1, -1, 1, 1) &&
            building.placementFootprint == BuildingFootprint(-1, -1, 1, 3) && building.anchor == null && !building.requiresResourcePool
    }

    fun birth(factory: MutableUnit, product: UnitDefinition, map: MapDefinition): FactoryBirth {
        if (factory.definition.production?.exitProfile == FactoryExitProfile.NATIVE_EXPERIMENTAL) {
            val speed = product.movement.moveSpeed.toFloat()
            val protection = 70f + (if (speed < .75f) 30f else 0f) + (if (speed < .55f) 20f else 0f)
            return FactoryBirth(Position(factory.position.x, maxOf(factory.position.y + 5.0,
                factory.position.y - 100.0 + product.radius)), 180.0,
                Position(factory.position.x - 1.0, factory.position.y + factory.definition.radius * 3.0), protection)
        }
        if (factory.definition.production?.exitProfile == FactoryExitProfile.CUSTOM_NAUTILUS) {
            // Original custom F first writes world-axis offsets, then the general exit helper.
            val angle = factory.heading.toFloat() - 90f
            val side = if (factory.productionSide) -7f else 7f
            val x = factory.position.x.toFloat() + GroundMath.cos(angle) * 30f + GroundMath.cos(angle + 90f) * side
            val y = factory.position.y.toFloat() + GroundMath.sin(angle) * 30f + GroundMath.sin(angle + 90f) * side
            val candidates = listOf(x to y, x + 10f to y, x - 10f to y, x - 20f to y + 10f, x - 20f to y - 10f)
            val blocking = map.blockingFor(product.movement.type)
            val chosen = candidates.firstOrNull { (px, py) ->
                val column = (px / map.tileWidth).toInt(); val row = (py / map.tileHeight).toInt()
                column in 0 until map.widthTiles && row in 0 until map.heightTiles && !blocking.isBlocked(column, row)
            } ?: return FactoryBirth(Position(factory.position.x, factory.position.y + 30.0), 180.0,
                Position(factory.position.x - 1.0, factory.position.y + 90.0), 30f, true)
            val point = Position(chosen.first.toDouble(), chosen.second.toDouble())
            val away = angle + side
            return FactoryBirth(point, factory.heading,
                Position((chosen.first + GroundMath.cos(away) * 110f).toDouble(),
                    (chosen.second + GroundMath.sin(away) * 110f).toDouble()), 45f)
        }
        if (factory.definition.production?.exitProfile == FactoryExitProfile.NATIVE_SEA) {
            val speed = product.movement.moveSpeed.toFloat()
            val protection = 30f + (if (speed < .75f) 30f else 0f) + (if (speed < .55f) 20f else 0f)
            return FactoryBirth(Position(factory.position.x, maxOf(factory.position.y + 5.0,
                factory.position.y - 20.0 + product.radius)), 180.0,
                Position(factory.position.x - 1.0, factory.position.y + 135.0), protection)
        }
        if (factory.definition.production?.exitProfile != FactoryExitProfile.CUSTOM_MECH)
            return FactoryBirth(Position(factory.position.x, factory.position.y + 5.0), 180.0,
                Position(factory.position.x - 1.0, factory.position.y + 90.0), 30f)
        val side = if (factory.productionSide) -7.0 else 7.0
        val heading = ScreenPlane.normalize(factory.heading + 180.0)
        val forward = Math.toRadians(heading)
        val across = Math.toRadians(factory.heading + 90.0)
        val x = factory.position.x - sin(forward) + sin(across) * side
        val y = factory.position.y + cos(forward) - cos(across) * side
        val candidates = listOf(Position(x, y), Position(x + 10.0, y), Position(x - 10.0, y),
            Position(x - 20.0, y + 10.0), Position(x - 20.0, y - 10.0))
        val chosen = candidates.firstOrNull { point ->
            MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth, map.heightTiles.toDouble() * map.tileHeight,
                point, product.radius) && !map.blocking.isBlocked(
                MapBounds.cell(point.x, map.tileWidth, map.widthTiles), MapBounds.cell(point.y, map.tileHeight, map.heightTiles))
        }
        if (chosen == null) return FactoryBirth(Position(factory.position.x, factory.position.y - 1.0), 180.0,
            Position(factory.position.x - if (factory.productionSide) 0.0 else 1.0, factory.position.y + 120.0), 30f, true)
        val exitAngle = Math.toRadians(heading + side)
        return FactoryBirth(chosen, heading, Position(chosen.x + sin(exitAngle) * 120.0,
            chosen.y - cos(exitAngle) * 120.0), 45f)
    }
}
