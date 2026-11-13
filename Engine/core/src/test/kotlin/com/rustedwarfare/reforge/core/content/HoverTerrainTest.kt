package com.rustedwarfare.reforge.core.content

import com.rustedwarfare.reforge.core.path.PlanningTerrain
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HoverTerrainTest {
    @Test fun hover_truth_and_no_fog_planning_keep_dynamic_occupied_cells_blocked_on_water() {
        val tiles = TileSetDefinition(1, "water", 20, 20, Path.of("unused.png"), emptySet(),
            1, 1, waterTileIds = setOf(0))
        val map = MapDefinition(3, 1, 20, 20, listOf(tiles), intArrayOf(1, 1, 1),
            BlockingGrid(3, 1, booleanArrayOf(true, true, true)), setOf(1 to 0))
        assertEquals(listOf(false, true, false), (0..2).map { map.hoverBlocking.isBlocked(it, 0) })
        val planned = PlanningTerrain.fromNoFog(map, MovementType.HOVER)
        assertEquals(listOf(false, true, false), (0..2).map { planned.isBlocked(it, 0) })
    }

    @Test fun hover_planning_uses_only_observed_gids_and_retains_or_forgets_them() {
        val tiles = TileSetDefinition(1, "shore", 20, 20, Path.of("unused.png"), setOf(1, 2, 3),
            5, 5, waterTileIds = setOf(1), hoverBlockedTileIds = setOf(3))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(TestContentFactory.openScenario())).context
        val world = context.world
        context.close()
        for (retention in TerrainRetention.entries) {
            val store = PlayerTerrainKnowledge.create(world, "p1", 5, 1,
                KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, false))
            store.accept(world, listOf(VisibleTerrainCell(0, 0, false, 1), VisibleTerrainCell(1, 0, true, 2),
                VisibleTerrainCell(2, 0, true, 3), VisibleTerrainCell(3, 0, true, 4)))
            val observed = PlanningTerrain.fromKnowledge(store.snapshot(world), 20, 20, MovementType.HOVER, listOf(tiles))
            assertEquals(listOf(false, false, false, true, false), (0..4).map { observed.isBlocked(it, 0) })
            store.accept(world, emptyList())
            val hidden = PlanningTerrain.fromKnowledge(store.snapshot(world), 20, 20, MovementType.HOVER, listOf(tiles))
            assertEquals(if (retention == TerrainRetention.MEMORY) listOf(false, false, false, true, false)
                else List(5) { false }, (0..4).map { hidden.isBlocked(it, 0) })
            // An old authorized snapshot retains its values even after forgetting and store disposal.
            store.close()
            assertEquals(listOf(false, false, false, true, false), (0..4).map { observed.isBlocked(it, 0) })
        }
    }

    @Test fun bridge_does_not_clear_lava_or_land_blocks_and_soft_cliffs_remain_traversable() {
        val image = tilesetImagePath("p001-ground").toString().replace("&", "&amp;")
        withMap("""<map orientation="orthogonal" renderorder="right-down" width="7" height="1" tilewidth="20" tileheight="20">
            <tileset firstgid="1" name="hover-ground" tilewidth="20" tileheight="20">
              <image source="$image" width="100" height="160"/>
              <tile id="1"><properties><property name="cliff-soft" value=""/></properties></tile>
              <tile id="2"><properties><property name="lava" value=""/></properties></tile>
              <tile id="3"><properties><property name="lava-cliff" value=""/></properties></tile>
              <tile id="4"><properties><property name="block-land" value=""/></properties></tile>
              <tile id="5"><properties><property name="water-bridge" value=""/></properties></tile>
              <tile id="6"><properties><property name="water-bridge" value=""/><property name="lava" value=""/></properties></tile>
            </tileset>
            <layer name="Ground" width="7" height="1"><data encoding="csv">1,2,3,4,5,6,7</data></layer>
            </map>""") { path ->
            val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(path)).value
            assertEquals(listOf(false, false, true, true, true, false, true),
                (0..6).map { map.hoverBlocking.isBlocked(it, 0) })
            assertEquals(listOf(false, true, true, true, true, false, true),
                (0..6).map { map.blocking.isBlocked(it, 0) })
        }
    }

    @Test fun loaded_ground_allows_hover_across_water_and_cliffs_but_not_hard_obstacles() {
        val image = tilesetImagePath("p001-ground").toString().replace("&", "&amp;")
        withMap("""<map orientation="orthogonal" renderorder="right-down" width="8" height="1" tilewidth="20" tileheight="20">
            <tileset firstgid="1" name="hover-ground" tilewidth="20" tileheight="20">
              <image source="$image" width="100" height="160"/>
              <tile id="1"><properties><property name="water" value=""/></properties></tile>
              <tile id="2"><properties><property name="cliff" value=""/></properties></tile>
              <tile id="3"><properties><property name="large-cliff" value=""/></properties></tile>
              <tile id="4"><properties><property name="trees" value=""/></properties></tile>
              <tile id="5"><properties><property name="large-rock" value=""/></properties></tile>
              <tile id="6"><properties><property name="small-rock" value=""/></properties></tile>
            </tileset>
            <layer name="Ground" width="8" height="1"><data encoding="csv">1,2,3,4,5,6,7,0</data></layer>
            </map>""") { path ->
            val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(path)).value
            val hover = MovementType.valueOf("HOVER")
            // Fixed PC ground costs: small-rock is positive, never an impassable cell.
            val expected = listOf(false, false, false, true, true, true, false, true)
            assertEquals(expected, map.groundGids.map { GroundRules.blocked(map.tileSets, it, hover) })
            val terrain = PlanningTerrain.fromNoFog(map, hover)
            assertEquals(expected, (0..7).map { terrain.isBlocked(it, 0) })
            assertEquals(listOf(false, true, true, true, true, true, true, true),
                (0..7).map { map.blocking.isBlocked(it, 0) })
        }
    }
}
