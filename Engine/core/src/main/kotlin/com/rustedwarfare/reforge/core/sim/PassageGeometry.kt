package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.path.PlanningTerrain
import kotlin.math.ceil

internal data class PassageCell(val column: Int, val row: Int)

internal data class PassageOpening(val horizontal: Boolean, val outward: Int, val plane: Double,
                                   val low: Double, val high: Double) {
    private fun along(position: Position) = if (horizontal) position.x else position.y
    private fun across(position: Position) = if (horizontal) position.y else position.x
    fun depth(position: Position): Double = (along(position) - plane) * outward

    fun crossing(from: Position, to: Position): PassageCrossing? {
        val start = depth(from)
        val end = depth(to)
        val inbound = when {
            start >= 0.0 && end < 0.0 -> true
            start <= 0.0 && end > 0.0 -> false
            else -> return null
        }
        val fraction = -start / (end - start)
        val offset = across(from) + (across(to) - across(from)) * fraction
        return if (offset in low..high) PassageCrossing(fraction, inbound) else null
    }

    fun overlapsOutlet(position: Position, halfX: Double, halfY: Double, outletRoom: Double): Boolean {
        val side = across(position)
        val halfAlong = if (horizontal) halfX else halfY
        val halfAcross = if (horizontal) halfY else halfX
        val outside = depth(position)
        return outside + halfAlong > 0.0 && outside - halfAlong < outletRoom &&
            side + halfAcross > low && side - halfAcross < high
    }
}

internal data class PassageCrossing(val fraction: Double, val inbound: Boolean)

internal class NarrowPassage(val cells: Set<PassageCell>, val mouths: List<PassageOpening>,
                             val width: Double, private val tileWidth: Int, private val tileHeight: Int, cellHash: Int = cells.hashCode()) {
    private val identity = cellHash * 31 + mouths.hashCode()

    override fun hashCode(): Int = identity

    override fun equals(other: Any?): Boolean =
        this === other || other is NarrowPassage && identity == other.identity && cells == other.cells && mouths == other.mouths &&
            width == other.width && tileWidth == other.tileWidth && tileHeight == other.tileHeight

    fun contains(column: Int, row: Int): Boolean = PassageCell(column, row) in cells

    fun overlaps(position: Position, halfX: Double, halfY: Double): Boolean {
        val firstColumn = kotlin.math.floor((position.x - halfX) / tileWidth).toInt()
        val lastColumn = ceil((position.x + halfX) / tileWidth).toInt() - 1
        val firstRow = kotlin.math.floor((position.y - halfY) / tileHeight).toInt()
        val lastRow = ceil((position.y + halfY) / tileHeight).toInt() - 1
        for (row in firstRow..lastRow) for (column in firstColumn..lastColumn) {
            if (PassageCell(column, row) in cells) return true
        }
        return false
    }

    fun entryAlong(start: Position, points: List<Position>, overlaps: Boolean): Int? {
        var from = start
        for (to in points) {
            val first = mouths[0].crossing(from, to)
            val second = mouths[1].crossing(from, to)
            val door = when {
                first == null && second == null -> null
                first != null && (second == null || first.fraction <= second.fraction) -> 0
                else -> 1
            }
            if (door != null) {
                val crossing = checkNotNull(if (door == 0) first else second)
                return if (crossing.inbound) door else if (overlaps) 1 - door else null
            }
            from = to
        }
        if (!overlaps) return null
        // The center can already be past a mouth while the stern still overlaps the passage.
        val exit = mouths.indexOfFirst { it.depth(start) >= 0.0 }
        return if (exit >= 0) 1 - exit else null
    }
}

/** Derive the connected narrow tiles and their two mouths from one authorized grid. */
internal class KnownPassages(private val twoHullWidth: Double) {
    private data class Face(val horizontal: Boolean, val outward: Int, val plane: Int)
    private val bay = HashMap<PassageCell, Boolean>()
    class Tile(val passage: NarrowPassage? = null, val wideBay: Boolean = false)
    private val wide = Tile(wideBay = true)
    private val noGate = Tile()
    private val passages = HashMap<PassageCell, Tile>()

    fun at(terrain: PlanningTerrain, point: Position): NarrowPassage? = tileAt(terrain,
        kotlin.math.floor(point.x / terrain.tileWidth).toInt(), kotlin.math.floor(point.y / terrain.tileHeight).toInt()).passage

