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
) {
    private constructor(width: Int, height: Int, tileWidth: Int, tileHeight: Int, cells: BooleanArray, validated: Unit) :
        this(width, height, tileWidth, tileHeight, cells, BlockedCellIndex(width, cells), 1)

    constructor(widthTiles: Int, heightTiles: Int, tileWidth: Int, tileHeight: Int, blocked: BooleanArray) :
        this(widthTiles, heightTiles, tileWidth, tileHeight, blocked.run {
            validateShape(widthTiles, heightTiles, tileWidth, tileHeight)
            if (size.toLong() != widthTiles.toLong() * heightTiles)
                throw PlanningException("invalid-blocking", "Blocking count must match planning dimensions")
            copyOf()
        }, Unit)

    /** A finer graph over the same immutable authorized terrain. No second mask or index is built. */
    internal fun subdivided(factor: Int): PlanningTerrain {
        require(factor > 0 && tileWidth % factor == 0 && tileHeight % factor == 0)
        require(widthTiles.toLong() * heightTiles * factor <= Int.MAX_VALUE / factor)
        return PlanningTerrain(widthTiles * factor, heightTiles * factor, tileWidth / factor, tileHeight / factor,
            cells, blockedIndex, subdivision * factor)
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
        for ((column, row) in buildings) {
            require(column in 0 until widthTiles && row in 0 until heightTiles)
            combined[row * widthTiles + column] = true
        }
        return PlanningTerrain(widthTiles, heightTiles, tileWidth, tileHeight, combined)
    }

    internal fun withUpdatedKnowledge(previous: KnowledgeSnapshot, current: KnowledgeSnapshot,
                                      movementType: MovementType, tileSets: List<TileSetDefinition>): PlanningTerrain {
        var updated: BooleanArray? = null
        current.forEachTerrainChangeFrom(previous) { index, knowledge ->
            val blocked = knowledge.movementBlocked(movementType, tileSets)
            if (blocked != isBlocked(index % widthTiles, index / widthTiles)) {
                val output = updated ?: cellCopy().also { updated = it }
                output[index] = blocked
            }
        }
        return updated?.let { PlanningTerrain(widthTiles, heightTiles, tileWidth, tileHeight, it) } ?: this
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
