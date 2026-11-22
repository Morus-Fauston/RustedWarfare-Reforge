package com.rustedwarfare.reforge.core.content

/** Public tile meanings only. No map coordinates, world or live knowledge can be queried here. */
object GroundRules {
    /** Tiled GID flip flags occupy the top 3 bits of an unsigned 32-bit cell GID. */
    const val FLIP_HORIZONTAL_FLAG: Int = Int.MIN_VALUE // 0x80000000
    const val FLIP_VERTICAL_FLAG: Int = 0x40000000
    const val FLIP_DIAGONAL_FLAG: Int = 0x20000000
    const val FLIP_FLAGS_MASK: Int = FLIP_HORIZONTAL_FLAG or FLIP_VERTICAL_FLAG or FLIP_DIAGONAL_FLAG
    const val GID_MASK: Int = 0x1FFFFFFF

    fun stripFlipFlags(rawGid: Int): Int = rawGid and GID_MASK

    fun flipFlags(rawGid: Int): Int = rawGid and FLIP_FLAGS_MASK

    fun owner(tileSets: List<TileSetDefinition>, gid: Int): TileSetDefinition? {
        val clean = stripFlipFlags(gid)
        if (clean == 0) return null
        // Match Tiled / original RW behavior: the last tileset whose firstGid <= clean owns the GID,
        // provided local < tileCount.
        var candidate: TileSetDefinition? = null
        for (tileSet in tileSets) {
            if (clean.toLong() >= tileSet.firstGid.toLong()) {
                if (candidate == null || tileSet.firstGid >= candidate.firstGid) {
                    candidate = tileSet
                }
            }
        }
        val chosen = candidate ?: return null
        return if (clean.toLong() < chosen.firstGid.toLong() + chosen.tileCount.toLong()) chosen else null
    }

    fun owns(tileSets: List<TileSetDefinition>, gid: Int): Boolean = owner(tileSets, gid) != null

    fun blocked(tileSets: List<TileSetDefinition>, gid: Int, type: MovementType): Boolean {
        val clean = stripFlipFlags(gid)
        val tileSet = owner(tileSets, clean) ?: return true
        val local = clean - tileSet.firstGid
        if (type == MovementType.HOVER) return local in tileSet.hoverBlockedTileIds
        if (type == MovementType.OVER_CLIFF) return local in tileSet.overCliffBlockedTileIds || local in tileSet.waterTileIds
        if (type == MovementType.OVER_CLIFF_WATER) return local in tileSet.overCliffBlockedTileIds
        return local in tileSet.blockedTileIds || if (type == MovementType.WATER)
            local !in tileSet.waterTileIds && local !in tileSet.waterBridgeTileIds
        else local in tileSet.waterTileIds
    }

    /** Reviewed static cI difference: HOVER blocked, OVER_CLIFF_WATER not blocked. */
    fun cliff(tileSets: List<TileSetDefinition>, gid: Int): Boolean {
        val clean = stripFlipFlags(gid)
        val tileSet = owner(tileSets, clean) ?: return false
        val local = clean - tileSet.firstGid
        return local in tileSet.hoverBlockedTileIds && local !in tileSet.overCliffBlockedTileIds
    }

    fun liquid(tileSets: List<TileSetDefinition>, gid: Int): Boolean {
        val clean = stripFlipFlags(gid)
        val tileSet = owner(tileSets, clean) ?: return false
        return clean - tileSet.firstGid in tileSet.waterTileIds
    }

    internal fun nativeLiquid(tileSets: List<TileSetDefinition>, gid: Int): Boolean {
        val clean = stripFlipFlags(gid)
        val tileSet = owner(tileSets, clean) ?: return false
        val local = clean - tileSet.firstGid
        return local in tileSet.waterTileIds || local in tileSet.lavaTileIds
    }

    fun resourcePool(tileSets: List<TileSetDefinition>, gid: Int): Boolean {
        val clean = stripFlipFlags(gid)
        val tileSet = owner(tileSets, clean) ?: return false
        return clean - tileSet.firstGid in tileSet.resourcePoolTileIds
    }

    fun buildingBlocked(tileSets: List<TileSetDefinition>, gid: Int): Boolean {
        val clean = stripFlipFlags(gid)
        val tileSet = owner(tileSets, clean) ?: return false
        return clean - tileSet.firstGid in tileSet.buildingBlockedTileIds
    }

    fun waterBridge(tileSets: List<TileSetDefinition>, gid: Int): Boolean {
        val clean = stripFlipFlags(gid)
        val tileSet = owner(tileSets, clean) ?: return false
        return clean - tileSet.firstGid in tileSet.waterBridgeTileIds
    }

    fun unitSpawn(tileSets: List<TileSetDefinition>, gid: Int): TileUnitMetadata? {
        val clean = stripFlipFlags(gid)
        val tileSet = owner(tileSets, clean) ?: return null
        return tileSet.unitTiles[clean - tileSet.firstGid]
    }
}