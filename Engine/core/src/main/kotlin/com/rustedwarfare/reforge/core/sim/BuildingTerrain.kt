package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BuildingFootprint
import com.rustedwarfare.reforge.core.content.FootprintAnchor
import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
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
            }))

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
            if (buildings.isEmpty()) return null
            val cells = linkedSetOf<Pair<Int, Int>>()
            for (unit in buildings) {
                val building = checkNotNull(input.unitDefinitions.getValue(unit.typeId).building)
                for (cell in footprint(input.map, unit.position, building.footprint, building.anchor)) {
                    if (input.map.blocking.isBlocked(cell.first, cell.second) || !cells.add(cell))
                        fail("Building footprint must be clear of hard terrain and other live buildings")
                }
            }
            for (unit in input.units) {
                if (input.unitDefinitions.getValue(unit.typeId).building != null) continue
                val cell = MapBounds.cell(unit.position.x, input.map.tileWidth, input.map.widthTiles) to
                    MapBounds.cell(unit.position.y, input.map.tileHeight, input.map.heightTiles)
                if (cell in cells) fail("Initial mobile unit center must be outside live building cost")
            }
            return BuildingTerrain(input.map, cells)
        }

        fun footprint(map: MapDefinition, position: Position, shape: BuildingFootprint, anchor: FootprintAnchor? = null): Set<Pair<Int, Int>> {
            fun custom(value: Double, offset: Float, tile: Int): Long {
                // Preserve each binary32 operation and toward-zero conversion before Long shape arithmetic.
                val projected = ((value.toFloat() - offset) + 1f) * (1f / tile.toFloat())
                if (!projected.isFinite() || projected.toDouble() < Int.MIN_VALUE.toDouble() || projected.toDouble() > Int.MAX_VALUE.toDouble())
                    fail("Building footprint anchor must be a representable integer")
                return projected.toInt().toLong()
            }
            val column = if (anchor == null) MapBounds.cell(position.x, map.tileWidth, map.widthTiles).toLong()
                else custom(position.x, anchor.offsetX, map.tileWidth)
            val row = if (anchor == null) MapBounds.cell(position.y, map.tileHeight, map.heightTiles).toLong()
                else custom(position.y, anchor.offsetY, map.tileHeight)
            val left = column + shape.left.toLong()
            val right = column + shape.right.toLong()
            val top = row + shape.top.toLong()
            val bottom = row + shape.bottom.toLong()
            if (left < 0L || top < 0L || right >= map.widthTiles.toLong() || bottom >= map.heightTiles.toLong() ||
                left > right || top > bottom) fail("Building footprint must be entirely within the map")
            // Only bounded spans may be multiplied, iterated, or converted back to Int.
            require((right - left + 1L) * (bottom - top + 1L) <= map.widthTiles.toLong() * map.heightTiles)
            val cells = linkedSetOf<Pair<Int, Int>>()
            for (y in top..bottom) for (x in left..right) cells += x.toInt() to y.toInt()
            return cells
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