    fun tileAt(terrain: PlanningTerrain, column: Int, row: Int): Tile {
        if (column !in 0 until terrain.widthTiles || row !in 0 until terrain.heightTiles) return noGate
        val cell = PassageCell(column, row)
        passages[cell]?.let { return it }
        if (!open(terrain, cell)) return noGate.also { passages[cell] = it }
        if (isBay(terrain, cell)) return wide.also { passages[cell] = it }
        val visited = linkedSetOf(cell)
        val pending = ArrayDeque<PassageCell>()
        val boundary = linkedMapOf<Face, MutableList<Int>>()
        pending.addLast(cell)
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            for ((dc, dr) in OFFSETS) {
                val neighbor = PassageCell(next.column + dc, next.row + dr)
                if (!open(terrain, neighbor)) continue
                if (isBay(terrain, neighbor)) {
                    val (face, offset) = if (dc != 0) Face(true, dc, next.column + maxOf(0, dc)) to next.row
                        else Face(false, dr, next.row + maxOf(0, dr)) to next.column
                    boundary.getOrPut(face) { mutableListOf() }.add(offset)
                } else if (visited.add(neighbor)) pending.addLast(neighbor)
            }
        }
        val mouths = openings(terrain, boundary)
        val passage = if (mouths.size == 2) NarrowPassage(visited, mouths,
            visited.minOf { maximumSquare(terrain, it) }, terrain.tileWidth, terrain.tileHeight) else null
        val result = passage?.let { Tile(it) } ?: noGate
        for (known in visited) passages[known] = result
        return result
    }

    private fun isBay(terrain: PlanningTerrain, cell: PassageCell): Boolean =
        bay.getOrPut(cell) { fitsSquare(terrain, cell, twoHullWidth) }

    private fun open(terrain: PlanningTerrain, cell: PassageCell): Boolean =
        cell.column in 0 until terrain.widthTiles && cell.row in 0 until terrain.heightTiles &&
            !terrain.isBlocked(cell.column, cell.row)

    private fun openings(terrain: PlanningTerrain, boundary: Map<Face, List<Int>>): List<PassageOpening> {
        val mouths = mutableListOf<PassageOpening>()
        for ((face, values) in boundary) {
            val offsets = values.sorted()
            val width = if (face.horizontal) terrain.tileHeight else terrain.tileWidth
            val coordinate = face.plane.toDouble() * if (face.horizontal) terrain.tileWidth else terrain.tileHeight
            var start = offsets.first()
            var previous = start
            fun add(end: Int) {
                mouths += PassageOpening(face.horizontal, face.outward, coordinate, start.toDouble() * width, (end + 1.0) * width)
            }
            for (offset in offsets.drop(1)) {
                if (offset > previous + 1) {
                    add(previous)
                    start = offset
                }
                previous = offset
            }
            add(previous)
        }
        return mouths.sortedWith(compareBy<PassageOpening> { it.horizontal }.thenBy { it.outward }
            .thenBy { it.plane }.thenBy { it.low })
    }

    private fun maximumSquare(terrain: PlanningTerrain, cell: PassageCell): Double {
        var lower = minOf(terrain.tileWidth, terrain.tileHeight)
        var upper = ceil(twoHullWidth).toInt()
        while (upper - lower > 1) {
            val size = lower + (upper - lower) / 2
            if (fitsSquare(terrain, cell, size.toDouble())) lower = size else upper = size
        }
        return lower.toDouble()
    }

    private fun fitsSquare(terrain: PlanningTerrain, cell: PassageCell, width: Double): Boolean {
        val columns = ceil(width / terrain.tileWidth).toInt()
        val rows = ceil(width / terrain.tileHeight).toInt()
        val firstColumn = maxOf(0, cell.column - columns + 1)
        val firstRow = maxOf(0, cell.row - rows + 1)
        for (row in firstRow..minOf(cell.row, terrain.heightTiles - rows)) {
            for (column in firstColumn..minOf(cell.column, terrain.widthTiles - columns)) {
                if (!terrain.anyBlocked(column, column + columns - 1, row, row + rows - 1) { _, _ -> true }) return true
            }
        }
        return false
    }

    companion object {
        private val OFFSETS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
    }
}
