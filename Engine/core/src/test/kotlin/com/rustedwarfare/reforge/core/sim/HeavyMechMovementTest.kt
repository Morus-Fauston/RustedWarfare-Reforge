package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.test.*

class HeavyMechMovementTest {
    @Test fun discovering_a_ground_wall_repairs_the_route_instead_of_flying_through_it() {
        val knowledge = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false)
        val wall = (0 until 30).map { 15 to it }.toSet()
        val context = HeavyMechTestScene.open(HeavyMechTestScene.scenario(heading = 90.0, blocked = wall).copy(automaticVision = knowledge))
        val open = HeavyMechTestScene.open(HeavyMechTestScene.scenario(heading = 90.0).copy(automaticVision = knowledge))
        try {
            for (world in listOf(context, open)) assertIs<ActionResult.Accepted>(world.submit(
                MoveAction("east", "p1", "heavy", Position(600.0, 200.0), world.world)))
            assertEquals(open.observe().units.single().pathPoints, context.observe().units.single().pathPoints,
                "An unseen wall must not change the knowledge-based initial path")
            var detour = false
            for (q in 0L..599L) {
                val observation = context.advance(Step(q, 1.0, "sim-tick"))
                open.advance(Step(q, 1.0, "sim-tick"))
                val unit = observation.units.single()
                assertTrue(unit.position.x < 300.0, "An OVER_CLIFF sliding unit cannot cross the sealed wall")
                assertFalse((unit.position.x / 20).toInt() to (unit.position.y / 20).toInt() in wall)
                assertNull(unit.flightPose)
                detour = detour || unit.pathPoints.any { kotlin.math.abs(it.y - 200.0) > 40.0 }
            }
            assertTrue(assertNotNull(context.observePlayer(context.world, "p1").knowledge).planningBlocked(15, 10))
            assertTrue(detour, "Discovering part of a wall repairs the path around its known portion")
            assertTrue(open.observe().units.single().position.x > 500.0)
            assertEquals(CloseResult.Closed, context.close())
            assertIs<InitResult.Accepted>(context.reopen(HeavyMechTestScene.scenario(heading = 90.0, blocked = wall)))
            val sealed = assertIs<ActionResult.Accepted>(context.submit(
                MoveAction("known-wall", "p1", "heavy", Position(600.0, 200.0), context.world)))
            assertTrue(sealed.events.any { it.kind == "path-unreachable" })
            assertNull(context.observe().units.single().moveTarget)
            assertEquals(Position(200.0, 200.0), context.advance(Step(0, 1.0, "sim-tick")).units.single().position)
        } finally { context.close(); open.close() }
    }

    @Test fun cliff_sliding_moves_before_body_alignment_and_stop_keeps_the_inertial_tail() {
        val context = HeavyMechTestScene.open()
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("east", "p1", "heavy", Position(600.0, 200.0), context.world)))
            val first = context.advance(Step(0, 1.0, "sim-tick")).units.single()
            // Fixed equal-direction witness from the qualified generic sliding rule, B=.07.
            assertEquals(200.0987, first.position.x, 1e-10)
            assertEquals(200.0, first.position.y, 1e-10)
            assertEquals(.5, first.heading, 1e-10)
            assertEquals(.0987, first.speed, 1e-10)
            assertEquals(0.0, first.height)
            assertNull(first.flightPose)
            for (step in 1L..9L) context.advance(Step(step, 1.0, "sim-tick"))
            val moving = context.observe().units.single()
            assertEquals(.61, moving.speed, 1e-10)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "heavy", context.world)))
            val stopped = context.observe().units.single()
            assertNull(stopped.moveTarget)
            assertEquals(moving.position, stopped.position)
            assertEquals(moving.speed, stopped.speed)
            val coast = context.advance(Step(10, 1.0, "sim-tick")).units.single()
            assertEquals(.3703, coast.speed, 1e-10)
            assertEquals(moving.position.x + .3703, coast.position.x, 1e-10)
            assertEquals(moving.position.y, coast.position.y, 1e-10)
            assertEquals(MovementState.DECELERATING, coast.movementState)
            val lastCoast = context.advance(Step(11, 1.0, "sim-tick")).units.single()
            assertEquals(.1306, lastCoast.speed, 1e-10)
            val idle = context.advance(Step(12, 1.0, "sim-tick")).units.single()
            assertEquals(0.0, idle.speed)
            assertEquals(lastCoast.position, idle.position)
            assertEquals(MovementState.IDLE, idle.movementState)
            assertEquals(idle.position, context.advance(Step(13, 1.0, "sim-tick")).units.single().position)
        } finally { context.close() }
    }
}
