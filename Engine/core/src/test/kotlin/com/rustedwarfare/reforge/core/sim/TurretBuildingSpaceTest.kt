package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.rustedwarfare.reforge.core.content.BuildingFootprint
import com.rustedwarfare.reforge.core.content.FootprintAnchor
import com.rustedwarfare.reforge.core.knowledge.*

class TurretBuildingSpaceTest {
    @Test
    fun trusted_anchors_shapes_and_whole_circle_bounds_cannot_bypass_initialization() {
        val base = TurretTestContent.scenario()
        val tower = base.unitDefinitions.getValue("c_turret_t1")
        val building = tower.building!!
        for (bad in listOf(building.copy(anchor = FootprintAnchor(Float.NaN, 20f)),
            building.copy(anchor = FootprintAnchor(20f, Float.POSITIVE_INFINITY)),
            building.copy(footprint = BuildingFootprint(1, 0, 1, 1)))) {
            val definition = TurretTestContent.variant(tower, building = bad)
            assertEquals("unsupported-building", assertIs<InitResult.Rejected>(SimulationContext.initialize(
                base.copy(unitDefinitions = base.unitDefinitions + (definition.id to definition)))).error.code)
        }
        for (bad in listOf(building.copy(anchor = FootprintAnchor(-Float.MAX_VALUE, 20f)),
            building.copy(footprint = BuildingFootprint(Int.MIN_VALUE, 0, Int.MAX_VALUE, 1)))) {
            val definition = TurretTestContent.variant(tower, building = bad)
            assertEquals("invalid-spawn", assertIs<InitResult.Rejected>(SimulationContext.initialize(
                base.copy(unitDefinitions = base.unitDefinitions + (definition.id to definition)))).error.code)
        }
        for ((position, accepted) in listOf(Position(16.0, 16.0) to true,
            Position(584.0, 384.0) to true, Position(15.999, 100.0) to false,
            Position(584.001, 100.0) to false, Position(100.0, 384.001) to false,
            Position(Double.NaN, 100.0) to false)) {
            val result = SimulationContext.initialize(base.copy(units = base.units.map { it.copy(position = position) }))
            if (accepted) assertIs<InitResult.Accepted>(result).context.close()
            else assertEquals("invalid-spawn", assertIs<InitResult.Rejected>(result, "$position").error.code)
        }
    }

    @Test
    fun discovery_and_loss_use_the_same_custom_cells_for_owner_paths_in_both_retention_modes() {
        for (retention in TerrainRetention.entries) {
            val base = TurretTestContent.scenario(Position(310.0, 210.0))
            val definitions = base.unitDefinitions.mapValues { (_, unit) -> TurretTestContent.variant(unit, sight = 4, combat = null) }
            val scene = base.copy(unitDefinitions = definitions, players = base.players.map { it.copy(teamId = "allied") },
                units = listOf(UnitSpawn("a", "p1", "c_tank", Position(110.0, 210.0), 90.0),
                    UnitSpawn("t", "p2", "c_turret_t1", Position(310.0, 210.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "t" to 11L), 12L),
                automaticVision = KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, false))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                assertTrue(c.observePlayer(c.world, "p1").units.none { it.unitId == "t" })
                assertIs<ActionResult.Accepted>(c.submit(MoveAction("discover", "p1", "a", Position(510.0, 210.0), c.world)))
                assertTrue(c.observe().units.single { it.unitId == "a" }.pathPoints.all { it.y == 210.0 })
                var seen = false
                var next = 0L
                repeat(550) {
                    val frame = c.advance(Step(next++, 1.0, "sim-tick"))
                    val a = frame.units.single { it.unitId == "a" }
                    assertTrue(a.position.x !in 280.0..<320.0 || a.position.y !in 180.0..<220.0)
                    val view = c.observePlayer(c.world, "p1")
                    if (view.units.any { it.unitId == "t" }) {
                        seen = true
                        assertEquals(false, view.knowledge!!.cell(15, 10).lastKnownBlocked)
                    }
                }
                assertTrue(seen)
                assertTrue(c.observe().units.single { it.unitId == "a" }.position.x > 350.0)
                assertIs<ActionResult.Accepted>(c.submit(MoveAction("return", "p1", "a", Position(110.0, 210.0), c.world)))
                repeat(650) { c.advance(Step(next++, 1.0, "sim-tick")) }
                assertTrue(c.observePlayer(c.world, "p1").units.none { it.unitId == "t" })
                assertIs<ActionResult.Accepted>(c.submit(MoveAction("after-loss", "p1", "a", Position(510.0, 210.0), c.world)))
                assertTrue(c.observe().units.single { it.unitId == "a" }.pathPoints.all { it.y == 210.0 },
                    "Unseen custom buildings are not remembered hard terrain")
            } finally { c.close() }
        }
    }

    @Test
    fun real_initialization_uses_the_float_custom_anchor_and_preserves_neighboring_clear_cells() {
        val cases = listOf(
            Position(320.0, 220.0) to setOf(15 to 10, 16 to 10, 15 to 11, 16 to 11),
            Position(318.99999, 220.0) to setOf(15 to 10, 16 to 10, 15 to 11, 16 to 11),
            Position(18.0, 100.0) to setOf(0 to 4, 1 to 4, 0 to 5, 1 to 5))
        for ((position, occupied) in cases) {
            val base = TurretTestContent.scenario(position)
            for (cell in occupied + setOf(3 to 3, 20 to 15, 29 to 19)) {
                val map = base.map
                val changed = MapDefinition(map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight,
                    map.tileSets, map.groundGids, BlockingGrid(map.widthTiles, map.heightTiles,
                        BooleanArray(map.widthTiles * map.heightTiles) {
                            it % map.widthTiles == cell.first && it / map.widthTiles == cell.second }))
                val initialized = SimulationContext.initialize(base.copy(map = changed))
                if (cell in occupied) assertEquals("invalid-spawn", assertIs<InitResult.Rejected>(initialized,
                    "center=$position hard=$cell must collide with a real footprint").error.code)
                else assertIs<InitResult.Accepted>(initialized).context.close()
            }
        }
    }
}
