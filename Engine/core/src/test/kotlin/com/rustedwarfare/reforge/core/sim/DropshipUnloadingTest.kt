package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class DropshipUnloadingTest {
    @Test fun high_air_unload_intent_waits_for_landing_before_its_thirty_tick_clock_runs() = WaterTestScene.withMap { map ->
        val context = DropshipScene.open(DropshipScene.scenario(map, listOf(
            UnitSpawn("air", "p1", "dropship", Position(190.0, 150.0), 90.0),
            UnitSpawn("a", "p1", "builder", Position(200.0, 150.0), 90.0))))
        try {
            assertIs<ActionResult.Accepted>(context.submit(LoadAction("board", "p1", "a", "air", LoadDirection.INTO, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("fly", "p1", "air", Position(350.0, 150.0), context.world)))
            repeat(20) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertTrue(context.observe().units.single { it.unitId == "air" }.height > 6.9)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "air", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnloadAction("unload", "p1", "air", true, context.world)))
            repeat(8) { context.advance(Step(it + 20L, 1.0, "sim-tick")) }
            val landing = context.observe().units.single { it.unitId == "air" }
            assertTrue(landing.height < 4.0)
            assertEquals(30f, landing.transport!!.unloadRemaining)
            repeat(29) { context.advance(Step(it + 28L, 1.0, "sim-tick")) }
            assertEquals("air", context.observe().units.single { it.unitId == "a" }.carriedBy)
            context.advance(Step(57, 1.0, "sim-tick"))
            assertNull(context.observe().units.single { it.unitId == "a" }.carriedBy)
        } finally { context.close() }
    }
}
