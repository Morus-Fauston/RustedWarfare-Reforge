package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class GroundRulesTest {
    @Test fun movement_liquid_and_resource_meanings_use_owned_gids_without_conflating_bridges() {
        val sets = listOf(TileSetDefinition(1, "shore", 20, 20, Path.of("unused.png"), setOf(3),
            5, 5, waterTileIds = setOf(1, 3), waterBridgeTileIds = setOf(2), resourcePoolTileIds = setOf(4)),
            TileSetDefinition(20, "second", 20, 20, Path.of("unused.png"), emptySet(),
                1, 1, waterTileIds = setOf(0)))
        // dry, liquid, bridge, liquid cliff, pool, absent, gap, second tileset, after its end
        val gids = listOf(1, 2, 3, 4, 5, 0, 10, 20, 21)
        assertEquals(listOf(false, true, false, true, false, true, true, true, true),
            gids.map { GroundRules.blocked(sets, it, MovementType.LAND) })
        assertEquals(listOf(true, false, false, true, true, true, true, false, true),
            gids.map { GroundRules.blocked(sets, it, MovementType.WATER) })
        assertEquals(listOf(false, true, false, true, false, false, false, true, false),
            gids.map { GroundRules.liquid(sets, it) })
        assertEquals(listOf(false, false, false, false, true, false, false, false, false),
            gids.map { GroundRules.resourcePool(sets, it) })
    }

    @Test fun foundation_cells_add_pool_and_dynamic_cost_to_the_same_land_water_meanings() {
        val tiles = TileSetDefinition(1, "shore", 20, 20, Path.of("unused.png"), emptySet(),
            5, 5, waterTileIds = setOf(1), waterBridgeTileIds = setOf(2), resourcePoolTileIds = setOf(3))
        val map = MapDefinition(5, 1, 20, 20, listOf(tiles), intArrayOf(1, 2, 3, 4, 3),
            BlockingGrid(5, 1, booleanArrayOf(false, true, false, false, true)), setOf(4 to 0))
        assertEquals(listOf(false, true, false, true, true),
            (0..4).map { map.isFoundationBlocked(it, 0, MovementType.LAND) })
        assertEquals(listOf(true, false, false, true, true),
            (0..4).map { map.isFoundationBlocked(it, 0, MovementType.WATER) })
    }
}
