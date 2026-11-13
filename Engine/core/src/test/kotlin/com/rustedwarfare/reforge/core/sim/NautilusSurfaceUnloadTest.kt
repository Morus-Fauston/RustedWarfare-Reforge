package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class NautilusSurfaceUnloadTest {
    @Test fun unloading_assigns_current_parent_height_before_the_passengers_next_update() = WaterTestScene.withMap { map ->
        val spawns = listOf(UnitSpawn("a", "p1", "hoverTank", Position(510.0, 180.0), 90.0, -8.0),
            UnitSpawn("mother", "p1", "nautilusSubmarineSurface", Position(500.0, 180.0), 90.0, 2.0))
        val c = NautilusScene.open(NautilusScene.scenario(map).copy(units = spawns,
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "mother" to 9L), 10L)))
        try {
            assertIs<ActionResult.Accepted>(c.submit(LoadAction("board", "p1", "a", "mother", LoadDirection.INTO, c.world)))
            assertIs<ActionResult.Accepted>(c.submit(UnloadAction("unload", "p1", "mother", true, c.world)))
            repeat(30) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val observation = c.observe()
            val child = observation.units.single { it.unitId == "a" }
            val parent = observation.units.single { it.unitId == "mother" }
            assertNull(child.carriedBy)
            assertEquals(parent.height, child.height)
            assertTrue(child.height > 0.0, "The old submerged passenger height must be replaced")
            assertEquals(Position(529.997314453125, 187.01150512695312), child.position)
            assertEquals(45f, child.transportExit!!.remaining)
        } finally { c.close() }
    }

    @Test fun retained_child_is_released_only_by_surface_liquid_unload_with_custom_exit_geometry() = WaterTestScene.withMap { map ->
        val c = NautilusScene.open(NautilusScene.scenario(map))
        try {
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", "mother", "hoverTank", "a".repeat(32), c.world)))
            repeat(250) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("surface", "p1", "mother", "surface", c.world)))
            c.advance(Step(250, 1.0, "sim-tick"))
            assertTrue(c.observe().units.single { it.unitId == "mother" }.transport!!.canUnload)
            assertIs<ActionResult.Accepted>(c.submit(UnloadAction("unload", "p1", "mother", true, c.world)))
            for (step in 251L..279L) c.advance(Step(step, 1.0, "sim-tick"))
            assertEquals("mother", c.observe().units.single { it.unitId != "mother" }.carriedBy)
            c.enableMotionDiagnostics(c.world)
            val released = c.advance(Step(280, 1.0, "sim-tick"))
            val child = released.units.single { it.unitId != "mother" }
            assertNull(child.carriedBy)
            // Produced children update after their parent, so the public motion sample isolates the exit.
            val exit = c.drainMotionDiagnostics(c.world).single { it.unitId == child.unitId }
            assertEquals(Position(529.997314453125, 187.01150512695312), exit.positionBefore)
            assertEquals(90.0, exit.headingBefore)
            assertEquals("mother", child.transportExit!!.previousCarrierId)
            assertEquals(44f, child.transportExit.remaining)
            assertEquals(4550L, c.observePlayer(c.world, "p1").balance)
            assertTrue(released.events.any { it.kind == "unit-unloaded" && it.unitId == child.unitId })
        } finally { c.close() }
    }
}
