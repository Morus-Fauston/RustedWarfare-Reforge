package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.MapBounds
import com.rustedwarfare.reforge.core.content.MapDefinition
import kotlin.math.floor

/** Planning-only swept-circle clearance; closed contact with a known blocked tile is rejected. */
internal object PlanningSegmentClearance {
    fun allows(map: PlanningTerrain, start: Position, end: Position, radius: Double): Boolean =
        allows(map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight, start, end, radius, map::isBlocked)

    /** Trusted movement check only. This truth input is never used to select a planning route. */
    fun allowsMovement(map: MapDefinition, start: Position, end: Position, radius: Double): Boolean =
        allows(map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight, start, end, radius, map.blocking::isBlocked)

    private inline fun allows(widthTiles: Int, heightTiles: Int, tileWidth: Int, tileHeight: Int,
                             start: Position, end: Position, radius: Double, blocked: (Int, Int) -> Boolean): Boolean {
        // A rectangle is convex: containing both endpoint circles contains the whole sweep.
        val width = widthTiles.toDouble() * tileWidth
        val height = heightTiles.toDouble() * tileHeight
        if (!MapBounds.contains(width, height, start, radius) ||
            !MapBounds.contains(width, height, end, radius)) return false
        val firstColumn = (floor((minOf(start.x, end.x) - radius) / tileWidth) - 1)
            .coerceIn(0.0, widthTiles.toDouble()).toInt()
        val lastColumn = (floor((maxOf(start.x, end.x) + radius) / tileWidth) + 1)
            .coerceIn(-1.0, widthTiles - 1.0).toInt()
        val firstRow = (floor((minOf(start.y, end.y) - radius) / tileHeight) - 1)
            .coerceIn(0.0, heightTiles.toDouble()).toInt()
        val lastRow = (floor((maxOf(start.y, end.y) + radius) / tileHeight) + 1)
            .coerceIn(-1.0, heightTiles - 1.0).toInt()
        for (row in firstRow..lastRow) for (column in firstColumn..lastColumn) {
            if (blocked(column, row) && KnowledgePathImpact.affects(start, listOf(end), end, radius,
                    column, row, tileWidth, tileHeight)) return false
        }
        return true
    }
}
