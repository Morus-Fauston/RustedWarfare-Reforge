package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class LandingCraftExitStateTest {
    @Test fun the_previous_carrier_reference_expires_after_forty_five_active_updates() = WaterTestScene.withMap { map ->
        val context = LandingCraftScene.open(LandingCraftScene.scenario(map, spawns = listOf(
            UnitSpawn("a", "p1", "builder", Position(200.0, 150.0), 90.0),
            UnitSpawn("craft", "p1", "hovercraft", Position(190.0, 150.0), 90.0))))
        try {
            assertIs<ActionResult.Accepted>(context.submit(LoadAction("board", "p1", "a", "craft", LoadDirection.INTO, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnloadAction("unload", "p1", "craft", true, context.world)))
            repeat(30) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertEquals(TransportExitObservation("craft", 45f), context.observe().units.single { it.unitId == "a" }.transportExit)
            repeat(44) { context.advance(Step(it + 30L, 1.0, "sim-tick")) }
            assertEquals(TransportExitObservation("craft", 1f), context.observe().units.single { it.unitId == "a" }.transportExit)
            context.advance(Step(74, 1.0, "sim-tick"))
            assertNull(context.observe().units.single { it.unitId == "a" }.transportExit)
        } finally { context.close() }
    }
}
