package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class DropshipExitTest {
    @Test fun odd_cargo_side_keeps_the_native_asymmetric_x_formula_at_all_cardinal_headings() = WaterTestScene.withMap { map ->
        // Independently substituted native theta -90, 0, 90, 180; side +7.
        val cases = listOf(
            Triple(0.0, Position(197.0, 159.0), Position(190.0, 216.0)),
            Triple(90.0, Position(188.0, 150.0), Position(124.0, 150.0)),
            Triple(180.0, Position(183.0, 141.0), Position(190.0, 84.0)),
            Triple(270.0, Position(192.0, 150.0), Position(256.0, 150.0)))
        for ((heading, point, target) in cases) {
            val context = DropshipScene.open(DropshipScene.scenario(map, listOf(
                UnitSpawn("z", "p1", "dropship", Position(190.0, 150.0), heading),
                UnitSpawn("a", "p1", "builder", Position(200.0, 150.0), heading))))
            try {
                assertIs<ActionResult.Accepted>(context.submit(LoadAction("board", "p1", "a", "z", LoadDirection.INTO, context.world)))
                assertIs<ActionResult.Accepted>(context.submit(UnloadAction("unload", "p1", "z", true, context.world)))
                repeat(30) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
                val passenger = context.observe().units.single { it.unitId == "a" }
                assertNull(passenger.carriedBy)
                assertEquals(point.x, passenger.position.x, .03, "Heading $heading")
                assertEquals(point.y, passenger.position.y, .03, "Heading $heading")
                assertEquals(target.x, assertNotNull(passenger.moveTarget).x, .03)
                assertEquals(target.y, assertNotNull(passenger.moveTarget).y, .03)
            } finally { context.close() }
        }
    }

    @Test fun air_transport_releases_the_tail_at_its_native_asymmetric_point_and_orders_sixty_six_from_carrier() = WaterTestScene.withMap { map ->
        val context = DropshipScene.open(DropshipScene.scenario(map, listOf(
            UnitSpawn("z", "p1", "dropship", Position(190.0, 150.0), 90.0),
            UnitSpawn("a", "p1", "builder", Position(200.0, 150.0), 90.0),
            UnitSpawn("b", "p1", "builder", Position(200.0, 150.0), 90.0))))
        try {
            for (id in listOf("a", "b")) assertIs<ActionResult.Accepted>(context.submit(
                LoadAction("board-$id", "p1", id, "z", LoadDirection.INTO, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UnloadAction("unload", "p1", "z", true, context.world)))
            repeat(29) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertEquals("z", context.observe().units.single { it.unitId == "b" }.carriedBy)
            val released = context.advance(Step(29, 1.0, "sim-tick")).units.single { it.unitId == "b" }
            assertNull(released.carriedBy)
            // Native theta 0 (screen heading 90): even side puts both side terms in x.
            assertEquals(174.0, released.position.x, .03)
            assertEquals(150.0, released.position.y, .03)
            assertEquals(270.0, released.heading)
            assertEquals(124.0, assertNotNull(released.moveTarget).x, .03)
            assertEquals(150.0, assertNotNull(released.moveTarget).y, .03)
            assertEquals(TransportExitObservation("z", 45f), released.transportExit)
            assertEquals("z", context.observe().units.single { it.unitId == "a" }.carriedBy)
            assertIs<ActionResult.Accepted>(context.submit(UnloadAction("cancel", "p1", "z", false, context.world)))
            repeat(40) { context.advance(Step(it + 30L, 1.0, "sim-tick")) }
            assertEquals("z", context.observe().units.single { it.unitId == "a" }.carriedBy)
            assertIs<ActionResult.Accepted>(context.submit(SelectAction("select", "p1", "b", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "b", context.world)))
        } finally { context.close() }
    }
}
