package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Position
import java.util.Collections
import kotlin.math.abs

enum class MapButton { SELECT, COMMAND, PAN }
sealed interface MapIntent {
    data class Click(val position: Position, val mode: SelectionMode) : MapIntent
    data class Box(val start: Position, val end: Position, val mode: SelectionMode, val selectionAtStart: Set<String>) : MapIntent
    data class Command(val position: Position) : MapIntent
}
class MapGesture(private val map: MapProjection) {
    private data class Press(val x: Double, val y: Double, val world: Position,
        val button: MapButton, val mode: SelectionMode, val selected: Set<String>)
    private var press: Press? = null
    private var lastX = 0.0
    private var lastY = 0.0
    private var dragging = false
    var box: Pair<Position, Position>? = null
        private set
    fun begin(x: Double, y: Double, button: MapButton, mode: SelectionMode, selected: Set<String>): Boolean {
        cancel()
        val world = map.toWorld(x, y) ?: return false
        press = Press(x, y, world, button, mode, Collections.unmodifiableSet(LinkedHashSet(selected)))
        lastX = x; lastY = y
        return true
    }
    fun drag(x: Double, y: Double): Boolean {
        val start = press ?: return false
        val end = map.clippedWorld(x, y) ?: return true
        when (start.button) {
            MapButton.PAN -> map.panBy(x - lastX, y - lastY)
            MapButton.SELECT -> {
                dragging = dragging || abs(x - start.x) > 8 || abs(y - start.y) > 8
                if (dragging) box = start.world to end
            }
            MapButton.COMMAND -> Unit
        }
        lastX = x; lastY = y
        return true
    }
    fun release(x: Double, y: Double): MapIntent? {
        val start = press ?: return null
        drag(x, y)
        val result = when (start.button) {
            MapButton.SELECT -> if (dragging) map.clippedWorld(x, y)?.let {
                MapIntent.Box(start.world, it, start.mode, start.selected)
            } else map.toWorld(x, y)?.let { MapIntent.Click(it, start.mode) }
            MapButton.COMMAND -> map.toWorld(x, y)?.let { MapIntent.Command(it) }
            MapButton.PAN -> null
        }
        cancel()
        return result
    }
    fun cancel() { press = null; dragging = false; box = null }
}
