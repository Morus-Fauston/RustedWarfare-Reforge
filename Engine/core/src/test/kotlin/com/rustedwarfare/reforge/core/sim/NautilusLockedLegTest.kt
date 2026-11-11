package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class NautilusLockedLegTest {
    @Test fun water_conversion_retracts_the_same_six_feet_without_lifting_or_land_prediction() = WaterTestScene.withMap { map ->
        val c = NautilusScene.open(NautilusScene.scenario(map).copy(units = listOf(
            UnitSpawn("mother", "p1", "nautilusSubmarineLand", Position(500.0, 180.0), 90.0))))
        try {
            val landFoot = c.observe().units.single().mechPose!!.legs.first().foot
            assertEquals(Position(500.0, 160.0), landFoot)
            val converted = c.advance(Step(0, 1.0, "sim-tick")).units.single()
            assertEquals("nautilusSubmarineSurface", converted.typeId)
            assertEquals(landFoot, converted.mechPose!!.legs.first().foot)
            val retracting = c.advance(Step(1, 1.0, "sim-tick")).units.single().mechPose!!.legs.first()
            assertEquals(Position(500.0, 160.3), retracting.foot)
            assertEquals(0.0, retracting.height)
            assertEquals(MechLegPhase.SWINGING, retracting.phase)
        } finally { c.close() }
    }
}
