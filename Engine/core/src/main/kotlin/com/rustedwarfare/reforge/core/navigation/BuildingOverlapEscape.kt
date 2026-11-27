package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.ScreenPlane
import com.rustedwarfare.reforge.core.sim.MapBounds
import com.rustedwarfare.reforge.core.sim.MutableUnit

import com.rustedwarfare.reforge.core.content.FactoryExitProfile
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Single authoritative & authorized safe-escape rule for units that overlap their parent factory
 * or a newly placed building footprint (ADR-006 / ADR-016 / ENG-P017-004 / ENG-P017-006).
 *
 * Uses O(1) local spatial/tile queries against [DynamicBuildingGrid] with zero unit-ID whitelists.
 */
internal object BuildingOverlapEscape {

    /**
     * Returns true when [unit]'s center currently occupies a dynamic building cell that is passable
     * on underlying hard ground [baseMap], permitting turn/heading escape without penetrating hard ground.
     */
    fun isCenterEscapingBuildingCell(
        unit: MutableUnit,
        baseMap: MapDefinition,
        buildingTerrain: DynamicBuildingGrid?,
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
        buildingTerrain: DynamicBuildingGrid?,
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
        forEachNearbyLiveBuilding(pos, circle.radius, map, buildingTerrain, spatialIndex, units) { buildingUnit ->
            val building = checkNotNull(buildingUnit.definition.building)
            val fp = buildingTerrain.footprintCells(map, buildingUnit.position, building.footprint, building.anchor)
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
        buildingTerrain: DynamicBuildingGrid?,
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
            buildingTerrain = buildingTerrain,
        )
        if (buildingTerrain == null || shape !is MovementCollisionShape.Circle || shape.radius <= 0.0) {
            return seaCells
        }
        val start = unit.position
        if (!touchesAnyBuildingTile(start, shape.radius, map, buildingTerrain)) return seaCells
        val dx = next.x - start.x
        val dy = next.y - start.y
        var released: MutableSet<Pair<Int, Int>>? = if (seaCells.isEmpty()) null else seaCells.toMutableSet()
        forEachNearbyLiveBuilding(start, shape.radius, map, buildingTerrain, spatialIndex, units) { other ->
            val building = checkNotNull(other.definition.building)
            val footprintCells = buildingTerrain.footprintCells(map, other.position, building.footprint, building.anchor)
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

    /**
     * Static geometric overlap test between [unit]'s authoritative collision hull at its current
     * `(position, heading)` and [producer]'s building footprint (both circles and rotated rectangles).
     * Unlike [departingSeaFactoryCells], this does NOT require a non-zero separation vector `next`.
     */
    fun overlapsProducerFootprint(
        map: MapDefinition,
        buildingTerrain: DynamicBuildingGrid?,
        unit: MutableUnit,
        producer: MutableUnit?,
    ): Boolean {
        if (producer?.alive != true) return false
        val building = producer.definition.building ?: return false
        val producerCells = buildingTerrain?.footprintCells(map, producer.position, building.footprint, building.anchor)
            ?: pureFootprintCells(map, producer.position, building.footprint, building.anchor)
        if (producerCells.isEmpty()) return false
        val north = producerCells.minOf { it.second }.toDouble() * map.tileHeight
        val west = producerCells.minOf { it.first }.toDouble() * map.tileWidth
        val east = (producerCells.maxOf { it.first } + 1.0) * map.tileWidth
        val south = (producerCells.maxOf { it.second } + 1.0) * map.tileHeight
        val pos = unit.position
        return when (val shape = unit.definition.movementCollision) {
            is MovementCollisionShape.Circle -> {
                val r = shape.radius
                if (r <= 0.0) {
                    pos.x in west..east && pos.y in north..south
                } else {
                    val ox = pos.x - pos.x.coerceIn(west, east)
                    val oy = pos.y - pos.y.coerceIn(north, south)
                    (ox == 0.0 && oy == 0.0) || (ox * ox + oy * oy < r * r)
                }
            }
            is MovementCollisionShape.Rectangle -> UnitContact.posesOverlap(
                shape,
                pos,
                unit.heading,
                MovementCollisionShape.Rectangle(south - north, east - west),
                Position((west + east) / 2.0, (north + south) / 2.0),
                0.0,
            )
        }
    }

    fun departingSeaFactoryCells(
        map: MapDefinition,
        unit: MutableUnit,
        producer: MutableUnit?,
        next: Position,
        shape: MovementCollisionShape,
        requireExitOrder: Boolean = true,
        buildingTerrain: DynamicBuildingGrid? = null,
    ): Set<Pair<Int, Int>> {
        if (producer?.alive != true || producer.definition.production?.exitProfile != FactoryExitProfile.NATIVE_SEA ||
            (requireExitOrder && unit.originMoveId == null) || next == unit.position
        ) return emptySet()
        val building = checkNotNull(producer.definition.building)
        val producerCells = buildingTerrain?.footprintCells(map, producer.position, building.footprint, building.anchor)
            ?: pureFootprintCells(map, producer.position, building.footprint, building.anchor)
        if (producerCells.isEmpty()) return emptySet()
        val north = producerCells.minOf { it.second }.toDouble() * map.tileHeight
        val west = producerCells.minOf { it.first }.toDouble() * map.tileWidth
        val east = (producerCells.maxOf { it.first } + 1.0) * map.tileWidth
        val south = (producerCells.maxOf { it.second } + 1.0) * map.tileHeight
        val start = unit.position
        val leaving = when (shape) {
            is MovementCollisionShape.Circle ->
                CircularTerrain.separatesFrom(start, next.x - start.x, next.y - start.y, shape.radius, west, east, north, south)
            is MovementCollisionShape.Rectangle ->
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

    private fun pureFootprintCells(
        map: MapDefinition,
        position: Position,
        shape: com.rustedwarfare.reforge.core.content.BuildingFootprint,
        anchor: com.rustedwarfare.reforge.core.content.FootprintAnchor?,
    ): Set<Pair<Int, Int>> {
        val column = if (anchor == null) MapBounds.cell(position.x, map.tileWidth, map.widthTiles)
        else (((position.x.toFloat() - anchor.offsetX) + 1f) * (1f / map.tileWidth.toFloat())).toInt()
        val row = if (anchor == null) MapBounds.cell(position.y, map.tileHeight, map.heightTiles)
        else (((position.y.toFloat() - anchor.offsetY) + 1f) * (1f / map.tileHeight.toFloat())).toInt()
        val cells = LinkedHashSet<Pair<Int, Int>>()
        for (r in (row + shape.top)..(row + shape.bottom)) {
            for (c in (column + shape.left)..(column + shape.right)) {
                if (c in 0 until map.widthTiles && r in 0 until map.heightTiles) cells.add(c to r)
            }
        }
        return cells
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
        buildingTerrain: DynamicBuildingGrid?,
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
        buildingTerrain: DynamicBuildingGrid,
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
        buildingTerrain: DynamicBuildingGrid,
        spatialIndex: WorldSpatialIndex?,
        units: Map<String, MutableUnit>,
        visit: (MutableUnit) -> Unit,
    ) {
        val reach = radius + maxOf(map.tileWidth, map.tileHeight) * buildingTerrain.maxHalfExtentTiles.toDouble()
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
