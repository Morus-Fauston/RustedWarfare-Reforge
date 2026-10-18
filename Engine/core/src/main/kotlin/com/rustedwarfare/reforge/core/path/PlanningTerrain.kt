package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.knowledge.KnowledgeSnapshot

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
    fun isBlocked(column: Int, row: Int): Boolean {
        if(column !in 0 until widthTiles || row !in 0 until heightTiles) throw PlanningException("out-of-bounds", "Planning cell is outside the terrain")
        return cells[row*widthTiles+column]
    }

    companion object {
        private fun validateShape(width: Int, height: Int, tileWidth: Int, tileHeight: Int) {
            if(width<=0 || height<=0 || width.toLong()*height>Int.MAX_VALUE) throw PlanningException("invalid-dimensions", "Positive dimensions with product within Int are required")
            if(tileWidth<=0 || tileHeight<=0) throw PlanningException("invalid-geometry", "Planning tile dimensions must be positive")
        }
        fun fromKnowledge(snapshot: KnowledgeSnapshot, tileWidth: Int, tileHeight: Int): PlanningTerrain {
            validateShape(snapshot.width,snapshot.height,tileWidth,tileHeight)
            return PlanningTerrain(snapshot.width,snapshot.height,tileWidth,tileHeight,
                BooleanArray(snapshot.width*snapshot.height) { snapshot.planningBlocked(it%snapshot.width,it/snapshot.width) })
        }

        /** Trusted no-fog host adapter. The result retains no reference to map or blocking grid. */
        fun fromNoFog(map: MapDefinition): PlanningTerrain {
            validateShape(map.widthTiles,map.heightTiles,map.tileWidth,map.tileHeight)
            if(map.blocking.widthTiles!=map.widthTiles || map.blocking.heightTiles!=map.heightTiles) throw PlanningException("invalid-blocking", "Truth blocking dimensions do not match map dimensions")
            return PlanningTerrain(map.widthTiles,map.heightTiles,map.tileWidth,map.tileHeight,
                BooleanArray(map.widthTiles*map.heightTiles) { map.blocking.isBlocked(it%map.widthTiles,it/map.widthTiles) })
        }
    }
}
