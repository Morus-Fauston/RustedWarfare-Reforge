package com.rustedwarfare.reforge.core.sim

import kotlin.test.Test
import kotlin.test.assertEquals

class PropulsionPolicyTest {
    @Test
    fun distance_and_angle_boundaries_use_the_reference_comparisons() {
        val profile = TestContentFactory.openScenario().unitDefinitions.getValue("tank").movement
        // Exercise long-distance policy inputs directly: adjacent A* points are only 20 units apart.
        val cases = listOf(18.999 to 20.0, 19.0 to 20.0, 19.001 to 46.0,
            59.999 to 46.0, 60.0 to 46.0, 60.001 to 89.0,
            129.999 to 89.0, 130.0 to 89.0, 130.001 to 180.0)
        for ((distance, gate) in cases) {
            for (sign in listOf(-1.0, 1.0)) {
                for (offset in listOf(-0.001, 0.0, 0.001)) {
                    val angle = sign * (gate + offset)
                    val expected = if (offset <= 0.0) profile.moveSpeed else 0.0
                    assertEquals(expected, ReferencePropulsion.targetSpeed(profile, profile.moveSpeed * 0.5,
                        distance * distance, angle, false, 4.0), "distance=$distance angle=$angle")
                }
            }
        }
    }

    @Test
    fun slow_turn_fast_start_and_final_distance_caps_are_applied_in_order() {
        val profile = TestContentFactory.openScenario().unitDefinitions.getValue("tank").movement
        fun speed(distance: Double, angle: Double, turn: Double, maximum: Double = 1.1,
                  actual: Double = 0.0, deceleration: Double = 0.17, final: Boolean = false): Double =
            ReferencePropulsion.targetSpeed(profile.copy(maxTurnSpeed = turn, moveSpeed = maximum,
                moveDecelerationSpeed = deceleration), actual, distance * distance, angle, final, 4.0)
        assertEquals(1.1, speed(80.0, 17.0, 1.3))
        assertEquals(0.0, speed(80.0, 17.001, 1.3))
        assertEquals(1.1, speed(80.001, 44.5, 1.3))
        assertEquals(0.0, speed(80.001, 44.501, 1.3))
        assertEquals(1.1, speed(10.0, 17.0 * 0.7, 1.0))
        assertEquals(0.0, speed(10.0, 17.0 * 0.7 + 0.001, 1.0))
        assertEquals(6.0, speed(30.0, 1.0, 4.1, 6.0))
        assertEquals(0.0, speed(30.0, 1.001, 4.1, 6.0))
        assertEquals(1.1, speed(18.0, 0.0, 4.1, deceleration = 0.12, final = true))
        assertEquals(0.55, speed(17.999, 0.0, 4.1, deceleration = 0.12, final = true))
        assertEquals(0.55, speed(13.0, 0.0, 4.1, deceleration = 0.12, final = true))
        assertEquals(0.275, speed(12.999, 0.0, 4.1, deceleration = 0.12, final = true))
        assertEquals(3.0, speed(17.0, 0.0, 4.1, 6.0, final = true))
        assertEquals(1.5, speed(8.999, 0.0, 4.1, 6.0, final = true))
        assertEquals(0.0, speed(3.999, 0.0, 4.1, final = true))
        assertEquals(0.0, speed(2.999, 0.0, 4.1))
    }
}
