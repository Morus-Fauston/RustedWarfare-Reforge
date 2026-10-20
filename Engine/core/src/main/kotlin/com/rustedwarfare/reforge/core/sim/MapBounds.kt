package com.rustedwarfare.reforge.core.sim

/** Closed map edges allow tangency; obstacle contact uses its separate closed collision rule. */
internal object MapBounds {
    fun cell(coordinate: Double, tileSize: Int, tileCount: Int): Int =
        if (coordinate == tileCount.toDouble() * tileSize) tileCount - 1
        else kotlin.math.floor(coordinate / tileSize).toInt()

    fun contains(width: Double, height: Double, position: Position, radius: Double): Boolean =
        position.x.isFinite() && position.y.isFinite() && radius.isFinite() && radius >= 0.0 &&
            position.x >= radius && position.y >= radius &&
            position.x <= width - radius && position.y <= height - radius
}
