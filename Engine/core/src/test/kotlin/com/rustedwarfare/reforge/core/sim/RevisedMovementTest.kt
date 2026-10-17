package com.rustedwarfare.reforge.core.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RevisedMovementTest {
    private fun context(start: Position, target: Position, heading: Double = 90.0): SimulationContext {
        val scenario = TestContentFactory.openScenario().copy(
            units = listOf(UnitSpawn("u1", "p1", "tank", start, heading)),
        )
        return assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context.also {
            assertIs<ActionResult.Accepted>(it.submit(MoveAction("a1", "p1", "u1", target, it.world)))
        }
    }

    @Test
    fun turns_before_accelerating_when_outside_the_propulsion_gate() {
        val scenario = TestContentFactory.openScenario().copy(
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(110.0, 110.0), 0.0)),
        )
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        assertIs<ActionResult.Accepted>(context.submit(MoveAction("a1", "p1", "u1", Position(170.0, 110.0), context.world)))
        val unit = context.advance(Step(0, 1.0, "sim-tick")).units.single()
        assertEquals(0.0, unit.speed)
        assertEquals(Position(110.0, 110.0), unit.position)
        assertEquals(0.25, unit.angularVelocity)
        assertEquals(0.25, unit.heading)
    }

    @Test
    fun intermediate_radius_is_strict_and_uses_the_step_start_position() {
        val context = context(Position(114.0, 110.0), Position(170.0, 110.0))
        val before = context.observe().units.single()
        assertEquals(Position(130.0, 110.0), before.pathPoints.first())
        val atBoundary = context.advance(Step(0, 1.0, "sim-tick")).units.single()
        assertEquals(before.pathPoints, atBoundary.pathPoints)
        val inside = context.advance(Step(1, 1.0, "sim-tick")).units.single()
        assertEquals(before.pathPoints.drop(1), inside.pathPoints)
        assertEquals(Position(170.0, 110.0), inside.moveTarget)
    }

    @Test
    fun final_radius_is_strict_and_same_tile_target_is_exact() {
        val context = context(Position(110.0, 110.0), Position(114.0, 110.0))
        assertEquals(listOf(Position(114.0, 110.0)), context.observe().units.single().pathPoints)
        val boundary = context.advance(Step(0, 1.0, "sim-tick"))
        assertTrue(boundary.events.none { it.kind == "movement-arrived" })
        assertEquals(0.07, boundary.units.single().speed, 1e-9)
        val inside = context.advance(Step(1, 1.0, "sim-tick"))
        assertEquals(listOf("movement-arrived"), inside.events.map { it.kind })
        assertEquals(null, inside.units.single().moveTarget)
        assertEquals(Position(110.07, 110.0), inside.units.single().position)
    }

    @Test
    fun only_one_snapshot_waypoint_is_consumed_per_step() {
        val context = context(Position(115.0, 110.0), Position(141.0, 110.0))
        val before = context.observe().units.single()
        assertEquals(2, before.pathPoints.size)
        val after = context.advance(Step(0, 1.0, "sim-tick"))
        assertEquals(before.pathPoints.drop(1), after.units.single().pathPoints)
        assertEquals(Position(141.0, 110.0), after.units.single().moveTarget)
        assertTrue(after.events.none { it.kind == "movement-arrived" })
    }

    @Test
    fun unreachable_replacement_retains_speed_then_decelerates_without_restoring_the_old_path() {
        val context = context(Position(110.0, 110.0), Position(250.0, 110.0))
        (0L..15L).forEach { context.advance(Step(it, 1.0, "sim-tick")) }
        val before = context.observe().units.single()
        assertEquals(1.1, before.speed, 1e-9)
        val result = assertIs<ActionResult.Accepted>(context.submit(MoveAction("a2", "p1", "u1", Position(450.0, 100.0), context.world)))
        assertEquals(listOf("movement-cancelled", "path-unreachable"), result.events.map { it.kind })
        assertEquals(before.speed, context.observe().units.single().speed)
        val expected = listOf(0.93, 0.76, 0.59, 0.42, 0.25, 0.08, 0.0)
        expected.forEachIndexed { index, speed ->
            val unit = context.advance(Step(16L + index, 1.0, "sim-tick")).units.single()
            assertEquals(speed, unit.speed, 1e-9)
            assertEquals(null, unit.moveTarget)
            assertTrue(unit.pathPoints.isEmpty())
        }
        assertEquals(3.03, context.observe().units.single().position.x - before.position.x, 1e-9)
    }
}
