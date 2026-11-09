package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class DropshipEnvironmentTest {
    @Test fun idle_liquid_flight_cannot_unload_and_a_normal_return_to_dry_ground_lands() {
        for (liquid in listOf("water", "lava", "lava-cliff")) WaterTestScene.withMap(liquidProperty = liquid) { map ->
            val context = DropshipScene.open(DropshipScene.scenario(map, listOf(
                UnitSpawn("air", "p1", "dropship", Position(405.0, 150.0), 90.0),
                UnitSpawn("worker", "p1", "builder", Position(390.0, 150.0), 90.0))))
            try {
                assertIs<ActionResult.Accepted>(context.submit(LoadAction("board", "p1", "worker", "air", LoadDirection.INTO, context.world)))
                assertEquals("cannot-unload", assertIs<ActionResult.Rejected>(context.submit(
                    UnloadAction("wet-unload", "p1", "air", true, context.world))).error.code)
                repeat(120) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                val wet = context.observe().units.single { it.unitId == "air" }
                assertTrue(wet.height in 33.4..36.6)
                assertFalse(wet.transport!!.canUnload)
                assertEquals("air", context.observe().units.single { it.unitId == "worker" }.carriedBy)
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("return", "p1", "air", Position(300.0, 250.0), context.world)))
                repeat(400) { context.advance(Step(it + 120L, 1.0, "sim-tick")) }
                val dry = context.observe().units.single { it.unitId == "air" }
                assertTrue(dry.position.x < 350.0)
                assertTrue(dry.tasks.isEmpty())
                assertEquals(2.0, dry.height)
                assertTrue(dry.transport!!.canUnload)
                assertIs<ActionResult.Accepted>(context.submit(UnloadAction("dry-unload", "p1", "air", true, context.world)))
            } finally { context.close() }
        }
    }

    @Test fun four_slots_owner_permissions_and_world_reopen_use_the_shared_transport_relation() = WaterTestScene.withMap { map ->
        val input = DropshipScene.scenario(map, listOf(
            UnitSpawn("air", "p1", "dropship", Position(190.0, 150.0), 90.0),
            UnitSpawn("other", "p1", "hovercraft", Position(190.0, 150.0), 90.0)) +
            (1..5).map { UnitSpawn("crew-$it", "p1", "builder", Position(200.0, 150.0), 90.0) })
        val context = DropshipScene.open(input)
        val oldWorld = context.world
        try {
            assertIs<ActionResult.Rejected>(context.submit(LoadAction("foreign", "p2", "crew-1", "air", LoadDirection.INTO, context.world)))
            assertIs<ActionResult.Rejected>(context.submit(LoadAction("nested", "p1", "air", "other", LoadDirection.INTO, context.world)))
            for (id in 1..4) assertIs<ActionResult.Accepted>(context.submit(LoadAction("board-$id", "p1", "crew-$id", "air", LoadDirection.INTO, context.world)))
            val full = context.observe().units.single { it.unitId == "air" }.transport!!
            assertEquals(4, full.passengerCount)
            assertEquals(4, full.occupiedSlots)
            assertEquals("transport-full", assertIs<ActionResult.Rejected>(context.submit(
                LoadAction("overflow", "p1", "crew-5", "air", LoadDirection.INTO, context.world))).error.code)
            assertEquals(full, context.observe().units.single { it.unitId == "air" }.transport)
            assertIs<ActionResult.Rejected>(context.submit(MoveAction("cargo-move", "p1", "crew-1", Position(300.0, 150.0), context.world)))
            assertIs<ActionResult.Rejected>(context.submit(AttackAction("unarmed", "p1", "air", "crew-5", context.world)))
        } finally { context.close() }
        val reopened = DropshipScene.open(input)
        try {
            assertNotEquals(oldWorld, reopened.world)
            assertEquals(0, reopened.observe().units.single { it.unitId == "air" }.transport!!.passengerCount)
            assertTrue(reopened.observe().units.all { it.carriedBy == null })
            assertIs<ActionResult.Rejected>(reopened.submit(UnloadAction("stale", "p1", "air", true, oldWorld)))
        } finally { reopened.close() }
    }
}
