package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.BuildingFootprint
import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.FootprintAnchor
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.foundationMovementType
import com.rustedwarfare.reforge.core.navigation.DynamicBuildingGrid
import java.util.Collections

internal class BuildingInitializationException(val error: SimulationError) : IllegalArgumentException(error.message)

/** Immutable live-building cost, separate from the original ground map. */
internal class BuildingTerrain private constructor(
    map: MapDefinition,
    occupied: Set<Pair<Int, Int>>,
    override val maxHalfExtentTiles: Int = DEFAULT_MAX_HALF_EXTENT_TILES,
) : DynamicBuildingGrid {
    private val widthTiles: Int = map.widthTiles
    private val heightTiles: Int = map.heightTiles
    override val cells: Set<Pair<Int, Int>> = Collections.unmodifiableSet(LinkedHashSet(occupied.sortedWith(
        compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })))
    private val buildingMask: BooleanArray = BooleanArray(widthTiles * heightTiles).also { mask ->
        for ((c, r) in cells) {
            if (c in 0 until widthTiles && r in 0 until heightTiles) mask[r * widthTiles + c] = true
        }
    }
    override fun isBuildingCell(column: Int, row: Int): Boolean =
        column in 0 until widthTiles && row in 0 until heightTiles && buildingMask[row * widthTiles + column]
    override fun footprintCells(
        map: MapDefinition,
        position: Position,
        shape: BuildingFootprint,
        anchor: FootprintAnchor?,
    ): Set<Pair<Int, Int>> = footprint(map, position, shape, anchor)
    override val collisionMap: MapDefinition = MapDefinition(map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight,
        map.tileSets, map.groundGids, BlockingGrid(map.widthTiles, map.heightTiles,
            BooleanArray(map.widthTiles * map.heightTiles) { index ->
                val column = index % map.widthTiles
                val row = index / map.widthTiles
                map.blocking.isBlocked(column, row) || buildingMask[index]
            }), map.waterObstacles + cells)

    companion object {
        private const val DEFAULT_MAX_HALF_EXTENT_TILES = 6

        private fun computeMaxHalfExtent(buildings: Collection<UnitDefinition>): Int {
            var maxExtent = DEFAULT_MAX_HALF_EXTENT_TILES
            for (def in buildings) {
                val fp = def.building?.footprint ?: continue
                val extent = maxOf(kotlin.math.abs(fp.left), kotlin.math.abs(fp.right),
                    kotlin.math.abs(fp.top), kotlin.math.abs(fp.bottom)) + 2
                if (extent > maxExtent) maxExtent = extent
            }
            return maxExtent
        }

        fun fromLive(map: MapDefinition, units: Collection<MutableUnit>, old: BuildingTerrain?): BuildingTerrain {
            val liveBuildings = units.filter { it.alive && it.definition.building != null }
            val cells = liveBuildings.flatMapTo(linkedSetOf()) {
                val building = checkNotNull(it.definition.building)
                footprint(map, it.position, building.footprint, building.anchor)
            }
            val maxExtent = computeMaxHalfExtent(liveBuildings.map { it.definition })
            return if (old != null && cells == old.cells && maxExtent == old.maxHalfExtentTiles) old
            else BuildingTerrain(map, cells, maxExtent)
        }

        fun initial(input: Scenario): BuildingTerrain? {
            val buildings = input.units.filter { input.unitDefinitions.getValue(it.typeId).building != null }
            if (buildings.isEmpty() && input.unitDefinitions.values.none { it.builder != null || it.groundHeight != null }) return null
            val cells = linkedSetOf<Pair<Int, Int>>()
            for (unit in buildings) {
                val definition = input.unitDefinitions.getValue(unit.typeId)
                cells += placementCells(input.map, definition, unit.position, cells)
            }
            for (unit in input.units) {
                val definition = input.unitDefinitions.getValue(unit.typeId)
                if (definition.building != null || definition.movement.type == MovementType.AIR) continue
                val cell = MapBounds.cell(unit.position.x, input.map.tileWidth, input.map.widthTiles) to
                    MapBounds.cell(unit.position.y, input.map.tileHeight, input.map.heightTiles)
                if (cell in cells) fail("Initial mobile unit center must be outside live building cost")
            }
            return BuildingTerrain(input.map, cells, computeMaxHalfExtent(input.unitDefinitions.values))
        }

        /** Check this proposed building only; existing live products may still occupy their birth factory. */
        fun placementCells(map: MapDefinition, definition: UnitDefinition, position: Position,
                           occupied: Set<Pair<Int, Int>>): Set<Pair<Int, Int>> {
            val building = checkNotNull(definition.building)
            if (building.requiresResourcePool && !map.isResourcePool(
                    MapBounds.cell(position.x, map.tileWidth, map.widthTiles),
                    MapBounds.cell(position.y, map.tileHeight, map.heightTiles)))
                fail("Resource building requires a resource pool at its center")
            val cells = footprint(map, position, building.footprint, building.anchor)
            for (cell in cells) {
                if ((!building.requiresResourcePool && map.isFoundationBlocked(cell.first, cell.second,
                        definition.foundationMovementType)) || cell in occupied)
                    fail("Building footprint must respect ground, resource pools and other live buildings")
            }
            return cells
        }

        fun footprint(map: MapDefinition, position: Position, shape: BuildingFootprint,
                      anchor: FootprintAnchor? = null, clipToMap: Boolean = false): Set<Pair<Int, Int>> {
            val geometry = try {
                ConstructionPlacement.footprint(map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight,
                    position, shape, anchor, clipToMap)
            } catch (failure: IllegalArgumentException) { fail(checkNotNull(failure.message)) }
            if (!geometry.withinMap) fail("Building footprint must be entirely within the map")
            return geometry.cells.toSet()
        }

        private fun fail(message: String): Nothing = throw BuildingInitializationException(SimulationError("invalid-spawn", message))
    }
}

/** Only currently authorized building cells. It contains neither truth versions nor ground memory. */
internal class BuildingKnowledge private constructor(val revision: Long, cells: Set<Pair<Int, Int>>) {
    val cells: Set<Pair<Int, Int>> = Collections.unmodifiableSet(LinkedHashSet(cells))

    companion object {
        fun projected(old: BuildingKnowledge?, cells: Set<Pair<Int, Int>>): BuildingKnowledge =
            if (old != null && old.cells == cells) old
            else BuildingKnowledge(if (old == null) 0L else Math.incrementExact(old.revision), cells)
    }
}
