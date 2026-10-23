package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.sim.Position

/** Half-open terrain cells agree with floor-based center membership, including at endpoints. */
internal object CenterLineCells {
    fun enters(a: Position, b: Position, column: Int, row: Int, tileWidth: Int, tileHeight: Int): Boolean {
        var low = 0.0
        var high = 1.0
        var lowClosed = true
        var highClosed = true
        fun clip(start: Double, delta: Double, lower: Double, upper: Double): Boolean {
            if (delta == 0.0) return start >= lower && start < upper
            val increasing = delta > 0.0
            val enter = ((if (increasing) lower else upper) - start) / delta
            val leave = ((if (increasing) upper else lower) - start) / delta
            val enterClosed = increasing
            val leaveClosed = !increasing
            if (enter > low) { low = enter; lowClosed = enterClosed }
            else if (enter == low) lowClosed = lowClosed && enterClosed
            if (leave < high) { high = leave; highClosed = leaveClosed }
            else if (leave == high) highClosed = highClosed && leaveClosed
            return low < high || (low == high && lowClosed && highClosed)
        }
        val left = column * tileWidth.toDouble()
        val top = row * tileHeight.toDouble()
        return clip(a.x, b.x - a.x, left, left + tileWidth) &&
            clip(a.y, b.y - a.y, top, top + tileHeight)
    }
}
