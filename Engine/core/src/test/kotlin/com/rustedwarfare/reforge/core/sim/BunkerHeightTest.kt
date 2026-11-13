package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class BunkerHeightTest {
    @Test fun nonair_height_approaches_three_then_descends_after_deployment_without_a_conversion_teleport() = WaterTestScene.withMap { map ->
        val context = AaBeamScene.open(BunkerScene.scenario(map))
        try {
            assertEquals(0.0, context.observe().units.single().height)
            val first = context.advance(Step(0, 1.0, "sim-tick")).units.single()
            assertEquals(.342f.toDouble(), first.height)
            assertNull(first.flightPose)
            for (i in 1L..29L) context.advance(Step(i, 1.0, "sim-tick"))
            assertEquals(3.0, context.observe().units.single().height)
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("deploy", "p1", "bunker", "deploy", context.world)))
            for (i in 30L..196L) context.advance(Step(i, 1.0, "sim-tick"))
            assertEquals("mechBunkerDeployed", context.observe().units.single().typeId)
            assertEquals(3.0, context.observe().units.single().height)
            val descending = context.advance(Step(197, 1.0, "sim-tick")).units.single()
            assertEquals((3f - .145f).toDouble(), descending.height)
            for (i in 198L..230L) context.advance(Step(i, 1.0, "sim-tick"))
            assertEquals(0.0, context.observe().units.single().height)
        } finally { context.close() }
    }
}
