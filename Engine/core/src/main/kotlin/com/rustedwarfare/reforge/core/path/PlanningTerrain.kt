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
class PlanningTerrain(
    val widthTiles: Int,
    val heightTiles: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    blocked: BooleanArray,
) {
    init {
        validateShape(widthTiles,heightTiles,tileWidth,tileHeight)
        if(blocked.size.toLong()!=widthTiles.toLong()*heightTiles) throw PlanningException("invalid-blocking", "Blocking count must match planning dimensions")
    }
    private val cells=blocked.copyOf()
    private val blockedIndex = BlockedCellIndex(widthTiles, cells)
    internal fun anyBlocked(firstColumn: Int, lastColumn: Int, firstRow: Int, lastRow: Int,
                            predicate: (Int, Int) -> Boolean): Boolean =
        blockedIndex.any(firstColumn, lastColumn, firstRow, lastRow, predicate)
    fun isBlocked(column: Int, row: Int): Boolean {
        if(column !in 0 until widthTiles || row !in 0 until heightTiles) throw PlanningException("out-of-bounds", "Planning cell is outside the terrain")
        return cells[row*widthTiles+column]
    }

    /** Compose authorized dynamic cells into a self-contained value, without retaining a truth query. */
    internal fun withBuildings(buildings: Set<Pair<Int, Int>>): PlanningTerrain {
        if (buildings.isEmpty()) return this
        val combined = cells.copyOf()
        for ((column, row) in buildings) {
            require(column in 0 until widthTiles && row in 0 until heightTiles)
            combined[row * widthTiles + column] = true
        }
        return PlanningTerrain(widthTiles, heightTiles, tileWidth, tileHeight, combined)
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
    else if (type == MovementType.HOVER || type == MovementType.OVER_CLIFF) lastKnownGroundGid?.let { GroundRules.blocked(tileSets, it, type) } ?: false
    else lastKnownBlocked ?: false
