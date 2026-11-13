package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.FootprintAnchor
import com.rustedwarfare.reforge.core.content.BuildingFootprint
import java.util.Collections

/** Geometry only: no ground, resource, unit or visibility information. */
class PlacementFootprint internal constructor(cells: List<Pair<Int, Int>>, val withinMap: Boolean) {
    val cells: List<Pair<Int, Int>> = Collections.unmodifiableList(ArrayList(cells))
}

/** Shared placement center for the authoritative construction and authorized client preview. */
object ConstructionPlacement {
    /** Native mobile nano-site contact test; this is not dynamic movement collision. */
    fun mobileOverlaps(position: Position, radius: Double, other: Position, otherRadius: Double,
                       otherIsBuilding: Boolean): Boolean {
        val dx = position.x.toFloat() - other.x.toFloat()
        val dy = position.y.toFloat() - other.y.toFloat()
        val limit = if (otherIsBuilding) 9f else maxOf(11f, radius.toFloat() + otherRadius.toFloat())
        return dx * dx + dy * dy < limit * limit
    }

    fun snap(order: Position, anchor: FootprintAnchor?): Position {
        fun coordinate(value: Double, offset: Float): Double =
            ((((value.toFloat() - offset) + 1f) * .05f).toInt() * 20f + offset).toDouble()
        return Position(coordinate(order.x, anchor?.offsetX ?: 10f), coordinate(order.y, anchor?.offsetY ?: 10f))
    }

    fun contains(widthTiles: Int, heightTiles: Int, tileWidth: Int, tileHeight: Int,
                 position: Position, radius: Double): Boolean =
        MapBounds.contains(widthTiles.toDouble() * tileWidth, heightTiles.toDouble() * tileHeight, position, radius)

    /** Outside cells remain available to the preview; truth consumers require [PlacementFootprint.withinMap]. */
    fun footprint(widthTiles: Int, heightTiles: Int, tileWidth: Int, tileHeight: Int,
                  position: Position, shape: BuildingFootprint, anchor: FootprintAnchor? = null,
                  clipToMap: Boolean = false): PlacementFootprint {
        require(widthTiles > 0 && heightTiles > 0 && tileWidth > 0 && tileHeight > 0)
        fun custom(value: Double, offset: Float, tile: Int): Long {
            // Each operation is binary32; the source's cast truncates toward zero.
            val coordinate = ((value.toFloat() - offset) + 1f) * (1f / tile.toFloat())
            require(coordinate.isFinite() && coordinate.toDouble() >= Int.MIN_VALUE.toDouble() &&
                coordinate.toDouble() <= Int.MAX_VALUE.toDouble()) { "Custom building anchor is outside its integer domain" }
            return coordinate.toInt().toLong()
        }
        val column = if (anchor == null) MapBounds.cell(position.x, tileWidth, widthTiles).toLong()
            else custom(position.x, anchor.offsetX, tileWidth)
        val row = if (anchor == null) MapBounds.cell(position.y, tileHeight, heightTiles).toLong()
            else custom(position.y, anchor.offsetY, tileHeight)
        val left = (column + shape.left.toLong()).let { if (clipToMap) maxOf(0L, it) else it }
        val right = (column + shape.right.toLong()).let { if (clipToMap) minOf(widthTiles - 1L, it) else it }
        val top = (row + shape.top.toLong()).let { if (clipToMap) maxOf(0L, it) else it }
        val bottom = (row + shape.bottom.toLong()).let { if (clipToMap) minOf(heightTiles - 1L, it) else it }
        val withinMap = left >= 0L && top >= 0L && right < widthTiles && bottom < heightTiles && left <= right && top <= bottom
        // Bound spans before multiplication, allocation, iteration or conversion back to Int.
        val columns = right - left + 1L
        val rows = bottom - top + 1L
        val limit = widthTiles.toLong() * heightTiles
        if (left < Int.MIN_VALUE || top < Int.MIN_VALUE || right > Int.MAX_VALUE || bottom > Int.MAX_VALUE ||
            columns <= 0L || rows <= 0L || columns > limit || rows > limit || columns > limit / rows)
            return PlacementFootprint(emptyList(), false)
        val cells = ArrayList<Pair<Int, Int>>()
        for (y in top..bottom) for (x in left..right) cells += x.toInt() to y.toInt()
        return PlacementFootprint(cells, withinMap)
    }
}
