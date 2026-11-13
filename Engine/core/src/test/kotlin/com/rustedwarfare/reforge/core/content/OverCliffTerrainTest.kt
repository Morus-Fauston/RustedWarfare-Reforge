package com.rustedwarfare.reforge.core.content

import com.rustedwarfare.reforge.core.path.PlanningTerrain
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class OverCliffTerrainTest {
    @Test fun static_cliff_difference_ignores_dynamic_buildings_and_keeps_mixed_hard_properties_blocked() {
        val image = tilesetImagePath("p001-ground").toString().replace("&", "&amp;")
        val properties = listOf(listOf("cliff"), listOf("cliff-soft"), listOf("large-cliff"),
            listOf("large-cliff", "trees"), listOf("large-cliff", "lava"),
            listOf("water-bridge", "large-cliff", "block-land"), listOf("water", "large-cliff"))
        val declarations = properties.mapIndexed { id, names ->
            """<tile id="$id"><properties>""" + names.joinToString("") {
                """<property name="$it" value=""/>"""
            } + "</properties></tile>"
        }.joinToString("\n")
        withMap("""<map orientation="orthogonal" renderorder="right-down" width="7" height="1" tilewidth="20" tileheight="20">
            <tileset firstgid="1" name="mixed-ground" tilewidth="20" tileheight="20">
              <image source="$image" width="100" height="160"/>$declarations
            </tileset>
            <layer name="Ground" width="7" height="1"><data encoding="csv">1,2,3,4,5,6,7</data></layer>
            </map>""") { path ->
            val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(path)).value
            assertEquals(listOf(false, false, true, false, false, false, true),
                map.groundGids.map { GroundRules.cliff(map.tileSets, it) })
            val occupied = MapDefinition(7, 1, 20, 20, map.tileSets, map.groundGids, map.blocking, setOf(0 to 0))
            assertEquals(listOf(true, false, false, true, true, true, true),
                (0..6).map { PlanningTerrain.fromNoFog(occupied, MovementType.OVER_CLIFF).isBlocked(it, 0) })
            assertFalse(GroundRules.cliff(map.tileSets, 0))
            assertFalse(GroundRules.cliff(map.tileSets, 999))
        }
    }

    @Test fun cliff_planning_uses_authorized_gids_without_land_blocking_or_hidden_truth() {
        val tiles = TileSetDefinition(1, "cliff", 20, 20, Path.of("unused.png"), setOf(1, 2),
            4, 4, waterTileIds = setOf(3), hoverBlockedTileIds = setOf(2), overCliffBlockedTileIds = setOf(2))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(TestContentFactory.openScenario())).context
        val world = context.world
        context.close()
        for (retention in TerrainRetention.entries) {
            val store = PlayerTerrainKnowledge.create(world, "p1", 5, 1,
                KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, false))
            store.accept(world, listOf(VisibleTerrainCell(0, 0, false, 1), VisibleTerrainCell(1, 0, true, 2),
                VisibleTerrainCell(2, 0, true, 3), VisibleTerrainCell(3, 0, false, 4)))
            val old = PlanningTerrain.fromKnowledge(store.snapshot(world), 20, 20, MovementType.OVER_CLIFF, listOf(tiles))
            assertEquals(listOf(false, false, true, true, false), (0..4).map { old.isBlocked(it, 0) })
            store.accept(world, emptyList())
            val hidden = PlanningTerrain.fromKnowledge(store.snapshot(world), 20, 20, MovementType.OVER_CLIFF, listOf(tiles))
            assertEquals(if (retention == TerrainRetention.MEMORY) listOf(false, false, true, true, false)
                else List(5) { false }, (0..4).map { hidden.isBlocked(it, 0) })
            store.close()
            assertEquals(listOf(false, false, true, true, false), (0..4).map { old.isBlocked(it, 0) })
        }
    }

    @Test fun loaded_over_cliff_ground_crosses_cliffs_but_keeps_water_and_hard_obstacles_blocked() {
        val image = tilesetImagePath("p001-ground").toString().replace("&", "&amp;")
        val properties = listOf("", "water", "cliff", "cliff-soft", "large-cliff", "trees",
            "large-rock", "small-rock", "block-land", "lava", "lava-cliff")
        val tiles = properties.mapIndexed { id, property ->
            if (property.isEmpty()) "" else """<tile id="$id"><properties><property name="$property" value=""/></properties></tile>"""
        }.joinToString("\n")
        withMap("""<map orientation="orthogonal" renderorder="right-down" width="12" height="1" tilewidth="20" tileheight="20">
            <tileset firstgid="1" name="cliff-ground" tilewidth="20" tileheight="20">
              <image source="$image" width="100" height="160"/>$tiles
            </tileset>
            <layer name="Ground" width="12" height="1"><data encoding="csv">1,2,3,4,5,6,7,8,9,10,11,0</data></layer>
            </map>""") { path ->
            val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(path)).value
            val type = MovementType.valueOf("OVER_CLIFF")
            // Reviewed PC terrain-cost static table, including positive small-rock cost.
            val expected = listOf(false, true, false, false, false, true, true, false, true, true, true, true)
            assertEquals(expected, map.groundGids.map { GroundRules.blocked(map.tileSets, it, type) })
            val terrain = PlanningTerrain.fromNoFog(map, type)
            assertEquals(expected, (0..11).map { terrain.isBlocked(it, 0) })
        }
    }
}
