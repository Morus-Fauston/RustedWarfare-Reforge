package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import kotlin.test.*

class LocalMotionFollowingTest {
    private fun open(
        heading: Double = 125.0,
        maximumSpeed: Double = 1.1,
        start: Position = Position(100.0, 100.0),
        wall: Int? = null,
    ): SimulationContext {
        val source = TestContentFactory.openScenario()
        val tank = source.unitDefinitions.getValue("tank")
        return assertIs<InitResult.Accepted>(
            SimulationContext.initialize(
                source.copy(
                    map = MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600), BlockingGrid(30, 20, BooleanArray(600) { it == wall })),
                    unitDefinitions = mapOf(
                        "tank" to TestContentFactory.unitWith(
                            tank,
                            movement = tank.movement.copy(
                                moveSpeed = maximumSpeed,
                                moveAccelerationSpeed = .07,
                                moveDecelerationSpeed = .17,
                                turnAcceleration = 0.0,
                            ),
                        ),
                    ),
                    units = listOf(UnitSpawn("u1", "p1", "tank", start, heading)),
                    automaticVision = null,
                ),
            ),
        ).context
    }

    @Test
    fun motion_diagnostics_world_guards_and_observational_parity() {
        val context = open()
        val world = context.world
        try {
            context.enableMotionDiagnostics(world)
            context.submit(MoveAction("read", "p1", "u1", Position(100.0, 300.0), world))
            val before = context.observe()
            assertTrue(context.drainMotionDiagnostics(world).isEmpty())
            assertSame(before, context.observe())
            context.advance(Step(0, 1.0, "sim-tick"))
            val after = context.observe()
            val samples = context.drainMotionDiagnostics(world)
            assertEquals("read", samples.single().originMoveId)
            assertFailsWith<UnsupportedOperationException> { (samples as MutableList<MotionStepSample>).clear() }
            assertSame(after, context.observe())
        } finally {
            context.close()
        }
        assertFailsWith<MotionDiagnosticsException> { context.drainMotionDiagnostics(world) }
        context.reopen(TestContentFactory.openScenario())
        try {
            assertFailsWith<MotionDiagnosticsException> { context.drainMotionDiagnostics(world) }
        } finally {
            context.close()
        }
    }

    @Test
    fun actual_collision_history_survives_stop_and_clears_after_three_unmodified_steps() {
        val context = open(90.0, 1.1, Position(150.0, 110.0), 158)
        try {
            context.enableMotionDiagnostics(context.world)
            context.enableKnowledge(
                context.world,
                com.rustedwarfare.reforge.core.knowledge.KnowledgeRules(
                    com.rustedwarfare.reforge.core.knowledge.TerrainRetention.MEMORY,
                    com.rustedwarfare.reforge.core.knowledge.PathInformationSource.PLAYER_KNOWLEDGE,
                    false,
                ),
            )
            context.submit(MoveAction("into-hidden", "p1", "u1", Position(250.0, 110.0), context.world))
            var step = 0L
            var hitTrace: MotionStepSample? = null
            while (step < 40L) {
                val observation = context.advance(Step(step++, 1.0, "sim-tick"))
                val trace = context.drainMotionDiagnostics(context.world).single()
                if (observation.events.any { it.kind == "movement-blocked" }) {
                    hitTrace = trace
                    break
                }
            }
            assertNotNull(hitTrace)
            assertEquals(1.0, hitTrace.correctionTimeAfter)
            context.submit(StopAction("stop", "p1", "u1", context.world))
            context.submit(MoveAction("away", "p1", "u1", Position(50.0, 110.0), context.world))
            val first = context.advance(Step(step++, 1.0, "sim-tick"))
            assertTrue(first.events.any { it.kind == "movement-blocked" })
            var expectedHistory = 2.0
            assertEquals(expectedHistory, context.drainMotionDiagnostics(context.world).single().correctionTimeAfter)
            var clear = 0
            repeat(12) {
                val observation = context.advance(Step(step++, 1.0, "sim-tick"))
                val trace = context.drainMotionDiagnostics(context.world).single()
                if (observation.events.any { it.kind == "movement-blocked" }) {
                    expectedHistory += 1.0
                    clear = 0
                } else {
                    clear++
                    if (clear >= 3) expectedHistory = 0.0
                }
                assertEquals(expectedHistory, trace.correctionTimeAfter)
            }
            assertTrue(clear >= 3)
            assertEquals(0.0, expectedHistory)
        } finally {
            context.close()
        }
    }

    @Test
    fun zero_maximum_world_speed_rejects_without_creating_motion_state() {
        val context = open(90.0, 0.0)
        try {
            val rejected = assertIs<ActionResult.Rejected>(
                context.submit(MoveAction("zero", "p1", "u1", Position(300.0, 100.0), context.world)),
            )
            assertEquals("immobile-unit", rejected.error.code)
            val unit = context.advance(Step(0, 1.0, "sim-tick")).units.single()
            assertEquals(0.0, unit.speed)
            assertEquals(Position(100.0, 100.0), unit.position)
            assertNull(unit.moveTarget)
            assertTrue(unit.pathPoints.isEmpty())
        } finally {
            context.close()
        }
    }

    @Test
    fun continuous_waypoint_aim_steers_directly_at_full_leg_endpoint_without_20px_breadcrumbs() {
        for (length in listOf(80.0, 100.0, 150.0, 200.0)) {
            val context = open(90.0, 1.1)
            try {
                context.enableMotionDiagnostics(context.world)
                val goal = Position(100.0 + length, 100.0)
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "u1", goal, context.world)))
                assertTrue(context.observe().units.single().pathPoints.isEmpty(), "Planning belongs to advance")
                val first = context.advance(Step(0, 1.0, "sim-tick")).units.single()
                val trace = context.drainMotionDiagnostics(context.world).single()
                assertEquals(goal, trace.steeringAim, "WaypointRouteAdapter must aim directly at full leg endpoint without 20px breadcrumbs")
                assertEquals(listOf(goal), first.pathPoints)
            } finally {
                context.close()
            }
        }
    }

    @Test
    fun replacing_a_move_and_stopping_do_not_leak_previous_route_state() {
        val used = open()
        try {
            used.submit(MoveAction("old", "p1", "u1", Position(100.0, 140.0), used.world))
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
            } finally {
                fresh.close()
            }
        } finally {
            used.close()
        }
    }
}
