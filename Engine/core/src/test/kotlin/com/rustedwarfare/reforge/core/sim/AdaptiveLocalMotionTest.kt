package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.navigation.PlanningTerrain
import kotlin.math.hypot
import kotlin.test.*

class AdaptiveLocalMotionTest {
    private fun profile(v: Double, turn: Double, acceleration: Double) = MovementProfile(
        MovementType.LAND, v, .05, .02, turn, acceleration, false, false,
    )

    @Test
    fun invalid_step_preserves_route_and_pending_replacement_while_diagnostics_stay_observational() {
        val source = TestContentFactory.openScenario()
        val base = source.unitDefinitions.getValue("tank")
        fun open() = assertIs<InitResult.Accepted>(
            SimulationContext.initialize(
                source.copy(
                    map = MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600), BlockingGrid(30, 20, BooleanArray(600))),
                    unitDefinitions = mapOf("tank" to TestContentFactory.unitWith(base, movement = profile(1.1, 3.0, .01))),
                    units = listOf(UnitSpawn("u1", "p1", "tank", Position(150.0, 150.0), 90.0)),
                ),
            ),
        ).context
        val measured = open()
        val plain = open()
        try {
            measured.enableMotionDiagnostics(measured.world)
            for (c in listOf(measured, plain)) c.submit(MoveAction("old", "p1", "u1", Position(450.0, 250.0), c.world))
            for (q in 0L..50L) {
                if (q == 10L) {
                    for (c in listOf(measured, plain)) {
                        c.submit(StopAction("stop", "p1", "u1", c.world, atStep = q))
                        c.submit(MoveAction("new", "p1", "u1", Position(450.0, 100.0), c.world, atStep = q))
                        val before = c.observe()
                        assertFailsWith<IllegalArgumentException> { c.advance(Step(q, .5, "sim-tick")) }
                        assertSame(before, c.observe())
                    }
                }
                val a = measured.advance(Step(q, 1.0, "sim-tick"))
                val b = plain.advance(Step(q, 1.0, "sim-tick"))
                assertEquals(a.units, b.units)
                assertEquals(a.events, b.events)
                assertEquals(a.actionResults, b.actionResults)
                assertEquals(measured.observePlayer(measured.world, "p1").units, plain.observePlayer(plain.world, "p1").units)
                val trace = measured.drainMotionDiagnostics(measured.world).single()
                assertEquals(a.units.single().position, trace.finalPosition)
                if (q == 10L) {
                    assertTrue(a.actionResults.any { it.actionId == "stop" })
                    assertTrue(a.actionResults.any { it.actionId == "new" })
                    assertEquals(Position(450.0, 100.0), trace.steeringAim)
                }
            }
        } finally {
            measured.close()
            plain.close()
        }
    }

    @Test
    fun waypoint_route_adapter_computes_exact_polyline_distance_and_recovers_occluded_front_waypoint() {
        val base = TestContentFactory.openScenario()
        val blocked = BooleanArray(30 * 20) { idx -> idx == (5 * 30 + 6) } // obstacle at (6, 5) -> [120,140) x [100,120)
        val map = MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600), BlockingGrid(30, 20, blocked))
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(map = map))).context
        try {
            val hostField = SimulationContext::class.java.getDeclaredField("movementHost").apply { isAccessible = true }
            val host = hostField.get(ctx) as UnitMovementHost
            val u = host.units.getValue("u1")
            val terrain = PlanningTerrain.fromNoFog(map)

            u.position = Position(80.0, 80.0)
            u.heading = 90.0
            val p0 = Position(130.0, 110.0) // inside blocked cell (6,5) -> firstClear == false
            val p1 = Position(200.0, 80.0)  // clear horizontal leg along y=80 -> rejoins p1!
            val p2 = Position(200.0, 160.0)
            u.adoptRoute(listOf(p0, p1, p2), p2, -1L)

            val sample = WaypointRouteAdapter.sampleGuidance(host, u, terrain, p0, false, 1L)
            assertEquals(p1, sample.aimPosition)
            assertEquals(1, sample.waypointIndex)
            assertFalse(sample.finalApproach)
            assertEquals(hypot(200.0 - 80.0, 0.0) + hypot(0.0, 160.0 - 80.0), sample.remainingDistance, 1e-9)
        } finally {
            ctx.close()
        }
    }

    @Test
    fun different_motion_abilities_complete_a_turning_move_with_continuous_direct_steering() {
        for (p in listOf(profile(.55, 1.5, .005), profile(1.1, 3.0, .01), profile(2.2, 6.0, .04), profile(1.1, 4.1, 0.0), profile(2.2, 4.1, .25))) {
            val source = TestContentFactory.openScenario()
            val base = source.unitDefinitions.getValue("tank")
            val context = assertIs<InitResult.Accepted>(
                SimulationContext.initialize(
                    source.copy(
                        map = MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600), BlockingGrid(30, 20, BooleanArray(600))),
                        unitDefinitions = mapOf("tank" to TestContentFactory.unitWith(base, movement = p)),
                        units = listOf(UnitSpawn("u1", "p1", "tank", Position(150.0, 150.0), 90.0)),
                    ),
                ),
            ).context
            try {
                context.enableMotionDiagnostics(context.world)
                val goal = Position(450.0, 250.0)
                context.submit(MoveAction("move", "p1", "u1", goal, context.world))
                var arrived = false
                var stable = 0
                var previous = context.observe().units.single()
                for (q in 0L until 2500L) {
                    val observation = context.advance(Step(q, 1.0, "sim-tick"))
                    val now = observation.units.single()
                    val trace = context.drainMotionDiagnostics(context.world).single()
                    if (q == 0L) {
                        assertEquals(goal, trace.steeringAim)
                    }
                    assertTrue(now.position.x in 11.0..589.0 && now.position.y in 11.0..389.0)
                    if (observation.events.any { it.kind == "movement-arrived" }) arrived = true
                    assertTrue(observation.events.none { it.kind in setOf("path-unreachable", "movement-boundary-blocked") })
                    if (arrived) {
                        assertNull(now.moveTarget)
                        assertTrue(now.pathPoints.isEmpty())
                        assertEquals(previous.heading, now.heading)
                        assertEquals(0.0, now.angularVelocity)
                        assertEquals(0.0, now.speed)
                        stable = if (now.speed == 0.0 && now.position == previous.position) stable + 1 else 0
                        if (stable >= 19) break
                    }
                    previous = now
                }
                assertTrue(arrived, "No normal completion for $p")
                assertTrue(stable >= 19, "Missing stable tail for $p")
            } finally {
                context.close()
            }
        }
    }
}
