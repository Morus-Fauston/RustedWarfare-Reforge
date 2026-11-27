package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.FactoryExitProfile
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Single authoritative & authorized safe-escape rule for units that overlap their parent factory
 * or a newly placed building footprint (ADR-006 / ADR-016 / ENG-P017-004).
 *
 * Consolidates the 5 previously duplicated overlap-escape implementations across
 * [SimulationContext], [FactoryExit], [HullTerrainGrid], [UnitMovementOrchestrator], and [UnitContact],
 * using local spatial/tile queries in O(1) instead of scanning all live units on the map.
 */
internal object BuildingOverlapEscape {
    private val nativeSeaFactoryIds = setOf("seaFactory", "seaFactoryT2")

    /**
     * Returns true when [unit]'s center currently occupies a dynamic building cell that is passable
     * on underlying hard ground [baseMap], permitting turn/heading escape without penetrating hard ground.
     */
    fun isCenterEscapingBuildingCell(
        unit: MutableUnit,
        baseMap: MapDefinition,
        buildingTerrain: BuildingTerrain?,
        movementType: MovementType,
    ): Boolean {
        if (unit.birthRemaining > 0f || buildingTerrain == null) return false
        val col = MapBounds.cell(unit.position.x, baseMap.tileWidth, baseMap.widthTiles)
        val row = MapBounds.cell(unit.position.y, baseMap.tileHeight, baseMap.heightTiles)
        if (col !in 0 until baseMap.widthTiles || row !in 0 until baseMap.heightTiles) return false
        return buildingTerrain.collisionMap.blockingFor(movementType).isBlocked(col, row) &&
            !baseMap.blockingFor(movementType).isBlocked(col, row)
    }

    /**
     * Returns the subset of [ownerBuildings] cells belonging to live buildings that [unit]'s circular
     * terrain hull ALREADY overlaps at [unit.position] (when its center cell is not itself inside
     * [ownerBuildings]), so planning/avoidance does not trap the unit on its own overlapping edge.
     */
    fun overlappedAuthorizedBuildingCells(
        unit: MutableUnit,
        map: MapDefinition,
        buildingTerrain: BuildingTerrain?,
        spatialIndex: WorldSpatialIndex,
        units: Map<String, MutableUnit>,
        ownerBuildings: Set<Pair<Int, Int>>,
    ): Set<Pair<Int, Int>> {
        if (ownerBuildings.isEmpty() || buildingTerrain == null) return emptySet()
        val circle = UnitLocomotion.terrainHull(unit, map) as? MovementCollisionShape.Circle ?: return emptySet()
        if (circle.radius <= 0.0) return emptySet()
        val pos = unit.position
        if (!touchesAnyBuildingTile(pos, circle.radius, map, buildingTerrain)) return emptySet()

        var excluded: HashSet<Pair<Int, Int>>? = null
        forEachNearbyLiveBuilding(pos, circle.radius, map, spatialIndex, units) { buildingUnit ->
            val building = checkNotNull(buildingUnit.definition.building)
            val fp = BuildingTerrain.footprint(map, buildingUnit.position, building.footprint, building.anchor)
            if (fp.isNotEmpty() && circleOverlapsFootprint(pos, circle.radius, map, fp)) {
                for (cell in fp) {
                    if (cell in ownerBuildings) {
                        val set = excluded ?: HashSet<Pair<Int, Int>>().also { excluded = it }
                        set.add(cell)
                    }
                }
            }
        }
        return excluded ?: emptySet()
    }

