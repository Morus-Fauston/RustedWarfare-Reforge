package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Position
import kotlin.math.min

/** UI uses y-up; the core uses y-down. Letterboxing is outside this map area. */
data class MapArea(val left: Double, val bottom: Double, val width: Double, val height: Double) {
    init { require(listOf(left, bottom, width, height).all { it.isFinite() } && width > 0 && height > 0) }
    val right get() = left + width
    val top get() = bottom + height
    fun contains(x: Double, y: Double) = x.isFinite() && y.isFinite() && x >= left && x < right && y > bottom && y <= top
}

class MapProjection(val worldWidth: Double, val worldHeight: Double,
                    val area: MapArea = MapArea(24.0, 100.0, 600.0, 400.0)) {
    var zoom: Double = 1.0
        private set
    var center: Position = Position(worldWidth / 2, worldHeight / 2)
        private set
    init { require(worldWidth.isFinite() && worldHeight.isFinite() && worldWidth > 0 && worldHeight > 0) }
    val scale get() = min(area.width / worldWidth, area.height / worldHeight) * zoom
    val width get() = worldWidth * scale
    val height get() = worldHeight * scale
    val left get() = area.left + area.width / 2 - center.x * scale
    val bottom get() = area.bottom + area.height / 2 - (worldHeight - center.y) * scale

    fun zoomAt(uiX: Double, uiY: Double, factor: Double) {
        if (!factor.isFinite() || factor <= 0.0) return
        val anchor = toWorld(uiX, uiY) ?: return
        zoom = (zoom * factor).coerceIn(1.0, 4.0)
        center = Position(anchor.x - (uiX - area.left - area.width / 2) / scale,
            anchor.y + (uiY - area.bottom - area.height / 2) / scale)
        clampCenter()
    }

    fun pan(uiDeltaX: Double, uiDeltaY: Double) {
        if (!uiDeltaX.isFinite() || !uiDeltaY.isFinite()) return
        center = Position(center.x - uiDeltaX / scale, center.y + uiDeltaY / scale)
        clampCenter()
    }

    fun focus(position: Position) {
        if (!position.x.isFinite() || !position.y.isFinite()) return
        center = position
        clampCenter()
    }

    private fun clampCenter() {
        fun axis(value: Double, extent: Double, pixels: Double): Double {
            val half = pixels / scale / 2
            return if (half * 2 >= extent) extent / 2 else value.coerceIn(half, extent - half)
        }
        center = Position(axis(center.x, worldWidth, area.width), axis(center.y, worldHeight, area.height))
    }

    fun clippedWorld(uiX: Double, uiY: Double): Position? {
        if (!uiX.isFinite() || !uiY.isFinite()) return null
        return Position(((uiX.coerceIn(area.left, area.right) - left) / scale).coerceIn(0.0, worldWidth),
            (worldHeight - (uiY.coerceIn(area.bottom, area.top) - bottom) / scale).coerceIn(0.0, worldHeight))
    }

    val visibleWorld: Pair<Position, Position> get() = checkNotNull(clippedWorld(area.left, area.top)) to
        checkNotNull(clippedWorld(area.right, area.bottom))

    fun toWorld(uiX: Double, uiY: Double): Position? {
        if (!area.contains(uiX, uiY)) return null
        val x = (uiX - left) / scale
        val y = worldHeight - (uiY - bottom) / scale
        return if (x.isFinite() && y.isFinite() && x >= 0 && x < worldWidth && y >= 0 && y < worldHeight)
            Position(x, y) else null
    }
    fun x(worldX: Double): Float = (left + worldX * scale).toFloat()
    fun y(worldY: Double): Float = (bottom + (worldHeight - worldY) * scale).toFloat()
}
