package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class NautilusActionAdmissionTest {
    @Test fun dive_requires_water_in_menu_and_submission_before_automatic_shore_conversion() = WaterTestScene.withMap { map ->
        val scenario = NautilusScene.scenario(map).copy(units = listOf(
            UnitSpawn("mother", "p1", "nautilusSubmarineSurface", Position(200.0, 180.0), 90.0)))
        val c = NautilusScene.open(scenario)
        try {
            assertFalse(c.observe().units.single().unitActions!!.actions.single().available)
            val denied = assertIs<ActionResult.Rejected>(c.submit(UnitAction("dive", "p1", "mother", "dive", c.world)))
            assertEquals("unit-action-requires-water", denied.error.code)
            assertNull(c.observe().units.single().unitActions!!.actions.single().queueIndex)
        } finally { c.close() }
    }

    @Test fun queued_dive_can_be_cancelled_without_conversion_or_resource_change() = WaterTestScene.withMap { map ->
        val c = NautilusScene.open(NautilusScene.scenario(map))
        try {
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("surface", "p1", "mother", "surface", c.world)))
            c.advance(Step(0, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("dive", "p1", "mother", "dive", c.world)))
            c.advance(Step(1, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("cancel", "p1", "mother", "dive", c.world, cancelQueued = true)))
            val next = c.advance(Step(2, 1.0, "sim-tick")).units.single()
            assertEquals("nautilusSubmarineSurface", next.typeId)
            assertNull(next.unitActions!!.actions.single().queueIndex)
            assertTrue(next.unitActions!!.actions.single().available)
            assertEquals(EnergyObservation(6f, 6f, false), next.energy)
        } finally { c.close() }
    }
}
