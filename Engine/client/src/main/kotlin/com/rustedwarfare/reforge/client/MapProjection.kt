package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Position
import kotlin.math.min

/** UI uses y-up; the core uses y-down. Letterboxing is outside this map area. */
class MapProjection(val worldWidth: Double, val worldHeight: Double) {
    val scale = min(600.0 / worldWidth, 400.0 / worldHeight)
    val width = worldWidth * scale
    val height = worldHeight * scale
    val left = 24.0 + (600.0 - width) / 2
    val bottom = 100.0 + (400.0 - height) / 2
    fun toWorld(uiX: Double, uiY: Double): Position? {
        val x = (uiX - left) / scale
        val y = worldHeight - (uiY - bottom) / scale
        return if (x.isFinite() && y.isFinite() && x >= 0 && x < worldWidth && y >= 0 && y < worldHeight)
            Position(x, y) else null
    }
    fun x(worldX: Double): Float = (left + worldX * scale).toFloat()
    fun y(worldY: Double): Float = (bottom + (worldHeight - worldY) * scale).toFloat()
}
