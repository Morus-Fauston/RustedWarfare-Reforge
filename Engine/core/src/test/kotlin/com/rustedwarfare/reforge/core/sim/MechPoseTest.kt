package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class MechPoseTest {
    @Test fun a_moving_neighbour_raises_the_start_distance_from_five_to_twenty() {
        val source = MechTestScene.unit()
        val mech = assertNotNull(source.mech)
        val profile = com.rustedwarfare.reforge.core.content.MechDefinition(mech.body, mech.legs.map { it.copy(moveSpeed = 1.0) })
        val context = MechTestScene.open(MechTestScene.scenario(MechTestScene.with(source, profile)))
        try {
            context.submit(MoveAction("east", "p1", "u1", Position(500.0, 100.0), context.world))
            var sawHeld = false
            var sawReleased = false
            for (step in 0L..70L) {
                val unit = context.advance(Step(step, 1.0, "sim-tick")).units.single()
                val legs = unit.mechPose!!.legs
                val distance = unit.position.x + 12.0 - 100.0 // Straight-line fixed prediction and untouched second foot.
                if (!sawReleased && legs[0].phase == MechLegPhase.SWINGING && legs[1].phase == MechLegPhase.PLANTED && distance > 5.0) {
                    assertTrue(distance <= 20.0)
                    sawHeld = true
                }
                if (!sawReleased && legs[1].phase == MechLegPhase.WARMING_UP) {
                    assertTrue(distance > 20.0)
                    sawReleased = true
                }
            }
            assertTrue(sawHeld); assertTrue(sawReleased)
        } finally { context.close() }
    }

    @Test fun complete_authorized_pose_sequences_replay_after_turn_retarget_and_stop() {
        fun run(): List<MechPose> {
            val context = MechTestScene.open()
            return try { (0L..179L).map { step ->
                when (step) {
                    0L -> context.submit(MoveAction("east", "p1", "u1", Position(500.0, 100.0), context.world))
                    35L -> context.submit(MoveAction("southwest", "p1", "u1", Position(50.0, 300.0), context.world))
                    82L -> context.submit(StopAction("stop", "p1", "u1", context.world))
                }
                context.advance(Step(step, 1.0, "sim-tick"))
                assertNotNull(context.observePlayer(context.world, "p1").units.single().mechPose)
            } } finally { context.close() }
        }
        val first = run()
        assertEquals(first, run())
        assertTrue(first.distinct().size > 50)
    }

    @Test fun stop_in_each_gait_stage_finishes_the_inertial_tail_and_lands_without_freezing_a_raised_foot() {
        for (stopAt in listOf(0, 1, 6, 7, 9, 10, 14)) {
            val context = MechTestScene.open()
            try {
                context.submit(MoveAction("move", "p1", "u1", Position(500.0, 100.0), context.world))
                repeat(stopAt) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                val before = context.observePlayer(context.world, "p1").units.single()
                context.submit(StopAction("stop", "p1", "u1", context.world))
                assertNull(context.observe().units.single().moveTarget)
                assertEquals(before.mechPose, context.observe().units.single().mechPose)
                val tail = (0 until 100).map { context.advance(Step(stopAt.toLong() + it, 1.0, "sim-tick")).units.single() }
                val last = tail.last()
                assertEquals(0.0, last.speed)
                assertEquals(MovementState.IDLE, last.movementState)
                assertTrue(last.mechPose!!.legs.all { it.height == 0.0 && it.phase == MechLegPhase.PLANTED }, "stopAt=$stopAt")
                assertTrue(tail.takeLast(10).all { it.position == last.position && it.mechPose!!.legs == last.mechPose!!.legs })
                println("MECH_STOP stopAt=$stopAt final=$last")
            } finally { context.close() }
        }
    }

    @Test fun hard_limit_keeps_the_old_distance_and_large_recovery_uses_the_literal_unrotated_config() {
        val source = MechTestScene.unit()
        val mech = assertNotNull(source.mech)
        for ((multiplier, expected) in listOf(10.0 to Position(200.056, 92.0), 30.0 to Position(92.056, 100.0))) {
            val definition = com.rustedwarfare.reforge.core.content.MechDefinition(mech.body,
                mech.legs.map { it.copy(predictionMultiplier = multiplier) })
            val context = MechTestScene.open(MechTestScene.scenario(MechTestScene.with(source, definition)))
            try {
                context.submit(MoveAction("move", "p1", "u1", Position(500.0, 100.0), context.world))
                val foot = context.advance(Step(0, 1.0, "sim-tick")).units.single().mechPose!!.legs.first().foot
                assertEquals(expected.x, foot.x, 1e-10)
                assertEquals(expected.y, foot.y, 1e-10)
            } finally { context.close() }
        }
        // A legal controlled profile exposes old-h vs recomputed-h after the hard clamp on its first swing.
        val tinyLimit = com.rustedwarfare.reforge.core.content.MechDefinition(mech.body,
            mech.legs.map { it.copy(hardLimit = 1.0, liftOffset = 0.0) })
        val context = MechTestScene.open(MechTestScene.scenario(MechTestScene.with(source, tinyLimit)))
        try {
            context.submit(MoveAction("move", "p1", "u1", Position(500.0, 100.0), context.world))
            val foot = context.advance(Step(0, 1.0, "sim-tick")).units.single().mechPose!!.legs.first().foot
            assertEquals(113.856, foot.x, 1e-10) // target 112.056, clamp to 111.056, old h still requires a full 2.8 step.
        } finally { context.close() }
    }

    @Test fun a_nonzero_initial_foot_angle_accumulates_the_clamped_turn_increment() {
        val context = MechTestScene.open(MechTestScene.scenario(heading = 30.0))
        try {
            context.submit(MoveAction("turn", "p1", "u1", Position(500.0, 100.0), context.world))
            val swing = (0L..60L).flatMap { context.advance(Step(it, 1.0, "sim-tick")).units.single().mechPose!!.legs }
                .first { it.phase == MechLegPhase.SWINGING }
            assertEquals(37.0, swing.heading, 1e-10) // DEC old30 + helperDelta7, not assignment to7.
        } finally { context.close() }
    }

    @Test fun moving_feet_keep_the_world_anchor_then_warm_lift_and_swing_in_separate_steps() {
        val context = MechTestScene.open()
        try {
            val initial = assertNotNull(context.observePlayer(context.world, "p1").units.single().mechPose)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("east", "p1", "u1", Position(500.0, 100.0), context.world)))
            val poses = (0L..9L).map { step ->
                context.advance(Step(step, 1.0, "sim-tick"))
                assertNotNull(context.observePlayer(context.world, "p1").units.single().mechPose).also {
                    println("MECH_WARM q=${step + 1} $it")
                }
            }
            // Literal DEC-P004-001 worked example: initial .1, warm 6q, .65 lift, strict >2 swing gate.
            val heights = listOf(0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.75, 1.4, 2.05, 2.05)
            poses.forEachIndexed { i, pose -> assertEquals(heights[i], pose.legs.first().height, 1e-12, "q=${i + 1}") }
            poses.take(9).forEach { assertEquals(Position(100.0, 92.0), it.legs.first().foot) }
            assertEquals(102.8, poses.last().legs.first().foot.x, 1e-12)
            assertEquals(92.0, poses.last().legs.first().foot.y, 1e-12)
            assertEquals(0.1, initial.legs.first().height) // Earlier observation remains a snapshot.
        } finally { context.close() }
    }

    @Test fun authorized_initial_pose_has_independent_body_heading_and_rotated_shoulders_and_feet() {
        for ((heading, feet, shoulders) in listOf(
            Triple(0.0, listOf(Position(92.0, 100.0), Position(108.0, 100.0)),
                listOf(Position(97.0, 100.0), Position(103.0, 100.0))),
            Triple(90.0, listOf(Position(100.0, 92.0), Position(100.0, 108.0)),
                listOf(Position(100.0, 97.0), Position(100.0, 103.0))),
        )) {
            val context = MechTestScene.open(MechTestScene.scenario(heading = heading))
            try {
                val pose = assertNotNull(context.observePlayer(context.world, "p1").units.single().mechPose)
                assertEquals(heading, pose.forwardHeading)
                assertEquals(false, pose.reversing)
                pose.legs.forEachIndexed { i, leg ->
                    assertEquals(feet[i].x, leg.foot.x, 1e-12)
                    assertEquals(feet[i].y, leg.foot.y, 1e-12)
                    assertEquals(shoulders[i].x, leg.anchor.x, 1e-12)
                    assertEquals(shoulders[i].y, leg.anchor.y, 1e-12)
                    assertEquals(0.1, leg.height)
                    assertEquals(heading, leg.heading)
                }
            } finally { context.close() }
        }
    }
}
