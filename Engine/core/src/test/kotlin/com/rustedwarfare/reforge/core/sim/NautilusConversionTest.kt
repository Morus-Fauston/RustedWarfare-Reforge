package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class NautilusConversionTest {
    @Test fun surface_and_dive_are_queued_same_object_conversions_with_current_factory_speed() = WaterTestScene.withMap { map ->
        val c = NautilusScene.open(NautilusScene.scenario(map))
        try {
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("surface", "p1", "mother", "surface", c.world)))
            assertEquals("nautilusSubmarine", c.observe().units.single().typeId)
            val surface = c.advance(Step(0, 1.0, "sim-tick")).units.single()
            assertEquals("nautilusSubmarineSurface", surface.typeId)
            assertEquals("mother", surface.unitId)
            assertEquals(Position(500.0, 180.0), surface.position)
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("dive", "p1", "mother", "dive", c.world)))
            for (step in 1L..134L) c.advance(Step(step, 1.0, "sim-tick"))
            val before = c.observe().units.single()
            assertEquals("nautilusSubmarineSurface", before.typeId)
            assertTrue(checkNotNull(before.unitActions!!.actions.single().progress) < 1f)
            val sub = c.advance(Step(135, 1.0, "sim-tick")).units.single()
            assertEquals("nautilusSubmarine", sub.typeId)
            assertEquals("mother", sub.unitId)
            assertEquals(4000.0, sub.health)
            assertEquals(EnergyObservation(6f, 6f, false), sub.energy)
            assertEquals(Position(500.0, 180.0), sub.position)
        } finally { c.close() }
    }
}
