package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import java.nio.file.Path
import kotlin.test.*

class CornerCaptureTest {
    private val initialPath = listOf(Position(30.0, 30.0), Position(70.0, 30.0), Position(70.0, 50.0))

    private fun open(radius: Double, startY: Double, speed: Double, acceleration: Double, deceleration: Double,
                     capture: Double = 16.0, hiddenWall: Boolean = false, offsetRows: Int = 0): SimulationContext {
        val height = 5 + offsetRows
        val offset = offsetRows * 20.0
        val tank = UnitDefinition("tank", "CustomUnitMetadata", radius, radius, 1, Path.of("owned-unused.png"), 1.0,
            MovementProfile(MovementType.LAND, speed, acceleration, deceleration, 4.1, 0.25, false, false), emptySet())
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(Scenario("capture-owned",
            MapDefinition(5, height, 20, 20, emptyList(), IntArray(5 * height), BlockingGrid(5, height,
                BooleanArray(5 * height) { it == 12 + 5 * offsetRows || (hiddenWall && it == 8 + 5 * offsetRows) })), mapOf("tank" to tank),
            listOf(Player("p1", PlayerControl.LOCAL)), listOf(UnitSpawn("u1", "p1", "tank", Position(30.0, startY), 0.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(capture, 4.0)))).context
        context.enableKnowledge(context.world, KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        context.acceptVisibleTerrain(context.world, "p1", listOf(VisibleTerrainCell(2, 2 + offsetRows, true)))
        context.enablePathDiagnostics(context.world)
        context.submit(MoveAction("move", "p1", "u1", Position(70.0, 50.0 + offset), context.world))
        assertEquals(initialPath.map { it.copy(y = it.y + offset) }, context.observe().units.single().pathPoints)
        assertEquals(1, context.drainPathDiagnostics(context.world).single().actualSearchCalls)
        return context
    }

    @Test fun cp01_keeps_unsafe_corner_then_consumes_it_when_the_actual_shortcut_is_clear() {
        val context = open(3.0, 46.0, 1.1, 0.07, 0.17)
        try {
            val literalPositions = mapOf(0L to 45.93, 1L to 45.79, 12L to 39.63, 13L to 38.65, 14L to 37.60)
            for (step in 0L..14L) {
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                val unit = observation.units.single()
                assertEquals(30.0, unit.position.x)
                literalPositions[step]?.let { assertEquals(it, unit.position.y, 1e-9) }
                assertEquals(if (step < 14L) initialPath else initialPath.drop(1), unit.pathPoints)
                assertEquals(Position(70.0, 50.0), unit.moveTarget)
                assertTrue(observation.events.none { it.kind in setOf("movement-arrived", "movement-blocked") })
                assertTrue(context.drainPathDiagnostics(context.world).isEmpty())
                println("CP01,$step,${unit.position},${unit.speed},${unit.heading},${unit.pathPoints}")
            }
        } finally { context.close() }
    }

    @Test fun cp03_consumes_at_most_one_point_and_does_not_read_unknown_truth_for_capture() {
        val first = open(1.0, 70.0, 50.0, 50.0, 50.0, 100.0, offsetRows = 1)
        val second = open(1.0, 70.0, 50.0, 50.0, 50.0, 100.0, true, 1)
        val path = initialPath.map { it.copy(y = it.y + 20.0) }
        // From (30,20), both remaining corners are within capture range and both
        // shortcuts clear the known wall: minimum distance is 100/sqrt(4100) > 1.
        assertTrue(100.0 / kotlin.math.sqrt(4100.0) > 1.0)
        assertTrue(path.all { kotlin.math.hypot(it.x - 30.0, it.y - 20.0) < 100.0 })
        try {
            for (step in 0L..1L) {
                val a = first.advance(Step(step, 1.0, "sim-tick"))
                val b = second.advance(Step(step, 1.0, "sim-tick"))
                assertEquals(a.units, b.units)
                val unit = a.units.single()
                assertEquals(Position(30.0, 20.0), unit.position)
                assertEquals(if (step == 0L) 50.0 else 0.0, unit.speed)
                assertEquals(if (step == 0L) 0.0 else 359.75, unit.heading)
                assertEquals(if (step == 0L) path else path.drop(1), unit.pathPoints)
                assertEquals(Position(70.0, 70.0), unit.moveTarget)
                assertTrue(a.events.none { it.kind in setOf("movement-arrived", "movement-blocked") })
                assertTrue(first.drainPathDiagnostics(first.world).isEmpty())
                assertTrue(second.drainPathDiagnostics(second.world).isEmpty())
                println("CP03,$step,${unit.position},${unit.speed},${unit.heading},${unit.pathPoints}")
            }
        } finally { first.close(); second.close() }
    }

    @Test fun cp02_strict_sixteen_boundary_keeps_a_safe_corner_until_the_following_step() {
        val tank = UnitDefinition("tank", "CustomUnitMetadata", 8.0, 8.0, 1, Path.of("owned-unused.png"), 1.0,
            MovementProfile(MovementType.LAND, 8.0, 8.0, 8.0, 4.1, 0.25, false, false), emptySet())
        val path = listOf(Position(130.0, 30.0), Position(130.0, 50.0))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(Scenario("capture-strict-owned",
            MapDefinition(7, 5, 20, 20, emptyList(), IntArray(35), BlockingGrid(7, 5, BooleanArray(35) { it == 17 })),
            mapOf("tank" to tank), listOf(Player("p1", PlayerControl.LOCAL)),
            listOf(UnitSpawn("u1", "p1", "tank", Position(50.0, 30.0), 90.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0)))).context
        try {
            context.enablePathDiagnostics(context.world)
            context.submit(MoveAction("move", "p1", "u1", Position(130.0, 50.0), context.world))
            assertEquals(path, context.observe().units.single().pathPoints)
            context.drainPathDiagnostics(context.world)
            for (step in 0L..9L) {
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                val unit = observation.units.single()
                assertEquals(Position(58.0 + 8.0 * step, 30.0), unit.position)
                assertEquals(8.0, unit.speed)
                assertEquals(90.0, unit.heading)
                assertEquals(if (step < 9L) path else path.drop(1), unit.pathPoints)
                assertEquals(Position(130.0, 50.0), unit.moveTarget)
                assertTrue(observation.events.none { it.kind in setOf("movement-arrived", "movement-blocked") })
                assertTrue(context.drainPathDiagnostics(context.world).isEmpty())
                println("CP02,$step,${unit.position},${unit.speed},${unit.heading},${unit.pathPoints}")
            }
        } finally { context.close() }
    }
}
