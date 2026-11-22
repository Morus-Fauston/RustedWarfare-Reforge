package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Position
import java.util.Collections

data class MinimapUnit(val id: String, val owner: String, val position: Position)
data class MinimapFrame(val terrain: List<TerrainFrameCell>, val units: List<MinimapUnit>)

/** Consumes exactly the same authorized frame as the main map. */
class Minimap(val worldWidth: Double, val worldHeight: Double,
              initialArea: MapArea = MapArea(752.0, 456.0, 176.0, 112.0)) {
    var area: MapArea = initialArea
        private set

    fun updateArea(newArea: MapArea) {
        area = newArea
    }

    companion object {
        /**
         * Computes a screen-aspect-compensated minimap MapArea anchored at the top-right corner of [bounds],
         * preserving the true map width:height ratio in physical screen pixels even under non-uniform stage scaling.
         */
        fun fitArea(
            bounds: MapArea,
            worldWidth: Double,
            worldHeight: Double,
            stagePixelsPerUnitX: Double = 1.0,
            stagePixelsPerUnitY: Double = 1.0,
        ): MapArea {
            if (worldWidth <= 0.0 || worldHeight <= 0.0 || stagePixelsPerUnitX <= 0.0 || stagePixelsPerUnitY <= 0.0) {
                return bounds
            }
            val maxPxW = bounds.width * stagePixelsPerUnitX
            val maxPxH = bounds.height * stagePixelsPerUnitY
            val scale = minOf(maxPxW / worldWidth, maxPxH / worldHeight)
            val stageW = (worldWidth * scale / stagePixelsPerUnitX).coerceIn(1.0, bounds.width)
            val stageH = (worldHeight * scale / stagePixelsPerUnitY).coerceIn(1.0, bounds.height)
            // Anchor top-right inside bounds so minimap hugs the top-right corner cleanly
            val left = bounds.left + (bounds.width - stageW)
            val bottom = bounds.bottom + (bounds.height - stageH)
            return MapArea(left, bottom, stageW, stageH)
        }

        /**
         * Fits the map inside an opaque [bounds] well, centered horizontally & vertically
         * (HUD Spec §6: black letterbox inside the right-rail minimap well).
         */
        fun fitCentered(
            bounds: MapArea,
            worldWidth: Double,
            worldHeight: Double,
            stagePixelsPerUnitX: Double = 1.0,
            stagePixelsPerUnitY: Double = 1.0,
        ): MapArea {
            if (worldWidth <= 0.0 || worldHeight <= 0.0 || stagePixelsPerUnitX <= 0.0 || stagePixelsPerUnitY <= 0.0) {
                return bounds
            }
            val maxPxW = bounds.width * stagePixelsPerUnitX
            val maxPxH = bounds.height * stagePixelsPerUnitY
            val scale = minOf(maxPxW / worldWidth, maxPxH / worldHeight)
            val stageW = (worldWidth * scale / stagePixelsPerUnitX).coerceIn(1.0, bounds.width)
            val stageH = (worldHeight * scale / stagePixelsPerUnitY).coerceIn(1.0, bounds.height)
            val left = bounds.left + (bounds.width - stageW) * 0.5
            val bottom = bounds.bottom + (bounds.height - stageH) * 0.5
            return MapArea(left, bottom, stageW, stageH)
        }
    }
    fun frame(frame: SandboxFrame): MinimapFrame = MinimapFrame(
        Collections.unmodifiableList(ArrayList(frame.terrain)),
        Collections.unmodifiableList(frame.observation.units.filter { it.alive && it.attachment == null }.map {
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
