package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProportionalMovementTest {
    private fun context(maximumSpeed: Double): SimulationContext {
        val source = TestContentFactory.openScenario()
        val original = source.unitDefinitions.getValue("tank")
        val definition = TestContentFactory.unitWith(original, movement = original.movement.copy(
            moveSpeed = maximumSpeed, moveAccelerationSpeed = 0.07, moveDecelerationSpeed = 0.17))
        return assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
            map = MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600), BlockingGrid(30, 20, BooleanArray(600))),
            unitDefinitions = mapOf("tank" to definition),
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(100.0, 100.0), 90.0)),
            automaticVision = null,
        ))).context
    }

    @Test
    fun first_step_accelerates_a_proportion_then_integrates_world_speed() {
        for ((maximumSpeed, expectedFirstSpeed) in listOf(1.1 to 0.077, 2.2 to 0.154)) {
            val context = context(maximumSpeed)
            try {
                val accepted = assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "u1",
                    Position(300.0, 100.0), context.world)))
                assertEquals(listOf("movement-started"), accepted.events.map { it.kind })
                val unit = context.advance(Step(0, 1.0, "sim-tick")).units.single()
                assertEquals(expectedFirstSpeed, unit.speed, 1e-12)
                assertEquals(100.0 + expectedFirstSpeed, unit.position.x, 1e-12)
                assertEquals(100.0, unit.position.y, 1e-12)
            } finally { context.close() }
        }
    }

    @Test
    fun stop_clears_the_command_before_proportional_braking_and_settle() {
        for ((maximumSpeed, expectedAfterStop) in listOf(1.1 to 0.913, 2.2 to 1.826, 0.0 to 0.0)) {
            val context = context(maximumSpeed)
            try {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "u1",
                    Position(500.0, 100.0), context.world)))
                repeat(15) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                assertEquals(maximumSpeed, context.observe().units.single().speed, 1e-12)
                assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "u1", context.world)))
                assertEquals(null, context.observe().units.single().moveTarget)
                assertEquals(maximumSpeed, context.observe().units.single().speed, 1e-12)
                val firstBraking = context.advance(Step(15, 1.0, "sim-tick")).units.single()
                assertEquals(expectedAfterStop, firstBraking.speed, 1e-12)
                repeat(6) { context.advance(Step(16L + it, 1.0, "sim-tick")) }
                val settled = context.observe().units.single()
                assertEquals(0.0, settled.speed)
                assertEquals(MovementState.IDLE, settled.movementState)
                assertEquals(settled.position, context.advance(Step(22, 1.0, "sim-tick")).units.single().position)
            } finally { context.close() }
        }
    }
}
