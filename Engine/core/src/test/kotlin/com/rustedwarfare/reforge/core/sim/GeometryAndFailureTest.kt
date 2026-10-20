package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GeometryAndFailureTest {
    @Test
    fun extreme_finite_motion_is_boundary_blocked_without_discarding_other_units_or_queued_actions() {
        val source = TestContentFactory.openScenario()
        val tank = source.unitDefinitions.getValue("tank")
        val fast = TestContentFactory.unitWith(tank, movement = tank.movement.copy(
            moveSpeed = Double.MAX_VALUE, moveAccelerationSpeed = Double.MAX_VALUE,
            moveDecelerationSpeed = Double.MAX_VALUE,
        ))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
            map = map(), unitDefinitions = mapOf("tank" to tank, "fast" to fast),
            units = listOf(UnitSpawn("a", "p1", "tank", Position(110.0, 150.0), 90.0),
                UnitSpawn("u1", "p1", "fast", Position(110.0, 110.0), 90.0)),
        ))).context
        context.submit(MoveAction("a-move", "p1", "a", Position(250.0, 150.0), context.world))
        context.submit(MoveAction("u-move", "p1", "u1", Position(250.0, 110.0), context.world))
        val before = context.advance(Step(0, 1.0, "sim-tick"))
        assertTrue(before.units.all { it.position.x.isFinite() && it.position.y.isFinite() })
        val stopped = before.units.single { it.unitId == "u1" }
        assertEquals(Position(110.0, 110.0), stopped.position)
        assertEquals(0.0, stopped.speed)
        assertEquals(null, stopped.moveTarget)
        assertTrue(before.events.any { it.unitId == "u1" && it.kind == "movement-boundary-blocked" })
        assertTrue(before.units.single { it.unitId == "a" }.position.x > 110.0)
        context.submit(StopAction("queued-stop", "p1", "a", context.world, atStep = 1))
        val recovered = context.advance(Step(1, 1.0, "sim-tick"))
        assertEquals(2.0, recovered.simulationTime)
        assertEquals(listOf("queued-stop"), recovered.actionResults.map { it.actionId })
        assertTrue(recovered.units.all { it.speed == 0.0 && it.position.x.isFinite() && it.position.y.isFinite() })
        assertEquals(before.units.map { it.position }, recovered.units.map { it.position })
        context.close()
    }

    private fun map(vararg blocked: Pair<Int, Int>): MapDefinition {
        val cells = BooleanArray(30 * 20)
        blocked.forEach { (col, row) -> cells[row * 30 + col] = true }
        return MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600), BlockingGrid(30, 20, cells))
    }

    @Test
    fun circle_tangent_to_a_blocked_rectangle_is_not_a_path_candidate() {
        val source = TestContentFactory.openScenario()
        val unit = TestContentFactory.unitWith(source.unitDefinitions.getValue("tank"), radius = 10.0)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
            map = map(7 to 5), unitDefinitions = mapOf("tank" to unit),
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(110.0, 110.0), 90.0)),
        ))).context
        val result = assertIs<ActionResult.Accepted>(context.submit(
            MoveAction("a1", "p1", "u1", Position(130.0, 110.0), context.world),
        ))
        assertEquals(listOf("movement-cancelled", "path-unreachable"), result.events.map { it.kind })
    }

    @Test
    fun illegal_exact_target_is_not_replaced_with_its_legal_tile_center() {
        val source = TestContentFactory.openScenario()
        val unit = TestContentFactory.unitWith(source.unitDefinitions.getValue("tank"), radius = 10.0)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
            map = map(7 to 7), unitDefinitions = mapOf("tank" to unit),
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(110.0, 110.0), 90.0)),
        ))).context
        // The (6,6) center is clear; (134,132) touches the blocked corner at distance 10.
        val result = assertIs<ActionResult.Accepted>(context.submit(
            MoveAction("a1", "p1", "u1", Position(134.0, 132.0), context.world),
        ))
        assertEquals(listOf("movement-cancelled", "path-unreachable"), result.events.map { it.kind })
        assertEquals(null, context.observe().units.single().moveTarget)
    }

    @Test
    fun collision_rejects_candidate_displacement_and_zeroes_both_motion_components() {
        val source = TestContentFactory.openScenario()
        val definition = source.unitDefinitions.getValue("tank")
        val unit = TestContentFactory.unitWith(definition,
            movement = definition.movement.copy(moveSpeed = 20.0, moveAccelerationSpeed = 20.0))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
            map = map(8 to 5), unitDefinitions = mapOf("tank" to unit),
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(110.0, 110.0), 90.0)),
        ))).context
        context.enableKnowledge(context.world, KnowledgeRules(TerrainRetention.MEMORY,
            PathInformationSource.PLAYER_KNOWLEDGE, false))
        context.submit(MoveAction("a1", "p1", "u1", Position(250.0, 110.0), context.world))
        assertEquals(listOf(Position(250.0, 110.0)), context.observe().units.single().pathPoints)
        val first = context.advance(Step(0, 1.0, "sim-tick")).units.single()
        assertEquals(Position(130.0, 110.0), first.position)
        context.submit(MoveAction("turn", "p1", "u1", Position(250.0, 112.0), context.world))
        val blocked = context.advance(Step(1, 1.0, "sim-tick"))
        assertEquals(first.position, blocked.units.single().position)
        assertEquals(0.0, blocked.units.single().speed)
        assertEquals(0.0, blocked.units.single().angularVelocity)
        assertEquals(MovementState.BLOCKED, blocked.units.single().movementState)
        assertTrue(blocked.events.any { it.kind == "movement-blocked" })
        context.close()
    }

    @Test
    fun failed_reopen_leaves_no_half_world_and_a_valid_scenario_can_recover() {
        val scenario = TestContentFactory.openScenario()
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        val oldWorld = context.world
        context.close()
        val invalid = scenario.copy(units = listOf(scenario.units.single().copy(ownerPlayerId = "absent")))
        assertEquals("bad-reference", assertIs<InitResult.Rejected>(context.reopen(invalid)).error.code)
        assertIs<InitResult.Accepted>(context.reopen(scenario))
        assertTrue(oldWorld !== context.world)
        assertEquals(scenario.units.single().position, context.observe().units.single().position)
    }

    @Test
    fun explicit_capture_radius_changes_consumption_without_a_global_default() {
        val source = TestContentFactory.openScenario().copy(
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(115.0, 110.0), 90.0)),
            movementContract = MovementContract(1.0, 4.0),
        )
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(source)).context
        context.submit(MoveAction("a1", "p1", "u1", Position(170.0, 110.0), context.world))
        val path = context.observe().units.single().pathPoints
        assertEquals(path, context.advance(Step(0, 1.0, "sim-tick")).units.single().pathPoints)
    }
}
