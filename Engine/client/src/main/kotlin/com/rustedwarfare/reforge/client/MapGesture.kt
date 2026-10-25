package com.rustedwarfare.reforge.client

data class UiPoint(val x: Double, val y: Double)
enum class MapButton { SELECT, COMMAND, PAN }
sealed interface MapGestureResult {
    data class Click(val point: UiPoint, val mode: SelectionMode) : MapGestureResult
    data class Command(val point: UiPoint) : MapGestureResult
    data class Box(val start: UiPoint, val end: UiPoint, val mode: SelectionMode,
                   val baseSelection: Set<String>) : MapGestureResult
}

/** A press owns its pointer until release/cancel; Stage-owned presses never enter this state. */
class MapGesture(private val area: MapArea) {
    private data class Press(val start: UiPoint, val button: MapButton, val pointer: Int,
        val mode: SelectionMode, val selection: Set<String>, var last: UiPoint, var dragged: Boolean = false)
    private var press: Press? = null
    fun begin(point: UiPoint, button: MapButton, pointer: Int, mode: SelectionMode,
              selectedIds: Set<String>): Boolean {
        if (press != null || pointer != 0 || !area.contains(point.x, point.y)) return false
        press = Press(point, button, pointer, mode,
            java.util.Collections.unmodifiableSet(LinkedHashSet(selectedIds)), point)
        return true
    }
    fun drag(point: UiPoint, pointer: Int): UiPoint? {
        val current = press ?: return null
        if (pointer != current.pointer || !point.x.isFinite() || !point.y.isFinite()) return null
        val delta = UiPoint(point.x - current.last.x, point.y - current.last.y)
        current.last = point
        if (kotlin.math.abs(point.x - current.start.x) > 8 || kotlin.math.abs(point.y - current.start.y) > 8)
            current.dragged = true
        return if (current.button == MapButton.PAN) delta else null
    }
    fun end(point: UiPoint, button: MapButton, pointer: Int): MapGestureResult? {
        val current = press ?: return null
        if (pointer != current.pointer || button != current.button) return null
        drag(point, pointer)
        press = null
        if (!point.x.isFinite() || !point.y.isFinite()) return null
        return when {
            button == MapButton.PAN -> null
            button == MapButton.SELECT && current.dragged -> MapGestureResult.Box(current.start,
                UiPoint(point.x.coerceIn(area.left, area.right), point.y.coerceIn(area.bottom, area.top)),
                current.mode, current.selection)
            current.dragged || !area.contains(point.x, point.y) -> null
            button == MapButton.SELECT -> MapGestureResult.Click(point, current.mode)
            else -> MapGestureResult.Command(point)
        }
    }
    fun cancel() { press = null }
    val rectangle: Pair<UiPoint, UiPoint>? get() = press?.takeIf { it.button == MapButton.SELECT && it.dragged }?.let {
        it.start to UiPoint(it.last.x.coerceIn(area.left, area.right), it.last.y.coerceIn(area.bottom, area.top))
    }
}

fun selectionMode(control: Boolean, shift: Boolean): SelectionMode = when {
    control -> SelectionMode.TOGGLE
    shift -> SelectionMode.ADD
    else -> SelectionMode.REPLACE
}
