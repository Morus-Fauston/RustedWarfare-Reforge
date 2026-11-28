package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.util.Collections

/** Normalized visual layer kind drawn in fixed bottom-to-top order on the client. */
enum class MapVisualLayerKind {
    GROUND,
    GROUND_DETAILS,
    GROUND_DETAILS_2,
    ITEMS,
}

/** Immutable visual tile layer; cell values preserve Tiled's top 3 flip/rotate bits. */
class MapVisualLayer(
    val name: String,
    val kind: MapVisualLayerKind,
    rawGids: IntArray,
) {
    private val gids = rawGids.copyOf()
    val rawGids: IntArray get() = gids.copyOf()

    fun rawGidAt(index: Int): Int = gids[index]

    fun cleanGidAt(index: Int): Int = GroundRules.stripFlipFlags(gids[index])

    fun flipFlagsAt(index: Int): Int = GroundRules.flipFlags(gids[index])
}

/** Unit metadata declared on a tileset tile (e.g. units.tsx or misc.tsx). */
data class TileUnitMetadata(
    val unitType: String,
    val team: String? = null,
    val variantType: String? = null,
    val isCustomUnit: Boolean = false,
)

/** Immutable map-declared starting unit extracted from Ground / Items / Units layers. */
data class MapStartingUnit(
    val unitType: String,
    val team: String?,
    val col: Int,
    val row: Int,
    val worldX: Double,
    val worldY: Double,
    val variantType: String? = null,
    val isCustomUnit: Boolean = false,
)

/** Basic metadata parsed from <objectgroup name="Triggers"> map_info objects. */
class MapTriggerInfo(
    val mapType: String? = null,
    val fog: String? = null,
    properties: Map<String, String> = emptyMap(),
    ignoredObjectCount: Int = 0,
) {
    val properties: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(properties))
    val ignoredObjectCount: Int = ignoredObjectCount.coerceAtLeast(0)
}

