package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class NormalMoveCompletionTest {
    private fun context(heading: Double = 90.0, maximumSpeed: Double = 1.1): SimulationContext {
        val source = TestContentFactory.openScenario()
        val original = source.unitDefinitions.getValue("tank")
        val unit = TestContentFactory.unitWith(original, movement = original.movement.copy(
            moveSpeed = maximumSpeed, moveAccelerationSpeed = 0.07, moveDecelerationSpeed = 0.17))
        return assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
            map = MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600), BlockingGrid(30, 20, BooleanArray(600))),
            unitDefinitions = mapOf("tank" to unit),
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(100.0, 100.0), heading)),
            automaticVision = null,
        ))).context
    }

    @Test
    fun normal_completion_is_strict_seven_and_precedes_this_steps_turn() {
        for (distance in listOf(6.99, 7.0)) {
            val context = context(heading = 0.0)
            try {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "u1",
                    Position(100.0 + distance, 100.0), context.world)))
                val result = context.advance(Step(0, 1.0, "sim-tick"))
                val unit = result.units.single()
                if (distance < 7.0) {
                    assertEquals(null, unit.moveTarget)
                    assertEquals(listOf("movement-started", "movement-arrived"), result.events.map { it.kind })
                    assertEquals(0.0, unit.heading)
                    assertEquals(Position(100.0, 100.0), unit.position)
                } else {
                    assertNotNull(unit.moveTarget)
                    assertEquals(0.25, unit.heading, 1e-12)
                }
            } finally { context.close() }
        }
    }

    @Test
    fun normal_clear_keeps_inertia_and_integrates_the_updated_proportion() {
        val context = context()
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("warm", "p1", "u1", Position(500.0, 100.0), context.world)))
            repeat(10) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val before = context.observe().units.single()
            assertEquals(0.77, before.speed, 1e-12)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("near", "p1", "u1", Position(before.position.x + 6.0, 100.0), context.world)))
            val observation = context.advance(Step(10, 1.0, "sim-tick"))
            val after = observation.units.single()
            assertEquals(null, after.moveTarget)
            assertEquals(0.583, after.speed, 1e-12)
            assertEquals(before.position.x + 0.583, after.position.x, 1e-12)
            assertEquals(MovementState.DECELERATING, after.movementState)
            assertEquals(listOf("movement-started", "movement-arrived"), observation.events.map { it.kind })
        } finally { context.close() }
    }

    @Test
    fun near_target_time_is_accumulated_before_the_strict_long_wait_boundaries() {
        for ((distance, lastActiveQ) in listOf(15.0 to 240, 35.0 to 340)) {
            val context = context(maximumSpeed = 0.0)
            try {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("wait", "p1", "u1",
                    Position(100.0 + distance, 100.0), context.world)))
                repeat(lastActiveQ) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                assertNotNull(context.observe().units.single().moveTarget)
                val completed = context.advance(Step(lastActiveQ.toLong(), 1.0, "sim-tick"))
                assertEquals(null, completed.units.single().moveTarget)
                assertEquals(Position(100.0, 100.0), completed.units.single().position)
                assertEquals(listOf("movement-arrived"), completed.events.map { it.kind })
            } finally { context.close() }
        }
        val far = context(maximumSpeed = 0.0)
        try {
            assertIs<ActionResult.Accepted>(far.submit(MoveAction("far", "p1", "u1", Position(150.0, 100.0), far.world)))
            repeat(400) { far.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertNotNull(far.observe().units.single().moveTarget) // Total command age alone cannot expand the radius.
        } finally { far.close() }
    }

    @Test
    fun stop_freezes_heading_and_retains_steering_state_without_preventing_idle() {
        val context = context(heading = 0.0)
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("turn", "p1", "u1", Position(300.0, 100.0), context.world)))
            repeat(4) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val turning = context.observe().units.single()
            assertEquals(1.0, turning.angularVelocity, 1e-12)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "u1", context.world)))
            repeat(7) { context.advance(Step(4L + it, 1.0, "sim-tick")) }
            val stopped = context.observe().units.single()
            assertEquals(turning.heading, stopped.heading)
            assertEquals(turning.angularVelocity, stopped.angularVelocity)
            assertEquals(0.0, stopped.speed)
            assertEquals(MovementState.IDLE, stopped.movementState)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("resume", "p1", "u1", Position(300.0, 100.0), context.world)))
            val resumed = context.advance(Step(11, 1.0, "sim-tick")).units.single()
            assertEquals(1.25, resumed.angularVelocity, 1e-12)
            assertEquals(stopped.heading + 1.25, resumed.heading, 1e-12)
        } finally { context.close() }
    }
}
