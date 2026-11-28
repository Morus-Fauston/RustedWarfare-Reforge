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
    val liveEntries: Array<LiveBuildingEntry> = EMPTY_LIVE_ENTRIES,
) : DynamicBuildingGrid {
    internal class LiveBuildingEntry(
        val objectId: Long,
        val xBits: Long,
        val yBits: Long,
        val footprintRef: BuildingFootprint,
        val anchorRef: FootprintAnchor?,
        val anchorCol: Int,
        val anchorRow: Int,
        val footprint: Set<Pair<Int, Int>>,
    )
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
    private val footprintCache = HashMap<FootprintCacheKey, Set<Pair<Int, Int>>>(16)
    private data class FootprintCacheKey(val xBits: Long, val yBits: Long, val shape: BuildingFootprint, val anchor: FootprintAnchor?)

    override fun footprintCells(
        map: MapDefinition,
        position: Position,
        shape: BuildingFootprint,
        anchor: FootprintAnchor?,
    ): Set<Pair<Int, Int>> {
        val key = FootprintCacheKey(position.x.toRawBits(), position.y.toRawBits(), shape, anchor)
        return footprintCache.getOrPut(key) { footprint(map, position, shape, anchor) }
    }
    override val collisionMap: MapDefinition = map.withDynamicObstacles(cells)

    companion object {
        private const val DEFAULT_MAX_HALF_EXTENT_TILES = 6
        private val EMPTY_LIVE_ENTRIES = emptyArray<LiveBuildingEntry>()

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
            // Zero-allocation fast path: if every live building's (objectId, position, footprint, anchor)
            // matches `old.liveEntries` in iteration order, reuse `old` verbatim without touching sets or maps.
            if (old != null) {
                val prev = old.liveEntries
                var idx = 0
                var same = true
                for (u in units) {
                    if (!u.alive) continue
                    val b = u.definition.building ?: continue
                    val oid = u.objectId ?: -1L
                    if (idx >= prev.size) {
                        same = false
                        break
                    }
                    val e = prev[idx]
                    if (e.objectId != oid ||
                        e.xBits != u.position.x.toRawBits() ||
                        e.yBits != u.position.y.toRawBits() ||
                        e.footprintRef != b.footprint ||
                        e.anchorRef != b.anchor
                    ) {
                        same = false
                        break
                    }
                    idx++
                }
                if (same && idx == prev.size && (idx > 0 || old.cells.isEmpty())) {
                    return old
                }
            }
            val liveBuildings = ArrayList<MutableUnit>()
            for (u in units) {
                if (u.alive && u.definition.building != null) liveBuildings.add(u)
            }
            val cells = LinkedHashSet<Pair<Int, Int>>(liveBuildings.size * 9 + 4)
            var maxExtent = DEFAULT_MAX_HALF_EXTENT_TILES
            val entries = Array(liveBuildings.size) { i ->
                val u = liveBuildings[i]
                val building = checkNotNull(u.definition.building)
                val fp = building.footprint
                val extent = maxOf(kotlin.math.abs(fp.left), kotlin.math.abs(fp.right),
                    kotlin.math.abs(fp.top), kotlin.math.abs(fp.bottom)) + 2
                if (extent > maxExtent) maxExtent = extent
                val fpCells = old?.footprintCells(map, u.position, fp, building.anchor)
                    ?: footprint(map, u.position, fp, building.anchor)
                cells.addAll(fpCells)
                LiveBuildingEntry(
                    objectId = u.objectId ?: -1L,
                    xBits = u.position.x.toRawBits(),
                    yBits = u.position.y.toRawBits(),
                    footprintRef = fp,
                    anchorRef = building.anchor,
                    anchorCol = MapBounds.cell(u.position.x, map.tileWidth, map.widthTiles),
                    anchorRow = MapBounds.cell(u.position.y, map.tileHeight, map.heightTiles),
                    footprint = fpCells,
                )
            }
            return BuildingTerrain(map, cells, maxExtent, entries)
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
    private val packedSorted: LongArray = LongArray(this.cells.size).also { arr ->
        var idx = 0
        for ((c, r) in this.cells) arr[idx++] = packCell(c, r)
        java.util.Arrays.sort(arr)
    }

    /** Zero-allocation O(log K) building cell membership check (eliminates boxed `Pair<Int, Int>` per query). */
    fun containsCell(column: Int, row: Int): Boolean {
        if (packedSorted.isEmpty()) return false
        return java.util.Arrays.binarySearch(packedSorted, packCell(column, row)) >= 0
    }

    companion object {
        private fun packCell(column: Int, row: Int): Long =
            (row.toLong() shl 32) or (column.toLong() and 0xFFFFFFFFL)

        fun projected(old: BuildingKnowledge?, cells: Set<Pair<Int, Int>>): BuildingKnowledge =
            if (old != null && old.cells == cells) old
            else BuildingKnowledge(if (old == null) 0L else Math.incrementExact(old.revision), cells)
    }
}
