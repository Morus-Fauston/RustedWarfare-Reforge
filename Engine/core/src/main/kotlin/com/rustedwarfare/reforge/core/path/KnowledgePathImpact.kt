package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.sim.Position

/**
 * Pure planning-path impact check over [PlanningSegmentClearance] (ENG-P017-004).
 *
 * Accounts for the unit's terrain passability [clearanceRadius] so wide hulls grazing a newly
 * discovered obstacle along their swept margin trigger immediate replanning instead of colliding.
 */
internal object KnowledgePathImpact {
    fun affectsSegment(
        position: Position,
        remaining: List<Position>,
        target: Position,
        clearanceRadius: Double,
        column: Int,
        row: Int,
        tileWidth: Int,
        tileHeight: Int,
        mapWidth: Double = Double.POSITIVE_INFINITY,
        mapHeight: Double = Double.POSITIVE_INFINITY,
    ): Boolean {
        var from = position
        if (remaining.isEmpty()) {
            return PlanningSegmentClearance.entersCellWithClearance(
                from, target, clearanceRadius, column, row, tileWidth, tileHeight, mapWidth, mapHeight,
            )
        }
        for (to in remaining) {
            if (PlanningSegmentClearance.entersCellWithClearance(
                    from, to, clearanceRadius, column, row, tileWidth, tileHeight, mapWidth, mapHeight,
                )
            ) return true
            from = to
        }
        return false
    }

    fun affectsCenter(
        position: Position,
        remaining: List<Position>,
        target: Position,
        column: Int,
        row: Int,
        tileWidth: Int,
        tileHeight: Int,
    ): Boolean = affectsSegment(position, remaining, target, 0.0, column, row, tileWidth, tileHeight)
}
