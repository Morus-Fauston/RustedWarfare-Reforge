package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.test.*

class BuildingAttackApproachTest {
    private fun scene(targetX: Double, start: Position = Position(100.0, 200.0), wall: Boolean = true,
                      actorRadius: Double = 11.0): Scenario {
        val base = TurretTestContent.scenario(Position(targetX, 200.0))
        val tower = TurretTestContent.variant(base.unitDefinitions.getValue("c_turret_t1"), combat = null)
        val tank = TurretTestContent.variant(base.unitDefinitions.getValue("c_tank"), radius = actorRadius)
        val map = base.map.let { m -> MapDefinition(m.widthTiles, m.heightTiles, m.tileWidth, m.tileHeight,
            m.tileSets, m.groundGids, BlockingGrid(m.widthTiles, m.heightTiles,
                BooleanArray(m.widthTiles * m.heightTiles) { wall && it % m.widthTiles == 15 })) }
        return base.copy(map = map, unitDefinitions = base.unitDefinitions + (tower.id to tower) + (tank.id to tank),
            units = listOf(UnitSpawn("a", "p1", tank.id, start, if (start.x < targetX) 90.0 else 270.0),
                UnitSpawn("t", "p2", tower.id, Position(targetX, 200.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "t" to 11L), 12L))
    }

    @Test fun ordinary_move_really_reaches_the_wide_legal_region_and_legal_continuous_stances_really_fire() {
        val moving = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(420.0))).context
        try {
            assertIs<ActionResult.Accepted>(moving.submit(MoveAction("move-control", "p1", "a", Position(299.0, 200.0), moving.world)))
            repeat(400) { moving.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val actor = moving.observe().units.single { it.unitId == "a" }
            assertTrue(actor.position.x in 290.0..<300.0, actor.toString())
            assertNull(actor.moveTarget)
            assertTrue((actor.position.x - 420.0) * (actor.position.x - 420.0) +
                (actor.position.y - 200.0) * (actor.position.y - 200.0) < 16900.0)
        } finally { moving.close() }
        for (x in listOf(420.0, 428.5)) {
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(x, Position(299.0, 200.0)))).context
            try {
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("stance-control", "p1", "a", "t", c.world)))
                var fired = false
                repeat(50) {
                    val frame = c.advance(Step(it.toLong(), 1.0, "sim-tick"))
                    fired = fired || frame.projectileEvents.any { e -> e.kind == "born" && e.projectile.sourceUnitId == "a" }
                }
                assertTrue(fired); assertTrue(c.observe().units.single { it.unitId == "t" }.health!! < 700.0)
            } finally { c.close() }
        }
    }

    @Test fun a_full_barrier_with_no_legal_range_region_stays_unreachable_without_opening_any_cost_cell() {
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(450.0))).context
        try {
            c.enablePathDiagnostics(c.world)
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("unreachable", "p1", "a", "t", c.world)))
            repeat(20) {
                val frame = c.advance(Step(it.toLong(), 1.0, "sim-tick"))
                assertTrue(frame.events.any { e -> e.kind == "path-unreachable" && e.unitId == "a" })
                assertEquals(Position(100.0, 200.0), frame.units.single { u -> u.unitId == "a" }.position)
                assertTrue(frame.projectileEvents.isEmpty())
            }
            assertEquals(700.0, c.observe().units.single { it.unitId == "t" }.health)
            assertTrue(c.drainPathDiagnostics(c.world).all { it.actualSearchCalls == 1L })
        } finally { c.close() }
    }

    @Test fun an_edge_building_uses_legal_attacker_stances_instead_of_rejecting_its_center_for_the_attackers_radius() {
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(
            scene(16.0, Position(300.0, 200.0), wall = false, actorRadius = 19.0))).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("edge", "p1", "a", "t", c.world)))
            repeat(500) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertTrue(c.observe().units.single { it.unitId == "a" }.position.x < 146.0)
            assertTrue(c.observe().units.single { it.unitId == "t" }.health!! < 700.0)
        } finally { c.close() }
    }

    @Test fun authorized_building_approach_reuses_its_route_in_both_owner_knowledge_modes() {
        for (retention in TerrainRetention.entries) {
            val input = scene(420.0, Position(150.0, 200.0), wall = false).copy(
                automaticVision = KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, false))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                assertTrue(c.observePlayer(c.world, "p1").units.any { it.unitId == "t" })
                c.enablePathDiagnostics(c.world)
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("known", "p1", "a", "t", c.world)))
                repeat(350) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                assertTrue(c.observe().units.single { it.unitId == "t" }.health!! < 700.0)
                val paths = c.drainPathDiagnostics(c.world)
                assertEquals(1, paths.count { it.reason == com.rustedwarfare.reforge.core.path.PathSearchReason.ATTACK_PURSUIT })
                assertTrue(paths.all { it.usedPlayerKnowledge })
            } finally { c.close() }
        }
    }
}
