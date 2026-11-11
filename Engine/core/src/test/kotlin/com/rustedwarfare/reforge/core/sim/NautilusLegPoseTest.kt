package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class NautilusLegPoseTest {
    @Test fun surfaced_body_exposes_six_feet_and_conversion_reconciles_the_pose_without_changing_identity() = WaterTestScene.withMap { map ->
        val c = NautilusScene.open(NautilusScene.scenario(map))
        try {
            assertNull(c.observe().units.single().mechPose)
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("surface", "p1", "mother", "surface", c.world)))
            val surfaced = c.advance(Step(0, 1.0, "sim-tick")).units.single()
            assertEquals(6, assertNotNull(surfaced.mechPose).legs.size)
            assertEquals("mother", surfaced.unitId)
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("dive", "p1", "mother", "dive", c.world)))
            for (step in 1L..135L) c.advance(Step(step, 1.0, "sim-tick"))
            assertNull(c.observe().units.single().mechPose)
        } finally { c.close() }
    }

    @Test fun land_walk_uses_six_leg_lift_swing_and_plant_phases() = WaterTestScene.withMap { map ->
        val c = NautilusScene.open(NautilusScene.scenario(map).copy(units = listOf(
            UnitSpawn("mother", "p1", "nautilusSubmarineLand", Position(200.0, 180.0), 90.0))))
        try {
            assertEquals(6, c.observe().units.single().mechPose!!.legs.size)
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("walk", "p1", "mother", Position(360.0, 180.0), c.world)))
            val phases = mutableSetOf<MechLegPhase>()
            repeat(160) { step ->
                val unit = c.advance(Step(step.toLong(), 1.0, "sim-tick")).units.single()
                phases += unit.mechPose!!.legs.map { it.phase }
            }
            assertTrue(MechLegPhase.LIFTING in phases)
            assertTrue(MechLegPhase.SWINGING in phases)
            assertTrue(MechLegPhase.PLANTED in phases)
        } finally { c.close() }
    }
}
