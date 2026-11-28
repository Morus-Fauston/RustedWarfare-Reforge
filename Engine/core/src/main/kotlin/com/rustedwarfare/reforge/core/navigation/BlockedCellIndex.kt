package com.rustedwarfare.reforge.core.navigation

/** Primitive zero-boxing cell predicate for hot terrain broad-phase scans. */
internal fun interface IntCellPredicate {
    fun test(col: Int, row: Int): Boolean
}

/** Primitive broad phase built from an owner's immutable defensive copy. No geometry decisions. */
internal class BlockedCellIndex(private val width: Int, private val cells: BooleanArray) {
    val count: Int by lazy { cells.count { it } }
    // Keep empty parts of each scanned row skippable in a single word.
    // Local checks still read the owner's defensive copy directly.
    private val words: LongArray by lazy {
        LongArray(((cells.size.toLong() + 63) / 64).toInt()).also { index ->
            for (cell in cells.indices) if (cells[cell]) {
                val word = cell ushr 6
                index[word] = index[word] or (1L shl (cell and 63))
            }
        }
    }

    fun any(firstColumn: Int, lastColumn: Int, firstRow: Int, lastRow: Int,
            predicate: (Int, Int) -> Boolean): Boolean =
        anyPrim(firstColumn, lastColumn, firstRow, lastRow) { col, row -> predicate(col, row) }

    fun anyPrim(firstColumn: Int, lastColumn: Int, firstRow: Int, lastRow: Int,
                predicate: IntCellPredicate): Boolean {
        if (firstColumn > lastColumn || firstRow > lastRow) return false
        val area = (lastColumn.toLong() - firstColumn + 1) * (lastRow.toLong() - firstRow + 1)
        if (area <= 16) {
            for (row in firstRow..lastRow) for (column in firstColumn..lastColumn) {
                if (cells[row * width + column] && predicate.test(column, row)) return true
            }
            return false
        }
        if (count == 0) return false
        val occupied = words
        for (row in firstRow..lastRow) {
            val origin = row * width
            val first = origin + firstColumn
            val last = origin + lastColumn
            val firstWord = first ushr 6
            val lastWord = last ushr 6
            for (word in firstWord..lastWord) {
                var bits = occupied[word]
                if (word == firstWord) bits = bits and (-1L shl (first and 63))
                if (word == lastWord) bits = bits and (-1L ushr (63 - (last and 63)))
                while (bits != 0L) {
                    val column = (word shl 6) + java.lang.Long.numberOfTrailingZeros(bits) - origin
                    if (predicate.test(column, row)) return true
                    bits = bits and (bits - 1)
                }
            }
        }
        return false
    }
}
