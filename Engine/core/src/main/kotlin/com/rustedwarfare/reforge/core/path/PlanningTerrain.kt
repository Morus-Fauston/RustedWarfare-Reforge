package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.TileSetDefinition
import com.rustedwarfare.reforge.core.content.waterBlocked
import com.rustedwarfare.reforge.core.content.GroundRules
import com.rustedwarfare.reforge.core.knowledge.KnowledgeSnapshot
import com.rustedwarfare.reforge.core.knowledge.CellKnowledge

class PlanningException(val code: String, message: String) : IllegalArgumentException(message)

/** A self-contained planning value, not an authorization interface or a live truth query. */
class PlanningTerrain private constructor(
    val widthTiles: Int,
    val heightTiles: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    private val cells: BooleanArray,
    private val blockedIndex: BlockedCellIndex,
    private val subdivision: Int,
    private val prebuiltClearance: TerrainClearanceField? = null,
) {
    private val clearanceLock = Any()
    @Volatile
    private var lazyClearance: TerrainClearanceField? = prebuiltClearance

    private constructor(
        width: Int,
        height: Int,
        tileWidth: Int,
        tileHeight: Int,
        cells: BooleanArray,
        validated: Unit,
        prebuiltClearance: TerrainClearanceField? = null,
    ) : this(width, height, tileWidth, tileHeight, cells, BlockedCellIndex(width, cells), 1, prebuiltClearance)

    constructor(widthTiles: Int, heightTiles: Int, tileWidth: Int, tileHeight: Int, blocked: BooleanArray) :
        this(widthTiles, heightTiles, tileWidth, tileHeight, blocked.run {
            validateShape(widthTiles, heightTiles, tileWidth, tileHeight)
            if (size.toLong() != widthTiles.toLong() * heightTiles)
                throw PlanningException("invalid-blocking", "Blocking count must match planning dimensions")
            copyOf()
        }, Unit)

    /**
     * Returns the immutable clearance distance field & 4-connected component topology over the
     * underlying base grid (`subdivision == 1`). Built lazily once and incrementally updated on
     * [withBuildings] / [withUpdatedKnowledge].
     */
    internal fun clearanceField(): TerrainClearanceField {
        lazyClearance?.let { return it }
        synchronized(clearanceLock) {
            lazyClearance?.let { return it }
            val baseW = widthTiles / subdivision
            val baseH = heightTiles / subdivision
            val built = TerrainClearanceField.build(baseW, baseH, cells)
            lazyClearance = built
            return built
        }
    }

    /** O(1) Chebyshev obstacle clearance rings for cell `(column, row)`. */
    internal fun clearanceRings(column: Int, row: Int): Int {
        if (column !in 0 until widthTiles || row !in 0 until heightTiles)
            throw PlanningException("out-of-bounds", "Planning cell is outside the terrain")
        val field = clearanceField()
        return if (subdivision == 1) field.clearanceRings(column, row)
        else field.clearanceRings(column / subdivision, row / subdivision) * subdivision
    }

    /**
     * O(1) center-clearance check matching [CenterClearance] / [PlanningCenterTerrain] semantics
     * without scanning the full map.
     */
    internal fun allowsCenter(column: Int, row: Int, radius: Double): Boolean {
        if (!radius.isFinite() || radius < 0.0) return false
        if (column !in 0 until widthTiles || row !in 0 until heightTiles) return false
        val blocked = cells[(row / subdivision) * (widthTiles / subdivision) + column / subdivision]
        if (blocked) return false
        val cx = (column + 0.5) * tileWidth
        val cy = (row + 0.5) * tileHeight
        val mapW = widthTiles.toDouble() * tileWidth
        val mapH = heightTiles.toDouble() * tileHeight
        return cx >= radius && cy >= radius && cx <= mapW - radius && cy <= mapH - radius
    }

    /**
     * O(1) 4-connected passability reachability test between two grid cells on the authorized
     * planning graph. Returns false immediately when the goal lies inside an enclosed island or
     * disconnected pocket, letting callers bypass full-map search with 0 node expansions.
     */
    internal fun isCellConnected(
        startCol: Int,
        startRow: Int,
        goalCol: Int,
        goalRow: Int,
    ): Boolean {
        if (startCol !in 0 until widthTiles || startRow !in 0 until heightTiles ||
            goalCol !in 0 until widthTiles || goalRow !in 0 until heightTiles) return false
        val field = clearanceField()
        val sc = field.componentId(startCol / subdivision, startRow / subdivision)
        return sc != 0 && sc == field.componentId(goalCol / subdivision, goalRow / subdivision)
    }

    /** O(1) 4-connected component ID for cell `(column, row)` (0 if blocked). */
    internal fun componentId(column: Int, row: Int): Int {
        if (column !in 0 until widthTiles || row !in 0 until heightTiles) return 0
        return clearanceField().componentId(column / subdivision, row / subdivision)
    }

    /** A finer graph over the same immutable authorized terrain. No second mask or index is built. */
    internal fun subdivided(factor: Int): PlanningTerrain {
        require(factor > 0 && tileWidth % factor == 0 && tileHeight % factor == 0)
        require(widthTiles.toLong() * heightTiles * factor <= Int.MAX_VALUE / factor)
        return PlanningTerrain(widthTiles * factor, heightTiles * factor, tileWidth / factor, tileHeight / factor,
            cells, blockedIndex, subdivision * factor, lazyClearance)
    }

    internal fun anyBlocked(firstColumn: Int, lastColumn: Int, firstRow: Int, lastRow: Int,
                            predicate: (Int, Int) -> Boolean): Boolean {
        if (subdivision == 1) return blockedIndex.any(firstColumn, lastColumn, firstRow, lastRow, predicate)
        if (firstColumn > lastColumn || firstRow > lastRow) return false
        return blockedIndex.any(firstColumn / subdivision, lastColumn / subdivision,
            firstRow / subdivision, lastRow / subdivision) { column, row ->
            val left = column * subdivision
            val top = row * subdivision
            for (y in maxOf(firstRow, top)..minOf(lastRow, top + subdivision - 1))
                for (x in maxOf(firstColumn, left)..minOf(lastColumn, left + subdivision - 1))
                    if (predicate(x, y)) return@any true
            false
        }
    }
    fun isBlocked(column: Int, row: Int): Boolean {
        if(column !in 0 until widthTiles || row !in 0 until heightTiles) throw PlanningException("out-of-bounds", "Planning cell is outside the terrain")
        return cells[(row / subdivision) * (widthTiles / subdivision) + column / subdivision]
    }

    private fun cellCopy() = if (subdivision == 1) cells.copyOf() else
        BooleanArray(widthTiles * heightTiles) { isBlocked(it % widthTiles, it / widthTiles) }

    /** Compose authorized dynamic cells into a self-contained value, without retaining a truth query. */
    internal fun withBuildings(buildings: Set<Pair<Int, Int>>): PlanningTerrain {
        if (buildings.isEmpty()) return this
        val combined = cellCopy()
        val changed = IntArray(buildings.size)
        var changedCount = 0
        for ((column, row) in buildings) {
            require(column in 0 until widthTiles && row in 0 until heightTiles)
            val idx = row * widthTiles + column
            if (!combined[idx]) {
                combined[idx] = true
                changed[changedCount++] = idx
            }
        }
        if (changedCount == 0) return this
        val updatedClearance = if (subdivision == 1) {
            lazyClearance?.withChangedCells(combined, changed.copyOf(changedCount))
        } else null
        return PlanningTerrain(widthTiles, heightTiles, tileWidth, tileHeight, combined, Unit, updatedClearance)
    }

    internal fun withUpdatedKnowledge(previous: KnowledgeSnapshot, current: KnowledgeSnapshot,
                                      movementType: MovementType, tileSets: List<TileSetDefinition>): PlanningTerrain {
        var updated: BooleanArray? = null
        var changed = IntArray(16)
        var changedCount = 0
        current.forEachTerrainChangeFrom(previous) { index, knowledge ->
            val blocked = knowledge.movementBlocked(movementType, tileSets)
            if (blocked != isBlocked(index % widthTiles, index / widthTiles)) {
                val output = updated ?: cellCopy().also { updated = it }
                output[index] = blocked
                if (changedCount == changed.size) changed = changed.copyOf(changed.size shl 1)
                changed[changedCount++] = index
            }
        }
        val nextCells = updated ?: return this
        val updatedClearance = if (subdivision == 1) {
            lazyClearance?.withChangedCells(nextCells, changed.copyOf(changedCount))
        } else null
        return PlanningTerrain(widthTiles, heightTiles, tileWidth, tileHeight, nextCells, Unit, updatedClearance)
    }

    companion object {
        private fun validateShape(width: Int, height: Int, tileWidth: Int, tileHeight: Int) {
            if(width<=0 || height<=0 || width.toLong()*height>Int.MAX_VALUE) throw PlanningException("invalid-dimensions", "Positive dimensions with product within Int are required")
            if(tileWidth<=0 || tileHeight<=0) throw PlanningException("invalid-geometry", "Planning tile dimensions must be positive")
        }
        fun fromKnowledge(snapshot: KnowledgeSnapshot, tileWidth: Int, tileHeight: Int,
                          movementType: MovementType = MovementType.LAND,
                          tileSets: List<TileSetDefinition> = emptyList()): PlanningTerrain {
            validateShape(snapshot.width,snapshot.height,tileWidth,tileHeight)
            return PlanningTerrain(snapshot.width,snapshot.height,tileWidth,tileHeight,
                BooleanArray(snapshot.width*snapshot.height) {
                    snapshot.movementBlocked(it%snapshot.width, it/snapshot.width, movementType, tileSets)
                })
        }

        /** Trusted no-fog host adapter. The result retains no reference to map or blocking grid. */
        fun fromNoFog(map: MapDefinition, movementType: MovementType = MovementType.LAND): PlanningTerrain {
            validateShape(map.widthTiles,map.heightTiles,map.tileWidth,map.tileHeight)
            val blocking = map.blockingFor(movementType)
            if(blocking.widthTiles!=map.widthTiles || blocking.heightTiles!=map.heightTiles) throw PlanningException("invalid-blocking", "Truth blocking dimensions do not match map dimensions")
            return PlanningTerrain(map.widthTiles,map.heightTiles,map.tileWidth,map.tileHeight,
                BooleanArray(map.widthTiles*map.heightTiles) { blocking.isBlocked(it%map.widthTiles,it/map.widthTiles) })
        }
    }
}

/** Authorized memory plus public tile semantics only; absent GIDs retain unknown-cell exploration. */
internal fun KnowledgeSnapshot.movementBlocked(column: Int, row: Int, type: MovementType,
                                               tileSets: List<TileSetDefinition>): Boolean =
    cell(column, row).movementBlocked(type, tileSets)

internal fun CellKnowledge.movementBlocked(type: MovementType, tileSets: List<TileSetDefinition>): Boolean =
    if (type == MovementType.WATER) lastKnownGroundGid?.let { tileSets.waterBlocked(it) } ?: false
    else if (type in setOf(MovementType.HOVER, MovementType.OVER_CLIFF, MovementType.OVER_CLIFF_WATER))
        lastKnownGroundGid?.let { GroundRules.blocked(tileSets, it, type) } ?: false
    else lastKnownBlocked ?: false
