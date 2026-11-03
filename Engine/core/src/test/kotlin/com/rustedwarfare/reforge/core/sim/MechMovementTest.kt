package com.rustedwarfare.reforge.core.sim

import kotlin.test.*
import kotlin.math.hypot

class MechMovementTest {
    @Test fun cardinal_diagonal_and_differently_oriented_starts_arrive_and_settle() {
        val targets = listOf(Position(500.0, 200.0), Position(100.0, 200.0), Position(300.0, 60.0),
            Position(300.0, 340.0), Position(450.0, 300.0), Position(150.0, 100.0))
        for (heading in listOf(0.0, 45.0, 90.0, 180.0, 270.0)) for (target in targets) {
            val context = MechTestScene.open(MechTestScene.scenario(heading = heading, position = Position(300.0, 200.0)))
            try {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "u1", target, context.world)))
                var arrived = 0
                var stable = 0
                for (step in 0L..900L) {
                    val observation = context.advance(Step(step, 1.0, "sim-tick"))
                    arrived += observation.events.count { it.kind == "movement-arrived" }
                    val unit = context.observePlayer(context.world, "p1").units.single()
                    assertTrue(unit.position.x.isFinite() && unit.position.y.isFinite())
                    stable = if (unit.moveTarget == null && unit.speed == 0.0 &&
                        unit.mechPose!!.legs.all { it.phase == MechLegPhase.PLANTED && it.height == 0.0 }) stable + 1 else 0
                    if (stable >= 20) break
                }
                val unit = context.observePlayer(context.world, "p1").units.single()
                println("MECH_ARRIVAL heading=$heading target=$target final=$unit arrived=$arrived stable=$stable")
                assertEquals(1, arrived, "heading=$heading target=$target")
                assertTrue(stable >= 20)
                assertTrue(hypot(unit.position.x - target.x, unit.position.y - target.y) < 7.1)
            } finally { context.close() }
        }
    }

    @Test fun reverse_uses_strict_hundred_degree_gate_and_retarget_crosses_zero_with_inertia() {
        for ((heading, reverse) in listOf(350.0 to false, 349.0 to true)) {
            val context = MechTestScene.open(MechTestScene.scenario(heading = heading, position = Position(300.0, 200.0)))
            try {
                context.submit(MoveAction("east", "p1", "u1", Position(400.0, 200.0), context.world))
                context.advance(Step(0, 1.0, "sim-tick"))
                assertEquals(reverse, context.observePlayer(context.world, "p1").units.single().mechPose!!.reversing)
            } finally { context.close() }
        }
        val context = MechTestScene.open()
        try {
            context.submit(MoveAction("east", "p1", "u1", Position(500.0, 100.0), context.world))
            repeat(10) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val before = context.observe().units.single()
            assertEquals(0.56, before.speed, 1e-12)
            context.submit(MoveAction("west", "p1", "u1", Position(50.0, 100.0), context.world))
            val first = context.advance(Step(10, 1.0, "sim-tick")).units.single()
            assertTrue(first.mechPose!!.reversing)
            assertEquals(0.504, first.speed, 1e-12)
            assertTrue(first.position.x > before.position.x)
            repeat(15) { context.advance(Step(11L + it, 1.0, "sim-tick")) }
            assertTrue(context.observe().units.single().speed < 0.0)
        } finally { context.close() }
    }

    @Test fun a_reverse_start_routes_through_a_real_obstacle_gap_without_crossing_the_wall() {
        val wall = (0 until 20).filter { it != 10 }.map { 15 to it }.toSet()
        val context = MechTestScene.open(MechTestScene.scenario(heading = 270.0, blocked = wall))
        try {
            context.submit(MoveAction("gap", "p1", "u1", Position(500.0, 100.0), context.world))
            var arrivals = 0
            for (step in 0L..1500L) {
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                val unit = observation.units.single()
                assertFalse((unit.position.x / 20).toInt() to (unit.position.y / 20).toInt() in wall)
                arrivals += observation.events.count { it.kind == "movement-arrived" }
                if (arrivals == 1 && unit.speed == 0.0) break
            }
            println("MECH_GAP final=${context.observe().units.single()} arrivals=$arrivals")
            assertEquals(1, arrivals)
            assertEquals(0.0, context.observe().units.single().speed)
        } finally { context.close() }
    }

    @Test fun short_command_unreachable_replacement_and_map_boundary_keep_existing_land_rules() {
        val context = MechTestScene.open()
        try {
            context.submit(MoveAction("short", "p1", "u1", Position(106.0, 100.0), context.world))
            val result = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(Position(100.0, 100.0), result.units.single().position)
            assertEquals(null, result.units.single().moveTarget)
            assertTrue(result.events.any { it.kind == "movement-arrived" })
            val before = context.observe().units
            assertIs<ActionResult.Rejected>(context.submit(MoveAction("nan", "p1", "u1", Position(Double.NaN, 100.0), context.world)))
            val outside = assertIs<ActionResult.Accepted>(context.submit(MoveAction("outside", "p1", "u1", Position(0.0, 100.0), context.world)))
            assertEquals(listOf("movement-cancelled", "path-unreachable"), outside.events.map { it.kind }) // D-083.
            assertEquals(before, context.observe().units)
        } finally { context.close() }
        val wall = (0 until 20).map { 15 to it }.toSet()
        val blocked = MechTestScene.open(MechTestScene.scenario(blocked = wall))
        try {
            blocked.submit(MoveAction("old", "p1", "u1", Position(200.0, 100.0), blocked.world))
            blocked.advance(Step(0, 1.0, "sim-tick"))
            val result = assertIs<ActionResult.Accepted>(blocked.submit(MoveAction("sealed", "p1", "u1", Position(500.0, 100.0), blocked.world)))
            assertTrue(result.events.any { it.kind == "path-unreachable" })
            assertNull(blocked.observe().units.single().moveTarget)
            assertEquals(0.0, blocked.advance(Step(1, 1.0, "sim-tick")).units.single().speed)
        } finally { blocked.close() }
        val unit = MechTestScene.unit()
        val fast = MechTestScene.with(unit, movement = unit.movement.copy(moveSpeed = 60.0, moveAccelerationSpeed = 1.0, reverseSpeedPercentage = 0.0))
        val edge = MechTestScene.open(MechTestScene.scenario(fast, heading = 270.0, position = Position(20.0, 100.0)))
        try {
            edge.submit(MoveAction("edge", "p1", "u1", Position(13.0, 100.0), edge.world))
            val result = edge.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(Position(20.0, 100.0), result.units.single().position)
            assertEquals(MovementState.BLOCKED, result.units.single().movementState)
            assertTrue(result.events.any { it.kind == "movement-boundary-blocked" })
        } finally { edge.close() }
        assertIs<InitResult.Rejected>(SimulationContext.initialize(MechTestScene.scenario(position = Position(12.0, 100.0))))
    }

    @Test fun opposite_move_uses_signed_propulsion_without_turning_the_legs_frame_around() {
        val context = MechTestScene.open()
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("west", "p1", "u1", Position(50.0, 100.0), context.world)))
            context.advance(Step(0, 1.0, "sim-tick"))
            val unit = context.observePlayer(context.world, "p1").units.single()
            assertEquals(90.0, unit.heading)
            assertEquals(-0.056, unit.speed, 1e-12) // DEC signed -.07 proportion * .8 world speed.
            assertEquals(99.944, unit.position.x, 1e-12)
            assertTrue(assertNotNull(unit.mechPose).reversing)
        } finally { context.close() }
    }
}
