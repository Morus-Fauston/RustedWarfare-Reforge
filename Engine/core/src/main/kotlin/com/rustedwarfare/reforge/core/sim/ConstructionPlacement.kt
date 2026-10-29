package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.FootprintAnchor

/** Shared placement center for the authoritative construction and authorized client preview. */
object ConstructionPlacement {
    fun snap(order: Position, anchor: FootprintAnchor?): Position {
        fun coordinate(value: Double, offset: Float): Double =
            ((((value.toFloat() - offset) + 1f) * .05f).toInt() * 20f + offset).toDouble()
        return Position(coordinate(order.x, anchor?.offsetX ?: 10f), coordinate(order.y, anchor?.offsetY ?: 10f))
    }
}
