package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import kotlin.test.*

class LocalMotionFollowingTest {
    @Test fun internal_diagnostic_reads_are_immutable_and_do_not_create_or_consume_motion_state() {
        val context = open()
        val world = context.world
        try {
            context.submit(MoveAction("read", "p1", "u1", Position(100.0, 300.0), world))
            val before = context.observe()
            assertTrue(context.localMotionSnapshots(world).isEmpty())
            assertSame(before, context.observe())
            context.advance(Step(0, 1.0, "sim-tick"))
            val after = context.observe()
            val first = context.localMotionSnapshots(world)
            assertEquals("read", first.getValue("u1").commandId)
            assertFailsWith<UnsupportedOperationException> { (first as MutableMap).clear() }
            assertEquals(first, context.localMotionSnapshots(world))
            assertSame(after, context.observe())
            context.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(Position(100.0, 100.0), first.getValue("u1").origin)
        } finally { context.close() }
        assertFailsWith<com.rustedwarfare.reforge.core.path.PathDiagnosticsException> { context.localMotionSnapshots(world) }
        context.reopen(TestContentFactory.openScenario())
        try { assertFailsWith<com.rustedwarfare.reforge.core.path.PathDiagnosticsException> { context.localMotionSnapshots(world) } }
        finally { context.close() }
    }

    @Test fun fixed_local_aims_cover_short_long_and_zero_length_segments_without_a_capacity_cutoff() {
        val expected = mapOf(
            0.0 to listOf(0.0), 15.0 to listOf(15.0), 40.0 to listOf(20.0, 40.0),
            80.0 to listOf(20.0, 40.0, 60.0, 80.0),
            100.0 to listOf(40.0, 60.0, 80.0, 100.0),
            150.0 to listOf(40.0, 60.0, 80.0, 100.0, 120.0, 150.0),
            200.0 to listOf(40.0, 60.0, 80.0, 100.0, 120.0, 140.0, 160.0, 180.0, 200.0),
        )
        for ((length, distances) in expected) {
            val first = LocalMotionSegment.create(Position(50.0, 50.0), Position(50.0, 50.0 + length))
            var cursor: LocalMotionSegment? = first
            for ((i, distance) in distances.withIndex()) {
                val point = assertNotNull(cursor)
                assertEquals(Position(50.0, 50.0 + distance), point.aim)
                assertEquals(i == distances.lastIndex, point.isLast)
                cursor = point.next()
            }
            assertNull(cursor)
            // Consuming another cursor must leave the generation-time first aim intact.
            assertEquals(Position(50.0, 50.0 + distances.first()), first.aim)
        }
        var long = LocalMotionSegment.create(Position(0.0, 0.0), Position(5000.0, 0.0))
        repeat(119) { long = assertNotNull(long.next()) }
        assertEquals(Position(2420.0, 0.0), long.aim)
        assertFalse(long.isLast)
    }

