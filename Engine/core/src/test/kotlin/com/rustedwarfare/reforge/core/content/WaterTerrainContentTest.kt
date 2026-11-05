package com.rustedwarfare.reforge.core.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WaterTerrainContentTest {
    @Test
    fun deferred_water_grid_keeps_owned_tile_semantics_and_obstacles_after_caller_mutation() {
        val image = tilesetImagePath("p001-ground")
        val sets = mutableListOf(TileSetDefinition(1, "water", 20, 20, image, emptySet(),
            40, 5, waterTileIds = setOf(0)))
        val obstacles = mutableSetOf(0 to 0)
        val gids = intArrayOf(1, 1)
        val map = MapDefinition(2, 1, 20, 20, sets, gids,
            BlockingGrid(2, 1, booleanArrayOf(true, true)), obstacles)
        sets.clear()
        obstacles.clear()
        gids.fill(0)
        assertEquals(listOf(true, false), (0..1).map { map.waterBlocking.isBlocked(it, 0) })
        assertEquals(listOf(1, 1), map.groundGids.toList())
    }

    @Test
    fun water_passability_keeps_dry_land_zero_gid_and_combined_cliffs_blocked() {
        val image = tilesetImagePath("p001-ground").toString().replace("&", "&amp;")
        withMap("""<map orientation="orthogonal" renderorder="right-down" width="4" height="2" tilewidth="20" tileheight="20">
            <tileset firstgid="1" name="water-passability" tilewidth="20" tileheight="20">
              <image source="$image" width="100" height="160"/>
              <tile id="0"><properties><property name="water" value=""/></properties></tile>
              <tile id="1"><properties><property name="water" value="false"/></properties></tile>
              <tile id="2"><properties><property name="water-bridge" value=""/></properties></tile>
              <tile id="3"><properties><property name="water" value=""/><property name="water-bridge" value=""/></properties></tile>
              <tile id="4"><properties><property name="cliff" value=""/></properties></tile>
              <tile id="6"><properties><property name="water" value=""/><property name="cliff" value=""/></properties></tile>
            </tileset>
            <layer name="Ground" width="4" height="2"><data encoding="csv">1,2,3,4,5,6,7,0</data></layer>
            </map>""") { path ->
            val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(path)).value
            assertEquals(listOf(false, false, false, false, true, true, true, true),
                (0 until 8).map { map.waterBlocking.isBlocked(it % 4, it / 4) })
            assertEquals(listOf(true, true, false, true, true, false, true, true),
                (0 until 8).map { map.blocking.isBlocked(it % 4, it / 4) })
            assertEquals(4, map.waterBlocking.blockedCount)
        }
    }

    @Test
    fun water_and_bridge_properties_are_present_even_with_empty_or_false_values() {
        val image = tilesetImagePath("p001-ground").toString().replace("&", "&amp;")
        withMap("""<map orientation="orthogonal" renderorder="right-down" width="3" height="2" tilewidth="20" tileheight="20">
            <tileset firstgid="1" name="water-boundaries" tilewidth="20" tileheight="20">
              <image source="$image" width="100" height="160"/>
              <tile id="0"><properties><property name="water" value=""/></properties></tile>
              <tile id="1"><properties><property name="water" value="false"/></properties></tile>
              <tile id="2"><properties><property name="water-bridge" value=""/></properties></tile>
              <tile id="3"><properties><property name="water" value=""/><property name="water-bridge" value=""/></properties></tile>
              <tile id="4"><properties><property name="cliff" value=""/></properties></tile>
            </tileset>
            <layer name="Ground" width="3" height="2"><data encoding="csv">1,2,3,4,5,6</data></layer>
            </map>""") { path ->
            val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(path)).value
            assertEquals(listOf(true, true, false, true, true, false),
                (0 until 6).map { map.blocking.isBlocked(it % 3, it / 3) })
            assertEquals(4, map.blocking.blockedCount)
        }
    }
}
