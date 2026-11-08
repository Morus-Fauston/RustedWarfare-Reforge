package com.rustedwarfare.reforge.core.content

/** Public tile meanings only. No map coordinates, world or live knowledge can be queried here. */
object GroundRules {
    private fun owner(tileSets: List<TileSetDefinition>, gid: Int): TileSetDefinition? =
        if (gid == 0) null else tileSets.firstOrNull {
            gid.toLong() >= it.firstGid && gid.toLong() < it.firstGid.toLong() + it.tileCount
        }

    fun owns(tileSets: List<TileSetDefinition>, gid: Int): Boolean = owner(tileSets, gid) != null

    fun blocked(tileSets: List<TileSetDefinition>, gid: Int, type: MovementType): Boolean {
        val tileSet = owner(tileSets, gid) ?: return true
        val local = gid - tileSet.firstGid
        if (type == MovementType.HOVER) return local in tileSet.hoverBlockedTileIds
        return local in tileSet.blockedTileIds || if (type == MovementType.WATER)
            local !in tileSet.waterTileIds && local !in tileSet.waterBridgeTileIds
        else local in tileSet.waterTileIds
    }

    fun liquid(tileSets: List<TileSetDefinition>, gid: Int): Boolean {
        val tileSet = owner(tileSets, gid) ?: return false
        return gid - tileSet.firstGid in tileSet.waterTileIds
    }

    fun resourcePool(tileSets: List<TileSetDefinition>, gid: Int): Boolean {
        val tileSet = owner(tileSets, gid) ?: return false
        return gid - tileSet.firstGid in tileSet.resourcePoolTileIds
    }
}
