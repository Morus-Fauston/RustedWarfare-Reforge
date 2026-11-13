package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class LandingCraftAdmissionTest {
    @Test fun foreign_building_water_air_carrier_and_already_carried_candidates_never_partially_enter_the_manifest() = WaterTestScene.withMap { map ->
        val input = LandingCraftScene.scenario(map, spawns = listOf(
            UnitSpawn("craft", "p1", "hovercraft", Position(390.0, 150.0), 90.0),
            UnitSpawn("other-craft", "p1", "hovercraft", Position(380.0, 150.0), 90.0),
            UnitSpawn("factory", "p1", "seaFactory", Position(510.0, 250.0), 0.0),
            UnitSpawn("air", "p1", "helicopter", Position(380.0, 180.0), 90.0),
            UnitSpawn("water", "p1", "attackSubmarine", Position(410.0, 180.0), 90.0),
            UnitSpawn("foreign", "p2", "builder", Position(380.0, 150.0), 90.0),
            UnitSpawn("extra", "p1", "builder", Position(380.0, 150.0), 90.0),
            UnitSpawn("worker", "p1", "builder", Position(380.0, 150.0), 90.0)))
        val context = LandingCraftScene.open(input)
        try {
            for (id in listOf("craft", "other-craft", "factory", "air", "water", "foreign")) {
                assertIs<ActionResult.Rejected>(context.submit(LoadAction("invalid-$id", "p1", "craft", id, LoadDirection.UP, context.world)), id)
                assertEquals(emptyList(), context.observe().units.single { it.unitId == "craft" }.transport!!.passengerIds, id)
                assertNull(context.observe().units.single { it.unitId == id }.carriedBy, id)
            }
            assertIs<ActionResult.Accepted>(context.submit(LoadAction("valid", "p1", "worker", "craft", LoadDirection.INTO, context.world)))
            assertIs<ActionResult.Rejected>(context.submit(LoadAction("again", "p1", "other-craft", "worker", LoadDirection.UP, context.world)))
            assertEquals(listOf("worker"), context.observe().units.single { it.unitId == "craft" }.transport!!.passengerIds)
            assertIs<ActionResult.Accepted>(context.submit(UnloadAction("unload", "p1", "craft", true, context.world)))
            assertIs<ActionResult.Rejected>(context.submit(LoadAction("while-unloading", "p1", "craft", "foreign", LoadDirection.UP, context.world)))
            val blocked = assertIs<ActionResult.Rejected>(context.submit(LoadAction("legal-while-unloading", "p1", "craft", "extra", LoadDirection.UP, context.world)))
            assertEquals("transport-unloading", blocked.error.code)
            assertEquals(listOf("worker"), context.observe().units.single { it.unitId == "craft" }.transport!!.passengerIds)
        } finally { context.close() }
    }
}
