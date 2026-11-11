package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class NautilusSharedQueueTest {
    @Test fun conversion_preempts_paid_production_preserving_progress_and_completion_has_no_overflow() = WaterTestScene.withMap { map ->
        val c = NautilusScene.open(NautilusScene.scenario(map))
        try {
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", "mother", "hoverTank", "a".repeat(32), c.world)))
            c.advance(Step(0, 1.0, "sim-tick"))
            val saved = c.observe().economy!!.queuedProduction.getValue("mother").single().progress
            assertEquals(.004f, saved)
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("surface", "p1", "mother", "surface", c.world)))
            c.advance(Step(1, 1.0, "sim-tick"))
            assertEquals("nautilusSubmarineSurface", c.observe().units.single().typeId)
            assertEquals(saved, c.observe().economy!!.queuedProduction.getValue("mother").single().progress)
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("dive", "p1", "mother", "dive", c.world)))
            for (step in 2L..136L) c.advance(Step(step, 1.0, "sim-tick"))
            assertEquals("nautilusSubmarine", c.observe().units.single().typeId)
            assertEquals(saved, c.observe().economy!!.queuedProduction.getValue("mother").single().progress)
            c.advance(Step(137, 1.0, "sim-tick"))
            assertEquals(.008f, c.observe().economy!!.queuedProduction.getValue("mother").single().progress)
            assertEquals(4550L, c.observePlayer(c.world, "p1").balance)
        } finally { c.close() }
    }
}
