package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class NautilusAutomaticConversionTest {
    @Test fun shoreline_automatic_conversion_uses_ground_water_truth_and_full_section_order_not_height() = WaterTestScene.withMap { map ->
        for ((from, position, expected) in listOf(
            Triple("nautilusSubmarineLand", Position(500.0, 180.0), "nautilusSubmarineSurface"),
            Triple("nautilusSubmarineSurface", Position(200.0, 180.0), "nautilusSubmarineLand"))) {
            val scenario = NautilusScene.scenario(map).copy(units = listOf(
                UnitSpawn("mother", "p1", from, position, 90.0, -8.0)))
            val c = NautilusScene.open(scenario)
            try {
                assertEquals(from, c.observe().units.single().typeId)
                val next = c.advance(Step(0, 1.0, "sim-tick")).units.single()
                assertEquals(expected, next.typeId)
                assertEquals("mother", next.unitId)
                assertEquals(position, next.position)
                assertTrue(next.height < 0.0, "Conversion does not replace current height with the new target")
                assertEquals(60f, next.unitActions!!.automaticCooldown)
                assertTrue(c.observe().events.any { it.kind == "unit-action-automatic" })
            } finally { c.close() }
        }
    }
}
