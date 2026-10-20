package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.path.PathPlanner
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.test.*

class MapBoundaryTest {
    @Test fun invalid_target_cancels_without_replacement_and_deceleration_still_obeys_the_boundary() {
        val source = scenario()
        val tank = source.unitDefinitions.getValue("tank")
        val fast = TestContentFactory.unitWith(tank, movement = tank.movement.copy(
            moveSpeed = 40.0, moveAccelerationSpeed = 40.0, moveDecelerationSpeed = 0.0))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
            unitDefinitions = mapOf("tank" to fast),
            units = listOf(source.units.single().copy(position = Position(50.0, 70.0)))))).context
        try {
            context.submit(MoveAction("moving", "p1", "u1", Position(50.0, 11.0), context.world))
            val moving = context.advance(Step(0, 1.0, "sim-tick")).units.single()
            assertEquals(Position(50.0, 30.0), moving.position)
            assertEquals(40.0, moving.speed)
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(context.submit(
                MoveAction("foreign", "p2", "u1", Position(50.0, 10.0), context.world))).error.code)
            assertEquals("invalid-target", assertIs<ActionResult.Rejected>(context.submit(
                MoveAction("nan", "p1", "u1", Position(Double.NaN, 10.0), context.world))).error.code)
            assertEquals(moving, context.observe().units.single())
            val result = assertIs<ActionResult.Accepted>(context.submit(
                MoveAction("outside", "p1", "u1", Position(50.0, 10.0), context.world)))
            assertEquals(listOf("movement-cancelled", "path-unreachable"), result.events.map { it.kind })
            val cancelled = context.observe().units.single()
            assertNull(cancelled.moveTarget)
            assertTrue(cancelled.pathPoints.isEmpty())
            assertEquals(moving.speed, cancelled.speed)
            val stopped = context.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(moving.position, stopped.units.single().position)
            assertEquals(0.0, stopped.units.single().speed)
            assertTrue(stopped.events.any { it.kind == "movement-boundary-blocked" })
        } finally { context.close() }
    }

    @Test fun zero_radius_closed_edge_units_use_the_last_cell_without_global_ownership_visibility() {
        for (radius in listOf(0.0, -0.0)) {
            val source = scenario(radius).copy(automaticVision = KnowledgeRules(
                TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
            for (edge in listOf(Position(0.0, 50.0), Position(100.0, 50.0), Position(50.0, 0.0),
                Position(50.0, 100.0), Position(100.0, 100.0))) {
                val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
                    units = listOf(source.units.single().copy(position = edge))))).context
                try {
                    assertEquals(edge, context.observePlayer(context.world, "p1").units.single().position)
                    assertTrue(context.observePlayer(context.world, "p2").units.isEmpty())
                    assertIs<ActionResult.Accepted>(context.submit(SelectAction("select", "p1", "u1", context.world)))
                } finally { context.close() }
            }
        }
    }

    @Test fun finite_boundary_displacement_stops_at_last_legal_position_and_accepts_a_new_move() {
        val source = scenario()
        val definition = source.unitDefinitions.getValue("tank")
        val fast = TestContentFactory.unitWith(definition, movement = definition.movement.copy(
            moveSpeed = 40.0, moveAccelerationSpeed = 40.0, moveDecelerationSpeed = 0.0))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
            unitDefinitions = mapOf("tank" to fast),
            units = listOf(source.units.single().copy(position = Position(50.0, 50.0), heading = 0.0))))).context
        try {
            context.submit(MoveAction("move", "p1", "u1", Position(50.0, 11.0), context.world))
            val result = context.advance(Step(0, 1.0, "sim-tick"))
            val stopped = result.units.single()
            assertEquals(Position(50.0, 50.0), stopped.position)
            assertEquals(0.0, stopped.speed)
            assertEquals(0.0, stopped.angularVelocity)
            assertNull(stopped.moveTarget)
            assertTrue(stopped.pathPoints.isEmpty())
            assertEquals(MovementState.BLOCKED, stopped.movementState)
            assertTrue(result.events.any { it.kind == "movement-boundary-blocked" })
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("retry", "p1", "u1", Position(50.0, 70.0), context.world)))
            assertEquals(Position(50.0, 70.0), context.observe().units.single().moveTarget)
        } finally { context.close() }
    }

    @Test fun planning_keeps_the_entire_circle_inside_and_preserves_exact_edge_targets() {
        val terrain = PlanningTerrain(5, 5, 20, 20, BooleanArray(25))
        val center = Position(50.0, 50.0)
        for (edge in listOf(Position(11.0, 50.0), Position(89.0, 50.0), Position(50.0, 11.0), Position(50.0, 89.0))) {
            assertEquals(listOf(edge), PathPlanner.findPath(terrain, center, edge, 11.0))
        }
        for (outside in listOf(Position(10.99, 50.0), Position(89.01, 50.0), Position(50.0, 10.99), Position(50.0, 89.01))) {
            assertNull(PathPlanner.findPath(terrain, center, outside, 11.0))
            assertNull(PathPlanner.findPath(terrain, outside, center, 11.0))
        }
        assertNull(PathPlanner.findPath(terrain, center, center, 51.0))
        for (radius in listOf(0.0, -0.0)) {
            for (edge in listOf(Position(0.0, 50.0), Position(100.0, 50.0), Position(50.0, 0.0),
                Position(50.0, 100.0), Position(100.0, 100.0))) {
                assertEquals(listOf(edge), PathPlanner.findPath(terrain, center, edge, radius))
                assertEquals(listOf(center), PathPlanner.findPath(terrain, edge, center, radius))
            }
            assertNull(PathPlanner.findPath(terrain, center, Position(100.01, 50.0), radius))
        }
    }

    private fun scenario(radius: Double = 11.0): Scenario {
        val source = TestContentFactory.openScenario()
        return source.copy(
            map = MapDefinition(5, 5, 20, 20, emptyList(), IntArray(25), BlockingGrid(5, 5, BooleanArray(25))),
            unitDefinitions = mapOf("tank" to TestContentFactory.unitWith(source.unitDefinitions.getValue("tank"), radius)),
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(50.0, 50.0), 0.0)),
        )
    }

    @Test fun whole_circle_spawn_accepts_tangency_and_rejects_each_overhanging_edge_atomically() {
        val source = scenario()
        for (legal in listOf(Position(11.0, 50.0), Position(89.0, 50.0), Position(50.0, 11.0), Position(50.0, 89.0))) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
                units = listOf(source.units.single().copy(position = legal))))).context
            assertEquals(legal, context.observe().units.single().position)
            context.close()
        }
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(source)).context
        context.close()
        for (illegal in listOf(Position(10.99, 50.0), Position(89.01, 50.0), Position(50.0, 10.99), Position(50.0, 89.01))) {
            val rejected = assertIs<InitResult.Rejected>(context.reopen(source.copy(units = listOf(
                source.units.single(), source.units.single().copy(unitId = "bad", position = illegal)))))
            assertEquals("invalid-spawn", rejected.error.code)
            assertTrue(rejected.error.message.contains("bad"))
            assertFailsWith<IllegalStateException> { context.observe() }
        }
        assertIs<InitResult.Accepted>(context.reopen(source))
        assertEquals(listOf("u1"), context.observe().units.map { it.unitId })
        context.close()
        assertEquals("invalid-spawn", assertIs<InitResult.Rejected>(context.reopen(source.copy(
            map = MapDefinition(1, 1, 20, 20, emptyList(), IntArray(1), BlockingGrid(1, 1, BooleanArray(1))),
            units = listOf(source.units.single().copy(position = Position(10.0, 10.0)))))).error.code)
    }
}
