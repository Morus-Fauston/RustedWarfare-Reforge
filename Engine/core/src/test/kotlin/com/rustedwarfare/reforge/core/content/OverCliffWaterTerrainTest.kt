package com.rustedwarfare.reforge.core.content

import com.rustedwarfare.reforge.core.path.PlanningTerrain
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class OverCliffWaterTerrainTest {
    @Test fun remembered_amphibious_ground_uses_authorized_tile_semantics_and_retains_unknown_exploration() {
        val tiles = TileSetDefinition(1, "amphibious", 20, 20, Path.of("unused.png"), setOf(1, 2),
            4, 4, waterTileIds = setOf(3), hoverBlockedTileIds = setOf(2), overCliffBlockedTileIds = setOf(2))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(TestContentFactory.openScenario())).context
        val world = c.world
        c.close()
        for (retention in TerrainRetention.entries) {
            val store = PlayerTerrainKnowledge.create(world, "p1", 5, 1,
                KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, false))
            store.accept(world, listOf(VisibleTerrainCell(0, 0, false, 1), VisibleTerrainCell(1, 0, true, 2),
                VisibleTerrainCell(2, 0, true, 3), VisibleTerrainCell(3, 0, true, 4)))
            val observed = PlanningTerrain.fromKnowledge(store.snapshot(world), 20, 20,
                MovementType.OVER_CLIFF_WATER, listOf(tiles))
            assertEquals(listOf(false, false, true, false, false), (0..4).map { observed.isBlocked(it, 0) })
            store.accept(world, emptyList())
            val hidden = PlanningTerrain.fromKnowledge(store.snapshot(world), 20, 20,
                MovementType.OVER_CLIFF_WATER, listOf(tiles))
            assertEquals(if (retention == TerrainRetention.MEMORY) listOf(false, false, true, false, false)
                else List(5) { false }, (0..4).map { hidden.isBlocked(it, 0) })
            store.close()
            assertEquals(listOf(false, false, true, false, false), (0..4).map { observed.isBlocked(it, 0) })
        }
    }

    @Test fun amphibious_cliff_class_crosses_water_and_large_cliffs_but_keeps_hard_obstacles() {
        val image = tilesetImagePath("p001-ground").toString().replace("&", "&amp;")
        val properties = listOf("", "water", "cliff", "cliff-soft", "large-cliff", "trees",
            "large-rock", "small-rock", "block-land", "lava", "lava-cliff")
        val tiles = properties.mapIndexed { id, property ->
            if (property.isEmpty()) "" else """<tile id="$id"><properties><property name="$property" value=""/></properties></tile>"""
        }.joinToString("\n")
        withMap("""<map orientation="orthogonal" renderorder="right-down" width="12" height="1" tilewidth="20" tileheight="20">
            <tileset firstgid="1" name="cliff-water-ground" tilewidth="20" tileheight="20">
              <image source="$image" width="100" height="160"/>$tiles
            </tileset>
            <layer name="Ground" width="12" height="1"><data encoding="csv">1,2,3,4,5,6,7,8,9,10,11,0</data></layer>
            </map>""") { path ->
            val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(path)).value
            val type = MovementType.valueOf("OVER_CLIFF_WATER")
            // DEC-P008-002 reviewed PC single-property passability table; small rocks are a cost.
            val expected = listOf(false, false, false, false, false, true, true, false, true, true, true, true)
            assertEquals(expected, map.groundGids.map { GroundRules.blocked(map.tileSets, it, type) })
            val terrain = PlanningTerrain.fromNoFog(map, type)
            assertEquals(expected, (0..11).map { terrain.isBlocked(it, 0) })
        }
    }
}
