package com.rustedwarfare.reforge.core.knowledge

import java.util.Collections
import java.util.RandomAccess

/** Owned immutable blocks. A snapshot retains values, never a store or its mutable publication state. */
internal class KnowledgeCells private constructor(
    val size: Int,
    private val blocks: Array<Array<CellKnowledge>?>,
) {
    val view: List<CellKnowledge> = Collections.unmodifiableList(object : java.util.AbstractList<CellKnowledge>(), RandomAccess {
        override val size: Int get() = this@KnowledgeCells.size
        override fun get(index: Int): CellKnowledge = this@KnowledgeCells[index]
    })

    operator fun get(index: Int): CellKnowledge {
        if (index !in 0 until size) throw IndexOutOfBoundsException("Index $index outside $size cells")
        return blocks[index / BLOCK_SIZE]?.get(index % BLOCK_SIZE) ?: UNKNOWN
    }

    fun replacing(changes: List<KnowledgeCellChange>): KnowledgeCells {
        if (changes.isEmpty()) return this
        val next = blocks.copyOf()
        val copied = HashSet<Int>()
        for (change in changes) {
            val block = change.index / BLOCK_SIZE
            if (copied.add(block)) next[block] = blocks[block]?.copyOf()
                ?: Array(minOf(BLOCK_SIZE, size - block * BLOCK_SIZE)) { UNKNOWN }
            checkNotNull(next[block])[change.index % BLOCK_SIZE] = change.after
        }
        // No builder or input array survives publication with mutation access to these blocks.
        return KnowledgeCells(size, next)
    }

    companion object {
        private const val BLOCK_SIZE = 64
        val UNKNOWN = CellKnowledge(false, null)
        private val EMPTY = KnowledgeCells(0, emptyArray())
        fun unknown(size: Int): KnowledgeCells = if (size == 0) EMPTY else
            KnowledgeCells(size, arrayOfNulls((size.toLong() + BLOCK_SIZE - 1).div(BLOCK_SIZE).toInt()))
        fun copied(cells: List<CellKnowledge>): KnowledgeCells {
            val owned = Array<Array<CellKnowledge>?>((cells.size.toLong() + BLOCK_SIZE - 1).div(BLOCK_SIZE).toInt()) { block ->
                Array(minOf(BLOCK_SIZE, cells.size - block * BLOCK_SIZE)) { offset -> cells[block * BLOCK_SIZE + offset] }
            }
            return KnowledgeCells(cells.size, owned)
        }
    }
}

internal data class KnowledgeCellChange(val index: Int, val before: CellKnowledge, val after: CellKnowledge)
internal data class KnowledgeProjection(val store: PlayerTerrainKnowledge, val changes: List<KnowledgeCellChange>)
