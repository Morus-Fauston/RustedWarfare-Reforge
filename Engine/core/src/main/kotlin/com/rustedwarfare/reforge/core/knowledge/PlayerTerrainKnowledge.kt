package com.rustedwarfare.reforge.core.knowledge

import com.rustedwarfare.reforge.core.sim.WorldHandle
import java.util.Collections

class KnowledgeException(val code: String, message: String) : IllegalArgumentException(message)

data class VisibleTerrainCell(val column: Int, val row: Int, val blocked: Boolean, val groundGid: Int? = null)
data class CellKnowledge(val visible: Boolean, val lastKnownBlocked: Boolean?, val lastKnownGroundGid: Int? = null)

class KnowledgeSnapshot internal constructor(
    val worldId: String,
    val playerId: String,
    val width: Int,
    val height: Int,
    val revision: Long,
    cells: List<CellKnowledge>,
) {
    val cells: List<CellKnowledge> = Collections.unmodifiableList(ArrayList(cells))
    fun cell(column: Int, row: Int): CellKnowledge {
        if(column !in 0 until width || row !in 0 until height) throw KnowledgeException("out-of-bounds", "Knowledge cell is outside the map")
        return cells[row * width + column]
    }
    fun planningBlocked(column: Int, row: Int): Boolean = cell(column,row).lastKnownBlocked ?: false
}

/** Trusted host-only state: one store per player, no real-map reference or geometry producer. */
class PlayerTerrainKnowledge private constructor(
    private val world: WorldHandle,
    val playerId: String,
    val width: Int,
    val height: Int,
    val rules: KnowledgeRules,
) {
    private var cells: List<CellKnowledge> = List(width * height) { CellKnowledge(false,null) }
    private var revision=0L
    private var closed=false

    companion object {
        fun create(world: WorldHandle, playerId: String, width: Int, height: Int, rules: KnowledgeRules): PlayerTerrainKnowledge {
            if(width<=0 || height<=0 || width.toLong()*height>Int.MAX_VALUE) throw KnowledgeException("invalid-dimensions", "Positive dimensions with product within Int are required")
            if(playerId.isBlank()) throw KnowledgeException("invalid-player", "Player ID must not be blank")
            if(rules.retention==TerrainRetention.HARDCORE_FORGET && rules.pathSource!=PathInformationSource.PLAYER_KNOWLEDGE) throw KnowledgeException("incompatible-rules", "Hardcore forgetting requires player knowledge path information")
            return PlayerTerrainKnowledge(world,playerId,width,height,rules)
        }
    }

    fun accept(world: WorldHandle, visibleTerrain: List<VisibleTerrainCell>) {
        checkAccess(world)
        val projection=visibleTerrain.toList()
        val seen=HashSet<Int>()
        for(cell in projection) {
            if(cell.column !in 0 until width || cell.row !in 0 until height) throw KnowledgeException("out-of-bounds", "Visible terrain cell is outside the map")
            if(!seen.add(cell.row*width+cell.column)) throw KnowledgeException("duplicate-cell", "Visible terrain projection contains a repeated cell")
        }
        val next=cells.map { CellKnowledge(false,
            if(rules.retention==TerrainRetention.MEMORY) it.lastKnownBlocked else null,
            if(rules.retention==TerrainRetention.MEMORY) it.lastKnownGroundGid else null) }.toMutableList()
        for(cell in projection) next[cell.row*width+cell.column]=CellKnowledge(true,cell.blocked,cell.groundGid)
        cells=next
        revision++
    }

    fun snapshot(world: WorldHandle): KnowledgeSnapshot {
        checkAccess(world)
        return KnowledgeSnapshot(this.world.id,playerId,width,height,revision,cells)
    }

    internal fun projected(world: WorldHandle, visibleTerrain: List<VisibleTerrainCell>): PlayerTerrainKnowledge {
        checkAccess(world)
        val next = PlayerTerrainKnowledge(this.world, playerId, width, height, rules)
        next.cells = cells
        next.revision = revision
        next.accept(world, visibleTerrain)
        return next
    }

    fun close() {
        if(closed) return
        closed=true
        cells=emptyList()
    }

    private fun checkAccess(world: WorldHandle) {
        if(closed) throw KnowledgeException("closed", "Knowledge store is closed")
        if(world !== this.world) throw KnowledgeException("stale-world", "Knowledge access belongs to a different world capability")
    }
}
