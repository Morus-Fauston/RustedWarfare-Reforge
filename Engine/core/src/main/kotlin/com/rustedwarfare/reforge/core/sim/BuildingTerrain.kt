package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BuildingFootprint
import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.FootprintAnchor
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.foundationMovementType
import java.util.Collections

internal class BuildingInitializationException(val error: SimulationError) : IllegalArgumentException(error.message)

/** Immutable live-building cost, separate from the original ground map. */
internal class BuildingTerrain private constructor(map: MapDefinition, occupied: Set<Pair<Int, Int>>) {
    val cells: Set<Pair<Int, Int>> = Collections.unmodifiableSet(LinkedHashSet(occupied.sortedWith(
        compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })))
    val collisionMap: MapDefinition = MapDefinition(map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight,
        map.tileSets, map.groundGids, BlockingGrid(map.widthTiles, map.heightTiles,
            BooleanArray(map.widthTiles * map.heightTiles) { index ->
                val column = index % map.widthTiles
                val row = index / map.widthTiles
                map.blocking.isBlocked(column, row) || (column to row) in cells
            }), map.waterObstacles + cells)

    companion object {
        fun fromLive(map: MapDefinition, units: Collection<MutableUnit>, old: BuildingTerrain): BuildingTerrain {
            val cells = units.filter { it.alive && it.definition.building != null }.flatMapTo(linkedSetOf()) {
                val building = checkNotNull(it.definition.building)
                footprint(map, it.position, building.footprint, building.anchor)
            }
            return if (cells == old.cells) old else BuildingTerrain(map, cells)
        }

        fun initial(input: Scenario): BuildingTerrain? {
            val buildings = input.units.filter { input.unitDefinitions.getValue(it.typeId).building != null }
            if (buildings.isEmpty() && input.unitDefinitions.values.none { it.builder != null }) return null
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
            return BuildingTerrain(input.map, cells)
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
                if ((!building.requiresResourcePool && (map.blockingFor(definition.foundationMovementType).isBlocked(cell.first, cell.second) ||
                        map.isResourcePool(cell.first, cell.second))) || cell in occupied)
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
