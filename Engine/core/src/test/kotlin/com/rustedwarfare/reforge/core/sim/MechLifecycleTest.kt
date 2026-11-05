package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MechDefinition
import kotlin.test.*

class MechLifecycleTest {
    @Test fun finite_input_coordinates_that_overflow_the_initial_pose_are_rejected_without_opening_a_world() {
        val source = MechTestScene.unit()
        val mech = assertNotNull(source.mech)
        for (anchors in listOf(false, true)) {
            val profile = MechDefinition(mech.body, mech.legs.map {
                if (anchors) it.copy(attachX = Double.MAX_VALUE, attachY = Double.MAX_VALUE)
                else it.copy(x = Double.MAX_VALUE, y = Double.MAX_VALUE)
            })
            val context = MechTestScene.open()
            context.close()
            assertIs<InitResult.Rejected>(context.initialize(MechTestScene.scenario(MechTestScene.with(source, profile), heading = 45.0)))
            assertIs<InitResult.Accepted>(context.initialize(MechTestScene.scenario()))
            assertTrue(context.observePlayer(context.world, "p1").units.single().mechPose!!.legs.all {
                it.foot.x.isFinite() && it.anchor.x.isFinite() })
            context.close()
        }
    }

    @Test fun later_anchor_rotation_overflow_rolls_back_before_exposing_a_non_finite_pose() {
        val source = MechTestScene.unit()
        val mech = assertNotNull(source.mech)
        val profile = MechDefinition(mech.body, mech.legs.map { it.copy(attachX = Double.MAX_VALUE, attachY = Double.MAX_VALUE) })
        val context = MechTestScene.open(MechTestScene.scenario(MechTestScene.with(source, profile), heading = 0.0))
        try {
            context.submit(MoveAction("east", "p1", "u1", Position(500.0, 100.0), context.world))
            val before = context.observePlayer(context.world, "p1")
            repeat(2) {
                assertFailsWith<ArithmeticException> { context.advance(Step(0, 1.0, "sim-tick")) }
                assertSame(before, context.observePlayer(context.world, "p1"))
            }
        } finally { context.close() }
    }

    @Test fun an_arithmetic_failure_rolls_back_all_units_poses_actions_and_animation_clocks() {
        val source = MechTestScene.unit()
        val mech = assertNotNull(source.mech)
        val bad = MechTestScene.with(source, MechDefinition(mech.body,
            mech.legs.map { it.copy(predictionMultiplier = Double.MAX_VALUE) }))
        val input = MechTestScene.scenario().copy(unitDefinitions = mapOf("mech" to source, "bad" to bad), units = listOf(
            UnitSpawn("a-good", "p1", "mech", Position(100.0, 100.0), 90.0),
            UnitSpawn("z-bad", "p1", "bad", Position(100.0, 200.0), 90.0)))
        val context = MechTestScene.open(input)
        try {
            context.submit(MoveAction("good-move", "p1", "a-good", Position(500.0, 100.0), context.world, atStep = 0))
            context.submit(MoveAction("bad-move", "p1", "z-bad", Position(500.0, 200.0), context.world, atStep = 0))
            val before = context.observe()
            val playerBefore = context.observePlayer(context.world, "p1")
            repeat(2) {
                assertFailsWith<ArithmeticException> { context.advance(Step(0, 1.0, "sim-tick")) }
                assertSame(before, context.observe())
                assertSame(playerBefore, context.observePlayer(context.world, "p1"))
                assertEquals(listOf(0.1, 0.1), before.units.first().mechPose!!.legs.map { it.height })
            }
        } finally { context.close() }
    }

    @Test fun immutable_pose_snapshots_survive_progress_and_close_reopen_resets_all_new_state() {
        val input = MechTestScene.scenario()
        val context = MechTestScene.open(input)
        try {
            val originalWorld = context.world
            val before = context.observePlayer(context.world, "p1").units.single().mechPose!!
            assertFailsWith<UnsupportedOperationException> { (before.legs as MutableList).clear() }
            repeat(400) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertEquals(90.0, before.forwardHeading)
            assertEquals(listOf(0.1, 0.1), before.legs.map { it.height })
            assertEquals(CloseResult.Closed, context.close())
            assertIs<InitResult.Accepted>(context.reopen(input))
            assertEquals(before, context.observePlayer(context.world, "p1").units.single().mechPose)
            assertFails { context.observePlayer(originalWorld, "p1") }
            val snapshot = context.observe()
            assertFailsWith<IllegalArgumentException> { context.advance(Step(0, Double.NaN, "sim-tick")) }
            assertSame(snapshot, context.observe())
        } finally { context.close() }
    }
}
