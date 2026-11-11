package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class NautilusSurfaceLoadingTest {
    @Test fun surface_accepts_three_ordinary_hover_passengers_and_preserves_them_after_diving() = WaterTestScene.withMap { map ->
        val spawns = listOf(UnitSpawn("mother", "p1", "nautilusSubmarineSurface", Position(500.0, 180.0), 90.0)) +
            (1..4).map { UnitSpawn("h$it", "p1", "hoverTank", Position(510.0, 180.0), 90.0) }
        val c = NautilusScene.open(NautilusScene.scenario(map).copy(units = spawns,
            combatContract = CombatContract(12345, -1, spawns.mapIndexed { i, u -> u.unitId to i.toLong() + 8L }.toMap(), 13L)))
        try {
            for (index in 1..3) assertIs<ActionResult.Accepted>(c.submit(
                LoadAction("load$index", "p1", "h$index", "mother", LoadDirection.INTO, c.world)))
            val full = c.observe().units.single { it.unitId == "mother" }.transport!!
            assertEquals(listOf("h1", "h2", "h3"), full.passengerIds)
            assertEquals(3, full.capacity)
            val denied = assertIs<ActionResult.Rejected>(c.submit(
                LoadAction("full", "p1", "h4", "mother", LoadDirection.INTO, c.world)))
            assertEquals("transport-full", denied.error.code)
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("dive", "p1", "mother", "dive", c.world)))
            repeat(135) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val submerged = c.observe().units.single { it.unitId == "mother" }
            assertEquals("nautilusSubmarine", submerged.typeId)
            assertEquals(full.passengerIds, submerged.transport!!.passengerIds)
            assertFalse(submerged.transport.canUnload)
            assertEquals("invalid-passenger", assertIs<ActionResult.Rejected>(c.submit(
                LoadAction("sub-load", "p1", "h4", "mother", LoadDirection.INTO, c.world))).error.code)
        } finally { c.close() }
    }
}
