package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class NautilusSurfaceProductionTest {
    @Test fun surfaced_mother_releases_a_product_at_a_legal_exit_with_one_birth_and_one_payment() = WaterTestScene.withMap { map ->
        val scenario = NautilusScene.scenario(map).let { s ->
            s.copy(units = s.units.map { it.copy(typeId = "nautilusSubmarineSurface") })
        }
        val c = NautilusScene.open(scenario)
        try {
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", "mother", "hoverTank", "c".repeat(32), c.world)))
            repeat(250) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val observation = c.observe()
            val parent = observation.units.single { it.unitId == "mother" }
            val child = observation.units.single { it.unitId != "mother" }
            assertNull(child.carriedBy)
            assertTrue(checkNotNull(parent.transport!!.passengerIds).isEmpty())
            assertEquals(4550L, c.observePlayer(c.world, "p1").balance)
            assertEquals(9L, observation.production!!.units.single().objectId)
            assertFalse(observation.events.any { it.kind == "unit-loaded" })
        } finally { c.close() }
    }
}
