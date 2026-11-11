package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class NautilusHeightTest {
    @Test fun the_water_body_uses_custom_ground_falling_and_target_drift_without_the_energy_aircraft_fall_branch() = WaterTestScene.withMap { map ->
        val c = NautilusScene.open(NautilusScene.scenario(map))
        try {
            val first = c.advance(Step(0, 1.0, "sim-tick")).units.single()
            // Current PC custom update: approach -.005, default falling .03, then clamp downward velocity underwater.
            assertEquals((-.005f - .03f).toDouble(), first.height)
            assertEquals(EnergyObservation(6f, 6f, false), first.energy)
            assertNull(first.flightPose)
            repeat(249) { c.advance(Step(it.toLong() + 1L, 1.0, "sim-tick")) }
            val settled = c.observe().units.single()
            assertTrue(settled.height in -8.11..-7.89)
            assertEquals(EnergyObservation(6f, 6f, false), settled.energy)
        } finally { c.close() }
    }
}
