package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.sim.Position

/** Pure planning-path association only; not a movement collision policy or truth query. */
internal object KnowledgePathImpact {
    fun affectsCenter(position: Position, remaining: List<Position>, target: Position,
                      column: Int, row: Int, tileWidth: Int, tileHeight: Int): Boolean {
        var from = position
        for (to in remaining.ifEmpty { listOf(target) }) {
            if (CenterLineCells.enters(from, to, column, row, tileWidth, tileHeight)) return true
            from = to
        }
        return false
    }

}
