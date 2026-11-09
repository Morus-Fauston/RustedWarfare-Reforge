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
    waterObstacles: Set<Pair<Int, Int>> = emptySet(),
) {
    val tileSets: List<TileSetDefinition> = Collections.unmodifiableList(ArrayList(tileSets))
    private val gids = groundGids.copyOf()
    val groundGids: IntArray get() = gids.copyOf()
    /** Immutable dynamic cost already composed into LAND's [blocking] by the host adapter. */
    internal val waterObstacles: Set<Pair<Int, Int>> = Collections.unmodifiableSet(LinkedHashSet(waterObstacles))
    /** Separate immutable passability; ordinary land blocking remains the existing [blocking]. */
    val waterBlocking: BlockingGrid by lazy {
        BlockingGrid(widthTiles, heightTiles, BooleanArray(widthTiles * heightTiles) { index ->
            (index % widthTiles to index / widthTiles) in this.waterObstacles || this.tileSets.waterBlocked(gids.getOrNull(index) ?: 0)
        })
    }

    val hoverBlocking: BlockingGrid by lazy {
        BlockingGrid(widthTiles, heightTiles, BooleanArray(widthTiles * heightTiles) { index ->
            (index % widthTiles to index / widthTiles) in this.waterObstacles ||
                GroundRules.blocked(this.tileSets, gids.getOrNull(index) ?: 0, MovementType.HOVER)
        })
    }

    val overCliffBlocking: BlockingGrid by lazy {
        BlockingGrid(widthTiles, heightTiles, BooleanArray(widthTiles * heightTiles) { index ->
            (index % widthTiles to index / widthTiles) in this.waterObstacles ||
                GroundRules.blocked(this.tileSets, gids.getOrNull(index) ?: 0, MovementType.OVER_CLIFF)
        })
    }

    internal fun blockingFor(type: MovementType): BlockingGrid = when (type) {
        MovementType.OVER_CLIFF -> overCliffBlocking
        MovementType.WATER -> waterBlocking
        MovementType.HOVER -> hoverBlocking
        else -> blocking
    }

    /** Authoritative ordinary foundation cell; resource-required buildings check their center separately. */
    fun isFoundationBlocked(col: Int, row: Int, type: MovementType): Boolean =
        blockingFor(type).isBlocked(col, row) || isResourcePool(col, row)

    /** Current liquid truth for the finite water content; bridge presence alone is not liquid. */
    internal fun isWater(col: Int, row: Int): Boolean {
        require(col in 0 until widthTiles && row in 0 until heightTiles)
        val gid = gids[row * widthTiles + col]
        return GroundRules.liquid(tileSets, gid)
    }

    /** Native cK includes water/lava properties and the static cJ passability difference. */
    internal fun isLiquid(col: Int, row: Int): Boolean {
        if (col !in 0 until widthTiles || row !in 0 until heightTiles) return false
        val gid = gids[row * widthTiles + col]
        return GroundRules.nativeLiquid(tileSets, gid) || isOverWater(col, row)
    }

    /** Native cJ: static OVER_CLIFF blocked minus WATER blocked; excludes dynamic obstacles. */
    internal fun isOverWater(col: Int, row: Int): Boolean {
        if (col !in 0 until widthTiles || row !in 0 until heightTiles) return true
        val gid = gids[row * widthTiles + col]
        return GroundRules.blocked(tileSets, gid, MovementType.OVER_CLIFF) &&
            !GroundRules.blocked(tileSets, gid, MovementType.WATER)
    }

    internal fun isCliff(col: Int, row: Int): Boolean {
        if (col !in 0 until widthTiles || row !in 0 until heightTiles) return true
        return GroundRules.cliff(tileSets, gids[row * widthTiles + col])
    }

    fun isResourcePool(col: Int, row: Int): Boolean {
        require(col in 0 until widthTiles && row in 0 until heightTiles)
        val gid = gids[row * widthTiles + col]
        return GroundRules.resourcePool(tileSets, gid)
    }
}

/** GID semantics only: it cannot query a position, world or hidden current ground. */
fun List<TileSetDefinition>.waterBlocked(gid: Int): Boolean = GroundRules.blocked(this, gid, MovementType.WATER)

class TileSetDefinition internal constructor(
    val firstGid: Int,
    val name: String,
    val tileWidth: Int,
    val tileHeight: Int,
    val imagePath: Path,
    blockedTileIds: Set<Int>,
    val tileCount: Int,
    val columns: Int,
    val spacing: Int = 0,
    val margin: Int = 0,
    val transparentColor: String? = null,
    resourcePoolTileIds: Set<Int> = emptySet(),
    waterTileIds: Set<Int> = emptySet(),
    waterBridgeTileIds: Set<Int> = emptySet(),
    hoverBlockedTileIds: Set<Int> = blockedTileIds,
    overCliffBlockedTileIds: Set<Int> = blockedTileIds,
    lavaTileIds: Set<Int> = emptySet(),
) {
    val blockedTileIds: Set<Int> = Collections.unmodifiableSet(LinkedHashSet(blockedTileIds))
    val resourcePoolTileIds: Set<Int> = Collections.unmodifiableSet(LinkedHashSet(resourcePoolTileIds))
    val waterTileIds: Set<Int> = Collections.unmodifiableSet(LinkedHashSet(waterTileIds))
    val waterBridgeTileIds: Set<Int> = Collections.unmodifiableSet(LinkedHashSet(waterBridgeTileIds))
    val hoverBlockedTileIds: Set<Int> = Collections.unmodifiableSet(LinkedHashSet(hoverBlockedTileIds))
    val overCliffBlockedTileIds: Set<Int> = Collections.unmodifiableSet(LinkedHashSet(overCliffBlockedTileIds))
    val lavaTileIds: Set<Int> = Collections.unmodifiableSet(LinkedHashSet(lavaTileIds))

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
    private val blockedIndex = com.rustedwarfare.reforge.core.path.BlockedCellIndex(widthTiles, cells)
    val blockedCount: Int = blockedIndex.count

    internal fun anyBlocked(firstColumn: Int, lastColumn: Int, firstRow: Int, lastRow: Int,
                            predicate: (Int, Int) -> Boolean): Boolean =
        blockedIndex.any(firstColumn, lastColumn, firstRow, lastRow, predicate)

    fun isBlocked(col: Int, row: Int): Boolean {
        if (col !in 0 until widthTiles || row !in 0 until heightTiles) {
            throw IndexOutOfBoundsException("Cell ($col, $row) outside $widthTiles x $heightTiles")
        }
        return cells[row * widthTiles + col]
    }
}
