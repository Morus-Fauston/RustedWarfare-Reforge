package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class LandingCraftUnloadGateTest {
    @Test fun unloading_pauses_during_normal_movement_and_resumes_after_stop() = WaterTestScene.withMap { map ->
        val context = LandingCraftScene.open(LandingCraftScene.scenario(map, spawns = listOf(
            UnitSpawn("a", "p1", "builder", Position(200.0, 150.0), 90.0),
            UnitSpawn("craft", "p1", "hovercraft", Position(190.0, 150.0), 90.0))))
        try {
            assertIs<ActionResult.Accepted>(context.submit(LoadAction("board", "p1", "a", "craft", LoadDirection.INTO, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnloadAction("unload", "p1", "craft", true, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "craft", Position(350.0, 150.0), context.world)))
            repeat(40) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val moving = context.observe().units.single { it.unitId == "craft" }.transport!!
            assertEquals(30f, moving.unloadRemaining)
            assertEquals(listOf("a"), moving.passengerIds)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "craft", context.world)))
            repeat(29) { context.advance(Step(it + 40L, 1.0, "sim-tick")) }
            assertEquals("craft", context.observe().units.single { it.unitId == "a" }.carriedBy)
            context.advance(Step(69, 1.0, "sim-tick"))
            assertNull(context.observe().units.single { it.unitId == "a" }.carriedBy)
        } finally { context.close() }
    }

    @Test fun the_start_action_is_rejected_on_liquid_without_changing_the_manifest() = WaterTestScene.withMap { map ->
        val context = LandingCraftScene.open(LandingCraftScene.scenario(map, spawns = listOf(
            UnitSpawn("a", "p1", "builder", Position(390.0, 150.0), 90.0),
            UnitSpawn("craft", "p1", "hovercraft", Position(405.0, 150.0), 90.0))))
        try {
            assertIs<ActionResult.Accepted>(context.submit(LoadAction("board", "p1", "a", "craft", LoadDirection.INTO, context.world)))
            val manifest = context.observe().units.single { it.unitId == "craft" }.transport
            assertEquals("cannot-unload", assertIs<ActionResult.Rejected>(context.submit(
                UnloadAction("unload", "p1", "craft", true, context.world))).error.code)
            assertEquals(manifest, context.observe().units.single { it.unitId == "craft" }.transport)
        } finally { context.close() }
    }
}
