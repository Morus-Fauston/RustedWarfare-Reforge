package com.rustedwarfare.reforge.core.sim

import kotlin.test.*
import com.rustedwarfare.reforge.core.content.MechDefinition

class MechBodyTest {
    @Test fun swinging_foot_uses_the_retained_negative_half_turn_direction() {
        val source = MechTestScene.unit()
        val profile = MechDefinition(source.mech!!.body, source.mech!!.legs.map { it.copy(warmUp = 100.0) })
        val context = MechTestScene.open(MechTestScene.scenario(
            MechTestScene.with(source, profile, source.movement.copy(reverseSpeedPercentage = 0.0, moveSpeed = 0.0)),
            position = Position(300.0, 200.0)))
        try {
            context.submit(MoveAction("north", "p1", "u1", Position(300.0, 50.0), context.world))
            context.advance(Step(0, 1.0, "sim-tick"))
            context.submit(MoveAction("west", "p1", "u1", Position(100.0, 200.0), context.world))
            var firstSwing: UnitObservation? = null
            for (step in 1L..249L) {
                val unit = context.advance(Step(step, 1.0, "sim-tick")).units.single()
                if (unit.mechPose!!.legs.any { it.phase == MechLegPhase.SWINGING }) { firstSwing = unit; break }
            }
            val unit = assertNotNull(firstSwing)
            assertEquals(270.0, unit.heading, 1e-10)
            assertEquals(83.0, unit.mechPose!!.legs.first { it.phase == MechLegPhase.SWINGING }.heading, 1e-10)
        } finally { context.close() }
    }

    @Test fun negative_half_turn_keeps_its_sign_and_does_not_create_a_spurious_body_hold() {
        val source = MechTestScene.unit()
        val context = MechTestScene.open(MechTestScene.scenario(
            MechTestScene.with(source, movement = source.movement.copy(moveSpeed = 0.0)),
            heading = 270.9, position = Position(400.0, 200.0)))
        try {
            context.submit(MoveAction("west", "p1", "u1", Position(100.0, 200.0), context.world))
            val headings = (0L..2L).map { context.advance(Step(it, 1.0, "sim-tick")).units.single().heading }
            assertEquals(270.4, headings[0], 1e-10)
            assertEquals(270.0, headings[1], 1e-10)
            context.submit(MoveAction("turn", "p1", "u1", Position(100.0, 300.0), context.world))
            context.advance(Step(3, 1.0, "sim-tick"))
            val unit = context.advance(Step(4, 1.0, "sim-tick")).units.single()
            assertEquals(269.7, unit.mechPose!!.forwardHeading, 1e-10)
        } finally { context.close() }
    }

    @Test fun fast_reverse_motion_is_not_mistaken_for_the_stationary_one_degree_gate() {
        val source = MechTestScene.unit()
        val context = MechTestScene.open(MechTestScene.scenario(
            MechTestScene.with(source, movement = source.movement.copy(moveSpeed = 6.0,
                moveAccelerationSpeed = 1.0, moveDecelerationSpeed = 1.0)), position = Position(300.0, 200.0)))
        try {
            context.submit(MoveAction("west", "p1", "u1", Position(50.0, 200.0), context.world))
            assertEquals(-6.0, context.advance(Step(0, 1.0, "sim-tick")).units.single().speed)
            context.submit(MoveAction("angled", "p1", "u1", Position(50.0, 210.0), context.world))
            assertEquals(-6.0, context.advance(Step(1, 1.0, "sim-tick")).units.single().speed)
        } finally { context.close() }
    }

    @Test fun reversing_holds_the_upper_body_for_twenty_five_later_steps_then_uses_its_own_turn_rate() {
        val context = MechTestScene.open(MechTestScene.scenario(position = Position(400.0, 100.0)))
        try {
            context.submit(MoveAction("reverse", "p1", "u1", Position(50.0, 100.0), context.world))
            context.advance(Step(0, 1.0, "sim-tick"))
            repeat(25) { step ->
                val unit = context.advance(Step(step + 1L, 1.0, "sim-tick")).units.single()
                assertEquals(90.0, unit.mechPose!!.forwardHeading)
                assertEquals(90.0, unit.heading)
            }
            val unit = context.advance(Step(26, 1.0, "sim-tick")).units.single()
            assertEquals(90.3, unit.mechPose!!.forwardHeading, 1e-12)
            assertEquals(0.3, unit.mechPose!!.angularVelocity, 1e-12)
        } finally { context.close() }
    }

