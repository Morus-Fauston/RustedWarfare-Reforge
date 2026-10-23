package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition

/** Static LAND center/cell correction. Map outer-circle handling belongs to the caller. */
internal object TerrainMotion {
    fun resolve(map: MapDefinition, start: Position, candidate: Position): Position {
        val a = Position(start.x / map.tileWidth, start.y / map.tileHeight)
        val b = Position(candidate.x / map.tileWidth, candidate.y / map.tileHeight)
        // D-083 retains the map's closed outer edge for zero-radius units.
        val oldX = MapBounds.cell(start.x, map.tileWidth, map.widthTiles)
        val oldY = MapBounds.cell(start.y, map.tileHeight, map.heightTiles)
        val newX = MapBounds.cell(candidate.x, map.tileWidth, map.widthTiles)
        val newY = MapBounds.cell(candidate.y, map.tileHeight, map.heightTiles)
        if (kotlin.math.abs(newX.toLong() - oldX) > 1L || kotlin.math.abs(newY.toLong() - oldY) > 1L) {
            val line = ExactCenterLineCells(start, candidate)
            val firstX = (minOf(oldX, newX).toLong() - 1L).coerceAtLeast(0L).toInt()
            val lastX = (maxOf(oldX, newX).toLong() + 1L).coerceAtMost(map.widthTiles.toLong() - 1L).toInt()
            val firstY = (minOf(oldY, newY).toLong() - 1L).coerceAtLeast(0L).toInt()
            val lastY = (maxOf(oldY, newY).toLong() + 1L).coerceAtMost(map.heightTiles.toLong() - 1L).toInt()
            for (row in firstY..lastY) for (column in firstX..lastX) {
                if (map.blocking.isBlocked(column, row) && line.enters(column, row, map.tileWidth, map.tileHeight,
                        column == map.widthTiles - 1, row == map.heightTiles - 1)) return start
            }
            return candidate
        }
        fun blocked(x: Int, y: Int): Boolean = x !in 0 until map.widthTiles || y !in 0 until map.heightTiles || map.blocking.isBlocked(x, y)
        if ((oldX == newX && oldY == newY) || !blocked(newX, newY)) return candidate
        var corrected: Position? = null
        if (oldX != newX && oldY != newY) {
            val sideA = blocked(oldX, newY)
            val sideB = blocked(newX, oldY)
            if (sideA && sideB) return start
            if (sideA) corrected = edgeCorrection(a, b, oldX, newY, ::blocked)
            if (corrected == null && sideB) corrected = edgeCorrection(a, b, newX, oldY, ::blocked)
        }
        if (corrected == null) corrected = edgeCorrection(a, b, newX, newY, ::blocked)
        // Both terrain queries use this core's sole static grid. There is no
        // separate mask layer whose exclusion could permit an escape override.
        return corrected?.let { Position(it.x * map.tileWidth, it.y * map.tileHeight) } ?: start
    }

    private fun edgeCorrection(a: Position, b: Position, x: Int, y: Int, blocked: (Int, Int) -> Boolean): Position? {
        val left = x.toDouble(); val top = y.toDouble()
        val right = left + 1.0; val bottom = top + 1.0
        var edge = -1
        if (a.y < b.y) {
            if (!blocked(x, y - 1) && intersects(a, b, Position(left, top), Position(right, top))) edge = 3
        } else if (!blocked(x, y + 1) && intersects(a, b, Position(left, bottom), Position(right, bottom))) edge = 1
        if (a.x < b.x) {
            if (!blocked(x - 1, y) && intersects(a, b, Position(left, top), Position(left, bottom))) edge = 2
        } else if (!blocked(x + 1, y) && intersects(a, b, Position(right, top), Position(right, bottom))) edge = 0
        return when (edge) {
            0 -> b.copy(x = right + 0.01)
            1 -> b.copy(y = bottom + 0.01)
            2 -> b.copy(x = left - 0.01)
            3 -> b.copy(y = top - 0.01)
            else -> null
        }
    }

    private fun intersects(a: Position, b: Position, c: Position, d: Position): Boolean {
        val denominator = (d.y - c.y) * (b.x - a.x) - (d.x - c.x) * (b.y - a.y)
        if (denominator == 0.0) return false
        val u = ((d.x - c.x) * (a.y - c.y) - (d.y - c.y) * (a.x - c.x)) / denominator
        val v = ((b.x - a.x) * (a.y - c.y) - (b.y - a.y) * (a.x - c.x)) / denominator
        return u in 0.0..1.0 && v in 0.0..1.0
    }
}
