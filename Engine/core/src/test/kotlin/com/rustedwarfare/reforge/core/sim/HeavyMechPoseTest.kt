package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class HeavyMechPoseTest {
    @Test fun a_loaded_land_sliding_variant_initializes_four_ordered_world_feet_and_shoulders() {
        val context = HeavyMechTestScene.open()
        try {
            val initial = context.observe()
            val unit = initial.units.single()
            val pose = assertNotNull(unit.mechPose)
            assertEquals(listOf(0, 1, 2, 3), pose.legs.map { it.index })
            assertEquals(listOf(Position(182.0,185.0), Position(218.0,185.0), Position(182.0,215.0), Position(218.0,215.0)), pose.legs.map { it.foot })
            assertEquals(listOf(Position(195.0,190.0), Position(205.0,190.0), Position(195.0,210.0), Position(205.0,210.0)), pose.legs.map { it.anchor })
            assertTrue(pose.legs.all { it.height == .1 && it.heading == 0.0 })
            assertEquals(0.0, pose.forwardHeading)
            assertEquals(0.0, unit.height)
            assertNull(unit.flightPose)
            context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(listOf(.1, .1, .1, .1), initial.units.single().mechPose!!.legs.map { it.height })
        } finally { context.close() }
    }
}
