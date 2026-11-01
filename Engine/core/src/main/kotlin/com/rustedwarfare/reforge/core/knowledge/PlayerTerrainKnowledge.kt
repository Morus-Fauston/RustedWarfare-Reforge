package com.rustedwarfare.reforge.core.knowledge

import com.rustedwarfare.reforge.core.sim.WorldHandle
import java.util.Collections

class KnowledgeException(val code: String, message: String) : IllegalArgumentException(message)

data class VisibleTerrainCell(val column: Int, val row: Int, val blocked: Boolean, val groundGid: Int? = null)
data class CellKnowledge(val visible: Boolean, val lastKnownBlocked: Boolean?, val lastKnownGroundGid: Int? = null)

class KnowledgeSnapshot private constructor(
    val worldId: String,
    val playerId: String,
    val width: Int,
    val height: Int,
    val revision: Long,
    ownedCells: KnowledgeCells,
) {
    internal constructor(worldId: String, playerId: String, width: Int, height: Int, revision: Long,
                         cells: List<CellKnowledge>) : this(worldId, playerId, width, height, revision, KnowledgeCells.copied(cells))
    val cells: List<CellKnowledge> = ownedCells.view
    fun cell(column: Int, row: Int): CellKnowledge {
        if(column !in 0 until width || row !in 0 until height) throw KnowledgeException("out-of-bounds", "Knowledge cell is outside the map")
        return cells[row * width + column]
    }
    fun planningBlocked(column: Int, row: Int): Boolean = cell(column,row).lastKnownBlocked ?: false
    internal companion object {
        fun owned(worldId: String, playerId: String, width: Int, height: Int, revision: Long,
                  cells: KnowledgeCells) = KnowledgeSnapshot(worldId, playerId, width, height, revision, cells)
    }
}

/** Trusted host-only state: one store per player, no real-map reference or geometry producer. */
class PlayerTerrainKnowledge private constructor(
    private val world: WorldHandle,
    val playerId: String,
    val width: Int,
    val height: Int,
    val rules: KnowledgeRules,
    initialCells: KnowledgeCells,
    initialRevision: Long = 0L,
    initialVisible: Set<Int> = emptySet(),
) {
    private var cells = initialCells
    private var visible = initialVisible
    private var revision=initialRevision
    private var cachedSnapshot: KnowledgeSnapshot? = null
    private var closed=false

    companion object {
        fun create(world: WorldHandle, playerId: String, width: Int, height: Int, rules: KnowledgeRules): PlayerTerrainKnowledge {
            if(width<=0 || height<=0 || width.toLong()*height>Int.MAX_VALUE) throw KnowledgeException("invalid-dimensions", "Positive dimensions with product within Int are required")
            if(playerId.isBlank()) throw KnowledgeException("invalid-player", "Player ID must not be blank")
            if(rules.retention==TerrainRetention.HARDCORE_FORGET && rules.pathSource!=PathInformationSource.PLAYER_KNOWLEDGE) throw KnowledgeException("incompatible-rules", "Hardcore forgetting requires player knowledge path information")
            return PlayerTerrainKnowledge(world,playerId,width,height,rules,
                KnowledgeCells.unknown(width * height))
        }
    }

    fun accept(world: WorldHandle, visibleTerrain: List<VisibleTerrainCell>) {
        checkAccess(world)
        update(visibleTerrain)
    }

    private fun update(visibleTerrain: List<VisibleTerrainCell>): List<KnowledgeCellChange> {
        val projection=visibleTerrain.toList()
        val seen=HashSet<Int>()
        for(cell in projection) {
            if(cell.column !in 0 until width || cell.row !in 0 until height) throw KnowledgeException("out-of-bounds", "Visible terrain cell is outside the map")
            if(!seen.add(cell.row*width+cell.column)) throw KnowledgeException("duplicate-cell", "Visible terrain projection contains a repeated cell")
        }
        val changes = ArrayList<KnowledgeCellChange>()
        for (index in visible) if (index !in seen) {
            val before = cells[index]
            val after = if (rules.retention == TerrainRetention.MEMORY)
                CellKnowledge(false, before.lastKnownBlocked, before.lastKnownGroundGid) else KnowledgeCells.UNKNOWN
            changes.add(KnowledgeCellChange(index, before, after))
        }
        for (cell in projection) {
            val index = cell.row * width + cell.column
            val before = cells[index]
            if (!before.visible || before.lastKnownBlocked != cell.blocked || before.lastKnownGroundGid != cell.groundGid)
                changes.add(KnowledgeCellChange(index, before, CellKnowledge(true, cell.blocked, cell.groundGid)))
        }
        val next = cells.replacing(changes)
        cells = next
        visible = seen // owned by this update; later updates only read this set
        revision++
        cachedSnapshot=null
        return Collections.unmodifiableList(changes)
    }

    fun snapshot(world: WorldHandle): KnowledgeSnapshot {
        checkAccess(world)
        return cachedSnapshot ?: KnowledgeSnapshot.owned(this.world.id,playerId,width,height,revision,cells)
            .also { cachedSnapshot=it }
    }

    internal fun projected(world: WorldHandle, visibleTerrain: List<VisibleTerrainCell>): KnowledgeProjection {
        checkAccess(world)
        // accept replaces the list atomically; neither this store nor the projection mutates it.
        val next = PlayerTerrainKnowledge(this.world, playerId, width, height, rules, cells, revision, visible)
        val changes = next.update(visibleTerrain)
        return KnowledgeProjection(next, changes)
    }

    fun close() {
        if(closed) return
        closed=true
        cells=KnowledgeCells.unknown(0)
        visible=emptySet()
        cachedSnapshot=null
    }

    private fun checkAccess(world: WorldHandle) {
        if(closed) throw KnowledgeException("closed", "Knowledge store is closed")
        if(world !== this.world) throw KnowledgeException("stale-world", "Knowledge access belongs to a different world capability")
    }
}
