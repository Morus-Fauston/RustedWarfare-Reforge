package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Position
import kotlin.math.min
import kotlin.math.pow

/** UI uses y-up; the core uses y-down. Letterboxing is outside this map area. */
data class MapArea(val left: Double, val bottom: Double, val width: Double, val height: Double)
data class MapCamera(val center: Position, val zoom: Double = 1.0)

class MapProjection(val worldWidth: Double, val worldHeight: Double,
                    val mapArea: MapArea = MapArea(24.0, 100.0, 600.0, 400.0)) {
    var camera = MapCamera(Position(worldWidth / 2, worldHeight / 2))
        private set
    init {
        require(listOf(worldWidth, worldHeight, mapArea.width, mapArea.height).all { it.isFinite() && it > 0 })
        require(mapArea.left.isFinite() && mapArea.bottom.isFinite())
    }
    private val fitScale = min(mapArea.width / worldWidth, mapArea.height / worldHeight)
    val scale: Double get() = fitScale * camera.zoom
    val width: Double get() = worldWidth * scale
    val height: Double get() = worldHeight * scale
    val left: Double get() = mapArea.left + mapArea.width / 2 - camera.center.x * scale
    val bottom: Double get() = mapArea.bottom + mapArea.height / 2 - (worldHeight - camera.center.y) * scale
    fun zoomAt(uiX: Double, uiY: Double, scroll: Double) {
        if (!scroll.isFinite()) return
        val anchor = toWorld(uiX, uiY) ?: return
        val zoom = (camera.zoom * 1.2.pow(-scroll)).coerceIn(1.0, 4.0)
        val nextScale = fitScale * zoom
        camera = MapCamera(clamp(Position(
            anchor.x - (uiX - mapArea.left - mapArea.width / 2) / nextScale,
            anchor.y + (uiY - mapArea.bottom - mapArea.height / 2) / nextScale), zoom), zoom)
    }
    fun panBy(uiDx: Double, uiDy: Double) {
        if (!uiDx.isFinite() || !uiDy.isFinite()) return
        centerOn(Position(camera.center.x - uiDx / scale, camera.center.y + uiDy / scale))
    }
    fun centerOn(position: Position) {
        if (!position.x.isFinite() || !position.y.isFinite()) return
        camera = camera.copy(center = clamp(position, camera.zoom))
    }
    private fun clamp(position: Position, zoom: Double): Position {
        val halfWidth = min(worldWidth / 2, mapArea.width / (fitScale * zoom) / 2)
        val halfHeight = min(worldHeight / 2, mapArea.height / (fitScale * zoom) / 2)
        return Position(position.x.coerceIn(halfWidth, worldWidth - halfWidth),
            position.y.coerceIn(halfHeight, worldHeight - halfHeight))
    }
    fun toWorld(uiX: Double, uiY: Double): Position? {
        if (uiX < mapArea.left || uiX >= mapArea.left + mapArea.width ||
            uiY <= mapArea.bottom || uiY > mapArea.bottom + mapArea.height) return null
        val x = (uiX - left) / scale
        val y = worldHeight - (uiY - bottom) / scale
        return if (x.isFinite() && y.isFinite() && x >= 0 && x < worldWidth && y >= 0 && y < worldHeight)
            Position(x, y) else null
    }
    /** A map-owned drag may end outside its viewport; clip only that endpoint. */
    fun clippedWorld(uiX: Double, uiY: Double): Position? {
        if (!uiX.isFinite() || !uiY.isFinite()) return null
        val x = uiX.coerceIn(mapArea.left, mapArea.left + mapArea.width)
        val y = uiY.coerceIn(mapArea.bottom, mapArea.bottom + mapArea.height)
        return Position(((x - left) / scale).coerceIn(0.0, worldWidth),
            (worldHeight - (y - bottom) / scale).coerceIn(0.0, worldHeight))
    }
    fun x(worldX: Double): Float = (left + worldX * scale).toFloat()
    fun y(worldY: Double): Float = (bottom + (worldHeight - worldY) * scale).toFloat()
}
