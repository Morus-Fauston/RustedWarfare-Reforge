package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class DropshipPlayerProjectionTest {
    @Test fun only_the_owner_receives_the_manifest_and_loading_orders_do_not_leak_through_visible_carrier_events() = WaterTestScene.withMap { map ->
        val context = DropshipScene.open(DropshipScene.scenario(map, spawns = listOf(
            UnitSpawn("craft", "p1", "dropship", Position(190.0, 150.0), 90.0),
            UnitSpawn("worker", "p1", "builder", Position(200.0, 150.0), 90.0))))
        try {
            assertIs<ActionResult.Rejected>(context.submit(LoadAction("enemy-order", "p2", "worker", "craft", LoadDirection.INTO, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(LoadAction("load", "p1", "worker", "craft", LoadDirection.INTO, context.world)))
            val owner = context.observePlayer(context.world, "p1")
            val enemy = context.observePlayer(context.world, "p2")
            assertEquals(listOf("worker"), owner.units.single { it.unitId == "craft" }.transport!!.passengerIds)
            assertNull(enemy.units.single { it.unitId == "craft" }.transport)
            assertTrue(enemy.units.none { it.unitId == "worker" })
            assertTrue(enemy.events.none { it.kind == "unit-loaded" })
            assertIs<ActionResult.Accepted>(context.submit(UnloadAction("unload", "p1", "craft", true, context.world)))
            assertTrue(context.observePlayer(context.world, "p2").events.none { it.kind == "unload-started" })
            assertIs<ActionResult.Rejected>(context.submit(MoveAction("hidden-control", "p2", "worker", Position(250.0, 150.0), context.world)))
        } finally { context.close() }
    }
}
