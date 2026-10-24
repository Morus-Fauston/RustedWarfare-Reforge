package com.rustedwarfare.reforge.core.path

/** Primitive broad phase built from an owner's immutable defensive copy. No geometry decisions. */
internal class BlockedCellIndex(private val width: Int, private val cells: BooleanArray) {
    val count: Int by lazy { cells.count { it } }
    // Publication is thread safe, and short/local queries never allocate an unused index.
    private val indices: IntArray by lazy {
        IntArray(count).also { output ->
            var next = 0
            for (index in cells.indices) if (cells[index]) output[next++] = index
        }
    }

    fun any(firstColumn: Int, lastColumn: Int, firstRow: Int, lastRow: Int,
            predicate: (Int, Int) -> Boolean): Boolean {
        if (firstColumn > lastColumn || firstRow > lastRow) return false
        val area = (lastColumn.toLong() - firstColumn + 1) * (lastRow.toLong() - firstRow + 1)
        // Small/local rectangles avoid a whole-map count; dense maps avoid index division per cell.
        if (area <= 16 || area <= count.toLong() * 2) {
            for (row in firstRow..lastRow) for (column in firstColumn..lastColumn) {
                if (cells[row * width + column] && predicate(column, row)) return true
            }
        } else {
            for (index in indices) {
                val row = index / width
                if (row < firstRow) continue
                if (row > lastRow) break
                val column = index % width
                if (column in firstColumn..lastColumn && predicate(column, row)) return true
            }
        }
        return false
    }
}
