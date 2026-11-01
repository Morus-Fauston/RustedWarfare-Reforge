package com.rustedwarfare.reforge.core.knowledge

import java.util.Collections

/** Source multiplicities, not a truth map. Published chunks have no mutation-capable alias. */
internal class VisionCoverage private constructor(val size: Int, private val blocks: Array<IntArray?>) {
    operator fun get(index: Int): Int = blocks[index / BLOCK_SIZE]?.get(index % BLOCK_SIZE) ?: 0

    fun edit(update: (Editor) -> Unit): CoverageUpdate {
        val editor = Editor(this)
        update(editor)
        return editor.finish()
    }

    class Editor internal constructor(private val original: VisionCoverage) {
        private val changed = HashMap<Int, IntArray>()
        private var sealed = false
        fun add(index: Int, amount: Int) {
            check(!sealed)
            if (amount == 0) return
            val block = index / BLOCK_SIZE
            val cells = changed.getOrPut(block) { original.blocks[block]?.copyOf()
                ?: IntArray(minOf(BLOCK_SIZE, original.size - block * BLOCK_SIZE)) }
            val offset = index % BLOCK_SIZE
            val next = cells[offset] + amount
            check(next >= 0) { "Vision source count must remain nonnegative" }
            cells[offset] = next
        }

        internal fun finish(): CoverageUpdate {
            check(!sealed); sealed = true
            var next: Array<IntArray?>? = null
            val visibility = ArrayList<Int>()
            for ((block, cells) in changed) {
                var different = false
                var nonzero = false
                for (offset in cells.indices) {
                    val index = block * BLOCK_SIZE + offset
                    val before = original[index]
                    val after = cells[offset]
                    different = different || before != after
                    nonzero = nonzero || after != 0
                    if ((before > 0) != (after > 0)) visibility.add(index)
                }
                if (different) {
                    if (next == null) next = original.blocks.copyOf()
                    next[block] = if (nonzero) cells else null
                }
            }
            return CoverageUpdate(next?.let { VisionCoverage(original.size, it) } ?: original,
                Collections.unmodifiableList(visibility))
        }
    }

    companion object {
        private const val BLOCK_SIZE = 64
        fun empty(size: Int) = VisionCoverage(size, arrayOfNulls((size.toLong() + BLOCK_SIZE - 1).div(BLOCK_SIZE).toInt()))
    }
}

internal data class CoverageUpdate(val coverage: VisionCoverage, val visibilityChanges: List<Int>)

/** Only this audience's visible cells. No sources, other players' knowledge or world capabilities. */
internal class VisionArea private constructor(private val width: Int, private val height: Int,
    private val coverage: VisionCoverage, private val indices: IntArray, cells: List<VisionCell>) {
    val cells: List<VisionCell> = Collections.unmodifiableList(cells)
    fun contains(column: Int, row: Int): Boolean = column in 0 until width && row in 0 until height && coverage[row * width + column] > 0

    fun updated(update: CoverageUpdate): VisionArea {
        if (update.visibilityChanges.isEmpty()) return this
        val dirty = update.visibilityChanges.sorted()
        val buffer = IntArray(minOf(coverage.size.toLong(), indices.size.toLong() + dirty.size).toInt())
        val values = ArrayList<VisionCell>(buffer.size)
        var old = 0; var changed = 0; var count = 0
        while (old < indices.size || changed < dirty.size) {
            val oldIndex = if (old < indices.size) indices[old] else Int.MAX_VALUE
            val dirtyIndex = if (changed < dirty.size) dirty[changed] else Int.MAX_VALUE
            val index = minOf(oldIndex, dirtyIndex)
            if (update.coverage[index] > 0) {
                buffer[count++] = index
                values.add(if (index == oldIndex) cells[old] else VisionCell(index % width, index / width))
            }
            if (index == oldIndex) old++
            if (index == dirtyIndex) changed++
        }
        return VisionArea(width, height, update.coverage, buffer.copyOf(count), values)
    }

    companion object {
        fun empty(width: Int, height: Int, coverage: VisionCoverage) = VisionArea(width, height, coverage, IntArray(0), emptyList())
    }
}
