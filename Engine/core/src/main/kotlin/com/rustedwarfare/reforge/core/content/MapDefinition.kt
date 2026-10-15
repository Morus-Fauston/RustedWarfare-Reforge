package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.util.Collections

class MapDefinition(
    val widthTiles: Int,
    val heightTiles: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    tileSets: List<TileSetDefinition>,
    groundGids: IntArray,
    val blocking: BlockingGrid,
) {
    val tileSets: List<TileSetDefinition> = Collections.unmodifiableList(ArrayList(tileSets))
    private val gids = groundGids.copyOf()
    val groundGids: IntArray get() = gids.copyOf()
}

class TileSetDefinition internal constructor(
    val firstGid: Int,
    val name: String,
    val tileWidth: Int,
    val tileHeight: Int,
    val imagePath: Path,
    blockedTileIds: Set<Int>,
    internal val tileCount: Int,
    internal val columns: Int,
    internal val spacing: Int = 0,
    internal val margin: Int = 0,
    internal val transparentColor: String? = null,
) {
    val blockedTileIds: Set<Int> = Collections.unmodifiableSet(LinkedHashSet(blockedTileIds))

    constructor(
        firstGid: Int,
        name: String,
        tileWidth: Int,
        tileHeight: Int,
        imagePath: Path,
        blockedTileIds: Set<Int>,
    ) : this(firstGid, name, tileWidth, tileHeight, imagePath, blockedTileIds,
        Int.MAX_VALUE, 0, 0, 0, null)
}

class BlockingGrid(val widthTiles: Int, val heightTiles: Int, blocked: BooleanArray) {
    init {
        require(widthTiles > 0 && heightTiles > 0 && widthTiles.toLong() * heightTiles == blocked.size.toLong())
    }
    private val cells = blocked.copyOf()
    val blockedCount: Int = cells.count { it }

    fun isBlocked(col: Int, row: Int): Boolean {
        if (col !in 0 until widthTiles || row !in 0 until heightTiles) {
            throw IndexOutOfBoundsException("Cell ($col, $row) outside $widthTiles x $heightTiles")
        }
        return cells[row * widthTiles + col]
    }
}
