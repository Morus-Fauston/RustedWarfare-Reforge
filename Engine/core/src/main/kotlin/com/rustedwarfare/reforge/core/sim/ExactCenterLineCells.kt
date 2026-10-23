package com.rustedwarfare.reforge.core.sim

import java.math.BigDecimal

/** Exact binary64 segment against grid cells, only for the multi-cell movement guard. */
internal class ExactCenterLineCells(start: Position, end: Position) {
    // The Double constructor retains the exact binary value; valueOf would not.
    private val x = BigDecimal(start.x)
    private val y = BigDecimal(start.y)
    private val dx = BigDecimal(end.x).subtract(x)
    private val dy = BigDecimal(end.y).subtract(y)

    fun enters(column: Int, row: Int, tileWidth: Int, tileHeight: Int,
               lastColumn: Boolean, lastRow: Boolean): Boolean {
        var low = Time.ZERO
        var high = Time.ONE
        var lowClosed = true
        var highClosed = true
        fun clip(start: BigDecimal, delta: BigDecimal, lower: BigDecimal, upper: BigDecimal,
                 upperClosed: Boolean): Boolean {
            if (delta.signum() == 0) return start >= lower && (start < upper || upperClosed && start.compareTo(upper) == 0)
            val increasing = delta.signum() > 0
            val enter = Time.of((if (increasing) lower else upper).subtract(start), delta)
            val leave = Time.of((if (increasing) upper else lower).subtract(start), delta)
            val enterClosed = increasing || upperClosed
            val leaveClosed = !increasing || upperClosed
            when (enter.compareTo(low)) {
                1 -> { low = enter; lowClosed = enterClosed }
                0 -> lowClosed = lowClosed && enterClosed
            }
            when (leave.compareTo(high)) {
                -1 -> { high = leave; highClosed = leaveClosed }
                0 -> highClosed = highClosed && leaveClosed
            }
            return low < high || low.compareTo(high) == 0 && lowClosed && highClosed
        }
        val left = BigDecimal.valueOf(column.toLong() * tileWidth)
        val right = BigDecimal.valueOf((column.toLong() + 1L) * tileWidth)
        val top = BigDecimal.valueOf(row.toLong() * tileHeight)
        val bottom = BigDecimal.valueOf((row.toLong() + 1L) * tileHeight)
        return clip(x, dx, left, right, lastColumn) && clip(y, dy, top, bottom, lastRow)
    }

    private class Time(private val numerator: BigDecimal, private val denominator: BigDecimal) : Comparable<Time> {
        override fun compareTo(other: Time): Int =
            numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator))
        companion object {
            val ZERO = Time(BigDecimal.ZERO, BigDecimal.ONE)
            val ONE = Time(BigDecimal.ONE, BigDecimal.ONE)
            fun of(numerator: BigDecimal, denominator: BigDecimal): Time =
                if (denominator.signum() < 0) Time(numerator.negate(), denominator.negate())
                else Time(numerator, denominator)
        }
    }
}