class MapDefinition(
    val widthTiles: Int,
    val heightTiles: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    tileSets: List<TileSetDefinition>,
    groundGids: IntArray,
    val blocking: BlockingGrid,
    waterObstacles: Set<Pair<Int, Int>> = emptySet(),
    visualLayers: List<MapVisualLayer> = emptyList(),
    waterBlockingOverride: BlockingGrid? = null,
    hoverBlockingOverride: BlockingGrid? = null,
    overCliffBlockingOverride: BlockingGrid? = null,
    overCliffWaterBlockingOverride: BlockingGrid? = null,
    resourcePoolsOverride: BooleanArray? = null,
    buildingBlockedOverride: BooleanArray? = null,
    waterCellsOverride: BooleanArray? = null,
    nativeLiquidCellsOverride: BooleanArray? = null,
    cliffCellsOverride: BooleanArray? = null,
    startingUnits: List<MapStartingUnit> = emptyList(),
    val triggerInfo: MapTriggerInfo = MapTriggerInfo(),
    ignoredDiagnostics: List<String> = emptyList(),
    mapProperties: Map<String, String> = emptyMap(),
    val backgroundColor: String? = null,
) {
    private val cellCount: Long = widthTiles.toLong() * heightTiles.toLong()

    init {
        for (layer in visualLayers) {
            require(layer.rawGids.size.toLong() == cellCount) {
                "Visual layer '${layer.name}' cell count does not match map"
            }
        }
        for (grid in listOfNotNull(waterBlockingOverride, hoverBlockingOverride, overCliffBlockingOverride, overCliffWaterBlockingOverride)) {
            require(grid.widthTiles == widthTiles && grid.heightTiles == heightTiles) {
                "Override BlockingGrid dimensions must match map"
            }
        }
    }

    val tileSets: List<TileSetDefinition> = Collections.unmodifiableList(ArrayList(tileSets))
    private val gids = IntArray(groundGids.size) { GroundRules.stripFlipFlags(groundGids[it]) }
    val groundGids: IntArray get() = gids.copyOf()
    val visualLayers: List<MapVisualLayer> = Collections.unmodifiableList(
        if (visualLayers.isNotEmpty()) ArrayList(visualLayers)
        else listOf(MapVisualLayer("Ground", MapVisualLayerKind.GROUND, groundGids))
    )
    val startingUnits: List<MapStartingUnit> = Collections.unmodifiableList(ArrayList(startingUnits))
    val ignoredDiagnostics: List<String> = Collections.unmodifiableList(ArrayList(ignoredDiagnostics))
    val mapProperties: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(mapProperties))

    private val resourcePools: BooleanArray? = resourcePoolsOverride?.copyOf()?.also {
        require(it.size.toLong() == cellCount) { "resourcePoolsOverride size must match map" }
    }
    private val buildingBlockedCells: BooleanArray? = buildingBlockedOverride?.copyOf()?.also {
        require(it.size.toLong() == cellCount) { "buildingBlockedOverride size must match map" }
    }
    private val waterCells: BooleanArray? = waterCellsOverride?.copyOf()?.also {
        require(it.size.toLong() == cellCount) { "waterCellsOverride size must match map" }
    }
    private val nativeLiquidCells: BooleanArray? = nativeLiquidCellsOverride?.copyOf()?.also {
        require(it.size.toLong() == cellCount) { "nativeLiquidCellsOverride size must match map" }
    }
    private val cliffCells: BooleanArray? = cliffCellsOverride?.copyOf()?.also {
        require(it.size.toLong() == cellCount) { "cliffCellsOverride size must match map" }
    }

    private val staticWaterOverride: BlockingGrid? = waterBlockingOverride
    private val staticHoverOverride: BlockingGrid? = hoverBlockingOverride
    private val staticOverCliffOverride: BlockingGrid? = overCliffBlockingOverride
    private val staticOverCliffWaterOverride: BlockingGrid? = overCliffWaterBlockingOverride

    private var dynamicBaseMap: MapDefinition? = null
    private var dynamicAddedCells: Set<Pair<Int, Int>> = emptySet()

    /** Immutable dynamic cost already composed into LAND's [blocking] by the host adapter. */
    internal val waterObstacles: Set<Pair<Int, Int>> = Collections.unmodifiableSet(LinkedHashSet(waterObstacles))

    /**
     * Returns an immutable copy adding live building / dynamic obstacles while preserving all multilayer
     * overrides and delegating lazy movement grids to O(changed-cells) array updates instead of rescanning
     * all tiles and boxing `(col to row)` pairs.
     */
    fun withDynamicObstacles(extraObstacles: Set<Pair<Int, Int>>): MapDefinition {
        if (extraObstacles.isEmpty()) return this
        val combinedLand = blocking.withExtraBlocked(extraObstacles)
        val combinedWaterObs = if (this.waterObstacles.isEmpty()) extraObstacles else (this.waterObstacles + extraObstacles)
        return MapDefinition(
            widthTiles = widthTiles,
            heightTiles = heightTiles,
            tileWidth = tileWidth,
            tileHeight = tileHeight,
            tileSets = tileSets,
            groundGids = gids,
            blocking = combinedLand,
            waterObstacles = combinedWaterObs,
            visualLayers = visualLayers,
            waterBlockingOverride = staticWaterOverride,
            hoverBlockingOverride = staticHoverOverride,
            overCliffBlockingOverride = staticOverCliffOverride,
            overCliffWaterBlockingOverride = staticOverCliffWaterOverride,
            resourcePoolsOverride = resourcePools,
            buildingBlockedOverride = buildingBlockedCells,
            waterCellsOverride = waterCells,
            nativeLiquidCellsOverride = nativeLiquidCells,
            cliffCellsOverride = cliffCells,
            startingUnits = startingUnits,
            triggerInfo = triggerInfo,
            ignoredDiagnostics = ignoredDiagnostics,
            mapProperties = mapProperties,
            backgroundColor = backgroundColor,
        ).also { derived ->
            derived.dynamicBaseMap = this
            derived.dynamicAddedCells = extraObstacles
        }
    }

    /** Separate immutable passability; ordinary land blocking remains the existing [blocking]. */
    val waterBlocking: BlockingGrid by lazy {
        val base = dynamicBaseMap
        if (base != null) {
            base.waterBlocking.withExtraBlocked(dynamicAddedCells)
        } else if (staticWaterOverride != null && this.waterObstacles.isEmpty()) staticWaterOverride
        else BlockingGrid(widthTiles, heightTiles, BooleanArray(widthTiles * heightTiles) { index ->
            val col = index % widthTiles
            val row = index / widthTiles
            (col to row) in this.waterObstacles ||
                (staticWaterOverride?.isBlocked(col, row) ?: this.tileSets.waterBlocked(gids.getOrNull(index) ?: 0))
        })
    }

    val hoverBlocking: BlockingGrid by lazy {
        val base = dynamicBaseMap
        if (base != null) {
            base.hoverBlocking.withExtraBlocked(dynamicAddedCells)
        } else if (staticHoverOverride != null && this.waterObstacles.isEmpty()) staticHoverOverride
        else BlockingGrid(widthTiles, heightTiles, BooleanArray(widthTiles * heightTiles) { index ->
            val col = index % widthTiles
            val row = index / widthTiles
            (col to row) in this.waterObstacles ||
                (staticHoverOverride?.isBlocked(col, row)
                    ?: GroundRules.blocked(this.tileSets, gids.getOrNull(index) ?: 0, MovementType.HOVER))
        })
    }

    val overCliffBlocking: BlockingGrid by lazy {
        val base = dynamicBaseMap
        if (base != null) {
            base.overCliffBlocking.withExtraBlocked(dynamicAddedCells)
        } else if (staticOverCliffOverride != null && this.waterObstacles.isEmpty()) staticOverCliffOverride
        else BlockingGrid(widthTiles, heightTiles, BooleanArray(widthTiles * heightTiles) { index ->
            val col = index % widthTiles
            val row = index / widthTiles
            (col to row) in this.waterObstacles ||
                (staticOverCliffOverride?.isBlocked(col, row)
                    ?: GroundRules.blocked(this.tileSets, gids.getOrNull(index) ?: 0, MovementType.OVER_CLIFF))
        })
    }

    val overCliffWaterBlocking: BlockingGrid by lazy {
        val base = dynamicBaseMap
        if (base != null) {
            base.overCliffWaterBlocking.withExtraBlocked(dynamicAddedCells)
        } else if (staticOverCliffWaterOverride != null && this.waterObstacles.isEmpty()) staticOverCliffWaterOverride
        else BlockingGrid(widthTiles, heightTiles, BooleanArray(widthTiles * heightTiles) { index ->
            val col = index % widthTiles
            val row = index / widthTiles
            (col to row) in this.waterObstacles ||
                (staticOverCliffWaterOverride?.isBlocked(col, row)
                    ?: GroundRules.blocked(this.tileSets, gids.getOrNull(index) ?: 0, MovementType.OVER_CLIFF_WATER))
        })
    }

    internal fun blockingFor(type: MovementType): BlockingGrid = when (type) {
        MovementType.OVER_CLIFF -> overCliffBlocking
        MovementType.OVER_CLIFF_WATER -> overCliffWaterBlocking
        MovementType.WATER -> waterBlocking
        MovementType.HOVER -> hoverBlocking
        else -> blocking
    }

    /** Authoritative ordinary foundation cell; resource-required buildings check their center separately. */
    fun isFoundationBlocked(col: Int, row: Int, type: MovementType): Boolean {
        if (col !in 0 until widthTiles || row !in 0 until heightTiles) return true
        val index = row * widthTiles + col
        val blockBuildings = buildingBlockedCells?.get(index)
            ?: GroundRules.buildingBlocked(tileSets, gids.getOrNull(index) ?: 0)
        return blockBuildings ||
            (if (type == MovementType.AIR) false else blockingFor(type).isBlocked(col, row)) ||
            isResourcePool(col, row)
    }

    /** Current liquid truth for the finite water content; bridge presence alone is not liquid. */
    internal fun isWater(col: Int, row: Int): Boolean {
        require(col in 0 until widthTiles && row in 0 until heightTiles)
        val index = row * widthTiles + col
        waterCells?.let { return it[index] }
        return GroundRules.liquid(tileSets, gids[index])
    }

    /** Native cK includes water/lava properties and the static cJ passability difference. */
    internal fun isLiquid(col: Int, row: Int): Boolean {
        if (col !in 0 until widthTiles || row !in 0 until heightTiles) return false
        val index = row * widthTiles + col
        val baseNativeLiquid = nativeLiquidCells?.get(index) ?: GroundRules.nativeLiquid(tileSets, gids[index])
        return baseNativeLiquid || isOverWater(col, row)
    }

    /** Native cJ: static OVER_CLIFF blocked minus WATER blocked; excludes dynamic obstacles. */
    internal fun isOverWater(col: Int, row: Int): Boolean {
        if (col !in 0 until widthTiles || row !in 0 until heightTiles) return true
        val index = row * widthTiles + col
        val staticOverCliff = staticOverCliffOverride?.isBlocked(col, row)
            ?: GroundRules.blocked(tileSets, gids[index], MovementType.OVER_CLIFF)
        val staticWater = staticWaterOverride?.isBlocked(col, row)
            ?: GroundRules.blocked(tileSets, gids[index], MovementType.WATER)
        return staticOverCliff && !staticWater
    }

    internal fun isCliff(col: Int, row: Int): Boolean {
        if (col !in 0 until widthTiles || row !in 0 until heightTiles) return true
        val index = row * widthTiles + col
        cliffCells?.let { return it[index] }
        return GroundRules.cliff(tileSets, gids[index])
    }

    fun isResourcePool(col: Int, row: Int): Boolean {
        require(col in 0 until widthTiles && row in 0 until heightTiles)
        resourcePools?.let { return it[row * widthTiles + col] }
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
    buildingBlockedTileIds: Set<Int> = emptySet(),
    treeTileIds: Set<Int> = emptySet(),
    unitTiles: Map<Int, TileUnitMetadata> = emptyMap(),
    val imageSource: String = imagePath.fileName?.toString() ?: "",
    val tsxSource: String? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
) {
    val blockedTileIds: Set<Int> = Collections.unmodifiableSet(LinkedHashSet(blockedTileIds))
    val resourcePoolTileIds: Set<Int> = Collections.unmodifiableSet(LinkedHashSet(resourcePoolTileIds))
    val waterTileIds: Set<Int> = Collections.unmodifiableSet(LinkedHashSet(waterTileIds))
    val waterBridgeTileIds: Set<Int> = Collections.unmodifiableSet(LinkedHashSet(waterBridgeTileIds))
    val hoverBlockedTileIds: Set<Int> = Collections.unmodifiableSet(LinkedHashSet(hoverBlockedTileIds))
    val overCliffBlockedTileIds: Set<Int> = Collections.unmodifiableSet(LinkedHashSet(overCliffBlockedTileIds))
    val lavaTileIds: Set<Int> = Collections.unmodifiableSet(LinkedHashSet(lavaTileIds))
    val buildingBlockedTileIds: Set<Int> = Collections.unmodifiableSet(LinkedHashSet(buildingBlockedTileIds))
    val treeTileIds: Set<Int> = Collections.unmodifiableSet(LinkedHashSet(treeTileIds))
    val unitTiles: Map<Int, TileUnitMetadata> = Collections.unmodifiableMap(LinkedHashMap(unitTiles))

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
    private val blockedIndex = com.rustedwarfare.reforge.core.navigation.BlockedCellIndex(widthTiles, cells)
    val blockedCount: Int = blockedIndex.count

    internal fun anyBlocked(firstColumn: Int, lastColumn: Int, firstRow: Int, lastRow: Int,
                            predicate: (Int, Int) -> Boolean): Boolean =
        blockedIndex.any(firstColumn, lastColumn, firstRow, lastRow, predicate)

    internal fun anyBlockedPrim(firstColumn: Int, lastColumn: Int, firstRow: Int, lastRow: Int,
                                predicate: com.rustedwarfare.reforge.core.navigation.IntCellPredicate): Boolean =
        blockedIndex.anyPrim(firstColumn, lastColumn, firstRow, lastRow, predicate)

    internal fun withExtraBlocked(extraCells: Set<Pair<Int, Int>>): BlockingGrid {
        if (extraCells.isEmpty()) return this
        var anyAdded = false
        for ((c, r) in extraCells) {
            if (c in 0 until widthTiles && r in 0 until heightTiles && !cells[r * widthTiles + c]) {
                anyAdded = true
                break
            }
        }
        if (!anyAdded) return this
        val next = cells.copyOf()
        for ((c, r) in extraCells) {
            if (c in 0 until widthTiles && r in 0 until heightTiles) {
                next[r * widthTiles + c] = true
            }
        }
        return BlockingGrid(widthTiles, heightTiles, next)
    }

    fun isBlocked(col: Int, row: Int): Boolean {
        if (col !in 0 until widthTiles || row !in 0 until heightTiles) {
            throw IndexOutOfBoundsException("Cell ($col, $row) outside $widthTiles x $heightTiles")
        }
        return cells[row * widthTiles + col]
    }
}