package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class LandingCraftUnloadingTest {
    @Test fun unloading_pops_the_tail_at_thirty_ticks_then_cancel_preserves_the_remaining_passenger() = WaterTestScene.withMap { map ->
        val context = LandingCraftScene.open(LandingCraftScene.scenario(map, spawns = listOf(
            UnitSpawn("craft", "p1", "hovercraft", Position(190.0, 150.0), 90.0),
            UnitSpawn("a", "p1", "builder", Position(200.0, 150.0), 90.0),
            UnitSpawn("b", "p1", "builder", Position(200.0, 150.0), 90.0))))
        try {
            for (id in listOf("a", "b")) assertIs<ActionResult.Accepted>(context.submit(
                LoadAction("load-$id", "p1", id, "craft", LoadDirection.INTO, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnloadAction("unload", "p1", "craft", true, context.world)))
            repeat(29) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertEquals(2, context.observe().units.single { it.unitId == "craft" }.transport!!.passengerCount)
            val first = context.advance(Step(29, 1.0, "sim-tick"))
            val b = first.units.single { it.unitId == "b" }
            assertNull(b.carriedBy)
            assertEquals("craft", first.units.single { it.unitId == "a" }.carriedBy)
            assertEquals(270.0, b.heading)
            assertTrue(b.position.x in 182.0..184.0 && b.position.y in 140.0..142.0, b.position.toString())
            assertNotNull(b.moveTarget)
            assertEquals(UnitTaskKind.MOVE, b.tasks.single().kind)
            assertIs<ActionResult.Accepted>(context.submit(UnloadAction("cancel", "p1", "craft", false, context.world)))
            repeat(40) { context.advance(Step(it + 30L, 1.0, "sim-tick")) }
            assertEquals("craft", context.observe().units.single { it.unitId == "a" }.carriedBy)
            assertEquals(1, context.observe().units.single { it.unitId == "craft" }.transport!!.passengerCount)
            assertFalse(context.observe().units.single { it.unitId == "craft" }.transport!!.unloading)
            assertTrue(context.observePlayer(context.world, "p1").units.any { it.unitId == "b" })
        } finally { context.close() }
    }
}