    /**
     * Releases any live building footprint that [unit] ALREADY overlaps at [unit.position] along
     * displacement directions `unit.position -> next` that monotonically separate from that building's
     * footprint rectangle, while keeping underlying hard ground and all other buildings solid.
     */
    fun departingOverlappedBuildingCells(
        map: MapDefinition,
        buildingTerrain: BuildingTerrain?,
        spatialIndex: WorldSpatialIndex?,
        units: Map<String, MutableUnit>,
        unit: MutableUnit,
        next: Position,
        shape: MovementCollisionShape,
        requireExitOrderForSea: Boolean = true,
    ): Set<Pair<Int, Int>> {
        if (next == unit.position) return emptySet()
        val seaCells = departingSeaFactoryCells(
            map = map,
            unit = unit,
            producer = unit.productionOrigin?.let { units[it.producerId] },
            next = next,
            shape = shape,
            requireExitOrder = requireExitOrderForSea,
        )
        if (buildingTerrain == null || shape !is MovementCollisionShape.Circle || shape.radius <= 0.0) {
            return seaCells
        }
        val start = unit.position
        if (!touchesAnyBuildingTile(start, shape.radius, map, buildingTerrain)) return seaCells
        val dx = next.x - start.x
        val dy = next.y - start.y
        var released: MutableSet<Pair<Int, Int>>? = if (seaCells.isEmpty()) null else seaCells.toMutableSet()
        forEachNearbyLiveBuilding(start, shape.radius, map, spatialIndex, units) { other ->
            val building = checkNotNull(other.definition.building)
            val footprintCells = BuildingTerrain.footprint(map, other.position, building.footprint, building.anchor)
            if (footprintCells.isNotEmpty()) {
                val west = footprintCells.minOf { it.first }.toDouble() * map.tileWidth
                val east = (footprintCells.maxOf { it.first } + 1.0) * map.tileWidth
                val north = footprintCells.minOf { it.second }.toDouble() * map.tileHeight
                val south = (footprintCells.maxOf { it.second } + 1.0) * map.tileHeight
                val ox = start.x - start.x.coerceIn(west, east)
                val oy = start.y - start.y.coerceIn(north, south)
                val overlapsAtStart = (ox == 0.0 && oy == 0.0) || (ox * ox + oy * oy < shape.radius * shape.radius)
                if (overlapsAtStart && CircularTerrain.separatesFrom(start, dx, dy, shape.radius, west, east, north, south)) {
                    val acc = released ?: HashSet<Pair<Int, Int>>().also { released = it }
                    acc.addAll(footprintCells)
                }
            }
        }
        return released ?: emptySet()
    }

    fun departingSeaFactoryCells(
        map: MapDefinition,
        unit: MutableUnit,
        producer: MutableUnit?,
        next: Position,
        shape: MovementCollisionShape,
        requireExitOrder: Boolean = true,
    ): Set<Pair<Int, Int>> {
        if (producer?.alive != true || producer.definition.production?.exitProfile != FactoryExitProfile.NATIVE_SEA ||
            (requireExitOrder && unit.originMoveId == null) || next == unit.position
        ) return emptySet()
        val building = checkNotNull(producer.definition.building)
        val producerCells = BuildingTerrain.footprint(map, producer.position, building.footprint, building.anchor)
        val north = producerCells.minOf { it.second }.toDouble() * map.tileHeight
        val west = producerCells.minOf { it.first }.toDouble() * map.tileWidth
        val east = (producerCells.maxOf { it.first } + 1.0) * map.tileWidth
        val south = (producerCells.maxOf { it.second } + 1.0) * map.tileHeight
        val start = unit.position
        val leaving = when (shape) {
            is MovementCollisionShape.Circle ->
                CircularTerrain.separatesFrom(start, next.x - start.x, next.y - start.y, shape.radius, west, east, north, south)
            is MovementCollisionShape.Rectangle ->
                (producer.definition.id in nativeSeaFactoryIds || unit.originMoveId == "production-exit:${unit.unitId}") &&
                    start.y >= producer.position.y && next.y > start.y &&
                    start.y - hypot(shape.length / 2.0, shape.width / 2.0) < south &&
                    (requireExitOrder || UnitContact.separatesExistingOverlap(
                        shape, start, unit.heading, next,
                        MovementCollisionShape.Rectangle(south - north, east - west),
                        Position((west + east) / 2.0, (north + south) / 2.0), 0.0,
                    ))
        }
        return if (leaving) producerCells else emptySet()
    }

    fun leavingGrid(
        collisionMap: MapDefinition,
        hardGroundMap: MapDefinition,
        movementType: MovementType,
        escapedCells: Set<Pair<Int, Int>>,
        ordinaryGrid: HullTerrainGrid?,
    ): HullTerrainGrid? {
        if (escapedCells.isEmpty()) return ordinaryGrid
        return HullTerrainGrid.leavingProducer(collisionMap, hardGroundMap, movementType, escapedCells)
    }