    private fun open(heading: Double = 125.0, maximumSpeed: Double = 1.1,
                     start: Position = Position(100.0, 100.0), wall: Int? = null): SimulationContext {
        val source = TestContentFactory.openScenario()
        val tank = source.unitDefinitions.getValue("tank")
        return assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
            map = MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600), BlockingGrid(30, 20, BooleanArray(600) { it == wall })),
            unitDefinitions = mapOf("tank" to TestContentFactory.unitWith(tank,
                movement = tank.movement.copy(moveSpeed = maximumSpeed, moveAccelerationSpeed = .07,
                    moveDecelerationSpeed = .17, turnAcceleration = 0.0))),
            units = listOf(UnitSpawn("u1", "p1", "tank", start, heading)), automaticVision = null,
        ))).context
    }

    @Test fun actual_collision_history_survives_stop_and_clears_after_three_unmodified_steps() {
        val context = open(90.0, 1.1, Position(150.0, 110.0), 158)
        try {
            context.enableKnowledge(context.world, com.rustedwarfare.reforge.core.knowledge.KnowledgeRules(
                com.rustedwarfare.reforge.core.knowledge.TerrainRetention.MEMORY,
                com.rustedwarfare.reforge.core.knowledge.PathInformationSource.PLAYER_KNOWLEDGE, false))
            context.submit(MoveAction("into-hidden", "p1", "u1", Position(250.0, 110.0), context.world))
            var step = 0L
            while (step < 40L) {
                val observation = context.advance(Step(step++, 1.0, "sim-tick"))
                if (observation.events.any { it.kind == "movement-blocked" }) break
            }
            assertTrue(step < 40L)
            assertEquals(1.0, context.localMotionSnapshots(context.world).getValue("u1").correctionTime)
            context.submit(StopAction("stop", "p1", "u1", context.world))
            context.submit(MoveAction("away", "p1", "u1", Position(50.0, 110.0), context.world))
            val first = context.advance(Step(step++, 1.0, "sim-tick"))
            assertTrue(first.events.any { it.kind == "movement-blocked" })
            var expectedHistory = 2.0
            assertEquals(expectedHistory, context.localMotionSnapshots(context.world).getValue("u1").correctionTime)
            var clear = 0
            repeat(12) {
                val observation = context.advance(Step(step++, 1.0, "sim-tick"))
                if (observation.events.any { it.kind == "movement-blocked" }) { expectedHistory += 1.0; clear = 0 }
                else { clear++; if (clear >= 3) expectedHistory = 0.0 }
                assertEquals(expectedHistory, context.localMotionSnapshots(context.world).getValue("u1").correctionTime)
            }
            assertTrue(clear >= 3)
            assertEquals(0.0, expectedHistory)
        } finally { context.close() }
    }

    @Test fun positive_propulsion_timeout_advances_a_slow_units_fixed_local_aim_after_two_hundred_q() {
        val context = open(90.0, .1)
        try {
            context.submit(MoveAction("slow", "p1", "u1", Position(300.0, 100.0), context.world))
            repeat(200) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val before = context.localMotionSnapshots(context.world).getValue("u1")
            assertEquals(Position(140.0, 100.0), before.aim)
            assertTrue(context.observe().units.single().position.x < 124.0)
            context.advance(Step(200, 1.0, "sim-tick"))
            assertEquals(Position(160.0, 100.0), context.localMotionSnapshots(context.world).getValue("u1").aim)
            assertEquals(listOf(Position(300.0, 100.0)), context.observe().units.single().pathPoints)
        } finally { context.close() }
    }

    @Test fun zero_maximum_world_speed_does_not_erase_positive_propulsion_fraction() {
        val context = open(90.0, 0.0)
        try {
            context.submit(MoveAction("zero", "p1", "u1", Position(300.0, 100.0), context.world))
            val unit = context.advance(Step(0, 1.0, "sim-tick")).units.single()
            assertEquals(0.0, unit.speed); assertEquals(Position(100.0, 100.0), unit.position)
            assertEquals(1.0, context.localMotionSnapshots(context.world).getValue("u1").activeTime)
        } finally { context.close() }
    }

    @Test fun long_straight_routes_use_the_local_distance_for_the_first_propulsion_gate() {
        for (length in listOf(80.0, 100.0, 150.0, 200.0)) {
            val context = open()
            try {
                val goal = Position(100.0, 100.0 + length)
                context.submit(MoveAction("move", "p1", "u1", goal, context.world))
                assertEquals(listOf(goal), context.observe().units.single().pathPoints)
                // The first aim is 20 or 40wu away: the ordinary 46-degree gate rejects
                // this initial 55-degree error. Using the global goal wrongly selects 89.
                val first = context.advance(Step(0, 1.0, "sim-tick")).units.single()
                assertEquals(Position(100.0, 100.0), first.position, "length=$length")
                assertEquals(0.0, first.speed, "length=$length")
                assertEquals(listOf(goal), first.pathPoints)
            } finally { context.close() }
        }
    }

    @Test fun replacing_a_move_and_stopping_do_not_reuse_the_previous_local_segment() {
        val used = open()
        try {
            used.submit(MoveAction("old", "p1", "u1", Position(100.0, 300.0), used.world))
            val before = used.advance(Step(0, 1.0, "sim-tick")).units.single()
            assertEquals(0.0, before.speed)
            used.submit(StopAction("stop", "p1", "u1", used.world))
            val fresh = open(before.heading)
            try {
                val replacement = Position(300.0, 100.0)
                used.submit(MoveAction("new", "p1", "u1", replacement, used.world))
                fresh.submit(MoveAction("new", "p1", "u1", replacement, fresh.world))
                for (q in 0L..25L) {
                    val a = used.advance(Step(q + 1, 1.0, "sim-tick")).units.single()
                    val b = fresh.advance(Step(q, 1.0, "sim-tick")).units.single()
                    assertEquals(b, a, "replacement step=$q")
                }
            } finally { fresh.close() }
        } finally { used.close() }
    }
}
