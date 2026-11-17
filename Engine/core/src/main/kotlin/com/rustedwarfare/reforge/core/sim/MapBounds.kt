package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape

/** Closed map edges allow tangency; obstacle contact uses its separate closed collision rule. */
internal object MapBounds {
    fun cell(coordinate: Double, tileSize: Int, tileCount: Int): Int =
        if (coordinate == tileCount.toDouble() * tileSize) tileCount - 1
        else kotlin.math.floor(coordinate / tileSize).toInt()

    fun clamp(map: MapDefinition, position: Position): Position = Position(
        position.x.coerceIn(0.0, map.widthTiles.toDouble() * map.tileWidth),
        position.y.coerceIn(0.0, map.heightTiles.toDouble() * map.tileHeight))

    fun containsHull(map: MapDefinition, position: Position, heading: Double, shape: MovementCollisionShape): Boolean {
        val width = map.widthTiles.toDouble() * map.tileWidth
        val height = map.heightTiles.toDouble() * map.tileHeight
        return when (shape) {
            is MovementCollisionShape.Circle -> contains(width, height, position, shape.radius)
            is MovementCollisionShape.Rectangle ->
                RectangularTerrain(shape, ScreenPlane.normalize(heading)).contains(width, height, position)
        }
    }

    fun contains(width: Double, height: Double, position: Position, radius: Double): Boolean =
        contains(width, height, position.x, position.y, radius)

    fun contains(width: Double, height: Double, x: Double, y: Double, radius: Double): Boolean =
        x.isFinite() && y.isFinite() && radius.isFinite() && radius >= 0.0 &&
            x >= radius && y >= radius && x <= width - radius && y <= height - radius
}
