package com.rustedwarfare.reforge.core.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SteeringRegressionTest {
    private fun stationaryTurn(heading: Double, target: Position, acceleration: Double = 0.25): SimulationContext {
        val base = TestContentFactory.openScenario()
        val definition = base.unitDefinitions.getValue("tank")
        val scenario = base.copy(
            unitDefinitions = mapOf("tank" to TestContentFactory.unitWith(definition,
                movement = definition.movement.copy(moveSpeed = 0.0, turnAcceleration = acceleration))),
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(110.0, 110.0), heading)),
        )
        return assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context.also {
            assertIs<ActionResult.Accepted>(it.submit(MoveAction("turn", "p1", "u1", target, it.world)))
        }
    }

    @Test
    fun a_fixed_target_is_reached_without_heading_overshoot_or_hunting() {
        val context = stationaryTurn(0.0, Position(118.0, 110.0))
        try {
            val samples = (0L..119L).map { context.advance(Step(it, 1.0, "sim-tick")).units.single() }
            assertTrue(samples.all { it.heading in 0.0..90.0 }, "A right turn must not pass the fixed 90 degree target")
            assertTrue(samples.zipWithNext().all { (a, b) -> b.heading >= a.heading }, "A fixed target must not cause alternating corrections")
            assertEquals(90.0, samples.last().heading, 0.01)
            assertTrue(samples.takeLast(30).all { it == samples.last() }, "An aligned unit must stay aligned")
        } finally { context.close() }
    }

    @Test
    fun mirrored_and_wrapped_turns_converge_without_crossing_the_target() {
        for ((heading, target, desired) in listOf(
            Triple(180.0, Position(118.0, 110.0), 90.0),
            Triple(350.0, Position(110.0, 102.0), 0.0),
            Triple(10.0, Position(110.0, 102.0), 0.0),
        )) {
            val context = stationaryTurn(heading, target)
            try {
                val initialError = difference(desired, heading)
                var previousError = initialError
                repeat(120) { step ->
                    val sample = context.advance(Step(step.toLong(), 1.0, "sim-tick")).units.single()
                    val error = difference(desired, sample.heading)
                    assertTrue(error * initialError >= -1e-9, "Turn crossed the target heading")
                    assertTrue(kotlin.math.abs(error) <= kotlin.math.abs(previousError) + 1e-9)
                    previousError = error
                }
                assertEquals(0.0, previousError, 0.01)
            } finally { context.close() }
        }
    }

    @Test
    fun changing_target_during_a_turn_does_not_leave_a_permanent_oscillation() {
        val context = stationaryTurn(0.0, Position(118.0, 110.0))
        try {
            repeat(16) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("reverse", "p1", "u1", Position(102.0, 110.0), context.world)))
            val samples = (16L..215L).map { context.advance(Step(it, 1.0, "sim-tick")).units.single() }
            assertEquals(270.0, samples.last().heading, 0.01)
            assertTrue(samples.takeLast(30).all { it == samples.last() })
        } finally { context.close() }
    }

    @Test
    fun zero_acceleration_uses_a_bounded_constant_turn_rate() {
        val context = stationaryTurn(0.0, Position(118.0, 110.0), acceleration = 0.0)
        try {
            val samples = (0L..39L).map { context.advance(Step(it, 1.0, "sim-tick")).units.single() }
            assertEquals(4.1, samples.first().heading, 1e-9)
            assertTrue(samples.all { it.heading in 0.0..90.0 })
            assertEquals(90.0, samples.last().heading, 1e-9)
            assertTrue(samples.takeLast(10).all { it == samples.last() })
        } finally { context.close() }
    }

    private fun difference(target: Double, heading: Double): Double =
        ((target - heading + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
}