    @Test fun turning_without_propulsion_still_suppresses_sweep_using_the_previous_motion_intent() {
        val context = MechTestScene.open(MechTestScene.scenario(heading = 0.0))
        try {
            context.submit(MoveAction("east", "p1", "u1", Position(500.0, 100.0), context.world))
            val first = context.advance(Step(0, 1.0, "sim-tick")).units.single()
            assertEquals(0.0, first.speed)
            assertEquals(0.0, first.mechPose!!.forwardHeading)
            val second = context.advance(Step(1, 1.0, "sim-tick")).units.single()
            assertEquals(0.0, second.speed)
            assertEquals(0.3, second.mechPose!!.forwardHeading, 1e-12)
            assertEquals(1.5, second.heading, 1e-12)
        } finally { context.close() }
    }

    @Test fun stopped_motion_intent_allows_sweep_even_while_the_unit_is_still_braking() {
        val source = MechTestScene.unit()
        val mech = assertNotNull(source.mech)
        val profile = MechDefinition(mech.body.copy(sweepDelay = 0.0, sweepRandomDelay = 0), mech.legs)
        val context = MechTestScene.open(MechTestScene.scenario(MechTestScene.with(source, profile)))
        try {
            context.submit(MoveAction("move", "p1", "u1", Position(500.0, 100.0), context.world))
            repeat(20) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            context.submit(StopAction("stop", "p1", "u1", context.world))
            val first = context.advance(Step(20, 1.0, "sim-tick")).units.single()
            val second = context.advance(Step(21, 1.0, "sim-tick")).units.single()
            assertEquals(90.0, first.mechPose!!.forwardHeading)
            assertTrue(second.speed > 0.0)
            assertEquals(90.027, second.mechPose!!.forwardHeading, 1e-12)
        } finally { context.close() }
    }

    @Test fun sweep_random_delays_replay_for_the_same_identity_and_vary_with_another_identity() {
        fun run(id: String): List<Double> {
            val input = MechTestScene.scenario()
            val context = MechTestScene.open(input.copy(units = listOf(input.units.single().copy(unitId = id))))
            return try { (0L..799L).map {
                val heading = context.advance(Step(it, 1.0, "sim-tick")).units.single().mechPose!!.forwardHeading
                assertTrue(heading.isFinite() && heading in 69.999999..110.000001)
                heading
            } } finally { context.close() }
        }
        val first = run("u1")
        assertEquals(first, run("u1"))
        assertNotEquals(first, run("another-unit"))
        assertTrue(first.distinct().size > 50)
    }

    @Test fun idle_body_sweep_uses_its_own_clock_and_scaled_delta_without_rotating_planted_legs() {
        val context = MechTestScene.open()
        try {
            val initial = assertNotNull(context.observePlayer(context.world, "p1").units.single().mechPose)
            repeat(101) { step ->
                context.advance(Step(step.toLong(), 1.0, "sim-tick"))
                assertEquals(90.0, context.observePlayer(context.world, "p1").units.single().mechPose?.forwardHeading)
            }
            context.advance(Step(101, 1.0, "sim-tick"))
            val unit = context.observePlayer(context.world, "p1").units.single()
            val pose = assertNotNull(unit.mechPose)
            assertEquals(90.027, pose.forwardHeading, 1e-12) // .3 acceleration * .3 delta, integrated with .3 delta.
            assertEquals(0.09, pose.angularVelocity, 1e-12)
            assertEquals(90.0, unit.heading)
            assertEquals(initial.legs.map { it.foot }, pose.legs.map { it.foot })
            assertTrue(pose.legs.all { it.height == 0.0 && it.heading == 90.0 })
            println("MECH_IDLE_102 $pose")
        } finally { context.close() }
    }
}
