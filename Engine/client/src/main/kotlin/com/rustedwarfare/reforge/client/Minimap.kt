package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Position
import java.util.Collections

data class MinimapUnit(val id: String, val owner: String, val position: Position)
data class MinimapFrame(val terrain: List<TerrainFrameCell>, val units: List<MinimapUnit>)

/** Consumes exactly the same authorized frame as the main map. */
class Minimap(val worldWidth: Double, val worldHeight: Double,
              val area: MapArea = MapArea(752.0, 456.0, 176.0, 112.0)) {
    fun frame(frame: SandboxFrame): MinimapFrame = MinimapFrame(
        Collections.unmodifiableList(ArrayList(frame.terrain)),
        Collections.unmodifiableList(frame.observation.units.filter { it.alive }.map {
            MinimapUnit(it.unitId, it.ownerPlayerId, it.position)
        }))
    fun locate(x: Double, y: Double, dragging: Boolean = false): Position? {
        if (!x.isFinite() || !y.isFinite()) return null
        if (!dragging && (x < area.left || x >= area.left + area.width ||
                y <= area.bottom || y > area.bottom + area.height)) return null
        return Position(((x - area.left) / area.width * worldWidth).coerceIn(0.0, worldWidth),
            ((area.bottom + area.height - y) / area.height * worldHeight).coerceIn(0.0, worldHeight))
    }
    fun x(worldX: Double): Float = (area.left + worldX / worldWidth * area.width).toFloat()
    fun y(worldY: Double): Float = (area.bottom + area.height - worldY / worldHeight * area.height).toFloat()
}
