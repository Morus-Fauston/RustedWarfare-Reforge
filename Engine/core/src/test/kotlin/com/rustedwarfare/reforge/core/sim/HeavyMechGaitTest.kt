package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class HeavyMechGaitTest {
    @Test fun four_feet_predict_the_sliding_direction_then_land_after_stop_without_a_warmup() {
        val context = HeavyMechTestScene.open()
        try {
            val initial = assertNotNull(context.observePlayer(context.world, "p1").units.single().mechPose)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("east", "p1", "heavy", Position(600.0, 200.0), context.world)))
            val poses = (0L..7L).map { step ->
                context.advance(Step(step, 1.0, "sim-tick"))
                assertNotNull(context.observePlayer(context.world, "p1").units.single().mechPose)
            }
            // Qualified zero-warmup / .3 lift / 2.9 swing witness, initially facing north and sliding east.
            listOf(.4, .7, 1.0, 1.3, 1.6, 1.9, 2.2, 2.2).forEachIndexed { q, height ->
                assertEquals(height, poses[q].legs[0].height, 1e-10, "q=${q + 1}")
            }
            assertTrue(poses.take(7).all { it.legs[0].foot == Position(182.0, 185.0) })
            val swing = poses.last().legs[0]
            assertEquals(MechLegPhase.SWINGING, swing.phase)
            assertTrue(swing.foot.x > 184.5, "The first swing follows eastward momentum: $swing")
            assertTrue(swing.foot.y > 184.0, "A north-facing body must not aim the foot north: $swing")
            val allPoses = poses + (8L..159L).map { step ->
                context.advance(Step(step, 1.0, "sim-tick"))
                assertNotNull(context.observePlayer(context.world, "p1").units.single().mechPose)
            }
            for (leg in 0..3) assertTrue(allPoses.any { it.legs[leg].phase == MechLegPhase.SWINGING }, "leg=$leg")
            assertTrue(allPoses.flatMap { it.legs }.none { it.phase == MechLegPhase.WARMING_UP })
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "heavy", context.world)))
            for (step in 160L..259L) context.advance(Step(step, 1.0, "sim-tick"))
            val last = context.observePlayer(context.world, "p1").units.single()
            assertEquals(0.0, last.speed)
            assertTrue(assertNotNull(last.mechPose).legs.all { it.height == 0.0 && it.phase == MechLegPhase.PLANTED })
            assertTrue(initial.legs.all { it.height == .1 && it.phase == MechLegPhase.LOWERING })
        } finally { context.close() }
    }
}