    /**
     * Resolves circular terrain translation & wall-sliding while preventing internal 20x20 sub-tile
     * seam snags when sliding tangentially along an already-overlapped multi-tile building footprint.
     */
    fun resolveCircleTranslation(
        circle: CircularTerrain,
        collisionMap: MapDefinition,
        hardGroundMap: MapDefinition,
        buildingTerrain: BuildingTerrain?,
        spatialIndex: WorldSpatialIndex?,
        units: Map<String, MutableUnit>,
        unit: MutableUnit,
        movementType: MovementType,
        ordinaryGrid: HullTerrainGrid,
        candidate: Position,
        shape: MovementCollisionShape.Circle,
        producerCells: Set<Pair<Int, Int>>,
    ): Position {
        val start = unit.position
        if (producerCells.isNotEmpty()) {
            val grid = checkNotNull(leavingGrid(collisionMap, hardGroundMap, movementType, producerCells, ordinaryGrid))
            return circle.resolve(grid, start, candidate)
        }
        val direct = circle.resolveWithSlide(ordinaryGrid, start, candidate)
        if (direct != start || buildingTerrain == null || candidate == start) return direct

        var best = start
        var bestDistSq = 0.0
        val axes = arrayOf(Position(candidate.x, start.y), Position(start.x, candidate.y))
        for (axisCandidate in axes) {
            if (axisCandidate == start) continue
            val axisCells = departingOverlappedBuildingCells(
                hardGroundMap, buildingTerrain, spatialIndex, units, unit, axisCandidate, shape,
            )
            if (axisCells.isEmpty()) continue
            val axisGrid = checkNotNull(leavingGrid(collisionMap, hardGroundMap, movementType, axisCells, ordinaryGrid))
            val resolved = circle.resolve(axisGrid, start, axisCandidate)
            val dSq = ScreenPlane.distanceSquared(start, resolved)
            if (dSq > bestDistSq) {
                bestDistSq = dSq
                best = resolved
            }
        }
        return best
    }

    private fun touchesAnyBuildingTile(
        pos: Position,
        radius: Double,
        map: MapDefinition,
        buildingTerrain: BuildingTerrain,
    ): Boolean {
        val c0 = floor((pos.x - radius) / map.tileWidth).toInt().coerceAtLeast(0)
        val c1 = floor((pos.x + radius) / map.tileWidth).toInt().coerceAtMost(map.widthTiles - 1)
        val r0 = floor((pos.y - radius) / map.tileHeight).toInt().coerceAtLeast(0)
        val r1 = floor((pos.y + radius) / map.tileHeight).toInt().coerceAtMost(map.heightTiles - 1)
        for (r in r0..r1) {
            val minY = r.toDouble() * map.tileHeight
            val maxY = minY + map.tileHeight
            for (c in c0..c1) {
                if (!buildingTerrain.isBuildingCell(c, r)) continue
                val minX = c.toDouble() * map.tileWidth
                val maxX = minX + map.tileWidth
                val ox = pos.x - pos.x.coerceIn(minX, maxX)
                val oy = pos.y - pos.y.coerceIn(minY, maxY)
                if (ox * ox + oy * oy < radius * radius) return true
            }
        }
        return false
    }

    private fun circleOverlapsFootprint(
        pos: Position,
        radius: Double,
        map: MapDefinition,
        fp: Set<Pair<Int, Int>>,
    ): Boolean {
        val west = fp.minOf { it.first }.toDouble() * map.tileWidth
        val east = (fp.maxOf { it.first } + 1.0) * map.tileWidth
        val north = fp.minOf { it.second }.toDouble() * map.tileHeight
        val south = (fp.maxOf { it.second } + 1.0) * map.tileHeight
        val dx = pos.x - pos.x.coerceIn(west, east)
        val dy = pos.y - pos.y.coerceIn(north, south)
        return (dx == 0.0 && dy == 0.0) || dx * dx + dy * dy < radius * radius
    }

    private inline fun forEachNearbyLiveBuilding(
        pos: Position,
        radius: Double,
        map: MapDefinition,
        spatialIndex: WorldSpatialIndex?,
        units: Map<String, MutableUnit>,
        visit: (MutableUnit) -> Unit,
    ) {
        // Max building footprint half-extent margin (6 tiles) ensures any overlapping building center is inside the box.
        val reach = radius + maxOf(map.tileWidth, map.tileHeight) * 6.0
        if (spatialIndex != null && !spatialIndex.isEmpty()) {
            val candidates = spatialIndex.queryAllLayersCenterBox(pos, reach)
            for (i in 0 until candidates.size) {
                val other = candidates[i]
                if (other.alive && other.definition.building != null) visit(other)
            }
        } else {
            for (other in units.values) {
                if (other.alive && other.definition.building != null) visit(other)
            }
        }
    }
}
